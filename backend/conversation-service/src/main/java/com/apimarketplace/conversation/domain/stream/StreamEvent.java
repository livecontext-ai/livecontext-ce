package com.apimarketplace.conversation.domain.stream;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Sealed interface representing all possible events in a streaming session.
 * Each event type carries specific data relevant to that event.
 */
public sealed interface StreamEvent permits
        StreamEvent.StreamStarted,
        StreamEvent.ContentChunk,
        StreamEvent.ThinkingChunk,
        StreamEvent.ThinkingSection,
        StreamEvent.ToolCall,
        StreamEvent.ToolResult,
        StreamEvent.FetchScreenshot,
        StreamEvent.AgentBrowseStep,
        StreamEvent.CredentialRequired,
        StreamEvent.ServiceApprovalRequired,
        StreamEvent.StreamAwaitingApproval,
        StreamEvent.PendingActionCancelled,
        StreamEvent.StreamCompleted,
        StreamEvent.StreamError,
        StreamEvent.StreamStopped,
        StreamEvent.Heartbeat,
        StreamEvent.TitleUpdated,
        StreamEvent.CompactionDone {

    String streamId();
    Instant timestamp();

    /**
     * Emitted when a new stream is created and ready to receive content.
     */
    record StreamStarted(
            String streamId,
            String conversationId,
            String model,
            Instant timestamp
    ) implements StreamEvent {}

    /**
     * Emitted for each content chunk received from the LLM.
     */
    record ContentChunk(
            String streamId,
            String content,
            Instant timestamp
    ) implements StreamEvent {}

    /**
     * Emitted for each thinking/reasoning chunk from thinking models (Gemini 2.5+/3, o1, etc.).
     * This represents the model's internal reasoning process before producing the final response.
     */
    record ThinkingChunk(
            String streamId,
            String thinking,
            Instant timestamp
    ) implements StreamEvent {}

    /**
     * Emitted for a single thinking section (like a tool call).
     * Each section becomes a separate activity with its own point/bullet.
     */
    record ThinkingSection(
            String streamId,
            String title,
            String content,
            Instant timestamp
    ) implements StreamEvent {}

    /**
     * Emitted when the LLM requests a tool call.
     */
    record ToolCall(
            String streamId,
            String toolName,
            String toolId,
            Map<String, Object> arguments,
            Instant timestamp
    ) implements StreamEvent {}

    /**
     * Emitted when a tool execution completes.
     * Note: Only metadata is sent via streaming. Full result content is fetched on demand from DB.
     * Visualization info is included for tools that create/display resources (datasource, interface, workflow).
     * iconSlug and displayToolName are included for catalog_call to show the actual API icon and name.
     * label is included for workflow add_step to show the step label.
     * serviceApproval is included for request_service_approval to display approval preview.
     */
    record ToolResult(
            String streamId,
            String toolId,
            String toolName,
            boolean success,
            Long durationMs,
            String resultId,
            String error,
            Visualization visualization,
            String iconSlug,
            String displayToolName,
            String label,
            Map<String, Object> serviceApproval,
            Instant timestamp
    ) implements StreamEvent {}

    /**
     * Visualization info for displaying resources inline in tool results.
     * Used for datasource tables, interface previews, workflow diagrams, and workflow runs.
     */
    record Visualization(
            String type,   // "datasource", "interface", "workflow", or "workflow_run"
            String id,     // Resource ID (workflowId for workflow_run)
            String title,  // Display title
            String runId,  // Run ID (only for workflow_run type)
            Integer runIndex // Run index for display (only for workflow_run type)
    ) {
        /**
         * Constructor for non-workflow_run types (backward compatible).
         */
        public Visualization(String type, String id, String title) {
            this(type, id, title, null, null);
        }
    }

    /**
     * Emitted when a fetch screenshot is received from websearch-service.
     * Used for progressive live browser preview in the chat.
     * screenshotKey is an S3/MinIO key (e.g. "screenshots/abc123_def456.jpg").
     */
    record FetchScreenshot(
            String streamId,
            String toolId,
            String url,
            int screenshotIndex,
            String screenshotKey,
            boolean isFinal,
            Instant timestamp
    ) implements StreamEvent {}

    /**
     * Emitted when an agent_browse session boots and the runner has captured
     * the upstream Chromium DevTools endpoint. Carries the live-view
     * connection coordinates so the chat frontend can open the CDP
     * WebSocket bridge BEFORE the (blocking) tool call returns.
     *
     * <p>The orchestrator's {@code BrowserStepStreamConsumer} XREADs this
     * from the per-session Redis Stream {@code agent:run:{rid}:node:{nid}:steps}
     * (where the runner XADDed it as a {@code "cdp_ready"} step event),
     * mints the {@code cdpToken} via {@code CdpTokenIssuer}, and
     * publishes this record on the chat pub/sub channels so the
     * frontend can render the live-view card mid-execution.</p>
     *
     * <p>Discriminator: presence of {@code cdpToken} field - distinguishes
     * from {@link FetchScreenshot} (which has {@code screenshotKey}).</p>
     */
    record AgentBrowseStep(
            String streamId,
            String toolId,
            String sessionId,
            String cdpToken,
            String cdpWsUrl,
            String currentUrl,
            int stepIndex,
            String runId,
            String nodeId,
            Instant timestamp
    ) implements StreamEvent {}

    /**
     * Emitted when a tool requires user credentials.
     */
    record CredentialRequired(
            String streamId,
            String credentialType,
            String toolName,
            String toolId,
            Instant timestamp
    ) implements StreamEvent {}

    /**
     * Emitted when the agent requests approval for external services.
     * Contains a list of services with their display info (icon, name, description).
     */
    record ServiceApprovalRequired(
            String streamId,
            List<ServiceApprovalInfo> services,
            String reason,
            boolean needsAttention,
            Instant timestamp
    ) implements StreamEvent {}

    /**
     * Information about a service requiring approval.
     * Used for displaying service details in the approval prompt.
     */
    record ServiceApprovalInfo(
            String serviceType,    // e.g., "gmail", "slack"
            String serviceName,    // e.g., "Gmail", "Slack"
            String iconSlug,       // e.g., "gmail", "slack" for icon display
            String toolName,       // e.g., "List Messages", "Send Message"
            String toolId,         // Tool UUID that triggered the approval
            String description     // Why this service is needed
    ) {
        /**
         * Create from minimal info.
         */
        public ServiceApprovalInfo(String serviceType, String serviceName, String iconSlug) {
            this(serviceType, serviceName, iconSlug, null, null, null);
        }
    }

    /**
     * Emitted when the stream completes normally.
     */
    record StreamCompleted(
            String streamId,
            String fullContent,
            int totalTokens,
            Instant timestamp
    ) implements StreamEvent {}

    /**
     * Emitted when an error occurs during streaming.
     */
    record StreamError(
            String streamId,
            String error,
            String errorCode,
            boolean retryable,
            Instant timestamp
    ) implements StreamEvent {}

    /**
     * Emitted when the user manually stops the stream.
     */
    record StreamStopped(
            String streamId,
            String partialContent,
            Instant timestamp
    ) implements StreamEvent {}

    /**
     * Emitted when the stream is paused awaiting user approval for external services.
     * Different from stopped (user-initiated) - this is a temporary pause waiting for user action.
     */
    record StreamAwaitingApproval(
            String streamId,
            String partialContent,
            List<ServiceApprovalInfo> services,
            Instant timestamp
    ) implements StreamEvent {}

    /**
     * Emitted when a pending action is cancelled because user sent a new message.
     * This notifies the frontend to hide ServiceApprovalCard.
     */
    record PendingActionCancelled(
            String streamId,
            String reason,
            String message,
            Instant timestamp
    ) implements StreamEvent {}

    /**
     * Emitted periodically to keep the streaming connection alive.
     */
    record Heartbeat(
            String streamId,
            Instant timestamp
    ) implements StreamEvent {}

    /**
     * Emitted when the conversation title is synthesized.
     */
    record TitleUpdated(
            String streamId,
            String conversationId,
            String title,
            Instant timestamp
    ) implements StreamEvent {}

    /**
     * Emitted when a post-turn COLD compaction pass persists a new summary
     * envelope for the conversation. Fire-and-forget from
     * {@code ChatCompactionOrchestrator}; the frontend uses it to flash a
     * "prior context summarised" banner. Because compaction runs on the
     * {@code @Async} executor, this may publish after {@code StreamCompleted}
     * closed the stream - in that case the event is harmlessly dropped and
     * the frontend picks up the same info from the conversation DTO on the
     * next load (persistent source of truth: {@code conversation.summary_cold}).
     *
     * <p>Field naming note: {@code turnsCoveredCount} is an {@code int} -
     * intentionally different from {@code ConversationDto.CompactionMarker.turnsCovered}
     * which is a {@code List<Integer>} of the actual indices. The SSE event
     * is a lightweight real-time banner cue; the DTO drives the divider
     * placement. Distinct field names prevent a dev from mistakenly assuming
     * {@code event.turnsCoveredCount === dto.turnsCovered.length}.
     */
    record CompactionDone(
            String streamId,
            String conversationId,
            int turnsCoveredCount,
            String summarizerModel,
            Instant generatedAt,
            Instant timestamp
    ) implements StreamEvent {}

    /**
     * Factory methods for creating events.
     */
    static StreamStarted started(String streamId, String conversationId, String model) {
        return new StreamStarted(streamId, conversationId, model, Instant.now());
    }

    static ContentChunk content(String streamId, String content) {
        return new ContentChunk(streamId, content, Instant.now());
    }

    static ThinkingChunk thinking(String streamId, String thinking) {
        return new ThinkingChunk(streamId, thinking, Instant.now());
    }

    static ThinkingSection thinkingSection(String streamId, String title, String content) {
        return new ThinkingSection(streamId, title, content, Instant.now());
    }

    static ToolCall toolCall(String streamId, String toolName, String toolId, Map<String, Object> arguments) {
        return new ToolCall(streamId, toolName, toolId, arguments, Instant.now());
    }

    static ToolResult toolResult(String streamId, String toolId, String toolName,
                                  boolean success, Long durationMs,
                                  String resultId, String error,
                                  Visualization visualization,
                                  String iconSlug, String displayToolName, String label,
                                  Map<String, Object> serviceApproval) {
        return new ToolResult(streamId, toolId, toolName, success, durationMs,
                              resultId, error, visualization, iconSlug, displayToolName, label, serviceApproval, Instant.now());
    }

    static FetchScreenshot fetchScreenshot(String streamId, String toolId, String url,
                                              int screenshotIndex, String screenshotKey, boolean isFinal) {
        return new FetchScreenshot(streamId, toolId, url, screenshotIndex, screenshotKey, isFinal, Instant.now());
    }

    static CredentialRequired credentialRequired(String streamId, String credentialType, String toolName, String toolId) {
        return new CredentialRequired(streamId, credentialType, toolName, toolId, Instant.now());
    }

    static ServiceApprovalRequired serviceApprovalRequired(String streamId, List<ServiceApprovalInfo> services, String reason, boolean needsAttention) {
        return new ServiceApprovalRequired(streamId, services, reason, needsAttention, Instant.now());
    }

    static StreamCompleted completed(String streamId, String fullContent, int totalTokens) {
        return new StreamCompleted(streamId, fullContent, totalTokens, Instant.now());
    }

    static StreamError error(String streamId, String error, String errorCode, boolean retryable) {
        return new StreamError(streamId, error, errorCode, retryable, Instant.now());
    }

    static StreamStopped stopped(String streamId, String partialContent) {
        return new StreamStopped(streamId, partialContent, Instant.now());
    }

    static StreamAwaitingApproval awaitingApproval(String streamId, String partialContent, List<ServiceApprovalInfo> services) {
        return new StreamAwaitingApproval(streamId, partialContent, services, Instant.now());
    }

    static PendingActionCancelled pendingActionCancelled(String streamId, String reason, String message) {
        return new PendingActionCancelled(streamId, reason, message, Instant.now());
    }

    static Heartbeat heartbeat(String streamId) {
        return new Heartbeat(streamId, Instant.now());
    }

    static TitleUpdated titleUpdated(String streamId, String conversationId, String title) {
        return new TitleUpdated(streamId, conversationId, title, Instant.now());
    }

    static CompactionDone compactionDone(String streamId, String conversationId,
                                         int turnsCoveredCount, String summarizerModel,
                                         Instant generatedAt) {
        return new CompactionDone(streamId, conversationId, turnsCoveredCount,
                summarizerModel, generatedAt, Instant.now());
    }
}
