package com.apimarketplace.conversation.integration;

import com.apimarketplace.conversation.dto.AttachmentRef;
import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.entity.MessageAttachment;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageAttachmentRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.AttachmentService;
import com.apimarketplace.conversation.service.MessageService;
import com.apimarketplace.conversation.service.attachment.AttachmentBlobStore;
import com.apimarketplace.common.storage.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Integration tests for AttachmentService with real JPA and mocked StorageService.
 * Tests attachment upload validation, storage, and retrieval flow.
 */
@IntegrationTest
@Import(IntegrationTestConfig.class)
class AttachmentServiceIntegrationTest {

    @Autowired
    private AttachmentService attachmentService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MessageAttachmentRepository messageAttachmentRepository;

    @MockitoBean
    private StorageService storageService;

    @MockitoBean
    private AttachmentBlobStore attachmentBlobStore;

    @MockitoBean
    private RestTemplate restTemplate;

    private static final String USER_ID = "attachment-test-user";
    private static final String TENANT_ID = "tenant-att-001";

    @BeforeEach
    void setUp() {
        messageAttachmentRepository.deleteAll();
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
    }

    // ========================== Helper Methods ==========================

    private Conversation persistConversation(String userId) {
        Conversation conv = new Conversation(userId, "Attachment Chat", "gpt-4o", "openai");
        conv.setActive(true);
        conv.setUpdatedAt(LocalDateTime.now());
        conv.setOrganizationId(userId);  // V263 OrgScopedEntity
        return conversationRepository.saveAndFlush(conv);
    }

    private String addUserMessage(String conversationId) {
        MessageDto dto = new MessageDto("user", "Message with attachment");
        dto.setTimestamp(java.time.Instant.now().toString());
        MessageDto saved = messageService.addMessage(conversationId, dto);
        return saved.getId();
    }

    // ========================== Tests ==========================

    @Nested
    @DisplayName("uploadForChat - File validation")
    class UploadValidation {

        @Test
        @DisplayName("should reject null or empty file")
        void shouldRejectEmptyFile() {
            MockMultipartFile emptyFile = new MockMultipartFile(
                    "file", "", "text/plain", new byte[0]);

            assertThatThrownBy(() -> attachmentService.uploadForChat(emptyFile, TENANT_ID))
                    .isInstanceOf(AttachmentService.AttachmentException.class)
                    .hasMessageContaining("empty");
        }

        @Test
        @DisplayName("should reject file exceeding max size (50MB)")
        void shouldRejectOversizedFile() {
            // Create a file exceeding 50MB
            byte[] largeContent = new byte[51 * 1024 * 1024];
            MockMultipartFile largeFile = new MockMultipartFile(
                    "file", "large.txt", "text/plain", largeContent);

            assertThatThrownBy(() -> attachmentService.uploadForChat(largeFile, TENANT_ID))
                    .isInstanceOf(AttachmentService.AttachmentException.class)
                    .hasMessageContaining("maximum size");
        }

        @Test
        @DisplayName("should reject file with unsupported MIME type")
        void shouldRejectUnsupportedType() {
            MockMultipartFile exeFile = new MockMultipartFile(
                    "file", "malware.exe", "application/x-msdownload", "content".getBytes());

            assertThatThrownBy(() -> attachmentService.uploadForChat(exeFile, TENANT_ID))
                    .isInstanceOf(AttachmentService.AttachmentException.class)
                    .hasMessageContaining("not allowed");
        }

        @Test
        @DisplayName("should accept valid image file")
        void shouldAcceptValidImage() {
            UUID storageId = UUID.randomUUID();
            when(attachmentBlobStore.upload(anyString(), any(), anyString(), anyString(), any(byte[].class)))
                    .thenReturn(new AttachmentBlobStore.BlobRef(storageId.toString(), "tenant/general/chat/f", 1L));

            MockMultipartFile imageFile = new MockMultipartFile(
                    "file", "photo.png", "image/png", "fake-png-data".getBytes());

            var response = attachmentService.uploadForChat(imageFile, TENANT_ID);

            assertThat(response).isNotNull();
            assertThat(response.storageId()).isEqualTo(storageId.toString());
            assertThat(response.type()).isEqualTo("IMAGE");
            assertThat(response.fileName()).isEqualTo("photo.png");
            assertThat(response.mimeType()).isEqualTo("image/png");
        }

        @Test
        @DisplayName("should accept valid PDF file")
        void shouldAcceptValidPdf() {
            UUID storageId = UUID.randomUUID();
            when(attachmentBlobStore.upload(anyString(), any(), anyString(), anyString(), any(byte[].class)))
                    .thenReturn(new AttachmentBlobStore.BlobRef(storageId.toString(), "tenant/general/chat/f", 1L));

            MockMultipartFile pdfFile = new MockMultipartFile(
                    "file", "document.pdf", "application/pdf", "fake-pdf-data".getBytes());

            var response = attachmentService.uploadForChat(pdfFile, TENANT_ID);

            assertThat(response).isNotNull();
            assertThat(response.type()).isEqualTo("PDF");
        }

        @Test
        @DisplayName("should accept valid text file")
        void shouldAcceptValidTextFile() {
            UUID storageId = UUID.randomUUID();
            when(attachmentBlobStore.upload(anyString(), any(), anyString(), anyString(), any(byte[].class)))
                    .thenReturn(new AttachmentBlobStore.BlobRef(storageId.toString(), "tenant/general/chat/f", 1L));

            MockMultipartFile textFile = new MockMultipartFile(
                    "file", "code.py", "text/x-python", "print('hello')".getBytes());

            var response = attachmentService.uploadForChat(textFile, TENANT_ID);

            assertThat(response).isNotNull();
            assertThat(response.type()).isEqualTo("TEXT");
        }
    }

    @Nested
    @DisplayName("MessageAttachment persistence via MessageService")
    class AttachmentPersistence {

        @Test
        @DisplayName("should save and retrieve message attachments")
        void shouldSaveAndRetrieveAttachments() {
            Conversation conv = persistConversation(USER_ID);
            String messageId = addUserMessage(conv.getId());

            UUID storageId1 = UUID.randomUUID();
            UUID storageId2 = UUID.randomUUID();

            // Save attachments via MessageService
            List<AttachmentRef> refs = List.of(
                    createAttachmentRef(storageId1.toString(), "IMAGE", "photo.png", "image/png"),
                    createAttachmentRef(storageId2.toString(), "PDF", "doc.pdf", "application/pdf")
            );
            messageService.saveAttachments(messageId, refs);

            // Verify persisted via batch load
            List<MessageAttachment> attachments = messageAttachmentRepository
                    .findByMessageIdIn(List.of(messageId));
            assertThat(attachments).hasSize(2);
        }

        @Test
        @DisplayName("should handle empty attachment list gracefully")
        void shouldHandleEmptyAttachmentList() {
            Conversation conv = persistConversation(USER_ID);
            String messageId = addUserMessage(conv.getId());

            // Should not throw
            messageService.saveAttachments(messageId, List.of());
            messageService.saveAttachments(messageId, null);

            List<MessageAttachment> attachments = messageAttachmentRepository
                    .findByMessageIdIn(List.of(messageId));
            assertThat(attachments).isEmpty();
        }

        @Test
        @DisplayName("should batch load attachments for multiple messages")
        void shouldBatchLoadAttachments() {
            Conversation conv = persistConversation(USER_ID);
            String msgId1 = addUserMessage(conv.getId());
            String msgId2 = addUserMessage(conv.getId());

            // Add attachments to both messages
            messageService.saveAttachments(msgId1, List.of(
                    createAttachmentRef(UUID.randomUUID().toString(), "IMAGE", "a.png", "image/png")
            ));
            messageService.saveAttachments(msgId2, List.of(
                    createAttachmentRef(UUID.randomUUID().toString(), "PDF", "b.pdf", "application/pdf")
            ));

            // Batch load
            List<MessageAttachment> batch = messageAttachmentRepository
                    .findByMessageIdIn(List.of(msgId1, msgId2));
            assertThat(batch).hasSize(2);
        }

        @Test
        @DisplayName("should delete attachments for a message")
        void shouldDeleteAttachmentsForMessage() {
            Conversation conv = persistConversation(USER_ID);
            String messageId = addUserMessage(conv.getId());

            messageService.saveAttachments(messageId, List.of(
                    createAttachmentRef(UUID.randomUUID().toString(), "IMAGE", "photo.png", "image/png")
            ));

            // Verify exists
            assertThat(messageAttachmentRepository.findByMessageIdIn(List.of(messageId)))
                    .hasSize(1);

            // Delete all attachments for this message
            messageAttachmentRepository.deleteAll(
                    messageAttachmentRepository.findByMessageIdIn(List.of(messageId)));

            assertThat(messageAttachmentRepository.findByMessageIdIn(List.of(messageId)))
                    .isEmpty();
        }
    }

    // ========================== Helpers ==========================

    private AttachmentRef createAttachmentRef(String storageId, String type, String fileName, String mimeType) {
        AttachmentRef ref = new AttachmentRef();
        ref.setStorageId(storageId);
        ref.setType(type);
        ref.setFileName(fileName);
        ref.setMimeType(mimeType);
        return ref;
    }
}
