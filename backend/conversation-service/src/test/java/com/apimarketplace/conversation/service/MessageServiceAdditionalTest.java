package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.dto.AttachmentRef;
import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.entity.Message;
import com.apimarketplace.conversation.entity.MessageAttachment;
import com.apimarketplace.conversation.exception.ConversationInactiveException;
import com.apimarketplace.conversation.exception.InvalidMessageException;
import com.apimarketplace.conversation.mapper.MessageMapper;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageAttachmentRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import com.apimarketplace.common.event.EventBus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MessageService Additional Tests")
class MessageServiceAdditionalTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private MessageAttachmentRepository messageAttachmentRepository;

    @Mock
    private EventBus eventBus;

    @Mock
    private StorageBreakdownService storageBreakdownService;

    private final MessageMapper messageMapper = new MessageMapper();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MessageService messageService;

    @BeforeEach
    void setUp() {
        messageService = new MessageService(
                conversationRepository, messageRepository, messageAttachmentRepository, messageMapper, eventBus, objectMapper, storageBreakdownService, null);
    }

    private Conversation buildActiveConversation() {
        Conversation conv = new Conversation("user-1", "Test", "model", "provider");
        conv.setId("conv-1");
        conv.setActive(true);
        return conv;
    }

    // ================================================================
    // addMessage() - additional edge cases
    // ================================================================

    @Nested
    @DisplayName("addMessage() - additional edge cases")
    class AddMessageEdgeCases {

        @Test
        @DisplayName("should add assistant message with tool calls")
        void shouldAddAssistantWithToolCalls() {
            Conversation conv = buildActiveConversation();
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));
            when(messageRepository.save(any(Message.class))).thenAnswer(inv -> {
                Message m = inv.getArgument(0);
                m.setId("msg-1");
                m.setConversation(conv);
                return m;
            });

            MessageDto dto = new MessageDto();
            dto.setRole("assistant");
            dto.setToolCalls("[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"my_tool\"}}]");
            dto.setModel("gpt-4o");
            dto.setTimestamp("now");

            MessageDto result = messageService.addMessage("conv-1", dto);

            assertThat(result.getId()).isEqualTo("msg-1");
            ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
            verify(messageRepository).save(captor.capture());
            assertThat(captor.getValue().getToolCalls()).contains("my_tool");
        }

        @Test
        @DisplayName("should add tool result message")
        void shouldAddToolResultMessage() {
            Conversation conv = buildActiveConversation();
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));
            when(messageRepository.save(any(Message.class))).thenAnswer(inv -> {
                Message m = inv.getArgument(0);
                m.setId("msg-1");
                m.setConversation(conv);
                return m;
            });

            MessageDto dto = new MessageDto();
            dto.setRole("tool");
            dto.setToolCallId("call_1");
            dto.setToolName("my_tool");
            dto.setContent("Tool output result");
            dto.setTimestamp("now");

            MessageDto result = messageService.addMessage("conv-1", dto);

            assertThat(result.getId()).isEqualTo("msg-1");
        }

        @Test
        @DisplayName("should reject assistant message without content and without tool calls")
        void shouldRejectAssistantWithoutContentOrToolCalls() {
            Conversation conv = buildActiveConversation();
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            MessageDto dto = new MessageDto();
            dto.setRole("assistant");
            dto.setModel("gpt-4o");
            dto.setTimestamp("now");
            // No content, no toolCalls

            assertThatThrownBy(() -> messageService.addMessage("conv-1", dto))
                    .isInstanceOf(InvalidMessageException.class)
                    .hasMessageContaining("content or tool_calls");
        }

        @Test
        @DisplayName("should reject user message without content")
        void shouldRejectUserWithoutContent() {
            Conversation conv = buildActiveConversation();
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            MessageDto dto = new MessageDto();
            dto.setRole("user");
            dto.setModel("gpt-4o");
            dto.setTimestamp("now");
            // No content

            assertThatThrownBy(() -> messageService.addMessage("conv-1", dto))
                    .isInstanceOf(InvalidMessageException.class)
                    .hasMessageContaining("Content is required");
        }

        @Test
        @DisplayName("should reject system message without content")
        void shouldRejectSystemWithoutContent() {
            Conversation conv = buildActiveConversation();
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            MessageDto dto = new MessageDto();
            dto.setRole("system");
            dto.setTimestamp("now");

            assertThatThrownBy(() -> messageService.addMessage("conv-1", dto))
                    .isInstanceOf(InvalidMessageException.class)
                    .hasMessageContaining("Content is required");
        }

        @Test
        @DisplayName("should reject tool message without tool_call_id")
        void shouldRejectToolWithoutToolCallId() {
            Conversation conv = buildActiveConversation();
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            MessageDto dto = new MessageDto();
            dto.setRole("tool");
            dto.setContent("result");
            dto.setTimestamp("now");
            // No toolCallId

            assertThatThrownBy(() -> messageService.addMessage("conv-1", dto))
                    .isInstanceOf(InvalidMessageException.class)
                    .hasMessageContaining("tool_call_id");
        }

        @Test
        @DisplayName("should reject tool message without content")
        void shouldRejectToolWithoutContent() {
            Conversation conv = buildActiveConversation();
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            MessageDto dto = new MessageDto();
            dto.setRole("tool");
            dto.setToolCallId("call_1");
            dto.setTimestamp("now");
            // No content

            assertThatThrownBy(() -> messageService.addMessage("conv-1", dto))
                    .isInstanceOf(InvalidMessageException.class)
                    .hasMessageContaining("content");
        }

        @Test
        @DisplayName("should throw InvalidMessageException for unknown role")
        void shouldThrowForUnknownRole() {
            Conversation conv = buildActiveConversation();
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            MessageDto dto = new MessageDto();
            dto.setRole("unknown_role");
            dto.setContent("hello");
            dto.setTimestamp("now");

            assertThatThrownBy(() -> messageService.addMessage("conv-1", dto))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should set timestamp when not provided")
        void shouldSetTimestampWhenNotProvided() {
            Conversation conv = buildActiveConversation();
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));
            when(messageRepository.save(any(Message.class))).thenAnswer(inv -> {
                Message m = inv.getArgument(0);
                m.setId("msg-1");
                m.setConversation(conv);
                return m;
            });

            MessageDto dto = new MessageDto();
            dto.setRole("user");
            dto.setContent("hello");
            // No timestamp set

            messageService.addMessage("conv-1", dto);

            ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
            verify(messageRepository).save(captor.capture());
            assertThat(captor.getValue().getTimestamp()).isNotNull();
        }
    }

    // ================================================================
    // updateMessageToolCalls()
    // ================================================================

    @Nested
    @DisplayName("updateMessageToolCalls()")
    class UpdateMessageToolCalls {

        @Test
        @DisplayName("should update tool calls and return updated message")
        void shouldUpdateToolCalls() {
            Conversation conv = buildActiveConversation();
            Message message = new Message(Message.MessageRole.ASSISTANT, null);
            message.setId("msg-1");
            message.setConversation(conv);
            message.setToolCalls("[{\"id\":\"call_1\"}]");

            when(messageRepository.findById("msg-1")).thenReturn(Optional.of(message));
            when(messageRepository.save(any(Message.class))).thenReturn(message);

            String updatedJson = "[{\"id\":\"call_1\",\"status\":\"completed\"}]";
            MessageDto result = messageService.updateMessageToolCalls("msg-1", updatedJson);

            assertThat(message.getToolCalls()).isEqualTo(updatedJson);
            verify(messageRepository).save(message);
        }

        @Test
        @DisplayName("should throw when message not found")
        void shouldThrowWhenNotFound() {
            when(messageRepository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> messageService.updateMessageToolCalls("missing", "[]"))
                    .isInstanceOf(InvalidMessageException.class)
                    .hasMessageContaining("Message not found");
        }
    }

    // ================================================================
    // getLastNMessagesChronological()
    // ================================================================

    @Nested
    @DisplayName("getLastNMessagesChronological()")
    class GetLastNMessages {

        @Test
        @DisplayName("should return messages in chronological order")
        void shouldReturnChronological() {
            Conversation conv = buildActiveConversation();
            Message m1 = new Message(Message.MessageRole.USER, "first");
            m1.setConversation(conv);
            Message m2 = new Message(Message.MessageRole.ASSISTANT, "second");
            m2.setConversation(conv);

            // DESC order from DB
            Page<Message> page = new PageImpl<>(List.of(m2, m1), PageRequest.of(0, 10), 2);
            when(messageRepository.findByConversationIdOrderByCreatedAtDesc(eq("conv-1"), any()))
                    .thenReturn(page);

            List<MessageDto> results = messageService.getLastNMessagesChronological("conv-1", 10);

            // Should be reversed to chronological (ASC)
            assertThat(results).hasSize(2);
            assertThat(results.get(0).getContent()).isEqualTo("first");
            assertThat(results.get(1).getContent()).isEqualTo("second");
        }
    }

    // ================================================================
    // saveAttachments()
    // ================================================================

    @Nested
    @DisplayName("saveAttachments()")
    class SaveAttachments {

        @Test
        @DisplayName("should save attachments for a message")
        void shouldSaveAttachments() {
            UUID storageId1 = UUID.randomUUID();
            UUID storageId2 = UUID.randomUUID();

            AttachmentRef ref1 = new AttachmentRef();
            ref1.setStorageId(storageId1.toString());
            ref1.setType("image");
            ref1.setFileName("photo.jpg");
            ref1.setMimeType("image/jpeg");

            AttachmentRef ref2 = new AttachmentRef();
            ref2.setStorageId(storageId2.toString());
            ref2.setType("document");
            ref2.setFileName("doc.pdf");
            ref2.setMimeType("application/pdf");

            messageService.saveAttachments("msg-1", List.of(ref1, ref2));

            verify(messageAttachmentRepository, times(2)).save(any(MessageAttachment.class));
        }

        @Test
        @DisplayName("should handle null attachments list")
        void shouldHandleNullAttachments() {
            messageService.saveAttachments("msg-1", null);

            verify(messageAttachmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("should handle empty attachments list")
        void shouldHandleEmptyAttachments() {
            messageService.saveAttachments("msg-1", List.of());

            verify(messageAttachmentRepository, never()).save(any());
        }
    }
}
