package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.services.state.WorkflowStateManager.StateChangeEvent;
import com.apimarketplace.orchestrator.services.state.WorkflowStateManager.StateChangeListener;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.events.EdgeLifecycle;
import com.apimarketplace.orchestrator.services.streaming.events.StepLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Listener that emits streaming events when node statuses change.
 * <p>
 * This listener bridges the new WorkflowStateManager with the existing streaming infrastructure.
 * It converts StateChangeEvents to StepStatusEvents and EdgeStatusEvents.
 * </p>
 */
public class StreamingStateListener implements StateChangeListener {

    private static final Logger logger = LoggerFactory.getLogger(StreamingStateListener.class);

    private final WorkflowEventPublisher eventPublisher;
    private final WorkflowStateManager stateManager;

    // Track which edges we've already emitted for this run
    private final Set<String> emittedEdges = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<>());

    public StreamingStateListener(WorkflowEventPublisher eventPublisher, WorkflowStateManager stateManager) {
        this.eventPublisher = eventPublisher;
        this.stateManager = stateManager;
    }

    @Override
    public void onStateChange(StateChangeEvent event) {
        String runId = event.runId();
        NodeId nodeId = event.nodeId();
        NodeStatus newStatus = event.newStatus();

        logger.debug("[StreamingStateListener] {} : {} -> {}", nodeId, event.oldStatus(), newStatus);

        // Emit node status event
        emitNodeStatusEvent(runId, nodeId, newStatus);

        // Emit edge status events for incoming edges
        emitIncomingEdgeEvents(runId, nodeId, newStatus);

        // If node became READY, emit step_by_step_ready event (only in step-by-step mode)
        if (newStatus == NodeStatus.READY && stateManager.isStepByStepMode()) {
            emitStepByStepReadyEvent(runId, nodeId);
        }
    }

    // ========================================================================
    // NODE STATUS EVENTS
    // ========================================================================

    private void emitNodeStatusEvent(String runId, NodeId nodeId, NodeStatus status) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "step_status");
        payload.put("runId", runId);
        payload.put("nodeId", nodeId.toKey());
        payload.put("stepId", nodeId.toKey());
        payload.put("label", nodeId.label());
        payload.put("status", status.name());
        payload.put("timestamp", System.currentTimeMillis());

        StepLifecycle lifecycle = mapStatusToLifecycle(status);

        eventPublisher.emitStep(runId, nodeId.toKey(), payload, lifecycle);
    }

    private StepLifecycle mapStatusToLifecycle(NodeStatus status) {
        return switch (status) {
            case PENDING -> StepLifecycle.PENDING;
            case READY, WAITING_TRIGGER -> StepLifecycle.PENDING;
            case RUNNING, COLLECTING, AWAITING_SIGNAL -> StepLifecycle.RUNNING;
            case COMPLETED -> StepLifecycle.SUCCESS;
            case SKIPPED -> StepLifecycle.SKIPPED;
            case FAILED -> StepLifecycle.FAILURE;
        };
    }

    // ========================================================================
    // EDGE STATUS EVENTS
    // ========================================================================

    private void emitIncomingEdgeEvents(String runId, NodeId nodeId, NodeStatus status) {
        WorkflowGraph graph = stateManager.getGraph();
        if (graph == null) {
            return;
        }

        WorkflowNode node = graph.getNodeOrNull(nodeId);
        if (node == null) {
            return;
        }

        // Emit edge status for each incoming edge
        for (NodeId predecessorId : node.predecessors()) {
            EdgeLifecycle edgeLifecycle = determineEdgeLifecycle(predecessorId, nodeId);
            emitEdgeEvent(runId, predecessorId, nodeId, edgeLifecycle);
        }
    }

    private EdgeLifecycle determineEdgeLifecycle(NodeId from, NodeId to) {
        NodeStatus fromStatus = stateManager.getStatus(from);
        NodeStatus toStatus = stateManager.getStatus(to);

        // SKIPPED takes priority
        if (fromStatus == NodeStatus.SKIPPED || toStatus == NodeStatus.SKIPPED) {
            return EdgeLifecycle.SKIPPED;
        }

        // FAILED
        if (fromStatus == NodeStatus.FAILED || toStatus == NodeStatus.FAILED) {
            return EdgeLifecycle.SKIPPED; // Treat failed as edge not taken
        }

        // Both COMPLETED
        if (fromStatus == NodeStatus.COMPLETED && toStatus == NodeStatus.COMPLETED) {
            return EdgeLifecycle.COMPLETED;
        }

        // Source completed, target is running/ready
        if (fromStatus == NodeStatus.COMPLETED) {
            if (toStatus == NodeStatus.RUNNING || toStatus == NodeStatus.READY) {
                return EdgeLifecycle.RUNNING;
            }
        }

        // Default: registered but not yet active
        return EdgeLifecycle.REGISTERED;
    }

    private void emitEdgeEvent(String runId, NodeId from, NodeId to, EdgeLifecycle lifecycle) {
        String edgeId = from.toKey() + "->" + to.toKey();

        // Avoid emitting duplicate REGISTERED events
        if (lifecycle == EdgeLifecycle.REGISTERED) {
            if (!emittedEdges.add(edgeId + ":REGISTERED")) {
                return;
            }
        }

        eventPublisher.emitEdge(
                runId,
                edgeId,
                from.toKey(),
                to.toKey(),
                lifecycle
        );
    }

    // ========================================================================
    // STEP-BY-STEP EVENTS
    // ========================================================================

    private void emitStepByStepReadyEvent(String runId, NodeId readyNodeId) {
        // Get all currently ready nodes
        java.util.List<NodeId> allReadyNodes = stateManager.getReadyNodes();

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "step_by_step_ready");
        payload.put("runId", runId);
        payload.put("readyNodes", allReadyNodes.stream()
                .map(NodeId::toKey)
                .toList());
        payload.put("newlyReady", readyNodeId.toKey());
        payload.put("workflowComplete", stateManager.isWorkflowComplete());
        payload.put("timestamp", System.currentTimeMillis());

        // Use workflow status event for this
        eventPublisher.emitWorkflowStatus(
                runId,
                "STEP_BY_STEP_READY",
                "Step " + readyNodeId.toKey() + " is ready for execution",
                payload,
                false // not terminal
        );
    }

    // ========================================================================
    // LIFECYCLE
    // ========================================================================

    /**
     * Clear tracked state (call when listener is detached).
     */
    public void reset() {
        emittedEdges.clear();
    }
}
