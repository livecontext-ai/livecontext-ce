package com.apimarketplace.conversation.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AttachmentUploadResponse")
class AttachmentUploadResponseTest {

    @Nested
    @DisplayName("Record fields")
    class RecordFields {

        @Test
        @DisplayName("should create with all fields")
        void shouldCreateWithAllFields() {
            AttachmentUploadResponse response = new AttachmentUploadResponse(
                    "storage-1", "IMAGE", "photo.jpg", "image/jpeg", 1024L);

            assertThat(response.storageId()).isEqualTo("storage-1");
            assertThat(response.type()).isEqualTo("IMAGE");
            assertThat(response.fileName()).isEqualTo("photo.jpg");
            assertThat(response.mimeType()).isEqualTo("image/jpeg");
            assertThat(response.sizeBytes()).isEqualTo(1024L);
        }
    }

    @Nested
    @DisplayName("Factory method")
    class FactoryMethod {

        @Test
        @DisplayName("should create using of factory method")
        void shouldCreateUsingOf() {
            AttachmentUploadResponse response = AttachmentUploadResponse.of(
                    "storage-1", "PDF", "doc.pdf", "application/pdf", 2048L);

            assertThat(response.storageId()).isEqualTo("storage-1");
            assertThat(response.type()).isEqualTo("PDF");
            assertThat(response.fileName()).isEqualTo("doc.pdf");
            assertThat(response.mimeType()).isEqualTo("application/pdf");
            assertThat(response.sizeBytes()).isEqualTo(2048L);
        }
    }
}
