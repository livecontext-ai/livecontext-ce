package com.apimarketplace.conversation.service;

import com.apimarketplace.agent.domain.AttachmentType;
import com.apimarketplace.agent.domain.MessageAttachment;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.conversation.dto.AttachmentRef;
import com.apimarketplace.conversation.dto.AttachmentUploadResponse;
import com.apimarketplace.conversation.service.attachment.AttachmentBlobStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("AttachmentService")
@ExtendWith(MockitoExtension.class)
class AttachmentServiceTest {

    @Mock
    private StorageService storageService;

    @Mock
    private AttachmentBlobStore attachmentBlobStore;

    @InjectMocks
    private AttachmentService attachmentService;

    /** Stub the S3 upload to return a blob ref carrying the given storage row id. */
    private void stubUpload(UUID storageId) {
        when(attachmentBlobStore.upload(anyString(), any(), anyString(), anyString(), any(byte[].class)))
                .thenReturn(new AttachmentBlobStore.BlobRef(storageId.toString(), "user-1/general/chat/f", 1L));
    }

    @Nested
    @DisplayName("uploadForChat")
    class UploadForChat {

        @Test
        @DisplayName("should upload image file to S3 and return its storage id")
        void shouldUploadImageFile() throws IOException {
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(1024L);
            when(file.getContentType()).thenReturn("image/png");
            when(file.getOriginalFilename()).thenReturn("test.png");
            when(file.getBytes()).thenReturn(new byte[1024]);

            UUID storageId = UUID.randomUUID();
            stubUpload(storageId);

            AttachmentUploadResponse response = attachmentService.uploadForChat(file, "user-1");

            assertThat(response.storageId()).isEqualTo(storageId.toString());
            assertThat(response.type()).isEqualTo("IMAGE");
            assertThat(response.fileName()).isEqualTo("test.png");
            assertThat(response.mimeType()).isEqualTo("image/png");
            assertThat(response.sizeBytes()).isEqualTo(1024L);
        }

        @Test
        @DisplayName("should forward the active workspace (organizationId) to the blob store")
        void shouldForwardOrganizationToBlobStore() throws IOException {
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(10L);
            when(file.getContentType()).thenReturn("image/png");
            when(file.getOriginalFilename()).thenReturn("ws.png");
            when(file.getBytes()).thenReturn(new byte[10]);

            UUID storageId = UUID.randomUUID();
            when(attachmentBlobStore.upload(eq("user-1"), eq("org-42"), eq("ws.png"), eq("image/png"), any(byte[].class)))
                    .thenReturn(new AttachmentBlobStore.BlobRef(storageId.toString(), "user-1/general/chat/ws.png", 10L));

            AttachmentUploadResponse response = attachmentService.uploadForChat(file, "user-1", "org-42");

            assertThat(response.storageId()).isEqualTo(storageId.toString());
            verify(attachmentBlobStore).upload(eq("user-1"), eq("org-42"), eq("ws.png"), eq("image/png"), any(byte[].class));
        }

        @Test
        @DisplayName("should throw when the S3 upload fails (blob store returns null)")
        void shouldThrowWhenUploadFails() throws IOException {
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(1024L);
            when(file.getContentType()).thenReturn("image/png");
            when(file.getOriginalFilename()).thenReturn("test.png");
            when(file.getBytes()).thenReturn(new byte[1024]);
            when(attachmentBlobStore.upload(anyString(), any(), anyString(), anyString(), any(byte[].class)))
                    .thenReturn(null);

            assertThatThrownBy(() -> attachmentService.uploadForChat(file, "user-1"))
                    .isInstanceOf(AttachmentService.AttachmentException.class)
                    .hasMessageContaining("object storage");
        }

        @Test
        @DisplayName("should upload PDF file successfully")
        void shouldUploadPdfFile() throws IOException {
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(2048L);
            when(file.getContentType()).thenReturn("application/pdf");
            when(file.getOriginalFilename()).thenReturn("doc.pdf");
            when(file.getBytes()).thenReturn(new byte[2048]);

            stubUpload(UUID.randomUUID());

            AttachmentUploadResponse response = attachmentService.uploadForChat(file, "user-1");

            assertThat(response.type()).isEqualTo("PDF");
        }

        @Test
        @DisplayName("should upload text file successfully")
        void shouldUploadTextFile() throws IOException {
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(512L);
            when(file.getContentType()).thenReturn("text/plain");
            when(file.getOriginalFilename()).thenReturn("readme.txt");
            when(file.getBytes()).thenReturn(new byte[512]);

            stubUpload(UUID.randomUUID());

            AttachmentUploadResponse response = attachmentService.uploadForChat(file, "user-1");

            assertThat(response.type()).isEqualTo("TEXT");
        }

        @Test
        @DisplayName("should throw on null file")
        void shouldThrowOnNullFile() {
            assertThatThrownBy(() -> attachmentService.uploadForChat(null, "user-1"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should throw on empty file")
        void shouldThrowOnEmptyFile() {
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(true);

            assertThatThrownBy(() -> attachmentService.uploadForChat(file, "user-1"))
                    .isInstanceOf(AttachmentService.AttachmentException.class)
                    .hasMessageContaining("empty or missing");
        }

        @Test
        @DisplayName("should throw on file exceeding max size")
        void shouldThrowOnOversizedFile() {
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(60L * 1024 * 1024); // 60 MB

            assertThatThrownBy(() -> attachmentService.uploadForChat(file, "user-1"))
                    .isInstanceOf(AttachmentService.AttachmentException.class)
                    .hasMessageContaining("maximum size");
        }

        @Test
        @DisplayName("should throw on null content type")
        void shouldThrowOnNullContentType() {
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(1024L);
            when(file.getContentType()).thenReturn(null);

            assertThatThrownBy(() -> attachmentService.uploadForChat(file, "user-1"))
                    .isInstanceOf(AttachmentService.AttachmentException.class)
                    .hasMessageContaining("Could not determine file type");
        }

        @Test
        @DisplayName("should throw on disallowed content type")
        void shouldThrowOnDisallowedContentType() {
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(1024L);
            when(file.getContentType()).thenReturn("application/zip");

            assertThatThrownBy(() -> attachmentService.uploadForChat(file, "user-1"))
                    .isInstanceOf(AttachmentService.AttachmentException.class)
                    .hasMessageContaining("not allowed");
        }

        @Test
        @DisplayName("should throw on IOException reading bytes")
        void shouldThrowOnIOException() throws IOException {
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(1024L);
            when(file.getContentType()).thenReturn("image/png");
            when(file.getBytes()).thenThrow(new IOException("Read error"));

            assertThatThrownBy(() -> attachmentService.uploadForChat(file, "user-1"))
                    .isInstanceOf(AttachmentService.AttachmentException.class)
                    .hasMessageContaining("Failed to read file data");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp",
                "application/pdf",
                "text/plain", "text/markdown", "text/csv", "text/html",
                "application/json", "application/xml",
                "text/javascript", "text/css", "text/x-python", "text/x-java"
        })
        @DisplayName("should accept all allowed MIME types")
        void shouldAcceptAllAllowedTypes(String mimeType) throws IOException {
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(1024L);
            when(file.getContentType()).thenReturn(mimeType);
            when(file.getOriginalFilename()).thenReturn("test.file");
            when(file.getBytes()).thenReturn(new byte[1024]);

            stubUpload(UUID.randomUUID());

            AttachmentUploadResponse response = attachmentService.uploadForChat(file, "user-1");
            assertThat(response).isNotNull();
        }
    }

    @Nested
    @DisplayName("loadAttachments")
    class LoadAttachments {

        @Test
        @DisplayName("should return empty list for null refs")
        void shouldReturnEmptyForNull() {
            List<MessageAttachment> result = attachmentService.loadAttachments(null, "user-1");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty list for empty refs")
        void shouldReturnEmptyForEmptyList() {
            List<MessageAttachment> result = attachmentService.loadAttachments(List.of(), "user-1");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should load an S3-backed image attachment by downloading from object storage")
        void shouldLoadS3BackedImage() {
            UUID storageId = UUID.randomUUID();
            AttachmentRef ref = new AttachmentRef(storageId.toString(), "IMAGE", "photo.jpg", "image/jpeg");

            byte[] data = new byte[]{1, 2, 3};
            StorageEntity entity = mock(StorageEntity.class);
            when(entity.getS3Key()).thenReturn("user-1/general/chat/photo.jpg");
            when(entity.getTenantId()).thenReturn("user-1");
            when(storageService.getEntityById(storageId, "user-1")).thenReturn(Optional.of(entity));
            when(attachmentBlobStore.download("user-1", "user-1/general/chat/photo.jpg")).thenReturn(Optional.of(data));

            List<MessageAttachment> result = attachmentService.loadAttachments(List.of(ref), "user-1");

            assertThat(result).hasSize(1);
            MessageAttachment attachment = result.get(0);
            assertThat(attachment.type()).isEqualTo(AttachmentType.IMAGE);
            assertThat(attachment.mimeType()).isEqualTo("image/jpeg");
            assertThat(attachment.data()).isEqualTo(data);
            assertThat(attachment.fileName()).isEqualTo("photo.jpg");
            assertThat(attachment.extractedText()).isNull();
        }

        @Test
        @DisplayName("should load a legacy DB-binary attachment (no s3_key) without hitting object storage")
        void shouldLoadLegacyBinary() {
            UUID storageId = UUID.randomUUID();
            AttachmentRef ref = new AttachmentRef(storageId.toString(), "IMAGE", "old.png", "image/png");

            byte[] data = new byte[]{9, 8, 7};
            StorageEntity entity = mock(StorageEntity.class);
            when(entity.getS3Key()).thenReturn(null);
            when(entity.getDataBinary()).thenReturn(data);
            when(storageService.getEntityById(storageId, "user-1")).thenReturn(Optional.of(entity));

            List<MessageAttachment> result = attachmentService.loadAttachments(List.of(ref), "user-1");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).data()).isEqualTo(data);
            verify(attachmentBlobStore, never()).download(anyString(), anyString());
        }

        @Test
        @DisplayName("should extract text for TEXT type attachments")
        void shouldExtractTextForTextType() {
            UUID storageId = UUID.randomUUID();
            AttachmentRef ref = new AttachmentRef(storageId.toString(), "TEXT", "notes.txt", "text/plain");

            byte[] data = "Hello World".getBytes();
            StorageEntity entity = mock(StorageEntity.class);
            when(entity.getS3Key()).thenReturn("user-1/general/chat/notes.txt");
            when(entity.getTenantId()).thenReturn("user-1");
            when(storageService.getEntityById(storageId, "user-1")).thenReturn(Optional.of(entity));
            when(attachmentBlobStore.download("user-1", "user-1/general/chat/notes.txt")).thenReturn(Optional.of(data));

            List<MessageAttachment> result = attachmentService.loadAttachments(List.of(ref), "user-1");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).extractedText()).isEqualTo("Hello World");
        }

        @Test
        @DisplayName("should extract the PDF text layer into extractedText so the agent sees the content")
        void shouldExtractTextForPdfType() throws Exception {
            UUID storageId = UUID.randomUUID();
            AttachmentRef ref = new AttachmentRef(storageId.toString(), "PDF", "memoire.pdf", "application/pdf");

            byte[] pdfBytes = com.apimarketplace.conversation.service.attachment.TestPdfFactory
                    .singlePagePdf("Dissertation body to screen for plagiarism.");
            StorageEntity entity = mock(StorageEntity.class);
            when(entity.getS3Key()).thenReturn("user-1/general/chat/memoire.pdf");
            when(entity.getTenantId()).thenReturn("user-1");
            when(storageService.getEntityById(storageId, "user-1")).thenReturn(Optional.of(entity));
            when(attachmentBlobStore.download("user-1", "user-1/general/chat/memoire.pdf"))
                    .thenReturn(Optional.of(pdfBytes));

            List<MessageAttachment> result = attachmentService.loadAttachments(List.of(ref), "user-1");

            assertThat(result).hasSize(1);
            MessageAttachment attachment = result.get(0);
            assertThat(attachment.type()).isEqualTo(AttachmentType.PDF);
            // The raw bytes are still carried (native PDF support / disk-Read fallback), AND the
            // text layer is now populated so the model receives the content even when the binary
            // is over the inline byte cap.
            assertThat(attachment.data()).isEqualTo(pdfBytes);
            assertThat(attachment.extractedText()).contains("Dissertation body to screen for plagiarism.");
        }

        @Test
        @DisplayName("should leave extractedText null for a PDF with no text layer (scanned), keeping the disk-Read fallback")
        void shouldLeavePdfExtractedTextNullWhenNoTextLayer() throws Exception {
            UUID storageId = UUID.randomUUID();
            AttachmentRef ref = new AttachmentRef(storageId.toString(), "PDF", "scan.pdf", "application/pdf");

            byte[] pdfBytes = com.apimarketplace.conversation.service.attachment.TestPdfFactory.emptyPagePdf();
            StorageEntity entity = mock(StorageEntity.class);
            when(entity.getS3Key()).thenReturn("user-1/general/chat/scan.pdf");
            when(entity.getTenantId()).thenReturn("user-1");
            when(storageService.getEntityById(storageId, "user-1")).thenReturn(Optional.of(entity));
            when(attachmentBlobStore.download("user-1", "user-1/general/chat/scan.pdf"))
                    .thenReturn(Optional.of(pdfBytes));

            List<MessageAttachment> result = attachmentService.loadAttachments(List.of(ref), "user-1");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).data()).isEqualTo(pdfBytes);
            assertThat(result.get(0).extractedText()).isNull();
        }

        @Test
        @DisplayName("should skip attachment when the storage row is not found")
        void shouldSkipWhenNotFound() {
            UUID storageId = UUID.randomUUID();
            AttachmentRef ref = new AttachmentRef(storageId.toString(), "IMAGE", "photo.jpg", "image/jpeg");

            when(storageService.getEntityById(storageId, "user-1")).thenReturn(Optional.empty());

            List<MessageAttachment> result = attachmentService.loadAttachments(List.of(ref), "user-1");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should skip attachment with invalid storage ID")
        void shouldSkipInvalidStorageId() {
            AttachmentRef ref = new AttachmentRef("not-a-uuid", "IMAGE", "photo.jpg", "image/jpeg");

            List<MessageAttachment> result = attachmentService.loadAttachments(List.of(ref), "user-1");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getAttachmentWithMetadata")
    class GetAttachmentWithMetadata {

        @Test
        @DisplayName("should return S3-backed bytes downloaded under the key-owner tenant")
        void shouldReturnS3BackedBytes() {
            UUID storageId = UUID.randomUUID();
            byte[] data = new byte[]{4, 5, 6};
            StorageEntity entity = mock(StorageEntity.class);
            when(entity.getS3Key()).thenReturn("owner-9/general/chat/p.png");
            when(entity.getTenantId()).thenReturn("owner-9");
            when(entity.getMimeType()).thenReturn("image/png");
            when(entity.getFileName()).thenReturn("p.png");
            // Org-scoped lookup authorizes the row; the download then uses the OWNER tenant.
            when(storageService.getEntityByIdForScope(storageId, "viewer-1", "org-7")).thenReturn(Optional.of(entity));
            when(attachmentBlobStore.download("owner-9", "owner-9/general/chat/p.png")).thenReturn(Optional.of(data));

            Optional<AttachmentService.AttachmentData> result =
                    attachmentService.getAttachmentWithMetadata(storageId, "viewer-1", "org-7");

            assertThat(result).isPresent();
            assertThat(result.get().data()).isEqualTo(data);
            assertThat(result.get().mimeType()).isEqualTo("image/png");
            assertThat(result.get().fileName()).isEqualTo("p.png");
        }

        @Test
        @DisplayName("should return legacy DB-binary bytes when the row has no s3_key")
        void shouldReturnLegacyBinary() {
            UUID storageId = UUID.randomUUID();
            byte[] data = new byte[]{1};
            StorageEntity entity = mock(StorageEntity.class);
            when(entity.getS3Key()).thenReturn(null);
            when(entity.getDataBinary()).thenReturn(data);
            when(entity.getMimeType()).thenReturn("text/plain");
            when(entity.getFileName()).thenReturn("legacy.txt");
            when(storageService.getEntityById(storageId, "user-1")).thenReturn(Optional.of(entity));

            Optional<AttachmentService.AttachmentData> result =
                    attachmentService.getAttachmentWithMetadata(storageId, "user-1");

            assertThat(result).isPresent();
            assertThat(result.get().data()).isEqualTo(data);
        }

        @Test
        @DisplayName("should be empty when the storage row is not found")
        void shouldBeEmptyWhenNotFound() {
            UUID storageId = UUID.randomUUID();
            when(storageService.getEntityById(storageId, "user-1")).thenReturn(Optional.empty());

            assertThat(attachmentService.getAttachmentWithMetadata(storageId, "user-1")).isEmpty();
        }

        @Test
        @DisplayName("should be empty when the S3 download yields no bytes")
        void shouldBeEmptyWhenS3BytesUnavailable() {
            UUID storageId = UUID.randomUUID();
            StorageEntity entity = mock(StorageEntity.class);
            when(entity.getS3Key()).thenReturn("user-1/general/chat/x.png");
            when(entity.getTenantId()).thenReturn("user-1");
            when(storageService.getEntityById(storageId, "user-1")).thenReturn(Optional.of(entity));
            when(attachmentBlobStore.download("user-1", "user-1/general/chat/x.png")).thenReturn(Optional.empty());

            assertThat(attachmentService.getAttachmentWithMetadata(storageId, "user-1")).isEmpty();
        }
    }

    @Nested
    @DisplayName("AttachmentException")
    class AttachmentExceptionTests {

        @Test
        @DisplayName("should create with message only")
        void shouldCreateWithMessage() {
            AttachmentService.AttachmentException ex = new AttachmentService.AttachmentException("test error");
            assertThat(ex.getMessage()).isEqualTo("test error");
        }

        @Test
        @DisplayName("should create with message and cause")
        void shouldCreateWithMessageAndCause() {
            IOException cause = new IOException("IO problem");
            AttachmentService.AttachmentException ex = new AttachmentService.AttachmentException("test error", cause);
            assertThat(ex.getMessage()).isEqualTo("test error");
            assertThat(ex.getCause()).isEqualTo(cause);
        }
    }

    @Nested
    @DisplayName("AttachmentData record")
    class AttachmentDataTests {

        @Test
        @DisplayName("should create AttachmentData with all fields")
        void shouldCreateAttachmentData() {
            byte[] data = new byte[]{1, 2, 3};
            AttachmentService.AttachmentData attachmentData = new AttachmentService.AttachmentData(
                    data, "image/png", "test.png");

            assertThat(attachmentData.data()).isEqualTo(data);
            assertThat(attachmentData.mimeType()).isEqualTo("image/png");
            assertThat(attachmentData.fileName()).isEqualTo("test.png");
        }
    }
}
