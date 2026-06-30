package com.apimarketplace.orchestrator.services.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a node in the workflow graph with its metadata.
 * <p>
 * This is an immutable value object that contains:
 * <ul>
 *   <li>The node identifier</li>
 *   <li>The node type (trigger, step, decision, loop, agent)</li>
 *   <li>Predecessor and successor relationships</li>
 *   <li>Merge strategy for convergence points</li>
 *   <li>Loop relationships (post-loop decision)</li>
 * </ul>
 * </p>
 */
public final class WorkflowNode {

    private final NodeId id;
    private final NodeType type;
    private final List<NodeId> predecessors;
    private final List<NodeId> successors;
    private final MergeStrategy mergeStrategy;
    private final NodeId postLoopDecision;
    private final List<NodeId> loopBody;
    // For decision nodes: maps port name (if, else, elseif_0, etc.) to target node
    private final Map<String, NodeId> portSuccessors;

    /**
     * Node type enumeration.
     */
    public enum NodeType {
        /**
         * Entry point of the workflow.
         */
        TRIGGER,

        /**
         * MCP tool node (API call, transformation, etc.).
         */
        MCP,

        /**
         * Conditional branching node (one branch selected).
         */
        DECISION,

        /**
         * Loop controller node (while, for-each).
         */
        LOOP,

        /**
         * AI Agent node with LLM and tool calling capabilities.
         */
        AGENT,

        /**
         * Fork node - parallel branching (ALL branches execute).
         */
        FORK,

        /**
         * Merge node - synchronization point (waits for predecessors).
         */
        MERGE,

        /**
         * Aggregate node - collects N items into 1 (data transformation).
         */
        AGGREGATE
    }

    /**
     * Strategy for merge nodes (nodes with multiple predecessors).
     */
    public enum MergeStrategy {
        /**
         * Ready when ANY predecessor is completed.
         * Used for convergence after decision branches.
         */
        ANY,

        /**
         * Ready when ALL predecessors are completed or skipped.
         * Used for synchronization points.
         */
        ALL
    }

    // Private constructor - use Builder
    private WorkflowNode(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "Node id cannot be null");
        this.type = Objects.requireNonNull(builder.type, "Node type cannot be null");
        this.predecessors = Collections.unmodifiableList(new ArrayList<>(builder.predecessors));
        this.successors = Collections.unmodifiableList(new ArrayList<>(builder.successors));
        this.mergeStrategy = builder.mergeStrategy;
        this.postLoopDecision = builder.postLoopDecision;
        this.loopBody = builder.loopBody != null
                ? Collections.unmodifiableList(new ArrayList<>(builder.loopBody))
                : Collections.emptyList();
        this.portSuccessors = builder.portSuccessors != null
                ? Collections.unmodifiableMap(new HashMap<>(builder.portSuccessors))
                : Collections.emptyMap();
    }

    // ========================================================================
    // GETTERS
    // ========================================================================

    public NodeId id() {
        return id;
    }

    public NodeType type() {
        return type;
    }

    public List<NodeId> predecessors() {
        return predecessors;
    }

    public List<NodeId> successors() {
        return successors;
    }

    public MergeStrategy mergeStrategy() {
        return mergeStrategy;
    }

    public NodeId postLoopDecision() {
        return postLoopDecision;
    }

    public List<NodeId> loopBody() {
        return loopBody;
    }

    /**
     * Returns the port-to-successor mapping for decision/loop nodes.
     * For decision nodes: maps port names (if, else, elseif_0, etc.) to target nodes.
     * For loop nodes: maps port names (body, exit) to target nodes.
     *
     * @return Unmodifiable map of port names to target node IDs
     */
    public Map<String, NodeId> portSuccessors() {
        return portSuccessors;
    }

    /**
     * Get the successor node for a specific port (for decision nodes).
     *
     * @param port The port name (if, else, elseif_0, etc.)
     * @return The target node ID, or null if not found
     */
    public NodeId getSuccessorForPort(String port) {
        return portSuccessors.get(port);
    }

    /**
     * Check if this node has port-based successors (is a decision or loop node with ports).
     *
     * @return true if this node has port successors
     */
    public boolean hasPortSuccessors() {
        return !portSuccessors.isEmpty();
    }

    // ========================================================================
    // CONVENIENCE METHODS
    // ========================================================================

    /**
     * Returns true if this node has multiple predecessors (convergence point).
     * Merge nodes require special handling for ready state calculation.
     *
     * @return true if this is a merge node
     */
    public boolean isMergeNode() {
        return predecessors.size() > 1;
    }

    /**
     * Returns true if this is a loop node with a post-loop decision.
     *
     * @return true if this is a loop with post-decision
     */
    public boolean hasPostLoopDecision() {
        return type == NodeType.LOOP && postLoopDecision != null;
    }

    /**
     * Returns true if this is a trigger node (workflow entry point).
     *
     * @return true if type is TRIGGER
     */
    public boolean isTrigger() {
        return type == NodeType.TRIGGER;
    }

    /**
     * Returns true if this is a step node.
     *
     * @return true if type is STEP
     */
    public boolean isStep() {
        return type == NodeType.MCP;
    }

    /**
     * Returns true if this is a decision node.
     *
     * @return true if type is DECISION
     */
    public boolean isDecision() {
        return type == NodeType.DECISION;
    }

    /**
     * Returns true if this is a loop node.
     *
     * @return true if type is LOOP
     */
    public boolean isLoop() {
        return type == NodeType.LOOP;
    }

    /**
     * Returns true if this is an agent node.
     *
     * @return true if type is AGENT
     */
    public boolean isAgent() {
        return type == NodeType.AGENT;
    }

    /**
     * Returns true if this is a fork node.
     *
     * @return true if type is FORK
     */
    public boolean isFork() {
        return type == NodeType.FORK;
    }

    /**
     * Returns true if this is a merge node (explicit MERGE type).
     * Note: isMergeNode() checks for multiple predecessors (convergence point).
     *
     * @return true if type is MERGE
     */
    public boolean isMerge() {
        return type == NodeType.MERGE;
    }

    /**
     * Returns true if this is an executable node (requires user action).
     * Executable nodes are: TRIGGER, STEP, AGENT.
     *
     * @return true if executable
     */
    public boolean isExecutable() {
        return type == NodeType.TRIGGER || type == NodeType.MCP || type == NodeType.AGENT;
    }

    /**
     * Returns true if this is a control flow node.
     * Control flow nodes are: DECISION, LOOP, FORK.
     *
     * @return true if control flow
     */
    public boolean isControlFlow() {
        return type == NodeType.DECISION || type == NodeType.LOOP || type == NodeType.FORK;
    }

    /**
     * Returns true if this node has no predecessors (entry point).
     *
     * @return true if no predecessors
     */
    public boolean isEntryPoint() {
        return predecessors.isEmpty();
    }

    /**
     * Returns true if this node has no successors (exit point).
     *
     * @return true if no successors
     */
    public boolean isExitPoint() {
        return successors.isEmpty();
    }

    // ========================================================================
    // EQUALS, HASHCODE, TOSTRING
    // ========================================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkflowNode that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "WorkflowNode{" +
                "id=" + id +
                ", type=" + type +
                ", predecessors=" + predecessors.size() +
                ", successors=" + successors.size() +
                (isMergeNode() ? ", mergeStrategy=" + mergeStrategy : "") +
                '}';
    }

    // ========================================================================
    // BUILDER
    // ========================================================================

    /**
     * Creates a new builder for WorkflowNode.
     *
     * @param id   The node identifier
     * @param type The node type
     * @return A new Builder instance
     */
    public static Builder builder(NodeId id, NodeType type) {
        return new Builder(id, type);
    }

    /**
     * Builder class for WorkflowNode.
     */
    public static final class Builder {
        private final NodeId id;
        private final NodeType type;
        private final List<NodeId> predecessors = new ArrayList<>();
        private final List<NodeId> successors = new ArrayList<>();
        private MergeStrategy mergeStrategy = MergeStrategy.ALL;
        private NodeId postLoopDecision;
        private List<NodeId> loopBody;
        private Map<String, NodeId> portSuccessors;

        private Builder(NodeId id, NodeType type) {
            this.id = id;
            this.type = type;
        }

        public Builder addPredecessor(NodeId predecessor) {
            if (predecessor != null) {
                this.predecessors.add(predecessor);
            }
            return this;
        }

        public Builder predecessors(List<NodeId> predecessors) {
            if (predecessors != null) {
                this.predecessors.addAll(predecessors);
            }
            return this;
        }

        public Builder addSuccessor(NodeId successor) {
            if (successor != null) {
                this.successors.add(successor);
            }
            return this;
        }

        public Builder successors(List<NodeId> successors) {
            if (successors != null) {
                this.successors.addAll(successors);
            }
            return this;
        }

        public Builder mergeStrategy(MergeStrategy strategy) {
            this.mergeStrategy = strategy;
            return this;
        }

        public Builder postLoopDecision(NodeId postLoopDecision) {
            this.postLoopDecision = postLoopDecision;
            return this;
        }

        public Builder loopBody(List<NodeId> loopBody) {
            this.loopBody = loopBody;
            return this;
        }

        /**
         * Add a port-to-successor mapping for decision/loop nodes.
         *
         * @param port      The port name (if, else, elseif_0, body, exit, etc.)
         * @param successor The target node ID
         * @return This builder
         */
        public Builder addPortSuccessor(String port, NodeId successor) {
            if (port != null && successor != null) {
                if (this.portSuccessors == null) {
                    this.portSuccessors = new HashMap<>();
                }
                this.portSuccessors.put(port, successor);
            }
            return this;
        }

        /**
         * Set the entire port-to-successor mapping.
         *
         * @param portSuccessors The map of port names to target node IDs
         * @return This builder
         */
        public Builder portSuccessors(Map<String, NodeId> portSuccessors) {
            if (portSuccessors != null) {
                this.portSuccessors = new HashMap<>(portSuccessors);
            }
            return this;
        }

        /**
         * Get the node type (for inspection during graph building).
         */
        public NodeType getNodeType() {
            return this.type;
        }

        public WorkflowNode build() {
            return new WorkflowNode(this);
        }
    }
}
