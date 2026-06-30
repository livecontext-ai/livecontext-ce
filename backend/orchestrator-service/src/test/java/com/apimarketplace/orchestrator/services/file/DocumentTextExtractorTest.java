package com.apimarketplace.orchestrator.services.file;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DocumentTextExtractor - shared bytes-to-text extraction")
class DocumentTextExtractorTest {

    // ==================== classify ====================

    @Test
    @DisplayName("classify maps each supported family by MIME, and returns null for non-text binaries")
    void classifyByMime() {
        assertThat(DocumentTextExtractor.classify("application/pdf", "x")).isEqualTo(DocumentTextExtractor.DocKind.PDF);
        assertThat(DocumentTextExtractor.classify(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "x"))
                .isEqualTo(DocumentTextExtractor.DocKind.DOCX);
        assertThat(DocumentTextExtractor.classify(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "x"))
                .isEqualTo(DocumentTextExtractor.DocKind.XLSX);
        assertThat(DocumentTextExtractor.classify("text/html", "x")).isEqualTo(DocumentTextExtractor.DocKind.HTML);
        assertThat(DocumentTextExtractor.classify("text/plain", "x")).isEqualTo(DocumentTextExtractor.DocKind.TEXT);
        assertThat(DocumentTextExtractor.classify("application/json", "x")).isEqualTo(DocumentTextExtractor.DocKind.TEXT);
        assertThat(DocumentTextExtractor.classify("text/csv", "x")).isEqualTo(DocumentTextExtractor.DocKind.TEXT);
    }

    @Test
    @DisplayName("classify falls back to the filename extension when MIME is generic/absent")
    void classifyByExtension() {
        assertThat(DocumentTextExtractor.classify("application/octet-stream", "report.pdf"))
                .isEqualTo(DocumentTextExtractor.DocKind.PDF);
        assertThat(DocumentTextExtractor.classify(null, "data.csv")).isEqualTo(DocumentTextExtractor.DocKind.TEXT);
        assertThat(DocumentTextExtractor.classify("", "script.py")).isEqualTo(DocumentTextExtractor.DocKind.TEXT);
        assertThat(DocumentTextExtractor.classify(null, "notes.docx")).isEqualTo(DocumentTextExtractor.DocKind.DOCX);
    }

    @Test
    @DisplayName("classify returns null for images, audio, video, archives and unknown binaries (no text view)")
    void classifyNullForNonText() {
        assertThat(DocumentTextExtractor.classify("image/png", "shot.png")).isNull();
        assertThat(DocumentTextExtractor.classify("audio/mpeg", "song.mp3")).isNull();
        assertThat(DocumentTextExtractor.classify("video/mp4", "clip.mp4")).isNull();
        assertThat(DocumentTextExtractor.classify("application/zip", "bundle.zip")).isNull();
        assertThat(DocumentTextExtractor.classify("application/octet-stream", "blob.bin")).isNull();
        // Legacy binary Office formats are NOT OOXML and are not handled here.
        assertThat(DocumentTextExtractor.classify("application/msword", "old.doc")).isNull();
    }

    @Test
    @DisplayName("an image MIME wins even when the filename looks like a document (never extracted as text)")
    void imageMimeBeatsDocumentExtension() {
        assertThat(DocumentTextExtractor.classify("image/png", "trap.pdf")).isNull();
    }

    // ==================== extract ====================

    @Test
    @DisplayName("extract reads a real PDF's text layer")
    void extractPdf() throws Exception {
        byte[] pdf = makePdf("Hello dissertation chapter one");
        String text = DocumentTextExtractor.extract(pdf, "application/pdf", "thesis.pdf");
        assertThat(text).contains("Hello dissertation chapter one");
    }

    @Test
    @DisplayName("extract reads a real .docx paragraph text")
    void extractDocx() throws Exception {
        byte[] docx = makeDocx("First paragraph.", "Second paragraph.");
        String text = DocumentTextExtractor.extract(docx,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "memo.docx");
        assertThat(text).contains("First paragraph.").contains("Second paragraph.");
    }

    @Test
    @DisplayName("extract flattens a real .xlsx to tab-separated rows")
    void extractXlsx() throws Exception {
        byte[] xlsx = makeXlsx();
        String text = DocumentTextExtractor.extract(xlsx,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "data.xlsx");
        assertThat(text).contains("name\tage");
        assertThat(text).contains("Alice\t30");
    }

    @Test
    @DisplayName("extract strips HTML tags to readable text")
    void extractHtml() throws Exception {
        byte[] html = "<html><body><h1>Title</h1><p>Body text here</p></body></html>"
                .getBytes(StandardCharsets.UTF_8);
        String text = DocumentTextExtractor.extract(html, "text/html", "page.html");
        assertThat(text).contains("Title").contains("Body text here");
        assertThat(text).doesNotContain("<h1>");
    }

    @Test
    @DisplayName("extract returns plain UTF-8 for text/code files")
    void extractPlainText() throws Exception {
        byte[] csv = "a,b,c\n1,2,3".getBytes(StandardCharsets.UTF_8);
        assertThat(DocumentTextExtractor.extract(csv, "text/csv", "rows.csv")).isEqualTo("a,b,c\n1,2,3");
    }

    @Test
    @DisplayName("extract returns null (not an exception) for a non-text type, so the caller can fall back to a link")
    void extractNullForBinary() throws Exception {
        assertThat(DocumentTextExtractor.extract(new byte[]{1, 2, 3}, "image/png", "x.png")).isNull();
        assertThat(DocumentTextExtractor.extract(new byte[]{1, 2, 3}, "application/zip", "x.zip")).isNull();
    }

    @Test
    @DisplayName("extract returns null for empty/null bytes")
    void extractNullForNoBytes() throws Exception {
        assertThat(DocumentTextExtractor.extract(null, "application/pdf", "x.pdf")).isNull();
        assertThat(DocumentTextExtractor.extract(new byte[0], "application/pdf", "x.pdf")).isNull();
    }

    @Test
    @DisplayName("extract strips NUL bytes that PostgreSQL would reject in text/JSONB columns")
    void extractStripsNulBytes() throws Exception {
        String nul = String.valueOf((char) 0);
        byte[] withNul = ("abc" + nul + "def").getBytes(StandardCharsets.UTF_8);
        String text = DocumentTextExtractor.extract(withNul, "text/plain", "x.txt");
        assertThat(text).isEqualTo("abcdef");
        assertThat(text).doesNotContain(nul);
    }

    @Test
    @DisplayName("extract throws on bytes that claim a type but are corrupt (caller decides how to degrade)")
    void extractThrowsOnCorruptDocument() {
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
                DocumentTextExtractor.extract("not a real pdf".getBytes(StandardCharsets.UTF_8),
                        "application/pdf", "broken.pdf"));
    }

    // ==================== fixtures ====================

    private static byte[] makePdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    private static byte[] makeDocx(String... paragraphs) throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            for (String p : paragraphs) {
                doc.createParagraph().createRun().setText(p);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.write(bos);
            return bos.toByteArray();
        }
    }

    private static byte[] makeXlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Sheet1");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("name");
            header.createCell(1).setCellValue("age");
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("Alice");
            r1.createCell(1).setCellValue(30);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }
}
