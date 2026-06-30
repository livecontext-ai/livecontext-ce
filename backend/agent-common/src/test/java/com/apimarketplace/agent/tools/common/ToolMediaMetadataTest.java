package com.apimarketplace.agent.tools.common;

import com.apimarketplace.agent.domain.AttachmentType;
import com.apimarketplace.agent.domain.MessageAttachment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolMediaMetadata - vision media descriptor convention + heavy-bytes stripping")
class ToolMediaMetadataTest {

    @Test
    @DisplayName("imageDescriptor builds the {type,mimeType,dataBase64} shape the bridge expects")
    void imageDescriptorShape() {
        Map<String, Object> d = ToolMediaMetadata.imageDescriptor("image/png", "AAAB");

        assertThat(d)
                .containsEntry(ToolMediaMetadata.TYPE, "image")
                .containsEntry(ToolMediaMetadata.MIME_TYPE, "image/png")
                .containsEntry(ToolMediaMetadata.DATA_BASE64, "AAAB");
        // Exactly the three descriptor fields - nothing else leaks into the vision block.
        assertThat(d).hasSize(3);
    }

    @Test
    @DisplayName("withoutHeavyMedia replaces the heavy __media__ payload with a light count summary")
    void stripsHeavyMediaReplacingWithSummary() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("iconSlug", "image");
        metadata.put(ToolMediaMetadata.MEDIA_KEY, List.of(
                ToolMediaMetadata.imageDescriptor("image/png", "HEAVYBASE64A"),
                ToolMediaMetadata.imageDescriptor("image/png", "HEAVYBASE64B")));

        Map<String, Object> light = ToolMediaMetadata.withoutHeavyMedia(metadata);

        // Heavy bytes gone, light metadata preserved, summary records the count.
        assertThat(light).doesNotContainKey(ToolMediaMetadata.MEDIA_KEY);
        assertThat(light).containsEntry("iconSlug", "image");
        assertThat((String) light.get(ToolMediaMetadata.MEDIA_SUMMARY_KEY)).contains("2 media block(s)");
        // The base64 must not survive anywhere in the stripped map.
        assertThat(light.toString()).doesNotContain("HEAVYBASE64A").doesNotContain("HEAVYBASE64B");
    }

    @Test
    @DisplayName("withoutHeavyMedia never mutates the input map")
    void doesNotMutateInput() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ToolMediaMetadata.MEDIA_KEY, List.of(
                ToolMediaMetadata.imageDescriptor("image/png", "BYTES")));

        ToolMediaMetadata.withoutHeavyMedia(metadata);

        // Original retains its heavy payload - the helper returns a copy.
        assertThat(metadata).containsKey(ToolMediaMetadata.MEDIA_KEY);
        assertThat(metadata).doesNotContainKey(ToolMediaMetadata.MEDIA_SUMMARY_KEY);
    }

    @Test
    @DisplayName("withoutHeavyMedia is a no-op (same instance) when there is no media to strip")
    void noOpWhenNoMedia() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("iconSlug", "file");

        Map<String, Object> result = ToolMediaMetadata.withoutHeavyMedia(metadata);

        // Same reference returned untouched - no needless allocation on the common path.
        assertThat(result).isSameAs(metadata);
    }

    @Test
    @DisplayName("withoutHeavyMedia tolerates a null metadata map")
    void nullMetadataReturnsNull() {
        assertThat(ToolMediaMetadata.withoutHeavyMedia(null)).isNull();
    }

    @Test
    @DisplayName("withoutHeavyMedia summarizes a single non-list media payload as one block")
    void singleNonListMediaCountsAsOne() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ToolMediaMetadata.MEDIA_KEY, ToolMediaMetadata.imageDescriptor("image/png", "BYTES"));

        Map<String, Object> light = ToolMediaMetadata.withoutHeavyMedia(metadata);

        assertThat(light).doesNotContainKey(ToolMediaMetadata.MEDIA_KEY);
        assertThat((String) light.get(ToolMediaMetadata.MEDIA_SUMMARY_KEY)).contains("1 media block(s)");
    }

    // ---- toImageAttachments: direct-API vision path (AgentLoopExecutor) ----

    private static String b64(String raw) {
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("toImageAttachments decodes each image descriptor into an IMAGE MessageAttachment")
    void toImageAttachmentsDecodesImages() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ToolMediaMetadata.MEDIA_KEY, List.of(
                ToolMediaMetadata.imageDescriptor("image/png", b64("PNGBYTES")),
                ToolMediaMetadata.imageDescriptor("image/jpeg", b64("JPEGBYTES"))));

        List<MessageAttachment> out = ToolMediaMetadata.toImageAttachments(metadata);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).type()).isEqualTo(AttachmentType.IMAGE);
        assertThat(out.get(0).mimeType()).isEqualTo("image/png");
        assertThat(new String(out.get(0).data(), StandardCharsets.UTF_8)).isEqualTo("PNGBYTES");
        assertThat(out.get(1).mimeType()).isEqualTo("image/jpeg");
        assertThat(new String(out.get(1).data(), StandardCharsets.UTF_8)).isEqualTo("JPEGBYTES");
    }

    @Test
    @DisplayName("toImageAttachments returns empty list for null / no-media / non-list payload")
    void toImageAttachmentsEmptyCases() {
        assertThat(ToolMediaMetadata.toImageAttachments(null)).isEmpty();
        assertThat(ToolMediaMetadata.toImageAttachments(Map.of("iconSlug", "file"))).isEmpty();
        assertThat(ToolMediaMetadata.toImageAttachments(Map.of(ToolMediaMetadata.MEDIA_KEY, "not-a-list"))).isEmpty();
    }

    @Test
    @DisplayName("toImageAttachments skips non-image, blank, and malformed-base64 entries without poisoning the batch")
    void toImageAttachmentsSkipsBadEntries() {
        Map<String, Object> nonImage = ToolMediaMetadata.imageDescriptor("audio/mp3", b64("SOUND"));
        nonImage.put(ToolMediaMetadata.TYPE, "audio");
        Map<String, Object> blank = ToolMediaMetadata.imageDescriptor("image/png", "");
        Map<String, Object> malformed = ToolMediaMetadata.imageDescriptor("image/png", "!!!not-base64!!!");
        Map<String, Object> good = ToolMediaMetadata.imageDescriptor("image/png", b64("GOOD"));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ToolMediaMetadata.MEDIA_KEY, List.of(nonImage, blank, malformed, good));

        List<MessageAttachment> out = ToolMediaMetadata.toImageAttachments(metadata);

        // Only the single valid image survives - bad entries are skipped, not fatal.
        assertThat(out).hasSize(1);
        assertThat(new String(out.get(0).data(), StandardCharsets.UTF_8)).isEqualTo("GOOD");
    }

    @Test
    @DisplayName("toImageAttachments defaults a missing/blank mimeType to image/png and tolerates a missing type field")
    void toImageAttachmentsDefaultsMime() {
        Map<String, Object> noMime = new LinkedHashMap<>();
        noMime.put(ToolMediaMetadata.DATA_BASE64, b64("X")); // no type, no mimeType

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ToolMediaMetadata.MEDIA_KEY, List.of(noMime));

        List<MessageAttachment> out = ToolMediaMetadata.toImageAttachments(metadata);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).mimeType()).isEqualTo("image/png");
    }
}
