package com.apimarketplace.conversation.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AttachmentRef")
class AttachmentRefTest {

    @Nested
    @DisplayName("Constructors")
    class Constructors {

        @Test
        @DisplayName("should create with default constructor")
        void shouldCreateWithDefault() {
            AttachmentRef ref = new AttachmentRef();
            assertThat(ref.getStorageId()).isNull();
            assertThat(ref.getType()).isNull();
            assertThat(ref.getFileName()).isNull();
            assertThat(ref.getMimeType()).isNull();
        }

        @Test
        @DisplayName("should create with all-args constructor")
        void shouldCreateWithAllArgs() {
            AttachmentRef ref = new AttachmentRef("storage-1", "IMAGE", "photo.jpg", "image/jpeg");

            assertThat(ref.getStorageId()).isEqualTo("storage-1");
            assertThat(ref.getType()).isEqualTo("IMAGE");
            assertThat(ref.getFileName()).isEqualTo("photo.jpg");
            assertThat(ref.getMimeType()).isEqualTo("image/jpeg");
        }
    }

    @Nested
    @DisplayName("Getters and setters")
    class GettersSetters {

        @Test
        @DisplayName("should get and set all fields")
        void shouldGetAndSetAllFields() {
            AttachmentRef ref = new AttachmentRef();
            ref.setStorageId("storage-1");
            ref.setType("PDF");
            ref.setFileName("doc.pdf");
            ref.setMimeType("application/pdf");

            assertThat(ref.getStorageId()).isEqualTo("storage-1");
            assertThat(ref.getType()).isEqualTo("PDF");
            assertThat(ref.getFileName()).isEqualTo("doc.pdf");
            assertThat(ref.getMimeType()).isEqualTo("application/pdf");
        }
    }
}
