package com.apimarketplace.orchestrator.services.streaming.state;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handles pre-population of workflow state from database records.
 * This ensures streaming events show accumulated totals across all epochs (for reruns).
 */
@Component
public class StatePrePopulator {

    private static final Logger log = LoggerFactory.getLogger(StatePrePopulator.class);

    private final NodeEventStore nodeEventStore;
    private final RunStateStoreAccessor stateAccessor;

    public StatePrePopulator(NodeEventStore nodeEventStore, RunStateStoreAccessor stateAccessor) {
        this.nodeEventStore = nodeEventStore;
        this.stateAccessor = stateAccessor;
    }

    /**
     * Pre-populate node (step) counters with accumulated counts from database.
     * This ensures streaming events show accumulated totals across all epochs (for reruns).
     *
     * @param runId The workflow run ID
     * @param nodeId The node ID (step alias/label)
     * @param statusCounts Map of status -> count (e.g., {"SUCCESS": 15, "FAILED": 0})
     */
    public void prePopulateNodeCounts(String runId, String nodeId, Map<String, Integer> statusCounts) {
        if (runId == null || nodeId == null || statusCounts == null || statusCounts.isEmpty()) {
            return;
        }
        nodeEventStore.prePopulateCounts(runId, nodeId, statusCounts);
        log.info("[StatePrePopulator] Pre-populated node counts for runId={} nodeId={}: {}",
            runId, nodeId, statusCounts);
    }

}
