package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.conversation.client.StreamRedisKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * StreamingCallback implementation that publishes agent execution events to Redis
 * in the **conversation format** (flat JSON events on stream:events:{streamId}
 * and ws:conversation:{conversationId} channels).
 *
 * This mirrors the event format produced by conversation-service's StreamPubSubService
 * and RedisStreamingOutput, so the frontend SSE/WebSocket bridge picks them up
 * identically to local execution.
 *
 * Features ported from AgentStreamingCallbackFactory (conversation-service local mode):
 * - Thinking section parsing (split by **title** pattern)
 * - Service approval detection (approval_needed + request_credential)
 * - shouldStop() via flag (service approval) + Redis cancel key (user disconnect)
 * - Ordered entries tracking (for response enrichment → DB persistence)
 *
 * Used when streamingFormat="conversation" in AgentExecutionRequestDto.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationRedisStreamingCallback {

    private static final String STREAM_CHANNEL_PREFIX = "stream:events:";
    private static final String WS_CHANNEL_PREFIX = "ws:conversation:";
    private static final String CANCEL_KEY_PREFIX = "agent:cancel:";

    /** Regex to find **title** patterns in thinking text */
    private static final Pattern THINKING_TITLE_PATTERN = Pattern.compile("\\*\\*[^*]+\\*\\*");

    private final StringRedisTemplate redisTemplate;
    private final com.apimarketplace.common.event.EventBus eventBus;
    private final ObjectMapper objectMapper;
    private final ConversationClient conversationClient;
    private final ActiveStreamRegistry activeStreamRegistry;

    /**
     * Create a callback for a specific conversation execution (no stream_started emission).
     * Prefer {@link #forExecution(String, String, String)} for the remote-agent path -
     * that variant emits the stream_started event needed to flip the frontend's
     * streaming state on.
     */
    public ConversationCallback forExecution(String streamId, String conversationId) {
        setConversationStreamIndex(conversationId, streamId);
        registerStreamInConversationService(streamId, conversationId, null);
        return registerForDrain(
            new ConversationCallback(streamId, conversationId, null, null, null, null, null));
    }

    /**
     * Create a callback for a specific conversation execution and emit stream_started.
     *
     * The frontend's {@code detectStreamEventType} flags an event as {@code stream_started}
     * when it carries both {@code model} and {@code conversationId}. Without this emission,
     * {@code ConversationPanelContent}'s reducer never flips {@code isStreaming=true}, so
     * MessageHistory renders every tool card as final ("Done") even while tokens stream.
     *
     * @param streamId       stream ID (SSE channel key)
     * @param conversationId conversation ID (WebSocket channel key)
     * @param model          LLM model name (nullable - falls back to "unknown")
     */
    public ConversationCallback forExecution(String streamId, String conversationId, String model) {
        setConversationStreamIndex(conversationId, streamId);
        registerStreamInConversationService(streamId, conversationId, model);
        publishStreamStarted(streamId, conversationId, model);
        return registerForDrain(
            new ConversationCallback(streamId, conversationId, null, null, null, null, null));
    }

    /**
     * Create a callback with parent-forwarding for sub-agent real-time visibility
     * (no stream_started emission). Prefer the overload that accepts {@code model}.
     */
    public ConversationCallback forExecution(String streamId, String conversationId,
                                              String parentConversationId, String subAgentName,
                                              String subAgentAvatarUrl, String subAgentId,
                                              String workflowRunId) {
        setConversationStreamIndex(conversationId, streamId);
        registerStreamInConversationService(streamId, conversationId, null);
        return registerForDrain(new ConversationCallback(streamId, conversationId,
            parentConversationId, subAgentName, subAgentAvatarUrl, subAgentId, workflowRunId));
    }

    /**
     * Create a callback with parent-forwarding for sub-agent real-time visibility, emitting
     * a stream_started event. See {@link #forExecution(String, String, String)} for rationale.
     *
     * @param streamId              stream ID (SSE channel key)
     * @param conversationId        conversation ID (WebSocket channel key)
     * @param model                 LLM model name (nullable - falls back to "unknown")
     * @param parentConversationId  parent's conversation ID for event forwarding (nullable)
     * @param subAgentName          sub-agent display name (nullable)
     * @param subAgentAvatarUrl     sub-agent avatar URL (nullable)
     * @param subAgentId            sub-agent entity ID (nullable)
     * @param workflowRunId         workflow run ID for cancel propagation (nullable)
     */
    public ConversationCallback forExecution(String streamId, String conversationId, String model,
                                              String parentConversationId, String subAgentName,
                                              String subAgentAvatarUrl, String subAgentId,
                                              String workflowRunId) {
        setConversationStreamIndex(conversationId, streamId);
        registerStreamInConversationService(streamId, conversationId, model);
        publishStreamStarted(streamId, conversationId, model);
        return registerForDrain(new ConversationCallback(streamId, conversationId,
            parentConversationId, subAgentName, subAgentAvatarUrl, subAgentId, workflowRunId));
    }

    /**
     * Register the callback in {@link ActiveStreamRegistry} so a shutdown drain expiry can
     * finalize it as INTERRUPTED (and so its liveness heartbeat starts immediately).
     * The handle is removed again on every terminal path (onComplete / onError /
     * interruptFromShutdown).
     */
    private ConversationCallback registerForDrain(ConversationCallback callback) {
        activeStreamRegistry.register(callback.streamId, callback::interruptFromShutdown);
        return callback;
    }

    /**
     * Emit a stream_started-shaped event on the SSE and WS channels so the frontend
     * flips into streaming mode. Fields match what ChatStreamInitializer emits on the
     * local path: {streamId, conversationId, model, timestamp}.
     */
    private void publishStreamStarted(String streamId, String conversationId, String model) {
        if (streamId == null || conversationId == null) return;
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("streamId", streamId);
            event.put("conversationId", conversationId);
            event.put("model", model != null ? model : "unknown");
            event.put("timestamp", Instant.now().toString());
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(STREAM_CHANNEL_PREFIX + streamId, json);
            eventBus.publish(WS_CHANNEL_PREFIX + conversationId, json);
        } catch (Exception e) {
            log.warn("[CONV_STREAM] Failed to publish stream_started for {}: {}", streamId, e.getMessage());
        }
    }

    /**
     * Set the stream:conv:{conversationId} → streamId index in Redis.
     * This enables stop signal propagation: parent stop → lookup parent streamId → check cancel key.
     * Each conversation maps to its active streamId so nested sub-agents can find their parent's stream.
     */
    private void setConversationStreamIndex(String conversationId, String streamId) {
        if (conversationId != null && streamId != null) {
            try {
                redisTemplate.opsForValue().set(
                    StreamRedisKeys.convIndexKey(conversationId), streamId, StreamRedisKeys.STREAM_TTL);
                log.debug("[CONV_STREAM] Set conv index: {} → {}", conversationId, streamId);
            } catch (Exception e) {
                log.warn("[CONV_STREAM] Failed to set conv index for {}: {}", conversationId, e.getMessage());
            }
        }
    }

    /**
     * Register stream metadata in conversation-service via HTTP so the snapshot
     * endpoint can find and replay this stream's accumulated state.
     *
     * @param model the LLM model name when the {@code forExecution} variant knows it
     *              (nullable - falls back to "unknown" for the no-model variants)
     */
    private void registerStreamInConversationService(String streamId, String conversationId, String model) {
        if (streamId == null || conversationId == null) return;
        try {
            conversationClient.registerStream(streamId, conversationId,
                model != null ? model : "unknown", "remote-agent");
        } catch (Exception e) {
            log.warn("[CONV_STREAM] Failed to register stream in conversation-service: {}", e.getMessage());
        }
    }

    /**
     * Per-execution streaming callback that publishes conversation-format events to Redis.
     * Tracks thinking sections, service approvals, and ordered entries for response enrichment.
     */
    public class ConversationCallback implements StreamingCallback {

        private final String streamId;
        private final String conversationId;
        private final String sseChannel;
        private final String wsChannel;

        // Parent-forwarding for sub-agent real-time visibility
        private final String parentConversationId;
        private final String parentWsChannel;
        private final String subAgentName;
        private final String subAgentAvatarUrl;
        private final String subAgentId;

        // Workflow cancel propagation (for sub-agents spawned from workflow context)
        private final String workflowRunId;

        // Thinking section tracking (mirrors AgentStreamingCallbackFactory)
        private final StringBuilder thinkingBuffer = new StringBuilder();
        private int thinkingSessionCount = 0;
        private final List<Map<String, Object>> thinkingSections = new ArrayList<>();

        // Ordered entries: chronological thinking + tool call entries (for toolCalls JSON)
        private final List<Map<String, Object>> orderedEntries = new ArrayList<>();

        // Approval/authorization cards already emitted this turn. Deduped so an agent that
        // retries a gated call does not spam identical cards, while distinct services/rules
        // each still raise their own card. Async model: emitting a card NEVER pauses the run.
        private final Set<String> emittedApprovalKeys = new HashSet<>();

        // Stop control
        private volatile boolean stopped = false;
        private int shouldStopCheckCount = 0;

        // Cancel-poll throttle: shouldStop() is invoked on EVERY iteration of the LLM
        // stream-read loop, which can spin extremely fast on an empty/thinking flood.
        // Without throttling this issues several Redis round-trips per iteration and can
        // saturate the shared Redis (platform-wide latency spike when a user hits STOP).
        // We poll Redis at most once per CANCEL_POLL_INTERVAL_MS; lastCancelPollAt starts
        // at 0 so the very first call always polls.
        // THREAD-CONFINEMENT: lastCancelPollAt / nowMillis / shouldStopCheckCount are
        // accessed ONLY from the single stream-read thread that drives shouldStop() for
        // this callback (the rate limiter polls it synchronously on that same thread).
        // They are deliberately plain (non-volatile) fields. A planned STOP is still never
        // missed cross-thread because that flips the `volatile stopped` flag, which is read
        // first. If a future change polls shouldStop() from another thread, revisit this.
        private static final long CANCEL_POLL_INTERVAL_MS = 250;
        private long lastCancelPollAt = 0L;
        private long cancelPollIntervalMs = CANCEL_POLL_INTERVAL_MS;
        private java.util.function.LongSupplier nowMillis = System::currentTimeMillis;

        /** Test hook: override the poll interval and clock to exercise the throttle deterministically. */
        void configureCancelPollForTest(long intervalMs, java.util.function.LongSupplier clock) {
            this.cancelPollIntervalMs = intervalMs;
            this.nowMillis = clock;
        }

        // Graceful drain: guards interruptFromShutdown() so a shutdown interruption is
        // emitted/finalized exactly once even if interruptAll() races a normal completion.
        private final java.util.concurrent.atomic.AtomicBoolean shutdownInterrupted =
            new java.util.concurrent.atomic.AtomicBoolean(false);

        // Timing
        private final long streamStartTime = System.currentTimeMillis();

        ConversationCallback(String streamId, String conversationId,
                             String parentConversationId, String subAgentName,
                             String subAgentAvatarUrl, String subAgentId,
                             String workflowRunId) {
            this.streamId = streamId;
            this.conversationId = conversationId;
            this.sseChannel = STREAM_CHANNEL_PREFIX + streamId;
            this.wsChannel = conversationId != null ? WS_CHANNEL_PREFIX + conversationId : null;
            this.parentConversationId = parentConversationId;
            this.parentWsChannel = parentConversationId != null ? WS_CHANNEL_PREFIX + parentConversationId : null;
            this.subAgentName = subAgentName;
            this.subAgentAvatarUrl = subAgentAvatarUrl;
            this.subAgentId = subAgentId;
            this.workflowRunId = workflowRunId;
        }

        @Override
        public void onChunk(String content) {
            // Conversation format: ContentChunk
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("streamId", streamId);
            event.put("content", content);
            event.put("timestamp", Instant.now().toString());
            publish(event);

            // Accumulate content in Redis List for stop handler and snapshot replay
            try {
                String key = StreamRedisKeys.contentKey(streamId);
                redisTemplate.opsForList().rightPush(key, content);
                redisTemplate.opsForList().trim(key, -StreamRedisKeys.MAX_CONTENT_CHUNKS, -1);
                redisTemplate.expire(key, StreamRedisKeys.STREAM_TTL);
            } catch (Exception e) {
                log.warn("[CONV_STREAM] Failed to accumulate content chunk: {}", e.getMessage());
            }

            // Forward to parent conversation for real-time sub-agent visibility
            publishToParent("sub_agent_content", Map.of("content", content));
        }

        @Override
        public void onThinking(String thinking) {
            if (thinking == null || thinking.isEmpty()) return;

            // Send raw thinking chunk for real-time display (before sections parsed)
            Map<String, Object> rawEvent = new LinkedHashMap<>();
            rawEvent.put("streamId", streamId);
            rawEvent.put("thinking", thinking);
            rawEvent.put("timestamp", Instant.now().toString());
            publish(rawEvent);

            // Accumulate in buffer and emit complete sections
            thinkingBuffer.append(thinking);
            sendCompleteSectionsFromBuffer();

            // Forward to parent conversation for real-time sub-agent visibility
            publishToParent("sub_agent_thinking", Map.of("thinking", thinking));
        }

        @Override
        public void onToolCall(ToolCall toolCall) {
            // Flush thinking buffer before tool call (mirrors local behavior)
            flushThinkingBuffer();

            // Track in ordered entries
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", "tool_call");
            entry.put("id", toolCall.id());
            entry.put("toolName", toolCall.toolName());
            entry.put("arguments", toolCall.arguments());
            entry.put("timestamp", System.currentTimeMillis());
            orderedEntries.add(entry);

            // Conversation format: ToolCall
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("streamId", streamId);
            event.put("toolName", toolCall.toolName());
            event.put("toolId", toolCall.id());
            event.put("arguments", toolCall.arguments());
            event.put("timestamp", Instant.now().toString());
            publish(event);

            // Buffer tool event for snapshot replay
            appendToolEvent(event);

            // Forward to parent conversation for real-time sub-agent visibility
            Map<String, Object> parentPayload = new LinkedHashMap<>();
            parentPayload.put("toolName", toolCall.toolName());
            parentPayload.put("toolId", toolCall.id());
            parentPayload.put("arguments", toolCall.arguments());
            publishToParent("sub_agent_tool_call", parentPayload);
        }

        @Override
        public void onToolResult(ToolResult result) {
            String toolName = result.toolCall() != null ? result.toolCall().toolName() : null;
            String toolCallId = result.toolCall() != null ? result.toolCall().id() : null;

            // Detect a credential/service-approval or tool-authorization request carried by this
            // tool result and emit the matching card event LIVE - WITHOUT pausing the agent loop.
            // Async model: the run keeps going, the card appears mid-stream, and several cards can
            // be raised in a single turn (deduped by key). Previously each match hard-stopped the
            // loop (stopped=true, streamCompletedEarly=true) so the card only surfaced as the turn
            // ended and at most one showed per turn.
            emitApprovalCardsIfPresent(result, toolName);

            // Normal tool result: publish event
            publishToolResultEvent(result);

            // Forward to parent conversation for real-time sub-agent visibility
            Map<String, Object> parentPayload = new LinkedHashMap<>();
            parentPayload.put("toolName", toolName);
            parentPayload.put("toolId", toolCallId);
            parentPayload.put("success", result.success());
            if (result.durationMs() != null) parentPayload.put("durationMs", result.durationMs());
            publishToParent("sub_agent_tool_result", parentPayload);
        }

        /**
         * Emit the approval/authorization card event for this tool result if it carries one,
         * WITHOUT pausing the run (async). Deduped by a per-turn key so a retried gated call
         * does not spam identical cards while distinct services/rules each raise their own.
         * Three shapes are recognised, mirroring {@code persistPendingActionIfNeeded}:
         * <ol>
         *   <li>{@code request_credential} with {@code serviceApprovalRequested} metadata,</li>
         *   <li>any result with {@code toolAuthorizationRequired} metadata (ToolAuthorizationGuard),</li>
         *   <li>{@code approval_needed} JSON in the content (soft credential warning).</li>
         * </ol>
         */
        @SuppressWarnings("unchecked")
        private void emitApprovalCardsIfPresent(ToolResult result, String toolName) {
            if (!result.success()) return;
            Map<String, Object> metadata = result.metadata();

            // 1) Service approval requested via request_credential metadata.
            if (metadata != null && Boolean.TRUE.equals(metadata.get("serviceApprovalRequested"))) {
                List<Map<String, Object>> services = metadata.get("services") instanceof List<?> l
                    ? (List<Map<String, Object>>) l : List.of();
                if (!services.isEmpty()) {
                    List<Map<String, Object>> approvalInfos = toApprovalInfos(services);
                    boolean needsAttention = Boolean.TRUE.equals(metadata.get("needsAttention"));
                    if (markEmitted("svc:" + (needsAttention ? "attention:" : "connect:") + serviceKey(approvalInfos))) {
                        publishServiceApprovalRequired(approvalInfos,
                            (String) metadata.get("reason"),
                            needsAttention);
                    }
                    return;
                }
            }

            // 2) Sensitive action gated by ToolAuthorizationPolicy (any tool).
            if (metadata != null && Boolean.TRUE.equals(metadata.get("toolAuthorizationRequired"))) {
                String rule = (String) metadata.get("rule");
                if (rule != null && !rule.isBlank() && markEmitted("auth:" + rule)) {
                    publishToolAuthorizationRequired(metadata);
                }
                return;
            }

            // 3) Soft "approval_needed" JSON returned in the tool content (catalog credential miss).
            String content = result.content();
            if (content != null && content.contains("approval_needed")) {
                try {
                    Map<String, Object> contentMap = objectMapper.readValue(content, Map.class);
                    if ("approval_needed".equals(contentMap.get("status"))) {
                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("serviceType", contentMap.get("serviceType"));
                        info.put("serviceName", contentMap.get("serviceName"));
                        info.put("iconSlug", contentMap.get("iconSlug"));
                        info.put("toolName", contentMap.get("toolName"));
                        info.put("toolId", contentMap.get("toolId"));
                        info.put("description", contentMap.get("message"));
                        if (markEmitted("svc:connect:" + serviceKey(List.of(info)))) {
                            publishServiceApprovalRequired(List.of(info),
                                (String) contentMap.get("message"), false);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Tool result content not JSON for approval check: {}", e.getMessage());
                }
            }
        }

        /** Convert service metadata maps into the approval-info shape the card consumes. */
        private List<Map<String, Object>> toApprovalInfos(List<Map<String, Object>> services) {
            List<Map<String, Object>> infos = new ArrayList<>();
            for (Map<String, Object> svc : services) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("serviceType", svc.get("serviceType"));
                info.put("serviceName", svc.get("serviceName"));
                info.put("iconSlug", svc.get("iconSlug"));
                info.put("toolName", svc.get("toolName"));
                info.put("toolId", svc.get("toolId"));
                info.put("description", svc.get("description"));
                infos.add(info);
            }
            return infos;
        }

        /** Record a card key; returns true the first time it is seen this turn. */
        private boolean markEmitted(String key) {
            return emittedApprovalKeys.add(key);
        }

        /** Stable signature for a set of services (sorted serviceTypes). */
        private static String serviceKey(List<Map<String, Object>> services) {
            return services.stream()
                .map(s -> String.valueOf(s.get("serviceType")))
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        }

        @Override
        public void onComplete(CompletionResponse response) {
            // Flush any remaining thinking buffer
            flushThinkingBuffer();

            // Approval/authorization cards are emitted live per tool result (async model) - no
            // end-of-turn safety net or early-completion skip: the run always finishes normally.

            // Conversation format: StreamCompleted
            String content = response != null ? response.content() : "";
            int totalTokens = 0;
            if (response != null && response.usage() != null) {
                totalTokens = response.usage().getTotal();
            }
            publishDoneEvent(content, totalTokens);

            // Finalize stream in conversation-service (marks as COMPLETED for snapshot)
            finalizeStreamInConversationService("COMPLETED");

            // Terminal path: stream no longer needs a shutdown handle nor a liveness heartbeat
            activeStreamRegistry.unregister(streamId);
        }

        @Override
        public void onError(String error) {
            // Conversation format: StreamError
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("streamId", streamId);
            event.put("error", error);
            event.put("errorCode", "STREAM_ERROR");
            event.put("retryable", true);
            event.put("timestamp", Instant.now().toString());
            publish(event);

            // Finalize stream in conversation-service (marks as ERROR for snapshot)
            finalizeStreamInConversationService("ERROR");

            // Terminal path: stream no longer needs a shutdown handle nor a liveness heartbeat
            activeStreamRegistry.unregister(streamId);
        }

        /**
         * Graceful-drain interruption (instance shutting down, drain window expired).
         * Publishes a lightweight error event (same shape as {@link #onError(String)},
         * errorCode {@code INTERRUPTED}, retryable) so any connected client sees why the
         * stream ended, then finalizes the stream as INTERRUPTED in conversation-service -
         * which persists the partial content accumulated so far - and unregisters the
         * stream. Idempotent: invoked at most once even if {@code interruptAll()} races a
         * normal completion or is called twice.
         */
        public void interruptFromShutdown() {
            if (!shutdownInterrupted.compareAndSet(false, true)) {
                return;
            }
            log.info("[CONV_STREAM] Interrupting stream {} for service shutdown", streamId);

            // Lightweight stream error event - same shape as onError, dedicated error code
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("streamId", streamId);
            event.put("error", "Service restarting - partial response saved");
            event.put("errorCode", "INTERRUPTED");
            event.put("retryable", true);
            event.put("timestamp", Instant.now().toString());
            publish(event);

            // INTERRUPTED makes conversation-service persist the partial content to DB
            finalizeStreamInConversationService("INTERRUPTED");

            activeStreamRegistry.unregister(streamId);
        }

        @Override
        public boolean shouldStop() {
            // Check local flag (set by service approval detection or previous stop)
            if (stopped) return true;

            shouldStopCheckCount++;

            // Throttle Redis polling: shouldStop() fires on every stream-read iteration and
            // can be called thousands of times per second. Poll Redis at most once per
            // cancelPollIntervalMs; rapid successive calls within the window are skipped.
            long now = nowMillis.getAsLong();
            if (now - lastCancelPollAt < cancelPollIntervalMs) {
                return false;
            }
            lastCancelPollAt = now;

            try {
                // Check own cancel key (set by conversation-service on user disconnect).
                // Single GET: a StringRedisTemplate GET returns the value when the key
                // exists and null otherwise - no deserialization edge - so one call both
                // detects and dedups what used to be a hasKey + get fallback.
                String cancelKey = CANCEL_KEY_PREFIX + streamId;
                String cancelValue = redisTemplate.opsForValue().get(cancelKey);
                if (cancelValue != null) {
                    log.info("[CONV_STREAM] Stop signal detected for streamId={} (check #{})",
                        streamId, shouldStopCheckCount);
                    stopped = true;
                    return true;
                }

                // Sub-agent stop propagation: check parent's cancel key
                // Chain: stream:conv:{parentConversationId} → parentStreamId → agent:cancel:{parentStreamId}
                if (parentConversationId != null) {
                    String parentStreamId = redisTemplate.opsForValue()
                        .get(StreamRedisKeys.convIndexKey(parentConversationId));
                    if (parentStreamId != null) {
                        String parentCancelValue = redisTemplate.opsForValue()
                            .get(CANCEL_KEY_PREFIX + parentStreamId);
                        if (parentCancelValue != null) {
                            log.info("[CONV_STREAM] Parent stop propagated: parentConv={}, parentStream={}, subStream={}",
                                parentConversationId, parentStreamId, streamId);
                            stopped = true;
                            return true;
                        }
                    }
                }

                // Workflow cancel propagation: check workflow:cancel:{workflowRunId}
                // Set by orchestrator when workflow is cancelled/stopped
                if (workflowRunId != null) {
                    String workflowCancelValue = redisTemplate.opsForValue()
                        .get("workflow:cancel:" + workflowRunId);
                    if (workflowCancelValue != null) {
                        log.info("[CONV_STREAM] Workflow cancel propagated: runId={}, subStream={}",
                            workflowRunId, streamId);
                        stopped = true;
                        return true;
                    }
                }
            } catch (Exception e) {
                log.warn("[CONV_STREAM] Error checking cancel key for streamId={}: {}", streamId, e.getMessage());
            }

            return false;
        }

        // ========== Thinking Section Parsing (mirrors AgentStreamingCallbackFactory) ==========

        /**
         * Parse and emit complete thinking sections during streaming.
         * A section is "complete" when at least 2 **title** patterns exist in the buffer,
         * meaning the text between the first and second title is a complete section.
         */
        private int sendCompleteSectionsFromBuffer() {
            String bufferText = thinkingBuffer.toString();
            Matcher matcher = THINKING_TITLE_PATTERN.matcher(bufferText);

            List<Integer> titlePositions = new ArrayList<>();
            while (matcher.find()) {
                titlePositions.add(matcher.start());
            }

            if (titlePositions.size() < 2) {
                return 0; // Not enough titles to extract a complete section
            }

            // Extract text up to the last title as complete content
            int lastTitleStart = titlePositions.get(titlePositions.size() - 1);
            String completeText = bufferText.substring(0, lastTitleStart);
            String remaining = bufferText.substring(lastTitleStart);

            // Replace buffer with remaining text
            thinkingBuffer.setLength(0);
            thinkingBuffer.append(remaining);

            // Parse and emit complete sections
            List<Map<String, String>> sections = parseThinkingSections(completeText);
            int sectionsSent = 0;
            for (Map<String, String> section : sections) {
                String title = section.get("title");
                String content = section.get("content");
                if (content == null || content.isBlank()) continue;

                // Track for response enrichment
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", "thinking");
                entry.put("title", title);
                entry.put("content", content);
                entry.put("timestamp", System.currentTimeMillis());
                entry.put("sessionId", thinkingSessionCount++);
                orderedEntries.add(entry);

                // Track for persistence
                Map<String, Object> sectionData = new LinkedHashMap<>();
                sectionData.put("title", title);
                sectionData.put("content", content);
                thinkingSections.add(sectionData);

                // Publish thinking_section event
                // Frontend detects via: 'title' in data && 'content' in data
                publishThinkingSection(title, content);
                sectionsSent++;
            }
            return sectionsSent;
        }

        /**
         * Flush any remaining thinking text from the buffer.
         * Called before tool calls and at completion.
         */
        private void flushThinkingBuffer() {
            if (thinkingBuffer.isEmpty()) return;

            String text = thinkingBuffer.toString().trim();
            thinkingBuffer.setLength(0);

            if (text.isEmpty()) return;

            List<Map<String, String>> sections = parseThinkingSections(text);
            for (Map<String, String> section : sections) {
                String title = section.get("title");
                String content = section.get("content");
                if (content == null || content.isBlank()) continue;

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", "thinking");
                entry.put("title", title);
                entry.put("content", content);
                entry.put("timestamp", System.currentTimeMillis());
                entry.put("sessionId", thinkingSessionCount++);
                orderedEntries.add(entry);

                Map<String, Object> sectionData = new LinkedHashMap<>();
                sectionData.put("title", title);
                sectionData.put("content", content);
                thinkingSections.add(sectionData);

                publishThinkingSection(title, content);
            }
        }

        /**
         * Parse thinking text into title-delimited sections.
         * Splits by **title** regex pattern, same as AgentStreamingCallbackFactory.
         */
        private List<Map<String, String>> parseThinkingSections(String text) {
            List<Map<String, String>> sections = new ArrayList<>();
            Matcher matcher = THINKING_TITLE_PATTERN.matcher(text);

            List<String> titles = new ArrayList<>();
            List<Integer> starts = new ArrayList<>();
            while (matcher.find()) {
                titles.add(matcher.group().replaceAll("\\*\\*", "").trim());
                starts.add(matcher.start());
            }

            if (titles.isEmpty()) {
                // No titles found - return whole text as single section
                Map<String, String> section = new LinkedHashMap<>();
                section.put("title", "");
                section.put("content", text.trim());
                sections.add(section);
                return sections;
            }

            // Text before first title
            if (starts.get(0) > 0) {
                String beforeFirst = text.substring(0, starts.get(0)).trim();
                if (!beforeFirst.isEmpty()) {
                    Map<String, String> section = new LinkedHashMap<>();
                    section.put("title", "");
                    section.put("content", beforeFirst);
                    sections.add(section);
                }
            }

            // Each title + content up to next title
            for (int i = 0; i < titles.size(); i++) {
                int contentStart = text.indexOf("**", starts.get(i) + 2);
                contentStart = text.indexOf("**", contentStart) + 2; // skip closing **
                int contentEnd = (i + 1 < starts.size()) ? starts.get(i + 1) : text.length();
                String content = text.substring(Math.min(contentStart, text.length()),
                    Math.min(contentEnd, text.length())).trim();

                Map<String, String> section = new LinkedHashMap<>();
                section.put("title", titles.get(i));
                section.put("content", content);
                sections.add(section);
            }

            return sections;
        }

        // ========== Event Publishing Helpers ==========

        private void publishThinkingSection(String title, String content) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("streamId", streamId);
            event.put("title", title);
            event.put("content", content);
            event.put("timestamp", Instant.now().toString());
            publish(event);
        }

        private void publishToolResultEvent(ToolResult result) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("streamId", streamId);
            event.put("toolId", result.toolCall() != null ? result.toolCall().id() : null);
            event.put("toolName", result.toolCall() != null ? result.toolCall().toolName() : null);
            event.put("success", result.success());
            if (result.durationMs() != null) {
                event.put("durationMs", result.durationMs());
            }
            // resultId is null during remote streaming (tool results saved to DB after execution)
            // Include inline result content so frontend can display it without DB fetch
            event.put("resultId", null);
            if (result.content() != null) {
                event.put("result", result.content());
            } else if (result.error() != null) {
                event.put("result", result.error());
            }
            if (result.error() != null) {
                event.put("error", result.error());
            }
            // Pass through metadata fields for tool card display
            if (result.metadata() != null) {
                Map<String, Object> meta = result.metadata();
                if (meta.get("iconSlug") != null) {
                    event.put("iconSlug", meta.get("iconSlug"));
                }
                if (meta.get("toolName") != null) {
                    event.put("displayToolName", meta.get("toolName"));
                }
                if (meta.get("label") != null) {
                    event.put("label", meta.get("label"));
                }
                if (meta.get("visualization") != null) {
                    event.put("visualization", meta.get("visualization"));
                }
                if (meta.get("serviceApproval") != null) {
                    event.put("serviceApproval", meta.get("serviceApproval"));
                }
                if (meta.get("draftId") != null) {
                    event.put("draftId", meta.get("draftId"));
                }
                // Source-tool render cards (repo edit/write/diff + interface patch → diff;
                // repo git_status → gitStatus) for the remote (non-bridge) streaming path.
                if (meta.get("diff") != null) {
                    event.put("diff", meta.get("diff"));
                }
                if (meta.get("gitStatus") != null) {
                    event.put("gitStatus", meta.get("gitStatus"));
                }
            }
            event.put("timestamp", Instant.now().toString());
            publish(event);

            // Buffer tool result for snapshot replay
            appendToolEvent(event);
        }

        /**
         * Emit service_approval_required event.
         * Frontend detects via: 'services' in data && 'reason' in data
         */
        private void publishServiceApprovalRequired(List<Map<String, Object>> services,
                                                      String reason, boolean needsAttention) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("streamId", streamId);
            event.put("services", services);
            event.put("reason", reason != null ? reason : "Services need approval");
            event.put("needsAttention", needsAttention);
            event.put("timestamp", Instant.now().toString());
            publish(event);
        }

        /**
         * Emit tool_authorization_required event.
         * Frontend detects via: 'toolAuthorization' in data (distinct from the
         * service-approval discriminant, which is 'services' + 'reason').
         */
        private void publishToolAuthorizationRequired(Map<String, Object> metadata) {
            Map<String, Object> authorization = new LinkedHashMap<>();
            authorization.put("rule", metadata.get("rule"));
            authorization.put("toolName", metadata.get("toolName"));
            authorization.put("action", metadata.get("action"));
            authorization.put("toolCallId", metadata.get("toolCallId"));
            authorization.put("argsSummary", metadata.get("argsSummary"));
            // Only present for application:acquire - lets the card open the marketplace install
            // modal on the publication the user is about to install.
            authorization.put("applicationId", metadata.get("applicationId"));

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("streamId", streamId);
            event.put("toolAuthorization", authorization);
            event.put("timestamp", Instant.now().toString());
            publish(event);
        }

        private void publishDoneEvent(String content) {
            publishDoneEvent(content, 0);
        }

        private void publishDoneEvent(String content, int totalTokens) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("streamId", streamId);
            event.put("fullContent", content != null ? content : "");
            event.put("totalTokens", totalTokens);
            event.put("timestamp", Instant.now().toString());
            publish(event);
        }

        // ========== Data Extraction (for response enrichment) ==========

        /** Get parsed thinking sections for DB persistence */
        public List<Map<String, Object>> getThinkingSections() {
            return Collections.unmodifiableList(thinkingSections);
        }

        /** Get ordered entries (thinking + tool calls) for toolCalls JSON */
        public List<Map<String, Object>> getOrderedEntries() {
            return Collections.unmodifiableList(orderedEntries);
        }

        /** Stream start time for reasoning duration calculation */
        public long getStreamStartTime() {
            return streamStartTime;
        }

        /** Whether user/system stopped the execution */
        public boolean isStopped() {
            return stopped;
        }

        private void appendToolEvent(Map<String, Object> event) {
            try {
                String key = StreamRedisKeys.toolsKey(streamId);
                String json = objectMapper.writeValueAsString(event);
                redisTemplate.opsForList().rightPush(key, json);
                redisTemplate.opsForList().trim(key, -StreamRedisKeys.MAX_TOOL_EVENTS, -1);
                redisTemplate.expire(key, StreamRedisKeys.STREAM_TTL);
            } catch (Exception e) {
                log.warn("[CONV_STREAM] Failed to buffer tool event: {}", e.getMessage());
            }
        }

        private void finalizeStreamInConversationService(String terminalState) {
            try {
                conversationClient.finalizeStream(streamId, terminalState);
            } catch (Exception e) {
                log.warn("[CONV_STREAM] Failed to finalize stream {}: {}", streamId, e.getMessage());
            }
        }

        /**
         * Publish a sub-agent event to the parent's WebSocket conversation channel.
         * Includes sub-agent metadata (name, avatar, id) for frontend display.
         */
        private void publishToParent(String eventType, Map<String, Object> payload) {
            if (parentWsChannel == null) return;
            try {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("type", eventType);
                // Use nested "subAgent" object - matches ConversationEventPublisher format
                Map<String, Object> subAgentMeta = new LinkedHashMap<>();
                subAgentMeta.put("name", subAgentName);
                if (subAgentAvatarUrl != null) subAgentMeta.put("avatarUrl", subAgentAvatarUrl);
                if (subAgentId != null) subAgentMeta.put("agentId", subAgentId);
                event.put("subAgent", subAgentMeta);
                event.putAll(payload);
                event.put("timestamp", Instant.now().toString());
                String json = objectMapper.writeValueAsString(event);
                eventBus.publish(parentWsChannel, json);
                log.debug("[CONV_STREAM] Published {} to parent ws={}", eventType, parentWsChannel);
            } catch (Exception e) {
                log.warn("[CONV_STREAM] Failed to publish {} to parent: {}", eventType, e.getMessage());
            }
        }

        /**
         * Publish event JSON to both SSE and WebSocket Redis channels.
         */
        private void publish(Map<String, Object> event) {
            try {
                String json = objectMapper.writeValueAsString(event);

                // Publish to SSE channel (stream:events:{streamId})
                Long sseReceivers = redisTemplate.convertAndSend(sseChannel, json);

                // Publish to WebSocket channel (ws:conversation:{conversationId})
                Long wsReceivers = null;
                if (wsChannel != null) {
                    eventBus.publish(wsChannel, json);
                    wsReceivers = 1L; // EventBus doesn't return receiver count
                }

                // Determine event type for logging
                String eventType = event.containsKey("services") && event.containsKey("reason") ? "service_approval"
                    : event.containsKey("title") && event.containsKey("content")
                        && !event.containsKey("fullContent") ? "thinking_section"
                    : event.containsKey("content") && !event.containsKey("title") ? "content"
                    : event.containsKey("thinking") ? "thinking"
                    : event.containsKey("toolName") && event.containsKey("arguments") ? "tool_call"
                    : event.containsKey("success") ? "tool_result"
                    : event.containsKey("fullContent") ? "done"
                    : event.containsKey("error") ? "error"
                    : "unknown";
                log.info("[CONV_STREAM] Published {} - sse={} ({}recv), ws={} ({}recv)",
                    eventType, sseChannel, sseReceivers, wsChannel, wsReceivers);

            } catch (Exception e) {
                log.warn("[CONV_STREAM] Failed to publish event to Redis: {}", e.getMessage(), e);
            }
        }
    }
}
