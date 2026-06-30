package com.apimarketplace.orchestrator.services.streaming.state;

import com.apimarketplace.orchestrator.services.streaming.context.RunContextRegistry;
import com.apimarketplace.orchestrator.services.streaming.context.RunNodeState;
import com.apimarketplace.orchestrator.services.streaming.context.RunNodeState.StatusCounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Stateless service for recording node execution events.
 *
 * <p>Delegates all state management to {@link RunContextRegistry}.
 * The actual state is stored in {@link RunNodeState} which is owned by RunContext.
 */
@Component
public class NodeEventStore {

    private static final Logger log = LoggerFactory.getLogger(NodeEventStore.class);

    private final RunContextRegistry contextRegistry;

    public NodeEventStore(@Lazy RunContextRegistry contextRegistry) {
        this.contextRegistry = contextRegistry;
    }

    /**
     * Records a node execution event. Called immediately after DB persistence.
     *
     * @param runId       The workflow run ID
     * @param nodeId      The node ID (step alias/label)
     * @param itemIndex   The item index (0-based)
     * @param iteration   The iteration number (0 for non-loop, >0 for while loops)
     * @param status      The execution status (COMPLETED, FAILED, SKIPPED, RUNNING)
     * @return The updated StatusCounts for this node
     */
    public StatusCounts recordNodeExecution(String runId, String nodeId,
                                             Integer itemIndex, Integer iteration,
                                             String status) {
        if (runId == null || nodeId == null) {
            return StatusCounts.empty();
        }

        RunNodeState state = contextRegistry.getNodeState(runId);
        StatusCounts counts = state.recordExecution(nodeId, itemIndex, iteration, status);

        log.info("[NodeEventStore] Recorded: runId={} nodeId={} item={} iter={} status={} -> counts={}",
            runId, nodeId, itemIndex, iteration, status, counts);

        return counts;
    }

    /**
     * Gets the current status counts for a node.
     *
     * @param runId  The workflow run ID
     * @param nodeId The node ID (step alias/label)
     * @return The current StatusCounts, or empty if not found
     */
    public StatusCounts getStatusCounts(String runId, String nodeId) {
        if (runId == null || nodeId == null) {
            return StatusCounts.empty();
        }

        return contextRegistry.get(runId)
            .map(ctx -> ctx.getNodeState().getStatusCounts(nodeId))
            .orElse(StatusCounts.empty());
    }

    /**
     * Gets status counts for all nodes in a run.
     *
     * @param runId The workflow run ID
     * @return Map of nodeId to StatusCounts
     */
    public Map<String, StatusCounts> getAllStatusCounts(String runId) {
        if (runId == null) {
            return Map.of();
        }

        return contextRegistry.get(runId)
            .map(ctx -> ctx.getNodeState().getAllStatusCounts())
            .orElse(Map.of());
    }

    /**
     * Initialize total items for all nodes in a run.
     * This sets the expected total for proper "X/Y" display in UI.
     *
     * @param runId      The workflow run ID
     * @param totalItems The total number of items to be processed
     */
    public void initializeTotalItems(String runId, int totalItems) {
        if (runId == null || totalItems <= 0) {
            return;
        }

        RunNodeState state = contextRegistry.getNodeState(runId);
        state.setTotalItems(totalItems);
        log.debug("Initialized total items for run {}: {}", runId, totalItems);
    }

    /**
     * Initialize total items for a specific node in a run.
     *
     * @param runId      The workflow run ID
     * @param nodeId     The node ID
     * @param totalItems The total number of items for this node
     */
    public void initializeNodeTotalItems(String runId, String nodeId, int totalItems) {
        if (runId == null || nodeId == null || totalItems <= 0) {
            return;
        }

        RunNodeState state = contextRegistry.getNodeState(runId);
        state.setNodeTotalItems(nodeId, totalItems);
        log.debug("Initialized total items for run {} node {}: {}", runId, nodeId, totalItems);
    }

    /**
     * Pre-populate status counts for a node from accumulated database values.
     * This is used when starting a new epoch for reusable triggers to ensure
     * the first streaming event shows the accumulated totals.
     *
     * @param runId        The workflow run ID
     * @param nodeId       The node ID (step alias/label)
     * @param statusCounts Map of status -> count (e.g., {"SUCCESS": 15, "FAILED": 0})
     */
    public void prePopulateCounts(String runId, String nodeId, Map<String, Integer> statusCounts) {
        if (runId == null || nodeId == null || statusCounts == null) {
            return;
        }

        RunNodeState state = contextRegistry.getNodeState(runId);
        state.prePopulateCounts(nodeId, statusCounts);
        log.info("[NodeEventStore] Pre-populated counts for runId={} nodeId={}: {}", runId, nodeId, statusCounts);
    }

    /**
     * Gets the number of active runs being tracked.
     */
    public int getActiveRunCount() {
        return contextRegistry.size();
    }
}
