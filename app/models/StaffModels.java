package models;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class StaffModels {
    private StaffModels() { }

    public enum StaffRole {
        STAFF_LEAD,
        VERIFICATION_REVIEWER,
        SAFETY_MODERATOR,
        PAYMENT_REVIEWER;

        public static StaffRole parse(String value) {
            return StaffRole.valueOf(value == null ? "" : value.toUpperCase(Locale.ROOT));
        }

        public boolean canSee(String section) {
            return switch (section) {
                case "verifications" -> this == STAFF_LEAD || this == VERIFICATION_REVIEWER;
                case "listings", "flags", "reports" -> this == STAFF_LEAD || this == SAFETY_MODERATOR;
                case "payments" -> this == STAFF_LEAD || this == PAYMENT_REVIEWER;
                case "audit", "team" -> this == STAFF_LEAD;
                case "activity" -> true;
                default -> false;
            };
        }

        public String label() {
            return switch (this) {
                case STAFF_LEAD -> "Staff lead";
                case VERIFICATION_REVIEWER -> "Verification reviewer";
                case SAFETY_MODERATOR -> "Safety moderator";
                case PAYMENT_REVIEWER -> "Payment reviewer";
            };
        }
    }

    public record StaffIdentity(String userId, StaffRole role, boolean canViewDocuments) { }

    public record Column(String key, String label) { }

    public record TableRow(Map<String, String> values, String href, String actionLabel) {
        public String value(String key) {
            return values.getOrDefault(key, "—");
        }
    }

    public record TablePage(
            String title,
            String subtitle,
            String activeSection,
            List<Column> columns,
            List<TableRow> rows,
            int page,
            boolean hasNext,
            String previousHref,
            String nextHref
    ) { }

    public record ReviewerOption(String userId, String name) { }

    public record VerificationQueueRow(
            String userId,
            String reference,
            String applicant,
            String role,
            String submittedAt,
            String evidence,
            String state,
            String reviewerId,
            String reviewHref,
            String assignAction
    ) { }

    public record VerificationQueue(
            List<VerificationQueueRow> rows,
            List<ReviewerOption> reviewers,
            String roleFilter,
            String stateFilter,
            String search,
            int page,
            boolean hasNext,
            String previousHref,
            String nextHref
    ) { }

    public record DocumentItem(
            String id,
            String documentType,
            String contentType,
            String size,
            String state,
            boolean viewed,
            boolean reviewed,
            String viewHref,
            String reviewAction
    ) { }

    public record ApplicationRemark(String listingTitle, String remark) {
        @Override public String toString() { return "ApplicationRemark[redacted]"; }
    }

    public record VerificationCase(
            String userId,
            String applicant,
            String role,
            String state,
            String submittedAt,
            String updatedAt,
            String rejectionReason,
            String reviewer,
            List<DocumentItem> documents,
            String selectedDocumentId
    ) { }

    public record MediaItem(String id, String mediaType, String contentType, String viewHref) { }

    public record ListingCase(
            String id,
            String kind,
            String title,
            String description,
            String rent,
            String listingType,
            String bedrooms,
            String publicArea,
            String ownerName,
            boolean ownerVerified,
            String listingState,
            String moderationState,
            String moderationReason,
            String updatedAt,
            boolean locationSaved,
            List<MediaItem> media
    ) {
        public long imageCount() {
            return media.stream().filter(item -> "IMAGE".equals(item.mediaType())).count();
        }

        public boolean videoUploaded() {
            return media.stream().anyMatch(item -> "VIDEO".equals(item.mediaType()));
        }
    }

    public record ActionItem(
            Map<String, String> values,
            String actionPath,
            String id,
            String kind
    ) {
        public String value(String key) {
            return values.getOrDefault(key, "—");
        }
    }

    public record ActionQueue(
            String title,
            String subtitle,
            String activeSection,
            List<ActionItem> items,
            int page,
            boolean hasNext,
            String previousHref,
            String nextHref
    ) { }

    public record PaymentPage(List<TableRow> payments, List<TableRow> bookings, int page, boolean paymentNext, boolean bookingNext) { }

    public static List<JsonNode> items(JsonNode page) {
        var result = new ArrayList<JsonNode>();
        JsonNode items = page == null ? null : page.path("items");
        if (items != null && items.isArray()) {
            items.forEach(result::add);
        }
        return List.copyOf(result);
    }

    public static String text(JsonNode node, String key) {
        if (node == null) return "—";
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return "—";
        String text = value.asText().strip();
        return text.isEmpty() ? "—" : text;
    }

    public static String shortId(JsonNode node, String key) {
        String value = text(node, key);
        return value.length() <= 8 ? value : value.substring(0, 8).toUpperCase(Locale.ROOT);
    }

    public static String shortId(String value) {
        if (value == null || value.isBlank()) return "—";
        return value.length() <= 8 ? value : value.substring(0, 8).toUpperCase(Locale.ROOT);
    }

    public static String money(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.path(key);
        if (value == null || !value.isNumber()) return "—";
        long minor = value.asLong();
        return "KSh " + String.format(Locale.ROOT, "%,d", minor / 100);
    }

    public static String rent(JsonNode node) {
        JsonNode value = node == null ? null : node.path("monthly_rent_kes");
        if (value == null || !value.isNumber()) return "—";
        return "KSh " + String.format(Locale.ROOT, "%,d", value.asLong()) + " / month";
    }

    public static String bytes(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.path(key);
        if (value == null || !value.isNumber()) return "—";
        long size = value.asLong();
        if (size >= 1_048_576) return String.format(Locale.ROOT, "%.1f MB", size / 1_048_576.0);
        if (size >= 1_024) return String.format(Locale.ROOT, "%.0f KB", size / 1_024.0);
        return size + " bytes";
    }

    public static Map<String, String> values(Object... entries) {
        if (entries.length % 2 != 0) throw new IllegalArgumentException("Entries must be key/value pairs.");
        var result = new LinkedHashMap<String, String>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put(String.valueOf(entries[index]), String.valueOf(entries[index + 1]));
        }
        return Map.copyOf(result);
    }

    public static String safeState(String value, Set<String> allowed, String fallback) {
        String candidate = value == null ? fallback : value.toUpperCase(Locale.ROOT);
        return allowed.contains(candidate) ? candidate : fallback;
    }

    public static String safeInstant(String value) {
        try {
            return Instant.parse(value).toString();
        } catch (RuntimeException ignored) {
            return "—";
        }
    }
}

