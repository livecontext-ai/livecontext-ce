package com.apimarketplace.orchestrator.services.impl;

import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock implementation of FileStorageService for E2E testing.
 * Stores files in memory without requiring S3/MinIO infrastructure.
 */
@Service
@Primary
@ConditionalOnProperty(name = "orchestrator.mock.enabled", havingValue = "true")
public class MockFileStorageService implements FileStorageService {

    private static final Logger logger = LoggerFactory.getLogger(MockFileStorageService.class);

    private final Map<String, byte[]> storage = new ConcurrentHashMap<>();

    @Override
    public FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                          String fileName, String mimeType, InputStream content, long size) {
        try {
            byte[] bytes = content.readAllBytes();
            return upload(tenantId, workflowId, runId, stepAlias, fileName, mimeType, bytes);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read input stream", e);
        }
    }

    @Override
    public FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                          String fileName, String mimeType, byte[] content) {
        String key = buildKey(tenantId, workflowId, runId, stepAlias, fileName);
        storage.put(key, content);

        logger.info("MockFileStorageService: Stored file key={}, size={} bytes", key, content.length);
        return FileRef.of(key, fileName, mimeType, content.length);
    }

    @Override
    public String generateDownloadUrl(String key, Duration duration) {
        logger.info("MockFileStorageService: Generated mock download URL for key={}", key);
        return "mock://download/" + key;
    }

    @Override
    public Optional<byte[]> download(String key) {
        byte[] content = storage.get(key);
        if (content != null) {
            return Optional.of(content);
        }
        return Optional.empty();
    }

    @Override
    public boolean delete(String key) {
        boolean existed = storage.remove(key) != null;
        logger.info("MockFileStorageService: Delete key={}, existed={}", key, existed);
        return existed;
    }

    @Override
    public int deleteRunFiles(String tenantId, String workflowId, String runId) {
        String prefix = tenantId + "/" + workflowId + "/" + runId + "/";
        int count = 0;
        for (String key : storage.keySet()) {
            if (key.startsWith(prefix)) {
                storage.remove(key);
                count++;
            }
        }
        logger.info("MockFileStorageService: Deleted {} files for run prefix={}", count, prefix);
        return count;
    }

    @Override
    public boolean exists(String key) {
        return storage.containsKey(key);
    }

    private String buildKey(String tenantId, String workflowId, String runId, String stepAlias, String fileName) {
        String uniquePrefix = UUID.randomUUID().toString().substring(0, 8);
        return String.format("%s/%s/%s/%s/%s_%s",
            tenantId, workflowId, runId, stepAlias, uniquePrefix,
            fileName != null ? fileName : "unnamed");
    }
}
