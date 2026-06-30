package com.apimarketplace.orchestrator.tools.file;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.services.file.FileDownloader;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.apimarketplace.orchestrator.utils.file.FileConstants;
import com.apimarketplace.orchestrator.utils.file.FileNameExtractor;
import com.apimarketplace.orchestrator.utils.file.MimeTypeRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.apimarketplace.agent.registry.ToolSchemaGenerator.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Provider for file-related tools.
 * Enables workflows to download files from URLs and store them in S3/MinIO.
 *
 * The stored files are returned as FileRef objects that the frontend can detect
 * and render with download buttons.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileToolsProvider implements ToolsProvider {

    private final FileStorageService fileStorageService;
    private final FileDownloader fileDownloader;
    private final MimeTypeRegistry mimeTypeRegistry;

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.UTILITY;
    }

    @Override
    public List<AgentToolDefinition> getTools() {
        return List.of(
            buildDownloadFile(),
            buildStoreFile()
        );
    }

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> parameters, ToolExecutionContext context) {
        try {
            String tenantId = context.tenantId();
            if (tenantId == null) {
                return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "tenantId is required");
            }

            return switch (toolName) {
                case "download_file" -> executeDownloadFile(parameters, tenantId, context);
                case "store_file" -> executeStoreFile(parameters, tenantId, context);
                default -> ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown tool: " + toolName);
            };
        } catch (Exception e) {
            log.error("Error executing file tool {}: {}", toolName, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Error: " + e.getMessage());
        }
    }

    // ==================== Tool Definitions ====================

    private AgentToolDefinition buildDownloadFile() {
        List<ToolParameter> params = List.of(
            stringParam("url", "URL of the file to download", true),
            stringParam("filename", "Name to save the file as (optional, derived from URL if not provided)", false),
            stringParam("mime_type", "MIME type of the file (optional, auto-detected if not provided)", false)
        );

        return AgentToolDefinition.builder()
            .name("download_file")
            .description("""
                Downloads a file from a URL and stores it in S3/MinIO storage.
                Returns: {file, source_url} where `file` is a canonical FileRef {_type:'file', path, name, mimeType, size}.
                Use `file` in interfaces: <img src="{{photo}}"/> with variable_mapping: {photo: '{{core:<label>.output.file}}'} - the frontend injects an auth token (logged-in app) or an HMAC signature (marketplace + share preview for anonymous visitors).
                Supports: images (PNG, JPG, GIF, WebP), documents (PDF, DOCX), videos (MP4), audio (MP3), and more.
                Max file size: """ + FileConstants.MAX_FILE_SIZE_MB + """
                 MB.
                """)
            .category(ToolCategory.UTILITY)
            .parameters(params)
            .requiredParameters(List.of("url"))
            .inputSchema(generateInputSchema(params, List.of("url")))
            .helpText("""
                Downloads and stores a file from a URL.

                Example: download_file(url="https://example.com/report.pdf")
                Returns: FileRef with path, name, mimeType, size
                """)
            .requiresAuth(true)
            .tags(List.of("file", "download", "storage"))
            .build();
    }

    private AgentToolDefinition buildStoreFile() {
        List<ToolParameter> params = List.of(
            stringParam("content", "Base64-encoded file content", true),
            stringParam("filename", "Name of the file", true),
            stringParam("mime_type", "MIME type of the file", true)
        );

        return AgentToolDefinition.builder()
            .name("store_file")
            .description("""
                Stores base64-encoded file content in S3/MinIO storage.
                Returns: {file, source_url} - same canonical FileRef shape as download_file.
                Use when you have raw content (e.g. API response bytes, generated CSV/PDF) to store for download.
                The content param must be standard base64 (java.util.Base64). Example: 'SGVsbG8gV29ybGQ=' for 'Hello World'.
                """)
            .category(ToolCategory.UTILITY)
            .parameters(params)
            .requiredParameters(List.of("content", "filename", "mime_type"))
            .inputSchema(generateInputSchema(params, List.of("content", "filename", "mime_type")))
            .helpText("""
                Stores base64-encoded file content.

                Example: store_file(content="SGVsbG8...", filename="output.txt", mime_type="text/plain")
                Returns: FileRef with path, name, mimeType, size
                """)
            .requiresAuth(true)
            .tags(List.of("file", "store", "storage"))
            .build();
    }

    // ==================== Tool Execution ====================

    private ToolExecutionResult executeDownloadFile(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        String url = (String) parameters.get("url");
        String filename = (String) parameters.get("filename");
        String mimeType = (String) parameters.get("mime_type");

        if (url == null || url.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "url is required");
        }

        try {
            log.info("Downloading file from URL: {}", url);

            // Download the file using injected downloader
            byte[] content = fileDownloader.download(url);

            if (content.length > FileConstants.MAX_FILE_SIZE_BYTES) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "File too large. Maximum size is " + FileConstants.MAX_FILE_SIZE_MB + " MB");
            }

            // Derive filename from URL if not provided
            if (filename == null || filename.isBlank()) {
                filename = FileNameExtractor.fromUrl(url);
            }

            // Auto-detect MIME type if not provided
            if (mimeType == null || mimeType.isBlank()) {
                mimeType = mimeTypeRegistry.resolve(filename, content);
            }

            // Get workflow context for storage path
            String workflowId = getContextValue(context, "workflowId", "unknown");
            String runId = getContextValue(context, "runId", "unknown");
            String stepAlias = getContextValue(context, "stepAlias", "download");

            // Store the file. sourceType=STEP_OUTPUT marks this as a workflow file artifact.
            // TODO(files-folders): epoch/spawn/itemIndex are not exposed on ToolExecutionContext
            // (only workflowId/runId/stepAlias are threaded via the variables map), so they default
            // to 0/0/null here. Thread them through ToolExecutionContext to fully group tool-produced
            // files by epoch/spawn/iteration.
            FileRef fileRef = fileStorageService.upload(
                tenantId, workflowId, runId, stepAlias,
                filename, mimeType, content,
                /* epoch */ 0, /* spawn */ 0, /* itemIndex */ null,
                com.apimarketplace.common.storage.service.StorageSourceTypes.STEP_OUTPUT
            );

            log.info("File stored successfully: path={}, size={} bytes", fileRef.path(), fileRef.size());

            // Return the FileRef as the result
            Map<String, Object> result = Map.of(
                "file", fileRef,
                "message", "File downloaded and stored successfully: " + filename
            );

            return ToolExecutionResult.success(result);

        } catch (FileDownloader.FileDownloadException e) {
            log.error("Download failed from {}: {}", url, e.getMessage());
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to download file: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid URL: " + url);
        } catch (Exception e) {
            log.error("Failed to download file from {}: {}", url, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to download file: " + e.getMessage());
        }
    }

    private ToolExecutionResult executeStoreFile(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        String content = (String) parameters.get("content");
        String filename = (String) parameters.get("filename");
        String mimeType = (String) parameters.get("mime_type");

        if (content == null || content.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "content is required");
        }
        if (filename == null || filename.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "filename is required");
        }
        if (mimeType == null || mimeType.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "mime_type is required");
        }

        try {
            // Decode base64 content
            byte[] data = java.util.Base64.getDecoder().decode(content);

            if (data.length > FileConstants.MAX_FILE_SIZE_BYTES) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "File too large. Maximum size is " + FileConstants.MAX_FILE_SIZE_MB + " MB");
            }

            // Get workflow context for storage path
            String workflowId = getContextValue(context, "workflowId", "unknown");
            String runId = getContextValue(context, "runId", "unknown");
            String stepAlias = getContextValue(context, "stepAlias", "store");

            // Store the file. sourceType=STEP_OUTPUT marks this as a workflow file artifact.
            // TODO(files-folders): epoch/spawn/itemIndex are not exposed on ToolExecutionContext
            // (only workflowId/runId/stepAlias are threaded via the variables map), so they default
            // to 0/0/null here. Thread them through ToolExecutionContext to fully group tool-produced
            // files by epoch/spawn/iteration.
            FileRef fileRef = fileStorageService.upload(
                tenantId, workflowId, runId, stepAlias,
                filename, mimeType, data,
                /* epoch */ 0, /* spawn */ 0, /* itemIndex */ null,
                com.apimarketplace.common.storage.service.StorageSourceTypes.STEP_OUTPUT
            );

            log.info("File stored successfully: path={}, size={} bytes", fileRef.path(), fileRef.size());

            Map<String, Object> result = Map.of(
                "file", fileRef,
                "message", "File stored successfully: " + filename
            );

            return ToolExecutionResult.success(result);

        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid base64 content");
        } catch (Exception e) {
            log.error("Failed to store file {}: {}", filename, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to store file: " + e.getMessage());
        }
    }

    // ==================== Helper Methods ====================

    private String getContextValue(ToolExecutionContext context, String key, String defaultValue) {
        if (context == null || context.credentials() == null) {
            return defaultValue;
        }
        Object value = context.credentials().get(key);
        return value != null ? value.toString() : defaultValue;
    }
}
