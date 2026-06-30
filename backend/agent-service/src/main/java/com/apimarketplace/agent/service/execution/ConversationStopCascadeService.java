package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.common.web.TenantResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * F2.1 - propagate a conversation STOP to any workflow runs the conversation's
 * agent loop has spawned. The conversation-service calls this via
 * {@code AgentClient.cancelWorkflowsForConversation} after stopping the stream.
 *
 * <p>Why here: this service owns the join between {@code conversation_id} and
 * {@code workflow_run_id} (via {@code agent_executions}) and already shares the
 * Redis namespace with orchestrator (see {@code RedisStreamingCallback} and
 * {@code ConversationRedisStreamingCallback} which already read
 * {@code workflow:cancel:&#123;runId&#125;}). Reusing the same key prefix and TTL
 * keeps cancel propagation consistent with what the engine already honors at
 * {@code UnifiedExecutionEngine.isAgentCancelSignalSet}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationStopCascadeService {

    /** Mirror of {@code WorkflowRedisPublisher.CANCEL_KEY_PREFIX} (orchestrator-side). */
    private static final String WORKFLOW_CANCEL_KEY_PREFIX = "workflow:cancel:";
    /** Mirror of orchestrator TTL - 2h is generous enough that a delayed engine pickup still sees the key. */
    private static final Duration WORKFLOW_CANCEL_TTL = Duration.ofHours(2);
    /**
     * MUST stay aligned with {@code WorkflowRedisPublisher.setAgentCancelSignal}
     * which writes {@code "cancelled"}. Consumers (Java engine + Node bridge)
     * currently only check key presence, but a future cause-payload reader
     * (already used for {@code agent:cancel:} in the bridge) would silently
     * mis-classify if the two producers diverge.
     */
    private static final String WORKFLOW_CANCEL_VALUE = "cancelled";

    private final AgentExecutionRepository agentExecutionRepository;
    private final AgentTaskRepository agentTaskRepository;
    private final StringRedisTemplate redisTemplate;

    /**
     * Set the workflow cancel signal for every running workflow run linked to
     * this conversation. The engine polls this key before every node and bails
     * the traversal when set.
     *
     * @return number of distinct workflow runs flagged for cancellation
     */
    public int cancelRunningWorkflowsForConversation(String conversationId) {
        return cancelRunningWorkflowsForConversation(conversationId, TenantResolver.currentRequestOrganizationId());
    }

    public int cancelRunningWorkflowsForConversation(String conversationId, String organizationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return 0;
        }
        if (organizationId == null || organizationId.isBlank()) {
            log.warn("[STOP-CASCADE] Missing organizationId for workflow cancel cascade conv={}", conversationId);
            return 0;
        }

        List<UUID> runIds;
        try {
            runIds = agentExecutionRepository.findRunningWorkflowRunIdsByConversationIdAndOrganizationId(
                conversationId, organizationId);
        } catch (Exception e) {
            // DB hiccup must not break STOP - log and return 0 (the agent loop's
            // own shouldStop chain still bails on its own cancel key).
            log.warn("[STOP-CASCADE] DB lookup failed for conv={}: {}", conversationId, e.toString());
            return 0;
        }

        if (runIds == null || runIds.isEmpty()) {
            return 0;
        }

        int set = 0;
        for (UUID runId : runIds) {
            if (runId == null) continue;
            try {
                redisTemplate.opsForValue().set(
                    WORKFLOW_CANCEL_KEY_PREFIX + runId,
                    WORKFLOW_CANCEL_VALUE,
                    WORKFLOW_CANCEL_TTL);
                set++;
            } catch (Exception e) {
                log.warn("[STOP-CASCADE] Redis set failed for runId={}: {}", runId, e.toString());
            }
        }
        if (set > 0) {
            log.info("[STOP-CASCADE] Conversation {} STOP propagated to {} workflow run(s)", conversationId, set);
        }
        return set;
    }

    /**
     * F3.4 - cascade-cancel any non-terminal tasks linked to executions running
     * in this conversation. Reuses the existing recursive CTE
     * ({@link AgentTaskRepository#cascadingCancelInOrganization}) which marks the task and
     * all its descendant tasks as CANCELLED. Workers polling those tasks see
     * the status flip and stop. Bypasses the strict per-actor authorization in
     * {@code AgentTaskService.cancelTask} because this is a system-level cascade
     * triggered by the user themselves stopping the conversation that owns the
     * tasks.
     *
     * @return number of task rows transitioned to CANCELLED
     */
    @Transactional
    public int cancelTasksForConversation(String conversationId, String tenantId) {
        return cancelTasksForConversation(conversationId, tenantId, TenantResolver.currentRequestOrganizationId());
    }

    @Transactional
    public int cancelTasksForConversation(String conversationId, String tenantId, String organizationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return 0;
        }
        if (tenantId == null || tenantId.isBlank()) {
            return 0;
        }
        if (organizationId == null || organizationId.isBlank()) {
            log.warn("[STOP-CASCADE] Missing organizationId for task cancel cascade conv={}", conversationId);
            return 0;
        }

        List<UUID> taskIds;
        try {
            taskIds = agentExecutionRepository.findRunningTaskIdsByConversationIdAndOrganizationId(
                conversationId, organizationId);
        } catch (Exception e) {
            log.warn("[STOP-CASCADE] Task lookup failed for conv={}: {}", conversationId, e.toString());
            return 0;
        }

        if (taskIds == null || taskIds.isEmpty()) {
            return 0;
        }

        int totalCancelled = 0;
        for (UUID taskId : taskIds) {
            if (taskId == null) continue;
            try {
                int n = agentTaskRepository.cascadingCancelInOrganization(
                    taskId, tenantId, organizationId, "conversation_stopped");
                totalCancelled += n;
            } catch (Exception e) {
                log.warn("[STOP-CASCADE] Task cascade failed for taskId={}: {}", taskId, e.toString());
            }
        }
        if (totalCancelled > 0) {
            log.info("[STOP-CASCADE] Conversation {} STOP cancelled {} task row(s) across {} root task(s)",
                conversationId, totalCancelled, taskIds.size());
        }
        return totalCancelled;
    }
}
