package services;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigValueFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.Application;
import play.inject.guice.GuiceApplicationBuilder;
import play.libs.Json;
import play.libs.ws.WSClient;
import play.mvc.Http;
import play.mvc.Result;
import play.test.Helpers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** Real Play forms and HTTP client, synthetic backend; never sends email. */
public final class StaffInvitationFailureTest {
    private HttpServer backend;
    private ExecutorService workers;
    private Application app;
    private StaffSessionStore.ActiveSession session;
    private String sessionId;
    private final AtomicInteger invitations = new AtomicInteger();
    private final AtomicInteger refreshes = new AtomicInteger();
    private volatile int inviteStatus = 200;
    private volatile long inviteDelay = 800;
    private volatile int refreshStatus = 503;

    @Before public void start() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool();
        backend.setExecutor(workers);
        backend.createContext("/api/v1/admin/me", exchange -> respond(exchange, 200,
                "{\"userId\":\"00000000-0000-0000-0000-000000000001\",\"role\":\"STAFF_LEAD\",\"documents\":false}", 0));
        backend.createContext("/api/v1/admin/staff/invitations", exchange -> {
            invitations.incrementAndGet();
            assertNotNull(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            respond(exchange, inviteStatus, inviteStatus == 200 ? "{\"state\":\"INVITED\"}"
                    : "{\"detail\":\"Use a separate staff email; an existing account will not be promoted.\"}", inviteDelay);
        });
        backend.createContext("/api/v1/admin/staff", exchange -> respond(exchange, 200, "{\"items\":[]}", 0));
        backend.createContext("/api/v1/staff/auth/refresh", exchange -> {
            refreshes.incrementAndGet();
            respond(exchange, refreshStatus, "{\"detail\":\"Staff authentication is temporarily unavailable.\"}", 0);
        });
        backend.start();
        app = new GuiceApplicationBuilder().configure(Map.of(
                "makao.api.baseUrl", "http://127.0.0.1:" + backend.getAddress().getPort(),
                "makao.api.requestTimeout", "300ms", "makao.api.authRequestTimeout", "5s")).build();
        Helpers.start(app);
        activate(false);
    }

    @After public void stop() {
        if (app != null) Helpers.stop(app);
        if (backend != null) backend.stop(0);
        if (workers != null) workers.shutdownNow();
    }

    @Test public void delayedInvitationCompletesThroughTheRealCsrfProtectedForm() {
        Result result = submit();
        assertEquals(303, result.status());
        assertEquals("Staff invitation sent.", result.flash().get("success").orElseThrow());
        assertEquals(1, invitations.get());
    }

    @Test public void existingAccountRejectionIsShownWithoutRetrying() {
        inviteStatus = 409;
        inviteDelay = 0;
        Result result = submit();
        assertEquals(303, result.status());
        assertTrue(result.flash().get("error").orElseThrow().contains("separate staff email"));
        assertEquals(1, invitations.get());
    }

    @Test public void ambiguousInvitationTimeoutIsNotRetriedOrReportedAsSuccess() throws Exception {
        Config config = app.injector().instanceOf(Config.class)
                .withValue("makao.api.authRequestTimeout", ConfigValueFactory.fromAnyRef("500ms"));
        var client = new StaffApiClient(app.injector().instanceOf(WSClient.class), config);
        var result = client.post(session, "/api/v1/admin/staff/invitations", Json.newObject(), UUID.randomUUID())
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(504, result.status());
        assertTrue(result.detail().contains("may have completed"));
        assertEquals(1, invitations.get());
    }

    @Test public void temporaryRefreshFailureDoesNotClaimTheSessionExpiredOrSendInvitation() throws Exception {
        activate(true);
        var response = app.injector().instanceOf(StaffApiClient.class)
                .post(session, "/api/v1/admin/staff/invitations", Json.newObject(), UUID.randomUUID())
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(503, response.status());
        assertEquals(1, refreshes.get());
        assertEquals(0, invitations.get());
        assertTrue(app.injector().instanceOf(StaffSessionStore.class).active(sessionId).isPresent());
    }

    @Test public void invalidRefreshStillRequiresSignInAndDoesNotSendInvitation() throws Exception {
        activate(true);
        refreshStatus = 401;
        var response = app.injector().instanceOf(StaffApiClient.class)
                .post(session, "/api/v1/admin/staff/invitations", Json.newObject(), UUID.randomUUID())
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(401, response.status());
        assertEquals(1, refreshes.get());
        assertEquals(0, invitations.get());
    }

    @Test public void invitationWithoutCsrfNeverReachesBackend() {
        Result result = Helpers.route(app, new Http.RequestBuilder().method("POST").uri("/staff/team/invitations")
                .header("Cookie", "makao_staff_web=forged").session("staffSession", sessionId).bodyForm(Map.of("email", "reviewer@example.invalid",
                        "role", "VERIFICATION_REVIEWER", "reason", "Assigned document review")));
        assertEquals(403, result.status());
        assertEquals(0, invitations.get());
    }

    @Test public void stoppedBackendReportsConnectionFailureWithoutSendingAnInvitation() throws Exception {
        backend.stop(0);
        backend = null;
        var response = app.injector().instanceOf(StaffApiClient.class)
                .post(session, "/api/v1/admin/staff/invitations", Json.newObject(), UUID.randomUUID())
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(503, response.status());
        assertTrue(response.detail().contains("cannot reach"));
        assertEquals(0, invitations.get());
    }

    private void activate(boolean expiredAccess) {
        var store = app.injector().instanceOf(StaffSessionStore.class);
        sessionId = store.activate(Json.newObject().put("accessToken", "synthetic-access")
                .put("refreshToken", "synthetic-refresh").put("userId", "synthetic-staff")
                .put("accessTokenExpiresAt", Instant.now().plusSeconds(expiredAccess ? -1 : 300).toString())
                .put("refreshTokenExpiresAt", Instant.now().plusSeconds(3600).toString()));
        session = store.active(sessionId).orElseThrow();
    }

    private Result submit() {
        Result page = Helpers.route(app, new Http.RequestBuilder().method("GET").uri("/staff/team")
                .session("staffSession", sessionId));
        assertEquals(200, page.status());
        var match = Pattern.compile("name=\"csrfToken\" value=\"([^\"]+)\"").matcher(Helpers.contentAsString(page));
        assertTrue(match.find());
        return Helpers.route(app, new Http.RequestBuilder().method("POST").uri("/staff/team/invitations")
                .session("staffSession", sessionId).cookie(page.cookie("makao_staff_csrf").orElseThrow())
                .bodyForm(Map.of("csrfToken", match.group(1), "email", "reviewer@example.invalid",
                        "role", "VERIFICATION_REVIEWER", "documentReview", "true", "reason", "Assigned document review")));
    }

    private static void respond(HttpExchange exchange, int status, String body, long delay) throws IOException {
        try {
            exchange.getRequestBody().readAllBytes();
            if (delay > 0) Thread.sleep(delay);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        finally { exchange.close(); }
    }
}
