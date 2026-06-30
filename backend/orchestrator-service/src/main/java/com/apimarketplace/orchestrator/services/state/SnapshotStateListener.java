package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.services.state.WorkflowStateManager.StateChangeEvent;
import com.apimarketplace.orchestrator.services.state.WorkflowStateManager.StateChangeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listener that persists WorkflowStateManager state changes to StateSnapshot.
 *
 * <p>This ensures StateSnapshot (DB) stays in sync with in-memory state.
 * Persists: READY, RUNNING transitions (COMPLETED/FAILED/SKIPPED are handled by StepCompletionOrchestrator).
 *
 * <p>This is the SINGLE POINT of integration between WorkflowStateManager (in-memory)
 * and StateSnapshot (DB persistence).
 */
public class SnapshotStateListener implements StateChangeListener {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotStateListener.class);

    private final StateSnapshotService stateSnapshotService;
    private final String runId;

    public SnapshotStateListener(StateSnapshotService stateSnapshotService, String runId) {
        this.stateSnapshotService = stateSnapshotService;
        this.runId = runId;
    }

    @Override
    public void onStateChange(StateChangeEvent event) {
        if (!runId.equals(event.runId())) {
            return; // Safety check
        }

        String nodeId = event.nodeId().toString();
        NodeStatus newStatus = event.newStatus();

        switch (newStatus) {
            case READY -> {
                // Persist ready state to StateSnapshot
                stateSnapshotService.addReadyNode(runId, nodeId);
                logger.debug("[SnapshotListener] Persisted READY: runId={}, nodeId={}", runId, nodeId);
            }
            // RUNNING is tracked in-memory only (RunningNodeTracker)
            // COMPLETED, FAILED, SKIPPED are handled by StepCompletionOrchestrator
            default -> {
                // No-op for other states
            }
        }
    }
}
