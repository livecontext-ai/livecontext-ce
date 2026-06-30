package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.service.ConversationHistoryService;
import com.apimarketplace.conversation.service.StreamApiClient;
import com.apimarketplace.conversation.streaming.StreamingOutput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Service for processing chat streaming requests.
 *
 * V3 Architecture:
 * - Uses Redis for state management via StreamStateService
 * - Delegates to ConversationAgentService for agent loop with tools
 * - Single responsibility: Orchestrate the streaming request flow
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamingService {

    private final ConversationHistoryService conversationHistoryService;
    private final StreamApiClient streamApiClient;
    private final ConversationAgentService conversationAgentService;
    private final com.apimarketplace.conversation.service.PendingActionService pendingActionService;
    private final com.apimarketplace.conversation.service.MessageService messageService;

    /**
     * Maximum number of messages to include in agent context.
     * 20 messages = approximately 10 exchanges (user + assistant pairs).
     */
    @Value("${conversation.agent.history-limit:20}")
    private int historyMessageLimit;

    /**
     * Process a streaming chat request.
     *
     * Flow:
     * 1. Send stream ID to frontend
     * 2. Validate conversation exists
     * 3. Register stream in database
     * 4. Add user message to history
     * 5. Load conversation history for context
     * 6. Execute agent loop with streaming
     */
    public void processStreamingRequest(ChatRequest request, StreamingOutput streamOutput, String streamId) {
        try {
            log.info("Starting streaming with model: {}", request.getModel());

            // Send stream ID to frontend
            streamOutput.sendStreamId(streamId);

            // Get conversation ID (must be set by ChatControllerV3)
            String conversationId = validateAndGetConversationId(request);

            // Clear pending actions if exists (user sent a fresh message, abandoning previous
            // requests). SKIPPED for a resume (keepPendingActions=true): the user resolved ONE
            // parallel card and the OTHERS are still pending - wiping them here (and emitting
            // pending_action_cancelled) would make the sibling cards vanish from the chat.
            if (!request.isKeepPendingActions()) {
                clearPendingActionIfExists(conversationId, streamOutput);
            }

            // Register stream in database
            streamApiClient.createStream(streamId, conversationId, request.getUserId());

            // Add user message to history
            Map<String, Object> createdUserMessage = conversationHistoryService.addMessage(
                conversationId, "user", request.getMessage(), request.getModel(),
                request.getTimestamp(), null, request.getUserId()
            );

            // Save attachments if present
            if (request.getAttachments() != null && !request.getAttachments().isEmpty() && createdUserMessage != null) {
                String messageId = (String) createdUserMessage.get("id");
                if (messageId != null) {
                    messageService.saveAttachments(messageId, request.getAttachments());
                    log.info("Saved {} attachments for user message {}", request.getAttachments().size(), messageId);
                }
            }

            // Send user_message event
            streamOutput.sendUserMessage(conversationId, createdUserMessage);

            // Load conversation history for agent context
            loadConversationHistoryIntoRequest(request, conversationId);

            // Execute agent loop with streaming
            log.info("Executing agent loop for conversation: {}", conversationId);
            conversationAgentService.executeStreaming(request, streamOutput, conversationId);

        } catch (Exception e) {
            log.error("Error in streaming: {}", e.getMessage(), e);
            handleStreamingError(streamOutput, streamId, e);
        }
    }

    /**
     * Validates that conversation ID is set and returns it.
     * In V3 architecture, ChatControllerV3 creates the conversation before streaming.
     */
    private String validateAndGetConversationId(ChatRequest request) {
        String conversationId = request.getConversationId();

        if (conversationId == null || conversationId.isEmpty()) {
            throw new IllegalStateException(
                "ConversationId must be set before streaming. " +
                "This indicates a bug in the V3 flow - ChatControllerV3 should create the conversation first."
            );
        }

        log.debug("Using conversation: {}", conversationId);
        return conversationId;
    }

    /**
     * Loads conversation history from DB and injects it into the request.
     * Uses limited history (last N messages) in chronological order.
     */
    private void loadConversationHistoryIntoRequest(ChatRequest request, String conversationId) {
        if (request.getConversationHistory() != null && !request.getConversationHistory().isEmpty()) {
            log.debug("Request already has conversation history, skipping DB load");
            return;
        }

        List<Map<String, Object>> dbHistory = conversationHistoryService.getConversationHistoryLimited(
            conversationId, request.getUserId(), historyMessageLimit
        );

        if (dbHistory != null && !dbHistory.isEmpty()) {
            List<ChatRequest.ChatMessage> chatHistory = conversationHistoryService.convertToChatMessages(dbHistory);
            request.setConversationHistory(chatHistory);
            log.info("Loaded {} messages (limit: {}) from conversation history",
                chatHistory.size(), historyMessageLimit);
        }
    }

    /**
     * Handles streaming errors by sending error event and marking stream as error.
     */
    private void handleStreamingError(StreamingOutput streamOutput, String streamId, Exception e) {
        if (streamOutput.isStreamProcessing()) {
            streamOutput.sendError("Error processing your message: " + e.getMessage());
        }
        streamApiClient.markStreamAsError(streamId, e.getMessage());
    }

    /**
     * Clear any pending action when user sends a new message.
     * This prevents orphaned pending actions from previous requests.
     * Notifies frontend to hide ServiceApprovalCard.
     */
    private void clearPendingActionIfExists(String conversationId, StreamingOutput streamOutput) {
        java.util.Optional<java.util.Map<String, Object>> pendingAction =
            pendingActionService.getPendingAction(conversationId);

        if (pendingAction.isPresent()) {
            log.info("Clearing pending action for conversation {} - user sent new message", conversationId);
            pendingActionService.clearPendingAction(conversationId);

            // Notify frontend to hide ServiceApprovalCard
            streamOutput.sendPendingActionCancelled(
                "new_message",
                "Previous action request cancelled"
            );
        }
    }
}
