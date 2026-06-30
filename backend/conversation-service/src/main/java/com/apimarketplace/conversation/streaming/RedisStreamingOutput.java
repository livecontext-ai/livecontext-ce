package com.apimarketplace.conversation.streaming;

import com.apimarketplace.conversation.domain.stream.StreamEvent;
import com.apimarketplace.conversation.domain.stream.StreamEvent.ServiceApprovalInfo;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-based implementation of StreamingOutput.
 * Uses Redis Pub/Sub for real-time event distribution and Redis for state storage.
 *
 * Architecture:
 * - Events published to Redis Pub/Sub for real-time delivery
 * - State stored in Redis for horizontal scalability
 * - Content buffered to Redis for reconnection support
 * - Retry logic for resilient Redis operations
 */
@Slf4j
public class RedisStreamingOutput implements StreamingOutput {

    // Retry configuration for Redis operations
    private static final int MAX_RETRIES = 3;
    private static final Duration RETRY_DELAY = Duration.ofMillis(100);
    private static final Duration RETRY_MAX_DELAY = Duration.ofMillis(500);
    private static final Duration ACTIVE_CHECK_TIMEOUT = Duration.ofMillis(500);

    // Max content buffer size (2MB) to prevent OOM from unbounded growth.
    // Content is also stored in Redis (source of truth), so truncating the local buffer is safe.
    private static final int MAX_CONTENT_BUFFER_CHARS = 2 * 1024 * 1024;

    private final String streamId;
    private final StreamStateService stateService;
    private final StreamPubSubService pubSubService;
    private final AtomicBoolean isProcessing = new AtomicBoolean(true);
    private final StringBuilder contentBuffer = new StringBuilder();
    private final Object contentLock = new Object();

    private volatile String conversationId;
    private volatile String model;
    private volatile String provider;

    /**
     * Create a retry spec for Redis operations.
     * Uses exponential backoff with jitter.
     */
    private Retry createRetrySpec(String operationName) {
        return Retry.backoff(MAX_RETRIES, RETRY_DELAY)
                .maxBackoff(RETRY_MAX_DELAY)
                .doBeforeRetry(signal -> log.warn("⚠️ [REDIS] Retrying {} (attempt {}): {}",
                        operationName, signal.totalRetries() + 1, signal.failure().getMessage()));
    }

    /**
     * Subscribe with retry and error logging for non-critical operations.
     */
    private <T> void subscribeWithRetry(Mono<T> mono, String operationName) {
        mono.retryWhen(createRetrySpec(operationName))
                .doOnError(e -> log.error("❌ [REDIS] {} failed after {} retries: {}",
                        operationName, MAX_RETRIES, e.getMessage()))
                .subscribe();
    }

    /**
     * Execute critical operation with retry and blocking.
     * Used for state changes that must complete (complete, error, stop).
     */
    private <T> void executeBlocking(Mono<T> mono, String operationName, Duration timeout) {
        try {
            mono.retryWhen(createRetrySpec(operationName))
                    .block(timeout);
        } catch (Exception e) {
            log.error("❌ [REDIS] Critical {} failed: {}", operationName, e.getMessage());
        }
    }

    public RedisStreamingOutput(
            String streamId,
            StreamStateService stateService,
            StreamPubSubService pubSubService,
            String conversationId,
            String model,
            String provider) {
        this.streamId = streamId;
        this.stateService = stateService;
        this.pubSubService = pubSubService;
        this.conversationId = conversationId;
        this.model = model;
        this.provider = provider;

        log.info("✅ [REDIS STREAMING] Created RedisStreamingOutput for stream: {} conversation: {}",
                streamId, conversationId);
    }

    @Override
    public boolean isStreamProcessing() {
        if (!isProcessing.get()) {
            return false;
        }

        try {
            Boolean active = stateService.isActive(streamId).block(ACTIVE_CHECK_TIMEOUT);
            if (active != null && !active) {
                isProcessing.set(false);
                return false;
            }
        } catch (Exception e) {
            log.warn("[REDIS STREAMING] Failed to read stream activity for {} - keeping local stream active: {}",
                    streamId, e.getMessage());
        }

        return true;
    }

    @Override
    public void sendContent(String content, String model, String provider, String userId, String conversationId) {
        if (!isStreamProcessing()) {
            log.debug("📤 [REDIS STREAMING] Stream not active - ignoring content");
            return;
        }

        // Buffer content locally for getCurrentContent()
        synchronized (contentLock) {
            if (contentBuffer.length() < MAX_CONTENT_BUFFER_CHARS) {
                contentBuffer.append(content);
            } else {
                log.warn("⚠️ [REDIS STREAMING] Content buffer cap reached ({}MB) for stream: {} - local buffer truncated, Redis has full content",
                        MAX_CONTENT_BUFFER_CHARS / (1024 * 1024), streamId);
            }
        }

        // Append content to Redis for reconnection support (with retry)
        subscribeWithRetry(
                stateService.appendContent(streamId, content),
                "appendContent"
        );

        // Publish to Redis Pub/Sub for real-time delivery (with retry)
        subscribeWithRetry(
                pubSubService.publishContent(streamId, content)
                        .doOnSuccess(receivers -> log.trace("📢 [REDIS STREAMING] Published content to {} receivers", receivers)),
                "publishContent"
        );
    }

    @Override
    public void sendThinking(String thinking, String model, String provider, String userId, String conversationId) {
        log.info("🧠 [REDIS STREAMING] sendThinking called - {} chars, isStreamProcessing={}",
                thinking != null ? thinking.length() : 0, isStreamProcessing());

        if (!isStreamProcessing()) {
            log.debug("📤 [REDIS STREAMING] Stream not active - ignoring thinking content");
            return;
        }

        // Publish thinking event to Redis Pub/Sub for real-time delivery
        log.info("🧠 [REDIS STREAMING] Publishing thinking to streamId: {}", streamId);
        subscribeWithRetry(
                pubSubService.publishThinking(streamId, thinking)
                        .doOnSuccess(receivers -> log.info("🧠 [REDIS STREAMING] Published thinking to {} receivers", receivers)),
                "publishThinking"
        );
    }

    @Override
    public void sendThinkingSection(String title, String content) {
        // Note: We intentionally do NOT check isStreamProcessing() here
        // Thinking sections must be sent even during flushThinkingBuffer() in onComplete()
        // when the stream might already be in STOPPED state. This ensures streaming
        // displays the same thinking content as refresh (which loads from DB).
        if (streamId == null) {
            log.warn("🧠 [REDIS STREAMING] Cannot send thinking section - no streamId");
            return;
        }

        log.info("🧠 [REDIS STREAMING] Publishing thinking section '{}' to streamId: {}", title, streamId);
        subscribeWithRetry(
                pubSubService.publishThinkingSection(streamId, title, content)
                        .doOnSuccess(receivers -> log.info("🧠 [REDIS STREAMING] Published thinking section to {} receivers", receivers)),
                "publishThinkingSection"
        );
    }

    @Override
    public void sendStreamId(String streamId) {
        if (!isStreamProcessing()) return;

        // Update state to STREAMING immediately when stream starts (CRITICAL - with blocking retry)
        // This is critical for reconnection - the state must be STREAMING before any content is sent
        executeBlocking(
                stateService.updateState(streamId, StreamState.STREAMING)
                        .doOnSuccess(updated -> log.info("📢 [REDIS STREAMING] State updated to STREAMING for stream: {}", streamId)),
                "updateStateToStreaming",
                Duration.ofSeconds(2)
        );

        // Publish stream started event (with retry)
        log.info("📢 [REDIS STREAMING] Sending stream_started event - streamId: {} conversationId: {} model: {}",
                streamId, conversationId, model);

        subscribeWithRetry(
                pubSubService.publish(streamId, StreamEvent.started(streamId, conversationId, model)),
                "publishStreamStarted"
        );

        log.info("📢 [REDIS STREAMING] Sent stream_id: {} with conversationId: {}", streamId, conversationId);
    }

    @Override
    public void sendConversationIdReady(String conversationId) {
        if (!isStreamProcessing()) return;

        this.conversationId = conversationId;

        // Update conversation ID in Redis state (with retry)
        subscribeWithRetry(
                stateService.updateConversationId(streamId, conversationId),
                "updateConversationId"
        );

        log.debug("📢 [REDIS STREAMING] Conversation ready: {}", conversationId);
    }

    @Override
    public void sendConversationCreated(String conversationId, String title, boolean isTemporary) {
        if (!isStreamProcessing()) return;

        this.conversationId = conversationId;

        // Publish title event (with retry)
        subscribeWithRetry(
                pubSubService.publishTitleUpdated(streamId, conversationId, title),
                "publishTitleCreated"
        );

        log.debug("📢 [REDIS STREAMING] Conversation created: {} - {}", conversationId, title);
    }

    @Override
    public void sendTitleUpdated(String conversationId, String title, boolean isTemporary) {
        if (!isStreamProcessing()) return;

        // Publish title event (with retry)
        subscribeWithRetry(
                pubSubService.publishTitleUpdated(streamId, conversationId, title),
                "publishTitleUpdated"
        );

        log.debug("📢 [REDIS STREAMING] Title updated: {} - {}", conversationId, title);
    }

    @Override
    public void sendUserMessage(String conversationId, Map<String, Object> userMessage) {
        if (!isStreamProcessing()) return;

        // User message is stored in DB, not streamed to client
        log.debug("📢 [REDIS STREAMING] User message recorded for conversation: {}", conversationId);
    }

    @Override
    public void sendToolCall(String toolName, String toolCallId, String arguments,
                             String thinkingMessage, String model, String provider, String userId) {
        if (!isStreamProcessing()) {
            log.warn("⚠️ [REDIS STREAMING] sendToolCall ignored - stream not processing! tool={}", toolName);
            return;
        }

        Map<String, Object> args = new java.util.HashMap<>();
        args.put("raw", arguments != null ? arguments : "");
        if (thinkingMessage != null) {
            args.put("thinking", thinkingMessage);
        }

        // Publish AND store tool call event (for reconnection replay)
        // Use BLOCKING to ensure tool is stored before continuing (critical for reconnection)
        executeBlocking(
                pubSubService.publishAndStoreToolEvent(streamId, StreamEvent.toolCall(streamId, toolName, toolCallId, args))
                        .doOnSuccess(receivers -> log.info("📢 [REDIS STREAMING] Published+stored tool_call - tool: {} ({})", toolName, toolCallId)),
                "publishAndStoreToolCall",
                Duration.ofSeconds(2)
        );

        log.info("📢 [REDIS STREAMING] Tool call: {} ({}) - {}", toolName, toolCallId, thinkingMessage);
    }

    @Override
    public void sendToolResult(String toolName, String toolId, boolean success, Long durationMs,
                               String resultId, String error,
                               String visualizationType, String visualizationId, String visualizationTitle,
                               String visualizationRunId, Integer visualizationRunIndex,
                               String iconSlug, String displayToolName, String label,
                               Map<String, Object> serviceApproval,
                               String model, String provider, String userId, String conversationId) {
        if (!isStreamProcessing()) {
            log.warn("⚠️ [REDIS STREAMING] sendToolResult ignored - stream not processing! tool={}", toolName);
            return;
        }

        // Create visualization record if present
        StreamEvent.Visualization visualization = null;
        if (visualizationType != null && visualizationId != null) {
            // Use full constructor for workflow_run (with runId), short constructor for others
            if (visualizationRunId != null) {
                visualization = new StreamEvent.Visualization(
                    visualizationType, visualizationId, visualizationTitle,
                    visualizationRunId, visualizationRunIndex
                );
            } else {
                visualization = new StreamEvent.Visualization(visualizationType, visualizationId, visualizationTitle);
            }
        }

        // Publish AND store tool result event (for reconnection replay)
        // Use BLOCKING to ensure tool result is stored before continuing (critical for reconnection)
        executeBlocking(
                pubSubService.publishAndStoreToolEvent(streamId, StreamEvent.toolResult(
                        streamId, toolId, toolName, success, durationMs, resultId, error, visualization,
                        iconSlug, displayToolName, label, serviceApproval))
                        .doOnSuccess(receivers -> log.info("📢 [REDIS STREAMING] Published+stored tool_result - tool: {} success={} resultId={} viz={} icon={} label={} svcApproval={}",
                                toolName, success, resultId, visualizationType, iconSlug, label, serviceApproval != null)),
                "publishAndStoreToolResult",
                Duration.ofSeconds(2)
        );

        log.info("📢 [REDIS STREAMING] Tool result: {} - {} ({}ms) resultId={} viz={} runId={} icon={} label={} svcApproval={}",
            toolName, success ? "success" : "failed", durationMs, resultId, visualizationType, visualizationRunId, iconSlug, label, serviceApproval != null);
    }

    @Override
    public void sendCredentialRequired(String credentialType, String toolName, String toolId) {
        if (!isStreamProcessing()) return;

        // Publish credential required event (with retry)
        subscribeWithRetry(
                pubSubService.publish(streamId, StreamEvent.credentialRequired(streamId, credentialType, toolName, toolId)),
                "publishCredentialRequired"
        );

        log.info("📢 [REDIS STREAMING] Credential required: {} for tool {}", credentialType, toolName);
    }

    @Override
    public void sendServiceApprovalRequired(List<ServiceApprovalInfo> services, String reason, boolean needsAttention) {
        if (!isStreamProcessing()) return;

        // Publish service approval required event (with retry)
        subscribeWithRetry(
                pubSubService.publish(streamId, StreamEvent.serviceApprovalRequired(streamId, services, reason, needsAttention)),
                "publishServiceApprovalRequired"
        );

        log.info("📢 [REDIS STREAMING] Service approval required: {} services - {}",
                services.size(),
                services.stream().map(ServiceApprovalInfo::serviceType).toList());
    }

    @Override
    public void sendAwaitingApproval(List<ServiceApprovalInfo> services) {
        if (!isProcessing.getAndSet(false)) {
            log.debug("📤 [REDIS STREAMING] Stream already completed - ignoring awaiting_approval");
            return;
        }

        String contentToSend = getCurrentContent();

        // Mark stream as awaiting_approval in Redis (special state, not completed)
        executeBlocking(
                stateService.setAwaitingApproval(streamId),
                "setAwaitingApprovalState",
                Duration.ofSeconds(2)
        );

        // Publish awaiting_approval event (with retry)
        subscribeWithRetry(
                pubSubService.publish(streamId, StreamEvent.awaitingApproval(streamId, contentToSend, services)),
                "publishAwaitingApproval"
        );

        log.info("⏸️ [REDIS STREAMING] Awaiting approval: {} services - {}",
                services.size(),
                services.stream().map(ServiceApprovalInfo::serviceType).toList());
    }

    @Override
    public void sendPendingActionCancelled(String reason, String message) {
        // Publish pending_action_cancelled event (with retry)
        subscribeWithRetry(
                pubSubService.publish(streamId, StreamEvent.pendingActionCancelled(streamId, reason, message)),
                "publishPendingActionCancelled"
        );

        log.info("🔔 [REDIS STREAMING] Pending action cancelled - reason: {}, message: {}", reason, message);
    }

    @Override
    public void sendDone(String fullResponse, String model, String provider,
                         String userId, String conversationId) {
        if (!isProcessing.getAndSet(false)) {
            log.debug("📤 [REDIS STREAMING] Stream already completed - ignoring done");
            return;
        }

        // Prefer the fullResponse param (actual LLM content) over local buffer.
        // For remote/bridge flows, content is streamed directly to Redis,
        // so the local contentBuffer is never populated.
        String contentToSend = (fullResponse != null && !fullResponse.isEmpty())
                ? fullResponse
                : getCurrentContent();
        int totalTokens = contentToSend.length() / 4; // Rough estimate

        // Mark stream as completed in Redis (CRITICAL - blocking)
        executeBlocking(
                stateService.complete(streamId),
                "completeState",
                Duration.ofSeconds(2)
        );

        // Publish completion event (CRITICAL - blocking)
        executeBlocking(
                pubSubService.publishComplete(streamId, contentToSend, totalTokens),
                "publishComplete",
                Duration.ofSeconds(2)
        );

        log.info("✅ [REDIS STREAMING] Stream completed: {} ({} chars)", streamId, contentToSend.length());
    }

    @Override
    public void sendDoneSimple(String model, String provider, String userId,
                               String conversationId, String streamId) {
        sendDone(getCurrentContent(), model, provider, userId, conversationId);
    }

    @Override
    public void sendError(String errorMessage) {
        if (!isProcessing.getAndSet(false)) {
            log.debug("📤 [REDIS STREAMING] Stream already stopped - ignoring error");
            return;
        }

        // Mark stream as error in Redis (CRITICAL - blocking)
        executeBlocking(
                stateService.error(streamId, errorMessage),
                "errorState",
                Duration.ofSeconds(2)
        );

        // Publish error event (CRITICAL - blocking)
        executeBlocking(
                pubSubService.publishError(streamId, errorMessage, "STREAM_ERROR", true),
                "publishError",
                Duration.ofSeconds(2)
        );

        log.error("❌ [REDIS STREAMING] Stream error: {} - {}", streamId, errorMessage);
    }

    @Override
    public void sendError(String errorMessage, String model, String provider,
                          String userId, String conversationId) {
        sendError(errorMessage);
    }

    @Override
    public void stop() {
        if (!isProcessing.getAndSet(false)) {
            log.debug("🛑 [REDIS STREAMING] Stream already stopped");
            return;
        }

        String partialContent = getCurrentContent();

        // Mark stream as stopped in Redis (CRITICAL - blocking)
        executeBlocking(
                stateService.stop(streamId),
                "stopState",
                Duration.ofSeconds(2)
        );

        // Publish stopped event (CRITICAL - blocking)
        executeBlocking(
                pubSubService.publishStopped(streamId, partialContent),
                "publishStopped",
                Duration.ofSeconds(2)
        );

        log.info("🛑 [REDIS STREAMING] Stream stopped: {} ({} chars)", streamId, partialContent.length());
    }

    @Override
    public String getCurrentStreamId() {
        return streamId;
    }

    @Override
    public String getCurrentContent() {
        synchronized (contentLock) {
            return contentBuffer.toString();
        }
    }

    @Override
    public void handleNaturalEnd(String fullContent, String conversationId) {
        log.info("✅ [REDIS STREAMING] Natural end of stream: {}", streamId);
        // State transition handled by sendDone()
    }

    @Override
    public void handleStreamEnd(String errorMessage, String conversationId) {
        log.info("🔄 [REDIS STREAMING] Stream end: {} - {}", streamId, errorMessage);
        // State transition handled by sendError() or stop()
    }

    @Override
    public boolean isActive() {
        return isProcessing.get();
    }

    @Override
    public void updateConversationId(String newConversationId) {
        this.conversationId = newConversationId;

        // Update in Redis (with retry)
        subscribeWithRetry(
                stateService.updateConversationId(streamId, newConversationId)
                        .doOnSuccess(success -> log.info("✅ [REDIS STREAMING] Updated conversationId: {} -> {}",
                                streamId, newConversationId)),
                "updateConversationIdLate"
        );
    }

    /**
     * Gets the conversation ID for this stream.
     */
    public String getConversationId() {
        return conversationId;
    }

    /**
     * Gets the model for this stream.
     */
    public String getModel() {
        return model;
    }

    /**
     * Gets the provider for this stream.
     */
    public String getProvider() {
        return provider;
    }
}
