package com.apimarketplace.orchestrator.tools.utility;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.common.event.KeyValueStore;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Answers "has the agent CALLING this tool been stopped?" from inside a
 * long-running tool execution.
 *
 * <p>The agent loop only observes {@code StreamingCallback.shouldStop()} BETWEEN
 * tool calls, never during one - so a blocking tool (e.g. the {@code wait} tool's
 * sleep, or {@code workflow(action='wait_run')}) that holds its thread for minutes
 * would otherwise ignore a user STOP until it returns. This probe lets such tools
 * poll the same Redis cancel signals the streaming callbacks use, and release
 * early:
 *
 * <ul>
 *   <li><b>Workflow-agent path</b> - the executing run's id travels in the tool
 *       credentials as {@code __workflowRunId__} (stamped by {@code AgentNode});
 *       cancellation is the {@code workflow:cancel:&lt;runId&gt;} key, checked via
 *       {@link WorkflowRedisPublisher#isAgentCancelSignalSet(String)} which also
 *       walks the sub-workflow parent chain.</li>
 *   <li><b>Chat path</b> - the conversation id travels as {@code conversationId};
 *       the active stream is resolved through the {@code stream:conv:&lt;convId&gt;}
 *       index and cancellation is the {@code agent:cancel:&lt;streamId&gt;} key
 *       (set by the conversation STOP handler). Mirrors
 *       {@code ConversationRedisStreamingCallback}'s own check.</li>
 * </ul>
 *
 * <p>Fail-open: any Redis error reads as "not cancelled" - a broken Redis must
 * not abort otherwise-healthy waits (same policy as the run cancel probe).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentCancellationProbe {

    /** Mirror of the conversation-service {@code stream:conv:} index prefix. */
    private static final String STREAM_INDEX_KEY_PREFIX = "stream:conv:";
    /** Mirror of {@code ConversationRedisStreamingCallback.CANCEL_KEY_PREFIX}. */
    private static final String CHAT_CANCEL_KEY_PREFIX = "agent:cancel:";

    private final WorkflowRedisPublisher workflowRedisPublisher;
    private final KeyValueStore keyValueStore;

    /**
     * @return true if the caller of the current tool execution has a cancel
     *         signal set (workflow run cancel or chat stream stop); false when
     *         no signal is set, when the context carries no caller identity,
     *         or when Redis is unreachable (fail-open).
     */
    public boolean isCallerCancelled(ToolExecutionContext context) {
        if (context == null || context.credentials() == null) {
            return false;
        }
        Map<String, Object> credentials = context.credentials();

        // The publisher already fails open internally, but keep the probe's own
        // fail-open guarantee independent of that implementation detail: a
        // throwing collaborator must read as "not cancelled", never abort a wait.
        String runId = asNonBlankString(credentials.get("__workflowRunId__"));
        if (runId != null) {
            try {
                if (workflowRedisPublisher.isAgentCancelSignalSet(runId)) {
                    return true;
                }
            } catch (Exception e) {
                log.debug("Cancellation probe failed for run {} (fail-open): {}", runId, e.getMessage());
            }
        }

        String conversationId = asNonBlankString(credentials.get("conversationId"));
        if (conversationId == null) {
            return false;
        }
        try {
            return keyValueStore.get(STREAM_INDEX_KEY_PREFIX + conversationId)
                .map(streamId -> keyValueStore.get(CHAT_CANCEL_KEY_PREFIX + streamId).isPresent())
                .orElse(false);
        } catch (Exception e) {
            log.debug("Cancellation probe failed for conversation {} (fail-open): {}",
                conversationId, e.getMessage());
            return false;
        }
    }

    private static String asNonBlankString(Object value) {
        if (value == null) return null;
        String s = value.toString();
        return s.isBlank() ? null : s;
    }
}
