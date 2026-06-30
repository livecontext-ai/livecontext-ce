package com.apimarketplace.conversation.service.attachment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PdfTextExtractor")
class PdfTextExtractorTest {

    @Test
    @DisplayName("extracts the text layer of a real PDF")
    void extractsTextLayer() throws Exception {
        byte[] pdf = TestPdfFactory.singlePagePdf("Plagiarism check sample sentence.");

        String text = PdfTextExtractor.extract(pdf);

        assertThat(text).isNotNull();
        assertThat(text).contains("Plagiarism check sample sentence.");
    }

    @Test
    @DisplayName("trims surrounding whitespace from the extracted text")
    void trimsWhitespace() throws Exception {
        byte[] pdf = TestPdfFactory.singlePagePdf("Edge content");

        String text = PdfTextExtractor.extract(pdf);

        // PDFTextStripper appends a trailing line break; the extractor must trim it so the
        // value is not padded when inlined into the agent prompt.
        assertThat(text).isEqualTo(text.strip());
        assertThat(text).contains("Edge content");
    }

    @Test
    @DisplayName("returns null for a PDF with no text layer (scanned / image-only)")
    void returnsNullForNoTextLayer() throws Exception {
        byte[] pdf = TestPdfFactory.emptyPagePdf();

        // A blank extraction must degrade to null so the caller keeps the disk-Read fallback
        // (the bridge agent can still visually read a scanned PDF).
        assertThat(PdfTextExtractor.extract(pdf)).isNull();
    }

    @Test
    @DisplayName("returns null for null input")
    void returnsNullForNull() {
        assertThat(PdfTextExtractor.extract(null)).isNull();
    }

    @Test
    @DisplayName("returns null for empty input")
    void returnsNullForEmpty() {
        assertThat(PdfTextExtractor.extract(new byte[0])).isNull();
    }

    @Test
    @DisplayName("returns null (does not throw) for non-PDF / corrupt bytes")
    void returnsNullForCorruptBytes() {
        byte[] notAPdf = "this is plainly not a PDF document".getBytes();

        // Encrypted / corrupt / wrong-type bytes must never propagate an exception out of the
        // attachment load - they degrade to null.
        assertThat(PdfTextExtractor.extract(notAPdf)).isNull();
    }

    /** U+0000 built at runtime - never a literal in source, and the exact byte under test. */
    private static final String NUL = String.valueOf((char) 0);

    @Test
    @DisplayName("sanitize strips NUL (U+0000) that PostgreSQL rejects in text/JSONB")
    void sanitizeStripsNul() {
        assertThat(PdfTextExtractor.sanitize("clean" + NUL + "text" + NUL)).isEqualTo("cleantext");
    }

    @Test
    @DisplayName("sanitize trims surrounding whitespace")
    void sanitizeTrims() {
        assertThat(PdfTextExtractor.sanitize("  body  \n")).isEqualTo("body");
    }

    @Test
    @DisplayName("sanitize returns null for null, blank, and NUL-only input")
    void sanitizeReturnsNullForEmptyish() {
        assertThat(PdfTextExtractor.sanitize(null)).isNull();
        assertThat(PdfTextExtractor.sanitize("")).isNull();
        assertThat(PdfTextExtractor.sanitize("   ")).isNull();
        // A text layer that is only NUL bytes is "no usable text" once stripped.
        assertThat(PdfTextExtractor.sanitize(NUL + NUL)).isNull();
    }

    @Test
    @DisplayName("a real extracted PDF text layer is NUL-free")
    void extractedTextIsNulFree() throws Exception {
        String text = PdfTextExtractor.extract(TestPdfFactory.singlePagePdf("Body content"));
        assertThat(text).isNotNull();
        assertThat(text).doesNotContain(NUL);
    }
}
