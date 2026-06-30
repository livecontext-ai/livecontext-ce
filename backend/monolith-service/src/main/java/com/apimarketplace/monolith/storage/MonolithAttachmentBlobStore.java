package com.apimarketplace.monolith.storage;

import com.apimarketplace.conversation.service.attachment.AttachmentBlobStore;
import com.apimarketplace.storage.domain.FileRef;
import com.apimarketplace.storage.service.file.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.Optional;

/**
 * In-process {@link AttachmentBlobStore} for the CE monolith. The storage internal
 * HTTP controller is not mounted in monolith mode, so attachment bytes go straight
 * to the storage-service file implementation in the same JVM (mirrors
 * {@link MonolithFileStorageServiceAdapter}). {@code organization_id} is still
 * stamped by the {@code OrgScopedEntityListener} from the in-flight request's
 * {@code X-Organization-ID}, so workspace scope is preserved.
 */
@Slf4j
@Service
@Primary
@ConditionalOnProperty(name = "deployment.mode", havingValue = "monolith")
public class MonolithAttachmentBlobStore implements AttachmentBlobStore {

    private static final String CATEGORY = "chat";

    private final FileStorageService storageFileStorageService;

    public MonolithAttachmentBlobStore(FileStorageService storageFileStorageService) {
        this.storageFileStorageService = storageFileStorageService;
    }

    @Override
    public BlobRef upload(String tenantId, String organizationId, String fileName, String mimeType, byte[] content) {
        FileRef ref = storageFileStorageService.uploadGeneric(
                tenantId, CATEGORY, fileName, mimeType, new ByteArrayInputStream(content), content.length);
        if (ref == null || ref.id() == null) {
            return null;
        }
        return new BlobRef(ref.id(), ref.path(), ref.size());
    }

    @Override
    public Optional<byte[]> download(String ownerTenantId, String s3Key) {
        // In-process read: the caller already authorized the row (org-scoped lookup)
        // before resolving its s3Key, so a direct key fetch is safe here.
        try {
            return storageFileStorageService.download(s3Key);
        } catch (RuntimeException e) {
            // Mirror HttpAttachmentBlobStore: a transient object-storage error yields
            // no bytes (the attachment is skipped / 404s) instead of aborting an entire
            // message load - one bad attachment must not sink the others.
            log.warn("In-process attachment download failed: s3Key={}, error={}", s3Key, e.getMessage());
            return Optional.empty();
        }
    }
}
