package com.apimarketplace.orchestrator.services.file;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.jsoup.Jsoup;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Pure (state-free) text extraction from the common document formats stored in the
 * workspace - PDF, Word (.docx), Excel (.xlsx), HTML, and any plain-text/code/data
 * file. The single source of truth for "turn these bytes into readable text", shared by:
 * <ul>
 *   <li>{@code FilesToolsProvider.view} - so an agent that opens an uploaded document
 *       reads its CONTENT directly (not just a download link), on every provider and on
 *       both execution paths (the text rides in the normal tool-result body);</li>
 *   <li>{@code ExtractFromFileNode} (text mode) - the workflow node that imports a
 *       document into chunked items.</li>
 * </ul>
 *
 * <p>Images are NOT handled here: a vision-capable model SEES an image via the raw-bytes
 * vision channel ({@code ToolMediaMetadata}), not via text. Audio/video/unknown binaries
 * have no text representation and return {@code null} from {@link #classify}.</p>
 *
 * <p>The PDFBox text layer can emit a NUL ({@code U+0000}) which PostgreSQL rejects in
 * text/JSONB columns, so every result is stripped of NULs before returning.</p>
 */
public final class DocumentTextExtractor {

    private DocumentTextExtractor() {}

    /** A document family this extractor can turn into readable text. */
    public enum DocKind { PDF, DOCX, XLSX, HTML, TEXT }

    /** Plain-text / code / data file extensions treated as readable UTF-8 text. */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
        ".txt", ".md", ".markdown", ".csv", ".tsv", ".json", ".xml", ".yaml", ".yml",
        ".log", ".js", ".mjs", ".ts", ".tsx", ".jsx", ".py", ".java", ".kt", ".c", ".h",
        ".cpp", ".cs", ".go", ".rb", ".rs", ".php", ".sh", ".sql", ".css", ".scss",
        ".ini", ".toml", ".properties", ".conf", ".env", ".srt", ".vtt"
    );

    /**
     * Classify {@code (mimeType, fileName)} into a supported {@link DocKind}, or {@code null}
     * when the bytes have no readable-text representation here (images, audio, video, archives,
     * legacy binary office formats, unknown binaries). MIME wins; the filename extension is a
     * fallback for generic/absent MIME (e.g. {@code application/octet-stream}).
     */
    public static DocKind classify(String mimeType, String fileName) {
        String m = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);

        // Images are SEEN, not read as text - never claim them here.
        if (m.startsWith("image/")) return null;

        if (m.contains("pdf") || n.endsWith(".pdf")) return DocKind.PDF;
        if (m.contains("wordprocessingml") || n.endsWith(".docx")) return DocKind.DOCX;
        if (m.contains("spreadsheetml") || n.endsWith(".xlsx")) return DocKind.XLSX;
        if (m.contains("html") || n.endsWith(".html") || n.endsWith(".htm")) return DocKind.HTML;
        if (m.startsWith("text/")
                || m.contains("json")
                || m.contains("xml")
                || m.contains("csv")
                || m.contains("javascript")
                || hasTextExtension(n)) {
            return DocKind.TEXT;
        }
        return null;
    }

    /** True when {@link #extract} can produce readable text for this file. */
    public static boolean isTextExtractable(String mimeType, String fileName) {
        return classify(mimeType, fileName) != null;
    }

    /**
     * Extract readable UTF-8 text from {@code bytes}, dispatching on
     * {@link #classify(String, String)}. Returns {@code null} when the type is not
     * text-extractable (caller falls back to a download link). Throws when the bytes are
     * the right type but corrupt/encrypted/unparseable - the caller decides how to degrade.
     */
    public static String extract(byte[] bytes, String mimeType, String fileName) throws Exception {
        if (bytes == null || bytes.length == 0) return null;
        DocKind kind = classify(mimeType, fileName);
        if (kind == null) return null;
        String text = switch (kind) {
            case PDF  -> extractPdf(bytes);
            case DOCX -> extractDocx(bytes);
            case XLSX -> extractXlsx(bytes);
            case HTML -> extractHtml(bytes);
            case TEXT -> new String(bytes, StandardCharsets.UTF_8);
        };
        // PDFBox (and stray binary) can emit NULs that PostgreSQL text/JSONB rejects.
        return text == null ? null : text.replace("\u0000", "");
    }

    public static String extractPdf(byte[] bytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    public static String extractHtml(byte[] bytes) {
        return Jsoup.parse(new String(bytes, StandardCharsets.UTF_8)).text();
    }

    public static String extractDocx(byte[] bytes) throws Exception {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
             XWPFDocument doc = new XWPFDocument(bis)) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph para : doc.getParagraphs()) {
                String text = para.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append("\n");
                }
            }
            return sb.toString().trim();
        }
    }

    /**
     * Flatten an .xlsx workbook to text: one tab-separated line per row, sheets separated
     * by a {@code # <sheet name>} header when there is more than one. Cell values are
     * rendered exactly as the spreadsheet displays them (via {@link DataFormatter}).
     */
    public static String extractXlsx(byte[] bytes) throws Exception {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
             Workbook workbook = new XSSFWorkbook(bis)) {
            DataFormatter formatter = new DataFormatter();
            StringBuilder sb = new StringBuilder();
            int sheetCount = workbook.getNumberOfSheets();
            for (int s = 0; s < sheetCount; s++) {
                Sheet sheet = workbook.getSheetAt(s);
                if (sheetCount > 1) {
                    sb.append("# ").append(sheet.getSheetName()).append("\n");
                }
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    short lastCell = row.getLastCellNum();
                    for (int c = 0; c < lastCell; c++) {
                        if (c > 0) line.append("\t");
                        Cell cell = row.getCell(c);
                        line.append(cell == null ? "" : formatter.formatCellValue(cell));
                    }
                    sb.append(stripTrailing(line.toString())).append("\n");
                }
            }
            return sb.toString().trim();
        }
    }

    private static boolean hasTextExtension(String lowerName) {
        int dot = lowerName.lastIndexOf('.');
        if (dot < 0) return false;
        return TEXT_EXTENSIONS.contains(lowerName.substring(dot));
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\t' || s.charAt(end - 1) == ' ')) {
            end--;
        }
        return s.substring(0, end);
    }
}
