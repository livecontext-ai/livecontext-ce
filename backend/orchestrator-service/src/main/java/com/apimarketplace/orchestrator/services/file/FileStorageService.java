package com.apimarketplace.orchestrator.services.file;

import com.apimarketplace.orchestrator.domain.file.FileRef;

import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

/**
 * Interface for file storage operations using S3-compatible storage.
 * Implementations can use MinIO (dev) or AWS S3 / Cloudflare R2 (prod).
 *
 * Files are organized by: tenant/workflow/run/step/filename
 */
public interface FileStorageService {

    /**
     * Uploads a file and returns a FileRef for inclusion in workflow outputs.
     *
     * @param tenantId   Tenant identifier
     * @param workflowId Workflow identifier
     * @param runId      Run identifier
     * @param stepAlias  Step alias (normalized key)
     * @param fileName   Original filename
     * @param mimeType   MIME type of the file
     * @param content    File content as InputStream
     * @param size       File size in bytes
     * @return FileRef containing the storage key and metadata
     */
    FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                   String fileName, String mimeType, InputStream content, long size);

    /**
     * Uploads a file from byte array.
     *
     * @param tenantId   Tenant identifier
     * @param workflowId Workflow identifier
     * @param runId      Run identifier
     * @param stepAlias  Step alias (normalized key)
     * @param fileName   Original filename
     * @param mimeType   MIME type of the file
     * @param content    File content as byte array
     * @return FileRef containing the storage key and metadata
     */
    FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                   String fileName, String mimeType, byte[] content);

    /**
     * Context-carrying byte[] upload for WORKFLOW file producers (Download File, Compression,
     * Convert To File, SFTP, file tools). Threads the real run coordinates
     * ({@code epoch}, {@code spawn}, {@code itemIndex}) and a meaningful {@code sourceType}
     * (see {@link com.apimarketplace.common.storage.service.StorageSourceTypes}) into the
     * {@code storage.storage} index row so files can later be grouped by
     * workflow → epoch → spawn → iteration.
     *
     * <p>The no-context {@link #upload(String, String, String, String, String, String, byte[])}
     * keeps working - it delegates here with {@code epoch=0, spawn=0, itemIndex=null,
     * sourceType=S3_FILE}. Generic / non-workflow callers are unaffected.</p>
     */
    default FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                           String fileName, String mimeType, byte[] content,
                           int epoch, int spawn, Integer itemIndex, String sourceType) {
        return upload(tenantId, workflowId, runId, stepAlias, fileName, mimeType, content);
    }

    /**
     * Context-carrying InputStream upload for WORKFLOW file producers (mirror of the byte[] variant).
     * The no-context InputStream {@code upload} delegates here with defaults.
     */
    default FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                           String fileName, String mimeType, InputStream content, long size,
                           int epoch, int spawn, Integer itemIndex, String sourceType) {
        return upload(tenantId, workflowId, runId, stepAlias, fileName, mimeType, content, size);
    }

    /**
     * Generates a pre-signed URL for downloading a file.
     * The URL expires after the specified duration.
     *
     * @param key      S3 object key
     * @param duration URL validity duration
     * @return Pre-signed download URL
     */
    String generateDownloadUrl(String key, Duration duration);

    /**
     * Generates a pre-signed URL with default expiry (15 minutes).
     *
     * @param key S3 object key
     * @return Pre-signed download URL
     */
    default String generateDownloadUrl(String key) {
        return generateDownloadUrl(key, Duration.ofMinutes(15));
    }

    /**
     * Owner-aware presign, mirror of {@link #download(String, String)}: remote
     * implementations authorize the internal presign by the KEY-OWNER tenant
     * prefix, so an org teammate previewing a shared file must present the
     * owner's tenant (the entity is already authorized upstream via the
     * org-scoped lookup), not the caller's. Passing the caller's id for a
     * teammate-owned key 403s and the preview silently breaks. Local/mock
     * implementations ignore the tenant.
     *
     * @param ownerTenantId tenant that owns the key ({@code StorageEntity.getTenantId()})
     * @param key           S3 object key
     * @param duration      URL validity duration
     */
    default String generateDownloadUrl(String ownerTenantId, String key, Duration duration) {
        return generateDownloadUrl(key, duration);
    }

    /** Owner-aware presign with default expiry (15 minutes). */
    default String generateDownloadUrl(String ownerTenantId, String key) {
        return generateDownloadUrl(ownerTenantId, key, Duration.ofMinutes(15));
    }

    /**
     * Downloads file content.
     *
     * @param key S3 object key
     * @return File content as byte array, or empty if not found
     */
    Optional<byte[]> download(String key);

    /**
     * Downloads file content while preserving tenant ownership checks in remote
     * storage implementations. Local/mock implementations can ignore tenantId
     * and keep their key-only behavior.
     *
     * @param tenantId Tenant identifier that owns the source key
     * @param key S3 object key
     * @return File content as byte array, or empty if not found
     */
    default Optional<byte[]> download(String tenantId, String key) {
        return download(key);
    }

    /**
     * Deletes a file from storage.
     *
     * @param key S3 object key
     * @return true if deleted, false if not found
     */
    boolean delete(String key);

    /**
     * Deletes all files for a specific run.
     * Used for cleanup when a run is deleted.
     *
     * @param tenantId   Tenant identifier
     * @param workflowId Workflow identifier
     * @param runId      Run identifier
     * @return Number of files deleted
     */
    int deleteRunFiles(String tenantId, String workflowId, String runId);

    /**
     * Checks if a file exists.
     *
     * @param key S3 object key
     * @return true if file exists
     */
    boolean exists(String key);
}
