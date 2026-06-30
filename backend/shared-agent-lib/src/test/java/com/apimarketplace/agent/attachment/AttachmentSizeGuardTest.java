package com.apimarketplace.agent.attachment;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.agent.domain.AttachmentType;
import com.apimarketplace.agent.domain.MessageAttachment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 1a.8 - verify that oversized inline attachments are rewritten to
 * {@code extractedText} (PDFs/text) or a named placeholder (images/binaries) and
 * that each rewrite emits exactly one {@code [ATTACHMENT_OVER_CAP]} log for the
 * Stage 0 {@code attachments.over_cap_forced} metric.
 */
@DisplayName("AttachmentSizeGuard")
class AttachmentSizeGuardTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(AttachmentSizeGuard.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private static MessageAttachment image(byte[] data) {
        return MessageAttachment.builder()
                .type(AttachmentType.IMAGE)
                .mimeType("image/png")
                .data(data)
                .fileName("shot.png")
                .build();
    }

    private static MessageAttachment pdf(byte[] data, String extracted) {
        return MessageAttachment.builder()
                .type(AttachmentType.PDF)
                .mimeType("application/pdf")
                .data(data)
                .fileName("report.pdf")
                .extractedText(extracted)
                .build();
    }

    @Test
    @DisplayName("returns original attachment untouched when size is within cap")
    void withinCapReturnsSameInstance() {
        MessageAttachment a = image(new byte[100_000]);
        MessageAttachment result = AttachmentSizeGuard.enforceSizeCap(a, 262144, "anthropic");
        assertThat(result).isSameAs(a);
        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("returns original attachment at exact cap boundary (inclusive)")
    void atExactCapReturnsSameInstance() {
        MessageAttachment a = image(new byte[262144]);
        MessageAttachment result = AttachmentSizeGuard.enforceSizeCap(a, 262144, "anthropic");
        assertThat(result).isSameAs(a);
        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("under-cap PDF WITH extractedText is returned untouched (native PDF block preserved, no double-send)")
    void underCapPdfWithExtractedTextIsUnchanged() {
        // Server-side PDF extraction now sets extractedText on every PDF chat attachment. For a
        // PDF UNDER the inline cap, the guard must still return the ORIGINAL attachment so the
        // provider emits its single native `document` block; setting extractedText must NOT
        // downgrade a small PDF to a TEXT part (that would change/duplicate what the model sees).
        MessageAttachment small = pdf(new byte[100_000], "Short PDF body extracted server-side.");
        MessageAttachment result = AttachmentSizeGuard.enforceSizeCap(small, 262144, "anthropic");

        assertThat(result).isSameAs(small);
        assertThat(result.type()).isEqualTo(AttachmentType.PDF);
        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("oversized image with no extractedText → named placeholder + WARN log")
    void oversizedImageFallsBackToPlaceholder() {
        MessageAttachment huge = image(new byte[500_000]); // ~488KB
        MessageAttachment result = AttachmentSizeGuard.enforceSizeCap(huge, 262144, "anthropic");

        assertThat(result.type()).isEqualTo(AttachmentType.TEXT);
        assertThat(result.mimeType()).isEqualTo("text/plain");
        assertThat(result.fileName()).isEqualTo("shot.png");
        assertThat(result.extractedText())
                .contains("shot.png")
                .contains("binary payload exceeds")
                .contains("inline cap");

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .startsWith("[ATTACHMENT_OVER_CAP]")
                .contains("provider=anthropic")
                .contains("fileName=\"shot.png\"")
                .contains("bytes=500000")
                .contains("cap=262144")
                .contains("fallback=placeholder");
    }

    @Test
    @DisplayName("oversized PDF with extractedText → keeps extracted content + logs extractedText fallback")
    void oversizedPdfUsesExtractedText() {
        MessageAttachment huge = pdf(new byte[3_000_000], "Q3 revenue up 14%. Expenses flat.");
        MessageAttachment result = AttachmentSizeGuard.enforceSizeCap(huge, 262144, "google");

        assertThat(result.type()).isEqualTo(AttachmentType.TEXT);
        assertThat(result.extractedText())
                .contains("report.pdf")
                .contains("text extracted")
                .contains("Q3 revenue up 14%. Expenses flat.");

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("provider=google")
                .contains("fallback=extractedText");
    }

    @Test
    @DisplayName("null attachment passes through without NPE")
    void nullAttachmentIsSafe() {
        assertThat(AttachmentSizeGuard.enforceSizeCap(null, 262144, "anthropic")).isNull();
        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("null data passes through untouched")
    void nullDataIsSafe() {
        MessageAttachment noBytes = MessageAttachment.builder()
                .type(AttachmentType.TEXT)
                .fileName("empty.txt")
                .build();
        MessageAttachment result = AttachmentSizeGuard.enforceSizeCap(noBytes, 262144, "anthropic");
        assertThat(result).isSameAs(noBytes);
        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("null fileName falls back to 'unnamed' in placeholder + log")
    void nullFileNameDoesNotNpe() {
        MessageAttachment unnamed = MessageAttachment.builder()
                .type(AttachmentType.IMAGE)
                .mimeType("image/jpeg")
                .data(new byte[300_000])
                .build();
        MessageAttachment result = AttachmentSizeGuard.enforceSizeCap(unnamed, 262144, "anthropic");

        assertThat(result.extractedText()).contains("unnamed");
        assertThat(appender.list.get(0).getFormattedMessage()).contains("fileName=\"unnamed\"");
    }

    @Test
    @DisplayName("blank extractedText falls back to placeholder path, not extracted")
    void blankExtractedTextUsesPlaceholder() {
        MessageAttachment huge = pdf(new byte[500_000], "   ");
        MessageAttachment result = AttachmentSizeGuard.enforceSizeCap(huge, 262144, "anthropic");

        assertThat(result.extractedText())
                .contains("binary payload exceeds")
                .doesNotContain("text extracted");
        assertThat(appender.list.get(0).getFormattedMessage()).contains("fallback=placeholder");
    }

    @Test
    @DisplayName("human-readable size rendering: MB vs KB vs bytes thresholds")
    void humanSizeThresholds() {
        MessageAttachment mb = image(new byte[1_500_000]); // ~1.4MB
        MessageAttachment result = AttachmentSizeGuard.enforceSizeCap(mb, 262144, "anthropic");
        assertThat(result.extractedText()).contains("MB");
    }

    @Test
    @DisplayName("providerName label passes through verbatim - openai, anthropic, google all land in the log")
    void providerNameLabelRoundTrips() {
        for (String provider : new String[]{"openai", "anthropic", "google"}) {
            appender.list.clear();
            MessageAttachment a = image(new byte[300_000]);
            AttachmentSizeGuard.enforceSizeCap(a, 262144, provider);
            assertThat(appender.list.get(0).getFormattedMessage())
                    .as("provider label must appear in log for %s", provider)
                    .contains("provider=" + provider);
        }
    }

    @Test
    @DisplayName("DEFAULT_MAX_INLINE_BYTES constant matches the 262144 used by provider @Value defaults")
    void defaultConstantMatchesProviderValueDefaults() {
        // If this assertion ever fails, bump the three provider @Value("${…:262144}")
        // defaults in lockstep with the constant. Guards against silent drift flagged
        // by the Stage 1a.8 audit.
        assertThat(AttachmentSizeGuard.DEFAULT_MAX_INLINE_BYTES).isEqualTo(262144);
    }

    // ---- 4-arg overload: IMAGE gets a larger cap than other binaries (tool-vision parity) ----

    @Test
    @DisplayName("DEFAULT_MAX_INLINE_IMAGE_BYTES is the larger 3.6MB image cap aligned with the producer")
    void imageDefaultConstantMatchesProducerCap() {
        // Aligned with files.view.image-inline-max-bytes (FilesToolsProvider). Keep the three
        // provider @Value("${ai.attachments.image-max-inline-bytes:3600000}") defaults in lockstep.
        assertThat(AttachmentSizeGuard.DEFAULT_MAX_INLINE_IMAGE_BYTES).isEqualTo(3_600_000);
    }

    @Test
    @DisplayName("4-arg: a 1MB image passes through under the image cap while the same bytes would be dropped by the binary cap")
    void imageUnderImageCapSurvives() {
        // 1MB image: above the 256KB binary cap, below the 3.6MB image cap → must survive,
        // because pre-fix this exact screenshot was silently rewritten to a text placeholder.
        MessageAttachment oneMb = image(new byte[1_000_000]);
        MessageAttachment result = AttachmentSizeGuard.enforceSizeCap(
                oneMb, 262144, AttachmentSizeGuard.DEFAULT_MAX_INLINE_IMAGE_BYTES, "anthropic");

        assertThat(result).isSameAs(oneMb);
        assertThat(result.type()).isEqualTo(AttachmentType.IMAGE);
        assertThat(appender.list).as("no over-cap downgrade for an image under the image cap").isEmpty();
    }

    @Test
    @DisplayName("4-arg: an image above the image cap still downgrades, logging cap=imageCap (not the binary cap)")
    void imageOverImageCapStillDowngrades() {
        MessageAttachment huge = image(new byte[4_000_000]); // > 3.6MB image cap
        MessageAttachment result = AttachmentSizeGuard.enforceSizeCap(
                huge, 262144, 3_600_000, "anthropic");

        assertThat(result.type()).isEqualTo(AttachmentType.TEXT);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("bytes=4000000")
                .contains("cap=3600000"); // measured against the image cap, not 262144
    }

    @Test
    @DisplayName("4-arg: a non-image binary above the small cap is still capped by maxInlineBytes, not the image cap")
    void nonImageStillUsesBinaryCap() {
        // A 1MB PDF: under the 3.6MB image cap but over the 256KB binary cap → must downgrade,
        // proving the image cap does NOT leak to non-image types.
        MessageAttachment huge = pdf(new byte[1_000_000], "extracted body");
        MessageAttachment result = AttachmentSizeGuard.enforceSizeCap(
                huge, 262144, 3_600_000, "google");

        assertThat(result.type()).isEqualTo(AttachmentType.TEXT);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("cap=262144");
    }

    @Test
    @DisplayName("3-arg overload remains a single uniform cap for every type, images included (back-compat)")
    void threeArgOverloadStillCapsImagesUniformly() {
        // The legacy 3-arg form must keep its old semantics: image measured against the SAME
        // cap as binaries. A 500KB image over a 256KB cap downgrades - identical to pre-change.
        MessageAttachment huge = image(new byte[500_000]);
        MessageAttachment result = AttachmentSizeGuard.enforceSizeCap(huge, 262144, "anthropic");

        assertThat(result.type()).isEqualTo(AttachmentType.TEXT);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("cap=262144");
    }
}
