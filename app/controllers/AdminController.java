package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.inject.Inject;
import models.StaffModels;
import models.StaffModels.ActionItem;
import models.StaffModels.ActionQueue;
import models.StaffModels.Column;
import models.StaffModels.DocumentItem;
import models.StaffModels.ListingCase;
import models.StaffModels.MediaItem;
import models.StaffModels.PaymentPage;
import models.StaffModels.ReviewerOption;
import models.StaffModels.StaffIdentity;
import models.StaffModels.TablePage;
import models.StaffModels.TableRow;
import models.StaffModels.VerificationCase;
import models.StaffModels.VerificationQueue;
import models.StaffModels.VerificationQueueRow;
import play.filters.csrf.AddCSRFToken;
import play.filters.csrf.RequireCSRFCheck;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;
import services.StaffApiClient;
import services.StaffSessionStore;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class AdminController extends StaffWebSupport {
    private static final Set<String> VERIFICATION_ROLES = Set.of("RENTER", "LANDLORD", "SUBLESSOR");
    private static final Set<String> VERIFICATION_STATES = Set.of("PENDING", "VERIFIED", "REJECTED");
    private static final Set<String> LISTING_KINDS = Set.of("LANDLORD", "SUBLESSOR");
    private static final Set<String> LISTING_STATES = Set.of("PENDING", "APPROVED", "REJECTED");
    private static final Set<String> REPORT_STATES = Set.of("OPEN", "INVESTIGATING", "ESCALATED", "RESOLVED");
    private final StaffApiClient api;
    private final StaffSessionStore sessions;

    @Inject
    public AdminController(StaffApiClient api, StaffSessionStore sessions) {
        this.api = api;
        this.sessions = sessions;
    }

    public CompletionStage<Result> workspace(Http.Request request) {
        return withStaff(request, context -> {
            String destination = switch (context.staff().role()) {
                case STAFF_LEAD, VERIFICATION_REVIEWER -> routes.AdminController.verifications().url();
                case SAFETY_MODERATOR -> routes.AdminController.listings().url();
                case PAYMENT_REVIEWER -> routes.AdminController.payments().url();
            };
            return CompletableFuture.completedFuture(noStore(redirect(destination)));
        });
    }

    @AddCSRFToken
    public CompletionStage<Result> verifications(Http.Request request) {
        return withStaff(request, context -> {
            String role = optionalEnum(request.queryString("role").orElse(null), VERIFICATION_ROLES);
            String state = StaffModels.safeState(request.queryString("state").orElse(null), VERIFICATION_STATES, "PENDING");
            String search = bounded(request.queryString("search").orElse(null), 150);
            int page = page(request);
            String path = "/api/v1/admin/verifications?state=" + encode(state) + "&page=" + page + "&size=20"
                    + (role.isBlank() ? "" : "&role=" + encode(role))
                    + (search.isBlank() ? "" : "&search=" + encode(search));
            CompletionStage<StaffApiClient.ApiResponse> queueCall = api.get(context.session(), path);
            CompletionStage<StaffApiClient.ApiResponse> staffCall = context.staff().role() == StaffModels.StaffRole.STAFF_LEAD
                    ? api.get(context.session(), "/api/v1/admin/staff?page=0&size=50")
                    : CompletableFuture.completedFuture(StaffApiClient.ApiResponse.problem(200, ""));
            return queueCall.thenCombine(staffCall, (queueResponse, staffResponse) -> {
                if (!queueResponse.success()) return backendPageError(request, context, queueResponse, "Verification queue");
                List<ReviewerOption> reviewers = new ArrayList<>();
                if (context.staff().role() == StaffModels.StaffRole.STAFF_LEAD && staffResponse.success()) {
                    for (JsonNode reviewer : StaffModels.items(staffResponse.json())) {
                        if ("VERIFICATION_REVIEWER".equals(StaffModels.text(reviewer, "staff_role"))
                                && reviewer.path("can_view_documents").asBoolean(false)
                                && reviewer.path("enabled").asBoolean(false)) {
                            reviewers.add(new ReviewerOption(
                                    StaffModels.text(reviewer, "user_id"),
                                    StaffModels.text(reviewer, "full_name") + " · " + StaffModels.shortId(reviewer, "user_id")
                            ));
                        }
                    }
                }
                List<VerificationQueueRow> rows = new ArrayList<>();
                for (JsonNode item : StaffModels.items(queueResponse.json())) {
                    String userId = StaffModels.text(item, "user_id");
                    String itemRole = StaffModels.text(item, "role");
                    rows.add(new VerificationQueueRow(
                            userId,
                            StaffModels.shortId(item, "user_id"),
                            StaffModels.text(item, "full_name"),
                            itemRole,
                            StaffModels.text(item, "submitted_at"),
                            evidenceSummary(itemRole),
                            StaffModels.text(item, "status"),
                            StaffModels.text(item, "reviewer_id"),
                            routes.AdminController.verification(itemRole, userId).url(),
                            routes.AdminController.assignVerification(itemRole, userId).url()
                    ));
                }
                boolean hasNext = queueResponse.json().path("hasNext").asBoolean(false);
                VerificationQueue model = new VerificationQueue(
                        List.copyOf(rows), List.copyOf(reviewers), role, state, search, page, hasNext,
                        page == 0 ? null : verificationHref(role, state, search, page - 1),
                        hasNext ? verificationHref(role, state, search, page + 1) : null
                );
                return noStore(ok(views.html.admin.verificationQueue.render(
                        csrf(request), context.staff(), context.session().fullName(), model,
                        flash(request, "error"), flash(request, "success"))));
            });
        });
    }

    @RequireCSRFCheck
    public CompletionStage<Result> assignVerification(Http.Request request, String role, String userId) {
        String safeRole = requiredEnum(role, VERIFICATION_ROLES);
        UUID subject = parseUuid(userId);
        UUID reviewer = parseUuid(field(request, "reviewerId"));
        String reason = bounded(field(request, "reason"), 500);
        if (safeRole == null || subject == null || reviewer == null || reason.isBlank()) {
            return CompletableFuture.completedFuture(redirect(routes.AdminController.verifications())
                    .flashing("error", "Choose a reviewer and enter a valid assignment reason."));
        }
        return withStaff(request, context -> api.post(context.session(),
                "/api/v1/admin/verifications/" + safeRole + "/" + subject + "/assign",
                Json.newObject().put("reviewerId", reviewer.toString()).put("reason", reason), UUID.randomUUID()
        ).thenApply(response -> mutationRedirect(request, context, response, routes.AdminController.verifications().url(),
                "Verification case assigned.")));
    }

    @AddCSRFToken
    public CompletionStage<Result> verification(Http.Request request, String role, String userId) {
        String safeRole = requiredEnum(role, VERIFICATION_ROLES);
        UUID subject = parseUuid(userId);
        if (safeRole == null || subject == null) return CompletableFuture.completedFuture(notFoundPage());
        return withStaff(request, context -> api.get(context.session(),
                "/api/v1/admin/verifications/" + safeRole + "/" + subject).thenApply(response -> {
            if (!response.success()) return backendPageError(request, context, response, "Verification review");
            JsonNode item = response.json();
            List<DocumentItem> documents = new ArrayList<>();
            JsonNode evidence = item.path("documents");
            if (evidence.isArray()) for (JsonNode document : evidence) {
                String documentId = StaffModels.text(document, "id");
                documents.add(new DocumentItem(
                        documentId,
                        StaffModels.text(document, "document_type"),
                        StaffModels.text(document, "content_type"),
                        StaffModels.bytes(document, "size_bytes"),
                        StaffModels.text(document, "status"),
                        !document.path("viewed_at").isNull() && !document.path("viewed_at").isMissingNode(),
                        !document.path("reviewed_at").isNull() && !document.path("reviewed_at").isMissingNode(),
                        routes.AdminController.document(safeRole, subject.toString(), documentId).url(),
                        routes.AdminController.reviewDocument(safeRole, subject.toString(), documentId).url()
                ));
            }
            VerificationCase model = new VerificationCase(
                    subject.toString(), StaffModels.text(item, "full_name"), safeRole, StaffModels.text(item, "status"),
                    StaffModels.text(item, "submitted_at"), StaffModels.text(item, "updated_at"),
                    StaffModels.text(item, "rejection_reason"), StaffModels.text(item, "reviewer_name"),
                    List.copyOf(documents), selectedDocument(request, documents)
            );
            return noStore(ok(views.html.admin.verification.render(
                    csrf(request), context.staff(), context.session().fullName(), model, flash(request, "error"))));
        }));
    }

    public CompletionStage<Result> document(Http.Request request, String role, String userId, String documentId) {
        String safeRole = requiredEnum(role, VERIFICATION_ROLES);
        UUID subject = parseUuid(userId);
        UUID document = parseUuid(documentId);
        if (safeRole == null || subject == null || document == null) return CompletableFuture.completedFuture(notFoundPage());
        return withStaff(request, context -> api.get(context.session(),
                "/api/v1/admin/verifications/" + safeRole + "/" + subject).thenApply(response -> {
            if (!response.success()) return backendPageError(request, context, response, "Document unavailable");
            JsonNode documents = response.json().path("documents");
            if (documents.isArray()) for (JsonNode item : documents) {
                if (!document.toString().equals(StaffModels.text(item, "id"))) continue;
                String type = allowedDocumentType(StaffModels.text(item, "content_type"));
                if (type == null || !"READY".equals(StaffModels.text(item, "status"))) return notFoundPage();
                return noStore(ok(views.html.admin.document.render(type,
                        routes.AdminController.documentFile(safeRole, subject.toString(), document.toString()).url(),
                        routes.AdminController.verification(safeRole, subject.toString()).url())));
            }
            return notFoundPage();
        }));
    }

    @AddCSRFToken
    public CompletionStage<Result> applicationRemarks(Http.Request request, String userId) {
        UUID subject = parseUuid(userId);
        if (subject == null) return CompletableFuture.completedFuture(notFoundPage());
        return withStaff(request, context -> api.get(context.session(),
                "/api/v1/admin/verifications/RENTER/" + subject + "/application-remarks").thenApply(response -> {
            if (!response.success()) return backendPageError(request, context, response, "Application remarks");
            List<StaffModels.ApplicationRemark> remarks = new ArrayList<>();
            if (response.json().isArray()) for (JsonNode item : response.json()) {
                remarks.add(new StaffModels.ApplicationRemark(StaffModels.text(item, "listingTitle"), StaffModels.text(item, "remark")));
            }
            return noStore(ok(views.html.admin.applicationRemarks.render(csrf(request), context.staff(), context.session().fullName(), subject.toString(), List.copyOf(remarks))));
        }));
    }

    public CompletionStage<Result> documentFile(Http.Request request, String role, String userId, String documentId) {
        return loadDocumentFile(request, role, userId, documentId).thenApply(result -> noStore(result)
                .withHeaders("Content-Security-Policy", "sandbox; default-src 'none'; script-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"));
    }

    private CompletionStage<Result> loadDocumentFile(Http.Request request, String role, String userId, String documentId) {
        String safeRole = requiredEnum(role, VERIFICATION_ROLES);
        UUID subject = parseUuid(userId);
        UUID document = parseUuid(documentId);
        if (safeRole == null || subject == null || document == null) return CompletableFuture.completedFuture(notFoundPage());
        return withStaff(request, context -> api.getBinary(context.session(),
                "/api/v1/admin/verifications/" + safeRole + "/" + subject + "/documents/" + document)
                .thenApply(response -> {
                    if (!response.success() || response.bytes() == null) {
                        return backendPageError(request, context, response, "Document unavailable");
                    }
                    String type = allowedDocumentType(response.contentType());
                    if (type == null) return noStore(status(502, "Document type was not accepted."));
                    return ok(response.bytes()).as(type).withHeaders(
                            "Cache-Control", "no-store, private", "Pragma", "no-cache",
                            "Content-Disposition", "inline; filename=verification-document" + ("application/pdf".equals(type) ? ".pdf" : "image/png".equals(type) ? ".png" : ".jpg"),
                            "X-Content-Type-Options", "nosniff", "Referrer-Policy", "no-referrer"
                    );
                }));
    }

    @RequireCSRFCheck
    public CompletionStage<Result> reviewDocument(Http.Request request, String role, String userId, String documentId) {
        String safeRole = requiredEnum(role, VERIFICATION_ROLES);
        UUID subject = parseUuid(userId);
        UUID document = parseUuid(documentId);
        if (safeRole == null || subject == null || document == null) return CompletableFuture.completedFuture(notFoundPage());
        String target = routes.AdminController.verification(safeRole, subject.toString()).url();
        return withStaff(request, context -> api.post(context.session(),
                "/api/v1/admin/verifications/" + safeRole + "/" + subject + "/documents/" + document + "/review",
                Json.newObject(), UUID.randomUUID()).thenApply(response ->
                mutationRedirect(request, context, response, target, "Current evidence marked reviewed.")));
    }

    @RequireCSRFCheck
    public CompletionStage<Result> decideVerification(Http.Request request, String role, String userId) {
        String safeRole = requiredEnum(role, VERIFICATION_ROLES);
        UUID subject = parseUuid(userId);
        String reason = bounded(field(request, "reason"), 500);
        String expected = field(request, "expectedUpdatedAt");
        if (safeRole == null || subject == null || reason.isBlank() || expected.isBlank()) return CompletableFuture.completedFuture(notFoundPage());
        ObjectNode body = Json.newObject().put("approve", checked(request, "approve"))
                .put("expectedUpdatedAt", expected).put("reason", reason);
        String target = routes.AdminController.verification(safeRole, subject.toString()).url();
        return withStaff(request, context -> api.post(context.session(),
                "/api/v1/admin/verifications/" + safeRole + "/" + subject + "/decision",
                body, UUID.randomUUID()).thenApply(response -> mutationRedirect(request, context, response,
                response.success() ? routes.AdminController.verifications().url() : target,
                "Verification decision recorded.")));
    }

    @AddCSRFToken
    public CompletionStage<Result> listings(Http.Request request) {
        return withStaff(request, context -> {
            String kind = StaffModels.safeState(request.queryString("kind").orElse(null), LISTING_KINDS, "LANDLORD");
            String state = StaffModels.safeState(request.queryString("state").orElse(null), LISTING_STATES, "PENDING");
            int page = page(request);
            return api.get(context.session(), "/api/v1/admin/listings?kind=" + kind + "&state=" + state + "&page=" + page + "&size=20")
                    .thenApply(response -> {
                        if (!response.success()) return backendPageError(request, context, response, "Listing moderation");
                        List<TableRow> rows = new ArrayList<>();
                        for (JsonNode item : StaffModels.items(response.json())) {
                            String id = StaffModels.text(item, "id");
                            rows.add(new TableRow(StaffModels.values(
                                    "reference", StaffModels.shortId(item, "id"),
                                    "title", StaffModels.text(item, "title"),
                                    "state", StaffModels.text(item, "status"),
                                    "moderation", StaffModels.text(item, "moderation_status"),
                                    "submitted", StaffModels.text(item, "moderation_submitted_at")
                            ), routes.AdminController.listing(kind, id).url(), "Open moderation"));
                        }
                        boolean next = response.json().path("hasNext").asBoolean(false);
                        String base = routes.AdminController.listings().url() + "?kind=" + kind + "&state=" + state + "&page=";
                        TablePage model = new TablePage("Listing moderation", kind.toLowerCase(Locale.ROOT) + " listings · "
                                + state.toLowerCase(Locale.ROOT) + " review state",
                                "listings", List.of(new Column("reference", "Reference"), new Column("title", "Listing"),
                                new Column("state", "Listing state"), new Column("moderation", "Review state"), new Column("submitted", "Submitted")),
                                List.copyOf(rows), page, next, page == 0 ? null : base + (page - 1), next ? base + (page + 1) : null);
                        return noStore(ok(views.html.admin.table.render(csrf(request), context.staff(), context.session().fullName(), model,
                                flash(request, "error"), flash(request, "success"))));
                    });
        });
    }

    @AddCSRFToken
    public CompletionStage<Result> listing(Http.Request request, String kind, String listingId) {
        String safeKind = requiredEnum(kind, LISTING_KINDS);
        UUID id = parseUuid(listingId);
        if (safeKind == null || id == null) return CompletableFuture.completedFuture(notFoundPage());
        return withStaff(request, context -> api.get(context.session(), "/api/v1/admin/listings/" + safeKind + "/" + id)
                .thenApply(response -> {
                    if (!response.success()) return backendPageError(request, context, response, "Listing moderation");
                    JsonNode item = response.json();
                    List<MediaItem> media = new ArrayList<>();
                    JsonNode mediaNode = item.path("media");
                    if (mediaNode.isArray()) {
                        for (JsonNode entry : mediaNode) {
                            String mediaId = StaffModels.text(entry, "id");
                            media.add(new MediaItem(mediaId, StaffModels.text(entry, "media_type"),
                                    StaffModels.text(entry, "content_type"),
                                    routes.AdminController.listingMedia(safeKind, id.toString(), mediaId).url()));
                        }
                    }
                    ListingCase model = new ListingCase(id.toString(), safeKind, StaffModels.text(item, "title"),
                            StaffModels.text(item, "description"), StaffModels.rent(item),
                            StaffModels.text(item, "listing_type"), StaffModels.text(item, "bedrooms"),
                            StaffModels.text(item, "public_area"), StaffModels.text(item, "owner_name"),
                            item.path("owner_verified").asBoolean(false), StaffModels.text(item, "status"),
                            StaffModels.text(item, "moderation_status"), StaffModels.text(item, "moderation_reason"),
                            StaffModels.text(item, "updated_at"), item.path("locationSaved").asBoolean(false), List.copyOf(media));
                    return noStore(ok(views.html.admin.listing.render(csrf(request), context.staff(), context.session().fullName(), model, flash(request, "error"))));
                }));
    }

    public CompletionStage<Result> listingMedia(Http.Request request, String kind, String listingId, String mediaId) {
        String safeKind = requiredEnum(kind, LISTING_KINDS);
        UUID id = parseUuid(listingId);
        UUID media = parseUuid(mediaId);
        if (safeKind == null || id == null || media == null) return CompletableFuture.completedFuture(notFoundPage());
        String path = "/api/v1/admin/listings/" + safeKind + "/" + id + "/media/" + media;
        return withStaff(request, context -> api.getBinary(context.session(), path).thenApply(mediaResponse -> {
            String type = allowedListingMediaType(mediaResponse.contentType());
            if (!mediaResponse.success() || mediaResponse.bytes() == null || type == null) {
                int failureStatus = Set.of(401, 403, 404).contains(mediaResponse.status()) ? mediaResponse.status() : 502;
                return noStore(status(failureStatus, "Media is unavailable."));
            }
            return ok(mediaResponse.bytes()).as(type).withHeaders("Cache-Control", "no-store, private",
                    "Pragma", "no-cache", "X-Content-Type-Options", "nosniff", "Referrer-Policy", "no-referrer");
        }));
    }

    @RequireCSRFCheck
    public CompletionStage<Result> decideListing(Http.Request request, String kind, String listingId) {
        String safeKind = requiredEnum(kind, LISTING_KINDS);
        UUID id = parseUuid(listingId);
        String reason = bounded(field(request, "reason"), 500);
        String expected = field(request, "expectedUpdatedAt");
        if (safeKind == null || id == null || reason.isBlank() || expected.isBlank()) return CompletableFuture.completedFuture(notFoundPage());
        ObjectNode body = Json.newObject().put("approve", checked(request, "approve"))
                .put("expectedUpdatedAt", expected).put("reason", reason);
        String current = routes.AdminController.listing(safeKind, id.toString()).url();
        return withStaff(request, context -> api.post(context.session(), "/api/v1/admin/listings/" + safeKind + "/" + id + "/decision",
                body, UUID.randomUUID()).thenApply(response -> mutationRedirect(request, context, response,
                response.success() ? routes.AdminController.listings().url() : current, "Listing decision recorded.")));
    }

    @AddCSRFToken
    public CompletionStage<Result> flaggedMessages(Http.Request request) {
        return withStaff(request, context -> {
            int page = page(request);
            return api.get(context.session(), "/api/v1/admin/flagged-messages?page=" + page + "&size=20").thenApply(response -> {
                if (!response.success()) return backendPageError(request, context, response, "Flagged messages");
                List<ActionItem> items = new ArrayList<>();
                for (JsonNode item : StaffModels.items(response.json())) {
                    String id = StaffModels.text(item, "id");
                    String kind = StaffModels.text(item, "kind");
                    items.add(new ActionItem(StaffModels.values(
                            "Message reference", StaffModels.shortId(item, "chat_thread_id"),
                            "Role pair", "RENTAL".equals(kind) ? "Landlord / Renter" : "Sublessor / Renter",
                            "Masked content", StaffModels.text(item, "masked_body"),
                            "Flag reason", StaffModels.text(item, "moderation_reason"),
                            "Created", StaffModels.text(item, "created_at"),
                            "Review state", StaffModels.text(item, "review_state")
                    ), routes.AdminController.decideFlag(kind, id).url(), id, kind));
                }
                boolean next = response.json().path("hasNext").asBoolean(false);
                String base = routes.AdminController.flaggedMessages().url() + "?page=";
                ActionQueue model = new ActionQueue("Flagged messages", "Contact-sharing attempts stay masked in staff summaries and conversations",
                        "flags", List.copyOf(items), page, next, page == 0 ? null : base + (page - 1), next ? base + (page + 1) : null);
                return noStore(ok(views.html.admin.actionQueue.render(csrf(request), context.staff(), context.session().fullName(), model,
                        flash(request, "error"), flash(request, "success"))));
            });
        });
    }

    @RequireCSRFCheck
    public CompletionStage<Result> decideFlag(Http.Request request, String kind, String messageId) {
        String safeKind = requiredEnum(kind, Set.of("RENTAL", "ROOMMATE"));
        UUID id = parseUuid(messageId);
        String reason = bounded(field(request, "reason"), 500);
        if (safeKind == null || id == null || reason.isBlank()) return CompletableFuture.completedFuture(notFoundPage());
        ObjectNode body = Json.newObject().put("escalate", checked(request, "escalate")).put("reason", reason);
        return withStaff(request, context -> api.post(context.session(), "/api/v1/admin/flagged-messages/" + safeKind + "/" + id + "/decision",
                body, UUID.randomUUID()).thenApply(response -> mutationRedirect(request, context, response,
                routes.AdminController.flaggedMessages().url(), "Flagged message decision recorded.")));
    }

    @AddCSRFToken
    public CompletionStage<Result> reports(Http.Request request) {
        return withStaff(request, context -> {
            String state = StaffModels.safeState(request.queryString("state").orElse(null), REPORT_STATES, "OPEN");
            int page = page(request);
            return api.get(context.session(), "/api/v1/admin/reports?state=" + state + "&page=" + page + "&size=20").thenApply(response -> {
                if (!response.success()) return backendPageError(request, context, response, "User reports");
                List<ActionItem> items = new ArrayList<>();
                for (JsonNode item : StaffModels.items(response.json())) {
                    String id = StaffModels.text(item, "id");
                    String kind = StaffModels.text(item, "subject_kind");
                    items.add(new ActionItem(StaffModels.values(
                            "Report reference", StaffModels.shortId(item, "id"),
                            "Subject", StaffModels.text(item, "subject_label"),
                            "Concern", StaffModels.text(item, "category"),
                            "Private summary", StaffModels.text(item, "safe_description"),
                            "Priority", StaffModels.text(item, "priority"),
                            "State", StaffModels.text(item, "state")
                    ), routes.AdminController.decideReport(id).url(), id, kind));
                }
                boolean next = response.json().path("hasNext").asBoolean(false);
                String base = routes.AdminController.reports().url() + "?state=" + state + "&page=";
                ActionQueue model = new ActionQueue("User reports", "Prioritise safety concerns and record a reasoned outcome", "reports",
                        List.copyOf(items), page, next, page == 0 ? null : base + (page - 1), next ? base + (page + 1) : null);
                return noStore(ok(views.html.admin.actionQueue.render(csrf(request), context.staff(), context.session().fullName(), model,
                        flash(request, "error"), flash(request, "success"))));
            });
        });
    }

    @RequireCSRFCheck
    public CompletionStage<Result> decideReport(Http.Request request, String reportId) {
        UUID id = parseUuid(reportId);
        String state = requiredEnum(field(request, "state"), Set.of("INVESTIGATING", "ESCALATED", "RESOLVED"));
        String priority = requiredEnum(field(request, "priority"), Set.of("NORMAL", "HIGH"));
        String reason = bounded(field(request, "reason"), 500);
        if (id == null || state == null || priority == null || reason.isBlank()) return CompletableFuture.completedFuture(notFoundPage());
        ObjectNode body = Json.newObject().put("state", state).put("priority", priority)
                .put("pauseListing", checked(request, "pauseListing"))
                .put("notifyUsers", checked(request, "notifyUsers")).put("reason", reason);
        return withStaff(request, context -> api.post(context.session(), "/api/v1/admin/reports/" + id + "/decision",
                body, UUID.randomUUID()).thenApply(response -> mutationRedirect(request, context, response,
                routes.AdminController.reports().url(), "User report decision recorded.")));
    }

    @AddCSRFToken
    public CompletionStage<Result> payments(Http.Request request) {
        return withStaff(request, context -> {
            int page = page(request);
            CompletionStage<StaffApiClient.ApiResponse> payments = api.get(context.session(), "/api/v1/admin/payments?page=" + page + "&size=20");
            CompletionStage<StaffApiClient.ApiResponse> bookings = api.get(context.session(), "/api/v1/admin/bookings?page=" + page + "&size=20");
            return payments.thenCombine(bookings, (paymentResponse, bookingResponse) -> {
                if (!paymentResponse.success()) return backendPageError(request, context, paymentResponse, "Payments unavailable");
                if (!bookingResponse.success()) return backendPageError(request, context, bookingResponse, "Bookings unavailable");
                List<TableRow> paymentRows = new ArrayList<>();
                for (JsonNode item : StaffModels.items(paymentResponse.json())) {
                    paymentRows.add(new TableRow(StaffModels.values(
                            "reference", StaffModels.shortId(item, "id"), "type", StaffModels.text(item, "payment_type"),
                            "amount", StaffModels.money(item, "amount_minor") + " " + StaffModels.text(item, "currency"),
                            "status", StaffModels.text(item, "status"), "created", StaffModels.text(item, "created_at")
                    ), null, ""));
                }
                List<TableRow> bookingRows = new ArrayList<>();
                for (JsonNode item : StaffModels.items(bookingResponse.json())) {
                    bookingRows.add(new TableRow(StaffModels.values(
                            "id", StaffModels.text(item, "id"), "listing", StaffModels.text(item, "listing_kind"),
                            "amount", StaffModels.money(item, "total_amount_minor") + " " + StaffModels.text(item, "currency"),
                            "payout", StaffModels.money(item, "rent_amount_minor") + " rent + " + StaffModels.money(item, "deposit_amount_minor") + " deposit",
                            "status", StaffModels.text(item, "status"), "eligible", StaffModels.text(item, "release_eligible_at"),
                            "requested", StaffModels.text(item, "owner_release_requested_at"),
                            "renterMoveIn", StaffModels.text(item, "move_in_confirmed_at"), "ownerMoveIn", StaffModels.text(item, "owner_move_in_confirmed_at"),
                            "ownerId", StaffModels.text(item,"owner_id"), "accepted", StaffModels.text(item,"move_in_accepted"),
                            "testPayout", StaffModels.text(item,"test_payout_status"),
                            "destination", StaffModels.text(item,"test_destination_configured"),
                            "owner", StaffModels.text(item, "owner_name"), "renter", StaffModels.text(item, "renter_name"),
                            "ready", StaffModels.text(item, "release_ready")
                    ), null, ""));
                }
                PaymentPage model = new PaymentPage(List.copyOf(paymentRows), List.copyOf(bookingRows), page,
                        paymentResponse.json().path("hasNext").asBoolean(false), bookingResponse.json().path("hasNext").asBoolean(false));
                return noStore(ok(views.html.admin.payments.render(csrf(request), context.session().releaseFormToken(),
                        context.staff(), context.session().fullName(), model,
                        flash(request, "error"), flash(request, "success"))));
            });
        });
    }

    @RequireCSRFCheck
    public CompletionStage<Result> releaseTestBooking(Http.Request request, String bookingId) {
        UUID id = parseUuid(bookingId);
        if (id == null) return CompletableFuture.completedFuture(notFoundPage());
        return withStaff(request, context -> {
            if (context.staff().role() != StaffModels.StaffRole.PAYMENT_REVIEWER
                    && context.staff().role() != StaffModels.StaffRole.STAFF_LEAD) {
                return CompletableFuture.completedFuture(noStore(forbidden("TEST release permission is required.")));
            }
            String suppliedToken = field(request, "releaseToken");
            if (suppliedToken.isBlank() || !MessageDigest.isEqual(
                    suppliedToken.getBytes(StandardCharsets.UTF_8),
                    context.session().releaseFormToken().getBytes(StandardCharsets.UTF_8))) {
                return CompletableFuture.completedFuture(noStore(forbidden("A valid release form token is required.")));
            }
            UUID key = UUID.nameUUIDFromBytes(("makao-test-release:" + id).getBytes(StandardCharsets.UTF_8));
            return api.post(context.session(), "/api/v1/admin/payments/bookings/" + id + "/release", Json.newObject(), key)
                    .thenApply(response -> mutationRedirect(request, context, response,
                            routes.AdminController.payments().url(), "TEST release recorded. No Paystack transfer was made."));
        });
    }

    @RequireCSRFCheck
    public CompletionStage<Result> configureTestRecipient(Http.Request request,String ownerId) {
        UUID owner=parseUuid(ownerId);
        if(owner==null) return CompletableFuture.completedFuture(notFoundPage());
        return withStaff(request,context->{
            if(context.staff().role()!=StaffModels.StaffRole.STAFF_LEAD)
                return CompletableFuture.completedFuture(noStore(forbidden("Staff lead is required.")));
            String code=field(request,"recipientCode");
            if(!code.matches("RCP_[a-zA-Z0-9]+") || !"true".equals(field(request,"ownerDestinationConfirmed")))
                return CompletableFuture.completedFuture(noStore(badRequest("Enter a TEST recipient code and confirm it belongs to this owner.")));
            var body=Json.newObject().put("recipientCode",code).put("ownerDestinationConfirmed",true);
            UUID key=UUID.nameUUIDFromBytes(("test-destination:"+owner+":"+code).getBytes(StandardCharsets.UTF_8));
            return api.post(context.session(),"/api/v1/admin/payments/owners/"+owner+"/test-recipient",body,key)
                .thenApply(response->mutationRedirect(request,context,response,routes.AdminController.payments().url(),
                    "TEST destination configured. Automatic payout still requires fresh renter True and five minutes."));
        });
    }

    @AddCSRFToken
    public CompletionStage<Result> audit(Http.Request request) {
        return withStaff(request, context -> {
            int page = page(request);
            String actor = optionalUuidQuery(request.queryString("actor").orElse(null));
            String subject = optionalUuidQuery(request.queryString("subject").orElse(null));
            String path = "/api/v1/admin/audit?page=" + page + "&size=20"
                    + (actor.isBlank() ? "" : "&actor=" + actor) + (subject.isBlank() ? "" : "&subject=" + subject);
            return api.get(context.session(), path).thenApply(response -> {
                if (!response.success()) return backendPageError(request, context, response, "Audit log");
                List<TableRow> rows = auditRows(response.json(), true);
                boolean next = response.json().path("hasNext").asBoolean(false);
                String base = routes.AdminController.audit().url() + "?page=";
                TablePage model = new TablePage("Audit log and activity history", "Immutable records of staff actions and permissioned views.", "audit",
                        List.of(new Column("time", "Time"), new Column("actor", "Actor"), new Column("action", "Action"),
                                new Column("subject", "Subject"), new Column("before", "Previous"), new Column("after", "New state")),
                        rows, page, next, page == 0 ? null : base + (page - 1), next ? base + (page + 1) : null);
                return noStore(ok(views.html.admin.table.render(csrf(request), context.staff(), context.session().fullName(), model,
                        flash(request, "error"), flash(request, "success"))));
            });
        });
    }

    @RequireCSRFCheck
    public CompletionStage<Result> exportAudit(Http.Request request) {
        String actor = optionalUuidQuery(field(request, "actor"));
        String subject = optionalUuidQuery(field(request, "subject"));
        String reason = bounded(field(request, "reason"), 500);
        if (reason.isBlank()) return CompletableFuture.completedFuture(redirect(routes.AdminController.audit()).flashing("error", "An audit reason is required."));
        ObjectNode body = Json.newObject().put("page", 0).put("size", 50).put("reason", reason);
        if (actor.isBlank()) body.putNull("actor"); else body.put("actor", actor);
        if (subject.isBlank()) body.putNull("subject"); else body.put("subject", subject);
        return withStaff(request, context -> api.post(context.session(), "/api/v1/admin/audit/export", body, UUID.randomUUID())
                .thenApply(response -> {
                    if (!response.success()) return mutationRedirect(request, context, response, routes.AdminController.audit().url(), "");
                    byte[] bytes = response.json().toPrettyString().getBytes(StandardCharsets.UTF_8);
                    return ok(bytes).as("application/json").withHeaders("Cache-Control", "no-store, private", "Pragma", "no-cache",
                            "Content-Disposition", "attachment; filename=makao-staff-audit.json", "X-Content-Type-Options", "nosniff");
                }));
    }

    @AddCSRFToken
    public CompletionStage<Result> staff(Http.Request request) {
        return withStaff(request, context -> api.get(context.session(), "/api/v1/admin/staff?page=0&size=50").thenApply(response -> {
            if (!response.success()) return backendPageError(request, context, response, "Staff roles and permissions");
            List<TableRow> rows = new ArrayList<>();
            for (JsonNode item : StaffModels.items(response.json())) {
                String id = StaffModels.text(item, "user_id");
                rows.add(new TableRow(StaffModels.values(
                        "reference", StaffModels.shortId(item, "user_id"), "name", StaffModels.text(item, "full_name"),
                        "role", StaffModels.text(item, "staff_role"),
                        "documents", item.path("can_view_documents").asBoolean(false) ? "Allowed" : "Not allowed",
                        "state", item.path("enabled").asBoolean(false) ? "Active" : "Disabled"
                ), routes.AdminController.changeStaff(id).url(), "Update"));
            }
            return noStore(ok(views.html.admin.team.render(csrf(request), context.staff(), context.session().fullName(),
                    List.copyOf(rows), flash(request, "error"), flash(request, "success"))));
        }));
    }

    @RequireCSRFCheck
    public CompletionStage<Result> inviteStaff(Http.Request request) {
        String email = field(request, "email").toLowerCase(Locale.ROOT);
        String role = requiredEnum(field(request, "role"), Set.of("STAFF_LEAD", "VERIFICATION_REVIEWER", "SAFETY_MODERATOR", "PAYMENT_REVIEWER"));
        String reason = bounded(field(request, "reason"), 500);
        boolean documents = checked(request, "documentReview");
        if (!validEmail(email) || role == null || reason.isBlank() || (documents && !"VERIFICATION_REVIEWER".equals(role))) {
            return CompletableFuture.completedFuture(redirect(routes.AdminController.staff()).flashing("error", "Check the email, role, document permission, and audit reason."));
        }
        ObjectNode body = Json.newObject().put("email", email).put("role", role).put("documentReview", documents).put("reason", reason);
        return withStaff(request, context -> api.post(context.session(), "/api/v1/admin/staff/invitations", body, UUID.randomUUID())
                .thenApply(response -> mutationRedirect(request, context, response, routes.AdminController.staff().url(), "Staff invitation sent.")));
    }

    @RequireCSRFCheck
    public CompletionStage<Result> changeStaff(Http.Request request, String staffId) {
        UUID id = parseUuid(staffId);
        String role = requiredEnum(field(request, "role"), Set.of("STAFF_LEAD", "VERIFICATION_REVIEWER", "SAFETY_MODERATOR", "PAYMENT_REVIEWER"));
        String reason = bounded(field(request, "reason"), 500);
        boolean documents = checked(request, "documentReview");
        if (id == null || role == null || reason.isBlank() || (documents && !"VERIFICATION_REVIEWER".equals(role))) {
            return CompletableFuture.completedFuture(redirect(routes.AdminController.staff()).flashing("error", "Check the permission change and audit reason."));
        }
        ObjectNode body = Json.newObject().put("role", role).put("documentReview", documents)
                .put("enabled", checked(request, "enabled")).put("reason", reason);
        return withStaff(request, context -> api.post(context.session(), "/api/v1/admin/staff/" + id + "/permissions", body, UUID.randomUUID())
                .thenApply(response -> mutationRedirect(request, context, response, routes.AdminController.staff().url(), "Staff permissions updated; active sessions were revoked.")));
    }

    @AddCSRFToken
    public CompletionStage<Result> activity(Http.Request request) {
        return withStaff(request, context -> {
            int page = page(request);
            return api.get(context.session(), "/api/v1/admin/activity?page=" + page + "&size=20").thenApply(response -> {
                if (!response.success()) return backendPageError(request, context, response, "Activity history");
                boolean next = response.json().path("hasNext").asBoolean(false);
                String base = routes.AdminController.activity().url() + "?page=";
                TablePage model = new TablePage("Your activity history", "Your own permissioned views and decisions.", "activity",
                        List.of(new Column("time", "Time"), new Column("action", "Action"), new Column("subject", "Subject"),
                                new Column("reason", "Reason"), new Column("before", "Previous"), new Column("after", "New state")),
                        auditRows(response.json(), false), page, next, page == 0 ? null : base + (page - 1), next ? base + (page + 1) : null);
                return noStore(ok(views.html.admin.table.render(csrf(request), context.staff(), context.session().fullName(), model,
                        flash(request, "error"), flash(request, "success"))));
            });
        });
    }

    private CompletionStage<Result> withStaff(Http.Request request, StaffAction action) {
        String id = request.session().get(StaffAuthController.ACTIVE_SESSION).orElse(null);
        var session = sessions.active(id);
        if (session.isEmpty()) {
            return CompletableFuture.completedFuture(noStore(redirect(routes.StaffAuthController.signIn())
                    .removingFromSession(request, StaffAuthController.ACTIVE_SESSION)
                    .flashing("error", "Sign in with staff MFA to continue.")));
        }
        return api.get(session.get(), "/api/v1/admin/me").thenCompose(response -> {
            if (!response.success()) {
                if (response.status() != 401 && response.status() != 403) {
                    return CompletableFuture.completedFuture(backendPageError(request,
                            new StaffContext(id, session.get(), null), response, "Staff workspace unavailable"));
                }
                sessions.deleteActive(id);
                return CompletableFuture.completedFuture(noStore(redirect(routes.StaffAuthController.signIn())
                        .removingFromSession(request, StaffAuthController.ACTIVE_SESSION)
                        .flashing("error", response.status() == 503 ? response.detail() : "Your staff session is no longer active.")));
            }
            try {
                StaffIdentity staff = new StaffIdentity(
                        StaffModels.text(response.json(), "userId"),
                        StaffModels.StaffRole.parse(StaffModels.text(response.json(), "role")),
                        response.json().path("documents").asBoolean(false)
                );
                return action.apply(new StaffContext(id, session.get(), staff));
            } catch (RuntimeException invalidResponse) {
                sessions.deleteActive(id);
                return CompletableFuture.completedFuture(noStore(internalServerError(views.html.errorPage.render(
                        "Staff access unavailable", "The server returned an invalid staff access response.",
                        routes.StaffAuthController.signIn().url(), "Return to sign in"))));
            }
        }).exceptionally(failure -> noStore(status(503, views.html.errorPage.render(
                "Server unavailable", "The Makao Hub server could not be reached.",
                routes.StaffAuthController.signIn().url(), "Return to sign in"))));
    }

    private Result mutationRedirect(Http.Request request, StaffContext context, StaffApiClient.ApiResponse response,
                                    String target, String successMessage) {
        if (response.status() == 401) {
            sessions.deleteActive(context.sessionId());
            return noStore(redirect(routes.StaffAuthController.signIn()).removingFromSession(request, StaffAuthController.ACTIVE_SESSION)
                    .flashing("error", "Your staff session expired. Sign in again."));
        }
        return noStore(redirect(target).flashing(response.success() ? "success" : "error",
                response.success() ? successMessage : response.detail()));
    }

    private Result backendPageError(Http.Request request, StaffContext context, StaffApiClient.ApiResponse response, String title) {
        if (response.status() == 401) {
            sessions.deleteActive(context.sessionId());
            return noStore(redirect(routes.StaffAuthController.signIn()).removingFromSession(request, StaffAuthController.ACTIVE_SESSION)
                    .flashing("error", "Your staff session expired. Sign in again."));
        }
        int status = response.status() >= 400 && response.status() <= 599 ? response.status() : 502;
        return noStore(status(status, views.html.errorPage.render(title, response.detail(),
                routes.AdminController.workspace().url(), "Return to workspace")));
    }

    private Result notFoundPage() {
        return noStore(notFound(views.html.errorPage.render("Record not found", "The requested staff record is unavailable.",
                routes.AdminController.workspace().url(), "Return to workspace")));
    }

    private static List<TableRow> auditRows(JsonNode response, boolean includeActor) {
        List<TableRow> rows = new ArrayList<>();
        for (JsonNode item : StaffModels.items(response)) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("time", StaffModels.text(item, "occurred_at"));
            if (includeActor) values.put("actor", StaffModels.text(item, "actor_name"));
            values.put("action", StaffModels.text(item, "action"));
            values.put("subject", StaffModels.shortId(item, "subject_id"));
            values.put("reason", StaffModels.text(item, "reason"));
            values.put("before", StaffModels.text(item, "previous_state"));
            values.put("after", StaffModels.text(item, "new_state"));
            rows.add(new TableRow(Map.copyOf(values), null, ""));
        }
        return List.copyOf(rows);
    }

    private static String verificationHref(String role, String state, String search, int page) {
        return routes.AdminController.verifications().url() + "?state=" + encode(state) + "&page=" + page
                + (role.isBlank() ? "" : "&role=" + encode(role))
                + (search.isBlank() ? "" : "&search=" + encode(search));
    }

    private static String evidenceSummary(String role) {
        return switch (role) {
            case "RENTER" -> "ID front + back + selfie";
            case "LANDLORD" -> "ID + selfie + ownership";
            case "SUBLESSOR" -> "ID + selfie + agreement";
            default -> "Required role evidence";
        };
    }

    private static String selectedDocument(Http.Request request, List<DocumentItem> documents) {
        String requested = request.queryString("document").orElse("");
        for (DocumentItem document : documents) {
            if (document.id().equals(requested)) return requested;
        }
        return documents.isEmpty() ? "" : documents.get(0).id();
    }

    private static int page(Http.Request request) {
        try {
            int value = Integer.parseInt(request.queryString("page").orElse(null));
            return Math.max(0, Math.min(value, 10_000));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static String bounded(String value, int max) {
        if (value == null) return "";
        String result = value.strip();
        return result.length() <= max ? result : result.substring(0, max);
    }

    private static String optionalEnum(String value, Set<String> allowed) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.toUpperCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : "";
    }

    private static String requiredEnum(String value, Set<String> allowed) {
        String normalized = value == null ? "" : value.toUpperCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : null;
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String optionalUuidQuery(String value) {
        UUID parsed = parseUuid(value);
        return parsed == null ? "" : parsed.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String allowedDocumentType(String contentType) {
        if (contentType == null) return null;
        String type = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return Set.of("application/pdf", "image/jpeg", "image/png").contains(type) ? type : null;
    }

    private static String allowedListingMediaType(String contentType) {
        if (contentType == null) return null;
        String type = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return Set.of("image/jpeg", "image/png", "image/webp", "video/mp4").contains(type) ? type : null;
    }

    private record StaffContext(String sessionId, StaffSessionStore.ActiveSession session, StaffIdentity staff) { }

    @FunctionalInterface
    private interface StaffAction {
        CompletionStage<Result> apply(StaffContext context);
    }
}

