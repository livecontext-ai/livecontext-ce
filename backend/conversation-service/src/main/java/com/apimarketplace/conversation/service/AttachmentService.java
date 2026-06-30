package com.apimarketplace.conversation.service;

import com.apimarketplace.agent.domain.AttachmentType;
import com.apimarketplace.agent.domain.MessageAttachment;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.conversation.dto.AttachmentRef;
import com.apimarketplace.conversation.dto.AttachmentUploadResponse;
import com.apimarketplace.conversation.service.attachment.AttachmentBlobStore;
import com.apimarketplace.conversation.service.attachment.PdfTextExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

/**
 * Service for managing chat message attachments.
 * Handles file upload, validation, and storage via common-storage-service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    private final StorageService storageService;
    /** S3-backed blob store (HTTP in microservice mode, in-process in the CE monolith). */
    private final AttachmentBlobStore attachmentBlobStore;

    // File size limits
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024; // 50 MB

    // Allowed MIME types by category
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
        "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp"
    );

    private static final Set<String> ALLOWED_PDF_TYPES = Set.of(
        "application/pdf"
    );

    private static final Set<String> ALLOWED_TEXT_TYPES = Set.of(
        "text/plain", "text/markdown", "text/csv", "text/html",
        "application/json", "application/xml",
        "text/javascript", "text/css", "text/x-python", "text/x-java"
    );

    /**
     * Upload a file for chat attachment.
     *
     * @param file The file to upload
     * @param tenantId The tenant ID (user ID)
     * @return Upload response with storage ID
     * @throws AttachmentException if validation fails
     */
    public AttachmentUploadResponse uploadForChat(MultipartFile file, String tenantId) {
        return uploadForChat(file, tenantId, null);
    }

    public AttachmentUploadResponse uploadForChat(MultipartFile file, String tenantId, String organizationId) {
        log.info("Uploading attachment for tenant: {}, fileName: {}, size: {} bytes",
            tenantId, file.getOriginalFilename(), file.getSize());

        // Validate file
        validateFile(file);

        // Determine attachment type
        String mimeType = file.getContentType();
        AttachmentType type = determineType(mimeType);

        try {
            byte[] data = file.getBytes();

            // Save the bytes to object storage (S3). The blob store namespaces the
            // key under the owner tenant and the OrgScopedEntityListener stamps the
            // active workspace (organizationId) on the storage row.
            AttachmentBlobStore.BlobRef ref = attachmentBlobStore.upload(
                tenantId, organizationId, file.getOriginalFilename(), mimeType, data);
            if (ref == null) {
                throw new AttachmentException("Failed to store attachment in object storage");
            }

            log.info("Attachment uploaded successfully: storageId={}, s3Key={}, type={}, size={} bytes",
                ref.storageId(), ref.s3Key(), type, data.length);

            return AttachmentUploadResponse.of(
                ref.storageId(),
                type.name(),
                file.getOriginalFilename(),
                mimeType,
                file.getSize()
            );

        } catch (IOException e) {
            log.error("Failed to read file data: {}", e.getMessage());
            throw new AttachmentException("Failed to read file data", e);
        }
    }

    /**
     * Load attachments from storage and convert to MessageAttachment objects.
     *
     * @param refs List of attachment references
     * @param tenantId The tenant ID
     * @return List of MessageAttachment objects with loaded data
     */
    public List<MessageAttachment> loadAttachments(List<AttachmentRef> refs, String tenantId) {
        return loadAttachments(refs, tenantId, null);
    }

    public List<MessageAttachment> loadAttachments(List<AttachmentRef> refs, String tenantId, String organizationId) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }

        List<MessageAttachment> attachments = new ArrayList<>();

        for (AttachmentRef ref : refs) {
            try {
                UUID storageId = UUID.fromString(ref.getStorageId());
                Optional<byte[]> data = loadAttachmentBytes(storageId, tenantId, organizationId);

                if (data.isPresent()) {
                    byte[] bytes = data.get();
                    AttachmentType type = AttachmentType.valueOf(ref.getType());
                    String extractedText = null;

                    // Extract text for text files
                    if (type == AttachmentType.TEXT) {
                        extractedText = new String(bytes);
                    } else if (type == AttachmentType.PDF) {
                        // Extract the PDF text layer so the agent receives the document CONTENT,
                        // not just the file name. Without this, a PDF over the inline byte cap is
                        // degraded to a "contents not sent" placeholder on the direct-API path,
                        // and on the bridge path it is written to disk for the agent to Read (a
                        // slow multi-MB round-trip). Best-effort: a scanned / encrypted / corrupt
                        // PDF yields null and the downstream fallbacks apply unchanged.
                        extractedText = PdfTextExtractor.extract(bytes);
                    }

                    attachments.add(MessageAttachment.builder()
                        .type(type)
                        .mimeType(ref.getMimeType())
                        .data(bytes)
                        .fileName(ref.getFileName())
                        .extractedText(extractedText)
                        .build());

                    log.debug("Loaded attachment: storageId={}, type={}, size={} bytes",
                        storageId, type, bytes.length);
                } else {
                    log.warn("Attachment not found or invalid type: storageId={}", ref.getStorageId());
                }

            } catch (IllegalArgumentException e) {
                log.warn("Invalid attachment reference: storageId={}, error={}", ref.getStorageId(), e.getMessage());
            }
        }

        return attachments;
    }

    /**
     * Get attachment with metadata for download/display.
     *
     * @param storageId The storage ID
     * @param tenantId The tenant ID
     * @return Optional containing AttachmentData with bytes and mime type
     */
    public Optional<AttachmentData> getAttachmentWithMetadata(UUID storageId, String tenantId) {
        return getAttachmentWithMetadata(storageId, tenantId, null);
    }

    public Optional<AttachmentData> getAttachmentWithMetadata(UUID storageId, String tenantId, String organizationId) {
        var entityOpt = organizationId != null && !organizationId.isBlank()
            ? storageService.getEntityByIdForScope(storageId, tenantId, organizationId)
            : storageService.getEntityById(storageId, tenantId);
        if (entityOpt.isEmpty()) {
            return Optional.empty();
        }

        var entity = entityOpt.get();
        return readBytes(entity).map(bytes -> new AttachmentData(
            bytes,
            entity.getMimeType(),
            entity.getFileName()
        ));
    }

    /**
     * Data class for attachment with metadata.
     */
    public record AttachmentData(byte[] data, String mimeType, String fileName) {}

    // ========== Private methods ==========

    private Optional<byte[]> loadAttachmentBytes(UUID storageId, String tenantId, String organizationId) {
        Optional<StorageEntity> entityOpt = organizationId != null && !organizationId.isBlank()
                ? storageService.getEntityByIdForScope(storageId, tenantId, organizationId)
                : storageService.getEntityById(storageId, tenantId);
        return entityOpt.flatMap(this::readBytes);
    }

    /**
     * Read an attachment row's bytes: from S3 when the row is object-backed (has an
     * {@code s3_key}), else the legacy DB binary (rows uploaded before the S3
     * migration, kept readable during their lifetime). S3 reads use the KEY-OWNER
     * tenant (the row's {@code tenant_id}) - object keys are namespaced
     * {@code "<tenantId>/..."}. The org-scoped lookup that produced the row already
     * authorized access, so an org teammate can read a workspace attachment that
     * another member uploaded.
     */
    private Optional<byte[]> readBytes(StorageEntity entity) {
        String s3Key = entity.getS3Key();
        if (s3Key != null && !s3Key.isBlank()) {
            return attachmentBlobStore.download(entity.getTenantId(), s3Key);
        }
        return Optional.ofNullable(entity.getDataBinary());
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AttachmentException("File is empty or missing");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new AttachmentException(
                String.format("File exceeds maximum size of %d MB", MAX_FILE_SIZE / (1024 * 1024)));
        }

        String mimeType = file.getContentType();
        if (mimeType == null) {
            throw new AttachmentException("Could not determine file type");
        }

        if (!isAllowedType(mimeType)) {
            throw new AttachmentException("File type not allowed: " + mimeType);
        }
    }

    private boolean isAllowedType(String mimeType) {
        return ALLOWED_IMAGE_TYPES.contains(mimeType) ||
               ALLOWED_PDF_TYPES.contains(mimeType) ||
               ALLOWED_TEXT_TYPES.contains(mimeType);
    }

    private AttachmentType determineType(String mimeType) {
        if (ALLOWED_IMAGE_TYPES.contains(mimeType)) {
            return AttachmentType.IMAGE;
        }
        if (ALLOWED_PDF_TYPES.contains(mimeType)) {
            return AttachmentType.PDF;
        }
        if (ALLOWED_TEXT_TYPES.contains(mimeType)) {
            return AttachmentType.TEXT;
        }
        return AttachmentType.OTHER;
    }

    /**
     * Exception for attachment-related errors.
     */
    public static class AttachmentException extends RuntimeException {
        public AttachmentException(String message) {
            super(message);
        }

        public AttachmentException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
