package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;

import java.util.List;
import java.util.Map;

/**
 * Base interface for all execution nodes in the workflow tree.
 *
 * Core Principles:
 * - Each node knows how to execute itself
 * - Each node knows its children (next nodes)
 * - Execution flows naturally through tree traversal
 * - No special cases - all nodes treated uniformly
 */
public interface ExecutionNode {

    /**
     * Unique identifier for this node.
     */
    String getNodeId();

    /**
     * Type of this node (TRIGGER, STEP, DECISION, LOOP, MERGE, END).
     */
    NodeType getType();

    /**
     * Checks if this node can execute given the current context.
     * Returns false if prerequisites are not met.
     */
    boolean canExecute(ExecutionContext context);

    /**
     * Executes this node.
     * Returns the execution result.
     */
    NodeExecutionResult execute(ExecutionContext context);

    /**
     * Called after node execution completes.
     * Used for side effects: emit events, persist, record metrics.
     */
    void onComplete(ExecutionContext context, NodeExecutionResult result);

    /**
     * Returns the next nodes to execute based on the execution result.
     *
     * Examples:
     * - StepNode: returns all successors
     * - DecisionNode: returns nodes for selected branch
     * - Back-edge target: returns body nodes based on condition
     * - MergeNode: returns all successors
     */
    List<ExecutionNode> getNextNodes(NodeExecutionResult result);

    /**
     * Optional metadata for debugging/logging.
     */
    default Map<String, Object> getMetadata() {
        return Map.of();
    }

    /**
     * Returns all child nodes that should be traversed for tree search/indexing.
     * This includes nodes that are not returned by getNextNodes() but are still
     * part of this node's subtree (e.g., decision branches,
     * choice options, agent category targets).
     *
     * <p>This method enables polymorphic tree traversal without instanceof checks.
     *
     * <p>Default implementation returns empty list for simple nodes.
     * Override in nodes with special branching:
     * <ul>
     *   <li>DecisionNode: all branch nodes (if, elseif, else)</li>
     *   <li>OptionNode: all choice nodes</li>
     *   <li>AgentNode: category targets + guardrail targets</li>
     *   <li>UserApprovalNode: all port targets</li>
     * </ul>
     *
     * @return list of child nodes for traversal (may be empty, never null)
     */
    default List<ExecutionNode> getAllChildNodes() {
        return List.of();
    }

    /**
     * Returns the direct successors of this node in the workflow graph.
     * These are nodes that come after this node completes normally.
     *
     * <p>For branching nodes (Decision, Loop, etc.), this returns successors
     * that are reached after all branches complete (e.g., loop exit successors).
     *
     * <p>Default implementation returns empty list. Override in BaseNode
     * and other nodes that track their successors.
     *
     * @return list of successor nodes (may be empty, never null)
     */
    default List<ExecutionNode> getSuccessors() {
        return List.of();
    }

    /**
     * Returns the IDs of predecessor nodes in the workflow graph.
     * Predecessors are nodes that have edges leading to this node.
     *
     * <p>This is used for merge detection and parallel execution synchronization.
     *
     * <p>Default implementation returns empty list. Override in BaseNode.
     *
     * @return list of predecessor node IDs (may be empty, never null)
     */
    default List<String> getPredecessorIds() {
        return List.of();
    }

    /**
     * Adds a predecessor node ID to this node's predecessor list.
     * Used during edge wiring to build the graph structure.
     *
     * <p>Default implementation does nothing (for nodes that don't track predecessors).
     * Override in BaseNode to add to the predecessor list.
     *
     * @param predecessorId The node ID of the predecessor to add
     */
    default void addPredecessor(String predecessorId) {
        // Default: no-op for nodes that don't track predecessors
    }

    /**
     * Adds a successor node to this node's successor list.
     * Used during edge wiring to build the graph structure.
     *
     * <p>Default implementation does nothing (for nodes that don't track successors).
     * Override in BaseNode to add to the successor list.
     *
     * @param successor The successor node to add
     */
    default void addSuccessor(ExecutionNode successor) {
        // Default: no-op for nodes that don't track successors
    }

    /**
     * Checks if this node is an implicit merge point (has multiple predecessors).
     * Implicit merges must wait for all incoming branches to complete before executing.
     *
     * <p>This enables polymorphic merge detection without instanceof checks.
     *
     * <p>Default implementation returns false. Override in BaseNode to check
     * predecessorIds.size() > 1.
     *
     * @return true if this node has multiple predecessors, false otherwise
     */
    default boolean isImplicitMerge() {
        return false;
    }

    /**
     * Checks if this node is a branching node that selects between multiple paths.
     * Branching nodes emit edges differently: selected branches get COMPLETED edges,
     * non-selected branches get SKIPPED edges.
     *
     * <p>This enables polymorphic branching node handling without instanceof checks.
     * Used by EdgeStatusEmitter for unified edge emission.
     *
     * <p>Default implementation returns false. Override in:
     * <ul>
     *   <li>DecisionNode: returns true</li>
     *   <li>OptionNode: returns true</li>
     *   <li>UserApprovalNode: returns true</li>
     *   <li>AgentNode (CLASSIFY mode): returns true based on execution result</li>
     * </ul>
     *
     * @return true if this node selects between multiple branches, false otherwise
     */
    default boolean isBranchingNode() {
        return false;
    }

    /**
     * Checks if skipped branches should be propagated to downstream nodes.
     * For most branching nodes, when a branch is skipped, the skip status
     * should propagate to all downstream nodes on that branch.
     *
     * <p>However, for some nodes like Classify (agent), skip is per-item,
     * not global. Different items may select different categories, so
     * skip propagation would incorrectly mark all categories as skipped.
     *
     * <p>This enables unified edge emission for branching nodes without
     * special-casing classify nodes.
     *
     * <p>Default implementation returns true. Override in AgentNode to
     * return false for classify nodes.
     *
     * @return true if skip should propagate to downstream nodes, false otherwise
     */
    default boolean shouldPropagateSkipOnBranching() {
        return true;
    }

    /**
     * Checks if this node is a merge node (explicit or implicit merge point).
     * Merge nodes synchronize multiple incoming branches before continuing.
     *
     * <p>This enables polymorphic merge detection without instanceof checks.
     * Used by V2SkipPropagationService to avoid skipping merge nodes
     * (only edges to merge nodes are marked as skipped).
     *
     * <p>Default implementation returns false. Override in MergeNode.
     *
     * @return true if this node is a merge node, false otherwise
     */
    default boolean isMergeNode() {
        return false;
    }

    /**
     * Checks if this node is a split node.
     * Split nodes iterate over a list of items, executing body nodes for each item.
     *
     * <p>This enables polymorphic split detection without instanceof checks.
     * Used by UnifiedExecutionEngine for split execution.
     *
     * <p>Default implementation returns false. Override in SplitNode.
     *
     * @return true if this node is a split node, false otherwise
     */
    default boolean isSplitNode() {
        return false;
    }

    /**
     * Checks if this node is a decision node.
     * Decision nodes select one branch based on condition evaluation.
     *
     * <p>This enables polymorphic decision detection without instanceof checks.
     * Used by V2ExecutionEventService and SplitAwareNodeExecutor for condition persistence.
     *
     * <p>Default implementation returns false. Override in DecisionNode.
     *
     * @return true if this node is a decision node, false otherwise
     */
    default boolean isDecisionNode() {
        return false;
    }

    /**
     * Checks if this node is an option node.
     * Option nodes select one choice based on expression evaluation.
     *
     * <p>This enables polymorphic option detection without instanceof checks.
     * Used by V2ExecutionEventService for condition persistence.
     *
     * <p>Default implementation returns false. Override in OptionNode.
     *
     * @return true if this node is an option node, false otherwise
     */
    default boolean isOptionNode() {
        return false;
    }

    /**
     * Checks if this node is an agent node.
     * Agent nodes execute AI agent tasks (classify, guardrail, standard agent).
     *
     * <p>This enables polymorphic agent detection without instanceof checks.
     * Used by V2ExecutionEventService and ReadyNodeCalculator for agent-specific handling.
     *
     * <p>Default implementation returns false. Override in AgentNode.
     *
     * @return true if this node is an agent node, false otherwise
     */
    default boolean isAgentNode() {
        return false;
    }

    /**
     * Checks if this node is an aggregate node.
     * Aggregate nodes collect N split items into a single output (N -> 1 reduction).
     *
     * <p>This enables polymorphic aggregate detection without instanceof checks.
     * Used by UnifiedExecutionEngine for split-aggregate handling.
     *
     * <p>Default implementation returns false. Override in AggregateNode.
     *
     * @return true if this node is an aggregate node, false otherwise
     */
    default boolean isAggregateNode() {
        return false;
    }

    /**
     * Checks if this node is a find node.
     * Find nodes execute a CRUD read query and split results into parallel items.
     *
     * <p>This enables polymorphic find detection without instanceof checks.
     * Used by UnifiedExecutionEngine for find+split execution.
     *
     * <p>Default implementation returns false. Override in FindNode.
     *
     * @return true if this node is a find node, false otherwise
     */
    default boolean isFindNode() {
        return false;
    }

    /**
     * Checks if this node is a stop node.
     * Exit nodes end execution along their branch.
     * Other parallel branches (fork, split) continue normally.
     *
     * <p>This enables polymorphic exit detection without instanceof checks.
     * Used by EdgeStatusEmitter for special exit node handling.
     *
     * <p>Default implementation returns false. Override in ExitNode.
     *
     * @return true if this node is an exit node, false otherwise
     */
    default boolean isExitNode() {
        return false;
    }

    /**
     * Checks if this node is a trigger node.
     * Trigger nodes initiate workflow execution.
     *
     * <p>This enables polymorphic trigger detection without instanceof checks.
     * Used by ExecutionTreeBuilder for finding workflow entry points.
     *
     * <p>Default implementation returns false. Override in TriggerNode.
     *
     * @return true if this node is a trigger node, false otherwise
     */
    default boolean isTriggerNode() {
        return false;
    }

    /**
     * Checks if this node is a fork node.
     * Fork nodes execute all branches in parallel.
     *
     * <p>This enables polymorphic fork detection without instanceof checks.
     * Used by ForkNodeWirer for fork branch wiring.
     *
     * <p>Default implementation returns false. Override in ForkNode.
     *
     * @return true if this node is a fork node, false otherwise
     */
    default boolean isForkNode() {
        return false;
    }

    /**
     * Checks if this node is an approval node.
     * Approval nodes wait for user approval before continuing.
     *
     * <p>This enables polymorphic approval detection without instanceof checks.
     * Used by ApprovalNodeWirer for approval port wiring.
     *
     * <p>Default implementation returns false. Override in UserApprovalNode.
     *
     * @return true if this node is an approval node, false otherwise
     */
    default boolean isApprovalNode() {
        return false;
    }

    /**
     * Checks if this node is a switch node.
     * Switch nodes select one case based on value matching.
     *
     * <p>This enables polymorphic switch detection without instanceof checks.
     *
     * <p>Default implementation returns false. Override in SwitchNode.
     *
     * @return true if this node is a switch node, false otherwise
     */
    default boolean isSwitchNode() {
        return false;
    }

    /**
     * Checks if this node is an end node.
     * End nodes mark the completion of a workflow branch.
     *
     * <p>This enables polymorphic end detection without instanceof checks.
     *
     * <p>Default implementation returns false. Override in EndNode.
     *
     * @return true if this node is an end node, false otherwise
     */
    default boolean isEndNode() {
        return false;
    }

    /**
     * Checks if this node is a StopOnError node.
     * StopOnError nodes terminate the ENTIRE workflow (all branches),
     * unlike ExitNode which only ends one branch.
     *
     * <p>When detected after execution, the engine throws
     * {@link com.apimarketplace.orchestrator.execution.v2.engine.WorkflowStoppedException}
     * to cancel all parallel branches.
     *
     * <p>Default implementation returns false. Override in StopOnErrorNode.
     *
     * @return true if this node is a stop-on-error node, false otherwise
     */
    default boolean isStopOnErrorNode() {
        return false;
    }

    /**
     * Determines if this node should skip split context handling.
     * Control flow nodes (Split, Merge, Decision, Switch, Fork, Trigger, End)
     * handle their own context and don't need split context propagation.
     *
     * <p>This enables polymorphic split handling decisions without NodeType checks.
     * Used by SplitAwareNodeExecutor to determine execution path.
     *
     * <p>Default implementation returns false (most nodes use split context).
     * Override in control flow nodes that manage their own context.
     *
     * @return true if this node skips split handling, false otherwise
     */
    default boolean skipsSplitHandling() {
        return false;
    }

    /**
     * Returns the list expression for split nodes.
     * This expression is evaluated to get the list of items to iterate over.
     *
     * <p>This enables polymorphic split configuration access without instanceof checks.
     * Used by UnifiedExecutionEngine for split execution.
     *
     * <p>Default implementation returns null. Override in SplitNode.
     *
     * @return the list expression string, or null for non-split nodes
     */
    default String getListExpression() {
        return null;
    }

    /**
     * Returns the maximum items for split nodes.
     * Limits the number of items processed in parallel split execution.
     *
     * <p>This enables polymorphic split configuration access without instanceof checks.
     * Used by UnifiedExecutionEngine for split execution.
     *
     * <p>Default implementation returns 0. Override in SplitNode.
     *
     * @return maximum items (0 for non-split nodes)
     */
    default int getSplitMaxItems() {
        return 0;
    }

    /**
     * Returns the body nodes for container nodes (Split).
     * Body nodes are the nodes executed inside the container's iteration.
     *
     * <p>This enables polymorphic body node access without instanceof checks.
     * Used by EdgeStatusEmitter and skip propagation services.
     *
     * <p>Default implementation returns empty list. Override in SplitNode.
     *
     * @return list of body nodes (may be empty, never null)
     */
    default List<ExecutionNode> getBodyNodes() {
        return List.of();
    }

    /**
     * Returns all category target nodes for classify agent nodes.
     * Used by ReadyNodeCalculator to traverse all branches in split context.
     *
     * <p>This enables polymorphic access to classify targets without instanceof checks.
     *
     * <p>Default implementation returns empty collection. Override in AgentNode.
     *
     * @return collection of category target nodes (may be empty, never null)
     */
    default java.util.Collection<ExecutionNode> getAllCategoryTargetNodes() {
        return List.of();
    }

    /**
     * Returns child nodes that should be skipped based on the execution result.
     * For branching nodes, this returns nodes on non-selected branches.
     *
     * <p>This enables polymorphic skip propagation without instanceof checks.
     * Used by EdgeStatusEmitter and skip propagation services.
     *
     * <p>Default implementation computes: getAllChildNodes() - getNextNodes(result).
     * Override in specific branching nodes (DecisionNode, OptionNode, etc.) if needed.
     *
     * @param result The execution result (may contain branch selection info)
     * @return list of child nodes to skip (may be empty, never null)
     */
    default List<ExecutionNode> getSkippedChildNodes(NodeExecutionResult result) {
        List<ExecutionNode> all = getAllChildNodes();
        if (all.isEmpty()) {
            return List.of();
        }
        List<ExecutionNode> selected = getNextNodes(result);
        if (selected.isEmpty()) {
            return all; // No selection means all children are skipped
        }
        // Filter out selected nodes
        java.util.Set<String> selectedIds = selected.stream()
            .map(ExecutionNode::getNodeId)
            .collect(java.util.stream.Collectors.toSet());
        return all.stream()
            .filter(n -> !selectedIds.contains(n.getNodeId()))
            .toList();
    }

    // ========================================================================
    // WIRING METHODS (used during graph construction by Wirer classes)
    // ========================================================================

    /**
     * Returns all branching targets mapped by port name.
     * Used by EdgeStatusEmitter for port-qualified edge emission.
     *
     * <p>For branching nodes where multiple ports can share the same target
     * (e.g., classify categories all pointing to the same node), port-qualified
     * edge IDs are needed to distinguish COMPLETED vs SKIPPED edges.
     *
     * <p>Default implementation returns empty map. Override in all branching nodes:
     * <ul>
     *   <li>DecisionNode: "if" -> [nodes], "elseif_0" -> [nodes], "else" -> [nodes]</li>
     *   <li>SwitchNode: "case_0" -> [nodes], "case_1" -> [nodes], "default" -> [nodes]</li>
     *   <li>OptionNode: "choice_0" -> [nodes], "choice_1" -> [nodes]</li>
     *   <li>AgentNode (classify): "category_0" -> [nodes], "category_1" -> [nodes]</li>
     *   <li>AgentNode (guardrail): "pass" -> [nodes], "fail" -> [nodes]</li>
     *   <li>UserApprovalNode: "approved" -> [nodes], "rejected" -> [nodes], "timeout" -> [nodes]</li>
     * </ul>
     *
     * @return map of port name to target nodes (may be empty, never null)
     */
    default java.util.Map<String, java.util.List<ExecutionNode>> getBranchTargetsByPort() {
        return java.util.Map.of();
    }

    /**
     * Returns the port name that was selected based on the execution result.
     * Used by EdgeStatusEmitter to determine which port's edges are COMPLETED
     * and which are SKIPPED.
     *
     * <p>Default implementation returns null. Override in all branching nodes.
     *
     * @param result The execution result containing branch selection info
     * @return the selected port name (e.g., "if", "category_0", "pass"), or null
     */
    default String getSelectedPort(NodeExecutionResult result) {
        return null;
    }

    /**
     * Adds a target node to a branch at the specified index.
     * Used by DecisionNodeWirer and OptionNodeWirer for index-based branch wiring.
     *
     * <p>This enables polymorphic branch wiring without instanceof checks.
     *
     * <p>Default implementation does nothing. Override in DecisionNode and OptionNode.
     *
     * @param branchIndex The index of the branch to wire
     * @param target The target node to add to the branch
     */
    default void addBranchTarget(int branchIndex, ExecutionNode target) {
        // Default: no-op for nodes that don't support branch wiring
    }

    /**
     * Adds a target node to a port (string-based key).
     * Used by ApprovalNodeWirer for port-based wiring (approved, rejected, timeout).
     *
     * <p>This enables polymorphic port wiring without instanceof checks.
     *
     * <p>Default implementation does nothing. Override in UserApprovalNode.
     *
     * @param port The port name (e.g., "approved", "rejected", "timeout")
     * @param target The target node to add to the port
     */
    default void addPortTarget(String port, ExecutionNode target) {
        // Default: no-op for nodes that don't support port wiring
    }

    /**
     * Adds a category target for classify agent nodes.
     * Used by EdgeWiringOrchestrator for classify node wiring.
     *
     * <p>This enables polymorphic classify wiring without instanceof checks.
     *
     * <p>Default implementation does nothing. Override in AgentNode.
     *
     * @param port The category port (e.g., "category_0", "category_1")
     * @param target The target node for this category
     */
    default void addCategoryTarget(String port, ExecutionNode target) {
        // Default: no-op for nodes that don't support category wiring
    }

    /**
     * Adds a guardrail target for guardrail agent nodes.
     * Used by EdgeWiringOrchestrator for guardrail node wiring.
     *
     * <p>This enables polymorphic guardrail wiring without instanceof checks.
     *
     * <p>Default implementation does nothing. Override in AgentNode.
     *
     * @param port The guardrail port (e.g., "pass", "fail")
     * @param target The target node for this guardrail outcome
     */
    default void addGuardrailTarget(String port, ExecutionNode target) {
        // Default: no-op for nodes that don't support guardrail wiring
    }

    /**
     * Adds a fork branch with the given ID, label, and target node.
     * Used by ForkNodeWirer for fork branch wiring.
     *
     * <p>This enables polymorphic fork wiring without instanceof checks.
     *
     * <p>Default implementation does nothing. Override in ForkNode.
     *
     * @param branchId The branch identifier (e.g., "branch_0")
     * @param branchLabel The human-readable branch label (e.g., "Branch 0")
     * @param target The target node for this branch
     */
    default void addForkBranch(String branchId, String branchLabel, ExecutionNode target) {
        // Default: no-op for nodes that don't support fork branch wiring
    }

    /**
     * Adds a loop body target for loop nodes.
     *
     * <p>Default implementation does nothing. Override in LoopNode.
     *
     * @param target The target node for the loop body entry
     */
    default void addLoopBodyTarget(ExecutionNode target) {
        // Default: no-op for nodes that don't support loop wiring
    }

    /**
     * Adds a loop exit target for loop nodes.
     *
     * <p>Default implementation does nothing. Override in LoopNode.
     *
     * @param target The target node for the loop exit
     */
    default void addLoopExitTarget(ExecutionNode target) {
        // Default: no-op for nodes that don't support loop wiring
    }

    /**
     * Checks if this node is a loop node.
     * Loop nodes evaluate a condition and route to body or exit.
     *
     * <p>Default implementation returns false. Override in LoopNode.
     *
     * @return true if this node is a loop node, false otherwise
     */
    default boolean isLoopNode() {
        return false;
    }

    // ========================================================================
    // SERVICE INJECTION (polymorphic service acceptance)
    // ========================================================================

    /**
     * Accepts services from the registry.
     * Each node implementation pulls only the services it needs.
     *
     * <p>This enables polymorphic service injection without instanceof checks.
     * Used by ExecutionServiceInjector to inject services into all nodes uniformly.
     *
     * <p>Default implementation does nothing. Override in BaseNode and specific
     * node types that need services.
     *
     * @param registry The service registry containing all available services
     */
    default void acceptServices(com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry registry) {
        // Default: no-op for nodes that don't need services
    }
}
