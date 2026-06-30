package com.apimarketplace.storage.service.file;

import com.apimarketplace.common.storage.service.StorageSourceTypes;
import com.apimarketplace.storage.domain.FileRef;

import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Interface for file storage operations using S3-compatible storage.
 * Implementations can use MinIO (dev) or AWS S3 / Cloudflare R2 (prod).
 *
 * Files are organized by: tenant/workflow/run/step/filename
 */
public interface FileStorageService {

    FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                   String fileName, String mimeType, InputStream content, long size);

    FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                   String fileName, String mimeType, byte[] content);

    /**
     * Context-carrying byte[] upload for WORKFLOW file producers. Threads the real run coordinates
     * ({@code epoch}, {@code spawn}, {@code itemIndex}) and a meaningful {@code sourceType} into the
     * {@code storage.storage} index row so files can later be grouped by
     * workflow → epoch → spawn → iteration.
     *
     * <p>The legacy no-context {@link #upload(String, String, String, String, String, String, byte[])}
     * keeps working - it delegates here with {@code epoch=0, spawn=0, itemIndex=null,
     * sourceType=S3_FILE}, so generic / non-workflow callers are unaffected.</p>
     */
    default FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                           String fileName, String mimeType, byte[] content,
                           int epoch, int spawn, Integer itemIndex, String sourceType) {
        return upload(tenantId, workflowId, runId, stepAlias, fileName, mimeType, content);
    }

    /**
     * Context-carrying InputStream upload for WORKFLOW file producers (mirror of the byte[] variant
     * above). The legacy no-context InputStream {@code upload} delegates here with defaults.
     */
    default FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                           String fileName, String mimeType, InputStream content, long size,
                           int epoch, int spawn, Integer itemIndex, String sourceType) {
        return upload(tenantId, workflowId, runId, stepAlias, fileName, mimeType, content, size);
    }

    /**
     * Generic upload without workflow context.
     * Key format: {tenantId}/general/{category}/{uuid8}_{fileName}
     */
    FileRef uploadGeneric(String tenantId, String category, String fileName, String mimeType,
                          InputStream content, long size);

    /**
     * Folder-aware generic upload (V313 + folder-aware upload) - stamps
     * {@code parent_folder_id} on the indexed row so the file lands in the user's
     * current manual folder ({@code parentFolderId} null = root). Default ignores
     * the folder for implementations that don't index manual folders.
     */
    default FileRef uploadGeneric(String tenantId, String category, String fileName, String mimeType,
                                  InputStream content, long size, UUID parentFolderId) {
        return uploadGeneric(tenantId, category, fileName, mimeType, content, size);
    }

    String generateDownloadUrl(String key, Duration duration);

    default String generateDownloadUrl(String key) {
        return generateDownloadUrl(key, Duration.ofMinutes(15));
    }

    Optional<byte[]> download(String key);

    /**
     * Streaming download - returns a {@link DownloadStream} that holds an
     * open S3 {@code ResponseInputStream} plus the object's
     * {@code contentLength} and {@code contentType}.
     *
     * <p>The caller MUST close the returned stream (try-with-resources) to
     * release the SDK's HTTP connection. Failing to close leaks connections.
     *
     * <p>Use this instead of {@link #download(String)} for HTTP proxy paths
     * (see {@code FileController.proxyDownload} +
     * {@code InternalFileController.download}) - it serves files of any size
     * with constant memory (~8 KB transfer buffer) and avoids the
     * {@code byte[]}-buffering OOM that was hit on 2026-05-04.
     */
    Optional<DownloadStream> openStream(String key);

    boolean delete(String key);

    int deleteRunFiles(String tenantId, String workflowId, String runId);

    boolean exists(String key);
}
