package com.apimarketplace.conversation.streaming;

import java.util.List;
import java.util.Map;

import com.apimarketplace.conversation.domain.stream.StreamEvent.ServiceApprovalInfo;

/**
 * Interface for streaming output operations.
 *
 * This interface abstracts the streaming output methods used by services
 * like ConversationAgentService and ChatStreamingService.
 *
 * Implementation:
 * - RedisStreamingOutput: Uses Redis Pub/Sub for scalable real-time streaming
 */
public interface StreamingOutput {

    /**
     * Checks if the stream is still processing and can accept content.
     */
    boolean isStreamProcessing();

    /**
     * Sends content to all connected clients.
     */
    void sendContent(String content, String model, String provider, String userId, String conversationId);

    /**
     * Sends thinking/reasoning content to all connected clients.
     * Used for thinking models (Gemini 2.5+/3, o1, etc.) to show the model's reasoning process.
     */
    void sendThinking(String thinking, String model, String provider, String userId, String conversationId);

    /**
     * Sends a single thinking section as a separate activity (like a tool call).
     * Frontend will add this as a new _thinking activity with its own point/bullet.
     *
     * @param title The section title (from **title**)
     * @param content The section content
     */
    void sendThinkingSection(String title, String content);

    /**
     * Sends the stream ID to clients.
     */
    void sendStreamId(String streamId);

    /**
     * Sends conversation ID ready event.
     */
    void sendConversationIdReady(String conversationId);

    /**
     * Sends conversation created event.
     */
    void sendConversationCreated(String conversationId, String title, boolean isTemporary);

    /**
     * Sends title updated event.
     */
    void sendTitleUpdated(String conversationId, String title, boolean isTemporary);

    /**
     * Sends user message event.
     */
    void sendUserMessage(String conversationId, Map<String, Object> userMessage);

    /**
     * Sends tool call event with thinking message.
     */
    void sendToolCall(String toolName, String toolCallId, String arguments,
                       String thinkingMessage, String model, String provider, String userId);

    /**
     * Sends tool result event with metadata only (no content).
     * Full result content is fetched on demand from DB via resultId.
     * Visualization info is included for tools that create/display resources.
     *
     * @param visualizationType Type of visualization (datasource, interface, workflow, workflow_run) or null
     * @param visualizationId Resource ID for visualization or null
     * @param visualizationTitle Display title for visualization or null
     * @param visualizationRunId Run ID for workflow_run visualization type or null
     * @param visualizationRunIndex Run index for workflow_run visualization type or null
     * @param iconSlug Icon slug for UI display (for catalog_call) or null
     * @param displayToolName Human-readable tool name (for catalog_call) or null
     * @param label Step label for workflow add_step or null
     * @param serviceApproval Service approval data for request_service_approval tool or null
     */
    void sendToolResult(String toolName, String toolId, boolean success, Long durationMs,
                        String resultId, String error,
                        String visualizationType, String visualizationId, String visualizationTitle,
                        String visualizationRunId, Integer visualizationRunIndex,
                        String iconSlug, String displayToolName, String label,
                        Map<String, Object> serviceApproval,
                        String model, String provider, String userId, String conversationId);

    /**
     * Sends credential required event (for JIT tool execution).
     */
    void sendCredentialRequired(String credentialType, String toolName, String toolId);

    /**
     * Sends service approval required event (for batch service approval).
     * Contains a list of services with their display info (icon, name, description).
     *
     * @param services List of services requiring approval with their display info
     * @param reason Brief explanation of why these services are needed
     */
    void sendServiceApprovalRequired(List<ServiceApprovalInfo> services, String reason, boolean needsAttention);

    /**
     * Sends awaiting approval event and stops the stream.
     * Called when the agent requests service approval and needs to pause until user responds.
     * Different from 'stopped' (red) - this shows as 'awaiting' (amber) in the UI.
     *
     * @param services List of services awaiting approval
     */
    void sendAwaitingApproval(List<ServiceApprovalInfo> services);

    /**
     * Sends pending action cancelled event.
     * Called when user sends a new message while a pending action (service approval) is waiting.
     * Notifies frontend to hide ServiceApprovalCard.
     *
     * @param reason Why the pending action was cancelled (e.g., "new_message")
     * @param message Descriptive message for logging/debugging
     */
    void sendPendingActionCancelled(String reason, String message);

    /**
     * Sends done message and completes the stream.
     */
    void sendDone(String fullResponse, String model, String provider,
                   String userId, String conversationId);

    /**
     * Sends done message without full response.
     */
    void sendDoneSimple(String model, String provider, String userId,
                         String conversationId, String streamId);

    /**
     * Sends error message.
     */
    void sendError(String errorMessage);

    /**
     * Sends error message with context.
     */
    void sendError(String errorMessage, String model, String provider,
                    String userId, String conversationId);

    /**
     * Stops the stream.
     */
    void stop();

    /**
     * Gets the current stream ID.
     */
    String getCurrentStreamId();

    /**
     * Gets the current buffered content.
     */
    String getCurrentContent();

    /**
     * Handles natural end of stream.
     */
    void handleNaturalEnd(String fullContent, String conversationId);

    /**
     * Handles stream end (called from callback).
     */
    void handleStreamEnd(String errorMessage, String conversationId);

    /**
     * Checks if stream is active.
     */
    boolean isActive();

    /**
     * Updates the conversationId for this stream.
     * Called when a real conversationId is assigned after stream creation.
     */
    void updateConversationId(String newConversationId);
}
