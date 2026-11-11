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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public final class StaffAuthenticationTimeoutTest {
    private HttpServer backend;
    private ExecutorService workers;
    private Application app;
    private final AtomicInteger loginCalls = new AtomicInteger();
    private final AtomicInteger verificationCalls = new AtomicInteger();
    private volatile boolean rejectLogin;
    private static final String ACCESS = "synthetic-access-token";
    private static final String REFRESH = "synthetic-refresh-token";

    @Before
    public void start() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool();
        backend.setExecutor(workers);
        backend.createContext("/test-ready", exchange -> respond(exchange, 200, "{}", 0));
        backend.createContext("/api/v1/staff/auth/login", exchange -> {
            loginCalls.incrementAndGet();
            if (rejectLogin) {
                respond(exchange, 429, "{\"detail\":\"Too many verification codes were requested. Try again later.\"}", 0);
            } else {
                respond(exchange, 200, Json.newObject()
                        .put("challengeId", "00000000-0000-0000-0000-000000000001")
                        .put("purpose", "LOGIN")
                        .put("maskedEmail", "••••@example.invalid")
                        .put("expiresAt", Instant.now().plusSeconds(300).toString()).toString(), 800);
            }
        });
        backend.createContext("/api/v1/staff/auth/verify-otp", exchange -> {
            verificationCalls.incrementAndGet();
            respond(exchange, 200, Json.newObject()
                    .put("accessToken", ACCESS).put("refreshToken", REFRESH)
                    .put("userId", "00000000-0000-0000-0000-000000000002")
                    .put("fullName", "Synthetic Staff")
                    .put("accessTokenExpiresAt", Instant.now().plusSeconds(300).toString())
                    .put("refreshTokenExpiresAt", Instant.now().plusSeconds(3600).toString()).toString(), 800);
        });
        backend.start();
        app = new GuiceApplicationBuilder().configure(Map.of(
                "makao.api.baseUrl", "http://127.0.0.1:" + backend.getAddress().getPort(),
                "makao.api.requestTimeout", "150ms",
                "makao.api.authRequestTimeout", "5s")).build();
        Helpers.start(app);
    }

    @After
    public void stop() {
        if (app != null) Helpers.stop(app);
        if (backend != null) backend.stop(0);
        if (workers != null) workers.shutdownNow();
    }

    @Test
    public void delayedLoginAndOtpStillReachAnActiveServerSideSession() {
        Result loginPage = Helpers.route(app, new Http.RequestBuilder().method("GET").uri("/staff/sign-in"));
        Http.Cookie csrfCookie = loginPage.cookie("makao_staff_csrf").orElseThrow();
        Result login = Helpers.route(app, new Http.RequestBuilder().method("POST").uri("/staff/sign-in")
                .cookie(csrfCookie).bodyForm(Map.of("csrfToken", csrf(loginPage),
                        "email", "staff@example.invalid", "password", "SyntheticPassword!")));
        assertEquals(303, login.status());
        assertEquals("/staff/verify", login.redirectLocation().orElseThrow());
        String flow = login.session().get("staffAuthFlow").orElseThrow();
        StaffSessionStore sessions = app.injector().instanceOf(StaffSessionStore.class);
        assertTrue(sessions.challenge(flow).isPresent());
        assertTrue(sessions.active(flow).isEmpty());

        Result otpPage = Helpers.route(app, new Http.RequestBuilder().method("GET").uri("/staff/verify")
                .cookie(csrfCookie).session(login.session().data()));
        assertEquals(200, otpPage.status());
        Result verified = Helpers.route(app, new Http.RequestBuilder().method("POST").uri("/staff/verify")
                .cookie(csrfCookie).session(login.session().data())
                .bodyForm(Map.of("csrfToken", csrf(otpPage), "code", "123456")));
        assertEquals(303, verified.status());
        assertEquals("/staff", verified.redirectLocation().orElseThrow());
        String active = verified.session().get("staffSession").orElseThrow();
        assertTrue(sessions.active(active).isPresent());
        assertTrue(sessions.challenge(flow).isEmpty());
        assertFalse(verified.session().data().toString().contains(ACCESS));
        assertFalse(verified.session().data().toString().contains(REFRESH));
        assertEquals(1, loginCalls.get());
        assertEquals(1, verificationCalls.get());
    }

    @Test
    public void genuineTimeoutDoesNotResendTheLoginRequest() throws Exception {
        // Warm the HTTP client's connection before measuring a very short response deadline.
        app.injector().instanceOf(WSClient.class).url("http://127.0.0.1:" + backend.getAddress().getPort() + "/test-ready")
                .get().toCompletableFuture().get(10, TimeUnit.SECONDS);
        Config config = app.injector().instanceOf(Config.class)
                .withValue("makao.api.authRequestTimeout", ConfigValueFactory.fromAnyRef("250ms"));
        var client = new StaffApiClient(app.injector().instanceOf(WSClient.class), config);
        var response = client.login("staff@example.invalid", "SyntheticPassword!")
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(504, response.status());
        assertTrue(response.detail().contains("may have completed"));
        assertEquals(1, loginCalls.get());
        assertEquals(0, verificationCalls.get());
    }

    @Test
    public void backendRateLimitIsPreservedWithoutCreatingAnotherChallenge() throws Exception {
        rejectLogin = true;
        var response = app.injector().instanceOf(StaffApiClient.class)
                .login("staff@example.invalid", "SyntheticPassword!")
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(429, response.status());
        assertTrue(response.detail().contains("Too many verification codes"));
        assertEquals(1, loginCalls.get());
        assertEquals(0, verificationCalls.get());
    }

    private static String csrf(Result result) {
        var match = Pattern.compile("name=\"csrfToken\" value=\"([^\"]+)\"").matcher(Helpers.contentAsString(result));
        assertTrue("The real form must contain a CSRF token", match.find());
        return match.group(1);
    }

    private static void respond(HttpExchange exchange, int status, String body, long delayMillis) throws IOException {
        try {
            exchange.getRequestBody().readAllBytes();
            if (delayMillis > 0) Thread.sleep(delayMillis);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }
}
