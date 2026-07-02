package com.apimarketplace.orchestrator.services.file;

import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.storage.client.StorageClient;
import com.apimarketplace.storage.client.dto.FileRefDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

/**
 * FileStorageService implementation that delegates to storage-service via StorageClient (HTTP).
 * After upload, indexes the file in StorageService (common-storage) for Storage Explorer.
 */
@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "s3", matchIfMissing = true)
public class StorageClientAdapter implements FileStorageService {

    private static final Logger logger = LoggerFactory.getLogger(StorageClientAdapter.class);

    private final StorageClient storageClient;

    public StorageClientAdapter(StorageClient storageClient) {
        this.storageClient = storageClient;
        logger.info("StorageClientAdapter initialized (remote storage mode)");
    }

    @Override
    public FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                          String fileName, String mimeType, InputStream content, long size) {
        return upload(tenantId, workflowId, runId, stepAlias, fileName, mimeType, content, size,
                /* epoch */ 0, /* spawn */ 0, /* itemIndex */ null,
                com.apimarketplace.common.storage.service.StorageSourceTypes.S3_FILE);
    }

    @Override
    public FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                          String fileName, String mimeType, InputStream content, long size,
                          int epoch, int spawn, Integer itemIndex, String sourceType) {
        try {
            byte[] bytes = content.readAllBytes();
            return upload(tenantId, workflowId, runId, stepAlias, fileName, mimeType, bytes,
                    epoch, spawn, itemIndex, sourceType);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file content", e);
        }
    }

    @Override
    public FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                          String fileName, String mimeType, byte[] content) {
        return upload(tenantId, workflowId, runId, stepAlias, fileName, mimeType, content,
                /* epoch */ 0, /* spawn */ 0, /* itemIndex */ null,
                com.apimarketplace.common.storage.service.StorageSourceTypes.S3_FILE);
    }

    @Override
    public FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                          String fileName, String mimeType, byte[] content,
                          int epoch, int spawn, Integer itemIndex, String sourceType) {
        // v3.1 - storage.storage indexing is now done server-side inside
        // S3FileStorageService.upload() (symmetric with uploadGeneric). The
        // orchestrator-side indexer call has been removed to eliminate the
        // duplicate row that used to land per upload. organizationId stays null
        // here so the StorageClient falls back to the request-context forwarder
        // (unchanged behavior); the 4 run-context fields are forwarded as query params.
        FileRefDto dto = storageClient.upload(tenantId, workflowId, runId, stepAlias, fileName, mimeType, content,
                /* organizationId */ null, epoch, spawn, itemIndex, sourceType);
        if (dto == null) {
            throw new RuntimeException("Storage service upload failed for: " + fileName);
        }
        return FileRef.of(dto.path(), dto.name(), dto.mimeType(), dto.size(), dto.id());
    }

    @Override
    public String generateDownloadUrl(String key, Duration duration) {
        int minutes = (int) duration.toMinutes();
        String url = storageClient.generateDownloadUrl(null, key, minutes);
        if (url == null) {
            throw new RuntimeException("Failed to generate download URL for: " + key);
        }
        return url;
    }

    @Override
    public String generateDownloadUrl(String ownerTenantId, String key, Duration duration) {
        // Owner-aware presign: the internal presign route authorizes by KEY-OWNER
        // prefix (isKeyOwnedByTenant), so org-shared files must present the
        // OWNER's tenant, not the caller's request context - else a teammate's
        // preview/download 403s silently. See FileStorageService javadoc.
        int minutes = (int) duration.toMinutes();
        String url = storageClient.generateDownloadUrl(ownerTenantId, key, minutes);
        if (url == null) {
            throw new RuntimeException("Failed to generate download URL for: " + key);
        }
        return url;
    }

    @Override
    public Optional<byte[]> download(String key) {
        byte[] content = storageClient.download(null, key);
        return Optional.ofNullable(content);
    }

    @Override
    public Optional<byte[]> download(String tenantId, String key) {
        byte[] content = storageClient.download(tenantId, key);
        return Optional.ofNullable(content);
    }

    @Override
    public boolean delete(String key) {
        return storageClient.delete(null, key);
    }

    @Override
    public int deleteRunFiles(String tenantId, String workflowId, String runId) {
        return storageClient.deleteRunFiles(tenantId, workflowId, runId);
    }

    @Override
    public boolean exists(String key) {
        return storageClient.exists(null, key);
    }

}
