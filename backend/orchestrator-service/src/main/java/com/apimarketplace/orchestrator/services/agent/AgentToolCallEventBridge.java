package com.apimarketplace.orchestrator.services.agent;

import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.events.AgentToolCallPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridge between shared-agent-lib's StreamingCallback and orchestrator's streaming event system.
 *
 * Implements StreamingCallback to receive real-time tool call notifications from the agent loop
 * and emits them as streaming events via WorkflowEventPublisher.
 *
 * Optionally also publishes events to the conversation's WebSocket channel via
 * ConversationEventPublisher for live updates in the conversation panel.
 *
 * Thread-safe: All operations are synchronous and atomic.
 */
public class AgentToolCallEventBridge implements StreamingCallback {

    private static final Logger log = LoggerFactory.getLogger(AgentToolCallEventBridge.class);
    private static final String WORKFLOW_CANCEL_PREFIX = "workflow:cancel:";
    private static final String AGENT_CANCEL_PREFIX = "agent:cancel:";

    private final String runId;
    private final String nodeId;
    private final int itemIndex;
    private final Integer iteration;
    private final WorkflowEventPublisher eventPublisher;

    // Optional: conversation streaming for live updates in conversation panel
    private final ConversationEventPublisher conversationPublisher;
    private final String conversationId;
    private final String streamId;

    // Optional: forward events to parent conversation for real-time sub-agent visibility
    private final String parentConversationId;
    private final String subAgentName;
    private final String subAgentAvatarUrl;
    private final String subAgentId;

    // Optional: Redis for stop signal detection
    private final StringRedisTemplate redisTemplate;

    // Stop signal: cached locally once detected to avoid repeated Redis calls
    private volatile boolean stopped = false;

    /**
     * Create a new bridge for emitting agent tool call events (workflow events only).
     */
    public AgentToolCallEventBridge(
            String runId,
            String nodeId,
            int itemIndex,
            Integer iteration,
            WorkflowEventPublisher eventPublisher) {
        this(runId, nodeId, itemIndex, iteration, eventPublisher, null, null, null,
             null, null, null, null, null);
    }

    /**
     * Create a new bridge for emitting both workflow events AND conversation streaming events.
     */
    public AgentToolCallEventBridge(
            String runId,
            String nodeId,
            int itemIndex,
            Integer iteration,
            WorkflowEventPublisher eventPublisher,
            ConversationEventPublisher conversationPublisher,
            String conversationId,
            String streamId) {
        this(runId, nodeId, itemIndex, iteration, eventPublisher, conversationPublisher,
             conversationId, streamId, null, null, null, null, null);
    }

    /**
     * Full constructor with optional parent-forwarding for sub-agent real-time visibility.
     *
     * @param parentConversationId  Parent's conversation ID for event forwarding (nullable)
     * @param subAgentName          Sub-agent display name (nullable)
     * @param subAgentAvatarUrl     Sub-agent avatar URL (nullable)
     * @param subAgentId            Sub-agent entity ID (nullable)
     * @param redisTemplate         Redis template for stop signal detection (nullable)
     */
    public AgentToolCallEventBridge(
            String runId,
            String nodeId,
            int itemIndex,
            Integer iteration,
            WorkflowEventPublisher eventPublisher,
            ConversationEventPublisher conversationPublisher,
            String conversationId,
            String streamId,
            String parentConversationId,
            String subAgentName,
            String subAgentAvatarUrl,
            String subAgentId,
            StringRedisTemplate redisTemplate) {
        this.runId = runId;
        this.nodeId = nodeId;
        this.itemIndex = itemIndex;
        this.iteration = iteration;
        this.eventPublisher = eventPublisher;
        this.conversationPublisher = conversationPublisher;
        this.conversationId = conversationId;
        this.streamId = streamId;
        this.parentConversationId = parentConversationId;
        this.subAgentName = subAgentName;
        this.subAgentAvatarUrl = subAgentAvatarUrl;
        this.subAgentId = subAgentId;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void onChunk(String content) {
        // Publish content chunk to conversation channel for live streaming
        if (conversationPublisher != null && conversationId != null && streamId != null) {
            conversationPublisher.publishContent(conversationId, streamId, content);
        }

        // Forward to parent conversation for real-time sub-agent visibility
        if (conversationPublisher != null && parentConversationId != null) {
            conversationPublisher.publishSubAgentContent(
                parentConversationId,
                subAgentName, subAgentAvatarUrl, subAgentId,
                content
            );
        }
    }

    @Override
    public void onThinking(String thinking) {
        // Publish thinking chunk to conversation channel
        if (conversationPublisher != null && conversationId != null && streamId != null) {
            conversationPublisher.publishThinking(conversationId, streamId, thinking);
        }

        // Forward to parent conversation for real-time sub-agent visibility
        if (conversationPublisher != null && parentConversationId != null) {
            conversationPublisher.publishSubAgentThinking(
                parentConversationId,
                subAgentName, subAgentAvatarUrl, subAgentId,
                thinking
            );
        }
    }

    @Override
    public void onToolCall(ToolCall toolCall) {
        if (toolCall == null) {
            return;
        }

        log.info("[AgentBridge] Tool call: runId={}, nodeId={}, tool={}, callId={}",
            runId, nodeId, toolCall.toolName(), toolCall.id());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("arguments", toolCall.arguments());
        if (toolCall.index() != null) {
            payload.put("toolIndex", toolCall.index());
        }

        // Emit to workflow streaming channel
        if (eventPublisher != null) {
            eventPublisher.emitAgentToolCall(
                runId,
                nodeId,
                toolCall.toolName(),
                toolCall.id(),
                AgentToolCallPhase.CALLING,
                payload,
                itemIndex,
                iteration
            );
        }

        // Emit to conversation WebSocket channel
        if (conversationPublisher != null && conversationId != null && streamId != null) {
            conversationPublisher.publishToolCall(
                conversationId, streamId,
                toolCall.toolName(), toolCall.id(),
                payload
            );
        }

        // Forward to parent conversation for real-time sub-agent visibility
        if (conversationPublisher != null && parentConversationId != null) {
            conversationPublisher.publishSubAgentToolCall(
                parentConversationId,
                subAgentName, subAgentAvatarUrl, subAgentId,
                toolCall.toolName(), toolCall.id(), payload
            );
        }
    }

    @Override
    public void onToolResult(ToolResult result) {
        if (result == null || result.toolCall() == null) {
            return;
        }

        ToolCall toolCall = result.toolCall();
        AgentToolCallPhase phase = result.success()
            ? AgentToolCallPhase.COMPLETED
            : AgentToolCallPhase.FAILED;

        log.info("[AgentBridge] Tool result: runId={}, nodeId={}, tool={}, success={}, duration={}ms",
            runId, nodeId, toolCall.toolName(), result.success(), result.durationMs());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", result.success());
        if (result.durationMs() != null) {
            payload.put("durationMs", result.durationMs());
        }
        if (result.error() != null) {
            payload.put("error", result.error());
        }
        // Include content summary (truncated for streaming)
        if (result.content() != null) {
            String contentPreview = result.content().length() > 200
                ? result.content().substring(0, 200) + "..."
                : result.content();
            payload.put("contentPreview", contentPreview);
            payload.put("contentLength", result.content().length());
        }
        if (result.metadata() != null && !result.metadata().isEmpty()) {
            payload.put("metadata", result.metadata());
        }

        // Emit to workflow streaming channel
        if (eventPublisher != null) {
            eventPublisher.emitAgentToolCall(
                runId,
                nodeId,
                toolCall.toolName(),
                toolCall.id(),
                phase,
                payload,
                itemIndex,
                iteration
            );
        }

        // Emit to conversation WebSocket channel
        if (conversationPublisher != null && conversationId != null && streamId != null) {
            conversationPublisher.publishToolResult(
                conversationId, streamId,
                toolCall.id(), toolCall.toolName(),
                result.success(), result.durationMs(),
                result.content()
            );
        }

        // Forward to parent conversation for real-time sub-agent visibility
        if (conversationPublisher != null && parentConversationId != null) {
            conversationPublisher.publishSubAgentToolResult(
                parentConversationId,
                subAgentName, subAgentAvatarUrl, subAgentId,
                toolCall.id(), toolCall.toolName(),
                result.success(), result.durationMs()
            );
        }
    }

    @Override
    public void onComplete(CompletionResponse response) {
        // Completion is handled by AgentNode via emitNodeComplete
    }

    @Override
    public void onError(String error) {
        log.warn("[AgentBridge] Agent error: runId={}, nodeId={}, error={}", runId, nodeId, error);
        // Error is handled by AgentNode via emitNodeComplete
    }

    @Override
    public boolean shouldStop() {
        if (stopped) return true;
        if (redisTemplate == null) return false;

        try {
            // Check workflow cancel key (for workflow-context agents)
            if (runId != null) {
                Boolean exists = redisTemplate.hasKey(WORKFLOW_CANCEL_PREFIX + runId);
                if (Boolean.TRUE.equals(exists)) {
                    stopped = true;
                    log.info("[AgentBridge] Cancel signal detected via workflow:cancel for runId={}, nodeId={}", runId, nodeId);
                    return true;
                }
            }

            // Check conversation cancel key (for conversation-context sub-agents)
            if (streamId != null) {
                Boolean exists = redisTemplate.hasKey(AGENT_CANCEL_PREFIX + streamId);
                if (Boolean.TRUE.equals(exists)) {
                    stopped = true;
                    log.info("[AgentBridge] Cancel signal detected via agent:cancel for streamId={}, nodeId={}", streamId, nodeId);
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("[AgentBridge] Error checking cancel key: {}", e.getMessage());
        }

        return false;
    }
}
