package services;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.Test;
import play.Environment;
import play.Mode;
import play.libs.Json;

import java.io.File;
import java.time.Instant;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class StaffSessionStoreTest {
    private static Config config(String apiUrl, String secret, boolean secure) {
        return ConfigFactory.parseString("""
                makao.staff.sessionHours = 8
                makao.api.baseUrl = \"%s\"
                play.http.session.secure = %s
                play.http.secret.key = \"%s\"
                """.formatted(apiUrl, secure, secret));
    }

    @Test
    public void browserSessionIdDoesNotContainBackendTokens() {
        StaffSessionStore store = new StaffSessionStore(
                config("http://127.0.0.1:8080", "test-only", false), Environment.simple());
        String id = store.activate(Json.newObject()
                .put("accessToken", "access-secret")
                .put("refreshToken", "refresh-secret")
                .put("accessTokenExpiresAt", Instant.now().plusSeconds(300).toString())
                .put("refreshTokenExpiresAt", Instant.now().plusSeconds(3_600).toString())
                .put("userId", "staff-id")
                .put("fullName", "Staff Reviewer"));

        assertFalse(id.contains("access-secret"));
        assertFalse(id.contains("refresh-secret"));
        assertTrue(store.active(id).isPresent());
        String releaseToken = store.active(id).orElseThrow().releaseFormToken();
        assertFalse(releaseToken.isBlank());
        assertFalse(releaseToken.equals(id));
        assertFalse(releaseToken.contains("access-secret"));
        String secondId = store.activate(Json.newObject()
                .put("accessToken", "access-secret")
                .put("refreshToken", "refresh-secret")
                .put("accessTokenExpiresAt", Instant.now().plusSeconds(300).toString())
                .put("refreshTokenExpiresAt", Instant.now().plusSeconds(3_600).toString())
                .put("userId", "staff-id"));
        assertFalse(releaseToken.equals(store.active(secondId).orElseThrow().releaseFormToken()));
        store.deleteActive(id);
        assertTrue(store.active(id).isEmpty());
    }

    @Test
    public void productionRejectsCleartextBackendAndDefaultSecret() {
        Environment production = new Environment(new File("."), Mode.PROD);
        assertThrows(IllegalStateException.class, () -> new StaffSessionStore(
                config("http://127.0.0.1:8080", "changeme", false), production));
        assertThrows(IllegalStateException.class, () -> new StaffSessionStore(
                config("https://api.example.test", "changeme", true), production));
    }

    @Test
    public void malformedAuthenticationResponsesFailClosed() {
        StaffSessionStore store = new StaffSessionStore(
                config("http://127.0.0.1:8080", "test-only", false), Environment.simple());
        assertThrows(IllegalArgumentException.class, () -> store.saveChallenge(Json.newObject()));
        assertThrows(IllegalArgumentException.class, () -> store.activate(Json.newObject()));
    }
}
