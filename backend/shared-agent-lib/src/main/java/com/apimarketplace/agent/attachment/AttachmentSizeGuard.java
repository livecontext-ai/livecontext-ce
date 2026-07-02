package com.apimarketplace.agent.attachment;

import com.apimarketplace.agent.domain.AttachmentType;
import com.apimarketplace.agent.domain.MessageAttachment;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * Stage 1a.8 - enforce an inline-attachment byte cap before a multimodal payload is
 * serialized into the provider body. Large base64 blobs (multi-megabyte PDFs, HD
 * screenshots) dominate input-token counts on a surprising share of chat turns; past
 * the cap, we degrade to {@code extractedText} when available, otherwise a named
 * placeholder.
 *
 * <p>This guard does NOT upload the binary to external storage; a separate stage
 * will introduce the {@code fileData}/{@code document.url} upload pipeline. Until
 * then, the agent's safest answer on an oversized binary is "I can see the file
 * name but cannot read the contents" - which is exactly what the placeholder
 * encodes. Every rewrite is logged as {@code [ATTACHMENT_OVER_CAP]} so Stage 0
 * telemetry can count the forced fallbacks.
 *
 * <p><b>Images get a larger cap than other binaries.</b> A PDF/text blob's byte size
 * maps almost linearly to input tokens, so 256&nbsp;KB is the right ceiling there. An
 * IMAGE is tokenised by its pixel <em>dimensions</em>, not its byte length - a 1&nbsp;MB
 * screenshot and a 256&nbsp;KB one cost the model nearly the same. Capping images at
 * 256&nbsp;KB would silently downgrade essentially every real screenshot/photo to a text
 * placeholder, defeating the whole point of tool-result vision (and the bridge path,
 * which has no such cap, would then SEE images the direct-API path cannot - a parity
 * bug). Images therefore get {@link #DEFAULT_MAX_INLINE_IMAGE_BYTES}, aligned with the
 * producer's inline cap ({@code files.view.image-inline-max-bytes}); the transport ceiling
 * of each provider is the real backstop.
 */
@Slf4j
public final class AttachmentSizeGuard {

    /** Default cap - 256 KB of raw binary, consistent with plan U8 / R9-E. */
    public static final int DEFAULT_MAX_INLINE_BYTES = 262144;

    /**
     * Default cap for {@link AttachmentType#IMAGE} attachments - 3.6 MB, aligned with the
     * producer's {@code files.view.image-inline-max-bytes} so a tool-emitted image that was
     * allowed inline upstream is not re-dropped here. Images are tokenised by dimensions,
     * not bytes, so this larger ceiling does not blow up the token count.
     */
    public static final int DEFAULT_MAX_INLINE_IMAGE_BYTES = 3_600_000;

    private AttachmentSizeGuard() {}

    /**
     * Backward-compatible 3-arg overload: applies a single {@code maxInlineBytes} cap to
     * EVERY attachment type, images included.
     *
     * @deprecated use the 4-arg overload in providers - applying the uniform (256 KB) cap
     *     to IMAGE attachments silently rewrites essentially every real screenshot/photo
     *     to a text placeholder and re-introduces the bridge/direct-API vision-parity bug
     *     this class exists to prevent. No main-source caller remains; kept only so
     *     pre-existing external callers keep compiling and are warned at the call site.
     *
     * <p>Never returns {@code null}. A {@code null} input is returned as-is.
     */
    @Deprecated
    public static MessageAttachment enforceSizeCap(
            MessageAttachment attachment,
            int maxInlineBytes,
            String providerName) {
        return enforceSizeCap(attachment, maxInlineBytes, maxInlineBytes, providerName);
    }

    /**
     * Returns the original attachment if it fits within the cap for its type; otherwise a
     * {@link AttachmentType#TEXT} substitute whose content is either the existing extracted
     * text (PDFs/text files) or a named-file placeholder. IMAGE attachments are measured
     * against {@code maxInlineImageBytes}; all other types against {@code maxInlineBytes}.
     *
     * <p>Never returns {@code null}. A {@code null} input is returned as-is.
     */
    public static MessageAttachment enforceSizeCap(
            MessageAttachment attachment,
            int maxInlineBytes,
            int maxInlineImageBytes,
            String providerName) {
        if (attachment == null || attachment.data() == null) {
            return attachment;
        }
        int cap = attachment.type() == AttachmentType.IMAGE ? maxInlineImageBytes : maxInlineBytes;
        int size = attachment.data().length;
        if (size <= cap) {
            return attachment;
        }

        boolean hasExtracted = attachment.extractedText() != null
                && !attachment.extractedText().isBlank();

        String fallbackText;
        if (hasExtracted) {
            fallbackText = "[File: " + safeName(attachment.fileName())
                    + " (" + humanSize(size) + ", text extracted)]\n"
                    + attachment.extractedText();
        } else {
            fallbackText = "[File: " + safeName(attachment.fileName())
                    + " (" + humanSize(size) + ") - binary payload exceeds "
                    + humanSize(cap) + " inline cap; contents not sent to provider]";
        }

        logOverCap(attachment, size, cap, providerName, hasExtracted);

        return MessageAttachment.builder()
                .type(AttachmentType.TEXT)
                .mimeType("text/plain")
                .fileName(attachment.fileName())
                .data(fallbackText.getBytes(StandardCharsets.UTF_8))
                .extractedText(fallbackText)
                .build();
    }

    private static String safeName(String fileName) {
        return fileName == null ? "unnamed" : fileName;
    }

    private static void logOverCap(
            MessageAttachment a,
            int size,
            int cap,
            String provider,
            boolean hasExtracted) {
        // Plain structured log - Loki/Grafana consumes the prefix, fields are JSON-ish.
        log.warn("[ATTACHMENT_OVER_CAP] provider={} fileName=\"{}\" mimeType=\"{}\" bytes={} cap={} fallback={}",
                provider,
                safeName(a.fileName()),
                a.mimeType() == null ? "unknown" : a.mimeType(),
                size,
                cap,
                hasExtracted ? "extractedText" : "placeholder");
    }

    private static String humanSize(int bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
    }
}
