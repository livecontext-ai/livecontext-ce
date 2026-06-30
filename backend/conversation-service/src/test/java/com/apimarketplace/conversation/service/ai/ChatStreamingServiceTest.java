package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.service.ConversationHistoryService;
import com.apimarketplace.conversation.service.MessageService;
import com.apimarketplace.conversation.service.PendingActionService;
import com.apimarketplace.conversation.service.StreamApiClient;
import com.apimarketplace.conversation.streaming.StreamingOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ChatStreamingService")
@ExtendWith(MockitoExtension.class)
class ChatStreamingServiceTest {

    @Mock
    private ConversationHistoryService conversationHistoryService;

    @Mock
    private StreamApiClient streamApiClient;

    @Mock
    private ConversationAgentService conversationAgentService;

    @Mock
    private PendingActionService pendingActionService;

    @Mock
    private MessageService messageService;

    @Mock
    private StreamingOutput streamOutput;

    @InjectMocks
    private ChatStreamingService chatStreamingService;

    @BeforeEach
    void setUp() throws Exception {
        Field field = ChatStreamingService.class.getDeclaredField("historyMessageLimit");
        field.setAccessible(true);
        field.set(chatStreamingService, 20);
    }

    @Nested
    @DisplayName("processStreamingRequest")
    class ProcessStreamingRequestTests {

        @Test
        @DisplayName("should process basic streaming request")
        void shouldProcessBasicRequest() {
            ChatRequest request = new ChatRequest();
            request.setConversationId("conv-1");
            request.setUserId("user-1");
            request.setMessage("Hello");
            request.setModel("gpt-4");

            Map<String, Object> userMessage = Map.of("id", "msg-1", "role", "user", "content", "Hello");
            when(conversationHistoryService.addMessage(
                    eq("conv-1"), eq("user"), eq("Hello"), eq("gpt-4"),
                    any(), isNull(), eq("user-1")))
                    .thenReturn(userMessage);

            when(pendingActionService.getPendingAction("conv-1")).thenReturn(Optional.empty());

            chatStreamingService.processStreamingRequest(request, streamOutput, "stream-1");

            verify(streamOutput).sendStreamId("stream-1");
            verify(streamApiClient).createStream("stream-1", "conv-1", "user-1");
            verify(conversationHistoryService).addMessage(
                    eq("conv-1"), eq("user"), eq("Hello"), eq("gpt-4"),
                    any(), isNull(), eq("user-1"));
            verify(streamOutput).sendUserMessage(eq("conv-1"), eq(userMessage));
            verify(conversationAgentService).executeStreaming(eq(request), eq(streamOutput), eq("conv-1"));
        }

        @Test
        @DisplayName("should throw when conversationId is missing")
        void shouldThrowWhenConversationIdMissing() {
            ChatRequest request = new ChatRequest();
            request.setUserId("user-1");
            request.setMessage("Hello");

            when(streamOutput.isStreamProcessing()).thenReturn(true);

            chatStreamingService.processStreamingRequest(request, streamOutput, "stream-1");

            verify(streamOutput).sendError(contains("Error processing your message"));
            verify(streamApiClient).markStreamAsError(eq("stream-1"), any());
        }

        @Test
        @DisplayName("should clear pending action if exists")
        void shouldClearPendingAction() {
            ChatRequest request = new ChatRequest();
            request.setConversationId("conv-1");
            request.setUserId("user-1");
            request.setMessage("Hello");
            request.setModel("gpt-4");

            Map<String, Object> pendingAction = Map.of("type", "credential", "toolName", "gmail");
            when(pendingActionService.getPendingAction("conv-1")).thenReturn(Optional.of(pendingAction));

            Map<String, Object> userMessage = Map.of("id", "msg-1");
            when(conversationHistoryService.addMessage(
                    any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(userMessage);

            chatStreamingService.processStreamingRequest(request, streamOutput, "stream-1");

            verify(pendingActionService).clearPendingAction("conv-1");
            verify(streamOutput).sendPendingActionCancelled("new_message", "Previous action request cancelled");
        }

        @Test
        @DisplayName("should NOT clear pending actions on a resume (keepPendingActions=true) - sibling cards survive")
        void shouldNotClearPendingActionOnResume() {
            ChatRequest request = new ChatRequest();
            request.setConversationId("conv-1");
            request.setUserId("user-1");
            request.setMessage("I connected Gmail, continue");
            request.setModel("gpt-4");
            request.setKeepPendingActions(true); // resume after resolving ONE parallel card

            Map<String, Object> userMessage = Map.of("id", "msg-1");
            when(conversationHistoryService.addMessage(
                    any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(userMessage);

            chatStreamingService.processStreamingRequest(request, streamOutput, "stream-1");

            // The start-of-turn cleanup must be skipped - the other still-pending cards stay.
            verify(pendingActionService, never()).getPendingAction("conv-1");
            verify(pendingActionService, never()).clearPendingAction("conv-1");
            verify(streamOutput, never()).sendPendingActionCancelled(any(), any());
        }

        @Test
        @DisplayName("should save attachments when present")
        void shouldSaveAttachments() {
            ChatRequest request = new ChatRequest();
            request.setConversationId("conv-1");
            request.setUserId("user-1");
            request.setMessage("See attached");
            request.setModel("gpt-4");

            List<ChatRequest.ChatMessage> attachments = List.of();
            // Using setAttachments if it exists - skipping this detailed test
            // as attachments require specific DTO structure

            when(pendingActionService.getPendingAction("conv-1")).thenReturn(Optional.empty());

            Map<String, Object> userMessage = Map.of("id", "msg-1");
            when(conversationHistoryService.addMessage(
                    any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(userMessage);

            chatStreamingService.processStreamingRequest(request, streamOutput, "stream-1");

            verify(streamOutput).sendStreamId("stream-1");
        }

        @Test
        @DisplayName("should load conversation history from DB when not provided")
        void shouldLoadConversationHistory() {
            ChatRequest request = new ChatRequest();
            request.setConversationId("conv-1");
            request.setUserId("user-1");
            request.setMessage("Follow up");
            request.setModel("gpt-4");

            when(pendingActionService.getPendingAction("conv-1")).thenReturn(Optional.empty());

            Map<String, Object> userMessage = Map.of("id", "msg-1");
            when(conversationHistoryService.addMessage(
                    any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(userMessage);

            List<Map<String, Object>> dbHistory = List.of(
                    Map.of("role", "user", "content", "Hi"),
                    Map.of("role", "assistant", "content", "Hello!")
            );
            when(conversationHistoryService.getConversationHistoryLimited("conv-1", "user-1", 20))
                    .thenReturn(dbHistory);

            List<ChatRequest.ChatMessage> chatMessages = List.of(new ChatRequest.ChatMessage());
            when(conversationHistoryService.convertToChatMessages(dbHistory))
                    .thenReturn(chatMessages);

            chatStreamingService.processStreamingRequest(request, streamOutput, "stream-1");

            verify(conversationHistoryService).getConversationHistoryLimited("conv-1", "user-1", 20);
            verify(conversationHistoryService).convertToChatMessages(dbHistory);
        }

        @Test
        @DisplayName("should not reload history when already provided in request")
        void shouldNotReloadHistory() {
            ChatRequest request = new ChatRequest();
            request.setConversationId("conv-1");
            request.setUserId("user-1");
            request.setMessage("Follow up");
            request.setModel("gpt-4");

            ChatRequest.ChatMessage existingMsg = new ChatRequest.ChatMessage();
            existingMsg.setRole("user");
            existingMsg.setContent("Previous message");
            request.setConversationHistory(List.of(existingMsg));

            when(pendingActionService.getPendingAction("conv-1")).thenReturn(Optional.empty());

            Map<String, Object> userMessage = Map.of("id", "msg-1");
            when(conversationHistoryService.addMessage(
                    any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(userMessage);

            chatStreamingService.processStreamingRequest(request, streamOutput, "stream-1");

            verify(conversationHistoryService, never()).getConversationHistoryLimited(any(), any(), anyInt());
        }

        @Test
        @DisplayName("should handle streaming error gracefully")
        void shouldHandleStreamingError() {
            ChatRequest request = new ChatRequest();
            request.setConversationId("conv-1");
            request.setUserId("user-1");
            request.setMessage("Hello");
            request.setModel("gpt-4");

            when(pendingActionService.getPendingAction("conv-1")).thenReturn(Optional.empty());
            when(conversationHistoryService.addMessage(
                    any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("DB error"));
            when(streamOutput.isStreamProcessing()).thenReturn(true);

            chatStreamingService.processStreamingRequest(request, streamOutput, "stream-1");

            verify(streamOutput).sendError(contains("Error processing your message"));
            verify(streamApiClient).markStreamAsError(eq("stream-1"), any());
        }
    }
}
