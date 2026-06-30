package com.apimarketplace.conversation.controller.v3.chat;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.conversation.service.ConversationHistoryService;
import com.apimarketplace.conversation.streaming.StreamMetadata;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import com.apimarketplace.conversation.streaming.StreamStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Handles stream stop operations.
 * Single Responsibility: Stop active streams and save partial content.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamStopHandler {

    private final StreamStateService stateService;
    private final StreamPubSubService pubSubService;
    private final ConversationHistoryService conversationHistoryService;
    private final AgentClient agentClient;

    /**
     * Result of a stop operation.
     */
    public record StopResult(
            boolean success,
            String message,
            String conversationId,
            String streamId,
            int stoppedStreams,
            int savedPartialContent
    ) {}

    /**
     * Stop an active stream for a conversation.
     */
    public StopResult stopStream(String userId, String conversationId) {
        return stopStream(userId, conversationId, null);
    }

    public StopResult stopStream(String userId, String conversationId, String organizationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            log.error("Conversation ID is missing in request body");
            return new StopResult(false, "Conversation ID is required", conversationId, null, 0, 0);
        }

        try {
            log.info("Stop stream request from user {} for conversation {}", userId, conversationId);

            // Find active stream for this conversation
            StreamMetadata metadata = stateService.getByConversationId(conversationId).block();

            if (metadata == null || !metadata.state().isActive()) {
                log.info("No active stream found for conversation {}", conversationId);
                return new StopResult(
                        true,
                        "No active streams found for conversation",
                        conversationId,
                        null,
                        0,
                        0
                );
            }

            String streamId = metadata.streamId();

            // Save partial content
            String partialContent = stateService.getFullContent(streamId).block();
            int savedPartialContent = 0;
            if (partialContent != null && !partialContent.trim().isEmpty()) {
                conversationHistoryService.addMessage(
                        conversationId, "assistant", partialContent, "gpt-4",
                        java.time.Instant.now().toString(), null, userId
                );
                savedPartialContent = 1;
                log.info("[STOP] Partial content saved: {} chars", partialContent.length());
            }

            // Stop the stream and set cancel key for remote agent execution
            stateService.stop(streamId).block();
            stateService.setCancelKey(streamId).block();
            pubSubService.publishStopped(streamId, partialContent != null ? partialContent : "").block();

            // F2.1 - cascade STOP to any workflow runs the agent loop spawned
            // (e.g. via the workflow tool). Best-effort: agent-service writes
            // workflow:cancel:{runId} for each running run linked to this
            // conversation, which the orchestrator engine honors before every
            // node. Fire-and-forget - failure here doesn't break the STOP.
            try {
                int cancelledRuns = agentClient.cancelWorkflowsForConversation(conversationId, organizationId);
                if (cancelledRuns > 0) {
                    log.info("[STOP] Cascaded conversation STOP to {} workflow run(s)", cancelledRuns);
                }
            } catch (Exception e) {
                log.warn("[STOP] Workflow cascade failed (non-critical): {}", e.getMessage());
            }

            // F3.4 - cascade STOP to any non-terminal tasks the agent loop
            // assigned via agent.assign. Without this, a task tied to this
            // conversation continues being polled and executed after STOP.
            try {
                int cancelledTasks = agentClient.cancelTasksForConversation(conversationId, userId, organizationId);
                if (cancelledTasks > 0) {
                    log.info("[STOP] Cascaded conversation STOP to {} task row(s)", cancelledTasks);
                }
            } catch (Exception e) {
                log.warn("[STOP] Task cascade failed (non-critical): {}", e.getMessage());
            }

            return new StopResult(
                    true,
                    "Stream stopped for conversation",
                    conversationId,
                    streamId,
                    1,
                    savedPartialContent
            );

        } catch (Exception e) {
            log.error("Error stopping streams for conversation: {}", e.getMessage(), e);
            return new StopResult(
                    false,
                    "Error stopping streams: " + e.getMessage(),
                    conversationId,
                    null,
                    0,
                    0
            );
        }
    }

    /**
     * Convert StopResult to a Map for JSON response.
     */
    public Map<String, Object> toResponseMap(StopResult result) {
        if (result.streamId() != null) {
            return Map.of(
                    "success", result.success(),
                    "message", result.message(),
                    "conversationId", result.conversationId(),
                    "streamId", result.streamId(),
                    "stoppedStreams", result.stoppedStreams(),
                    "savedPartialContent", result.savedPartialContent()
            );
        } else {
            return Map.of(
                    "success", result.success(),
                    "message", result.message(),
                    "conversationId", result.conversationId() != null ? result.conversationId() : "",
                    "stoppedStreams", result.stoppedStreams()
            );
        }
    }
}
