package com.apimarketplace.conversation.controller.v3;

import com.apimarketplace.conversation.dto.AttachmentUploadResponse;
import com.apimarketplace.conversation.service.AttachmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("AttachmentController")
@ExtendWith(MockitoExtension.class)
class AttachmentControllerTest {

    @Mock
    private AttachmentService attachmentService;

    @Mock
    private MultipartFile multipartFile;

    @InjectMocks
    private AttachmentController attachmentController;

    @Nested
    @DisplayName("uploadAttachment")
    class UploadAttachmentTests {

        @Test
        @DisplayName("should upload successfully")
        void shouldUploadSuccessfully() {
            when(multipartFile.getOriginalFilename()).thenReturn("test.jpg");
            when(multipartFile.getSize()).thenReturn(1024L);

            AttachmentUploadResponse uploadResponse = AttachmentUploadResponse.of(
                    UUID.randomUUID().toString(), "image", "test.jpg", "image/jpeg", 1024L
            );
            when(attachmentService.uploadForChat(multipartFile, "user-1", "org-1"))
                    .thenReturn(uploadResponse);

            ResponseEntity<?> response = attachmentController.uploadAttachment(multipartFile, "user-1", "org-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(uploadResponse);
        }

        @Test
        @DisplayName("should return bad request for AttachmentException")
        void shouldReturnBadRequestForAttachmentException() {
            when(multipartFile.getOriginalFilename()).thenReturn("test.exe");
            when(multipartFile.getSize()).thenReturn(1024L);

            when(attachmentService.uploadForChat(multipartFile, "user-1", "org-1"))
                    .thenThrow(new AttachmentService.AttachmentException("Unsupported file type"));

            ResponseEntity<?> response = attachmentController.uploadAttachment(multipartFile, "user-1", "org-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) response.getBody();
            assertThat(body).containsEntry("error", "Unsupported file type");
            assertThat(body).containsEntry("status", "error");
        }

        @Test
        @DisplayName("should return 500 for unexpected error")
        void shouldReturn500ForUnexpectedError() {
            when(multipartFile.getOriginalFilename()).thenReturn("test.jpg");
            when(multipartFile.getSize()).thenReturn(1024L);

            when(attachmentService.uploadForChat(multipartFile, "user-1", "org-1"))
                    .thenThrow(new RuntimeException("Unexpected"));

            ResponseEntity<?> response = attachmentController.uploadAttachment(multipartFile, "user-1", "org-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) response.getBody();
            assertThat(body).containsEntry("error", "Failed to upload attachment");
        }
    }

    @Nested
    @DisplayName("getAttachment")
    class GetAttachmentTests {

        @Test
        @DisplayName("should return attachment data")
        void shouldReturnAttachmentData() {
            UUID storageId = UUID.randomUUID();
            AttachmentService.AttachmentData data = new AttachmentService.AttachmentData(
                    new byte[]{1, 2, 3}, "image/jpeg", "test.jpg"
            );
            when(attachmentService.getAttachmentWithMetadata(storageId, "user-1", "org-1"))
                    .thenReturn(Optional.of(data));

            ResponseEntity<?> response = attachmentController.getAttachment(storageId.toString(), "user-1", "org-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst("Content-Type")).isEqualTo("image/jpeg");
            assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("private, max-age=3600");
            assertThat(response.getBody()).isEqualTo(new byte[]{1, 2, 3});
        }

        @Test
        @DisplayName("should return 404 when not found")
        void shouldReturn404WhenNotFound() {
            UUID storageId = UUID.randomUUID();
            when(attachmentService.getAttachmentWithMetadata(storageId, "user-1", "org-1"))
                    .thenReturn(Optional.empty());

            ResponseEntity<?> response = attachmentController.getAttachment(storageId.toString(), "user-1", "org-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("should use default mime type when null")
        void shouldUseDefaultMimeType() {
            UUID storageId = UUID.randomUUID();
            AttachmentService.AttachmentData data = new AttachmentService.AttachmentData(
                    new byte[]{1}, null, "unknown.bin"
            );
            when(attachmentService.getAttachmentWithMetadata(storageId, "user-1", "org-1"))
                    .thenReturn(Optional.of(data));

            ResponseEntity<?> response = attachmentController.getAttachment(storageId.toString(), "user-1", "org-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst("Content-Type")).isEqualTo("application/octet-stream");
        }

        @Test
        @DisplayName("should return bad request for invalid UUID format")
        void shouldReturnBadRequestForInvalidUuid() {
            ResponseEntity<?> response = attachmentController.getAttachment("not-a-uuid", "user-1", "org-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) response.getBody();
            assertThat(body).containsEntry("error", "Invalid storage ID format");
        }
    }

    @Nested
    @DisplayName("deleteAttachment")
    class DeleteAttachmentTests {

        @Test
        @DisplayName("should return not implemented")
        void shouldReturnNotImplemented() {
            UUID storageId = UUID.randomUUID();

            ResponseEntity<?> response = attachmentController.deleteAttachment(storageId.toString(), "user-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) response.getBody();
            assertThat(body).containsEntry("error", "Attachment deletion not yet implemented");
        }
    }
}
