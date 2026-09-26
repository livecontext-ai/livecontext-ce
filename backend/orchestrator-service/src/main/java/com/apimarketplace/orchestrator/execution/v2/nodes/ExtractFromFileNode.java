package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.services.template.ReportedParams;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.file.DocumentTextExtractor;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * ExtractFromFile node - Imports file content into structured JSON items.
 *
 * Two modes:
 * <ul>
 *   <li><b>structured</b> (default): Parse CSV/XLSX/JSON into rows with column keys.</li>
 *   <li><b>text</b>: Extract raw text from PDF/HTML/DOCX/TXT with optional chunking for RAG pipelines.</li>
 * </ul>
 *
 * Text mode chunking strategies:
 * <ul>
 *   <li><b>fixed_size</b>: Cut every N characters with configurable overlap.</li>
 *   <li><b>recursive</b>: Split at paragraph boundaries, then sentences, then words if chunks exceed size.</li>
 *   <li><b>separator</b>: Split on a custom delimiter string.</li>
 * </ul>
 */
public class ExtractFromFileNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(ExtractFromFileNode.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Core.ExtractFromFileConfig extractFromFileConfig;
    private FileStorageService fileStorageService;

    public ExtractFromFileNode(String nodeId, Core.ExtractFromFileConfig config) {
        super(nodeId, NodeType.EXTRACT_FROM_FILE);
        this.extractFromFileConfig = config;
    }

    public void setFileStorageService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @Override
    public void acceptServices(ServiceRegistry registry) {
        super.acceptServices(registry);
        this.fileStorageService = registry.getFileStorageService();
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        String format = extractFromFileConfig != null ? extractFromFileConfig.format() : "csv";
        String mode = extractFromFileConfig != null ? extractFromFileConfig.mode() : "structured";
        logger.info("ExtractFromFile node executing: nodeId={}, mode={}, format={}, itemId={}",
            nodeId, mode, format, context.itemId());

        // The config this execution runs with: chunking / chunkSize / overlap written as {{...}}
        // resolved here. Held in a one-slot array (never a field: nodes are shared by items).
        Core.ExtractFromFileConfig[] effective = { extractFromFileConfig };
        boolean[] scalarsResolved = { false };
        try {
            Core.ExtractFromFileConfig cfg = withDeferredScalars(
                "extractFromFile", extractFromFileConfig, Core.ExtractFromFileConfig.class, context);
            effective[0] = cfg;
            scalarsResolved[0] = true;
            if ("text".equals(mode)) {
                return executeTextMode(cfg, format, context);
            }
            return executeStructuredMode(cfg, format, context);
        } catch (Exception e) {
            logger.error("ExtractFromFile execution failed: nodeId={}, mode={}, format={}, error={}",
                nodeId, mode, format, e.getMessage(), e);
            Map<String, Object> failOutput = new HashMap<>();
            failOutput.put("node_type", "EXTRACT_FROM_FILE");
            failOutput.put("item_index", context.itemIndex());
            failOutput.put("itemIndex", context.itemIndex());
            failOutput.put("item_id", context.itemId());
            failOutput.put("resolved_params", buildInputDataMap(effective[0], scalarsResolved[0], null));
            failOutput.put("error", e.getMessage());
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), failOutput, 0L);
        }
    }

    /**
     * Structured mode: parse CSV/XLSX/JSON into rows with column keys (original behavior).
     */
    private NodeExecutionResult executeStructuredMode(Core.ExtractFromFileConfig cfg, String format, ExecutionContext context) throws Exception {
        String inputValue = resolveInputContent(
            cfg != null ? cfg.value() : null, format, context);

        if (inputValue == null || inputValue.isBlank()) {
            throw new IllegalArgumentException("Input value is required for ExtractFromFile node");
        }

        // Resolved once, used to pick the sheet and reported. It used to be read configured, so
        // a `{{trigger:in.output.month}}` sheet failed with "Sheet not found: {{...}}".
        String sheetName = "xlsx".equals(format) && cfg != null
            ? resolveExpression(cfg.sheetName(), context) : null;

        List<Map<String, Object>> items = switch (format) {
            case "csv" -> parseCsv(cfg, inputValue);
            case "xlsx" -> parseXlsx(cfg, inputValue, sheetName);
            case "json" -> parseJson(inputValue);
            default -> throw new IllegalArgumentException("Unknown format: " + format);
        };

        List<String> columns = List.of();
        int columnCount = 0;
        if (!items.isEmpty()) {
            columns = new ArrayList<>(items.get(0).keySet());
            columnCount = columns.size();
        }

        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        result.put("format", format);
        result.put("rowCount", items.size());
        result.put("columnCount", columnCount);
        result.put("columns", columns);
        result.put("success", true);
        result.put("mode", "structured");
        addMetadata(cfg, result, context, inputValue);
        if (sheetName != null && result.get("resolved_params") instanceof Map<?, ?> reported) {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) reported;
            params.put("sheetName", ReportedParams.valueFrom(cfg.sheetName(), sheetName));
        }

        logger.info("ExtractFromFile structured completed: nodeId={}, format={}, rowCount={}, columnCount={}",
            nodeId, format, items.size(), columnCount);
        return NodeExecutionResult.success(nodeId, result);
    }

    /**
     * Text mode: extract raw text from PDF/HTML/DOCX/TXT and optionally chunk for RAG.
     */
    private NodeExecutionResult executeTextMode(Core.ExtractFromFileConfig cfg, String format, ExecutionContext context) throws Exception {
        String expression = cfg != null ? cfg.value() : null;
        byte[] rawBytes = resolveInputBytes(expression, format, context);

        if (rawBytes == null || rawBytes.length == 0) {
            throw new IllegalArgumentException("Input value is required for ExtractFromFile text mode");
        }

        String extractedText = switch (format) {
            case "pdf"  -> DocumentTextExtractor.extractPdf(rawBytes);
            case "html" -> DocumentTextExtractor.extractHtml(rawBytes);
            case "docx" -> DocumentTextExtractor.extractDocx(rawBytes);
            case "txt"  -> new String(rawBytes, StandardCharsets.UTF_8);
            case "csv"  -> new String(rawBytes, StandardCharsets.UTF_8);
            case "json" -> new String(rawBytes, StandardCharsets.UTF_8);
            default -> throw new IllegalArgumentException(
                "Unsupported format for text mode: " + format + ". Use pdf, html, docx, or txt.");
        };

        // Sanitize null bytes - PDFBox can emit \u0000 which PostgreSQL rejects in JSONB/text
        extractedText = extractedText.replace("\u0000", "");

        String sourceName = resolveSourceName(expression, context);

        List<Map<String, Object>> items;
        if (cfg != null && cfg.isChunkingEnabled()) {
            items = chunkText(cfg, extractedText, sourceName);
        } else {
            items = List.of(Map.of(
                "content", extractedText,
                "chunk_index", 0,
                "source", sourceName,
                "total_chunks", 1
            ));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        result.put("format", format);
        result.put("mode", "text");
        result.put("rowCount", items.size());
        result.put("total_chunks", items.size());
        result.put("text_length", extractedText.length());
        result.put("success", true);
        if (cfg != null && cfg.isChunkingEnabled()) {
            result.put("chunking_strategy", cfg.chunkingStrategy());
            result.put("chunk_size", cfg.chunkSize());
            result.put("overlap", cfg.overlap());
            result.put("chunk_unit", cfg.chunkUnit());
        }
        addMetadata(cfg, result, context, extractedText.length() > 500
            ? extractedText.substring(0, 500) + "..." : extractedText);

        logger.info("ExtractFromFile text completed: nodeId={}, format={}, chunks={}, textLength={}",
            nodeId, format, items.size(), extractedText.length());
        return NodeExecutionResult.success(nodeId, result);
    }

    private void addMetadata(Core.ExtractFromFileConfig cfg, Map<String, Object> result, ExecutionContext context, String inputPreview) {
        result.put("node_type", "EXTRACT_FROM_FILE");
        result.put("item_index", context.itemIndex());
        result.put("itemIndex", context.itemIndex());
        result.put("item_id", context.itemId());
        result.put("resolved_params", buildInputDataMap(cfg, true, inputPreview));
    }

    /**
     * Parse CSV string into a list of maps.
     * If hasHeaders is true, the first row is used as keys.
     * Otherwise, keys are column_0, column_1, etc.
     */
    private List<Map<String, Object>> parseCsv(Core.ExtractFromFileConfig cfg, String csvContent) {
        String delimiter = cfg != null ? cfg.delimiter() : ",";
        boolean hasHeaders = cfg != null && cfg.includeHeaders();

        String[] lines = csvContent.split("\\r?\\n");
        if (lines.length == 0) {
            return List.of();
        }

        List<Map<String, Object>> items = new ArrayList<>();
        String[] headers;
        int startIndex;

        if (hasHeaders && lines.length > 0) {
            headers = splitCsvLine(lines[0], delimiter);
            startIndex = 1;
        } else {
            // Generate column_0, column_1, ... based on first row
            String[] firstRow = splitCsvLine(lines[0], delimiter);
            headers = new String[firstRow.length];
            for (int i = 0; i < firstRow.length; i++) {
                headers[i] = "column_" + i;
            }
            startIndex = 0;
        }

        for (int i = startIndex; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] values = splitCsvLine(line, delimiter);
            Map<String, Object> row = new LinkedHashMap<>();
            for (int j = 0; j < headers.length; j++) {
                row.put(headers[j].trim(), j < values.length ? values[j].trim() : "");
            }
            items.add(row);
        }

        return items;
    }

    /**
     * Split a CSV line by delimiter, respecting quoted fields.
     */
    private String[] splitCsvLine(String line, String delimiter) {
        // Simple split for single-char delimiters
        // Handle quoted fields for CSV compliance
        if (delimiter.length() == 1 && line.contains("\"")) {
            return splitQuotedCsv(line, delimiter.charAt(0));
        }
        return line.split(java.util.regex.Pattern.quote(delimiter), -1);
    }

    /**
     * Parse a CSV line that may contain quoted fields.
     */
    private String[] splitQuotedCsv(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++; // skip escaped quote
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == delimiter) {
                    fields.add(current.toString());
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    /**
     * Parse Base64-encoded XLSX content into a list of maps using Apache POI.
     * Uses sheetName if provided, otherwise reads the first sheet.
     * If hasHeaders is true, the first row is used as column keys.
     */
    private List<Map<String, Object>> parseXlsx(Core.ExtractFromFileConfig cfg, String base64Content, String sheetName) throws Exception {
        boolean hasHeaders = cfg != null && cfg.includeHeaders();

        byte[] bytes = Base64.getDecoder().decode(base64Content);

        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
             Workbook workbook = new XSSFWorkbook(bis)) {

            Sheet sheet;
            if (sheetName != null && !sheetName.isBlank()) {
                sheet = workbook.getSheet(sheetName);
                if (sheet == null) {
                    throw new IllegalArgumentException("Sheet not found: " + sheetName);
                }
            } else {
                sheet = workbook.getSheetAt(0);
            }

            List<Map<String, Object>> items = new ArrayList<>();
            int firstRow = sheet.getFirstRowNum();
            int lastRow = sheet.getLastRowNum();

            if (firstRow > lastRow) {
                return List.of();
            }

            String[] headers;
            int dataStartRow;

            if (hasHeaders) {
                Row headerRow = sheet.getRow(firstRow);
                if (headerRow == null) {
                    return List.of();
                }
                int colCount = headerRow.getLastCellNum();
                headers = new String[colCount];
                for (int c = 0; c < colCount; c++) {
                    Cell cell = headerRow.getCell(c);
                    headers[c] = getCellStringValue(cell);
                }
                dataStartRow = firstRow + 1;
            } else {
                // Determine column count from first row
                Row firstDataRow = sheet.getRow(firstRow);
                if (firstDataRow == null) {
                    return List.of();
                }
                int colCount = firstDataRow.getLastCellNum();
                headers = new String[colCount];
                for (int c = 0; c < colCount; c++) {
                    headers[c] = "column_" + c;
                }
                dataStartRow = firstRow;
            }

            for (int r = dataStartRow; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                Map<String, Object> rowMap = new LinkedHashMap<>();
                for (int c = 0; c < headers.length; c++) {
                    Cell cell = row.getCell(c);
                    rowMap.put(headers[c], getCellValue(cell));
                }
                items.add(rowMap);
            }

            return items;
        }
    }

    /**
     * Get the string value of a cell for use as a header name.
     */
    private String getCellStringValue(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    yield String.valueOf((long) d);
                }
                yield String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }

    /**
     * Get the typed value of a cell (preserving numbers and booleans).
     */
    private Object getCellValue(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getDateCellValue().toString();
                }
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    yield (long) d;
                }
                yield d;
            }
            case BOOLEAN -> cell.getBooleanCellValue();
            case FORMULA -> cell.getCellFormula();
            case BLANK -> "";
            default -> "";
        };
    }

    /**
     * Parse JSON string into a list of maps.
     * Handles both array of objects and single object (wrapped into a list).
     */
    private List<Map<String, Object>> parseJson(String jsonContent) throws Exception {
        jsonContent = jsonContent.trim();

        if (jsonContent.startsWith("[")) {
            // Array of objects
            List<Map<String, Object>> items = objectMapper.readValue(
                jsonContent, new TypeReference<List<Map<String, Object>>>() {});
            return items != null ? items : List.of();
        } else if (jsonContent.startsWith("{")) {
            // Single object - wrap in list
            Map<String, Object> singleItem = objectMapper.readValue(
                jsonContent, new TypeReference<Map<String, Object>>() {});
            return singleItem != null ? List.of(singleItem) : List.of();
        } else {
            throw new IllegalArgumentException("Invalid JSON input: must be an object or array");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // TEXT EXTRACTION
    // ═══════════════════════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════════════════════
    // CHUNKING
    // ═══════════════════════════════════════════════════════════════════════════════

    private static final Pattern PARAGRAPH_SPLIT = Pattern.compile("\\n\\s*\\n");
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+");

    private List<Map<String, Object>> chunkText(Core.ExtractFromFileConfig cfg, String text, String source) {
        String strategy = cfg.chunkingStrategy();
        int chunkSize = cfg.chunkSize();
        int overlap = Math.min(cfg.overlap(), chunkSize - 1);
        SizeMeter meter = meterFor(cfg.chunkUnit());

        List<String> chunks = switch (strategy) {
            case "recursive"  -> chunkRecursive(text, chunkSize, overlap, meter);
            case "separator"  -> chunkBySeparator(text, cfg.separator(), chunkSize, overlap, meter);
            default           -> chunkFixedSize(text, chunkSize, overlap, meter); // fixed_size
        };

        List<Map<String, Object>> items = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            items.add(Map.of(
                "content", chunks.get(i),
                "chunk_index", i,
                "source", source,
                "total_chunks", chunks.size()
            ));
        }
        return items;
    }

    /**
     * Fixed-size chunking: cut every chunkSize units (chars or tokens) with overlap.
     */
    private List<String> chunkFixedSize(String text, int chunkSize, int overlap, SizeMeter meter) {
        int step = chunkSize - overlap;
        if (step <= 0) step = 1;
        return meter.hardSplit(text, chunkSize, step);
    }

    /**
     * Recursive chunking: split at paragraph boundaries, then sentences if still too large.
     * Overlap is applied once at the end via applyOverlap (not inside the chunkFixedSize fallback).
     */
    private List<String> chunkRecursive(String text, int chunkSize, int overlap, SizeMeter meter) {
        // Split into paragraphs first
        String[] paragraphs = PARAGRAPH_SPLIT.split(text);
        List<String> segments = mergeSegments(Arrays.asList(paragraphs), chunkSize, meter);

        // If any segment is still too large, split by sentences
        List<String> refined = new ArrayList<>();
        for (String segment : segments) {
            if (meter.measure(segment) <= chunkSize) {
                refined.add(segment);
            } else {
                String[] sentences = SENTENCE_SPLIT.split(segment);
                refined.addAll(mergeSegments(Arrays.asList(sentences), chunkSize, meter));
            }
        }

        // Final pass: any chunk still over limit gets fixed-size split (no overlap here)
        List<String> finalChunks = new ArrayList<>();
        for (String chunk : refined) {
            if (meter.measure(chunk) <= chunkSize) {
                finalChunks.add(chunk);
            } else {
                finalChunks.addAll(chunkFixedSize(chunk, chunkSize, 0, meter));
            }
        }

        return applyOverlap(finalChunks, overlap, meter);
    }

    /**
     * Separator-based chunking: split on a custom delimiter, then merge small pieces.
     * Overlap is applied once at the end via applyOverlap (not inside the chunkFixedSize fallback).
     */
    private List<String> chunkBySeparator(String text, String separator, int chunkSize, int overlap, SizeMeter meter) {
        String[] parts = text.split(Pattern.quote(separator));
        List<String> merged = mergeSegments(Arrays.asList(parts), chunkSize, meter);

        // Fixed-size fallback for oversized segments (no overlap here)
        List<String> finalChunks = new ArrayList<>();
        for (String chunk : merged) {
            if (meter.measure(chunk) <= chunkSize) {
                finalChunks.add(chunk);
            } else {
                finalChunks.addAll(chunkFixedSize(chunk, chunkSize, 0, meter));
            }
        }

        return applyOverlap(finalChunks, overlap, meter);
    }

    /**
     * Merge small segments into chunks that don't exceed chunkSize (measured via the meter).
     */
    private List<String> mergeSegments(List<String> segments, int chunkSize, SizeMeter meter) {
        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String seg : segments) {
            String trimmed = seg.trim();
            if (trimmed.isEmpty()) continue;
            if (current.isEmpty()) {
                current.append(trimmed);
            } else if (meter.measure(current + "\n" + trimmed) <= chunkSize) {
                current.append("\n").append(trimmed);
            } else {
                merged.add(current.toString());
                current = new StringBuilder(trimmed);
            }
        }
        if (!current.isEmpty()) {
            merged.add(current.toString());
        }
        return merged;
    }

    /**
     * Apply overlap between consecutive chunks (prepend the tail of the previous chunk,
     * measured in the active unit so token mode overlaps by whole tokens).
     */
    private List<String> applyOverlap(List<String> chunks, int overlap, SizeMeter meter) {
        if (overlap <= 0 || chunks.size() <= 1) return chunks;
        List<String> result = new ArrayList<>(chunks.size());
        result.add(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1);
            String overlapText = meter.tail(prev, overlap);
            result.add(overlapText + chunks.get(i));
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SIZE METERING - char (default) vs token (cl100k_base)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Abstracts how chunk sizes/overlaps are measured and how text is cut. {@link CharMeter}
     * (default) operates on characters and reproduces the original char-based behavior exactly.
     * {@link TokenMeter} operates on cl100k_base tokens so chunks fit an embedding model's
     * context window. Kept package-private for direct unit testing.
     */
    interface SizeMeter {
        /** Size of the text in the active unit (characters or tokens). */
        int measure(String s);
        /** The last {@code n} units of the text (used for overlap). */
        String tail(String s, int n);
        /** Cut the text into windows of {@code size} units advancing by {@code step} units. */
        List<String> hardSplit(String s, int size, int step);
    }

    static final SizeMeter CHAR_METER = new CharMeter();

    static final class CharMeter implements SizeMeter {
        @Override public int measure(String s) { return s.length(); }

        @Override public String tail(String s, int n) {
            return s.substring(Math.max(0, s.length() - n));
        }

        @Override public List<String> hardSplit(String s, int size, int step) {
            List<String> chunks = new ArrayList<>();
            int len = s.length();
            for (int start = 0; start < len; start += step) {
                int end = Math.min(start + size, len);
                chunks.add(s.substring(start, end));
                if (end == len) break;
            }
            return chunks;
        }
    }

    /**
     * Token metering over cl100k_base. Encodes once per call and slices token-id ranges,
     * decoding each range back to text, so a chunk boundary never cuts a token in half.
     * Uses the "ordinary" encode/count variants so {@code <|...|>}-style sequences in
     * document text are treated as plain text, never as special tokens.
     */
    static final class TokenMeter implements SizeMeter {
        private final Encoding encoding;

        TokenMeter(Encoding encoding) { this.encoding = encoding; }

        @Override public int measure(String s) {
            return encoding.countTokensOrdinary(s);
        }

        @Override public String tail(String s, int n) {
            IntArrayList ids = encoding.encodeOrdinary(s);
            return decodeRange(ids, Math.max(0, ids.size() - n), ids.size());
        }

        @Override public List<String> hardSplit(String s, int size, int step) {
            IntArrayList ids = encoding.encodeOrdinary(s);
            int total = ids.size();
            List<String> chunks = new ArrayList<>();
            for (int start = 0; start < total; start += step) {
                int end = Math.min(start + size, total);
                chunks.add(decodeRange(ids, start, end));
                if (end == total) break;
            }
            return chunks;
        }

        private String decodeRange(IntArrayList ids, int from, int to) {
            IntArrayList slice = new IntArrayList(Math.max(0, to - from));
            for (int i = from; i < to; i++) {
                slice.add(ids.get(i));
            }
            return encoding.decode(slice);
        }
    }

    /**
     * Heuristic token metering (~4 chars per token). Used only as a fallback when the
     * cl100k_base encoding cannot be initialized, so a token-mode request still approximates
     * token sizing instead of silently reverting to character sizing.
     */
    static final class HeuristicTokenMeter implements SizeMeter {
        static final int CHARS_PER_TOKEN = 4;

        @Override public int measure(String s) {
            return (s.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
        }

        @Override public String tail(String s, int n) {
            return CHAR_METER.tail(s, n * CHARS_PER_TOKEN);
        }

        @Override public List<String> hardSplit(String s, int size, int step) {
            return CHAR_METER.hardSplit(s, size * CHARS_PER_TOKEN, step * CHARS_PER_TOKEN);
        }
    }

    // Lazily-initialized cl100k_base encoding; only loaded the first time token mode runs,
    // so char-mode workflows never touch the tokenizer. volatile + double-checked lock
    // mirrors JtokkitTokenEstimator's publication guarantee (the Encoding is thread-safe).
    private static volatile Encoding tokenEncoding;

    private static Encoding tokenEncoding() {
        Encoding enc = tokenEncoding;
        if (enc == null) {
            synchronized (ExtractFromFileNode.class) {
                enc = tokenEncoding;
                if (enc == null) {
                    enc = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
                    tokenEncoding = enc;
                }
            }
        }
        return enc;
    }

    /**
     * Resolve the {@link SizeMeter} for a chunk unit. "token" yields a cl100k_base
     * {@link TokenMeter} (or a {@link HeuristicTokenMeter} if encoding init fails);
     * anything else yields the {@link CharMeter}.
     */
    static SizeMeter meterFor(String chunkUnit) {
        if ("token".equals(chunkUnit)) {
            try {
                return new TokenMeter(tokenEncoding());
            } catch (RuntimeException | LinkageError e) {
                logger.warn("cl100k_base token encoding unavailable, falling back to heuristic token metering: {}",
                    e.getMessage());
                return new HeuristicTokenMeter();
            }
        }
        return CHAR_METER;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // INPUT RESOLUTION
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Resolve input to raw bytes (for text mode). Binary formats (pdf, docx, xlsx) stay as bytes.
     */
    @SuppressWarnings("unchecked")
    private byte[] resolveInputBytes(String expression, String format, ExecutionContext context) {
        if (expression == null || expression.isBlank()) return null;

        Object result = resolveInputValue(expression, context);

        // The file SHAPE decides, not the path. Requiring a path here sent a file-shaped
        // value that has none - a table media cell naming a file known only by id, a link
        // to somewhere else - down the string fallback below, where String.valueOf turned
        // the Map into "{_type=file, id=...}" and the node COMPLETED with that as the
        // extracted document. The download judges the path and says what is missing.
        if (result instanceof Map<?, ?> mapResult && "file".equals(mapResult.get("_type"))) {
            return downloadFileRefBytes((Map<String, Object>) mapResult);
        }

        // Fallback: string content (e.g. inline text, base64); a structure is its JSON.
        String str = com.apimarketplace.orchestrator.services.TemplateEngine.asText(result);
        if (isBinaryFormat(format) && looksLikeBase64(str)) {
            return Base64.getDecoder().decode(str);
        }
        return str.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The value to extract from, typed. A reference to nothing FAILS the node: it used to fall
     * back to the configured text, and the node then extracted the literal {@code {{...}}} as if
     * it were the document and completed green.
     */
    private Object resolveInputValue(String expression, ExecutionContext context) {
        Object result = resolveTemplateValue(expression, context);
        if (result == null) {
            throw new IllegalStateException(
                "The value to extract from resolved to nothing: '" + expression
                    + "'. Check that the referenced node ran and that the path exists.");
        }
        return result;
    }

    private byte[] downloadFileRefBytes(Map<String, Object> fileRef) {
        if (!(fileRef.get("path") instanceof String path) || path.isBlank()) {
            throw new IllegalStateException("The file " + com.apimarketplace.orchestrator.domain.file.FileRefMessages.NO_STORAGE_PATH);
        }
        if (fileStorageService == null) {
            throw new IllegalStateException("FileStorageService not available for FileRef download: " + path);
        }
        Optional<byte[]> content = fileStorageService.download(path);
        if (content.isEmpty()) {
            throw new IllegalStateException("File not found in storage: " + path);
        }
        logger.info("Downloaded FileRef from S3: path={}, size={} bytes", path, content.get().length);
        return content.get();
    }

    private String resolveSourceName(String expression, ExecutionContext context) {
        if (expression == null) return "unknown";
        if (templateAdapter != null) {
            try {
                Object result = resolveTemplateValue(expression, context);
                if (result instanceof Map<?, ?> mapResult && mapResult.get("name") != null) {
                    return String.valueOf(mapResult.get("name"));
                }
                if (result instanceof Map<?, ?> mapResult && mapResult.get("path") != null) {
                    String path = String.valueOf(mapResult.get("path"));
                    int lastSlash = path.lastIndexOf('/');
                    return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
                }
            } catch (Exception ignored) {}
        }
        return expression.length() > 50 ? expression.substring(0, 50) + "..." : expression;
    }

    private static boolean isBinaryFormat(String format) {
        return "pdf".equals(format) || "docx".equals(format) || "xlsx".equals(format);
    }

    private static boolean looksLikeBase64(String str) {
        return str.length() > 100 && str.matches("^[A-Za-z0-9+/=\\s]+$");
    }

    /**
     * Resolves the input content, detecting FileRef objects from upstream nodes.
     * If the resolved value is a FileRef (Map with _type=file), downloads the file from S3.
     */
    @SuppressWarnings("unchecked")
    private String resolveInputContent(String expression, String format, ExecutionContext context) {
        if (expression == null || expression.isBlank()) {
            return null;
        }

        Object result = resolveInputValue(expression, context);

        // Detect FileRef map from upstream node output. Shape only: see resolveInputBytes
        // for why a path check here silently turned a path-less ref into its toString().
        if (result instanceof Map<?, ?> mapResult && "file".equals(mapResult.get("_type"))) {
            return downloadFileRef((Map<String, Object>) mapResult, format);
        }
        return com.apimarketplace.orchestrator.services.TemplateEngine.asText(result);
    }

    /**
     * Downloads file content from S3 using a FileRef map.
     * Returns base64 for xlsx format (parser expects it), raw string for others.
     */
    private String downloadFileRef(Map<String, Object> fileRef, String format) {
        if (!(fileRef.get("path") instanceof String path) || path.isBlank()) {
            throw new IllegalStateException("The file " + com.apimarketplace.orchestrator.domain.file.FileRefMessages.NO_STORAGE_PATH);
        }
        if (fileStorageService == null) {
            throw new IllegalStateException("FileStorageService not available for FileRef download: " + path);
        }
        Optional<byte[]> content = fileStorageService.download(path);
        if (content.isEmpty()) {
            throw new IllegalStateException("File not found in storage: " + path);
        }
        byte[] bytes = content.get();
        logger.info("Downloaded FileRef from S3: path={}, size={} bytes", path, bytes.length);
        return "xlsx".equals(format)
            ? Base64.getEncoder().encodeToString(bytes)
            : new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Resolve a SpEL expression using the template adapter.
     */
    private String resolveExpression(String expression, ExecutionContext context) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        // One resolver for every field of every node: typed, JSON for a structure, never the
        // configured template in place of a value (BaseNode#resolveTemplateValue).
        return resolveTemplateString(expression, context);
    }

    private Map<String, Object> buildInputDataMap(Core.ExtractFromFileConfig cfg, boolean scalarsResolved, String resolvedValue) {
        Map<String, Object> inputData = new LinkedHashMap<>();
        if (cfg != null) {
            inputData.put("format", cfg.format());
            inputData.put("mode", cfg.mode());
            // `separator` decides how a TEXT file is split into records. Reported only
            // in the mode that reads it: the config constructor defaults it, so a csv
            // node would otherwise show a separator it never uses next to its real
            // delimiter - telling the reader they configured something they did not.
            if (cfg.isTextMode() && cfg.separator() != null
                    && !cfg.separator().isBlank()) {
                inputData.put("separator", cfg.separator());
            }
            if (cfg.isTextMode()) {
                inputData.put("chunking", cfg.isChunkingEnabled());
                if (cfg.isChunkingEnabled()) {
                    inputData.put("chunkingStrategy", cfg.chunkingStrategy());
                    inputData.put("chunkSize", cfg.chunkSize());
                    inputData.put("overlap", cfg.overlap());
                    inputData.put("chunkUnit", cfg.chunkUnit());
                }
            } else {
                inputData.put("delimiter", cfg.delimiter());
                if (cfg.sheetName() != null) {
                    inputData.put("sheetName", cfg.sheetName());
                }
                inputData.put("hasHeaders", cfg.hasHeaders());
            }
        }
        // A chunking setting the plan wrote as {{...}}: the template until it resolved, then the
        // value it resolved to (withheld when it pulled a workspace variable).
        for (String field : List.of("chunking", "chunkSize", "overlap")) {
            String template = deferredScalar("extractFromFile", field);
            if (template == null) {
                continue;
            }
            if (!scalarsResolved) {
                inputData.put(field, template);
            } else if (inputData.containsKey(field)) {
                inputData.put(field, ReportedParams.valueFrom(template, inputData.get(field)));
            }
        }
        if (resolvedValue != null) {
            String preview = resolvedValue.length() > 500
                ? resolvedValue.substring(0, 500) + "... (" + resolvedValue.length() + " chars)"
                : resolvedValue;
            inputData.put("value", preview);
        }
        return inputData;
    }

    // Getters
    public Core.ExtractFromFileConfig getExtractFromFileConfig() { return extractFromFileConfig; }

    // Builder
    public static class Builder {
        private String nodeId;
        private Core.ExtractFromFileConfig extractFromFileConfig;

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder extractFromFileConfig(Core.ExtractFromFileConfig config) { this.extractFromFileConfig = config; return this; }
        public ExtractFromFileNode build() { return new ExtractFromFileNode(nodeId, extractFromFileConfig); }
    }

    public static Builder builder() { return new Builder(); }
}
