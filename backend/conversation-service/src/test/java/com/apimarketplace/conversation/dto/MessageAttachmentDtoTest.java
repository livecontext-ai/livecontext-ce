package com.apimarketplace.conversation.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MessageAttachmentDto")
class MessageAttachmentDtoTest {

    @Nested
    @DisplayName("Constructors")
    class Constructors {

        @Test
        @DisplayName("should create with default constructor")
        void shouldCreateWithDefault() {
            MessageAttachmentDto dto = new MessageAttachmentDto();
            assertThat(dto.getStorageId()).isNull();
            assertThat(dto.getType()).isNull();
            assertThat(dto.getFileName()).isNull();
            assertThat(dto.getMimeType()).isNull();
            assertThat(dto.getSizeBytes()).isNull();
        }

        @Test
        @DisplayName("should create with all-args constructor")
        void shouldCreateWithAllArgs() {
            MessageAttachmentDto dto = new MessageAttachmentDto(
                    "storage-1", "IMAGE", "photo.jpg", "image/jpeg", 1024);

            assertThat(dto.getStorageId()).isEqualTo("storage-1");
            assertThat(dto.getType()).isEqualTo("IMAGE");
            assertThat(dto.getFileName()).isEqualTo("photo.jpg");
            assertThat(dto.getMimeType()).isEqualTo("image/jpeg");
            assertThat(dto.getSizeBytes()).isEqualTo(1024);
        }
    }

    @Nested
    @DisplayName("Getters and setters")
    class GettersSetters {

        @Test
        @DisplayName("should get and set all fields")
        void shouldGetAndSetAllFields() {
            MessageAttachmentDto dto = new MessageAttachmentDto();
            dto.setStorageId("storage-2");
            dto.setType("PDF");
            dto.setFileName("doc.pdf");
            dto.setMimeType("application/pdf");
            dto.setSizeBytes(2048);

            assertThat(dto.getStorageId()).isEqualTo("storage-2");
            assertThat(dto.getType()).isEqualTo("PDF");
            assertThat(dto.getFileName()).isEqualTo("doc.pdf");
            assertThat(dto.getMimeType()).isEqualTo("application/pdf");
            assertThat(dto.getSizeBytes()).isEqualTo(2048);
        }
    }
}
