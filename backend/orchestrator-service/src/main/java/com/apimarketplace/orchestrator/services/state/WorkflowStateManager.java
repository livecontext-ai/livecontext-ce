package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.services.interfaces.StateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central state manager for workflow execution.
 * <p>
 * This is the single source of truth for all node and edge statuses in a workflow run.
 * It provides:
 * <ul>
 *   <li>Unified node status tracking</li>
 *   <li>Push-based ready state propagation (via ReadyPropagator)</li>
 *   <li>Automatic skip propagation (via ReadyPropagator)</li>
 *   <li>Edge status derivation from node statuses</li>
 *   <li>Event-based notifications via listeners</li>
 * </ul>
 * </p>
 * <p>
 * Thread-safety: This class is thread-safe and can be used concurrently.
 * </p>
 *
 * @see StateManager
 * @see ReadyPropagator
 */
@Service
public class WorkflowStateManager implements StateManager {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowStateManager.class);

    // Centralized state
    private final Map<NodeId, NodeStatus> nodeStatuses = new ConcurrentHashMap<>();
    private final Map<NodeId, Object> nodeResults = new ConcurrentHashMap<>();

    // Decision branch selection (nodeId -> set of selectedBranch port names)
    // Used during state reconstruction to know which branches were selected
    // In split context, multiple items can select different branches
    private final Map<NodeId, Set<String>> decisionBranches = new ConcurrentHashMap<>();

    // Workflow graph (immutable after init)
    private WorkflowGraph graph;
    private String runId;
    private boolean stepByStepMode;

    // Ready propagation (extracted for SRP)
    private ReadyPropagator readyPropagator;

    // Listeners for streaming and persistence
    private final List<StateChangeListener> listeners = new CopyOnWriteArrayList<>();

    // ========================================================================
    // INITIALIZATION
    // ========================================================================

    /**
     * Initialize the state manager with a workflow plan.
     * All nodes start as PENDING, trigger becomes READY.
     *
     * @param runId The workflow run ID
     * @param plan  The workflow plan
     */
    public void initialize(String runId, WorkflowPlan plan) {
        initialize(runId, plan, false);
    }

    /**
     * Initialize the state manager with a workflow plan and execution mode.
     * All nodes start as PENDING, all triggers become READY.
     *
     * @param runId The workflow run ID
     * @param plan  The workflow plan
     * @param stepByStepMode Whether the workflow is in step-by-step mode
     */
    public void initialize(String runId, WorkflowPlan plan, boolean stepByStepMode) {
        this.runId = runId;
        this.stepByStepMode = stepByStepMode;
        this.graph = WorkflowGraphBuilder.build(plan);
        initializeReadyPropagator();

        // All nodes start as PENDING
        for (WorkflowNode node : graph.getAllNodes()) {
            nodeStatuses.put(node.id(), NodeStatus.PENDING);
        }

        // All triggers become READY immediately (multi-workflow support)
        for (NodeId triggerId : graph.getTriggerIds()) {
            transitionTo(triggerId, NodeStatus.READY);
        }

        logger.info("[StateManager] Initialized for runId={}, triggers={} are READY, stepByStepMode={}",
                runId, graph.getTriggerIds(), stepByStepMode);
    }

    /**
     * Initialize the state manager with a pre-built graph.
     * Useful for testing or when the graph is already constructed.
     *
     * @param runId The workflow run ID
     * @param graph The pre-built workflow graph
     */
    public void initializeWithGraph(String runId, WorkflowGraph graph) {
        this.runId = runId;
        this.graph = graph;
        initializeReadyPropagator();

        // All nodes start as PENDING
        for (WorkflowNode node : graph.getAllNodes()) {
            nodeStatuses.put(node.id(), NodeStatus.PENDING);
        }

        // All triggers become READY immediately (multi-workflow support)
        for (NodeId triggerId : graph.getTriggerIds()) {
            transitionTo(triggerId, NodeStatus.READY);
        }

        logger.info("[StateManager] Initialized with graph for runId={}, triggers={} are READY",
                runId, graph.getTriggerIds());
    }

    /**
     * Reconstruct state from stored status map with multiple decision branch selections per node.
     * Used when resuming a workflow from database.
     * In split context, multiple items can select different branches for the same decision/classify node.
     *
     * @param runId                The workflow run ID
     * @param plan                 The workflow plan
     * @param savedStatuses        Map of node IDs to their saved statuses
     * @param savedDecisionBranches Map of decision node IDs to their selected branches (supports multiple)
     */
    public void loadFromSavedStateWithBranchSets(String runId, WorkflowPlan plan,
                                                  Map<String, NodeStatus> savedStatuses,
                                                  Map<String, Set<String>> savedDecisionBranches) {
        this.runId = runId;
        this.graph = WorkflowGraphBuilder.build(plan);
        initializeReadyPropagator();

        // Initialize all nodes as PENDING
        for (WorkflowNode node : graph.getAllNodes()) {
            nodeStatuses.put(node.id(), NodeStatus.PENDING);
        }

        // Apply saved statuses (without triggering listeners)
        // Try multiple matching strategies since IDs may have different formats
        logger.info("[StateManager] Loading {} saved statuses for runId={}", savedStatuses.size(), runId);
        int appliedCount = 0;
        for (Map.Entry<String, NodeStatus> entry : savedStatuses.entrySet()) {
            String key = entry.getKey();
            NodeId nodeId = findMatchingNodeId(key);

            if (nodeId != null) {
                nodeStatuses.put(nodeId, entry.getValue());
                appliedCount++;
                logger.info("[StateManager] Applied saved status: {} -> {} (matched as {})", key, entry.getValue(), nodeId);
            } else {
                logger.info("[StateManager] Saved status for '{}' = {} not found in graph", key, entry.getValue());
            }
        }

        // Apply saved decision branches
        // In split context, multiple items can select different branches, so we collect ALL branches
        logger.info("[StateManager] Loading {} saved decision branch entries", savedDecisionBranches.size());
        for (Map.Entry<String, Set<String>> entry : savedDecisionBranches.entrySet()) {
            String key = entry.getKey();
            NodeId nodeId = findMatchingNodeId(key);

            if (nodeId != null) {
                Set<String> branches = entry.getValue();
                decisionBranches.computeIfAbsent(nodeId, k -> ConcurrentHashMap.newKeySet()).addAll(branches);
                logger.info("[StateManager] Loaded decision branches: {} -> {}", nodeId, branches);
            } else {
                logger.info("[StateManager] Decision branch key '{}' not found in graph", key);
            }
        }

        // CRITICAL: Mark ALL pending triggers as READY
        // This handles the fresh start case where no steps have completed yet
        // In multi-workflow mode, all triggers are independent entry points
        for (NodeId triggerId : graph.getTriggerIds()) {
            if (getStatus(triggerId) == NodeStatus.PENDING) {
                logger.info("[StateManager] Setting trigger {} to READY (fresh start)", triggerId);
                nodeStatuses.put(triggerId, NodeStatus.READY);
            }
        }

        if (graph.hasMultipleTriggers()) {
            logger.info("[StateManager] Multi-workflow mode: {} independent triggers",
                graph.getTriggerIds().size());
        }

        // Log all node statuses before propagation
        logger.info("[StateManager] === Node statuses BEFORE propagation ===");
        for (Map.Entry<NodeId, NodeStatus> entry : nodeStatuses.entrySet()) {
            logger.info("[StateManager]   {} = {}", entry.getKey(), entry.getValue());
        }

        // Propagate decision branch selections to skip non-selected branches
        logger.info("[StateManager] Propagating decision branches...");
        propagateDecisionBranches();

        // Recalculate ready states for PENDING nodes (via ReadyPropagator)
        logger.info("[StateManager] Recalculating ready states for all PENDING nodes...");
        readyPropagator.recalculateAllPendingNodes();

        // Log all node statuses after propagation
        logger.info("[StateManager] === Node statuses AFTER propagation ===");
        for (Map.Entry<NodeId, NodeStatus> entry : nodeStatuses.entrySet()) {
            logger.info("[StateManager]   {} = {}", entry.getKey(), entry.getValue());
        }

        // Log ready nodes
        List<NodeId> readyNodes = getReadyNodes();
        logger.info("[StateManager] === READY nodes: {} ===", readyNodes);

        logger.info("[StateManager] Loaded state for runId={} with {} saved statuses ({} applied), triggers={}, readyNodes={}",
                runId, savedStatuses.size(), appliedCount, graph.getTriggerIds(), readyNodes);
    }

    /**
     * Initialize the ReadyPropagator with callback implementations.
     */
    private void initializeReadyPropagator() {
        this.readyPropagator = new ReadyPropagator(
            graph,
            this::getStatus,                         // StatusProvider
            new ReadyPropagator.StatusUpdater() {    // StatusUpdater
                @Override
                public void transitionTo(NodeId nodeId, NodeStatus status) {
                    WorkflowStateManager.this.transitionTo(nodeId, status);
                }
                @Override
                public void markSkipped(NodeId nodeId) {
                    WorkflowStateManager.this.markSkipped(nodeId);
                }
            },
            new ReadyPropagator.DecisionBranchProvider() {  // DecisionBranchProvider
                @Override
                public Set<String> getDecisionBranches(NodeId decisionId) {
                    return decisionBranches.get(decisionId);
                }
                @Override
                public void recalculateReadyState(NodeId nodeId) {
                    readyPropagator.recalculateReadyState(nodeId);
                }
            }
        );
    }

    /**
     * Propagate decision branch selections to mark non-selected branches as SKIPPED.
     * This is called during state reconstruction to ensure proper branch propagation.
     *
     * NOTE: The selectedBranch from DB can be either:
     * - A port name (e.g., "if", "else", "elseif_0", "category_0") - for step-by-step mode
     * - A target node (e.g., "mcp:list_bases_copy") - for auto mode
     * We need to handle both formats.
     *
     * In split context, multiple items can select different branches. ALL selected targets
     * should remain PENDING (to become READY), while unselected branches are SKIPPED.
     */
    private void propagateDecisionBranches() {
        for (Map.Entry<NodeId, Set<String>> entry : decisionBranches.entrySet()) {
            NodeId decisionId = entry.getKey();
            Set<String> selectedBranches = entry.getValue();

            if (selectedBranches == null || selectedBranches.isEmpty()) {
                continue;
            }

            WorkflowNode decisionNode = graph.getNodeOrNull(decisionId);
            if (decisionNode == null || (!decisionNode.isDecision() && decisionNode.type() != WorkflowNode.NodeType.AGENT)) {
                continue;
            }

            logger.info("[StateManager] Propagating decision branches for {}: selectedBranches={}",
                decisionId, selectedBranches);

            // Collect ALL selected targets from all branches
            Set<NodeId> selectedTargets = new HashSet<>();

            // If decision has port successors, use them for branch selection
            if (decisionNode.hasPortSuccessors()) {
                for (String selectedBranch : selectedBranches) {
                    // First, check if selectedBranch is a PORT name (if, else, elseif_N, category_N)
                    NodeId selectedTargetViaPort = decisionNode.getSuccessorForPort(selectedBranch);

                    if (selectedTargetViaPort != null) {
                        // selectedBranch is a port name - use the port mapping
                        logger.info("[StateManager] Decision {} selected port '{}' -> target {}",
                            decisionId, selectedBranch, selectedTargetViaPort);
                        selectedTargets.add(selectedTargetViaPort);
                    } else {
                        // selectedBranch might be a target node - try to match
                        NodeId selectedTargetId = NodeId.tryParse(selectedBranch);

                        for (Map.Entry<String, NodeId> portEntry : decisionNode.portSuccessors().entrySet()) {
                            NodeId targetId = portEntry.getValue();

                            if (targetId.equals(selectedTargetId) ||
                                targetId.toKey().equals(selectedBranch) ||
                                targetId.label().equals(selectedBranch)) {
                                logger.info("[StateManager] Decision {} selected target {} via match",
                                    decisionId, targetId);
                                selectedTargets.add(targetId);
                                break;
                            }
                        }
                    }
                }

                // Skip all OTHER branches (those not in selectedTargets)
                for (Map.Entry<String, NodeId> portEntry : decisionNode.portSuccessors().entrySet()) {
                    NodeId targetId = portEntry.getValue();

                    if (!selectedTargets.contains(targetId) && getStatus(targetId) == NodeStatus.PENDING) {
                        logger.info("[StateManager] Skipping non-selected branch: {} -> {} (selectedTargets: {})",
                            decisionId, targetId, selectedTargets);
                        nodeStatuses.put(targetId, NodeStatus.SKIPPED);
                    }
                }
            } else {
                // No port successors - try to match by successor list
                for (String selectedBranch : selectedBranches) {
                    NodeId selectedTargetId = NodeId.tryParse(selectedBranch);

                    for (NodeId successorId : decisionNode.successors()) {
                        if (successorId.equals(selectedTargetId) ||
                            successorId.toKey().equals(selectedBranch) ||
                            successorId.label().equals(selectedBranch)) {
                            selectedTargets.add(successorId);
                            break;
                        }
                    }
                }

                // Skip all OTHER successors
                for (NodeId successorId : decisionNode.successors()) {
                    if (!selectedTargets.contains(successorId) && getStatus(successorId) == NodeStatus.PENDING) {
                        logger.info("[StateManager] Skipping non-selected successor: {} -> {} (selectedTargets: {})",
                            decisionId, successorId, selectedTargets);
                        nodeStatuses.put(successorId, NodeStatus.SKIPPED);
                    }
                }
            }
        }
    }

    /**
     * Find a matching node ID in the graph for a given key.
     * Tries multiple strategies to match IDs with different formats.
     */
    private NodeId findMatchingNodeId(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        
        // Strategy 1: Direct parse
        NodeId directId = NodeId.tryParse(key);
        if (directId != null && graph.containsNode(directId)) {
            return directId;
        }
        
        // Strategy 2: Try common prefixes if key doesn't have a prefix
        if (!key.contains(":")) {
            String normalized = com.apimarketplace.orchestrator.utils.LabelNormalizer.normalizeLabel(key);
            if (normalized == null) normalized = key;
            
            // Try loop: prefix (common for whileb, whilea, etc.)
            NodeId loopId = NodeId.tryParse("core:" + normalized);
            if (loopId != null && graph.containsNode(loopId)) {
                return loopId;
            }
            
            // Try mcp: prefix
            NodeId stepId = NodeId.tryParse("mcp:" + normalized);
            if (stepId != null && graph.containsNode(stepId)) {
                return stepId;
            }
            
            // Try decision: prefix
            NodeId decisionId = NodeId.tryParse("core:" + normalized);
            if (decisionId != null && graph.containsNode(decisionId)) {
                return decisionId;
            }
            
            // Try trigger: prefix
            NodeId triggerId = NodeId.tryParse("trigger:" + normalized);
            if (triggerId != null && graph.containsNode(triggerId)) {
                return triggerId;
            }
        }
        
        return null;
    }

    // ========================================================================
    // STATUS TRANSITIONS
    // ========================================================================

    /**
     * Mark a node as running.
     *
     * @param nodeId The node to mark as running
     * @throws IllegalStateException if transition is not valid
     */
    public void markRunning(NodeId nodeId) {
        validateTransition(nodeId, NodeStatus.RUNNING);
        transitionTo(nodeId, NodeStatus.RUNNING);
    }

    /**
     * Mark a node as completed with its result.
     *
     * @param nodeId The node to mark as completed
     * @param result The execution result (can be null)
     */
    public void markCompleted(NodeId nodeId, Object result) {
        validateTransition(nodeId, NodeStatus.COMPLETED);

        if (result != null) {
            nodeResults.put(nodeId, result);
        }

        transitionTo(nodeId, NodeStatus.COMPLETED);

        // Propagate completion to successors (via ReadyPropagator)
        WorkflowNode node = graph.getNode(nodeId);
        readyPropagator.propagateCompletionToSuccessors(node);
    }

    /**
     * Mark a node as skipped.
     *
     * @param nodeId The node to mark as skipped
     */
    public void markSkipped(NodeId nodeId) {
        NodeStatus current = getStatus(nodeId);

        // Already terminal? Ignore
        if (current.isTerminal()) {
            logger.debug("[StateManager] Node {} already terminal ({}), skip ignored",
                    nodeId, current);
            return;
        }

        transitionTo(nodeId, NodeStatus.SKIPPED);

        // Propagate skip to successors (via ReadyPropagator)
        WorkflowNode node = graph.getNode(nodeId);
        readyPropagator.propagateSkipToSuccessors(node);
    }

    /**
     * Mark a node as failed.
     *
     * <p>After marking the node as FAILED, propagates to successors so that:
     * <ul>
     *   <li>Merge nodes can recalculate readiness (FAILED counts as "resolved")</li>
     *   <li>Single-predecessor successors get skipped (execution path is broken)</li>
     * </ul>
     * This prevents deadlocks in fork/merge patterns where one branch fails.</p>
     *
     * @param nodeId The node to mark as failed
     * @param error  The error that caused the failure
     */
    public void markFailed(NodeId nodeId, Throwable error) {
        validateTransition(nodeId, NodeStatus.FAILED);

        if (error != null) {
            nodeResults.put(nodeId, error);
        }

        transitionTo(nodeId, NodeStatus.FAILED);

        // Propagate failure to successors (via ReadyPropagator)
        // This allows merge nodes to recalculate readiness and prevents deadlocks
        WorkflowNode node = graph.getNode(nodeId);
        readyPropagator.propagateFailureToSuccessors(node);
    }

    /**
     * Record a decision branch selection.
     * Used during execution to remember which branch was taken.
     * In split context, multiple items can select different branches.
     *
     * @param decisionId The decision node ID
     * @param branchPort The selected branch port (if, else, elseif_N, category_N)
     */
    public void recordDecisionBranch(NodeId decisionId, String branchPort) {
        if (decisionId != null && branchPort != null) {
            decisionBranches.computeIfAbsent(decisionId, k -> ConcurrentHashMap.newKeySet()).add(branchPort);
            logger.debug("[StateManager] Recorded decision branch: {} -> {}", decisionId, branchPort);
        }
    }

    /**
     * Get the selected branch for a decision node.
     * Returns the first branch if multiple are recorded (for backward compatibility).
     *
     * @param decisionId The decision node ID
     * @return The selected branch port, or null if not recorded
     */
    public String getDecisionBranch(NodeId decisionId) {
        Set<String> branches = decisionBranches.get(decisionId);
        return (branches != null && !branches.isEmpty()) ? branches.iterator().next() : null;
    }

    /**
     * Get all selected branches for a decision node.
     * In split context, multiple items can select different branches.
     *
     * @param decisionId The decision node ID
     * @return All selected branch ports, or null if not recorded
     */
    public Set<String> getDecisionBranches(NodeId decisionId) {
        return decisionBranches.get(decisionId);
    }

    // ========================================================================
    // QUERY METHODS
    // ========================================================================

    /**
     * Get the current status of a node.
     *
     * @param nodeId The node ID
     * @return The current status (PENDING if not found)
     */
    public NodeStatus getStatus(NodeId nodeId) {
        return nodeStatuses.getOrDefault(nodeId, NodeStatus.PENDING);
    }

    /**
     * Get the current status of a node by its string key.
     *
     * @param nodeKey The node key (e.g., "mcp:my_step")
     * @return The current status (PENDING if not found)
     */
    public NodeStatus getStatus(String nodeKey) {
        NodeId nodeId = NodeId.tryParse(nodeKey);
        return nodeId != null ? getStatus(nodeId) : NodeStatus.PENDING;
    }

    /**
     * Get all nodes that are currently READY.
     *
     * @return List of ready node IDs
     */
    public List<NodeId> getReadyNodes() {
        return nodeStatuses.entrySet().stream()
                .filter(e -> e.getValue() == NodeStatus.READY)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Get all nodes with a specific status.
     *
     * @param status The status to filter by
     * @return List of node IDs with that status
     */
    public List<NodeId> getNodesByStatus(NodeStatus status) {
        return nodeStatuses.entrySet().stream()
                .filter(e -> e.getValue() == status)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Get the result of a completed node.
     *
     * @param nodeId The node ID
     * @return The result, or null if not available
     */
    public Object getResult(NodeId nodeId) {
        return nodeResults.get(nodeId);
    }

    /**
     * Get all node statuses as a map.
     *
     * @return Unmodifiable map of node IDs to statuses
     */
    public Map<NodeId, NodeStatus> getAllStatuses() {
        return Collections.unmodifiableMap(new HashMap<>(nodeStatuses));
    }

    /**
     * Get the status of an edge (derived from node statuses).
     *
     * @param from Source node ID
     * @param to   Target node ID
     * @return The edge status
     */
    public NodeStatus getEdgeStatus(NodeId from, NodeId to) {
        NodeStatus fromStatus = getStatus(from);
        NodeStatus toStatus = getStatus(to);

        // Priority 1: SKIPPED if either endpoint is SKIPPED
        if (fromStatus == NodeStatus.SKIPPED || toStatus == NodeStatus.SKIPPED) {
            return NodeStatus.SKIPPED;
        }

        // Priority 2: FAILED if either endpoint is FAILED
        if (fromStatus == NodeStatus.FAILED || toStatus == NodeStatus.FAILED) {
            return NodeStatus.FAILED;
        }

        // Priority 3: COMPLETED if both are COMPLETED
        if (fromStatus == NodeStatus.COMPLETED && toStatus == NodeStatus.COMPLETED) {
            return NodeStatus.COMPLETED;
        }

        // Priority 4: RUNNING if source is done and target is active
        if (fromStatus == NodeStatus.COMPLETED || fromStatus == NodeStatus.RUNNING) {
            if (toStatus == NodeStatus.RUNNING || toStatus == NodeStatus.READY) {
                return NodeStatus.RUNNING;
            }
        }

        // Default: PENDING
        return NodeStatus.PENDING;
    }

    /**
     * Get all edge states for streaming emission.
     *
     * @return List of all edge states
     */
    public List<EdgeState> getAllEdgeStates() {
        return graph.getEdges().stream()
                .map(e -> new EdgeState(e.from(), e.to(), getEdgeStatus(e.from(), e.to())))
                .toList();
    }

    /**
     * Get the workflow graph.
     *
     * @return The workflow graph
     */
    public WorkflowGraph getGraph() {
        return graph;
    }

    /**
     * Get the run ID.
     *
     * @return The run ID
     */
    public String getRunId() {
        return runId;
    }

    /**
     * Check if the workflow is in step-by-step mode.
     *
     * @return true if step-by-step mode is enabled
     */
    public boolean isStepByStepMode() {
        return stepByStepMode;
    }

    /**
     * Set the step-by-step mode.
     * Used when the mode is changed after initialization.
     *
     * @param stepByStepMode Whether to enable step-by-step mode
     */
    public void setStepByStepMode(boolean stepByStepMode) {
        this.stepByStepMode = stepByStepMode;
        logger.info("[StateManager] Step-by-step mode set to {} for runId={}", stepByStepMode, runId);
    }

    /**
     * Check if the workflow is complete (all nodes are terminal).
     *
     * @return true if workflow is complete
     */
    public boolean isWorkflowComplete() {
        return nodeStatuses.values().stream().allMatch(NodeStatus::isTerminal);
    }

    /**
     * Check if the workflow has any failed nodes.
     *
     * @return true if any node is FAILED
     */
    public boolean hasFailures() {
        return nodeStatuses.values().stream().anyMatch(s -> s == NodeStatus.FAILED);
    }

    // ========================================================================
    // INTERNAL HELPERS
    // ========================================================================

    private void transitionTo(NodeId nodeId, NodeStatus newStatus) {
        NodeStatus oldStatus = nodeStatuses.put(nodeId, newStatus);

        if (!Objects.equals(oldStatus, newStatus)) {
            logger.info("[StateManager] {} : {} -> {}", nodeId, oldStatus, newStatus);

            // Notify all listeners
            StateChangeEvent event = new StateChangeEvent(runId, nodeId, oldStatus, newStatus);
            for (StateChangeListener listener : listeners) {
                try {
                    listener.onStateChange(event);
                } catch (Exception e) {
                    logger.error("[StateManager] Listener error for {}: {}", nodeId, e.getMessage(), e);
                }
            }
        }
    }

    private void validateTransition(NodeId nodeId, NodeStatus targetStatus) {
        NodeStatus current = getStatus(nodeId);
        if (!current.canTransitionTo(targetStatus)) {
            throw new IllegalStateException(
                    String.format("Cannot transition %s from %s to %s (invalid transition)",
                            nodeId, current, targetStatus));
        }
    }

    // ========================================================================
    // LISTENER MANAGEMENT
    // ========================================================================

    /**
     * Add a state change listener.
     *
     * @param listener The listener to add
     */
    public void addListener(StateChangeListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Remove a state change listener.
     *
     * @param listener The listener to remove
     */
    public void removeListener(StateChangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Remove all listeners.
     */
    public void clearListeners() {
        listeners.clear();
    }

    // ========================================================================
    // INNER TYPES
    // ========================================================================

    /**
     * Represents the status of an edge between two nodes.
     */
    public record EdgeState(NodeId from, NodeId to, NodeStatus status) {

        @Override
        public String toString() {
            return from + " -> " + to + " [" + status + "]";
        }
    }

    /**
     * Event fired when a node's status changes.
     */
    public record StateChangeEvent(
            String runId,
            NodeId nodeId,
            NodeStatus oldStatus,
            NodeStatus newStatus
    ) {
        @Override
        public String toString() {
            return String.format("[%s] %s: %s -> %s", runId, nodeId, oldStatus, newStatus);
        }
    }

    /**
     * Listener interface for state change events.
     */
    public interface StateChangeListener {
        /**
         * Called when a node's status changes.
         *
         * @param event The state change event
         */
        void onStateChange(StateChangeEvent event);
    }
}
