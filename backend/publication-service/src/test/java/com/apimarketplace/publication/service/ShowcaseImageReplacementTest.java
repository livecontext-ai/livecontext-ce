package com.apimarketplace.publication.service;

import com.apimarketplace.common.storage.signing.ShowcaseUrlSigner;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Showcase image replacement - AI screening Step 4")
class ShowcaseImageReplacementTest {

    private static final String SECRET = "test-secret-32-bytes-long-enough-for-hmac";

    private ShowcaseUrlSigner signer;
    private ShowcaseFileRefRewriter rewriter;
    private ShowcaseSnapshotReader reader;
    private WorkflowPublicationEntity pub;

    @BeforeEach
    void setUp() {
        signer = new ShowcaseUrlSigner(SECRET);
        rewriter = new ShowcaseFileRefRewriter(signer, new ObjectMapper(), new SimpleMeterRegistry(), 15);
        reader = new ShowcaseSnapshotReader(rewriter);
        pub = new WorkflowPublicationEntity();
        pub.setId(UUID.randomUUID());
        pub.setPublisherId("1");
    }

    @Test
    @DisplayName("readInterfaceRender replaces external URL in htmlTemplate with signed proxy URL")
    void replacesExternalUrlInHtmlTemplate() {
        String externalUrl = "https://images.booking.com/hotel-lobby.jpg";
        String replacementPath = "_publications/" + pub.getId() + "/snapshot/ai-replace-abc/image.png";

        Map<String, Object> snapshot = snapshotWithHtml(
                "<img src=\"" + externalUrl + "\">",
                Map.of(externalUrl, replacementPath));

        pub.setShowcaseSnapshot(snapshot);

        Optional<Map<String, Object>> result = reader.readInterfaceRender(pub, "iface-1", 0, 10, null);
        assertThat(result).isPresent();

        String html = (String) result.get().get("htmlTemplate");
        assertThat(html).doesNotContain(externalUrl);
        assertThat(html).contains("/api/files/proxy-signed?key=");
        assertThat(html).contains("&sig=");
    }

    @Test
    @DisplayName("readInterfaceRender replaces external URL in cssTemplate")
    void replacesExternalUrlInCssTemplate() {
        String externalUrl = "https://cdn.airbnb.com/background.jpg";
        String replacementPath = "_publications/" + pub.getId() + "/snapshot/ai-replace-def/bg.png";

        Map<String, Object> defaultRender = new LinkedHashMap<>();
        defaultRender.put("htmlTemplate", "<div>no images here</div>");
        defaultRender.put("cssTemplate", "body { background: url('" + externalUrl + "'); }");
        defaultRender.put("jsTemplate", "");
        defaultRender.put("items", java.util.List.of());

        Map<String, Object> ifaceEntry = new LinkedHashMap<>();
        ifaceEntry.put("defaultRender", defaultRender);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("interfaceRenders", Map.of("iface-1", ifaceEntry));
        snapshot.put("imageReplacements", Map.of(externalUrl, replacementPath));

        pub.setShowcaseSnapshot(snapshot);

        Optional<Map<String, Object>> result = reader.readInterfaceRender(pub, "iface-1", 0, 10, null);
        assertThat(result).isPresent();

        String css = (String) result.get().get("cssTemplate");
        assertThat(css).doesNotContain(externalUrl);
        assertThat(css).contains("/api/files/proxy-signed?key=");
    }

    @Test
    @DisplayName("readInterfaceRender with no imageReplacements returns html unchanged")
    void noReplacementsReturnsHtmlUnchanged() {
        String html = "<img src=\"https://example.com/photo.jpg\">";
        Map<String, Object> snapshot = snapshotWithHtml(html, null);
        pub.setShowcaseSnapshot(snapshot);

        Optional<Map<String, Object>> result = reader.readInterfaceRender(pub, "iface-1", 0, 10, null);
        assertThat(result).isPresent();
        assertThat(result.get().get("htmlTemplate")).isEqualTo(html);
    }

    @Test
    @DisplayName("readInterfaceRender replaces same URL appearing multiple times")
    void replacesMultipleOccurrences() {
        String externalUrl = "https://cdn.example.com/img.jpg";
        String replacementPath = "_publications/" + pub.getId() + "/snapshot/ai-replace-xyz/img.png";

        String html = "<img src=\"" + externalUrl + "\"><div style=\"background:url(" + externalUrl + ")\">";
        Map<String, Object> snapshot = snapshotWithHtml(html, Map.of(externalUrl, replacementPath));
        pub.setShowcaseSnapshot(snapshot);

        Optional<Map<String, Object>> result = reader.readInterfaceRender(pub, "iface-1", 0, 10, null);
        assertThat(result).isPresent();

        String resultHtml = (String) result.get().get("htmlTemplate");
        assertThat(resultHtml).doesNotContain(externalUrl);
        int signedCount = countOccurrences(resultHtml, "/api/files/proxy-signed?key=");
        assertThat(signedCount).isEqualTo(2);
    }

    @Test
    @DisplayName("signReplacementUrls signs publication-namespaced keys")
    void signReplacementUrlsSignsPubNamespacedKeys() {
        Map<String, String> replacements = Map.of(
                "https://example.com/img.jpg",
                "_publications/" + pub.getId() + "/snapshot/ai-replace/img.png");

        Map<String, String> signed = rewriter.signReplacementUrls(replacements, pub);
        assertThat(signed).hasSize(1);
        assertThat(signed.get("https://example.com/img.jpg"))
                .startsWith("/api/files/proxy-signed?key=")
                .contains("&sig=");
    }

    @Test
    @DisplayName("signReplacementUrls rejects foreign-namespace keys")
    void signReplacementUrlsRejectsForeignKeys() {
        Map<String, String> replacements = Map.of(
                "https://example.com/img.jpg",
                "other-tenant/files/img.png");

        Map<String, String> signed = rewriter.signReplacementUrls(replacements, pub);
        assertThat(signed).isEmpty();
    }

    @Test
    @DisplayName("multiple replacements applied to same template")
    void multipleReplacementsApplied() {
        String url1 = "https://booking.com/hotel.jpg";
        String url2 = "https://airbnb.com/room.jpg";
        String path1 = "_publications/" + pub.getId() + "/snapshot/ai-replace-1/h.png";
        String path2 = "_publications/" + pub.getId() + "/snapshot/ai-replace-2/r.png";

        String html = "<img src=\"" + url1 + "\"><img src=\"" + url2 + "\">";
        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put(url1, path1);
        replacements.put(url2, path2);

        Map<String, Object> snapshot = snapshotWithHtml(html, replacements);
        pub.setShowcaseSnapshot(snapshot);

        Optional<Map<String, Object>> result = reader.readInterfaceRender(pub, "iface-1", 0, 10, null);
        assertThat(result).isPresent();

        String resultHtml = (String) result.get().get("htmlTemplate");
        assertThat(resultHtml).doesNotContain(url1);
        assertThat(resultHtml).doesNotContain(url2);
        assertThat(countOccurrences(resultHtml, "/api/files/proxy-signed?key=")).isEqualTo(2);
    }

    @Test
    @DisplayName("existing replacements merged with new ones at render time")
    void existingReplacementsMergedWithNew() {
        String url1 = "https://old-site.com/img1.jpg";
        String url2 = "https://new-site.com/img2.jpg";
        String path1 = "_publications/" + pub.getId() + "/snapshot/ai-replace-old/old.png";
        String path2 = "_publications/" + pub.getId() + "/snapshot/ai-replace-new/new.png";

        String html = "<img src=\"" + url1 + "\"><img src=\"" + url2 + "\">";

        Map<String, String> existingReplacements = new LinkedHashMap<>();
        existingReplacements.put(url1, path1);

        Map<String, Object> snapshot = snapshotWithHtml(html, existingReplacements);
        snapshot.put("imageReplacements", new LinkedHashMap<>(existingReplacements) {{
            put(url2, path2);
        }});
        pub.setShowcaseSnapshot(snapshot);

        Optional<Map<String, Object>> result = reader.readInterfaceRender(pub, "iface-1", 0, 10, null);
        assertThat(result).isPresent();

        String resultHtml = (String) result.get().get("htmlTemplate");
        assertThat(resultHtml).doesNotContain(url1);
        assertThat(resultHtml).doesNotContain(url2);
        assertThat(countOccurrences(resultHtml, "/api/files/proxy-signed?key=")).isEqualTo(2);
    }

    @Test
    @DisplayName("replacement for URL not present in HTML is a harmless no-op")
    void replacementForAbsentUrlIsNoOp() {
        String html = "<div>No images here</div>";
        String replacementPath = "_publications/" + pub.getId() + "/snapshot/ai-replace-x/x.png";
        Map<String, Object> snapshot = snapshotWithHtml(html,
                Map.of("https://not-in-html.com/img.jpg", replacementPath));
        pub.setShowcaseSnapshot(snapshot);

        Optional<Map<String, Object>> result = reader.readInterfaceRender(pub, "iface-1", 0, 10, null);
        assertThat(result).isPresent();
        assertThat(result.get().get("htmlTemplate")).isEqualTo(html);
    }

    // ===================== Wave 2b - replacements inside items[].data =====================

    @Test
    @DisplayName("readInterfaceRender replaces a raw scraped URL living in items[].data with a signed URL")
    void replacesRawUrlInItemData() {
        String externalUrl = "https://scontent.cdninstagram.com/v/t51/x_n.jpg?stp=y";
        String replacementPath = "_publications/" + pub.getId() + "/snapshot/ai-replace/repl.png";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("displayUrl", externalUrl);
        Map<String, Object> snapshot = snapshotWithItems("<div>{{displayUrl}}</div>", data,
                Map.of(externalUrl, replacementPath));
        pub.setShowcaseSnapshot(snapshot);

        Optional<Map<String, Object>> result = reader.readInterfaceRender(pub, "iface-1", 0, 10, null);
        assertThat(result).isPresent();

        Object value = firstItemData(result.get()).get("displayUrl");
        assertThat(value).isInstanceOf(String.class);
        assertThat((String) value)
                .doesNotContain("cdninstagram")
                .startsWith("/api/files/proxy-signed?key=")
                .contains("repl.png");
    }

    @Test
    @DisplayName("readInterfaceRender replaces a downloaded FileRef in items[].data, matched by namespaced basename")
    void replacesFileRefInItemData() {
        // Publisher saw this live path at screening time:
        String originalLivePath = "1/wf/run/step/472007201_n.jpg";
        String replacementPath = "_publications/" + pub.getId() + "/snapshot/ai-replace/new.png";
        // The snapshot copy re-namespaced the FileRef with a <hash>_ prefix:
        Map<String, Object> fileRef = new LinkedHashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", "_publications/" + pub.getId() + "/snapshot/abc123_472007201_n.jpg");
        fileRef.put("mimeType", "image/jpeg");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profilePicUrl", fileRef);
        Map<String, Object> snapshot = snapshotWithItems("<img src=\"{{profilePicUrl}}\">", data,
                Map.of(originalLivePath, replacementPath));
        pub.setShowcaseSnapshot(snapshot);

        Optional<Map<String, Object>> result = reader.readInterfaceRender(pub, "iface-1", 0, 10, null);
        assertThat(result).isPresent();

        Object value = firstItemData(result.get()).get("profilePicUrl");
        assertThat(value)
                .as("the FileRef should be replaced by the signed replacement URL string")
                .isInstanceOf(String.class);
        assertThat((String) value)
                .startsWith("/api/files/proxy-signed?key=")
                .contains("new.png")
                .doesNotContain("472007201");
    }

    @Test
    @DisplayName("a FileRef in items[].data with NO replacement is still signed normally (regression)")
    void unreplacedFileRefStillSigned() {
        Map<String, Object> fileRef = new LinkedHashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", "1/wf/run/step/keep_n.jpg"); // publisher-owned → rewriter signs it
        fileRef.put("mimeType", "image/jpeg");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profilePicUrl", fileRef);
        // A replacement exists for a DIFFERENT image - must not touch this one.
        Map<String, Object> snapshot = snapshotWithItems("<img src=\"{{profilePicUrl}}\">", data,
                Map.of("https://elsewhere.com/other.jpg",
                        "_publications/" + pub.getId() + "/snapshot/ai/other.png"));
        pub.setShowcaseSnapshot(snapshot);

        Optional<Map<String, Object>> result = reader.readInterfaceRender(pub, "iface-1", 0, 10, null);
        assertThat(result).isPresent();

        Object value = firstItemData(result.get()).get("profilePicUrl");
        assertThat(value).isInstanceOf(String.class);
        assertThat((String) value)
                .startsWith("/api/files/proxy-signed?key=")
                .contains("keep_n.jpg");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstItemData(Map<String, Object> render) {
        java.util.List<Map<String, Object>> items =
                (java.util.List<Map<String, Object>>) render.get("items");
        assertThat(items).isNotEmpty();
        return (Map<String, Object>) items.get(0).get("data");
    }

    private Map<String, Object> snapshotWithItems(String htmlTemplate, Map<String, Object> itemData,
                                                  Map<String, String> replacements) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("epoch", 0);
        item.put("itemIndex", 0);
        item.put("data", itemData);

        Map<String, Object> defaultRender = new LinkedHashMap<>();
        defaultRender.put("htmlTemplate", htmlTemplate);
        defaultRender.put("cssTemplate", "");
        defaultRender.put("jsTemplate", "");
        defaultRender.put("items", java.util.List.of(item));

        Map<String, Object> ifaceEntry = new LinkedHashMap<>();
        ifaceEntry.put("defaultRender", defaultRender);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("interfaceRenders", Map.of("iface-1", ifaceEntry));
        if (replacements != null && !replacements.isEmpty()) {
            snapshot.put("imageReplacements", replacements);
        }
        return snapshot;
    }

    private Map<String, Object> snapshotWithHtml(String htmlTemplate, Map<String, String> replacements) {
        Map<String, Object> defaultRender = new LinkedHashMap<>();
        defaultRender.put("htmlTemplate", htmlTemplate);
        defaultRender.put("cssTemplate", "");
        defaultRender.put("jsTemplate", "");
        defaultRender.put("items", java.util.List.of());

        Map<String, Object> ifaceEntry = new LinkedHashMap<>();
        ifaceEntry.put("defaultRender", defaultRender);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("interfaceRenders", Map.of("iface-1", ifaceEntry));
        if (replacements != null && !replacements.isEmpty()) {
            snapshot.put("imageReplacements", replacements);
        }
        return snapshot;
    }

    private static int countOccurrences(String str, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
