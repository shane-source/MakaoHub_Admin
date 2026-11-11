package controllers;

import javax.inject.Inject;
import play.filters.csrf.AddCSRFToken;
import play.filters.csrf.RequireCSRFCheck;
import play.mvc.Http;
import play.mvc.Result;
import services.StaffApiClient;
import services.StaffSessionStore;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class StaffAuthController extends StaffWebSupport {
    static final String ACTIVE_SESSION = "staffSession";
    private static final String AUTH_FLOW = "staffAuthFlow";
    private final StaffApiClient api;
    private final StaffSessionStore sessions;

    @Inject
    public StaffAuthController(StaffApiClient api, StaffSessionStore sessions) {
        this.api = api;
        this.sessions = sessions;
    }

    public Result home(Http.Request request) {
        return sessions.active(request.session().get(ACTIVE_SESSION).orElse(null)).isPresent()
                ? redirect(routes.AdminController.workspace())
                : redirect(routes.StaffAuthController.signIn());
    }

    @AddCSRFToken
    public Result signIn(Http.Request request) {
        if (sessions.active(request.session().get(ACTIVE_SESSION).orElse(null)).isPresent()) {
            return redirect(routes.AdminController.workspace());
        }
        return noStore(ok(views.html.login.render(csrf(request), flash(request, "error"), flash(request, "success"))));
    }

    @RequireCSRFCheck
    public CompletionStage<Result> startSignIn(Http.Request request) {
        String email = field(request, "email").toLowerCase(java.util.Locale.ROOT);
        String password = field(request, "password");
        if (!validEmail(email) || password.isBlank() || password.length() > 72) {
            return CompletableFuture.completedFuture(noStore(badRequest(views.html.login.render(
                    csrf(request), "Enter valid staff credentials.", ""))));
        }
        return api.login(email, password).thenApply(response -> {
            if (!response.success()) {
                return noStore(status(response.status(), views.html.login.render(csrf(request), response.detail(), "")));
            }
            try {
                String flowId = sessions.saveChallenge(response.json());
                return noStore(redirect(routes.StaffAuthController.verify()).addingToSession(request, AUTH_FLOW, flowId));
            } catch (IllegalArgumentException invalidResponse) {
                return noStore(status(502, views.html.login.render(csrf(request),
                        "Staff authentication is temporarily unavailable.", "")));
            }
        });
    }

    @AddCSRFToken
    public Result verify(Http.Request request) {
        String flowId = request.session().get(AUTH_FLOW).orElse(null);
        var challenge = sessions.challenge(flowId);
        if (challenge.isEmpty()) {
            return redirect(routes.StaffAuthController.signIn()).flashing("error", "Start a new staff sign-in. The previous verification code expired.");
        }
        var item = challenge.get();
        return noStore(ok(views.html.otp.render(csrf(request), item.maskedEmail(), item.expiresAt().toString(), flash(request, "error"))));
    }

    @RequireCSRFCheck
    public CompletionStage<Result> completeSignIn(Http.Request request) {
        String flowId = request.session().get(AUTH_FLOW).orElse(null);
        var challenge = sessions.challenge(flowId);
        if (challenge.isEmpty()) {
            return CompletableFuture.completedFuture(redirect(routes.StaffAuthController.signIn())
                    .flashing("error", "Start a new staff sign-in. The previous verification code expired."));
        }
        String code = field(request, "code");
        if (!code.matches("\\d{6}")) {
            return CompletableFuture.completedFuture(noStore(badRequest(views.html.otp.render(
                    csrf(request), challenge.get().maskedEmail(), challenge.get().expiresAt().toString(), "Enter the six-digit code."))));
        }
        return api.verify(challenge.get().challengeId(), code).thenApply(response -> {
            if (!response.success()) {
                return noStore(status(response.status(), views.html.otp.render(
                        csrf(request), challenge.get().maskedEmail(), challenge.get().expiresAt().toString(), response.detail())));
            }
            try {
                String activeId = sessions.activate(response.json());
                sessions.deleteChallenge(flowId);
                return noStore(redirect(routes.AdminController.workspace()).addingToSession(request, ACTIVE_SESSION, activeId));
            } catch (IllegalArgumentException invalidResponse) {
                sessions.deleteChallenge(flowId);
                return noStore(status(502, views.html.login.render(csrf(request),
                        "Staff authentication is temporarily unavailable.", "")));
            }
        });
    }

    @RequireCSRFCheck
    public CompletionStage<Result> signOut(Http.Request request) {
        String id = request.session().get(ACTIVE_SESSION).orElse(null);
        var active = sessions.active(id);
        sessions.deleteActive(id);
        Result signedOut = noStore(redirect(routes.StaffAuthController.signIn())
                .removingFromSession(request, ACTIVE_SESSION)
                .flashing("success", "You have signed out."));
        if (active.isEmpty()) return CompletableFuture.completedFuture(signedOut);
        return api.logout(active.get()).handle((ignored, failure) -> signedOut);
    }

    @AddCSRFToken
    public Result recover(Http.Request request) {
        return noStore(ok(views.html.recover.render(csrf(request), "", false, flash(request, "error"))));
    }

    @RequireCSRFCheck
    public CompletionStage<Result> requestRecovery(Http.Request request) {
        String email = field(request, "email").toLowerCase(java.util.Locale.ROOT);
        if (!validEmail(email)) {
            return CompletableFuture.completedFuture(noStore(badRequest(views.html.recover.render(
                    csrf(request), "", false, "Enter a valid staff email."))));
        }
        return api.requestRecovery(email).thenApply(response -> response.success()
                ? noStore(ok(views.html.recover.render(csrf(request), email, true, "")))
                : noStore(status(response.status(), views.html.recover.render(csrf(request), "", false, response.detail()))));
    }

    @RequireCSRFCheck
    public CompletionStage<Result> resetPassword(Http.Request request) {
        String email = field(request, "email").toLowerCase(java.util.Locale.ROOT);
        String code = field(request, "code");
        String password = field(request, "password");
        if (!validEmail(email) || !code.matches("\\d{6}") || !validPassword(password)) {
            return CompletableFuture.completedFuture(noStore(badRequest(views.html.recover.render(
                    csrf(request), email, true, "Enter a six-digit code and a password of 8–72 characters."))));
        }
        return api.resetPassword(email, code, password).thenApply(response -> response.success()
                ? noStore(redirect(routes.StaffAuthController.signIn()).flashing("success", "Password reset. Sign in with your new password."))
                : noStore(status(response.status(), views.html.recover.render(csrf(request), email, true, response.detail()))));
    }

    @AddCSRFToken
    public Result acceptInvitation(Http.Request request) {
        return noStore(ok(views.html.acceptInvitation.render(csrf(request), flash(request, "error"))));
    }

    @RequireCSRFCheck
    public CompletionStage<Result> completeInvitation(Http.Request request) {
        String email = field(request, "email").toLowerCase(java.util.Locale.ROOT);
        String code = field(request, "invitationCode");
        String fullName = field(request, "fullName");
        String password = field(request, "password");
        if (!validEmail(email) || !code.matches("[A-Za-z0-9_-]{43}") || fullName.isBlank()
                || fullName.length() > 150 || !validPassword(password)) {
            return CompletableFuture.completedFuture(noStore(badRequest(views.html.acceptInvitation.render(
                    csrf(request), "Check the invitation details and use a password of 8–72 characters."))));
        }
        return api.acceptInvitation(email, code, fullName, password).thenApply(response -> response.success()
                ? noStore(redirect(routes.StaffAuthController.signIn()).flashing("success", "Staff account created. Sign in to verify email MFA."))
                : noStore(status(response.status(), views.html.acceptInvitation.render(csrf(request), response.detail()))));
    }
}

