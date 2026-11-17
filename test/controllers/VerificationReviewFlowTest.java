package controllers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.Application;
import play.inject.guice.GuiceApplicationBuilder;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;
import play.test.Helpers;
import services.StaffSessionStore;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigValueFactory;
import play.libs.ws.WSClient;
import services.StaffApiClient;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** Synthetic evidence only: exercises the real Play controller, proxy and CSRF form. */
public final class VerificationReviewFlowTest {
    private static final String USER="00000000-0000-0000-0000-000000000001";
    private static final String DOCUMENT="00000000-0000-0000-0000-000000000002";
    private Application app;
    private HttpServer backend;
    private String session;
    private volatile boolean viewed, reviewed;
    private volatile String staffRole="VERIFICATION_REVIEWER";
    private volatile String state="PENDING";
    private volatile String contentType="image/png";
    private volatile int fileStatus=200;
    private volatile long fileDelay;
    private byte[] fileBytes;
    private final AtomicInteger reviewCalls=new AtomicInteger();
    private final AtomicInteger decisionCalls=new AtomicInteger();

    @Before public void start() throws IOException {
        var image=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",image);
        fileBytes=image.toByteArray();
        backend=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        backend.createContext("/api/v1/admin/me", e->reply(e,200,
                "{\"userId\":\"00000000-0000-0000-0000-000000000003\",\"role\":\""+staffRole+"\",\"documents\":true}",
                "application/json"));
        backend.createContext("/api/v1/admin/verifications/", e->{
            String path=e.getRequestURI().getPath();
            if(path.endsWith("/decision")) {
                decisionCalls.incrementAndGet();
                var request=Json.parse(e.getRequestBody().readAllBytes());
                state=request.path("approve").asBoolean() ? "VERIFIED" : "REJECTED";
                reply(e,200,"{\"state\":\""+state+"\"}","application/json");
            } else if(path.endsWith("/review")) {
                reviewCalls.incrementAndGet();
                if(viewed) { reviewed=true; reply(e,200,"{\"state\":\"REVIEWED\"}","application/json"); }
                else reply(e,409,"{\"detail\":\"View the current document before marking it reviewed.\"}","application/problem+json");
            } else if(path.contains("/documents/")) {
                try { if(fileDelay>0) Thread.sleep(fileDelay); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); e.close(); return; }
                if(fileStatus==200) {
                    viewed=true;
                    reply(e,200,fileBytes,contentType);
                } else reply(e,fileStatus,"{\"detail\":\"The stored evidence is invalid or unavailable.\"}","application/problem+json");
            } else {
                var document=Json.newObject().put("id",DOCUMENT).put("document_type","ID_FRONT")
                        .put("content_type",contentType).put("size_bytes",fileBytes.length).put("status","READY");
                if(viewed) document.put("viewed_at","2026-01-01T00:00:00Z");
                if(reviewed) document.put("reviewed_at","2026-01-01T00:00:00Z");
                String role=path.split("/")[5];
                var body=Json.newObject().put("status",state).put("role",role)
                        .put("updated_at","2026-01-01T00:00:00Z");
                body.putArray("documents").add(document);
                reply(e,200,body.toString(),"application/json");
            }
        });
        backend.start();
        app=new GuiceApplicationBuilder().configure("makao.api.baseUrl","http://127.0.0.1:"+backend.getAddress().getPort()).build();
        Helpers.start(app);
        session=app.injector().instanceOf(StaffSessionStore.class).activate(Json.newObject()
                .put("userId",USER).put("accessToken","synthetic-access").put("refreshToken","synthetic-refresh")
                .put("accessTokenExpiresAt",Instant.now().plusSeconds(300).toString())
                .put("refreshTokenExpiresAt",Instant.now().plusSeconds(3600).toString()));
    }

    @After public void stop() {
        if(app!=null) Helpers.stop(app);
        if(backend!=null) backend.stop(0);
    }

    @Test public void everyRoleRequiresViewingBeforeMarkingAndAcknowledgementStaysSeparate() {
        for(String role:new String[]{"LANDLORD","SUBLESSOR","RENTER"}) {
            viewed=false; reviewed=false;
            String url="/staff/verifications/"+role+"/"+USER;
            Result initial=get(url);
            assertFalse(disabled(Helpers.contentAsString(initial)));
            assertTrue(Helpers.contentAsString(initial).contains("Refresh review status"));
            assertTrue(Helpers.contentAsString(initial).contains("href=\"#verification-decision\""));
            assertTrue(Helpers.contentAsString(initial).contains("id=\"verification-decision\""));
            assertTrue(Helpers.contentAsString(initial).contains("value=\"false\" type=\"submit\">Reject with reason"));
            assertFalse(Helpers.contentAsString(initial).contains("target=\"_blank\""));
            Result evidence=get(url+"/documents/"+DOCUMENT);
            assertEquals(200,evidence.status());
            String viewer=Helpers.contentAsString(evidence);
            assertTrue(viewer.contains("Back to review"));
            assertTrue(viewer.contains("src=\""+url+"/documents/"+DOCUMENT+"/file\""));
            assertFalse(viewed);
            assertTrue(evidence.header("Cache-Control").orElseThrow().contains("no-store"));
            Result file=get(url+"/documents/"+DOCUMENT+"/file");
            assertEquals("image/png",file.contentType().orElseThrow());
            try { assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(Helpers.contentAsBytes(file).toArray()))); }
            catch (IOException invalidImage) { throw new AssertionError(invalidImage); }
            Result afterView=get(url);
            assertFalse(disabled(Helpers.contentAsString(afterView)));
            assertFalse(reviewed);
            var csrf=Pattern.compile("name=\"csrfToken\" value=\"([^\"]+)\"").matcher(Helpers.contentAsString(afterView));
            assertTrue(csrf.find());
            Result marked=Helpers.route(app,new Http.RequestBuilder().method("POST").uri(url+"/documents/"+DOCUMENT+"/review")
                    .session("staffSession",session).cookie(afterView.cookie("makao_staff_csrf").orElseThrow())
                    .bodyForm(Map.of("csrfToken",csrf.group(1))));
            assertEquals(303,marked.status());
            assertTrue(reviewed);
            assertFalse(Helpers.contentAsString(get(url)).contains(">Mark reviewed</button>"));
        }
        assertEquals(3,reviewCalls.get());
    }

    @Test public void staffLeadSeesDocumentReviewAndVerificationDecisionControls() {
        staffRole="STAFF_LEAD";
        String html=Helpers.contentAsString(get("/staff/verifications/LANDLORD/"+USER));
        assertTrue(html.contains(">Mark reviewed</button>"));
        assertTrue(html.contains("Approve role verification"));
        assertTrue(html.contains("Reject with reason"));
    }

    @Test public void finishedCaseCannotOfferAnotherReviewAcknowledgement() {
        viewed=true; state="VERIFIED";
        String html=Helpers.contentAsString(get("/staff/verifications/LANDLORD/"+USER));
        assertTrue(disabled(html));
        assertFalse(html.contains("Reject with reason"));
        assertFalse(html.contains("href=\"#verification-decision\""));
        assertEquals(0,reviewCalls.get());
    }

    @Test public void rejectButtonSendsAnExplicitNegativeDecisionWithCsrfAndReason() {
        String url="/staff/verifications/LANDLORD/"+USER;
        Result page=get(url);
        String html=Helpers.contentAsString(page);
        var csrf=Pattern.compile("name=\"csrfToken\" value=\"([^\"]+)\"").matcher(html);
        assertTrue(csrf.find());

        Result rejected=Helpers.route(app,new Http.RequestBuilder().method("POST").uri(url+"/decision")
                .session("staffSession",session).cookie(page.cookie("makao_staff_csrf").orElseThrow())
                .bodyForm(Map.of(
                        "csrfToken",csrf.group(1),
                        "approve","false",
                        "expectedUpdatedAt","2026-01-01T00:00:00Z",
                        "reason","Ownership proof is unreadable"
                )));

        assertEquals(303,rejected.status());
        assertEquals("/staff/verifications",rejected.redirectLocation().orElseThrow());
        assertEquals(1,decisionCalls.get());
        assertEquals("REJECTED",state);
        assertEquals(0,reviewCalls.get());
        assertFalse(Helpers.contentAsString(get(url)).contains("Reject with reason"));
    }

    @Test public void applicantNamesComeFromTheApiRatherThanTheDesignReference() {
        backend.createContext("/api/v1/admin/verifications", e->reply(e,200,"""
                {"items":[{"user_id":"00000000-0000-0000-0000-000000000001",
                "full_name":"Registered landlord & account","role":"LANDLORD","status":"PENDING",
                "submitted_at":"2026-01-01T00:00:00Z"}],"hasNext":false}
                ""","application/json"));
        String body=Helpers.contentAsString(get("/staff/verifications?role=LANDLORD"));
        assertTrue(body.contains("Registered landlord &amp; account"));
        assertTrue(body.contains("data-label=\"Role\">LANDLORD"));
        assertFalse(body.contains("Wanjiku"));
        assertFalse(body.contains("Njeri Wambui"));
        assertFalse(body.contains("VER-3041"));
    }

    @Test public void pdfHasSameTabOpenWithoutEmbeddingOrDownload() {
        contentType="application/pdf";
        fileBytes="%PDF-1.4\nsynthetic-transport-fixture".getBytes(StandardCharsets.US_ASCII);
        String path="/staff/verifications/LANDLORD/"+USER+"/documents/"+DOCUMENT;
        String viewer=Helpers.contentAsString(get(path));
        assertTrue(viewer.contains("Open PDF"));
        assertTrue(viewer.contains("Downloads are disabled"));
        assertTrue(viewer.contains("Back to review"));
        assertFalse(viewer.contains("<iframe"));
        assertFalse(viewer.contains("<object"));
        assertFalse(viewer.contains("target=\"_blank\""));
        assertFalse(viewed);
        Result file=get(path+"/file");
        assertEquals("application/pdf",file.contentType().orElseThrow());
        assertTrue(file.header("Content-Disposition").orElseThrow().endsWith(".pdf"));
        assertTrue("Raw file CSP: "+file.header("Content-Security-Policy").orElse("missing"),
                file.header("Content-Security-Policy").orElseThrow().contains("sandbox"));
        assertArrayEquals(fileBytes,Helpers.contentAsBytes(file).toArray());
        assertFalse(reviewed);
    }

    @Test public void fileRejectionKeepsItsExplanationAndDoesNotEnableAcknowledgement() {
        fileStatus=400;
        Result failure=Helpers.route(app,new Http.RequestBuilder().method("GET")
                .uri("/staff/verifications/LANDLORD/"+USER+"/documents/"+DOCUMENT+"/file")
                .session("staffSession",session));
        assertEquals(400,failure.status());
        assertTrue(failure.header("Content-Security-Policy").orElseThrow().contains("sandbox"));
        assertTrue(Helpers.contentAsString(failure).contains("The stored evidence is invalid or unavailable."));
        assertFalse(viewed);
        assertFalse(reviewed);
    }

    @Test public void activeDocumentContentIsNotServedAndAnonymousViewerRequiresSignIn() {
        contentType="image/svg+xml";
        String path="/staff/verifications/LANDLORD/"+USER+"/documents/"+DOCUMENT;
        Result rejected=Helpers.route(app,new Http.RequestBuilder().method("GET").uri(path).session("staffSession",session));
        assertEquals(404,rejected.status());
        assertFalse(viewed);
        Result anonymous=Helpers.route(app,new Http.RequestBuilder().method("GET").uri(path));
        assertEquals(303,anonymous.status());
        assertEquals("/staff/sign-in",anonymous.redirectLocation().orElseThrow());
        Result rawAnonymous=Helpers.route(app,new Http.RequestBuilder().method("GET").uri(path+"/file"));
        assertEquals(303,rawAnonymous.status());
        assertTrue(rawAnonymous.header("Content-Security-Policy").orElseThrow().contains("sandbox"));
    }

    @Test public void privateFileCanOutlastOrdinaryReadDeadline() throws Exception {
        fileDelay=800;
        Config config=app.injector().instanceOf(Config.class)
                .withValue("makao.api.requestTimeout",ConfigValueFactory.fromAnyRef("150ms"))
                .withValue("makao.api.authRequestTimeout",ConfigValueFactory.fromAnyRef("5s"));
        var client=new StaffApiClient(app.injector().instanceOf(WSClient.class),config);
        var active=app.injector().instanceOf(StaffSessionStore.class).active(session).orElseThrow();
        var response=client.getBinary(active,"/api/v1/admin/verifications/LANDLORD/"+USER+"/documents/"+DOCUMENT)
                .toCompletableFuture().get(10,TimeUnit.SECONDS);
        assertEquals(200,response.status());
        assertArrayEquals(fileBytes,response.bytes());
        assertFalse(reviewed);
    }

    private Result get(String path) {
        Result result=Helpers.route(app,new Http.RequestBuilder().method("GET").uri(path).session("staffSession",session));
        assertEquals(200,result.status());
        return result;
    }

    private static boolean disabled(String html) {
        return Pattern.compile("<button[^>]*disabled[^>]*>Mark reviewed</button>").matcher(html).find();
    }

    private static void reply(HttpExchange exchange,int status,String text,String type) throws IOException {
        reply(exchange,status,text.getBytes(StandardCharsets.UTF_8),type);
    }

    private static void reply(HttpExchange exchange,int status,byte[] bytes,String type) throws IOException {
        try {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type",type);
            exchange.sendResponseHeaders(status,bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally { exchange.close(); }
    }
}
