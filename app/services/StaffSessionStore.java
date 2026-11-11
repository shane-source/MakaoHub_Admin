package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.typesafe.config.Config;
import javax.inject.Inject;
import javax.inject.Singleton;
import play.Environment;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public final class StaffSessionStore {
    private static final int RANDOM_BYTES = 32;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, PendingChallenge> pending = new ConcurrentHashMap<>();
    private final Map<String, ActiveSession> active = new ConcurrentHashMap<>();
    private final Duration staffLifetime;

    @Inject
    public StaffSessionStore(Config config, Environment environment) {
        this.staffLifetime = Duration.ofHours(config.getLong("makao.staff.sessionHours"));
        URI api = URI.create(config.getString("makao.api.baseUrl"));
        if (environment.isProd() && !"https".equalsIgnoreCase(api.getScheme())) {
            throw new IllegalStateException("Production staff API traffic requires HTTPS.");
        }
        if (environment.isProd() && !config.getBoolean("play.http.session.secure")) {
            throw new IllegalStateException("Production staff cookies require the Secure flag.");
        }
        String playSecret = config.getString("play.http.secret.key");
        if (environment.isProd() && (playSecret.isBlank() || "changeme".equals(playSecret))) {
            throw new IllegalStateException("Production requires a unique PLAY_SECRET.");
        }
    }

    public String saveChallenge(JsonNode response) {
        String challengeId = response.path("challengeId").asText("");
        Instant expiresAt = parseInstant(response.path("expiresAt").asText(), Instant.EPOCH);
        if (challengeId.isBlank() || challengeId.length() > 512 || !expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("Invalid staff authentication challenge response.");
        }
        String id = newId();
        pending.put(id, new PendingChallenge(
                challengeId,
                response.path("maskedEmail").asText("your staff email"),
                expiresAt
        ));
        cleanup();
        return id;
    }

    public Optional<PendingChallenge> challenge(String id) {
        if (id == null) return Optional.empty();
        PendingChallenge found = pending.get(id);
        if (found == null || !found.expiresAt().isAfter(Instant.now())) {
            pending.remove(id);
            return Optional.empty();
        }
        return Optional.of(found);
    }

    public void deleteChallenge(String id) {
        if (id != null) pending.remove(id);
    }

    public String activate(JsonNode tokens) {
        String id = newId();
        Instant now = Instant.now();
        Instant refreshExpires = parseInstant(tokens.path("refreshTokenExpiresAt").asText(), now);
        String accessToken = tokens.path("accessToken").asText("");
        String refreshToken = tokens.path("refreshToken").asText("");
        String userId = tokens.path("userId").asText("");
        if (accessToken.isBlank() || accessToken.length() > 16_384
                || refreshToken.isBlank() || refreshToken.length() > 16_384
                || userId.isBlank() || !refreshExpires.isAfter(now)) {
            throw new IllegalArgumentException("Invalid staff token response.");
        }
        Instant ceiling = now.plus(staffLifetime);
        ActiveSession session = new ActiveSession(
                accessToken,
                refreshToken,
                parseInstant(tokens.path("accessTokenExpiresAt").asText(), now),
                refreshExpires.isBefore(ceiling) ? refreshExpires : ceiling,
                userId,
                tokens.path("fullName").asText("Staff member"),
                newId()
        );
        active.put(id, session);
        cleanup();
        return id;
    }

    public Optional<ActiveSession> active(String id) {
        if (id == null) return Optional.empty();
        ActiveSession found = active.get(id);
        if (found == null || !found.sessionExpiresAt().isAfter(Instant.now())) {
            active.remove(id);
            return Optional.empty();
        }
        return Optional.of(found);
    }

    public void deleteActive(String id) {
        if (id != null) active.remove(id);
    }

    private String newId() {
        byte[] value = new byte[RANDOM_BYTES];
        random.nextBytes(value);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        java.util.Arrays.fill(value, (byte) 0);
        return encoded;
    }

    private void cleanup() {
        Instant now = Instant.now();
        pending.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        active.entrySet().removeIf(entry -> !entry.getValue().sessionExpiresAt().isAfter(now));
    }

    private static Instant parseInstant(String value, Instant fallback) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    public record PendingChallenge(String challengeId, String maskedEmail, Instant expiresAt) { }

    public static final class ActiveSession {
        private volatile String accessToken;
        private volatile String refreshToken;
        private volatile Instant accessExpiresAt;
        private final Instant sessionExpiresAt;
        private final String userId;
        private final String fullName;
        private final String releaseFormToken;
        private CompletionStage<String> refreshInFlight;

        private ActiveSession(String accessToken, String refreshToken, Instant accessExpiresAt,
                              Instant sessionExpiresAt, String userId, String fullName, String releaseFormToken) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.accessExpiresAt = accessExpiresAt;
            this.sessionExpiresAt = sessionExpiresAt;
            this.userId = userId;
            this.fullName = fullName;
            this.releaseFormToken = releaseFormToken;
        }

        public String accessToken() { return accessToken; }
        public String refreshToken() { return refreshToken; }
        public Instant accessExpiresAt() { return accessExpiresAt; }
        public Instant sessionExpiresAt() { return sessionExpiresAt; }
        public String userId() { return userId; }
        public String fullName() { return fullName; }
        public String releaseFormToken() { return releaseFormToken; }

        synchronized CompletionStage<String> refreshInFlight() { return refreshInFlight; }
        synchronized void refreshInFlight(CompletionStage<String> stage) { refreshInFlight = stage; }
        synchronized void clearRefresh(CompletionStage<String> stage) {
            if (refreshInFlight == stage) refreshInFlight = null;
        }

        synchronized void rotate(JsonNode tokens) {
            accessToken = tokens.path("accessToken").asText();
            refreshToken = tokens.path("refreshToken").asText();
            accessExpiresAt = parseInstant(tokens.path("accessTokenExpiresAt").asText(), Instant.now());
        }
    }
}

