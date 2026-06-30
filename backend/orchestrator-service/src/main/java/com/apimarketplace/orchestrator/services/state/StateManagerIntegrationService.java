package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import com.apimarketplace.orchestrator.services.state.WorkflowStateManager.StateChangeEvent;
import com.apimarketplace.orchestrator.services.state.WorkflowStateManager.StateChangeListener;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridge service that integrates WorkflowStateManager with the existing codebase.
 * <p>
 * This service provides:
 * <ul>
 *   <li>Run-scoped WorkflowStateManager instances</li>
 *   <li>Conversion between old status formats and NodeStatus</li>
 *   <li>Integration hooks for StepCompletionOrchestrator</li>
 *   <li>Ready state queries for WorkflowResumeService</li>
 * </ul>
 * </p>
 */
@Service
public class StateManagerIntegrationService implements RunScopedCache {

    private static final Logger logger = LoggerFactory.getLogger(StateManagerIntegrationService.class);

    private final WorkflowEventPublisher eventPublisher;
    private final StateSnapshotService stateSnapshotService;

    // Run-scoped state managers
    private final Map<String, WorkflowStateManager> stateManagers = new ConcurrentHashMap<>();

    // Run-scoped streaming listeners
    private final Map<String, StreamingStateListener> streamingListeners = new ConcurrentHashMap<>();

    // Run-scoped snapshot listeners (for persisting READY/RUNNING to StateSnapshot)
    private final Map<String, SnapshotStateListener> snapshotListeners = new ConcurrentHashMap<>();

    public StateManagerIntegrationService(WorkflowEventPublisher eventPublisher, StateSnapshotService stateSnapshotService) {
        this.eventPublisher = eventPublisher;
        this.stateSnapshotService = stateSnapshotService;
    }

    // ========================================================================
    // INITIALIZATION AND LIFECYCLE
    // ========================================================================

    /**
     * Initialize a state manager for a new workflow run.
     *
     * @param execution The workflow execution
     * @return The initialized WorkflowStateManager
     */
    public WorkflowStateManager initializeForRun(WorkflowExecution execution) {
        return initializeForRun(execution, true, false);
    }

    /**
     * Initialize a state manager for a new workflow run.
     *
     * @param execution The workflow execution
     * @param enableStreaming Whether to enable streaming event emission
     * @return The initialized WorkflowStateManager
     */
    public WorkflowStateManager initializeForRun(WorkflowExecution execution, boolean enableStreaming) {
        return initializeForRun(execution, enableStreaming, false);
    }

    /**
     * Initialize a state manager for a new workflow run.
     *
     * @param execution The workflow execution
     * @param enableStreaming Whether to enable streaming event emission
     * @param stepByStepMode Whether the workflow is in step-by-step mode
     * @return The initialized WorkflowStateManager
     */
    public WorkflowStateManager initializeForRun(WorkflowExecution execution, boolean enableStreaming, boolean stepByStepMode) {
        String runId = execution.getRunId();
        WorkflowPlan plan = execution.getPlan();

        // Don't re-initialize if a StateManager already exists for this run.
        // This preserves state transitions (e.g. PENDING→READY) from previous steps.
        WorkflowStateManager existing = stateManagers.get(runId);
        if (existing != null) {
            logger.debug("[StateIntegration] StateManager already exists for runId={}, skipping re-initialization", runId);
            return existing;
        }

        WorkflowStateManager manager = new WorkflowStateManager();
        manager.initialize(runId, plan, stepByStepMode);

        stateManagers.put(runId, manager);

        // Attach streaming listener if enabled
        if (enableStreaming) {
            StreamingStateListener streamingListener = new StreamingStateListener(eventPublisher, manager);
            manager.addListener(streamingListener);
            streamingListeners.put(runId, streamingListener);
            logger.info("[StateIntegration] Attached streaming listener for runId={}", runId);
        }

        // Attach snapshot listener to persist READY/RUNNING state to StateSnapshot
        SnapshotStateListener snapshotListener = new SnapshotStateListener(stateSnapshotService, runId);
        manager.addListener(snapshotListener);
        snapshotListeners.put(runId, snapshotListener);
        logger.info("[StateIntegration] Attached snapshot listener for runId={}", runId);

        logger.info("[StateIntegration] Initialized state manager for runId={}, stepByStepMode={}", runId, stepByStepMode);
        return manager;
    }

    /**
     * Get or create a state manager for a workflow run.
     *
     * @param execution The workflow execution
     * @return The WorkflowStateManager for this run
     */
    public WorkflowStateManager getOrCreate(WorkflowExecution execution) {
        String runId = execution.getRunId();
        return stateManagers.computeIfAbsent(runId, id -> {
            WorkflowStateManager manager = new WorkflowStateManager();
            manager.initialize(id, execution.getPlan());
            logger.info("[StateIntegration] Created state manager on-demand for runId={}", runId);
            return manager;
        });
    }

    /**
     * Set the execution mode for a run's state manager.
     * Call this after the execution tree is built and the mode is known.
     *
     * @param runId The workflow run ID
     * @param stepByStepMode Whether to enable step-by-step mode
     */
    public void setStepByStepMode(String runId, boolean stepByStepMode) {
        WorkflowStateManager manager = stateManagers.get(runId);
        if (manager != null) {
            manager.setStepByStepMode(stepByStepMode);
            logger.info("[StateIntegration] Set step-by-step mode to {} for runId={}", stepByStepMode, runId);
        } else {
            logger.warn("[StateIntegration] Cannot set step-by-step mode - no state manager for runId={}", runId);
        }
    }

    /**
     * Get an existing state manager for a run.
     *
     * @param runId The run ID
     * @return The WorkflowStateManager, or null if not found
     */
    public WorkflowStateManager get(String runId) {
        return stateManagers.get(runId);
    }

    /**
     * Cleanup a state manager when a run completes.
     *
     * @param runId The run ID to cleanup
     */
    public void cleanup(String runId) {
        // Remove streaming listener
        StreamingStateListener streamingListener = streamingListeners.remove(runId);
        if (streamingListener != null) {
            streamingListener.reset();
        }

        // Remove snapshot listener
        snapshotListeners.remove(runId);

        // Remove state manager
        WorkflowStateManager manager = stateManagers.remove(runId);
        if (manager != null) {
            manager.clearListeners();
            logger.info("[StateIntegration] Cleaned up state manager for runId={}", runId);
        }
    }

    // ========================================================================
    // STATE RECONSTRUCTION (for workflow resume)
    // ========================================================================

    /**
     * Reconstruct state from saved step statuses.
     * Used when resuming a workflow from database.
     *
     * @param execution       The workflow execution
     * @param completedStepIds Set of completed step IDs
     * @param failedStepIds    Set of failed step IDs
     * @param skippedStepIds   Set of skipped step IDs
     * @return The reconstructed WorkflowStateManager
     */
    public WorkflowStateManager reconstructState(
            WorkflowExecution execution,
            Set<String> completedStepIds,
            Set<String> failedStepIds,
            Set<String> skippedStepIds) {

        String runId = execution.getRunId();
        WorkflowPlan plan = execution.getPlan();

        // Build status map from sets
        Map<String, NodeStatus> savedStatuses = new HashMap<>();

        for (String stepId : completedStepIds) {
            savedStatuses.put(stepId, NodeStatus.COMPLETED);
        }
        for (String stepId : failedStepIds) {
            savedStatuses.put(stepId, NodeStatus.FAILED);
        }
        for (String stepId : skippedStepIds) {
            savedStatuses.put(stepId, NodeStatus.SKIPPED);
        }

        // Create and load state manager
        WorkflowStateManager manager = new WorkflowStateManager();
        manager.loadFromSavedStateWithBranchSets(runId, plan, savedStatuses, Collections.emptyMap());

        stateManagers.put(runId, manager);

        // Attach streaming listener
        StreamingStateListener streamingListener = new StreamingStateListener(eventPublisher, manager);
        manager.addListener(streamingListener);
        streamingListeners.put(runId, streamingListener);

        // Attach snapshot listener to persist READY/RUNNING state to StateSnapshot
        SnapshotStateListener snapshotListener = new SnapshotStateListener(stateSnapshotService, runId);
        manager.addListener(snapshotListener);
        snapshotListeners.put(runId, snapshotListener);

        logger.info("[StateIntegration] Reconstructed state for runId={} with {} completed, {} failed, {} skipped",
                runId, completedStepIds.size(), failedStepIds.size(), skippedStepIds.size());

        return manager;
    }

    /**
     * Get ready nodes using the new StateManager if available,
     * otherwise return empty set (caller should fall back to legacy).
     *
     * @param runId The run ID
     * @return Set of ready node IDs, or empty if StateManager not initialized
     */
    public Set<String> getReadyNodesIfAvailable(String runId) {
        WorkflowStateManager manager = stateManagers.get(runId);
        if (manager == null) {
            return Collections.emptySet();
        }

        Set<String> readyNodes = new HashSet<>();
        for (NodeId nodeId : manager.getReadyNodes()) {
            readyNodes.add(nodeId.toKey());
        }
        return readyNodes;
    }

    /**
     * Check if StateManager is initialized for a run.
     *
     * @param runId The run ID
     * @return true if StateManager exists for this run
     */
    public boolean hasStateManager(String runId) {
        return stateManagers.containsKey(runId);
    }

    // ========================================================================
    // STATUS UPDATE METHODS (called from StepCompletionOrchestrator)
    // ========================================================================

    /**
     * Mark a node as completed.
     *
     * @param runId  The run ID
     * @param nodeId The node ID
     * @param result The execution result (can be null)
     */
    public void markNodeCompleted(String runId, String nodeId, Object result) {
        WorkflowStateManager manager = stateManagers.get(runId);
        if (manager == null) {
            logger.warn("[StateIntegration] No state manager for runId={}, skipping markCompleted", runId);
            return;
        }

        NodeId id = parseNodeId(nodeId);
        if (id == null) {
            logger.warn("[StateIntegration] Could not parse nodeId={}", nodeId);
            return;
        }

        try {
            manager.markCompleted(id, result);
            logger.debug("[StateIntegration] Marked {} as COMPLETED", nodeId);
        } catch (IllegalStateException e) {
            logger.warn("[StateIntegration] markCompleted failed for {}: {}", nodeId, e.getMessage());
        }
    }

    /**
     * Mark a node as skipped.
     *
     * @param runId  The run ID
     * @param nodeId The node ID
     */
    public void markNodeSkipped(String runId, String nodeId) {
        WorkflowStateManager manager = stateManagers.get(runId);
        if (manager == null) {
            logger.warn("[StateIntegration] No state manager for runId={}, skipping markSkipped", runId);
            return;
        }

        NodeId id = parseNodeId(nodeId);
        if (id == null) {
            logger.warn("[StateIntegration] Could not parse nodeId={}", nodeId);
            return;
        }

        manager.markSkipped(id);
        logger.debug("[StateIntegration] Marked {} as SKIPPED (with propagation)", nodeId);
    }

    /**
     * Mark a node as failed.
     *
     * @param runId  The run ID
     * @param nodeId The node ID
     * @param error  The error that caused the failure
     */
    public void markNodeFailed(String runId, String nodeId, Throwable error) {
        WorkflowStateManager manager = stateManagers.get(runId);
        if (manager == null) {
            logger.warn("[StateIntegration] No state manager for runId={}, skipping markFailed", runId);
            return;
        }

        NodeId id = parseNodeId(nodeId);
        if (id == null) {
            logger.warn("[StateIntegration] Could not parse nodeId={}", nodeId);
            return;
        }

        try {
            manager.markFailed(id, error);
            logger.debug("[StateIntegration] Marked {} as FAILED", nodeId);
        } catch (IllegalStateException e) {
            logger.warn("[StateIntegration] markFailed failed for {}: {}", nodeId, e.getMessage());
        }
    }

    // ========================================================================
    // QUERY METHODS (called from WorkflowResumeService)
    // ========================================================================

    /**
     * Get all ready nodes for a run.
     *
     * @param runId The run ID
     * @return Set of ready node IDs as strings
     */
    public Set<String> getReadyNodes(String runId) {
        WorkflowStateManager manager = stateManagers.get(runId);
        if (manager == null) {
            return Collections.emptySet();
        }

        Set<String> readyNodes = new HashSet<>();
        for (NodeId nodeId : manager.getReadyNodes()) {
            readyNodes.add(nodeId.toKey());
        }
        return readyNodes;
    }

    /**
     * Get the status of a specific node.
     *
     * @param runId  The run ID
     * @param nodeId The node ID
     * @return The status as a string ("PENDING", "READY", etc.), or "PENDING" if not found
     */
    public String getNodeStatus(String runId, String nodeId) {
        WorkflowStateManager manager = stateManagers.get(runId);
        if (manager == null) {
            return NodeStatus.PENDING.name();
        }

        NodeId id = parseNodeId(nodeId);
        if (id == null) {
            return NodeStatus.PENDING.name();
        }

        return manager.getStatus(id).name();
    }

    /**
     * Get all edge states for a run.
     *
     * @param runId The run ID
     * @return List of edge state maps suitable for streaming emission
     */
    public List<Map<String, Object>> getEdgeStates(String runId) {
        WorkflowStateManager manager = stateManagers.get(runId);
        if (manager == null) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> edgeStates = new ArrayList<>();
        for (WorkflowStateManager.EdgeState edge : manager.getAllEdgeStates()) {
            Map<String, Object> edgeMap = new HashMap<>();
            edgeMap.put("from", edge.from().toKey());
            edgeMap.put("to", edge.to().toKey());
            edgeMap.put("status", edge.status().name());
            edgeStates.add(edgeMap);
        }
        return edgeStates;
    }

    /**
     * Check if the workflow is complete.
     *
     * @param runId The run ID
     * @return true if all nodes are terminal
     */
    public boolean isWorkflowComplete(String runId) {
        WorkflowStateManager manager = stateManagers.get(runId);
        return manager != null && manager.isWorkflowComplete();
    }

    /**
     * Check if the workflow has any failures.
     *
     * @param runId The run ID
     * @return true if any node is FAILED
     */
    public boolean hasFailures(String runId) {
        WorkflowStateManager manager = stateManagers.get(runId);
        return manager != null && manager.hasFailures();
    }

    // ========================================================================
    // LISTENER MANAGEMENT
    // ========================================================================

    /**
     * Add a listener to a run's state manager.
     *
     * @param runId    The run ID
     * @param listener The listener to add
     */
    public void addListener(String runId, StateChangeListener listener) {
        WorkflowStateManager manager = stateManagers.get(runId);
        if (manager != null) {
            manager.addListener(listener);
        }
    }

    /**
     * Remove a listener from a run's state manager.
     *
     * @param runId    The run ID
     * @param listener The listener to remove
     */
    public void removeListener(String runId, StateChangeListener listener) {
        WorkflowStateManager manager = stateManagers.get(runId);
        if (manager != null) {
            manager.removeListener(listener);
        }
    }

    // ========================================================================
    // CONVERSION METHODS
    // ========================================================================

    /**
     * Convert a legacy status string to NodeStatus.
     *
     * @param legacyStatus The legacy status string (e.g., "SUCCESS", "FAILED")
     * @return The corresponding NodeStatus
     */
    public static NodeStatus fromLegacyStatus(String legacyStatus) {
        if (legacyStatus == null) {
            return NodeStatus.PENDING;
        }

        return switch (legacyStatus.toUpperCase()) {
            case "SUCCESS", "COMPLETED", "DONE" -> NodeStatus.COMPLETED;
            case "RUNNING", "EXECUTING", "IN_PROGRESS" -> NodeStatus.RUNNING;
            case "SKIPPED", "BYPASSED" -> NodeStatus.SKIPPED;
            case "FAILED", "ERROR", "FAILURE" -> NodeStatus.FAILED;
            case "READY", "WAITING" -> NodeStatus.READY;
            default -> NodeStatus.PENDING;
        };
    }

    /**
     * Convert NodeStatus to a legacy status string.
     *
     * @param status The NodeStatus
     * @return The legacy status string (e.g., "SUCCESS", "FAILED")
     */
    public static String toLegacyStatus(NodeStatus status) {
        if (status == null) {
            return "PENDING";
        }

        return switch (status) {
            case COMPLETED -> "SUCCESS";
            case RUNNING, COLLECTING -> "RUNNING";
            case SKIPPED -> "SKIPPED";
            case FAILED -> "FAILED";
            case READY -> "READY";
            case PENDING, WAITING_TRIGGER -> "PENDING";
            case AWAITING_SIGNAL -> "AWAITING_SIGNAL";
        };
    }

    // ========================================================================
    // PRIVATE HELPERS
    // ========================================================================

    /**
     * Parse a node ID string into a NodeId object.
     * Handles both prefixed ("mcp:my_step") and non-prefixed ("my_step") formats.
     *
     * @param nodeIdStr The node ID string
     * @return The parsed NodeId, or null if parsing fails
     */
    private NodeId parseNodeId(String nodeIdStr) {
        if (nodeIdStr == null || nodeIdStr.isBlank()) {
            return null;
        }

        try {
            return NodeId.parse(nodeIdStr);
        } catch (IllegalArgumentException e) {
            logger.warn("[StateIntegration] Failed to parse nodeId: {}", nodeIdStr);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RunScopedCache Implementation
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void cleanupRun(String runId) {
        cleanup(runId);
    }

    @Override
    public String getCacheName() {
        return "StateManagerCache";
    }

    @Override
    public CacheDomain getDomain() {
        return CacheDomain.STATE;
    }

    @Override
    public int getCacheSize() {
        return stateManagers.size() + streamingListeners.size() + snapshotListeners.size();
    }
}
