package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.services.template.ReportedParams;
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

        // Hoisted out of the try so the CATCH reports it too. The normal failure here is a
        // bad archive or an unsupported format - well AFTER the resolution - and reporting
        // the configured template there gave `value` one meaning on success and another on
        // failure, on the same node.
        String inputValue = null;
        // Resolved once and used everywhere it names something (the zip entry, the upload) and
        // in the report. It used to be read CONFIGURED, so `{{trigger:in.output.name}}.txt`
        // named the file literally while `value` beside it resolved.
        String filename = null;

        try {
            filename = resolveExpression(config.filename(), context);
            inputValue = resolveExpression(config.value(), context);

            if (inputValue == null || inputValue.isEmpty()) {
                logger.warn("Compression node received null/empty input: nodeId={}", nodeId);
                Map<String, Object> result = buildOutput("", config.operation(), config.format(), true, context, inputValue, filename);
                return NodeExecutionResult.success(nodeId, result);
            }

            String output;
            if ("decompress".equals(config.operation())) {
                output = decompress(inputValue, config.format());
            } else {
                output = compress(inputValue, config.format(), filename);
            }

            Map<String, Object> result = buildOutput(output, config.operation(), config.format(), true, context, inputValue, filename);

            // Upload to S3 on compress only (non-fatal on failure)
            if ("compress".equals(config.operation()) && fileStorageService != null && output != null && !output.isEmpty()) {
                try {
                    byte[] compressedBytes = Base64.getDecoder().decode(output);
                    String uploadName = (filename != null ? filename : "compressed") + getCompressedExtension(config.format());
                    String mimeType = getCompressedMimeType(config.format());
                    FileRef fileRef = fileStorageService.upload(
                        context.tenantId(), context.plan().getId(), context.runId(),
                        nodeId, uploadName, mimeType, compressedBytes,
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
            Map<String, Object> result = buildOutput(null, config.operation(), config.format(), false, context, inputValue, filename);
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), result, 0L);
        }
    }

    /**
     * Compresses a string using the specified format, returning base64-encoded result.
     */
    private String compress(String input, String format, String resolvedFilename) throws Exception {
        if ("base64".equals(format)) {
            return Base64.getEncoder().encodeToString(
                input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        byte[] inputBytes = input.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();

        switch (format) {
            case "zip" -> {
                String filename = resolvedFilename != null ? resolvedFilename : "data.txt";
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

    private Map<String, Object> buildOutput(String result, String operation, String format, boolean success, ExecutionContext context, String resolvedValue, String resolvedFilename) {
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
        output.put("resolved_params", buildInputDataMap(operation, format, resolvedValue, resolvedFilename));
        return output;
    }

    /**
     * The node's configuration, as the node itself reads it.
     *
     * <p>Both of these used to be re-resolved for display only, and both answers were wrong.
     * {@code filename} is now resolved ONCE in {@link #execute} and that value names the zip
     * entry, the upload and this report, so all three agree. And {@code value} is the payload being
     * compressed - re-resolving ran the expression a second time, and
     * {@code resolveTemplateString} coerced the result to a String, putting the whole
     * payload (or a base64 blob, on decompress) onto the step row of every item.
     *
     * <p>{@code value} is what the node COMPRESSED, from its own evaluation, which is what
     * that key means on {@code ConvertToFileNode} too - one key, one meaning, across
     * sibling nodes. Before the work has resolved it (the failure path reached from the
     * catch), it is the configured expression, which is all there is to say. Either way it
     * is bounded: a payload worth compressing is too big for a column persisted per row.
     */
    private Map<String, Object> buildInputDataMap(String operation, String format, String resolvedValue,
                                                  String resolvedFilename) {
        Map<String, Object> inputData = new LinkedHashMap<>();
        inputData.put("operation", operation);
        inputData.put("format", format);
        if (config != null) {
            Object reportedValue = resolvedValue != null ? resolvedValue : config.value();
            if (reportedValue != null) {
                inputData.put("value", ReportedParams.valueFrom(config.value(), reportedValue));
            }
            // Before resolution ran (a failure that early), the configured text is all there is.
            Object reportedFilename = resolvedFilename != null ? resolvedFilename : config.filename();
            if (reportedFilename != null) {
                inputData.put("filename", ReportedParams.valueFrom(config.filename(), reportedFilename));
            }
        }
        return ReportedParams.forReport(inputData);
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
        // One resolver for every field of every node: typed, JSON for a structure, never the
        // configured template in place of a value (BaseNode#resolveTemplateValue).
        return resolveTemplateString(expression, context);
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
