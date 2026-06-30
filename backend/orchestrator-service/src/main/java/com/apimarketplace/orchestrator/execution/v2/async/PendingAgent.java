package com.apimarketplace.orchestrator.execution.v2.async;

import java.time.Instant;
import java.util.Map;

/**
 * In-memory entry tracking an agent execution that has been offloaded to an async
 * worker pool (e.g., Redis queue) and is awaiting a result callback.
 *
 * <p>Held by {@link PendingAgentRegistry} from the moment {@code AgentNode} yields
 * with {@link com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult#asyncRunning}
 * until {@link AgentAsyncCompletionService} delivers the worker's result back into
 * the sync persistence pipeline.</p>
 *
 * <p>This is intentionally <b>in-memory only</b>. Restart recovery is handled
 * separately by the zombie scanner ticket (#67) which scans the durable Redis
 * result key + the active worker queue.</p>
 *
 * @param correlationId  unique id matching the queued task and the result message
 * @param runId          workflow run id
 * @param nodeId         the agent node id (e.g., {@code agent:my_classifier})
 * @param nodeLabel      the bare node label (e.g., {@code my_classifier}) used for DB persistence
 * @param dagTriggerId   the trigger id whose DAG this node belongs to (for epoch-scoped state)
 * @param epoch          the epoch in which the agent was launched
 * @param itemIndex      flat item index used by step persistence
 * @param itemId         hierarchical item id (e.g. {@code "0"}, {@code "0.1"}) for split tracking
 * @param agentType      one of {@code "agent"}, {@code "classify"}, {@code "guardrail"}
 * @param tenantId       tenant id (for credit consumption + plan reconstruction)
 * @param splitItemData      optional split-context payload to restore via SplitContextManager,
 *                           or {@code null} when the agent did not run inside a split
 * @param resolvedInputData  snapshot of the resolved node configuration (prompt, model, content,
 *                           categories/rules, etc.) captured before the async yield so the
 *                           completion service can persist it as {@code resolved_params} - the async
 *                           worker result only contains the agent output, not the input
 * @param conversationId     conversation id where the agent is writing its messages, or {@code null}
 *                           if no conversation is bound to this execution. Snapshotted at enqueue
 *                           time by {@code AgentNode.executeAgentAsyncQueue} so the completion
 *                           service can save the assistant response without re-resolving the agent
 *                           entity. The user prompt is saved at enqueue (not delivery) so the panel
 *                           shows it in real-time.
 * @param streamId           streamId returned by {@code conversationManager.startExecution} at
 *                           enqueue time, paired with conversationId to publish the stream-completed
 *                           event when the worker delivers. {@code null} when conversationId is null
 *                           or when conversation persistence wasn't started for this execution.
 * @param executionId        execution id linking the user prompt (saved at enqueue) and the
 *                           assistant message (saved at delivery) so the conversation panel can
 *                           group them as one turn. {@code null} when conversationId is null.
 * @param model              model name captured at enqueue for the {@code stream_completed} event
 *                           emitted on delivery - needed because the worker result doesn't echo it
 *                           back in a stable shape.
 * @param resolvedSystemPrompt  the FINAL system prompt sent to the LLM (modular prefix + custom
 *                              template, with all variables resolved). Snapshotted at enqueue
 *                              because the async worker DTO doesn't echo it back, and the raw
 *                              {@code agentConfig.systemPrompt()} template is missing the modular
 *                              prefix and pre-substitution. Without this, the Agent Performance
 *                              metric view shows a stale/empty system prompt for async agents.
 *                              {@code null} when no system prompt was configured.
 * @param resolvedUserPrompt    the FINAL user prompt sent to the LLM (template-resolved, with the
 *                              same fallback chain as {@code buildAgentRequest}: agentConfig.prompt()
 *                              → inputData["prompt"] → default placeholder). Snapshotted at enqueue
 *                              for the same reason as resolvedSystemPrompt. Prepended as the USER
 *                              message in async observability so the metric conversation tab matches
 *                              the chat / sync / sub-agent paths. {@code null} when blank/unresolvable.
 * @param startedAt          timestamp when the agent was offloaded - used by the zombie scanner
 * @param organizationId     PR20 - workspace identity snapshot. NULL = personal scope. Captured
 *                           at enqueue from ExecutionContext.organizationId() so the async-
 *                           delivery {@link AgentAsyncCompletionService} can stamp the
 *                           observability row with the same scope the sync path would have used.
 */
public record PendingAgent(
    String correlationId,
    String runId,
    String nodeId,
    String nodeLabel,
    String dagTriggerId,
    int epoch,
    int itemIndex,
    String itemId,
    String agentType,
    String tenantId,
    Map<String, Object> splitItemData,
    Map<String, Object> resolvedInputData,
    String conversationId,
    String streamId,
    String executionId,
    String model,
    String resolvedSystemPrompt,
    String resolvedUserPrompt,
    Instant startedAt,
    String organizationId,
    /**
     * The loop iteration this agent executed at when it ends a loop body, used so the async
     * completion records each iteration's step at a DISTINCT iteration (otherwise every iteration
     * persists at iter=0 and the per-iteration step_data overwrites → statusCounts under-reports).
     * Nullable for Redis back-compat: a pre-field serialized PendingAgent deserialises to {@code
     * null}, which the completion treats as iteration 0 (the prior behavior).
     */
    Integer loopIteration
) {
    /**
     * Back-compat constructor for call sites that pre-date PR20 organizationId.
     * Delegates with {@code organizationId = null}, which treats the execution
     * as personal-scope on the receiving side - matches the strict-isolation
     * default for pre-org rows. {@code loopIteration = null} (treated as iteration 0).
     */
    public PendingAgent(
        String correlationId, String runId, String nodeId, String nodeLabel,
        String dagTriggerId, int epoch, int itemIndex, String itemId,
        String agentType, String tenantId, Map<String, Object> splitItemData,
        Map<String, Object> resolvedInputData, String conversationId, String streamId,
        String executionId, String model, String resolvedSystemPrompt,
        String resolvedUserPrompt, Instant startedAt
    ) {
        this(correlationId, runId, nodeId, nodeLabel, dagTriggerId, epoch, itemIndex,
             itemId, agentType, tenantId, splitItemData, resolvedInputData,
             conversationId, streamId, executionId, model, resolvedSystemPrompt,
             resolvedUserPrompt, startedAt, null, null);
    }

    /**
     * Constructor with organizationId but without loopIteration (the common non-loop case).
     * Delegates with {@code loopIteration = null} (treated as iteration 0).
     */
    public PendingAgent(
        String correlationId, String runId, String nodeId, String nodeLabel,
        String dagTriggerId, int epoch, int itemIndex, String itemId,
        String agentType, String tenantId, Map<String, Object> splitItemData,
        Map<String, Object> resolvedInputData, String conversationId, String streamId,
        String executionId, String model, String resolvedSystemPrompt,
        String resolvedUserPrompt, Instant startedAt, String organizationId
    ) {
        this(correlationId, runId, nodeId, nodeLabel, dagTriggerId, epoch, itemIndex,
             itemId, agentType, tenantId, splitItemData, resolvedInputData,
             conversationId, streamId, executionId, model, resolvedSystemPrompt,
             resolvedUserPrompt, startedAt, organizationId, null);
    }
}
