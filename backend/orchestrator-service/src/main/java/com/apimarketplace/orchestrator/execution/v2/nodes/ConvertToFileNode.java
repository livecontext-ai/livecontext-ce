package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ConvertToFile node - Exports JSON data to CSV, XLSX, JSON, or TXT file content.
 *
 * Operations:
 * - csv: Convert List of Maps to CSV string with configurable delimiter and headers
 * - xlsx: Convert List of Maps to XLSX using Apache POI, returned as Base64
 * - json: Convert data to pretty-printed JSON string
 * - txt: Convert data to plain text (key=value per line)
 *
 * Usage:
 * - Export query results or API responses to downloadable file formats
 * - Convert structured data to CSV for spreadsheet import
 * - Generate XLSX reports from workflow data
 * - Produce JSON or TXT exports for downstream systems
 */
public class ConvertToFileNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(ConvertToFileNode.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Core.ConvertToFileConfig convertToFileConfig;
    private FileStorageService fileStorageService;

    public ConvertToFileNode(String nodeId, Core.ConvertToFileConfig convertToFileConfig) {
        super(nodeId, NodeType.CONVERT_TO_FILE);
        this.convertToFileConfig = convertToFileConfig;
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
        String format = convertToFileConfig != null ? convertToFileConfig.format() : "csv";
        String filename = convertToFileConfig != null ? convertToFileConfig.filename() : "export";
        logger.info("ConvertToFile node executing: nodeId={}, format={}, filename={}, itemId={}",
            nodeId, format, filename, context.itemId());

        // Captured outside the try so failure paths still surface the resolved inputs
        // to the inspector "Resolved parameters" panel.
        Map<String, Object> inputData = buildInputDataMap(context);

        try {
            // Resolve the input value expression
            Object inputValue = resolveExpression(
                convertToFileConfig != null ? convertToFileConfig.value() : null, context);

            // Resolve the data from step outputs
            List<Map<String, Object>> data = resolveData(inputValue, context);

            Map<String, Object> result = new HashMap<>();
            String fileContent;
            String fullFilename;

            switch (format) {
                case "csv" -> {
                    fileContent = convertToCsv(data);
                    fullFilename = filename + ".csv";
                }
                case "xlsx" -> {
                    fileContent = convertToXlsx(data);
                    fullFilename = filename + ".xlsx";
                }
                case "json" -> {
                    fileContent = convertToJson(data);
                    fullFilename = filename + ".json";
                }
                case "txt" -> {
                    fileContent = convertToTxt(data);
                    fullFilename = filename + ".txt";
                }
                default -> throw new IllegalArgumentException("Unknown format: " + format +
                    ". Supported formats: csv, xlsx, json, txt");
            }

            result.put("result", fileContent);
            result.put("format", format);
            result.put("row_count", data.size());
            result.put("success", true);

            // Upload to S3 - required for `file` FileRef emission. When storage is
            // unavailable, no `file` is emitted (success=true with file=null is a valid
            // outcome for downstream nodes that consume `result` directly).
            if (fileStorageService != null) {
                try {
                    byte[] fileBytes = "xlsx".equals(format)
                        ? Base64.getDecoder().decode(fileContent)
                        : fileContent.getBytes(StandardCharsets.UTF_8);
                    String mimeType = resolveMimeType(format);
                    FileRef fileRef = fileStorageService.upload(
                        context.tenantId(), context.plan().getId(), context.runId(),
                        nodeId, fullFilename, mimeType, fileBytes,
                        resolveStorageEpoch(context), context.spawn(), context.itemIndex(),
                        com.apimarketplace.common.storage.service.StorageSourceTypes.STEP_OUTPUT);
                    // Canonical FileRef only - frontend file-proxy injector and showcase
                    // HMAC rewriter both probe this shape.
                    result.put("file", fileRef);
                } catch (Exception e) {
                    logger.warn("S3 upload failed (non-fatal): {}", e.getMessage());
                }
            }

            // MANDATORY metadata
            result.put("node_type", "CONVERT_TO_FILE");
            result.put("item_index", context.itemIndex());
            result.put("itemIndex", context.itemIndex());
            result.put("item_id", context.itemId());
            result.put("resolved_params", inputData);

            logger.info("ConvertToFile completed: nodeId={}, format={}, rows={}, size={}",
                nodeId, format, data.size(), result.get("size"));
            return NodeExecutionResult.success(nodeId, result);

        } catch (Exception e) {
            logger.error("ConvertToFile execution failed: nodeId={}, format={}, error={}",
                nodeId, format, e.getMessage(), e);
            return NodeExecutionResult.failureWithOutput(
                nodeId, e.getMessage(), buildFailureOutput(context, format, inputData), 0L);
        }
    }

    /**
     * Persisted shape on failure mirrors the declared NodeDefinition.outputs() with safe defaults
     * so {{core:convertToFile.output.success}} and {{...row_count}} resolve consistently runtime
     * and post-persistence. {@code file} is null on failure (no FileRef is produced) - mirrors
     * {@link DownloadFileNode#buildFailureOutput} so consumers can null-check a single shape.
     */
    private Map<String, Object> buildFailureOutput(
            ExecutionContext context, String format, Map<String, Object> inputData) {
        Map<String, Object> failOutput = new HashMap<>();
        failOutput.put("file", null);
        failOutput.put("result", null);
        failOutput.put("format", format);
        failOutput.put("row_count", 0);
        failOutput.put("success", false);
        failOutput.put("node_type", "CONVERT_TO_FILE");
        failOutput.put("item_index", context.itemIndex());
        failOutput.put("itemIndex", context.itemIndex());
        failOutput.put("item_id", context.itemId());
        failOutput.put("resolved_params", inputData);
        return failOutput;
    }

    /**
     * Resolve input data from context step outputs.
     * The value expression points to a step output key containing the data.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resolveData(Object inputValue, ExecutionContext context) {
        if (inputValue instanceof List<?> || inputValue instanceof Map<?, ?>) {
            return extractListFromObject(inputValue);
        }

        String inputKey = inputValue != null ? String.valueOf(inputValue) : null;
        if (inputKey != null && !inputKey.isBlank() && context.stepOutputs() != null) {
            Object stepOutput = context.stepOutputs().get(inputKey);
            if (stepOutput != null) {
                return extractListFromObject(stepOutput);
            }
        }

        // Fallback: use trigger data as a single-row list
        if (context.triggerData() != null && !context.triggerData().isEmpty()) {
            return List.of(new LinkedHashMap<>(context.triggerData()));
        }

        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractListFromObject(Object data) {
        if (data instanceof List<?> rawList) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : rawList) {
                if (item instanceof Map) {
                    result.add((Map<String, Object>) item);
                }
            }
            return result;
        }

        if (data instanceof Map<?, ?> map) {
            for (String key : List.of("items", "rows", "data", "results", "records", "output")) {
                Object nested = map.get(key);
                if (nested instanceof List<?>) {
                    return extractListFromObject(nested);
                }
            }
            return List.of((Map<String, Object>) data);
        }

        return List.of();
    }

    /**
     * Convert List of Maps to CSV string.
     */
    private String convertToCsv(List<Map<String, Object>> data) {
        if (data.isEmpty()) {
            return "";
        }

        String delimiter = convertToFileConfig != null ? convertToFileConfig.delimiter() : ",";
        boolean includeHeaders = convertToFileConfig == null || convertToFileConfig.hasHeaders();

        // Collect all unique keys preserving order
        List<String> headers = new ArrayList<>();
        for (Map<String, Object> row : data) {
            for (String key : row.keySet()) {
                if (!headers.contains(key)) {
                    headers.add(key);
                }
            }
        }

        StringBuilder sb = new StringBuilder();

        if (includeHeaders) {
            sb.append(headers.stream()
                .map(this::escapeCsvField)
                .collect(Collectors.joining(delimiter)));
            sb.append("\n");
        }

        for (Map<String, Object> row : data) {
            sb.append(headers.stream()
                .map(h -> {
                    Object val = row.get(h);
                    return escapeCsvField(val != null ? String.valueOf(val) : "");
                })
                .collect(Collectors.joining(delimiter)));
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Escape a CSV field: wrap in quotes if it contains delimiter, quote, or newline.
     */
    private String escapeCsvField(String field) {
        String delimiter = convertToFileConfig != null ? convertToFileConfig.delimiter() : ",";
        if (field.contains(delimiter) || field.contains("\"") || field.contains("\n")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }

    /**
     * Convert List of Maps to XLSX using Apache POI, returned as Base64 string.
     */
    private String convertToXlsx(List<Map<String, Object>> data) throws Exception {
        boolean includeHeaders = convertToFileConfig == null || convertToFileConfig.hasHeaders();

        // Collect all unique keys
        List<String> headers = new ArrayList<>();
        for (Map<String, Object> row : data) {
            for (String key : row.keySet()) {
                if (!headers.contains(key)) {
                    headers.add(key);
                }
            }
        }

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Data");
            int rowIndex = 0;

            if (includeHeaders && !headers.isEmpty()) {
                Row headerRow = sheet.createRow(rowIndex++);
                for (int i = 0; i < headers.size(); i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(headers.get(i));
                }
            }

            for (Map<String, Object> rowData : data) {
                Row row = sheet.createRow(rowIndex++);
                for (int i = 0; i < headers.size(); i++) {
                    Cell cell = row.createCell(i);
                    Object val = rowData.get(headers.get(i));
                    if (val == null) {
                        cell.setCellValue("");
                    } else if (val instanceof Number) {
                        cell.setCellValue(((Number) val).doubleValue());
                    } else if (val instanceof Boolean) {
                        cell.setCellValue((Boolean) val);
                    } else {
                        cell.setCellValue(String.valueOf(val));
                    }
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        }
    }

    /**
     * Convert data to pretty-printed JSON string.
     */
    private String convertToJson(List<Map<String, Object>> data) throws Exception {
        return objectMapper.writeValueAsString(data);
    }

    /**
     * Convert data to plain text (key=value per line, rows separated by blank line).
     */
    private String convertToTxt(List<Map<String, Object>> data) {
        if (data.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.size(); i++) {
            Map<String, Object> row = data.get(i);
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                sb.append(entry.getKey())
                  .append("=")
                  .append(entry.getValue() != null ? String.valueOf(entry.getValue()) : "")
                  .append("\n");
            }
            if (i < data.size() - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private String resolveMimeType(String format) {
        return switch (format) {
            case "csv" -> "text/csv";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "json" -> "application/json";
            case "txt" -> "text/plain";
            default -> "application/octet-stream";
        };
    }

    /**
     * Resolve a SpEL expression using the template adapter.
     */
    private Object resolveExpression(String expression, ExecutionContext context) {
        if (expression == null || expression.isBlank()) {
            return null;
        }

        if (templateAdapter != null) {
            try {
                Map<String, Object> toResolve = Map.of("__expr__", expression);
                Map<String, Object> resolved = templateAdapter.resolveTemplates(toResolve, context);
                return resolved.getOrDefault("__expr__", expression);
            } catch (Exception e) {
                logger.warn("Failed to resolve expression '{}': {}", expression, e.getMessage());
                return expression;
            }
        }

        return expression;
    }

    private Map<String, Object> buildInputDataMap(ExecutionContext context) {
        Map<String, Object> inputData = new LinkedHashMap<>();
        if (convertToFileConfig != null) {
            inputData.put("format", convertToFileConfig.format());
            inputData.put("filename", resolveTemplateString(convertToFileConfig.filename(), context));
            inputData.put("delimiter", convertToFileConfig.delimiter());
            inputData.put("includeHeaders", convertToFileConfig.includeHeaders());
            if (convertToFileConfig.value() != null) {
                inputData.put("value", resolveTemplateString(convertToFileConfig.value(), context));
            }
        }
        return inputData;
    }

    // Getters
    public Core.ConvertToFileConfig getConvertToFileConfig() { return convertToFileConfig; }

    // Builder
    public static class Builder {
        private String nodeId;
        private Core.ConvertToFileConfig convertToFileConfig;

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder convertToFileConfig(Core.ConvertToFileConfig convertToFileConfig) { this.convertToFileConfig = convertToFileConfig; return this; }
        public ConvertToFileNode build() { return new ConvertToFileNode(nodeId, convertToFileConfig); }
    }

    public static Builder builder() { return new Builder(); }
}
