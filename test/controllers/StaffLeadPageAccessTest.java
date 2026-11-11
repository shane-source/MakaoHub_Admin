package controllers;

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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** Real Play routes with synthetic API responses; never sends email or changes live accounts. */
public final class StaffLeadPageAccessTest {
    private static final String ID="00000000-0000-0000-0000-000000000001";
    private static final String BOOKING="00000000-0000-0000-0000-000000000002";
    private Application app;
    private HttpServer backend;
    private String session;
    private volatile String role="STAFF_LEAD";
    private final Set<String> reads=ConcurrentHashMap.newKeySet();
    private final AtomicInteger releases=new AtomicInteger();
    private final AtomicReference<String> releaseKey=new AtomicReference<>();
    private final AtomicInteger destinations=new AtomicInteger();

    @Before public void start() throws Exception {
        backend=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        backend.createContext("/api/v1/admin/", exchange -> {
            String path=exchange.getRequestURI().getPath(); reads.add(path);
            if(path.equals("/api/v1/admin/payments/owners/"+ID+"/test-recipient")) {
                destinations.incrementAndGet();
                releaseKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            }
            if (path.equals("/api/v1/admin/payments/bookings/"+BOOKING+"/release")) {
                releases.incrementAndGet();
                releaseKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                byte[] response="{\"status\":\"ACTIVE\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                try { exchange.sendResponseHeaders(200,response.length); exchange.getResponseBody().write(response); }
                finally { exchange.close(); }
                return;
            }
            String body=switch(path) {
                case "/api/v1/admin/me" -> "{\"userId\":\""+ID+"\",\"role\":\""+role+"\",\"documents\":false}";
                case "/api/v1/admin/verifications" -> page("{\"user_id\":\""+ID+"\",\"full_name\":\"Backend applicant\",\"role\":\"LANDLORD\",\"status\":\"PENDING\"}");
                case "/api/v1/admin/listings" -> page("{\"id\":\""+ID+"\",\"title\":\"Backend listing\",\"status\":\"DRAFT\",\"moderation_status\":\"PENDING\"}");
                case "/api/v1/admin/flagged-messages" -> page("{\"id\":\""+ID+"\",\"kind\":\"RENTAL\",\"masked_body\":\"Backend masked message\"}");
                case "/api/v1/admin/reports" -> page("{\"id\":\""+ID+"\",\"subject_kind\":\"LANDLORD_LISTING\",\"subject_id\":\""+ID+"\",\"safe_description\":\"Backend report\"}");
                case "/api/v1/admin/listings/LANDLORD/"+ID -> "{\"id\":\""+ID+"\",\"title\":\"Backend listing\",\"status\":\"DRAFT\",\"moderation_status\":\"PENDING\",\"media\":[]}";
                case "/api/v1/admin/bookings" -> page("{\"owner_id\":\""+ID+"\", \"id\":\""+BOOKING+"\",\"status\":\"PAID_HELD\",\"currency\":\"KES\",\"total_amount_minor\":8400000,\"rent_amount_minor\":4000000,\"deposit_amount_minor\":4000000,\"move_in_confirmed_at\":\"2026-10-28T00:00:00Z\",\"owner_move_in_confirmed_at\":\"2026-10-28T01:00:00Z\",\"release_eligible_at\":\"2026-10-29T00:00:00Z\",\"owner_release_requested_at\":\"2026-10-29T01:00:00Z\",\"owner_name\":\"David Kamau\",\"renter_name\":\"Wanjiku Mwangi\",\"release_ready\":true}");
                default -> page("");
            };
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        backend.start();
        app=new GuiceApplicationBuilder().configure("makao.api.baseUrl","http://127.0.0.1:"+backend.getAddress().getPort()).build();
        Helpers.start(app);
        session=app.injector().instanceOf(StaffSessionStore.class).activate(Json.newObject()
                .put("userId",ID).put("accessToken","synthetic-access").put("refreshToken","synthetic-refresh")
                .put("accessTokenExpiresAt",Instant.now().plusSeconds(300).toString())
                .put("refreshTokenExpiresAt",Instant.now().plusSeconds(3600).toString()));
    }

    @After public void stop() {
        if(app!=null) Helpers.stop(app);
        if(backend!=null) backend.stop(0);
    }

    @Test public void staffLeadCanOpenEverySidebarRouteAndEachLoadsItsApi() {
        for(String path:new String[]{"verifications","listings","flagged-messages","reports","payments","audit","team","activity"}) {
            String html=get("/staff/"+path);
            for(String link:new String[]{"verifications","listings","flagged-messages","reports","payments","audit","team","activity"}) {
                assertTrue(html.contains("href=\"/staff/"+link+"\""));
            }
        }
        assertTrue(reads.containsAll(Set.of("/api/v1/admin/me","/api/v1/admin/verifications","/api/v1/admin/listings",
                "/api/v1/admin/flagged-messages","/api/v1/admin/reports","/api/v1/admin/payments","/api/v1/admin/bookings",
                "/api/v1/admin/audit","/api/v1/admin/staff","/api/v1/admin/activity")));
    }

    @Test public void leadCanOpenVerificationAndModerateListingsWithoutSafetyDecisions() {
        String verifications=get("/staff/verifications");
        assertTrue(verifications.contains("Backend applicant"));
        assertTrue(verifications.contains("Open case"));
        String listing=get("/staff/listings/LANDLORD/"+ID);
        assertTrue(listing.contains("Backend listing"));
        assertTrue(listing.contains("Approve listing for publication"));
        assertTrue(listing.contains("Reject and request changes"));
        String flags=get("/staff/flagged-messages");
        assertTrue(flags.contains("Backend masked message"));
        assertFalse(flags.contains("Confirm flag and warn user"));
        String reports=get("/staff/reports");
        assertTrue(reports.contains("Backend report"));
        assertFalse(reports.contains("Resolve selected report"));
        role="SAFETY_MODERATOR";
        assertTrue(get("/staff/listings/LANDLORD/"+ID).contains("Approve listing for publication"));
        assertTrue(get("/staff/flagged-messages").contains("Confirm flag and warn user"));
        assertTrue(get("/staff/reports").contains("Resolve selected report"));
    }

    @Test public void listingQueueLinksToLandlordAndSublessorReviewStates() {
        String html=get("/staff/listings");
        assertTrue(html.contains("?kind=LANDLORD&amp;state=PENDING"));
        assertTrue(html.contains("?kind=SUBLESSOR&amp;state=PENDING"));
        assertTrue(html.contains("?kind=LANDLORD&amp;state=APPROVED"));
        assertTrue(html.contains("?kind=SUBLESSOR&amp;state=REJECTED"));
    }

    @Test public void onlyStaffLeadCanConfigureDestinationAndNoPageOffersManualRelease() {
        role="PAYMENT_REVIEWER";
        Result page=Helpers.route(app,new Http.RequestBuilder().method("GET").uri("/staff/payments").session("staffSession",session));
        String html=Helpers.contentAsString(page);
        assertFalse(html.contains("Approve TEST release"));
        assertTrue(html.contains("fresh renter True response starts a five-minute wait"));
        assertFalse(html.contains("24-hour"));
        assertTrue(html.contains("No real money moves"));
        assertTrue(html.contains("Owner: David Kamau"));
        assertTrue(html.contains("Renter: Wanjiku Mwangi"));
        assertFalse(html.contains("<th>Booking</th>"));
        assertTrue(html.contains("2026-10-29T01:00:00Z"));
        assertFalse(html.contains("Set up TEST payout destination"));
        role="STAFF_LEAD";
        page=Helpers.route(app,new Http.RequestBuilder().method("GET").uri("/staff/payments").session("staffSession",session));
        html=Helpers.contentAsString(page);
        assertTrue(html.contains("Set up TEST payout destination"));
        var csrf=Pattern.compile("name=\"csrfToken\" value=\"([^\"]+)\"").matcher(html);
        assertTrue(csrf.find());
        String action="/staff/payments/owners/"+ID+"/test-recipient";

        Result missingCsrf=Helpers.route(app,new Http.RequestBuilder().method("POST").uri(action)
                .header("Accept","text/html")
                .header("Content-Type","application/x-www-form-urlencoded")
                .header("Cookie","makao_staff_csrf="+page.cookie("makao_staff_csrf").orElseThrow().value())
                .session("staffSession",session)
                .cookie(page.cookie("makao_staff_csrf").orElseThrow())
                .bodyForm(Map.of("recipientCode","RCP_synthetic","ownerDestinationConfirmed","true")));
        assertEquals(403,missingCsrf.status());
        assertEquals(0,releases.get());

        Result released=Helpers.route(app,new Http.RequestBuilder().method("POST").uri(action)
                .header("Content-Type","application/x-www-form-urlencoded")
                .header("Cookie","makao_staff_csrf="+page.cookie("makao_staff_csrf").orElseThrow().value())
                .session("staffSession",session)
                .cookie(page.cookie("makao_staff_csrf").orElseThrow())
                .bodyForm(Map.of("csrfToken",csrf.group(1),"recipientCode","RCP_synthetic","ownerDestinationConfirmed","true")));
        assertEquals(303,released.status());
        assertEquals("/staff/payments",released.redirectLocation().orElseThrow());
        assertEquals(1,destinations.get());assertEquals(0,releases.get());
        assertNotNull(releaseKey.get());

        role="PAYMENT_REVIEWER";
        Result unauthorizedAttempt=Helpers.route(app,new Http.RequestBuilder().method("POST").uri(action)
                .header("Content-Type","application/x-www-form-urlencoded")
                .header("Cookie","makao_staff_csrf="+page.cookie("makao_staff_csrf").orElseThrow().value())
                .session("staffSession",session)
                .cookie(page.cookie("makao_staff_csrf").orElseThrow())
                .bodyForm(Map.of("csrfToken",csrf.group(1),"recipientCode","RCP_synthetic","ownerDestinationConfirmed","true")));
        assertEquals(403,unauthorizedAttempt.status());
        assertEquals(1,destinations.get());assertEquals(0,releases.get());
    }

    private String get(String path) {
        var result=Helpers.route(app,new Http.RequestBuilder().method("GET").uri(path).session("staffSession",session));
        assertEquals(path,200,result.status());
        assertTrue(result.header("Cache-Control").orElse("").contains("no-store"));
        return Helpers.contentAsString(result);
    }

    private static String page(String item) { return "{\"items\":["+item+"],\"page\":0,\"hasNext\":false}"; }
}
