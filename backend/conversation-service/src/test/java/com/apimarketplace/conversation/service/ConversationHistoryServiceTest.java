package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.dto.MessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ConversationHistoryService")
@ExtendWith(MockitoExtension.class)
class ConversationHistoryServiceTest {

    @Mock
    private ConversationCommandService conversationCommandService;

    @Mock
    private ConversationQueryService conversationQueryService;

    @Mock
    private MessageService messageService;

    private ConversationHistoryService service;

    @BeforeEach
    void setUp() {
        service = new ConversationHistoryService(
                conversationCommandService, conversationQueryService, messageService);
    }

    @Nested
    @DisplayName("createConversation")
    class CreateConversation {

        @Test
        @DisplayName("should create and return conversation ID")
        void shouldCreateAndReturnId() {
            ConversationDto created = new ConversationDto();
            created.setId("conv-123");
            when(conversationCommandService.createConversation(any(ConversationDto.class))).thenReturn(created);

            String id = service.createConversation("user-1", "Test Title", "gpt-4", "openai");
            assertThat(id).isEqualTo("conv-123");
        }

        @Test
        @DisplayName("should use default values when params are null")
        void shouldUseDefaultValues() {
            ConversationDto created = new ConversationDto();
            created.setId("conv-123");
            when(conversationCommandService.createConversation(any(ConversationDto.class))).thenReturn(created);

            String id = service.createConversation("user-1", null, null, null);
            assertThat(id).isEqualTo("conv-123");
        }

        @Test
        @DisplayName("should return null on exception")
        void shouldReturnNullOnError() {
            when(conversationCommandService.createConversation(any())).thenThrow(new RuntimeException("error"));
            assertThat(service.createConversation("user-1", "title", "model", "provider")).isNull();
        }

        @Test
        @DisplayName("should return null when service returns null")
        void shouldReturnNullWhenServiceReturnsNull() {
            when(conversationCommandService.createConversation(any())).thenReturn(null);
            assertThat(service.createConversation("user-1", "title", "model", "provider")).isNull();
        }
    }

    @Nested
    @DisplayName("addMessage")
    class AddMessage {

        @Test
        @DisplayName("should add message and return map")
        void shouldAddMessageAndReturnMap() {
            MessageDto created = new MessageDto("assistant", "Hello");
            created.setId("msg-1");
            created.setConversationId("conv-1");
            when(messageService.addMessage(eq("conv-1"), any(MessageDto.class))).thenReturn(created);

            Map<String, Object> result = service.addMessage("conv-1", "assistant", "Hello", "gpt-4", "2024-01-01", null, "user-1");

            assertThat(result).isNotNull();
            assertThat(result.get("id")).isEqualTo("msg-1");
            assertThat(result.get("role")).isEqualTo("assistant");
            assertThat(result.get("content")).isEqualTo("Hello");
        }

        @Test
        @DisplayName("should return null on exception")
        void shouldReturnNullOnError() {
            when(messageService.addMessage(anyString(), any())).thenThrow(new RuntimeException("error"));

            Map<String, Object> result = service.addMessage("conv-1", "user", "msg", null, null, null, "user-1");
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("getConversationHistory")
    class GetConversationHistory {

        @Test
        @DisplayName("should return history as map list - service now caps at 200 most recent and routes through the paginated repo path")
        void shouldReturnHistoryAsMaps() {
            MessageDto msg = new MessageDto("user", "Hello");
            msg.setId("msg-1");
            msg.setConversationId("conv-1");
            // Page returned DESC; service reverses to chronological ASC for callers.
            org.springframework.data.domain.Page<MessageDto> page =
                new org.springframework.data.domain.PageImpl<>(
                    List.of(msg), org.springframework.data.domain.PageRequest.of(0, 200), 1);
            when(messageService.getMessagesByConversationId(org.mockito.ArgumentMatchers.eq("conv-1"),
                    org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(200)))
                .thenReturn(page);

            List<Map<String, Object>> history = service.getConversationHistory("conv-1", "user-1");

            assertThat(history).hasSize(1);
            assertThat(history.get(0).get("role")).isEqualTo("user");
            // Regression: the un-paginated `getMessagesByConversationId(String)` was removed -
            // service MUST go through the paginated overload to keep heap bounded.
            verify(messageService).getMessagesByConversationId("conv-1", 0, 200);
        }

        @Test
        @DisplayName("should return empty list on error")
        void shouldReturnEmptyOnError() {
            when(messageService.getMessagesByConversationId(org.mockito.ArgumentMatchers.eq("conv-1"),
                    org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new RuntimeException("error"));

            List<Map<String, Object>> history = service.getConversationHistory("conv-1", "user-1");
            assertThat(history).isEmpty();
        }
    }

    @Nested
    @DisplayName("getConversationHistoryLimited")
    class GetConversationHistoryLimited {

        @Test
        @DisplayName("should return limited history")
        void shouldReturnLimitedHistory() {
            MessageDto msg = new MessageDto("user", "Hello");
            msg.setId("msg-1");
            when(messageService.getLastNMessagesChronological("conv-1", 10)).thenReturn(List.of(msg));

            List<Map<String, Object>> history = service.getConversationHistoryLimited("conv-1", "user-1", 10);
            assertThat(history).hasSize(1);
        }
    }

    @Nested
    @DisplayName("convertToChatMessages")
    class ConvertToChatMessages {

        @Test
        @DisplayName("should convert map list to ChatMessage list")
        void shouldConvertToChatMessages() {
            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "user", "content", "Hello", "timestamp", "2024-01-01", "toolCalls", ""),
                    Map.of("role", "assistant", "content", "Hi!", "timestamp", "2024-01-01", "toolCalls", "")
            );

            List<ChatRequest.ChatMessage> chatMessages = service.convertToChatMessages(messages);

            assertThat(chatMessages).hasSize(2);
            assertThat(chatMessages.get(0).getRole()).isEqualTo("user");
            assertThat(chatMessages.get(0).getContent()).isEqualTo("Hello");
            assertThat(chatMessages.get(1).getRole()).isEqualTo("assistant");
        }
    }

    @Nested
    @DisplayName("updateConversationTitle")
    class UpdateConversationTitle {

        @Test
        @DisplayName("should update title successfully")
        void shouldUpdateTitle() {
            ConversationDto existing = new ConversationDto();
            existing.setId("conv-1");
            existing.setTitle("Old Title");
            when(conversationQueryService.getConversationById("conv-1", "user-1", null))
                    .thenReturn(Optional.of(existing));
            when(conversationCommandService.updateConversation(eq("conv-1"), any())).thenReturn(existing);

            boolean result = service.updateConversationTitle("conv-1", "user-1", null, "New Title");
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false when conversation not found")
        void shouldReturnFalseWhenNotFound() {
            when(conversationQueryService.getConversationById("conv-x", "user-1", null))
                    .thenReturn(Optional.empty());

            boolean result = service.updateConversationTitle("conv-x", "user-1", null, "New Title");
            assertThat(result).isFalse();
        }
    }

}
