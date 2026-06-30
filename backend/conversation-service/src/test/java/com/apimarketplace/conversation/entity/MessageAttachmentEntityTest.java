package com.apimarketplace.conversation.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MessageAttachment Entity")
class MessageAttachmentEntityTest {

    @Nested
    @DisplayName("No-args constructor")
    class NoArgsConstructorTests {

        @Test
        @DisplayName("should create with default values")
        void shouldCreateWithDefaults() {
            MessageAttachment attachment = new MessageAttachment();

            assertThat(attachment.getId()).isNull();
            assertThat(attachment.getMessageId()).isNull();
            assertThat(attachment.getStorageId()).isNull();
            assertThat(attachment.getAttachmentType()).isNull();
            assertThat(attachment.getFileName()).isNull();
            assertThat(attachment.getMimeType()).isNull();
            assertThat(attachment.getSizeBytes()).isNull();
            assertThat(attachment.getDisplayOrder()).isEqualTo(0);
            assertThat(attachment.getCreatedAt()).isNull();
        }
    }

    @Nested
    @DisplayName("Parameterized constructor")
    class ParameterizedConstructorTests {

        @Test
        @DisplayName("should create with all parameters")
        void shouldCreateWithAllParams() {
            UUID storageId = UUID.randomUUID();
            MessageAttachment attachment = new MessageAttachment(
                    "msg-1", storageId, "image",
                    "photo.jpg", "image/jpeg", 1024, 1
            );

            assertThat(attachment.getMessageId()).isEqualTo("msg-1");
            assertThat(attachment.getStorageId()).isEqualTo(storageId);
            assertThat(attachment.getAttachmentType()).isEqualTo("image");
            assertThat(attachment.getFileName()).isEqualTo("photo.jpg");
            assertThat(attachment.getMimeType()).isEqualTo("image/jpeg");
            assertThat(attachment.getSizeBytes()).isEqualTo(1024);
            assertThat(attachment.getDisplayOrder()).isEqualTo(1);
        }

        @Test
        @DisplayName("should create PDF attachment")
        void shouldCreatePdfAttachment() {
            UUID storageId = UUID.randomUUID();
            MessageAttachment attachment = new MessageAttachment(
                    "msg-2", storageId, "document",
                    "report.pdf", "application/pdf", 52428, 0
            );

            assertThat(attachment.getAttachmentType()).isEqualTo("document");
            assertThat(attachment.getMimeType()).isEqualTo("application/pdf");
        }
    }

    @Nested
    @DisplayName("Getters and Setters")
    class GettersSettersTests {

        @Test
        @DisplayName("should get and set id")
        void shouldGetAndSetId() {
            MessageAttachment attachment = new MessageAttachment();
            UUID id = UUID.randomUUID();
            attachment.setId(id);
            assertThat(attachment.getId()).isEqualTo(id);
        }

        @Test
        @DisplayName("should get and set messageId")
        void shouldGetAndSetMessageId() {
            MessageAttachment attachment = new MessageAttachment();
            attachment.setMessageId("msg-123");
            assertThat(attachment.getMessageId()).isEqualTo("msg-123");
        }

        @Test
        @DisplayName("should get and set storageId")
        void shouldGetAndSetStorageId() {
            MessageAttachment attachment = new MessageAttachment();
            UUID storageId = UUID.randomUUID();
            attachment.setStorageId(storageId);
            assertThat(attachment.getStorageId()).isEqualTo(storageId);
        }

        @Test
        @DisplayName("should get and set attachmentType")
        void shouldGetAndSetAttachmentType() {
            MessageAttachment attachment = new MessageAttachment();
            attachment.setAttachmentType("text");
            assertThat(attachment.getAttachmentType()).isEqualTo("text");
        }

        @Test
        @DisplayName("should get and set fileName")
        void shouldGetAndSetFileName() {
            MessageAttachment attachment = new MessageAttachment();
            attachment.setFileName("document.txt");
            assertThat(attachment.getFileName()).isEqualTo("document.txt");
        }

        @Test
        @DisplayName("should get and set mimeType")
        void shouldGetAndSetMimeType() {
            MessageAttachment attachment = new MessageAttachment();
            attachment.setMimeType("text/plain");
            assertThat(attachment.getMimeType()).isEqualTo("text/plain");
        }

        @Test
        @DisplayName("should get and set sizeBytes")
        void shouldGetAndSetSizeBytes() {
            MessageAttachment attachment = new MessageAttachment();
            attachment.setSizeBytes(2048);
            assertThat(attachment.getSizeBytes()).isEqualTo(2048);
        }

        @Test
        @DisplayName("should get and set displayOrder")
        void shouldGetAndSetDisplayOrder() {
            MessageAttachment attachment = new MessageAttachment();
            attachment.setDisplayOrder(3);
            assertThat(attachment.getDisplayOrder()).isEqualTo(3);
        }

        @Test
        @DisplayName("should get and set createdAt")
        void shouldGetAndSetCreatedAt() {
            MessageAttachment attachment = new MessageAttachment();
            LocalDateTime now = LocalDateTime.now();
            attachment.setCreatedAt(now);
            assertThat(attachment.getCreatedAt()).isEqualTo(now);
        }
    }
}
