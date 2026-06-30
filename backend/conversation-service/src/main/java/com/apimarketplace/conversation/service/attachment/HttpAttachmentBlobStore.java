package com.apimarketplace.conversation.service.attachment;

import com.apimarketplace.storage.client.StorageClient;
import com.apimarketplace.storage.client.dto.FileRefDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Microservice {@link AttachmentBlobStore}: routes attachment bytes through
 * storage-service over HTTP (storage-client). Category {@code "chat"} namespaces
 * the s3 key under {@code <tenantId>/general/chat/...}; the org-aware client
 * variants forward {@code X-Organization-ID} so the row lands in the active
 * workspace. Disabled in the CE monolith (deployment.mode=monolith), where
 * {@code MonolithAttachmentBlobStore} handles the same calls in-process.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)
public class HttpAttachmentBlobStore implements AttachmentBlobStore {

    private static final String CATEGORY = "chat";

    private final StorageClient storageClient;

    @Autowired
    public HttpAttachmentBlobStore(
            @Value("${services.storage-url:http://localhost:8082}") String storageServiceUrl) {
        // Own the StorageClient instead of a shared @Bean: in the monolith an
        // orchestrator StorageClient bean already exists, and a second one would
        // collide. This bean is microservice-only anyway (see the condition above).
        this(new StorageClient(storageServiceUrl));
    }

    /** Test/explicit-wiring constructor. */
    HttpAttachmentBlobStore(StorageClient storageClient) {
        this.storageClient = storageClient;
    }

    @Override
    public BlobRef upload(String tenantId, String organizationId, String fileName, String mimeType, byte[] content) {
        FileRefDto dto = storageClient.genericUpload(tenantId, CATEGORY, fileName, mimeType, content, organizationId);
        if (dto == null || dto.id() == null) {
            log.error("Attachment S3 upload failed: fileName={}, tenant={}, org={}", fileName, tenantId, organizationId);
            return null;
        }
        return new BlobRef(dto.id(), dto.path(), dto.size());
    }

    @Override
    public Optional<byte[]> download(String ownerTenantId, String s3Key) {
        return Optional.ofNullable(storageClient.download(ownerTenantId, s3Key));
    }
}
