package com.apimarketplace.orchestrator.tools.file;

import com.apimarketplace.orchestrator.services.template.ReportedParams;
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

    /**
     * LC-066: tags the storage row of a file stored from a restricted execution. Optional so the
     * narrow tests that build this provider by hand keep working; a restricted store_file is
     * refused when it is missing rather than stored untagged.
     */
    private com.apimarketplace.common.storage.service.StorageService storageIndex;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setStorageIndex(com.apimarketplace.common.storage.service.StorageService storageIndex) {
        this.storageIndex = storageIndex;
    }

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
            // Both tools WRITE a file into the workspace storage (and its quota). This provider
            // has no read/write category, so the workspace role is checked directly: a VIEWER
            // is read-only, exactly as on the upload endpoints.
            if (com.apimarketplace.auth.client.access.OrgAccessGuard.isRoleWriteBlocked(
                    context.orgId(), context.orgRole())) {
                return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
                        "Your workspace role is read-only (VIEWER), so '" + toolName + "' is not allowed: "
                                + "it stores a file in the workspace. Only the workspace owner can change "
                                + "your role, you cannot.");
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

                Redirects are followed, up to 5 hops, so ordinary share links and shortened
                URLs work. Only http and https are fetched, and only from public addresses:
                a private or internal address is refused with INVALID_PARAMETER_VALUE, and so
                is a redirect that leads to one or that drops from https to http. Those are
                refusals, not outages - retrying the same URL returns the same answer, so
                correct the URL instead.

                A link that needs a sign-in (a private Google Drive or Dropbox file) redirects
                to a login page, so this returns that page rather than the file. Use the
                provider's own integration for those, which reads the file with your
                connected account.
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

                Once this conversation or run has read Gmail or Google Drive, the stored file is
                classified as restricted: it is deleted after the restricted retention window, and
                files(action='view') of it is refused to a model outside the approved providers
                (Anthropic or OpenAI direct API).
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
            log.info("Downloading file from URL: {}", ReportedParams.maskUrlSecrets(url));

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

        } catch (FileDownloader.UrlNotAllowedException e) {
            // Before FileDownloadException, which it extends. A refusal is not transient:
            // reporting it as EXECUTION_FAILED tells an agent to retry, and it would retry
            // the same refusal until it runs out of iterations.
            log.warn("Refused to download from {}: {}", ReportedParams.maskUrlSecrets(url), e.getMessage());
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, e.getMessage());
        } catch (FileDownloader.FileDownloadException e) {
            log.error("Download failed from {}: {}", ReportedParams.maskUrlSecrets(url), e.getMessage());
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to download file: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                    "Invalid URL: " + ReportedParams.maskUrlSecrets(url));
        } catch (Exception e) {
            // Any client exception may word itself around the url; withhold its credentials.
            String reason = ReportedParams.scrubUrl(e.getMessage(), url);
            log.error("Failed to download file from {}: {} ({})", ReportedParams.maskUrlSecrets(url), reason,
                    e.getClass().getName());
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to download file: " + reason);
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

        // LC-066: the bytes come from the agent, so a restricted execution (Gmail / Drive content in
        // its context) may be storing exactly that content. The file must carry the tag: bounded
        // retention, and refused to non-allow-listed models when read back.
        boolean restricted = context != null && com.apimarketplace.common.classification.DataSensitivity
                .fromCredentials(context.credentials()).isRestricted();
        if (restricted && storageIndex == null) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, RESTRICTED_FILE_UNTAGGABLE);
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

            if (restricted) {
                RestrictedTagOutcome outcome = tagRestricted(tenantId, fileRef);
                if (outcome == RestrictedTagOutcome.REMOVED) {
                    return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, RESTRICTED_FILE_UNTAGGABLE);
                }
                if (outcome == RestrictedTagOutcome.LEFT_BEHIND) {
                    return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                        String.format(RESTRICTED_FILE_LEFT_BEHIND, filename));
                }
            }

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

    static final String RESTRICTED_FILE_UNTAGGABLE =
        "The file was not stored: this conversation holds Gmail or Google Drive content, so a stored file "
        + "must be classified as restricted, and that classification could not be recorded right now. "
        + "Nothing was saved. Try store_file again in a moment.";

    static final String RESTRICTED_FILE_LEFT_BEHIND =
        "The file was not stored correctly: this conversation holds Gmail or Google Drive content, so a "
        + "stored file must be classified as restricted, that classification could not be recorded, and "
        + "removing the unclassified copy also failed. A file named '%s' may still appear in the user's "
        + "files. Do not use it and do not call store_file again for it; tell the user to delete that "
        + "file and to retry later.";

    /** Result of tagging a file stored from a restricted context. */
    enum RestrictedTagOutcome {
        /** Tagged RESTRICTED (or nothing to tag: local/mock storage without an index row). */
        TAGGED,
        /** Not taggable, and both the object and its index row were removed: nothing was saved. */
        REMOVED,
        /** Not taggable, and the cleanup failed: an untagged copy may remain. */
        LEFT_BEHIND
    }

    /**
     * Tags the stored file's index row RESTRICTED (bounded retention, refused to non-allow-listed
     * models when read back). A file with no index row (local/mock storage) has nothing to tag. On
     * failure the file is removed again, object AND index row: a restricted file stored untagged is
     * the leak this closes, so it fails closed, and the outcome says whether the removal worked so
     * the tool never claims "nothing was saved" when bytes survive.
     */
    private RestrictedTagOutcome tagRestricted(String tenantId, FileRef fileRef) {
        if (fileRef == null || fileRef.id() == null || fileRef.id().isBlank()) {
            return RestrictedTagOutcome.TAGGED;
        }
        java.util.UUID fileId;
        try {
            fileId = java.util.UUID.fromString(fileRef.id());
        } catch (IllegalArgumentException e) {
            log.warn("store_file: restricted file has a non-UUID id {}; deleting it", fileRef.id());
            fileId = null;
        }
        if (fileId != null) {
            try {
                int tagged = storageIndex.markRestricted(tenantId, List.of(fileId), null);
                if (tagged > 0) {
                    return RestrictedTagOutcome.TAGGED;
                }
                log.warn("store_file: restricted file {} has no index row to tag; deleting it", fileRef.id());
            } catch (Exception e) {
                log.warn("store_file: could not tag restricted file {}; deleting it: {}", fileRef.id(), e.getMessage());
            }
        }
        boolean objectRemoved = removeObject(tenantId, fileRef.path());
        boolean rowRemoved = fileId == null || removeIndexRow(tenantId, fileId, fileRef.path());
        return objectRemoved && rowRemoved ? RestrictedTagOutcome.REMOVED : RestrictedTagOutcome.LEFT_BEHIND;
    }

    /**
     * Deletes the object under its OWNER tenant (the key's prefix): the remote internal delete
     * route refuses any other identity, and a tool thread has no request-scope X-User-ID to fall
     * back on, so the key-only delete would be refused and report {@code false}.
     */
    private boolean removeObject(String ownerTenantId, String key) {
        try {
            if (fileStorageService.delete(ownerTenantId, key)) {
                return true;
            }
            log.error("store_file: untagged restricted file was NOT deleted from object storage, key={}", key);
        } catch (Exception e) {
            log.error("store_file: untagged restricted file was NOT deleted from object storage, key={}: {}",
                key, e.getMessage());
        }
        return false;
    }

    /**
     * Soft-deletes the index row so the file no longer lists. A row that is already absent is
     * fine (nothing lists it); only a failing call counts as a failed cleanup.
     */
    private boolean removeIndexRow(String tenantId, java.util.UUID fileId, String key) {
        try {
            storageIndex.deleteById(fileId, tenantId);
            return true;
        } catch (Exception e) {
            log.error("store_file: index row {} of untagged restricted file was NOT deleted, key={}: {}",
                fileId, key, e.getMessage());
            return false;
        }
    }

    private String getContextValue(ToolExecutionContext context, String key, String defaultValue) {
        if (context == null || context.credentials() == null) {
            return defaultValue;
        }
        Object value = context.credentials().get(key);
        return value != null ? value.toString() : defaultValue;
    }
}
