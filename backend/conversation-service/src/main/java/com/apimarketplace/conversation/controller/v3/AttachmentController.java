package com.apimarketplace.conversation.controller.v3;

import com.apimarketplace.conversation.dto.AttachmentUploadResponse;
import com.apimarketplace.conversation.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

/**
 * Controller for managing chat message attachments.
 * Provides upload and download endpoints for file attachments.
 */
@Slf4j
@RestController
@RequestMapping("/api/v3/chat/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    /**
     * Upload a file attachment for chat messages.
     *
     * @param file The file to upload
     * @param userId The user ID from authentication header
     * @return Upload response with storage ID
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAttachment(
            @RequestParam("file") MultipartFile file,
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {

        log.info("Attachment upload request - user: {}, file: {}, size: {} bytes",
            userId, file.getOriginalFilename(), file.getSize());

        try {
            AttachmentUploadResponse response = attachmentService.uploadForChat(file, userId, organizationId);
            return ResponseEntity.ok(response);

        } catch (AttachmentService.AttachmentException e) {
            log.warn("Attachment upload failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage(),
                "status", "error"
            ));

        } catch (Exception e) {
            log.error("Unexpected error during attachment upload: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Failed to upload attachment",
                "status", "error"
            ));
        }
    }

    /**
     * Download/retrieve an attachment by storage ID.
     *
     * @param storageId The storage ID of the attachment
     * @param userId The user ID from authentication header
     * @return The file content with appropriate headers
     */
    @GetMapping("/{storageId}")
    public ResponseEntity<?> getAttachment(
            @PathVariable String storageId,
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {

        log.debug("Attachment download request - user: {}, storageId: {}", userId, storageId);

        try {
            UUID id = UUID.fromString(storageId);
            var attachmentData = attachmentService.getAttachmentWithMetadata(id, userId, organizationId);

            if (attachmentData.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            var data = attachmentData.get();
            String mimeType = data.mimeType() != null ? data.mimeType() : "application/octet-stream";

            // Return with proper content type and cache headers
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, mimeType)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(data.data());

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid storage ID format",
                "status", "error"
            ));
        }
    }

    /**
     * Delete an attachment by storage ID.
     *
     * @param storageId The storage ID of the attachment
     * @param userId The user ID from authentication header
     * @return Success/failure response
     */
    @DeleteMapping("/{storageId}")
    public ResponseEntity<?> deleteAttachment(
            @PathVariable String storageId,
            @RequestHeader("X-User-ID") String userId) {

        log.info("Attachment delete request - user: {}, storageId: {}", userId, storageId);

        // Note: Deletion would be implemented via StorageService.deleteById
        // For now, return not implemented
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of(
            "error", "Attachment deletion not yet implemented",
            "status", "error"
        ));
    }
}
