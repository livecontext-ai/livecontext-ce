package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.file.FileDownloader;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.apimarketplace.common.web.UrlSafetyValidator;
import com.apimarketplace.orchestrator.utils.file.FileConstants;
import com.apimarketplace.orchestrator.utils.file.FileNameExtractor;
import com.apimarketplace.orchestrator.utils.file.MimeTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Download File node - Downloads a file from URL and stores it in S3/MinIO.
 *
 * Flow:
 * 1. Resolve URL expression (supports SpEL templates like {{trigger.url}})
 * 2. Download file from URL
 * 3. Store in S3/MinIO with workflow context path
 * 4. Return FileRef in output for frontend download button
 *
 * Usage:
 * - Download images, PDFs, videos from external APIs
 * - Store API response files for later access
 * - Create downloadable outputs in workflows
 */
public class DownloadFileNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(DownloadFileNode.class);

    private final String urlExpression;
    private final String filenameExpression;
    private final String mimeTypeExpression;

    // Injected services
    private FileStorageService fileStorageService;
    private FileDownloader fileDownloader;
    private MimeTypeRegistry mimeTypeRegistry;

    public DownloadFileNode(String nodeId, String urlExpression, String filenameExpression, String mimeTypeExpression) {
        super(nodeId, NodeType.DOWNLOAD_FILE);
        this.urlExpression = urlExpression;
        this.filenameExpression = filenameExpression;
        this.mimeTypeExpression = mimeTypeExpression;
    }

    public void setFileStorageService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    public void setFileDownloader(FileDownloader fileDownloader) {
        this.fileDownloader = fileDownloader;
    }

    public void setMimeTypeRegistry(MimeTypeRegistry mimeTypeRegistry) {
        this.mimeTypeRegistry = mimeTypeRegistry;
    }

    /**
     * Accepts services from the registry.
     * DownloadFileNode needs file storage services.
     */
    @Override
    public void acceptServices(ServiceRegistry registry) {
        super.acceptServices(registry);
        this.fileStorageService = registry.getFileStorageService();
        this.fileDownloader = registry.getFileDownloader();
        this.mimeTypeRegistry = registry.getMimeTypeRegistry();
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        logger.info("Download file node executing: nodeId={}, itemId={}", nodeId, context.itemId());

        // Build resolved_params early so every exit path can include it
        Map<String, Object> earlyInputData = new java.util.LinkedHashMap<>();
        earlyInputData.put("url_expression", urlExpression);
        earlyInputData.put("filename_expression", filenameExpression);
        earlyInputData.put("mime_type_expression", mimeTypeExpression);

        try {
            // Validate required services
            if (fileStorageService == null) {
                return NodeExecutionResult.failureWithOutput(nodeId, "FileStorageService not configured",
                    buildFailureOutput(null, earlyInputData, context), System.currentTimeMillis() - startTime);
            }
            if (fileDownloader == null) {
                return NodeExecutionResult.failureWithOutput(nodeId, "FileDownloader not configured",
                    buildFailureOutput(null, earlyInputData, context), System.currentTimeMillis() - startTime);
            }

            // Resolve URL expression
            String url = resolveExpression(urlExpression, context);
            if (url == null || url.isBlank()) {
                return NodeExecutionResult.failureWithOutput(nodeId, "URL is required",
                    buildFailureOutput(null, earlyInputData, context), System.currentTimeMillis() - startTime);
            }

            // Update earlyInputData with resolved URL for richer diagnostics
            earlyInputData.put("url", url);

            // SSRF protection: validate URL before downloading
            UrlSafetyValidator.validateUrl(url);

            logger.info("Downloading from URL: {}", url);

            // Download file using injected downloader
            byte[] content = fileDownloader.download(url);

            logger.debug("Downloaded {} bytes from {}", content.length, url);

            // Check file size
            if (content.length > FileConstants.MAX_FILE_SIZE_BYTES) {
                return NodeExecutionResult.failureWithOutput(nodeId,
                    "File too large. Maximum size is " + FileConstants.MAX_FILE_SIZE_MB + " MB",
                    buildFailureOutput(url, earlyInputData, context), System.currentTimeMillis() - startTime);
            }

            // Resolve filename (or derive from URL)
            String filename = resolveExpression(filenameExpression, context);
            if (filename == null || filename.isBlank()) {
                filename = FileNameExtractor.fromUrl(url);
            }

            // Resolve MIME type (or auto-detect)
            String mimeType = resolveExpression(mimeTypeExpression, context);
            if (mimeType == null || mimeType.isBlank()) {
                mimeType = mimeTypeRegistry != null
                    ? mimeTypeRegistry.resolve(filename, content)
                    : FileConstants.DEFAULT_MIME_TYPE;
            }

            // Ensure filename has an extension (e.g., "600" from picsum.photos → "600.jpeg")
            if (filename != null && !filename.contains(".") && mimeType != null) {
                String ext = mimeTypeToExtension(mimeType);
                if (ext != null) {
                    filename = filename + ext;
                }
            }

            // Store file in S3 with run context (epoch/spawn/itemIndex) so the file row can be
            // grouped by workflow → epoch → spawn → iteration.
            FileRef fileRef = fileStorageService.upload(
                context.tenantId(),
                context.plan().getId(),
                context.runId(),
                nodeId,
                filename,
                mimeType,
                content,
                resolveStorageEpoch(context),
                context.spawn(),
                context.itemIndex(),
                com.apimarketplace.common.storage.service.StorageSourceTypes.STEP_OUTPUT
            );

            logger.info("File downloaded and stored: path={}, size={} bytes", fileRef.path(), fileRef.size());

            // Canonical FileRef only - Jackson serialises the record as
            // {_type:"file", path, name, mimeType, size}. Frontend file-proxy injector and
            // showcase HMAC rewriter both probe this shape; pre-baked URL strings would be
            // invisible to anonymous marketplace/share visitors.
            Map<String, Object> result = new java.util.HashMap<>();
            result.put("file", fileRef);
            result.put("source_url", url);
            result.put("node_type", "DOWNLOAD_FILE");
            result.put("item_index", context.itemIndex());
            result.put("itemIndex", context.itemIndex());
            result.put("item_id", context.itemId());

            // Persist resolved input params for inspector visibility
            Map<String, Object> inputData = new java.util.LinkedHashMap<>();
            inputData.put("url", url);
            if (filename != null) inputData.put("filename", filename);
            if (mimeType != null) inputData.put("mimeType", mimeType);
            result.put("resolved_params", inputData);

            return NodeExecutionResult.success(nodeId, result);

        } catch (FileDownloader.FileDownloadException e) {
            logger.error("Download failed for nodeId={}: {}", nodeId, e.getMessage());
            String resolvedUrl = (String) earlyInputData.get("url");
            return NodeExecutionResult.failureWithOutput(nodeId, "Download failed: " + e.getMessage(),
                buildFailureOutput(resolvedUrl, earlyInputData, context), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            logger.error("Download file failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            String resolvedUrl = (String) earlyInputData.get("url");
            return NodeExecutionResult.failureWithOutput(nodeId, "Download failed: " + e.getMessage(),
                buildFailureOutput(resolvedUrl, earlyInputData, context), System.currentTimeMillis() - startTime);
        }
    }

    /**
     * Builds a failure output that contains the canonical declared keys with safe defaults.
     * Ensures persisted JSONB always has the documented shape even when execution fails early.
     *
     * @param resolvedUrl the resolved source URL if available (may be null before URL resolution)
     * @param inputData   the resolved input params snapshot
     * @param context     the current execution context used to stamp mandatory persistence metadata
     */
    private Map<String, Object> buildFailureOutput(String resolvedUrl, Map<String, Object> inputData, ExecutionContext context) {
        Map<String, Object> failOutput = new java.util.HashMap<>();
        failOutput.put("file", null);
        failOutput.put("source_url", resolvedUrl);
        failOutput.put("resolved_params", inputData);
        return enrichWithMetadata(failOutput, context);
    }

    private String resolveExpression(String expression, ExecutionContext context) {
        if (expression == null || expression.isBlank()) {
            return null;
        }

        logger.info("📥 [DownloadFile] Resolving expression: {}", expression);
        logger.info("📥 [DownloadFile] Context stepOutputs keys: {}",
            context.stepOutputs() != null ? context.stepOutputs().keySet() : "NULL");

        if (templateAdapter != null) {
            try {
                Map<String, Object> toResolve = Map.of("__expr__", expression);
                Map<String, Object> resolved = templateAdapter.resolveTemplates(toResolve, context);
                Object value = resolved.get("__expr__");
                logger.info("📥 [DownloadFile] Resolved to: {}", value);
                return value != null ? value.toString() : null;
            } catch (Exception e) {
                logger.warn("⚠️ Failed to resolve expression: {} - {}", expression, e.getMessage());
                return expression; // Return as-is
            }
        } else {
            logger.warn("📥 [DownloadFile] templateAdapter is NULL!");
        }

        return expression;
    }

    private static String mimeTypeToExtension(String mimeType) {
        if (mimeType == null) return null;
        return switch (mimeType.toLowerCase()) {
            case "image/jpeg" -> ".jpeg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            case "application/pdf" -> ".pdf";
            case "text/plain" -> ".txt";
            case "text/csv" -> ".csv";
            case "text/html" -> ".html";
            case "application/json" -> ".json";
            case "application/xml", "text/xml" -> ".xml";
            case "application/zip" -> ".zip";
            case "application/gzip" -> ".gz";
            case "audio/mpeg" -> ".mp3";
            case "video/mp4" -> ".mp4";
            default -> null;
        };
    }

    public String getUrlExpression() {
        return urlExpression;
    }

    public String getFilenameExpression() {
        return filenameExpression;
    }

    public String getMimeTypeExpression() {
        return mimeTypeExpression;
    }

    public static class Builder {
        private String nodeId;
        private String urlExpression;
        private String filenameExpression;
        private String mimeTypeExpression;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder urlExpression(String urlExpression) {
            this.urlExpression = urlExpression;
            return this;
        }

        public Builder filenameExpression(String filenameExpression) {
            this.filenameExpression = filenameExpression;
            return this;
        }

        public Builder mimeTypeExpression(String mimeTypeExpression) {
            this.mimeTypeExpression = mimeTypeExpression;
            return this;
        }

        public DownloadFileNode build() {
            return new DownloadFileNode(nodeId, urlExpression, filenameExpression, mimeTypeExpression);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
