package com.apimarketplace.monolith.storage;

import com.apimarketplace.orchestrator.domain.file.FileRef;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

/**
 * In-process bridge from orchestrator file operations to the storage-service
 * file implementation when CE runs as a monolith.
 *
 * <p>Microservice mode uses {@code StorageClientAdapter} over HTTP. In monolith
 * mode the storage internal HTTP controller is not mounted, so this adapter
 * keeps the same orchestrator contract without routing back through localhost.
 */
@Service
@Primary
@ConditionalOnProperty(name = "deployment.mode", havingValue = "monolith")
public class MonolithFileStorageServiceAdapter implements com.apimarketplace.orchestrator.services.file.FileStorageService {

    private final com.apimarketplace.storage.service.file.FileStorageService storageFileStorageService;

    public MonolithFileStorageServiceAdapter(
            com.apimarketplace.storage.service.file.FileStorageService storageFileStorageService) {
        this.storageFileStorageService = storageFileStorageService;
    }

    @Override
    public FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                          String fileName, String mimeType, InputStream content, long size) {
        return toOrchestratorRef(storageFileStorageService.upload(
                tenantId, workflowId, runId, stepAlias, fileName, mimeType, content, size));
    }

    @Override
    public FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                          String fileName, String mimeType, byte[] content) {
        return toOrchestratorRef(storageFileStorageService.upload(
                tenantId, workflowId, runId, stepAlias, fileName, mimeType, content));
    }

    @Override
    public String generateDownloadUrl(String key, Duration duration) {
        return storageFileStorageService.generateDownloadUrl(key, duration);
    }

    @Override
    public Optional<byte[]> download(String key) {
        return storageFileStorageService.download(key);
    }

    @Override
    public boolean delete(String key) {
        return storageFileStorageService.delete(key);
    }

    @Override
    public int deleteRunFiles(String tenantId, String workflowId, String runId) {
        return storageFileStorageService.deleteRunFiles(tenantId, workflowId, runId);
    }

    @Override
    public boolean exists(String key) {
        return storageFileStorageService.exists(key);
    }

    private static FileRef toOrchestratorRef(com.apimarketplace.storage.domain.FileRef ref) {
        return FileRef.of(ref.path(), ref.name(), ref.mimeType(), ref.size());
    }
}
