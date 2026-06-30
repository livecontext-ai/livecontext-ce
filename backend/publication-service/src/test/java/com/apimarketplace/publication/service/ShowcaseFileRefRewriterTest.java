package com.apimarketplace.publication.service;

import com.apimarketplace.common.storage.signing.ShowcaseUrlSigner;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ShowcaseFileRefRewriter")
class ShowcaseFileRefRewriterTest {

    private static final String SECRET = "test-secret-32-bytes-long-enough-for-hmac";

    private ShowcaseUrlSigner signer;
    private SimpleMeterRegistry meterRegistry;
    private ShowcaseFileRefRewriter rewriter;
    private WorkflowPublicationEntity pub;

    @BeforeEach
    void setUp() {
        signer = new ShowcaseUrlSigner(SECRET);
        meterRegistry = new SimpleMeterRegistry();
        rewriter = new ShowcaseFileRefRewriter(signer, new com.fasterxml.jackson.databind.ObjectMapper(), meterRegistry, 15);
        pub = new WorkflowPublicationEntity();
        pub.setId(UUID.randomUUID());
        pub.setPublisherId("1");
    }

    @Test
    @DisplayName("Replaces a top-level FileRef in items[*].data with a /api/files/proxy-signed URL - regression for marketplace card showing <img src='{\"_type\":\"file\"...}'>")
    void replacesTopLevelFileRefWithSignedUrl() {
        Map<String, Object> fileRef = Map.of(
                "_type", "file",
                "path", "1/general/catalog-binary/abc.png",
                "name", "abc.png",
                "mimeType", "image/png",
                "size", 1234);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("openai_image", fileRef);
        data.put("prompt", "A sunset");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("data", data);

        List<Map<String, Object>> out = rewriter.rewriteItems(List.of(item), pub);

        Map<String, Object> outData = (Map<String, Object>) out.get(0).get("data");
        String url = (String) outData.get("openai_image");
        assertThat(url).startsWith("/api/files/proxy-signed?");
        assertThat(url).contains("key=1%2Fgeneral%2Fcatalog-binary%2Fabc.png");
        assertThat(url).contains("disposition=inline");
        assertThat(url).contains("sig=");
        assertThat(url).contains("exp=");
        // Non-FileRef fields untouched.
        assertThat(outData.get("prompt")).isEqualTo("A sunset");
    }

    @Test
    @DisplayName("URL signature round-trips through ShowcaseUrlSigner.verify - sign-side and verify-side use the same canonicalisation")
    void signatureRoundTripsThroughVerify() {
        Map<String, Object> fileRef = Map.of(
                "_type", "file", "path", "1/x.png",
                "name", "x.png", "mimeType", "image/png", "size", 1);
        List<Map<String, Object>> out = rewriter.rewriteItems(
                List.of(Map.of("data", Map.of("img", fileRef))), pub);
        String url = (String) ((Map<String, Object>) out.get(0).get("data")).get("img");

        // Parse URL and verify the signature using the SAME signer instance -
        // proves both sides agree on canonicalisation.
        java.util.Map<String, String> q = parseQuery(url);
        boolean ok = signer.verify(
                java.net.URLDecoder.decode(q.get("key"), java.nio.charset.StandardCharsets.UTF_8),
                Long.parseLong(q.get("exp")),
                q.get("disposition"),
                q.get("sig"),
                java.time.Instant.now().getEpochSecond());
        assertThat(ok).isTrue();
    }

    @Test
    @DisplayName("Recurses into nested maps and lists so deeply-nested FileRefs are also rewritten")
    void recursesIntoNestedStructures() {
        Map<String, Object> nestedFileRef = Map.of(
                "_type", "file", "path", "1/path/inner.png",
                "name", "i.png", "mimeType", "image/png", "size", 1);
        Map<String, Object> data = Map.of(
                "gallery", List.of(nestedFileRef),
                "wrapper", Map.of("inner", nestedFileRef));
        Map<String, Object> item = Map.of("data", data);

        List<Map<String, Object>> out = rewriter.rewriteItems(List.of(item), pub);

        Map<String, Object> outData = (Map<String, Object>) out.get(0).get("data");
        List<?> gallery = (List<?>) outData.get("gallery");
        assertThat(gallery.get(0)).asString().startsWith("/api/files/proxy-signed?");
        Map<String, Object> wrapper = (Map<String, Object>) outData.get("wrapper");
        assertThat(wrapper.get("inner")).asString().startsWith("/api/files/proxy-signed?");
    }

    @Test
    @DisplayName("Skips triggerData even when it embeds FileRefs - anti-leak guard for fields uploaded by acquirers in another tenant")
    void doesNotRewriteTriggerData() {
        Map<String, Object> fileRef = Map.of(
                "_type", "file", "path", "99/uploaded/by-acquirer.bin",
                "name", "x.bin", "mimeType", "application/octet-stream", "size", 1);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("data", Map.of("safe", "value"));
        item.put("triggerData", Map.of("trigger:form",
                Map.of("uploaded_file", fileRef)));

        List<Map<String, Object>> out = rewriter.rewriteItems(List.of(item), pub);

        Map<String, Object> outTrigger = (Map<String, Object>) out.get(0).get("triggerData");
        Map<String, Object> outForm = (Map<String, Object>) outTrigger.get("trigger:form");
        // Original FileRef object preserved (still a Map, not a String URL)
        assertThat(outForm.get("uploaded_file")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("Refuses to sign keys that don't begin with publisherId + '/' - defense in depth, prevents a corrupted snapshot from leaking another tenant's files")
    void refusesCrossTenantKey() {
        Map<String, Object> foreignFileRef = Map.of(
                "_type", "file", "path", "99/foreign-tenant.png",
                "name", "foreign.png", "mimeType", "image/png", "size", 1);
        Map<String, Object> item = Map.of("data", Map.of("img", foreignFileRef));

        List<Map<String, Object>> out = rewriter.rewriteItems(List.of(item), pub);

        // Original FileRef preserved (downstream renders broken image).
        Map<String, Object> outData = (Map<String, Object>) out.get(0).get("data");
        assertThat(outData.get("img")).isInstanceOf(Map.class);
        // Counter increments under skipped_foreign_key tag.
        assertThat(meterRegistry.counter("publication_showcase_presign_total",
                "status", "skipped_foreign_key").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Increments presign_total{status=ok} on success - gives ops a signal when the rewrite is healthy")
    void emitsOkMetricsOnSuccess() {
        Map<String, Object> fileRef = Map.of("_type", "file", "path", "1/ok.png",
                "name", "ok.png", "mimeType", "image/png", "size", 1);
        rewriter.rewriteItems(List.of(Map.of("data", Map.of("a", fileRef))), pub);

        assertThat(meterRegistry.counter("publication_showcase_presign_total", "status", "ok").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Disabled signer (no HMAC secret) returns items as-is so the anonymous render at least doesn't 500")
    void skipsWhenSignerDisabled() {
        ShowcaseUrlSigner disabled = new ShowcaseUrlSigner(null);
        ShowcaseFileRefRewriter r = new ShowcaseFileRefRewriter(disabled, new com.fasterxml.jackson.databind.ObjectMapper(), new SimpleMeterRegistry(), 15);
        Map<String, Object> fileRef = Map.of("_type", "file", "path", "1/x.png",
                "name", "x.png", "mimeType", "image/png", "size", 1);
        List<Map<String, Object>> items = List.of(Map.of("data", Map.of("img", fileRef)));

        List<Map<String, Object>> out = r.rewriteItems(items, pub);

        // Same reference returned (no mutation, no rewrite, no exception).
        assertThat(out).isSameAs(items);
    }

    @Test
    @DisplayName("No publisherId → skip rewrite entirely so we never accidentally sign a URL with empty/null tenant")
    void skipsWhenPublisherIdMissing() {
        pub.setPublisherId(null);
        Map<String, Object> fileRef = Map.of("_type", "file", "path", "1/x.png",
                "name", "x.png", "mimeType", "image/png", "size", 1);
        List<Map<String, Object>> items = List.of(Map.of("data", Map.of("img", fileRef)));

        List<Map<String, Object>> out = rewriter.rewriteItems(items, pub);

        assertThat(out).isSameAs(items);
    }

    @Test
    @DisplayName("Empty input list returns empty without invoking the signer")
    void emptyInputNoOps() {
        List<Map<String, Object>> out = rewriter.rewriteItems(List.of(), pub);
        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("PR2 regression: legacy flat-shape Map (file_url/file_name/...) is NOT signed - strict _type=='file' probe must reject it")
    void doesNotSignLegacyFlatFileShape() {
        // Pre-PR2 producer nodes emitted {file_url, file_name, file_size, content_type}.
        // PR2 removed that shape from the 4 producers but historical workflow_runs.state_snapshot
        // JSONB rows still carry it. ShowcaseFileRefRewriter must NOT sign these legacy Maps -
        // they would mint a URL bearing the raw `file_url` value (often already a /api/files/proxy
        // path) which can't be HMAC-signed cleanly. Strict `_type=='file'` probe is the gate.
        Map<String, Object> legacyMap = new LinkedHashMap<>();
        legacyMap.put("file_url", "/api/files/proxy?key=1/legacy.png");
        legacyMap.put("file_name", "legacy.png");
        legacyMap.put("file_size", 12345);
        legacyMap.put("content_type", "image/png");

        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("data", new LinkedHashMap<>(Map.of("img", legacyMap)));
        List<Map<String, Object>> items = List.of(wrapped);

        List<Map<String, Object>> out = rewriter.rewriteItems(items, pub);

        // Legacy Map must pass through untouched - no `path` rewrite, no sig query param.
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) out.get(0).get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> img = (Map<String, Object>) data.get("img");
        assertThat(img).containsEntry("file_url", "/api/files/proxy?key=1/legacy.png");
        assertThat(img).doesNotContainKey("_type");
        assertThat(img).doesNotContainKey("path");
        // Counter for successful signs must NOT have incremented for this item.
        // (refusesCrossTenantKey already asserts the counter mechanism works on the negative side.)
        assertThat(meterRegistry.find("showcase.filerefs.presign_total").tag("status", "ok").counter())
                .satisfiesAnyOf(
                        c -> assertThat(c).isNull(),
                        c -> assertThat(c.count()).isEqualTo(0.0)
                );
    }

    @Test
    @DisplayName("PR2 round-6: signs FileRefs whose path is in `_publications/{pubId}/` namespace (post copyFileRefsInRunState target)")
    void signsPublicationNamespaceKey() {
        // After `WorkflowPublicationService.copyFileRefsInRunState`, items[].data
        // FileRefs are namespace-copied to `_publications/{pubId}/...`. The
        // rewriter must accept this prefix in addition to the publisher's tenant
        // prefix - otherwise the namespace copy is signed-rejected and the
        // marketplace shows broken images even though the file was migrated.
        String pubNamespacePath = "_publications/" + pub.getId() + "/snapshot/runout/copied.png";
        Map<String, Object> fileRef = Map.of(
                "_type", "file",
                "path", pubNamespacePath,
                "name", "copied.png",
                "mimeType", "image/png",
                "size", 4567);
        List<Map<String, Object>> items = List.of(Map.of("data", Map.of("img", fileRef)));

        List<Map<String, Object>> out = rewriter.rewriteItems(items, pub);

        @SuppressWarnings("unchecked")
        Map<String, Object> outData = (Map<String, Object>) out.get(0).get("data");
        String url = (String) outData.get("img");
        assertThat(url).startsWith("/api/files/proxy-signed?");
        assertThat(url).contains("sig=");
        assertThat(meterRegistry.find("publication_showcase_presign_total").tag("status", "ok").counter().count())
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("PR2 round-6: still refuses to sign a key from ANOTHER publication's `_publications/<otherPubId>/` namespace")
    void refusesForeignPublicationNamespaceKey() {
        // Defense-in-depth: even though the publication namespace is now accepted,
        // it must be THIS publication's namespace - a key from another
        // publication's namespace (e.g. injected via a corrupted snapshot) must
        // still be refused. Otherwise a publisher could leak another
        // publication's files through their own marketplace card.
        java.util.UUID otherPubId = java.util.UUID.randomUUID();
        String foreignPubPath = "_publications/" + otherPubId + "/snapshot/runout/foreign.png";
        Map<String, Object> fileRef = Map.of(
                "_type", "file", "path", foreignPubPath,
                "name", "foreign.png", "mimeType", "image/png", "size", 1);
        List<Map<String, Object>> items = List.of(Map.of("data", Map.of("img", fileRef)));

        List<Map<String, Object>> out = rewriter.rewriteItems(items, pub);

        @SuppressWarnings("unchecked")
        Map<String, Object> outData = (Map<String, Object>) out.get(0).get("data");
        // Foreign publication key → not signed, FileRef map preserved as-is.
        assertThat(outData.get("img")).isInstanceOf(Map.class);
        assertThat(meterRegistry.find("publication_showcase_presign_total")
                .tag("status", "skipped_foreign_key").counter().count())
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("PR2 round-7 (D4): rewriteLanding signs FileRefs in landing.data (standalone INTERFACE publication)")
    void rewriteLandingSignsFileRefInData() {
        Map<String, Object> fileRef = Map.of(
                "_type", "file",
                "path", "1/landing/page-banner.png",
                "name", "banner.png",
                "mimeType", "image/png",
                "size", 4096);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("banner", fileRef);
        data.put("title", "Welcome");
        Map<String, Object> landing = new LinkedHashMap<>();
        landing.put("htmlTemplate", "<img src=\"{{banner}}\">{{title}}");
        landing.put("data", data);

        Map<String, Object> out = rewriter.rewriteLanding(landing, pub);

        @SuppressWarnings("unchecked")
        Map<String, Object> outData = (Map<String, Object>) out.get("data");
        String signedUrl = (String) outData.get("banner");
        assertThat(signedUrl).startsWith("/api/files/proxy-signed?");
        assertThat(signedUrl).contains("sig=");
        // Non-FileRef fields untouched.
        assertThat(outData.get("title")).isEqualTo("Welcome");
        // htmlTemplate untouched (no FileRef in it, just placeholder).
        assertThat(out.get("htmlTemplate")).isEqualTo("<img src=\"{{banner}}\">{{title}}");
    }

    @Test
    @DisplayName("PR2 round-7 (D4): rewriteLanding skips when signer is disabled (no HMAC secret)")
    void rewriteLandingSkipsWhenSignerDisabled() {
        // Build a rewriter with a disabled signer (null secret).
        ShowcaseUrlSigner disabledSigner = new ShowcaseUrlSigner(null);
        SimpleMeterRegistry localRegistry = new SimpleMeterRegistry();
        ShowcaseFileRefRewriter disabledRewriter = new ShowcaseFileRefRewriter(disabledSigner, new com.fasterxml.jackson.databind.ObjectMapper(), localRegistry, 15);
        Map<String, Object> landing = Map.of("htmlTemplate", "<img>",
                "data", Map.of("img", Map.of("_type", "file", "path", "1/x.png",
                        "name", "x.png", "mimeType", "image/png", "size", 1)));

        Map<String, Object> out = disabledRewriter.rewriteLanding(landing, pub);

        // Signer disabled → returns landing unchanged (degraded mode, FileRef Maps
        // land in HTML as JSON - broken images but no security leak).
        assertThat(out).isSameAs(landing);
    }

    @Test
    @DisplayName("PR2 round-7 (D4): rewriteLanding skips when publisherId is missing")
    void rewriteLandingSkipsWhenNoPublisherId() {
        pub.setPublisherId(null);
        Map<String, Object> landing = Map.of("htmlTemplate", "<img>",
                "data", Map.of("img", Map.of("_type", "file", "path", "1/x.png",
                        "name", "x.png", "mimeType", "image/png", "size", 1)));

        Map<String, Object> out = rewriter.rewriteLanding(landing, pub);

        assertThat(out).isSameAs(landing);
    }

    @Test
    @DisplayName("PR2 round-7 (D4): rewriteLanding handles null/empty landing gracefully")
    void rewriteLandingHandlesNullOrEmpty() {
        assertThat(rewriter.rewriteLanding(null, pub)).isNull();
        Map<String, Object> empty = new LinkedHashMap<>();
        assertThat(rewriter.rewriteLanding(empty, pub)).isSameAs(empty);
    }

    @Test
    @DisplayName("PR2 regression: FileRef with empty `path` is NOT signed - guards against shape with discriminator but no payload")
    void doesNotSignFileRefWithEmptyPath() {
        Map<String, Object> emptyPathRef = Map.of("_type", "file", "path", "",
                "name", "x.png", "mimeType", "image/png", "size", 1);
        List<Map<String, Object>> items = List.of(Map.of("data", Map.of("img", emptyPathRef)));

        List<Map<String, Object>> out = rewriter.rewriteItems(items, pub);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) out.get(0).get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> img = (Map<String, Object>) data.get("img");
        // path stays empty - no URL minted, no sign attempted.
        assertThat(img).containsEntry("path", "");
        assertThat(meterRegistry.find("showcase.filerefs.presign_total").tag("status", "ok").counter())
                .satisfiesAnyOf(
                        c -> assertThat(c).isNull(),
                        c -> assertThat(c.count()).isEqualTo(0.0)
                );
    }

    private static Map<String, String> parseQuery(String url) {
        Map<String, String> out = new LinkedHashMap<>();
        int q = url.indexOf('?');
        if (q < 0) return out;
        for (String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            out.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return out;
    }
}
