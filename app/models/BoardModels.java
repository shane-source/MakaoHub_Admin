package models;

import java.util.List;

/** View models for the listing moderation board (list + detail panel). */
public final class BoardModels {
    private BoardModels() { }

    public record ListingCard(String id, String kind, String title, String owner, String rent, String moderation,
                              long reports, String thumbHref, String selectHref, String inspectHref, String submitted) { }

    public record ReportEntry(String category, String description, String state, String created) { }

    public record Panel(StaffModels.ListingCase item, String heroHref, List<ReportEntry> reports, String closeHref) { }

    public record Board(String kind, String state, String sort, List<ListingCard> cards, long total, int page, boolean hasNext,
                        String previousHref, String nextHref, List<StaffModels.PageLink> links, Panel panel) { }
}
