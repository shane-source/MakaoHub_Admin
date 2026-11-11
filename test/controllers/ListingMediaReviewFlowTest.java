package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.Application;
import play.inject.guice.GuiceApplicationBuilder;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;
import play.test.Helpers;
import services.StaffSessionStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** Synthetic listing media only: proves the private staff gallery and moderation action work together. */
public final class ListingMediaReviewFlowTest {
    private static final String STAFF = "00000000-0000-0000-0000-000000000001";
    private static final String LISTING = "00000000-0000-0000-0000-000000000002";
    private static final String PHOTO_ONE = "00000000-0000-0000-0000-000000000003";
    private static final String PHOTO_TWO = "00000000-0000-0000-0000-000000000004";
    private static final String PHOTO_THREE = "00000000-0000-0000-0000-000000000005";
    private static final String VIDEO = "00000000-0000-0000-0000-000000000006";

    private Application app;
    private HttpServer backend;
    private String session;
    private byte[] imageBytes;
    private final byte[] videoBytes = "synthetic-mp4-fixture".getBytes(StandardCharsets.US_ASCII);
    private final AtomicInteger storageReads = new AtomicInteger();
    private final AtomicReference<JsonNode> decision = new AtomicReference<>();
    private volatile boolean returnActiveContent;

    @Before public void start() throws Exception {
        var image = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image);
        imageBytes = image.toByteArray();

        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/api/v1/admin/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/api/v1/admin/me".equals(path)) {
                reply(exchange, 200, "{\"userId\":\"" + STAFF + "\",\"role\":\"SAFETY_MODERATOR\",\"documents\":false}", "application/json");
            } else if (path.endsWith("/decision")) {
                decision.set(Json.parse(exchange.getRequestBody().readAllBytes()));
                reply(exchange, 200, "{\"moderation_status\":\"APPROVED\"}", "application/json");
            } else if (path.contains("/media/")) {
                storageReads.incrementAndGet();
                String id = path.substring(path.lastIndexOf('/') + 1);
                if (VIDEO.equals(id)) {
                    reply(exchange, 200, videoBytes, "video/mp4");
                } else {
                    String type = returnActiveContent && PHOTO_THREE.equals(id) ? "image/svg+xml" : "image/png";
                    reply(exchange, 200, imageBytes, type);
                }
            } else if (path.equals("/api/v1/admin/listings/LANDLORD/" + LISTING)
                    || path.equals("/api/v1/admin/listings/SUBLESSOR/" + LISTING)) {
                reply(exchange, 200, listing().toString(), "application/json");
            } else {
                reply(exchange, 404, "{\"detail\":\"Not found\"}", "application/problem+json");
            }
        });
        backend.start();

        app = new GuiceApplicationBuilder()
                .configure("makao.api.baseUrl", "http://127.0.0.1:" + backend.getAddress().getPort())
                .build();
        Helpers.start(app);
        session = app.injector().instanceOf(StaffSessionStore.class).activate(Json.newObject()
                .put("userId", STAFF).put("accessToken", "synthetic-access").put("refreshToken", "synthetic-refresh")
                .put("accessTokenExpiresAt", Instant.now().plusSeconds(300).toString())
                .put("refreshTokenExpiresAt", Instant.now().plusSeconds(3600).toString()));
    }

    @After public void stop() {
        if (app != null) Helpers.stop(app);
        if (backend != null) backend.stop(0);
    }

    @Test public void pageRendersEverySubmittedPhotoAndVideoWithoutLeakingSignedUrls() {
        Result page = get(listingPath());
        String html = Helpers.contentAsString(page);

        assertTrue(html.contains("Kijwetu Apartments"));
        assertEquals(3, occurrences(html, "<img src=\"" + mediaPath("")));
        assertEquals(1, occurrences(html, "<video controls"));
        assertTrue(html.contains("<source src=\"" + mediaPath(VIDEO) + "\" type=\"video/mp4\""));
        assertTrue(html.contains("3 photos"));
        assertTrue(html.contains("Video uploaded"));
        assertTrue(html.contains("Approve listing for publication"));
        assertFalse(html.contains("readUrl"));
        assertFalse(html.contains("127.0.0.1"));
        assertTrue(page.header("Content-Security-Policy").orElse("").contains("media-src 'self'"));
    }

    @Test public void sublessorUsesTheSameCompleteMediaReviewAndDecisionScreen() {
        String path = "/staff/listings/SUBLESSOR/" + LISTING;
        String html = Helpers.contentAsString(get(path));
        assertEquals(3, occurrences(html, "<img src=\"" + path + "/media/"));
        assertTrue(html.contains("<source src=\"" + path + "/media/" + VIDEO + "\" type=\"video/mp4\""));
        assertTrue(html.contains("Approve listing for publication"));
        assertTrue(html.contains("Reject and request changes"));
    }

    @Test public void proxyServesOnlyAuthenticatedAllowlistedPassiveMediaWithoutCaching() {
        Result photo = get(mediaPath(PHOTO_ONE));
        assertEquals("image/png", photo.contentType().orElseThrow());
        assertArrayEquals(imageBytes, Helpers.contentAsBytes(photo).toArray());
        assertTrue(photo.header("Cache-Control").orElse("").contains("no-store"));
        assertEquals("nosniff", photo.header("X-Content-Type-Options").orElseThrow());

        Result video = get(mediaPath(VIDEO));
        assertEquals("video/mp4", video.contentType().orElseThrow());
        assertArrayEquals(videoBytes, Helpers.contentAsBytes(video).toArray());
        assertTrue(video.header("Cache-Control").orElse("").contains("private"));

        int beforeAnonymous = storageReads.get();
        Result anonymous = Helpers.route(app, new Http.RequestBuilder().method("GET").uri(mediaPath(PHOTO_TWO)));
        assertEquals(303, anonymous.status());
        assertEquals("/staff/sign-in", anonymous.redirectLocation().orElseThrow());
        assertEquals(beforeAnonymous, storageReads.get());

        returnActiveContent = true;
        Result rejected = Helpers.route(app, new Http.RequestBuilder().method("GET")
                .uri(mediaPath(PHOTO_THREE)).session("staffSession", session));
        assertEquals(502, rejected.status());
        assertTrue(rejected.header("Cache-Control").orElse("").contains("no-store"));
    }

    @Test public void safetyModeratorCanApproveTheReviewedListing() {
        Result page = get(listingPath());
        String html = Helpers.contentAsString(page);
        var csrf = Pattern.compile("name=\"csrfToken\" value=\"([^\"]+)\"").matcher(html);
        assertTrue(csrf.find());

        Result approved = Helpers.route(app, new Http.RequestBuilder().method("POST")
                .uri(listingPath() + "/decision")
                .session("staffSession", session)
                .cookie(page.cookie("makao_staff_csrf").orElseThrow())
                .bodyForm(Map.of(
                        "csrfToken", csrf.group(1),
                        "approve", "true",
                        "expectedUpdatedAt", "2026-10-25T09:00:00Z",
                        "reason", "All submitted media and listing claims reviewed"
                )));

        assertEquals(303, approved.status());
        assertEquals("/staff/listings", approved.redirectLocation().orElseThrow());
        assertNotNull(decision.get());
        assertTrue(decision.get().path("approve").asBoolean());
        assertEquals("All submitted media and listing claims reviewed", decision.get().path("reason").asText());
    }

    private JsonNode listing() {
        var listing = Json.newObject()
                .put("id", LISTING)
                .put("title", "Kijwetu Apartments")
                .put("description", "Synthetic moderation fixture")
                .put("monthly_rent_kes", 40000)
                .put("listing_type", "APARTMENT")
                .put("bedrooms", "1")
                .put("public_area", "Ngara road")
                .put("owner_name", "Synthetic landlord")
                .put("owner_verified", true)
                .put("status", "DRAFT")
                .put("moderation_status", "PENDING")
                .put("moderation_reason", "")
                .put("updated_at", "2026-10-25T09:00:00Z")
                .put("locationSaved", true);
        var media = listing.putArray("media");
        addMedia(media.addObject(), PHOTO_ONE, "IMAGE", "image/png");
        addMedia(media.addObject(), PHOTO_TWO, "IMAGE", "image/png");
        addMedia(media.addObject(), PHOTO_THREE, "IMAGE", "image/png");
        addMedia(media.addObject(), VIDEO, "VIDEO", "video/mp4");
        return listing;
    }

    private void addMedia(com.fasterxml.jackson.databind.node.ObjectNode media, String id, String type, String contentType) {
        media.put("id", id)
                .put("media_type", type)
                .put("content_type", contentType);
    }

    private Result get(String path) {
        Result result = Helpers.route(app, new Http.RequestBuilder().method("GET").uri(path).session("staffSession", session));
        assertEquals(path, 200, result.status());
        return result;
    }

    private static String listingPath() {
        return "/staff/listings/LANDLORD/" + LISTING;
    }

    private static String mediaPath(String mediaId) {
        return listingPath() + "/media/" + mediaId;
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        for (int index = 0; (index = text.indexOf(token, index)) >= 0; index += token.length()) count++;
        return count;
    }

    private static void reply(HttpExchange exchange, int status, String text, String type) throws IOException {
        reply(exchange, status, text.getBytes(StandardCharsets.UTF_8), type);
    }

    private static void reply(HttpExchange exchange, int status, byte[] bytes, String type) throws IOException {
        try {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally {
            exchange.close();
        }
    }
}
