package controllers;

import org.junit.Test;
import play.Application;
import play.mvc.Http;
import play.mvc.Result;
import play.test.Helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static play.test.Helpers.GET;
import static play.test.Helpers.POST;

public final class StaffWebSecurityTest {
    @Test
    public void fullSidebarIsVisibleButLinksKeepTheirExistingPermissions() {
        Application app = Helpers.fakeApplication();
        Helpers.running(app, () -> {
            for (var role : models.StaffModels.StaffRole.values()) {
                var staff = new models.StaffModels.StaffIdentity("synthetic-staff", role, false);
                var queue = new models.StaffModels.VerificationQueue(java.util.List.of(), java.util.List.of(),
                        "", "PENDING", "", 0, false, null, null);
                String body = views.html.admin.verificationQueue.render("test-csrf", staff,
                        "Current staff account", queue, "", "").body();
                assertTrue(body.contains("STAFF · RESTRICTED"));
                assertTrue(body.contains("Current staff account"));
                String[][] sections = {
                        {"verifications", "/staff/verifications", "Verification queue"},
                        {"listings", "/staff/listings", "Listing moderation"},
                        {"flags", "/staff/flagged-messages", "Flagged messages"},
                        {"reports", "/staff/reports", "User reports"},
                        {"payments", "/staff/payments", "Payments &amp; disputes"},
                        {"audit", "/staff/audit", "Audit log"},
                        {"team", "/staff/team", "Staff &amp; permissions"}
                };
                for (String[] section : sections) {
                    assertTrue(body.contains(section[2]));
                    if (!role.canSee(section[0])) {
                        assertTrue(body.contains("aria-disabled=\"true\">" + section[2] + "</span>"));
                        assertFalse(body.contains("href=\"" + section[1] + "\""));
                    } else {
                        assertTrue(body.contains("href=\"" + section[1] + "\""));
                    }
                }
                assertTrue(body.contains("Activity history"));
            }
        });
    }

    @Test
    public void signInIsServerRenderedAndNeverContainsTokens() {
        Application app = Helpers.fakeApplication();
        Helpers.running(app, () -> {
            Result result = Helpers.route(app, new Http.RequestBuilder().method(GET).uri("/staff/sign-in"));
            String body = Helpers.contentAsString(result);
            assertEquals(200, result.status());
            assertTrue(body.contains("Sign in to your workspace"));
            assertFalse(body.contains("accessToken"));
            assertFalse(body.contains("refreshToken"));
            assertTrue(result.header("Cache-Control").orElse("").contains("no-store"));
        });
    }

    @Test
    public void protectedWorkspaceRedirectsAndPostRequiresCsrf() {
        Application app = Helpers.fakeApplication();
        Helpers.running(app, () -> {
            Result workspace = Helpers.route(app, new Http.RequestBuilder().method(GET).uri("/staff"));
            assertEquals(303, workspace.status());
            assertEquals("/staff/sign-in", workspace.redirectLocation().orElse(""));

            Result signIn = Helpers.route(app, new Http.RequestBuilder().method(POST).uri("/staff/sign-in")
                    .header("Cookie", "makao_staff_web=forged")
                    .bodyForm(java.util.Map.of("email", "staff@example.test", "password", "not-a-secret")));
            assertEquals(403, signIn.status());
        });
    }
}
