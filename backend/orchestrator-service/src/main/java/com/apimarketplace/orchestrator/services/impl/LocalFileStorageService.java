package com.apimarketplace.orchestrator.services.impl;

import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Filesystem-based implementation of FileStorageService for CE monolith mode.
 * Stores files on the local filesystem instead of S3/MinIO.
 * <p>
 * Activated by: storage.type=local
 */
@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "local")
public class LocalFileStorageService implements FileStorageService {

    private static final Logger logger = LoggerFactory.getLogger(LocalFileStorageService.class);

    private final Path basePath;

    /** Indexer for the {@code storage.storage} table - populated so the
     *  Files panel / Storage Explorer surfaces every {@code upload()}
     *  call in CE monolith deployments (where this class is active).
     *  Without this every local upload was invisible to the UI. */
    @Autowired(required = false)
    private StorageService storageIndexService;

    public LocalFileStorageService(@Value("${storage.local.base-path:./data/files}") String basePath) {
        this.basePath = Path.of(basePath);
        try {
            Files.createDirectories(this.basePath);
            logger.info("LocalFileStorageService initialized at: {}", this.basePath.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create local storage directory: " + basePath, e);
        }
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
        String key = buildKey(tenantId, workflowId, runId, stepAlias, fileName);
        Path filePath = basePath.resolve(key);

        try {
            Files.createDirectories(filePath.getParent());
            Files.copy(content, filePath, StandardCopyOption.REPLACE_EXISTING);
            long actualSize = Files.size(filePath);
            logger.debug("Stored file: key={}, size={} bytes", key, actualSize);
            indexUpload(tenantId, workflowId, runId, stepAlias, key, fileName, mimeType, actualSize,
                    epoch, spawn, itemIndex, sourceType);
            return FileRef.of(key, fileName, mimeType, actualSize);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write file: " + key, e);
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
        String key = buildKey(tenantId, workflowId, runId, stepAlias, fileName);
        Path filePath = basePath.resolve(key);

        try {
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, content);
            logger.debug("Stored file: key={}, size={} bytes", key, content.length);
            indexUpload(tenantId, workflowId, runId, stepAlias, key, fileName, mimeType, content.length,
                    epoch, spawn, itemIndex, sourceType);
            return FileRef.of(key, fileName, mimeType, content.length);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write file: " + key, e);
        }
    }

    /**
     * Mirror of {@code S3FileStorageService.indexWorkflowUpload}. Without this
     * call CE monolith deployments (storage.type=local) never populate
     * {@code storage.storage}, breaking the Files panel entirely. Failure is
     * logged + swallowed - the upload itself already succeeded.
     */
    private void indexUpload(String tenantId, String workflowId, String runId, String stepAlias,
                              String key, String fileName, String mimeType, long size,
                              int epoch, int spawn, Integer itemIndex, String sourceType) {
        if (storageIndexService == null) return;
        try {
            storageIndexService.saveS3FileIndex(
                    tenantId, workflowId, runId, stepAlias,
                    key, fileName, mimeType, size, epoch, spawn, itemIndex, sourceType);
        } catch (Exception e) {
            logger.warn("Failed to index local upload in storage.storage: tenant={}, key={}, error={}",
                    tenantId, key, e.getMessage());
        }
    }

    @Override
    public String generateDownloadUrl(String key, Duration duration) {
        // In CE mode, files are served directly by the application via FileDownloadController.
        // The token parameter provides anti-hotlink protection.
        return "/api/files/download?key=" + key;
    }

    @Override
    public Optional<byte[]> download(String key) {
        Path filePath = basePath.resolve(key);
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(filePath));
        } catch (IOException e) {
            logger.error("Failed to read file: {}", key, e);
            return Optional.empty();
        }
    }

    @Override
    public boolean delete(String key) {
        Path filePath = basePath.resolve(key);
        try {
            boolean deleted = Files.deleteIfExists(filePath);
            if (deleted) {
                logger.debug("Deleted file: {}", key);
            }
            return deleted;
        } catch (IOException e) {
            logger.error("Failed to delete file: {}", key, e);
            return false;
        }
    }

    @Override
    public int deleteRunFiles(String tenantId, String workflowId, String runId) {
        Path runDir = basePath.resolve(tenantId).resolve(workflowId).resolve(runId);
        if (!Files.exists(runDir)) {
            return 0;
        }

        int[] count = {0};
        try (Stream<Path> walk = Files.walk(runDir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                        if (!Files.isDirectory(path)) count[0]++;
                    } catch (IOException e) {
                        logger.warn("Failed to delete: {}", path, e);
                    }
                });
        } catch (IOException e) {
            logger.error("Failed to walk directory: {}", runDir, e);
        }

        logger.info("Deleted {} files for run: {}/{}/{}", count[0], tenantId, workflowId, runId);
        return count[0];
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(basePath.resolve(key));
    }

    private String buildKey(String tenantId, String workflowId, String runId, String stepAlias, String fileName) {
        String uniquePrefix = UUID.randomUUID().toString().substring(0, 8);
        return String.format("%s/%s/%s/%s/%s_%s",
            tenantId, workflowId, runId, stepAlias, uniquePrefix,
            fileName != null ? fileName : "unnamed");
    }
}
