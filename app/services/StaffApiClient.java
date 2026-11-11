package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.typesafe.config.Config;
import javax.inject.Inject;
import javax.inject.Singleton;
import play.libs.Json;
import play.libs.ws.WSClient;
import play.libs.ws.WSRequest;
import play.libs.ws.WSResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

@Singleton
public final class StaffApiClient {
    private static final Logger log = LoggerFactory.getLogger(StaffApiClient.class);
    private static final Duration EXPIRY_SKEW = Duration.ofSeconds(30);
    private final WSClient ws;
    private final String baseUrl;
    private final Duration timeout;
    private final Duration authTimeout;

    @Inject
    public StaffApiClient(WSClient ws, Config config) {
        this.ws = ws;
        this.baseUrl = stripTrailingSlash(config.getString("makao.api.baseUrl"));
        URI backend = URI.create(baseUrl);
        if (backend.getHost() == null || !("https".equalsIgnoreCase(backend.getScheme()) || isLocal(backend))) {
            throw new IllegalArgumentException("Makao API base URL must be HTTPS or a local development URL.");
        }
        this.timeout = config.getDuration("makao.api.requestTimeout");
        this.authTimeout = config.getDuration("makao.api.authRequestTimeout");
        if (authTimeout.isZero() || authTimeout.isNegative() || authTimeout.compareTo(timeout) < 0) {
            throw new IllegalArgumentException("Authentication timeout must be positive and at least the API request timeout.");
        }
    }

    public CompletionStage<ApiResponse> login(String email, String password) {
        return postPublic("/api/v1/staff/auth/login", Json.newObject()
                .put("email", email)
                .put("password", password));
    }

    public CompletionStage<ApiResponse> verify(String challengeId, String code) {
        return postPublic("/api/v1/staff/auth/verify-otp", Json.newObject()
                .put("challengeId", challengeId)
                .put("code", code));
    }

    public CompletionStage<ApiResponse> requestRecovery(String email) {
        return postPublic("/api/v1/staff/auth/forgot-password", Json.newObject().put("email", email));
    }

    public CompletionStage<ApiResponse> resetPassword(String email, String code, String password) {
        return postPublic("/api/v1/staff/auth/reset-password", Json.newObject()
                .put("email", email)
                .put("code", code)
                .put("newPassword", password));
    }

    public CompletionStage<ApiResponse> acceptInvitation(String email, String code, String fullName, String password) {
        return postPublic("/api/v1/staff/auth/accept-invitation", Json.newObject()
                .put("email", email)
                .put("invitationCode", code)
                .put("fullName", fullName)
                .put("password", password));
    }

    public CompletionStage<ApiResponse> get(StaffSessionStore.ActiveSession session, String path) {
        return authorized(session, token -> request(path).addHeader("Authorization", "Bearer " + token).get(), true);
    }

    public CompletionStage<ApiResponse> getBinary(StaffSessionStore.ActiveSession session, String path) {
        return authorizedBinary(session, token -> request(path)
                // Private file reads include storage transfer and an audited database commit.
                .setRequestTimeout(authTimeout)
                .addHeader("Authorization", "Bearer " + token)
                .get(), true);
    }

    public CompletionStage<ApiResponse> post(StaffSessionStore.ActiveSession session, String path, JsonNode body, UUID key) {
        return authorized(session, token -> request(path)
                // Creating an invitation includes synchronous SMTP delivery, like sign-in.
                .setRequestTimeout("/api/v1/admin/staff/invitations".equals(path) ? authTimeout : timeout)
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Idempotency-Key", key.toString())
                .post(body), true);
    }

    public CompletionStage<ApiResponse> logout(StaffSessionStore.ActiveSession session) {
        return currentToken(session, false).thenCompose(token -> request("/api/v1/staff/auth/logout")
                .addHeader("Authorization", "Bearer " + token)
                .post(JsonNodeFactory.instance.objectNode())
                .thenApply(this::toResponse));
    }

    private CompletionStage<ApiResponse> authorized(StaffSessionStore.ActiveSession session,
            Function<String, CompletionStage<WSResponse>> operation, boolean retry) {
        return currentToken(session, false).thenCompose(operation).thenCompose(response -> {
            if (response.getStatus() == 401 && retry) {
                return currentToken(session, true).thenCompose(operation).thenApply(this::toResponse);
            }
            return CompletableFuture.completedFuture(toResponse(response));
        }).exceptionally(failure -> apiFailure(failure));
    }

    private CompletionStage<ApiResponse> authorizedBinary(StaffSessionStore.ActiveSession session,
            Function<String, CompletionStage<WSResponse>> operation, boolean retry) {
        return currentToken(session, false).thenCompose(operation).thenCompose(response -> {
            if (response.getStatus() == 401 && retry) {
                return currentToken(session, true).thenCompose(operation).thenApply(this::toBinaryResponse);
            }
            return CompletableFuture.completedFuture(toBinaryResponse(response));
        }).exceptionally(this::apiFailure);
    }

    private CompletionStage<String> currentToken(StaffSessionStore.ActiveSession session, boolean force) {
        if (!force && session.accessExpiresAt().isAfter(Instant.now().plus(EXPIRY_SKEW))) {
            return CompletableFuture.completedFuture(session.accessToken());
        }
        synchronized (session) {
            CompletionStage<String> running = session.refreshInFlight();
            if (running != null) return running;
            var body = Json.newObject().put("refreshToken", session.refreshToken());
            CompletionStage<String> created = postPublic("/api/v1/staff/auth/refresh", body).thenCompose(response -> {
                if (!response.success()) {
                    return CompletableFuture.failedFuture(response.status() == 401 || response.status() == 403
                            ? new SessionExpiredException() : new ApiRequestException(response));
                }
                session.rotate(response.json());
                return CompletableFuture.completedFuture(session.accessToken());
            });
            session.refreshInFlight(created);
            created.whenComplete((value, failure) -> session.clearRefresh(created));
            return created;
        }
    }

    private CompletionStage<ApiResponse> postPublic(String path, JsonNode body) {
        return request(path).setRequestTimeout(authTimeout).post(body).thenApply(this::toResponse)
                .exceptionally(failure -> requestFailure("authentication", failure));
    }

    private ApiResponse requestFailure(String operation, Throwable failure) {
        boolean timedOut = false;
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 8; depth++) {
            if (cause instanceof TimeoutException || cause instanceof java.net.http.HttpTimeoutException) {
                timedOut = true;
                break;
            }
            if (cause.getCause() == cause) break;
            cause = cause.getCause();
        }
        // Fixed categories only; never log paths, tokens, exceptions or request bodies.
        log.warn("Staff API request failed: operation={}, kind={}", operation, timedOut ? "timeout" : "connection");
        return timedOut
                ? ApiResponse.problem(504, "The request took too long. It may have completed. Check the result before retrying; for an invitation, check the recipient's inbox.")
                : ApiResponse.problem(503, "The website cannot reach the Makao Hub backend. Make sure the backend is running before trying again.");
    }

    private WSRequest request(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")) {
            throw new IllegalArgumentException("Backend path must be absolute and local to the configured API.");
        }
        return ws.url(baseUrl + path)
                .addHeader("Accept", "application/json")
                .addHeader("Cache-Control", "no-store")
                .setRequestTimeout(timeout);
    }

    private ApiResponse toResponse(WSResponse response) {
        JsonNode body;
        try {
            body = response.getBody().isBlank() ? JsonNodeFactory.instance.objectNode() : response.asJson();
        } catch (RuntimeException ignored) {
            body = JsonNodeFactory.instance.objectNode().put("detail", "The Makao Hub server returned an unreadable response.");
        }
        return new ApiResponse(response.getStatus(), body, null, response.getContentType());
    }

    private ApiResponse toBinaryResponse(WSResponse response) {
        if (response.getStatus() < 200 || response.getStatus() >= 300) return toResponse(response);
        return new ApiResponse(response.getStatus(), JsonNodeFactory.instance.objectNode(), response.asByteArray(), response.getContentType());
    }

    private ApiResponse apiFailure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof SessionExpiredException) {
                return ApiResponse.problem(401, "Your staff session expired. Sign in again.");
            }
            if (current instanceof ApiRequestException requestFailure) return requestFailure.response;
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return requestFailure("protected", failure);
    }

    private static String stripTrailingSlash(String value) {
        String result = value.strip();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static boolean isLocal(URI uri) {
        String host = uri.getHost();
        return "http".equalsIgnoreCase(uri.getScheme()) && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host));
    }

    public record ApiResponse(int status, JsonNode json, byte[] bytes, String contentType) {
        public boolean success() { return status >= 200 && status < 300; }
        public String detail() {
            String detail = json == null ? "" : json.path("detail").asText("").strip();
            return detail.isEmpty() ? "The request could not be completed." : detail;
        }
        public static ApiResponse problem(int status, String detail) {
            return new ApiResponse(status, Json.newObject().put("detail", detail), null, "application/problem+json");
        }
    }

    public static final class SessionExpiredException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SessionExpiredException() { super("Staff session expired."); }
    }

    private static final class ApiRequestException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final ApiResponse response;

        private ApiRequestException(ApiResponse response) {
            super("Staff API request did not complete.");
            this.response = response;
        }
    }
}
