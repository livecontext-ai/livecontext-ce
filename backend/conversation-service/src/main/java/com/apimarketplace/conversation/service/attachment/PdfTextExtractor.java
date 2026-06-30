package com.apimarketplace.conversation.service.attachment;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Extracts the text layer of a PDF chat attachment so the agent receives the document
 * CONTENT, not just its file name.
 *
 * <p>Why this lives here and not in the orchestrator's {@code DocumentTextExtractor}: the
 * microservice boundary forbids conversation-service from depending on orchestrator-service,
 * and the only module both share ({@code agent-common}) is the wrong home for heavy
 * document-parsing dependencies (it would force PDFBox/POI/jsoup onto every service). It
 * mirrors orchestrator-service's {@code DocumentTextExtractor} PDF handling: the same PDFBox
 * 3.0.1 API ({@link Loader#loadPDF(byte[])} + {@link PDFTextStripper}) and the same NUL strip
 * that PostgreSQL text/JSONB requires, so the two extraction surfaces stay consistent.
 *
 * <p><b>Best-effort by design.</b> A scanned (image-only), encrypted, or corrupt PDF yields
 * {@code null} rather than throwing, so {@code AttachmentService.loadAttachments} leaves
 * {@code extractedText} unset and the downstream pipeline keeps its prior behaviour: the bridge
 * path writes the PDF to disk for the agent to Read (which can visually read a scanned PDF), and
 * the direct-API path degrades to the size-guard placeholder. Returning a real text layer when
 * one exists is what lets a large PDF (over the inline byte cap) reach the model at all.
 */
@Slf4j
public final class PdfTextExtractor {

    /** NUL (U+0000) built at runtime - PDFBox can emit it and PostgreSQL rejects it in text/JSONB. */
    private static final String NUL = String.valueOf((char) 0);

    private PdfTextExtractor() {}

    /**
     * Extract readable text from PDF {@code bytes}. Returns {@code null} when the input is
     * empty, the PDF has no usable text layer (scanned/image-only), or parsing fails
     * (encrypted/corrupt). The result is stripped of NUL ({@code U+0000}) bytes, which PDFBox
     * can emit and which PostgreSQL rejects in text/JSONB columns, and trimmed; a result that
     * is blank after stripping is returned as {@code null}.
     */
    public static String extract(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return sanitize(new PDFTextStripper().getText(document));
        } catch (Exception e) {
            // Encrypted / password-protected / corrupt / unparseable PDF. Degrade gracefully:
            // the caller leaves extractedText null and the downstream fallbacks take over.
            log.warn("[PDF_EXTRACT_FAILED] could not extract PDF text ({} bytes): {}",
                    bytes.length, e.toString());
            return null;
        }
    }

    /**
     * Normalise an extracted text layer: strip NUL ({@code U+0000}) - which PDFBox can emit and
     * PostgreSQL rejects in text/JSONB columns - then trim. A {@code null} input, or a result that
     * is blank after stripping (the scanned / image-only case), returns {@code null} so the caller
     * treats "no usable text" uniformly. Package-private so the NUL strip is directly unit-testable
     * (forcing PDFBox to emit a real U+0000 is impractical).
     */
    static String sanitize(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.replace(NUL, "").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
