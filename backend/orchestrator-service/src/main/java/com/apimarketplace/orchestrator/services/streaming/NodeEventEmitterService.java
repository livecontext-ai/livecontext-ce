package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.events.StepLifecycle;
import com.apimarketplace.orchestrator.services.streaming.context.RunNodeState.StatusCounts;
import com.apimarketplace.orchestrator.services.streaming.state.NodeEventStore;
import com.apimarketplace.orchestrator.services.streaming.state.RunStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service that handles the unified flow: persist to DB -> update in-memory state -> emit streaming event.
 *
 * This service ensures that:
 * 1. After each DB persistence, the NodeEventStore is updated with the new status
 * 2. A streaming event is immediately emitted with the updated statusCounts
 * 3. StatusCounts are computed from memory, NOT from DB queries
 *
 * For while loops, the indexing is by (item × iteration).
 * For regular steps, the indexing is by item only.
 */
@Service
public class NodeEventEmitterService {

    private static final Logger log = LoggerFactory.getLogger(NodeEventEmitterService.class);

    private final NodeEventStore nodeEventStore;
    private final RunStateStore runStateStore;
    private final WorkflowEventPublisher eventPublisher;
    private final StepEventBuilder stepEventBuilder;

    public NodeEventEmitterService(NodeEventStore nodeEventStore,
                                   RunStateStore runStateStore,
                                   WorkflowEventPublisher eventPublisher,
                                   StepEventBuilder stepEventBuilder) {
        this.nodeEventStore = nodeEventStore;
        this.runStateStore = runStateStore;
        this.eventPublisher = eventPublisher;
        this.stepEventBuilder = stepEventBuilder;
    }

    /**
     * Initialize total items count for all nodes in a run.
     * This sets the expected total for proper "X/Y" display in UI.
     *
     * @param runId      The workflow run ID
     * @param totalItems The total number of items to be processed
     */
    public void initializeTotalItems(String runId, int totalItems) {
        nodeEventStore.initializeTotalItems(runId, totalItems);
        log.debug("[NodeEventEmitter] Total items initialized: runId={} totalItems={}", runId, totalItems);
    }

    /**
     * Initialize total items count for a specific node.
     *
     * @param runId      The workflow run ID
     * @param nodeLabel  The node label
     * @param totalItems The total number of items for this node
     */
    public void initializeNodeTotalItems(String runId, String nodeLabel, int totalItems) {
        nodeEventStore.initializeNodeTotalItems(runId, nodeLabel, totalItems);
        log.debug("[NodeEventEmitter] Node total items initialized: runId={} nodeLabel={} totalItems={}",
            runId, nodeLabel, totalItems);
    }

    /**
     * Pre-populate the in-memory store with accumulated status counts from database.
     * This should be called when starting a new epoch for reusable triggers (webhook/manual/chat)
     * to ensure the first streaming event shows the accumulated totals.
     *
     * @param runId       The workflow run ID
     * @param nodeId      The node ID (step alias/label)
     * @param statusCounts Map of status -> count (e.g., {"SUCCESS": 15, "FAILED": 0})
     */
    public void prePopulateCounts(String runId, String nodeId, Map<String, Integer> statusCounts) {
        if (runId == null || nodeId == null || statusCounts == null || statusCounts.isEmpty()) {
            return;
        }
        nodeEventStore.prePopulateCounts(runId, nodeId, statusCounts);
        log.info("[NodeEventEmitter] Pre-populated counts for runId={} nodeId={}: {}", runId, nodeId, statusCounts);
    }

    /**
     * Called after a step has been persisted to DB.
     * Updates the in-memory state and emits a streaming event.
     * 
     * @param execution      The workflow execution context
     * @param stepId         The step ID (internal identifier)
     * @param stepAlias      The step alias/label (what's shown in UI)
     * @param normalizedId   The normalized step ID (for streaming routing)
     * @param result         The step execution result
     * @param itemIndex      The item index (0-based, from the trigger batch)
     * @param iteration      The iteration number (0 for non-loop, >0 for while loops)
     */
    public void onStepPersisted(WorkflowExecution execution,
                                 String stepId,
                                 String stepAlias,
                                 String normalizedId,
                                 StepExecutionResult result,
                                 Integer itemIndex,
                                 Integer iteration) {
        if (execution == null || result == null) {
            return;
        }

        String runId = execution.getRunId();
        // Use the internal extraction for backwards compatibility
        String nodeLabel = extractNodeLabel(stepId, stepAlias, result);
        recordInMemory(runId, nodeLabel, itemIndex, iteration, result);
    }

    /**
     * Called after a step has been persisted to DB.
     * This overload accepts the nodeLabel directly to ensure consistency with the caller.
     * 
     * @param execution      The workflow execution context
     * @param nodeLabel      The node label (must match what getStatusCounts uses)
     * @param result         The step execution result
     * @param itemIndex      The item index (0-based)
     * @param iteration      The iteration number (0 for non-loop)
     */
    public void recordStepExecution(WorkflowExecution execution,
                                    String nodeLabel,
                                    StepExecutionResult result,
                                    Integer itemIndex,
                                    Integer iteration) {
        if (execution == null || result == null) {
            return;
        }

        String runId = execution.getRunId();
        recordInMemory(runId, nodeLabel, itemIndex, iteration, result);
    }

    private void recordInMemory(String runId, String nodeLabel, Integer itemIndex, Integer iteration, StepExecutionResult result) {
        String statusValue = result.status() != null ? result.status().name() : "RUNNING";

        // Update in-memory state
        // NOTE: We do NOT emit streaming event here - the caller (WorkflowStreamingService.sendStepEvent) does that
        StatusCounts counts = nodeEventStore.recordNodeExecution(
            runId, nodeLabel, itemIndex, iteration, statusValue
        );

        log.debug("[NodeEventEmitter] Step recorded in memory: runId={} nodeLabel={} item={} iter={} status={} -> counts={}",
            runId, nodeLabel, itemIndex, iteration, statusValue, counts);
    }

    /**
     * Called after a step has been persisted to DB.
     * Overload that extracts item/iteration from result output.
     */
    public void onStepPersisted(WorkflowExecution execution,
                                 String stepId,
                                 String stepAlias,
                                 String normalizedId,
                                 StepExecutionResult result) {
        Integer itemIndex = extractIntegerFromOutput(result, "item_index", "itemIndex", "absoluteIndex");
        Integer iteration = extractIntegerFromOutput(result, "currentIteration", "iteration", "loopIteration");
        
        onStepPersisted(execution, stepId, stepAlias, normalizedId, result, itemIndex, iteration);
    }

    /**
     * Gets the current status counts for a node (from memory, not DB).
     *
     * @param runId    The workflow run ID
     * @param nodeLabel The node label/alias
     * @return StatusCounts as a Map
     */
    public Map<String, Object> getStatusCounts(String runId, String nodeLabel) {
        StatusCounts counts = nodeEventStore.getStatusCounts(runId, nodeLabel);
        return counts.toMap();
    }

    /**
     * Gets the current status counts for a node as StatusCounts object.
     *
     * @param runId    The workflow run ID
     * @param nodeLabel The node label/alias
     * @return StatusCounts object
     */
    public StatusCounts getStatusCountsRaw(String runId, String nodeLabel) {
        return nodeEventStore.getStatusCounts(runId, nodeLabel);
    }

    /**
     * Records a node execution status in memory.
     * Use this to record RUNNING status when a node starts.
     *
     * @param runId     The workflow run ID
     * @param nodeLabel The node label/alias
     * @param itemIndex The item index (0-based)
     * @param iteration The iteration number (0 for non-loop)
     * @param status    The status (RUNNING, COMPLETED, FAILED, SKIPPED)
     * @return The updated StatusCounts
     */
    public StatusCounts recordNodeExecution(String runId, String nodeLabel,
                                             int itemIndex, int iteration, String status) {
        StatusCounts counts = nodeEventStore.recordNodeExecution(
            runId, nodeLabel, itemIndex, iteration, status
        );
        log.debug("[NodeEventEmitter] Recorded: runId={} nodeLabel={} item={} iter={} status={} -> counts={}",
            runId, nodeLabel, itemIndex, iteration, status, counts);
        return counts;
    }

    // ==================== Private Helpers ====================

    private void emitStepEvent(WorkflowExecution execution,
                               String stepId,
                               String stepAlias,
                               String normalizedId,
                               StepExecutionResult result,
                               StatusCounts counts) {
        String runId = execution.getRunId();
        String emitAlias = normalizeLoopItemScopedStepId(stepAlias != null ? stepAlias : stepId);
        String emitNormalizedId = normalizeLoopItemScopedStepId(normalizedId != null ? normalizedId : emitAlias);

        // Determine lifecycle from status
        StepLifecycle lifecycle = mapToLifecycle(result.status() != null ? result.status().name() : "RUNNING");

        // Build event data using StepEventBuilder but override statusCounts with our in-memory counts
        Map<String, Object> eventData = stepEventBuilder.build(
            execution, 
            stepId, 
            emitAlias, 
            emitNormalizedId, 
            result,
            counts.toMap()  // Use in-memory counts, not DB query
        );

        // Add error info if present
        if (result.error() != null) {
            eventData.put("error", result.error().getMessage());
            eventData.put("errorType", result.error().getClass().getSimpleName());
        }

        // Emit the event
        eventPublisher.emitStep(runId, emitNormalizedId, eventData, lifecycle);

        log.debug("[NodeEventEmitter] Streaming event emitted: runId={} stepId={} lifecycle={} statusCounts={}",
            runId, emitNormalizedId, lifecycle, counts);
    }

    private String extractNodeLabel(String stepId, String stepAlias, StepExecutionResult result) {
        // Prefer stepAlias if available
        if (stepAlias != null && !stepAlias.isBlank()) {
            return stripPrefix(stepAlias);
        }
        // Fall back to result stepId
        if (result != null && result.stepId() != null) {
            return stripPrefix(result.stepId());
        }
        // Last resort: use stepId
        return stripPrefix(stepId);
    }

    private String stripPrefix(String value) {
        if (value == null) {
            return null;
        }
        // Remove common prefixes: mcp:, trigger:, core:, agent:, interface:, table:, note:
        if (value.startsWith("mcp:")) {
            return value.substring(4);
        }
        if (value.startsWith("trigger:")) {
            return value.substring(8);
        }
        if (value.startsWith("core:")) {
            return value.substring(5);
        }
        if (value.startsWith("agent:")) {
            return value.substring(6);
        }
        if (value.startsWith("interface:")) {
            return value.substring(10);
        }
        if (value.startsWith("table:")) {
            return value.substring(6);
        }
        if (value.startsWith("note:")) {
            return value.substring(5);
        }
        return value;
    }

    private String normalizeLoopItemScopedStepId(String stepId) {
        if (stepId == null) {
            return null;
        }
        // Remove #item-X suffix if present
        int itemIndex = stepId.indexOf("#item-");
        return itemIndex > 0 ? stepId.substring(0, itemIndex) : stepId;
    }

    private StepLifecycle mapToLifecycle(String status) {
        if (status == null) {
            return StepLifecycle.RUNNING;
        }
        return switch (status.toUpperCase()) {
            case "COMPLETED", "SUCCESS" -> StepLifecycle.SUCCESS;
            case "FAILED", "FAILURE", "ERROR" -> StepLifecycle.FAILURE;
            case "SKIPPED" -> StepLifecycle.SKIPPED;
            case "CANCELLED" -> StepLifecycle.CANCELLED;
            default -> StepLifecycle.RUNNING;
        };
    }

    private Integer extractIntegerFromOutput(StepExecutionResult result, String... keys) {
        if (result == null || result.output() == null) {
            return null;
        }
        Map<String, Object> output = result.output();
        for (String key : keys) {
            Object value = output.get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value instanceof String str) {
                try {
                    return Integer.parseInt(str);
                } catch (NumberFormatException ignored) {
                    // Try next key
                }
            }
        }
        return null;
    }
}
