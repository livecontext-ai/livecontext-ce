package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.zip.*;

/**
 * Compression node - Compresses and decompresses data.
 *
 * Flow:
 * 1. Resolve input value from SpEL expression
 * 2. Compress (string -> compress -> base64) or decompress (base64 -> decompress -> string)
 * 3. Return result with metadata
 *
 * Supported formats: gzip, zip, base64, deflate
 *
 * Usage:
 * - Compress data before storage or transfer
 * - Decompress previously compressed data
 * - Reduce payload sizes in workflows
 */
public class CompressionNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(CompressionNode.class);

    /**
     * Maximum allowed size for decompressed data (50 MB).
     * Prevents zip bomb attacks where a small compressed payload expands to gigabytes.
     */
    static final long MAX_DECOMPRESSED_SIZE = 50L * 1024 * 1024;

    private final Core.CompressionConfig config;
    private FileStorageService fileStorageService;

    private CompressionNode(Builder builder) {
        super(builder.nodeId, NodeType.COMPRESSION);
        this.config = builder.config;
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
        logger.info("Compression node executing: nodeId={}, operation={}, format={}, itemId={}",
            nodeId, config.operation(), config.format(), context.itemId());

        try {
            String inputValue = resolveExpression(config.value(), context);

            if (inputValue == null || inputValue.isEmpty()) {
                logger.warn("Compression node received null/empty input: nodeId={}", nodeId);
                Map<String, Object> result = buildOutput("", config.operation(), config.format(), true, context);
                return NodeExecutionResult.success(nodeId, result);
            }

            String output;
            if ("decompress".equals(config.operation())) {
                output = decompress(inputValue, config.format());
            } else {
                output = compress(inputValue, config.format());
            }

            Map<String, Object> result = buildOutput(output, config.operation(), config.format(), true, context);

            // Upload to S3 on compress only (non-fatal on failure)
            if ("compress".equals(config.operation()) && fileStorageService != null && output != null && !output.isEmpty()) {
                try {
                    byte[] compressedBytes = Base64.getDecoder().decode(output);
                    String filename = (config.filename() != null ? config.filename() : "compressed") + getCompressedExtension(config.format());
                    String mimeType = getCompressedMimeType(config.format());
                    FileRef fileRef = fileStorageService.upload(
                        context.tenantId(), context.plan().getId(), context.runId(),
                        nodeId, filename, mimeType, compressedBytes,
                        resolveStorageEpoch(context), context.spawn(), context.itemIndex(),
                        com.apimarketplace.common.storage.service.StorageSourceTypes.STEP_OUTPUT);
                    // Canonical FileRef only - frontend file-proxy injector and showcase
                    // HMAC rewriter both probe this shape.
                    result.put("file", fileRef);
                } catch (Exception e) {
                    logger.warn("S3 upload failed (non-fatal): {}", e.getMessage());
                }
            }

            logger.info("Compression completed: nodeId={}, operation={}, format={}", nodeId, config.operation(), config.format());
            return NodeExecutionResult.success(nodeId, result);

        } catch (Exception e) {
            logger.error("Compression execution failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            Map<String, Object> result = buildOutput(null, config.operation(), config.format(), false, context);
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), result, 0L);
        }
    }

    /**
     * Compresses a string using the specified format, returning base64-encoded result.
     */
    private String compress(String input, String format) throws Exception {
        if ("base64".equals(format)) {
            return Base64.getEncoder().encodeToString(
                input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        byte[] inputBytes = input.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();

        switch (format) {
            case "zip" -> {
                String filename = config.filename() != null ? config.filename() : "data.txt";
                try (ZipOutputStream zipOut = new ZipOutputStream(byteOut)) {
                    zipOut.putNextEntry(new ZipEntry(filename));
                    zipOut.write(inputBytes);
                    zipOut.closeEntry();
                }
            }
            case "deflate" -> {
                try (DeflaterOutputStream deflater = new DeflaterOutputStream(byteOut)) {
                    deflater.write(inputBytes);
                }
            }
            case "gzip" -> {
                try (GZIPOutputStream gzipOut = new GZIPOutputStream(byteOut)) {
                    gzipOut.write(inputBytes);
                }
            }
            default -> throw new IllegalArgumentException(
                "Unsupported compression format: " + format + ". Supported formats: gzip, zip, deflate, base64");
        }

        return Base64.getEncoder().encodeToString(byteOut.toByteArray());
    }

    /**
     * Decompresses a base64-encoded compressed string back to the original string.
     */
    private String decompress(String base64Input, String format) throws Exception {
        if ("base64".equals(format)) {
            return new String(Base64.getDecoder().decode(base64Input),
                java.nio.charset.StandardCharsets.UTF_8);
        }

        byte[] compressedBytes = Base64.getDecoder().decode(base64Input);
        ByteArrayInputStream byteIn = new ByteArrayInputStream(compressedBytes);
        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();

        switch (format) {
            case "zip" -> {
                try (ZipInputStream zipIn = new ZipInputStream(byteIn)) {
                    zipIn.getNextEntry();
                    readWithSizeLimit(zipIn, byteOut);
                }
            }
            case "deflate" -> {
                try (InflaterInputStream inflater = new InflaterInputStream(byteIn)) {
                    readWithSizeLimit(inflater, byteOut);
                }
            }
            case "gzip" -> {
                try (GZIPInputStream gzipIn = new GZIPInputStream(byteIn)) {
                    readWithSizeLimit(gzipIn, byteOut);
                }
            }
            default -> throw new IllegalArgumentException(
                "Unsupported decompression format: " + format + ". Supported formats: gzip, zip, deflate, base64");
        }

        return byteOut.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Reads from an InputStream into a ByteArrayOutputStream with a size limit.
     * Prevents zip bomb attacks by aborting when decompressed data exceeds MAX_DECOMPRESSED_SIZE.
     *
     * @param input  the decompression input stream
     * @param output the output stream to write decompressed bytes to
     * @throws java.io.IOException if an I/O error occurs or the size limit is exceeded
     */
    private void readWithSizeLimit(InputStream input, ByteArrayOutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        long totalRead = 0;
        int len;
        while ((len = input.read(buffer)) != -1) {
            totalRead += len;
            if (totalRead > MAX_DECOMPRESSED_SIZE) {
                throw new IOException(
                    "Decompressed data exceeds maximum allowed size of " + (MAX_DECOMPRESSED_SIZE / (1024 * 1024)) + " MB");
            }
            output.write(buffer, 0, len);
        }
    }

    private Map<String, Object> buildOutput(String result, String operation, String format, boolean success, ExecutionContext context) {
        Map<String, Object> output = new HashMap<>();
        output.put("result", result);
        output.put("operation", operation);
        output.put("format", format);
        output.put("success", success);
        // Shape-stability with DownloadFileNode/ConvertToFileNode: always seed `file: null`.
        // The compress-success S3-upload branch overwrites with the real FileRef when S3
        // is wired AND upload succeeds; decompress and failure paths keep the null so
        // `{{core:label.output.file}}` resolves consistently across runs.
        output.put("file", null);
        output.put("node_type", "COMPRESSION");
        output.put("item_index", context.itemIndex());
        output.put("itemIndex", context.itemIndex());
        output.put("item_id", context.itemId());
        output.put("resolved_params", buildInputDataMap(operation, format, context));
        return output;
    }

    private Map<String, Object> buildInputDataMap(String operation, String format, ExecutionContext context) {
        Map<String, Object> inputData = new LinkedHashMap<>();
        inputData.put("operation", operation);
        inputData.put("format", format);
        if (config != null) {
            if (config.value() != null) inputData.put("value", resolveTemplateString(config.value(), context));
            if (config.filename() != null) inputData.put("filename", resolveTemplateString(config.filename(), context));
        }
        return inputData;
    }

    private String getCompressedExtension(String format) {
        return switch (format) {
            case "gzip" -> ".gz";
            case "zip" -> ".zip";
            case "deflate" -> ".deflate";
            case "base64" -> ".b64";
            default -> "";
        };
    }

    private String getCompressedMimeType(String format) {
        return switch (format) {
            case "gzip" -> "application/gzip";
            case "zip" -> "application/zip";
            default -> "application/octet-stream";
        };
    }

    private String resolveExpression(String expression, ExecutionContext context) {
        if (expression == null || expression.isBlank()) {
            return null;
        }

        if (templateAdapter != null) {
            try {
                Map<String, Object> toResolve = Map.of("__expr__", expression);
                Map<String, Object> resolved = templateAdapter.resolveTemplates(toResolve, context);
                Object value = resolved.get("__expr__");
                return value != null ? value.toString() : null;
            } catch (Exception e) {
                logger.warn("Failed to resolve expression: {} - {}", expression, e.getMessage());
                return expression;
            }
        }

        return expression;
    }

    public Core.CompressionConfig getConfig() {
        return config;
    }

    // ========================================================================
    // BUILDER
    // ========================================================================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String nodeId;
        private Core.CompressionConfig config;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder compressionConfig(Core.CompressionConfig config) {
            this.config = config;
            return this;
        }

        public CompressionNode build() {
            if (config == null) {
                config = new Core.CompressionConfig(null, null, null, null);
            }
            return new CompressionNode(this);
        }
    }
}
