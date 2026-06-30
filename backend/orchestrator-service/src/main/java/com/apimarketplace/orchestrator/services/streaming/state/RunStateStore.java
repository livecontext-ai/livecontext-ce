package com.apimarketplace.orchestrator.services.streaming.state;

import com.apimarketplace.orchestrator.services.streaming.context.RunContextRegistry;
import com.apimarketplace.orchestrator.services.streaming.events.WorkflowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Facade for accessing workflow run state.
 *
 * <p>Delegates to {@link RunContextRegistry} for actual state management.
 * This class is kept for backward compatibility with existing code.
 *
 * <p>Note: Snapshots are now sent directly by SnapshotService from DB.
 * This class only maintains supplementary state (loops, merges, logs, interfaces).
 *
 * @see RunContextRegistry
 */
@Component
public class RunStateStore implements RunStateStoreAccessor {

    private static final Logger log = LoggerFactory.getLogger(RunStateStore.class);

    private final RunContextRegistry contextRegistry;

    @Autowired
    @Lazy
    private EventApplier eventApplier;

    @Autowired
    @Lazy
    private StatePrePopulator statePrePopulator;

    public RunStateStore(@Lazy RunContextRegistry contextRegistry) {
        this.contextRegistry = contextRegistry;
    }

    // ========== RunStateStoreAccessor Implementation ==========

    @Override
    public RunState getOrCreateRunState(String runId) {
        return contextRegistry.getRunState(runId);
    }

    // ========== Event Handling (delegates to EventApplier) ==========

    /**
     * Apply a workflow event to the run state.
     *
     * @param event The event to apply
     */
    public void applyEvent(WorkflowEvent event) {
        eventApplier.applyEvent(event);
    }

    // ========== Pre-Population (delegates to StatePrePopulator) ==========

    /**
     * Pre-populate node (step) counters with accumulated counts from database.
     * This ensures streaming events show accumulated totals across all epochs (for reruns).
     *
     * @param runId The workflow run ID
     * @param nodeId The node ID (step alias/label)
     * @param statusCounts Map of status -> count (e.g., {"SUCCESS": 15, "FAILED": 0})
     */
    public void prePopulateNodeCounts(String runId, String nodeId, Map<String, Integer> statusCounts) {
        statePrePopulator.prePopulateNodeCounts(runId, nodeId, statusCounts);
    }

    // ========== Snapshot Management ==========

    /**
     * Get a snapshot for a specific run.
     *
     * @param runId The workflow run ID
     * @return The snapshot, or null if run not found
     */
    public RunSnapshot snapshot(String runId) {
        return contextRegistry.snapshot(runId);
    }

    /**
     * Purge all state for a run.
     * Delegates to RunContextRegistry.close() which cleans up everything.
     *
     * @param runId The workflow run ID to purge
     */
    public void purge(String runId) {
        if (runId == null) {
            return;
        }
        // Don't close the context here - just log
        // The context will be closed by WorkflowStreamingService.finalizeStreaming()
        log.debug("purge() called for runId={} - context cleanup handled by RunContextRegistry", runId);
    }

    // ========== Snapshot Record ==========

    /**
     * Complete snapshot of a workflow run's state.
     * Used by the batch emitter to send streaming updates.
     */
    public record RunSnapshot(
        List<Map<String, Object>> steps,
        List<Map<String, Object>> edges,
        Map<String, Object> workflowStatus,
        Map<String, Object> workflowStatistics,
        List<Map<String, Object>> loops,
        List<Map<String, Object>> merges,
        List<Map<String, Object>> logs,
        List<Map<String, Object>> agentToolCalls,
        boolean terminal
    ) { }
}
