package com.apimarketplace.orchestrator.services.state;

import java.util.*;

/**
 * Immutable graph representation of a workflow.
 * <p>
 * Built once from WorkflowPlan, then used for all state calculations.
 * Contains all nodes and edges in the workflow, with efficient lookup methods.
 * </p>
 */
public final class WorkflowGraph {

    private final Map<NodeId, WorkflowNode> nodes;
    private final List<NodeId> triggerIds;
    private final List<Edge> edges;

    /**
     * Creates a new WorkflowGraph with multiple triggers.
     *
     * @param nodes      Map of node IDs to WorkflowNode instances
     * @param triggerIds List of trigger node IDs (entry points)
     * @param edges      List of edges in the graph
     */
    public WorkflowGraph(Map<NodeId, WorkflowNode> nodes, List<NodeId> triggerIds, List<Edge> edges) {
        this.nodes = Map.copyOf(nodes);
        if (triggerIds == null || triggerIds.isEmpty()) {
            throw new IllegalArgumentException("At least one trigger ID is required");
        }
        this.triggerIds = List.copyOf(triggerIds);
        this.edges = List.copyOf(edges);

        // Validate that all triggers exist
        for (NodeId triggerId : this.triggerIds) {
            if (!this.nodes.containsKey(triggerId)) {
                throw new IllegalArgumentException("Trigger node not found in graph: " + triggerId);
            }
        }
    }

    /**
     * Creates a new WorkflowGraph with a single trigger (backward compatibility).
     *
     * @param nodes     Map of node IDs to WorkflowNode instances
     * @param triggerId The ID of the trigger node (entry point)
     * @param edges     List of edges in the graph
     */
    public WorkflowGraph(Map<NodeId, WorkflowNode> nodes, NodeId triggerId, List<Edge> edges) {
        this(nodes, List.of(Objects.requireNonNull(triggerId, "Trigger ID cannot be null")), edges);
    }

    // ========================================================================
    // NODE ACCESS METHODS
    // ========================================================================

    /**
     * Gets a node by its ID.
     *
     * @param id The node ID
     * @return The WorkflowNode
     * @throws IllegalArgumentException if node not found
     */
    public WorkflowNode getNode(NodeId id) {
        WorkflowNode node = nodes.get(id);
        if (node == null) {
            throw new IllegalArgumentException("Unknown node: " + id);
        }
        return node;
    }

    /**
     * Gets a node by its ID, returning null if not found.
     *
     * @param id The node ID
     * @return The WorkflowNode, or null if not found
     */
    public WorkflowNode getNodeOrNull(NodeId id) {
        return nodes.get(id);
    }

    /**
     * Checks if a node exists in the graph.
     *
     * @param id The node ID
     * @return true if the node exists
     */
    public boolean containsNode(NodeId id) {
        return nodes.containsKey(id);
    }

    /**
     * Gets the first trigger (entry point) node.
     * For backward compatibility with single-trigger workflows.
     *
     * @return The first trigger WorkflowNode
     */
    public WorkflowNode getTrigger() {
        return getNode(triggerIds.get(0));
    }

    /**
     * Gets the first trigger node ID.
     * For backward compatibility with single-trigger workflows.
     *
     * @return The first trigger NodeId
     */
    public NodeId getTriggerId() {
        return triggerIds.get(0);
    }

    /**
     * Gets all trigger node IDs.
     * Supports multi-workflow mode with multiple independent triggers.
     *
     * @return Unmodifiable list of all trigger NodeIds
     */
    public List<NodeId> getTriggerIds() {
        return triggerIds;
    }

    /**
     * Gets all trigger (entry point) nodes.
     * Supports multi-workflow mode with multiple independent triggers.
     *
     * @return List of all trigger WorkflowNodes
     */
    public List<WorkflowNode> getTriggers() {
        return triggerIds.stream()
                .map(this::getNode)
                .toList();
    }

    /**
     * Check if this graph has multiple triggers (multi-workflow mode).
     *
     * @return true if there are multiple trigger nodes
     */
    public boolean hasMultipleTriggers() {
        return triggerIds.size() > 1;
    }

    /**
     * Gets all nodes in the graph.
     *
     * @return Unmodifiable collection of all nodes
     */
    public Collection<WorkflowNode> getAllNodes() {
        return nodes.values();
    }

    /**
     * Gets all node IDs in the graph.
     *
     * @return Unmodifiable set of all node IDs
     */
    public Set<NodeId> getAllNodeIds() {
        return nodes.keySet();
    }

    /**
     * Gets the number of nodes in the graph.
     *
     * @return The node count
     */
    public int getNodeCount() {
        return nodes.size();
    }

    // ========================================================================
    // EDGE ACCESS METHODS
    // ========================================================================

    /**
     * Gets all edges in the graph.
     *
     * @return Unmodifiable list of all edges
     */
    public List<Edge> getEdges() {
        return edges;
    }

    /**
     * Gets the number of edges in the graph.
     *
     * @return The edge count
     */
    public int getEdgeCount() {
        return edges.size();
    }

    /**
     * Checks if an edge exists between two nodes.
     *
     * @param from Source node ID
     * @param to   Target node ID
     * @return true if edge exists
     */
    public boolean hasEdge(NodeId from, NodeId to) {
        return edges.stream()
                .anyMatch(e -> e.from().equals(from) && e.to().equals(to));
    }

    // ========================================================================
    // GRAPH TRAVERSAL METHODS
    // ========================================================================

    /**
     * Gets all nodes of a specific type.
     *
     * @param type The node type
     * @return List of nodes of that type
     */
    public List<WorkflowNode> getNodesByType(WorkflowNode.NodeType type) {
        return nodes.values().stream()
                .filter(n -> n.type() == type)
                .toList();
    }

    /**
     * Gets all step nodes (including agents).
     *
     * @return List of executable nodes
     */
    public List<WorkflowNode> getExecutableNodes() {
        return nodes.values().stream()
                .filter(WorkflowNode::isExecutable)
                .toList();
    }

    /**
     * Gets all control flow nodes (decisions and loops).
     *
     * @return List of control flow nodes
     */
    public List<WorkflowNode> getControlFlowNodes() {
        return nodes.values().stream()
                .filter(WorkflowNode::isControlFlow)
                .toList();
    }

    /**
     * Gets all merge nodes (nodes with multiple predecessors).
     *
     * @return List of merge nodes
     */
    public List<WorkflowNode> getMergeNodes() {
        return nodes.values().stream()
                .filter(WorkflowNode::isMergeNode)
                .toList();
    }

    /**
     * Gets all exit points (nodes with no successors).
     *
     * @return List of exit point nodes
     */
    public List<WorkflowNode> getExitPoints() {
        return nodes.values().stream()
                .filter(WorkflowNode::isExitPoint)
                .toList();
    }

    /**
     * Gets all predecessors of a node.
     *
     * @param nodeId The node ID
     * @return List of predecessor nodes
     */
    public List<WorkflowNode> getPredecessors(NodeId nodeId) {
        WorkflowNode node = getNode(nodeId);
        return node.predecessors().stream()
                .map(this::getNode)
                .toList();
    }

    /**
     * Gets all successors of a node.
     *
     * @param nodeId The node ID
     * @return List of successor nodes
     */
    public List<WorkflowNode> getSuccessors(NodeId nodeId) {
        WorkflowNode node = getNode(nodeId);
        return node.successors().stream()
                .map(this::getNode)
                .toList();
    }

    /**
     * Gets all nodes in a loop's body.
     *
     * @param loopId The loop node ID
     * @return List of nodes in the loop body
     */
    public List<WorkflowNode> getLoopBody(NodeId loopId) {
        WorkflowNode loop = getNode(loopId);
        if (!loop.isLoop()) {
            throw new IllegalArgumentException("Node is not a loop: " + loopId);
        }
        return loop.loopBody().stream()
                .map(this::getNode)
                .toList();
    }

    // ========================================================================
    // GRAPH ANALYSIS METHODS
    // ========================================================================

    /**
     * Performs a topological sort of the graph.
     * Returns nodes in execution order (predecessors before successors).
     *
     * @return List of node IDs in topological order
     */
    public List<NodeId> topologicalSort() {
        List<NodeId> result = new ArrayList<>();
        Set<NodeId> visited = new HashSet<>();
        Set<NodeId> inProgress = new HashSet<>();

        for (NodeId nodeId : nodes.keySet()) {
            if (!visited.contains(nodeId)) {
                topologicalSortVisit(nodeId, visited, inProgress, result);
            }
        }

        Collections.reverse(result);
        return result;
    }

    private void topologicalSortVisit(NodeId nodeId, Set<NodeId> visited,
                                       Set<NodeId> inProgress, List<NodeId> result) {
        if (inProgress.contains(nodeId)) {
            throw new IllegalStateException("Graph contains a cycle at node: " + nodeId);
        }
        if (visited.contains(nodeId)) {
            return;
        }

        inProgress.add(nodeId);

        WorkflowNode node = getNode(nodeId);
        for (NodeId successor : node.successors()) {
            topologicalSortVisit(successor, visited, inProgress, result);
        }

        inProgress.remove(nodeId);
        visited.add(nodeId);
        result.add(nodeId);
    }

    /**
     * Finds all paths from the trigger to a target node.
     *
     * @param targetId The target node ID
     * @return List of paths (each path is a list of node IDs)
     */
    public List<List<NodeId>> findPathsToNode(NodeId targetId) {
        List<List<NodeId>> allPaths = new ArrayList<>();
        List<NodeId> currentPath = new ArrayList<>();
        // Search from the first trigger (primary entry point)
        findPathsDfs(triggerIds.get(0), targetId, currentPath, allPaths, new HashSet<>());
        return allPaths;
    }

    private void findPathsDfs(NodeId current, NodeId target, List<NodeId> currentPath,
                               List<List<NodeId>> allPaths, Set<NodeId> visited) {
        if (visited.contains(current)) {
            return;
        }

        currentPath.add(current);
        visited.add(current);

        if (current.equals(target)) {
            allPaths.add(new ArrayList<>(currentPath));
        } else {
            WorkflowNode node = getNode(current);
            for (NodeId successor : node.successors()) {
                findPathsDfs(successor, target, currentPath, allPaths, visited);
            }
        }

        currentPath.remove(currentPath.size() - 1);
        visited.remove(current);
    }

    // ========================================================================
    // TOSTRING
    // ========================================================================

    @Override
    public String toString() {
        return "WorkflowGraph{" +
                "nodes=" + nodes.size() +
                ", edges=" + edges.size() +
                ", triggers=" + triggerIds +
                '}';
    }

    // ========================================================================
    // EDGE RECORD
    // ========================================================================

    /**
     * Represents an edge (arc) between two nodes in the workflow graph.
     *
     * @param from Source node ID
     * @param to   Target node ID
     */
    public record Edge(NodeId from, NodeId to) {

        public Edge {
            Objects.requireNonNull(from, "Edge source cannot be null");
            Objects.requireNonNull(to, "Edge target cannot be null");
        }

        @Override
        public String toString() {
            return from + " -> " + to;
        }
    }
}
