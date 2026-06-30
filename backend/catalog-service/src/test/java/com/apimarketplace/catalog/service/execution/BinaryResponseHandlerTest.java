package com.apimarketplace.catalog.service.execution;

import com.apimarketplace.storage.client.StorageClient;
import com.apimarketplace.storage.client.dto.FileRefDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Validates that {@link BinaryResponseHandler} correctly:
 * - finds the first fileRef field in the output schema
 * - uploads bytes to MinIO via StorageClient
 * - emits a properly shaped FileRef map
 * - degrades safely on missing schema / missing storage / empty bytes
 */
class BinaryResponseHandlerTest {

    private ObjectMapper objectMapper;
    private StorageClient storageClient;
    private BinaryResponseHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        storageClient = mock(StorageClient.class);
        handler = new BinaryResponseHandler(objectMapper);
        // Inject mock via reflection (the field is @Autowired(required=false))
        Field f = BinaryResponseHandler.class.getDeclaredField("storageClient");
        f.setAccessible(true);
        f.set(handler, storageClient);
    }

    @Test
    @DisplayName("uploads bytes and returns a typed FileRef under the schema's first fileRef key")
    void uploadsAndProjects() {
        when(storageClient.genericUpload(eq("tenant-1"), anyString(), anyString(), anyString(), any(byte[].class)))
            .thenReturn(FileRefDto.of(
                "tenant-1/general/catalog-binary/abcd1234_clipdrop_xxxxxxxx.png",
                "clipdrop_xxxxxxxx.png",
                "image/png",
                4));

        String schema = "[{\"key\":\"image\",\"type\":\"fileRef\",\"description\":\"png\"}]";
        Map<String, Object> projected = handler.handle(
            new byte[]{1, 2, 3, 4}, "image/png", "tenant-1", schema, "clipdrop");

        assertEquals(1, projected.size());
        Map<?, ?> ref = (Map<?, ?>) projected.get("image");
        assertEquals("file", ref.get("_type"));
        assertEquals("tenant-1/general/catalog-binary/abcd1234_clipdrop_xxxxxxxx.png", ref.get("path"));
        assertEquals("clipdrop_xxxxxxxx.png", ref.get("name"));
        assertEquals("image/png", ref.get("mimeType"));
        assertEquals(4L, ref.get("size"));
        // v3.1 - `url` is intentionally NOT included in the FileRef. The
        // 60-min presigned link used to leak into the agent's tool-result
        // context for every catalog tool emitting a FileRef; consumers
        // (frontend chat card, Files panel, agent re-feeding the path)
        // re-resolve via the path-keyed file proxy on demand.
        org.junit.jupiter.api.Assertions.assertFalse(ref.containsKey("url"),
                "FileRef must not carry a presigned `url` - the proxy resolves it on demand");
    }

    @Test
    @DisplayName("returns empty map when bytes are empty")
    void emptyBytesReturnsEmpty() {
        Map<String, Object> projected = handler.handle(
            new byte[0], "image/png", "tenant", "[{\"key\":\"f\",\"type\":\"fileRef\",\"description\":\"x\"}]", "tool");
        assertTrue(projected.isEmpty());
        verifyNoInteractions(storageClient);
    }

    @Test
    @DisplayName("returns empty map when no fileRef field is declared in the schema")
    void schemaWithoutFileRef() {
        String schema = "[{\"key\":\"text\",\"type\":\"string\",\"description\":\"x\"}]";
        Map<String, Object> projected = handler.handle(
            new byte[]{1, 2, 3}, "image/png", "tenant", schema, "tool");
        assertTrue(projected.isEmpty());
        verifyNoInteractions(storageClient);
    }

    @Test
    @DisplayName("uses the FIRST fileRef field when multiple fileRefs are declared")
    void firstFileRefWins() {
        when(storageClient.genericUpload(any(), any(), any(), any(), any()))
            .thenReturn(FileRefDto.of("k", "n.png", "image/png", 1));
        String schema = "[" +
            "{\"key\":\"primary\",\"type\":\"fileRef\",\"description\":\"x\"}," +
            "{\"key\":\"secondary\",\"type\":\"fileRef\",\"description\":\"y\"}" +
            "]";
        Map<String, Object> projected = handler.handle(
            new byte[]{1}, "image/png", "tenant", schema, "tool");
        assertTrue(projected.containsKey("primary"));
        assertFalse(projected.containsKey("secondary"));
    }

    @Test
    @DisplayName("malformed schema returns empty map")
    void malformedSchema() {
        Map<String, Object> projected = handler.handle(
            new byte[]{1}, "image/png", "tenant", "{not valid", "tool");
        assertTrue(projected.isEmpty());
    }

    @Test
    @DisplayName("storage upload returning null is handled gracefully")
    void uploadNullReturnsEmpty() {
        when(storageClient.genericUpload(any(), any(), any(), any(), any())).thenReturn(null);
        String schema = "[{\"key\":\"img\",\"type\":\"fileRef\",\"description\":\"x\"}]";
        Map<String, Object> projected = handler.handle(
            new byte[]{1}, "image/png", "tenant", schema, "tool");
        assertTrue(projected.isEmpty());
    }

    @Test
    @DisplayName("resolves extension for audio variants (mp3, wav, opus, m4a, pcm, ulaw, flac)")
    void extensionAudioVariants() throws Exception {
        java.lang.reflect.Method m = BinaryResponseHandler.class
            .getDeclaredMethod("extensionFromMime", String.class);
        m.setAccessible(true);
        assertEquals(".mp3",  m.invoke(handler, "audio/mpeg"));
        assertEquals(".wav",  m.invoke(handler, "audio/wav"));
        assertEquals(".wav",  m.invoke(handler, "audio/x-wav"));
        assertEquals(".opus", m.invoke(handler, "audio/opus"));
        assertEquals(".m4a",  m.invoke(handler, "audio/mp4"));
        assertEquals(".flac", m.invoke(handler, "audio/flac"));
        assertEquals(".aac",  m.invoke(handler, "audio/aac"));
        assertEquals(".pcm",  m.invoke(handler, "audio/pcm"));
        assertEquals(".pcm",  m.invoke(handler, "audio/L16"));
        assertEquals(".ulaw", m.invoke(handler, "audio/basic"));
        assertEquals(".ulaw", m.invoke(handler, "audio/x-mulaw"));
        assertEquals(".alaw", m.invoke(handler, "audio/x-alaw"));
    }

    @Test
    @DisplayName("resolves extension for video variants (mp4, webm, mov, mkv, avi)")
    void extensionVideoVariants() throws Exception {
        java.lang.reflect.Method m = BinaryResponseHandler.class
            .getDeclaredMethod("extensionFromMime", String.class);
        m.setAccessible(true);
        assertEquals(".mp4",  m.invoke(handler, "video/mp4"));
        assertEquals(".webm", m.invoke(handler, "video/webm"));
        assertEquals(".mov",  m.invoke(handler, "video/quicktime"));
        assertEquals(".mkv",  m.invoke(handler, "video/x-matroska"));
        assertEquals(".avi",  m.invoke(handler, "video/x-msvideo"));
    }

    @Test
    @DisplayName("resolves extension for 3D models (glb, gltf) and modern image formats (avif, heic)")
    void extensionModelAndModernImages() throws Exception {
        java.lang.reflect.Method m = BinaryResponseHandler.class
            .getDeclaredMethod("extensionFromMime", String.class);
        m.setAccessible(true);
        assertEquals(".glb",  m.invoke(handler, "model/gltf-binary"));
        assertEquals(".gltf", m.invoke(handler, "model/gltf+json"));
        assertEquals(".avif", m.invoke(handler, "image/avif"));
        assertEquals(".heic", m.invoke(handler, "image/heic"));
    }

    @Test
    @DisplayName("strips charset parameter from Content-Type and still resolves correctly")
    void extensionStripsCharset() throws Exception {
        java.lang.reflect.Method m = BinaryResponseHandler.class
            .getDeclaredMethod("extensionFromMime", String.class);
        m.setAccessible(true);
        assertEquals(".json", m.invoke(handler, "application/json; charset=utf-8"));
        assertEquals(".mp3",  m.invoke(handler, "audio/mpeg; codecs=mp3"));
    }

    @Test
    @DisplayName("falls back to subtype for unknown MIME (e.g. application/x-tar -> .tar)")
    void extensionSubtypeFallback() throws Exception {
        java.lang.reflect.Method m = BinaryResponseHandler.class
            .getDeclaredMethod("extensionFromMime", String.class);
        m.setAccessible(true);
        // Subtype fallback strips x- and vnd. prefixes
        assertEquals(".tar", m.invoke(handler, "application/x-tar"));
        assertEquals(".bin", m.invoke(handler, "application/unknown-very-long-subtype-name"));
        // Truly unknown -> .bin
        assertEquals(".bin", m.invoke(handler, (Object) null));
    }

    @Test
    @DisplayName("handles uppercase and mixed-case MIME types (normalizes to lowercase)")
    void extensionUppercaseNormalization() throws Exception {
        java.lang.reflect.Method m = BinaryResponseHandler.class
            .getDeclaredMethod("extensionFromMime", String.class);
        m.setAccessible(true);
        assertEquals(".mp3",  m.invoke(handler, "AUDIO/MPEG"));
        assertEquals(".mp3",  m.invoke(handler, "Audio/Mpeg"));
        assertEquals(".png",  m.invoke(handler, "IMAGE/PNG"));
        assertEquals(".pdf",  m.invoke(handler, "Application/PDF"));
    }

    @Test
    @DisplayName("handles empty/blank MIME and SIP-telephony L16 with rate parameter")
    void extensionEdgeCases() throws Exception {
        java.lang.reflect.Method m = BinaryResponseHandler.class
            .getDeclaredMethod("extensionFromMime", String.class);
        m.setAccessible(true);
        // Empty string - no slash, no branches match, fallback returns .bin
        assertEquals(".bin", m.invoke(handler, ""));
        // SIP telephony: audio/L16;rate=16000 - semicolon stripped, startsWith "audio/l16" matches
        assertEquals(".pcm", m.invoke(handler, "audio/L16;rate=16000"));
        assertEquals(".pcm", m.invoke(handler, "audio/L24;rate=48000"));
        // ElevenLabs default: audio/mpeg with codecs parameter
        assertEquals(".mp3", m.invoke(handler, "audio/mpeg;codecs=mp3"));
    }

    // ════════════════════════════════════════════════════════════════════
    //  dehydrateInlineBase64 - walks JSON-shaped responses and replaces
    //  large base64 strings with FileRef maps. v1 of the catalog binary
    //  dehydration contract (Gemini image-gen, OpenAI image-gen, …).
    // ════════════════════════════════════════════════════════════════════

    /** Build a base64 string that decodes to N bytes of valid PNG (magic header + filler). */
    private static String pngBase64OfSize(int totalBytes) {
        byte[] png = new byte[totalBytes];
        // PNG signature: 89 50 4E 47 0D 0A 1A 0A
        png[0] = (byte) 0x89; png[1] = 'P'; png[2] = 'N'; png[3] = 'G';
        png[4] = 0x0D; png[5] = 0x0A; png[6] = 0x1A; png[7] = 0x0A;
        for (int i = 8; i < totalBytes; i++) png[i] = (byte) (i & 0xFF);
        return java.util.Base64.getEncoder().encodeToString(png);
    }

    @Test
    @DisplayName("dehydrate - large base64 PNG nested deep in a Gemini-shape map is replaced by a FileRef")
    void dehydrateGeminiLikeShape() {
        // Realistic shape: 80 KB PNG so we comfortably exceed the 64 KB threshold.
        String b64 = pngBase64OfSize(80 * 1024);
        when(storageClient.genericUpload(eq("tenant-9"), eq("catalog-binary"), anyString(), eq("image/png"), any(byte[].class)))
            .thenReturn(FileRefDto.of(
                "tenant-9/general/catalog-binary/key.png",
                "gemini_abc.png", "image/png", 81920));

        Map<String, Object> response = Map.of(
            "candidates", java.util.List.of(Map.of(
                "content", Map.of(
                    "parts", java.util.List.of(
                        Map.of("text", "Here is your image"),
                        Map.of("inlineData", Map.of("mimeType", "image/png", "data", b64))
                    ))))
        );

        BinaryResponseHandler.DehydrationResult result =
            handler.dehydrateInlineBase64(response, "tenant-9", "google-gemini-generate-content");

        assertTrue(result.hasAssets(), "should detect and dehydrate the inline PNG");
        assertEquals(1, result.assets().size());
        assertEquals("candidates[0].content.parts[1].inlineData.data",
            result.assets().get(0).sourcePath(),
            "source_path must record where in the original response the binary lived (debug + audit)");

        // The raw b64 string at that path must now be a FileRef Map.
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) result.root();
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> cands = (java.util.List<Map<String, Object>>) root.get("candidates");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> parts =
            (java.util.List<Map<String, Object>>) ((Map<String, Object>) cands.get(0).get("content")).get("parts");
        Object replaced = ((Map<String, Object>) parts.get(1).get("inlineData")).get("data");
        assertTrue(replaced instanceof Map, "the b64 string must be replaced by a Map (FileRef)");
        @SuppressWarnings("unchecked")
        Map<String, Object> ref = (Map<String, Object>) replaced;
        assertEquals("file", ref.get("_type"));
        assertEquals("image/png", ref.get("mimeType"));
        assertEquals("candidates[0].content.parts[1].inlineData.data", ref.get("source_path"));
    }

    @Test
    @DisplayName("dehydrate - small base64 (favicon, ~20 KB) is left untouched (below 64 KB threshold)")
    void dehydrateSmallBase64Untouched() {
        String b64 = pngBase64OfSize(20 * 1024);
        Map<String, Object> response = Map.of("data", java.util.List.of(Map.of("b64_json", b64)));

        BinaryResponseHandler.DehydrationResult result =
            handler.dehydrateInlineBase64(response, "tenant", "openai-create-image");

        assertFalse(result.hasAssets(), "20 KB binary must NOT be dehydrated (favicons / small thumbs)");
        assertEquals(response, result.root(), "no asset → root is structurally unchanged");
        verifyNoInteractions(storageClient);
    }

    @Test
    @DisplayName("dehydrate - large NON-base64 text (e.g. JSON dump) is left untouched")
    void dehydrateLargeNonBase64TextUntouched() {
        // 100 KB string that contains characters outside the base64 alphabet
        StringBuilder sb = new StringBuilder(100 * 1024);
        for (int i = 0; i < 100 * 1024; i++) sb.append("Hello, world! @ # $ % ^ & * ()_+ ");
        String bigText = sb.substring(0, 100 * 1024);
        Map<String, Object> response = Map.of("body", bigText);

        BinaryResponseHandler.DehydrationResult result =
            handler.dehydrateInlineBase64(response, "tenant", "some-tool");

        assertFalse(result.hasAssets(), "non-base64 text must NOT be dehydrated even when large");
        verifyNoInteractions(storageClient);
    }

    @Test
    @DisplayName("dehydrate - upload failure leaves the original b64 string in place (graceful degradation)")
    void dehydrateUploadFailureFallsBackInline() {
        String b64 = pngBase64OfSize(80 * 1024);
        when(storageClient.genericUpload(any(), any(), any(), any(), any()))
            .thenReturn(null); // simulates storage outage / null response

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("data", java.util.List.of(java.util.Map.of("b64_json", b64)));

        BinaryResponseHandler.DehydrationResult result =
            handler.dehydrateInlineBase64(response, "tenant", "openai-create-image");

        assertFalse(result.hasAssets(), "no assets recorded when upload fails");
        // Walk the rewritten tree: the b64 string must still be there
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) result.root();
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> data = (java.util.List<Map<String, Object>>) root.get("data");
        assertEquals(b64, data.get(0).get("b64_json"),
            "original b64 must be preserved on upload failure - better degradation than corruption");
    }

    @Test
    @DisplayName("dehydrate - multiple binaries in one response (e.g. n=4 OpenAI image-gen) all get dehydrated")
    void dehydrateMultipleBinaries() {
        String b64a = pngBase64OfSize(80 * 1024);
        String b64b = pngBase64OfSize(75 * 1024);
        when(storageClient.genericUpload(any(), any(), any(), any(), any()))
            .thenReturn(FileRefDto.of("k", "n.png", "image/png", 80000));

        Map<String, Object> response = Map.of("data", java.util.List.of(
            Map.of("b64_json", b64a),
            Map.of("b64_json", b64b)
        ));

        BinaryResponseHandler.DehydrationResult result =
            handler.dehydrateInlineBase64(response, "tenant", "openai-create-image");

        assertEquals(2, result.assets().size(), "both b64 leaves must be dehydrated");
        assertEquals("data[0].b64_json", result.assets().get(0).sourcePath());
        assertEquals("data[1].b64_json", result.assets().get(1).sourcePath());
        verify(storageClient, times(2)).genericUpload(any(), eq("catalog-binary"), any(), any(), any());
    }

    @Test
    @DisplayName("dehydrate - null storage client → NOOP (no partial state)")
    void dehydrateNoStorageClient() throws Exception {
        // Re-init handler with no storageClient injected
        BinaryResponseHandler bare = new BinaryResponseHandler(objectMapper);
        BinaryResponseHandler.DehydrationResult result = bare.dehydrateInlineBase64(
            Map.of("data", pngBase64OfSize(80 * 1024)), "tenant", "tool");
        assertFalse(result.hasAssets(), "without storage we must not partially dehydrate");
    }

    @Test
    @DisplayName("dehydrate - PNG with low-entropy C2PA metadata prefix (OpenAI gpt-image-1) is dehydrated via magic-byte early-accept")
    void dehydratePngWithLowEntropyC2paPrefix() {
        // Reproduces the prod bug (DB row id=24997, run epoch 6, OpenAI
        // create_image): gpt-image-1 prefixes its PNG with a JUMB metadata
        // box carrying C2PA content provenance - repetitive ASCII bytes that
        // drag the first-4 KB Shannon entropy to ~5.5, below the 6.0
        // threshold. Pre-fix: the entropy gate silently rejected the
        // payload, no upload, no FileRef, agent received raw 2 MB b64
        // round-trip. Post-fix: sniffMime detects the PNG magic in the
        // first 8 bytes and the entropy gate is bypassed for known
        // binaries - the entropy fallback now applies ONLY to unknown MIME
        // (still catches ASCII-text false positives).
        int totalBytes = 200 * 1024;
        byte[] png = new byte[totalBytes];
        png[0] = (byte) 0x89; png[1] = 'P'; png[2] = 'N'; png[3] = 'G';
        png[4] = 0x0D; png[5] = 0x0A; png[6] = 0x1A; png[7] = 0x0A;
        // Fill the next 4 KB with low-entropy "JUMB" / C2PA-style ASCII
        // text so byteEntropy on the prefix sample falls below 6.0.
        byte[] meta = ("JUMBjumbjumdcborjumbjumdcborjumbjumdjsonjumbjumb").getBytes();
        for (int i = 8; i < 8 + 4096; i++) png[i] = meta[i % meta.length];
        for (int i = 8 + 4096; i < totalBytes; i++) png[i] = (byte) ((i * 31) & 0xFF);

        // Sanity: confirm the first-4 KB entropy IS below threshold so the
        // pre-fix code would have rejected - this test fails meaningfully
        // only if the C2PA-style prefix really does drag entropy down.
        int n = Math.min(png.length, 4096);
        int[] freq = new int[256];
        for (int i = 0; i < n; i++) freq[png[i] & 0xFF]++;
        double h = 0.0;
        for (int c : freq) {
            if (c == 0) continue;
            double p = (double) c / n;
            h -= p * (Math.log(p) / Math.log(2));
        }
        assertTrue(h < 6.0,
            "regression test premise: prefix entropy must be < 6.0 to exercise the bypass; got " + h);

        when(storageClient.genericUpload(eq("tenant-1"), eq("catalog-binary"), anyString(), eq("image/png"), any(byte[].class)))
            .thenReturn(FileRefDto.of(
                "tenant-1/general/catalog-binary/abc_openai-create-image_xxx.png",
                "openai-create-image_xxx.png", "image/png", totalBytes));

        String b64 = java.util.Base64.getEncoder().encodeToString(png);
        Map<String, Object> response = Map.of(
            "created", 1777392771,
            "data", java.util.List.of(Map.of("b64_json", b64, "revised_prompt", "an apple")));

        BinaryResponseHandler.DehydrationResult result =
            handler.dehydrateInlineBase64(response, "tenant-1", "openai-create-image");

        assertTrue(result.hasAssets(),
            "real PNG with low-entropy C2PA prefix MUST be dehydrated - the magic bytes win over the entropy heuristic");
        assertEquals(1, result.assets().size());
        assertEquals("data[0].b64_json", result.assets().get(0).sourcePath());
        verify(storageClient).genericUpload(eq("tenant-1"), eq("catalog-binary"), anyString(), eq("image/png"), any(byte[].class));
    }

    @Test
    @DisplayName("dehydrate - entropy guard rejects low-entropy decoded payload (e.g. zero-padded blob)")
    void dehydrateEntropyGuard() {
        // 100 KB of pure 'A' characters. Base64 'A' = bit pattern 000000, so
        // decoding yields 75 KB of zero bytes → byte entropy = 0.0, well
        // below the 6.0 threshold. The decode succeeds, the size threshold
        // passes, but the entropy guard rejects → no upload, no FileRef.
        // This is the canonical false-positive shape: a string that
        // decodes successfully as base64 but doesn't carry actual binary
        // content (e.g. a >85 KB padding blob, an all-zero buffer, or a
        // long run of repeated letters that happen to satisfy the alphabet).
        char[] padded = new char[100 * 1024];
        java.util.Arrays.fill(padded, 'A');
        Map<String, Object> response = Map.of("body", new String(padded));

        BinaryResponseHandler.DehydrationResult result =
            handler.dehydrateInlineBase64(response, "tenant", "scraper");

        assertFalse(result.hasAssets(), "low-entropy decoded payload must NOT be dehydrated (false-positive guard)");
        verifyNoInteractions(storageClient);
    }

    @Test
    @DisplayName("dehydrate - URL-safe base64 (-_) is decoded and dehydrated like the standard alphabet")
    void dehydrateUrlSafeBase64() {
        // Build a real PNG, encode with URL-safe alphabet (substitutes - for + and _ for /).
        byte[] png = new byte[80 * 1024];
        png[0] = (byte) 0x89; png[1] = 'P'; png[2] = 'N'; png[3] = 'G';
        for (int i = 4; i < png.length; i++) png[i] = (byte) ((i * 7) & 0xFF);
        String urlSafe = java.util.Base64.getUrlEncoder().encodeToString(png);

        when(storageClient.genericUpload(any(), any(), any(), eq("image/png"), any()))
            .thenReturn(FileRefDto.of("k", "n.png", "image/png", 81920));

        BinaryResponseHandler.DehydrationResult result =
            handler.dehydrateInlineBase64(Map.of("token", urlSafe), "tenant", "tool");

        assertTrue(result.hasAssets(),
            "URL-safe base64 (e.g. JWT-style payloads) must be detected - without this, GCP/AWS signed-URL responses leak raw");
    }

    @Test
    @DisplayName("dehydrate - ftyp brand 'heic' resolves image/heic (not video/mp4 fallback)")
    void dehydrateHeicBrand() {
        // ISO BMFF header: ftyp box + brand "heic"
        byte[] heic = new byte[80 * 1024];
        heic[4] = 'f'; heic[5] = 't'; heic[6] = 'y'; heic[7] = 'p';
        heic[8] = 'h'; heic[9] = 'e'; heic[10] = 'i'; heic[11] = 'c';
        for (int i = 12; i < heic.length; i++) heic[i] = (byte) ((i * 13) & 0xFF);
        String b64 = java.util.Base64.getEncoder().encodeToString(heic);

        org.mockito.ArgumentCaptor<String> mimeCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        when(storageClient.genericUpload(any(), any(), any(), mimeCaptor.capture(), any()))
            .thenReturn(FileRefDto.of("k", "n.heic", "image/heic", 81920));

        handler.dehydrateInlineBase64(Map.of("d", b64), "tenant", "tool");

        assertEquals("image/heic", mimeCaptor.getValue(),
            "iOS HEIC photos must NOT be mis-typed as video/mp4 - frontend would render a broken video player");
    }

    @Test
    @DisplayName("dehydrate - ftyp brand 'avif' resolves image/avif")
    void dehydrateAvifBrand() {
        byte[] avif = new byte[80 * 1024];
        avif[4] = 'f'; avif[5] = 't'; avif[6] = 'y'; avif[7] = 'p';
        avif[8] = 'a'; avif[9] = 'v'; avif[10] = 'i'; avif[11] = 'f';
        for (int i = 12; i < avif.length; i++) avif[i] = (byte) ((i * 17) & 0xFF);
        String b64 = java.util.Base64.getEncoder().encodeToString(avif);

        org.mockito.ArgumentCaptor<String> mimeCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        when(storageClient.genericUpload(any(), any(), any(), mimeCaptor.capture(), any()))
            .thenReturn(FileRefDto.of("k", "n.avif", "image/avif", 81920));

        handler.dehydrateInlineBase64(Map.of("d", b64), "tenant", "tool");

        assertEquals("image/avif", mimeCaptor.getValue());
    }

    @Test
    @DisplayName("dehydrate - mime is sniffed from magic bytes (PNG, PDF) when upstream gave none")
    void dehydrateMimeSniffing() {
        // The mock returns whatever mimeType we PASS IN, so we capture the arg to verify sniffing.
        org.mockito.ArgumentCaptor<String> mimeCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        when(storageClient.genericUpload(any(), any(), any(), mimeCaptor.capture(), any()))
            .thenReturn(FileRefDto.of("k", "n.bin", "x", 1));

        // PNG magic bytes
        handler.dehydrateInlineBase64(Map.of("d", pngBase64OfSize(80 * 1024)), "tenant", "tool");
        assertEquals("image/png", mimeCaptor.getValue());

        // PDF magic bytes "%PDF-..." + high-entropy filler so the entropy
        // guard doesn't skip an all-zero buffer as "looks like text".
        byte[] pdf = new byte[80 * 1024];
        pdf[0] = '%'; pdf[1] = 'P'; pdf[2] = 'D'; pdf[3] = 'F';
        for (int i = 4; i < pdf.length; i++) pdf[i] = (byte) (i & 0xFF);
        String pdfB64 = java.util.Base64.getEncoder().encodeToString(pdf);
        handler.dehydrateInlineBase64(Map.of("d", pdfB64), "tenant", "tool");
        assertEquals("application/pdf", mimeCaptor.getValue());
    }
}
