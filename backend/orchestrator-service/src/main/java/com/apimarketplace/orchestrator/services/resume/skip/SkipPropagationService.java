package com.apimarketplace.orchestrator.services.resume.skip;

import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.streaming.EdgeStatusService;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Facade service for skip propagation logic in workflow execution.
 *
 * <h2>Purpose</h2>
 * When a decision node selects one branch, all other branches (and their downstream nodes)
 * must be marked as SKIPPED. This service handles the recursive propagation of skip status.
 *
 * <h2>Delegates to</h2>
 * <ul>
 *   <li>{@link SkipGraphAnalyzer} - Graph traversal and node classification</li>
 *   <li>{@link ReachabilityAnalyzer} - Node reachability analysis</li>
 *   <li>{@link VirtualNodeSkipHandler} - Virtual node skip handling</li>
 *   <li>{@link SkippedStepCleanup} - Cleanup of incorrectly skipped steps</li>
 *   <li>{@link ParentChildChecker} - Parent/child relationship checks</li>
 * </ul>
 *
 * @see SkipPropagationContext
 */
@Service
public class SkipPropagationService {

    private static final Logger logger = LoggerFactory.getLogger(SkipPropagationService.class);

    private final StepCompletionOrchestrator stepCompletionOrchestrator;
    private final EdgeStatusService edgeStatusService;
    private final SkipGraphAnalyzer graphAnalyzer;
    private final ReachabilityAnalyzer reachabilityAnalyzer;
    private final VirtualNodeSkipHandler virtualNodeSkipHandler;
    private final SkippedStepCleanup skippedStepCleanup;
    private final ParentChildChecker parentChildChecker;

    public SkipPropagationService(
            StepCompletionOrchestrator stepCompletionOrchestrator,
            EdgeStatusService edgeStatusService,
            SkipGraphAnalyzer graphAnalyzer,
            ReachabilityAnalyzer reachabilityAnalyzer,
            VirtualNodeSkipHandler virtualNodeSkipHandler,
            SkippedStepCleanup skippedStepCleanup,
            ParentChildChecker parentChildChecker) {
        this.stepCompletionOrchestrator = stepCompletionOrchestrator;
        this.edgeStatusService = edgeStatusService;
        this.graphAnalyzer = graphAnalyzer;
        this.reachabilityAnalyzer = reachabilityAnalyzer;
        this.virtualNodeSkipHandler = virtualNodeSkipHandler;
        this.skippedStepCleanup = skippedStepCleanup;
        this.parentChildChecker = parentChildChecker;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API - Skip Propagation
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Propagates the SKIPPED status to all successor nodes in the workflow.
     *
     * @param context The skip propagation context containing all necessary information
     */
    public void propagateSkipToSuccessors(SkipPropagationContext context) {
        propagateSkipToSuccessors(
            context.execution(),
            context.plan(),
            context.skippedNodeId(),
            context.skipSourceId(),
            context.visited()
        );
    }

    /**
     * Propagates the SKIPPED status to all successor nodes in the workflow.
     *
     * @param execution The workflow execution context
     * @param plan The workflow plan
     * @param skippedNodeId The node being skipped
     * @param skipSourceId The original source of the skip
     * @param visited Set of already visited nodes to prevent infinite loops
     */
    public void propagateSkipToSuccessors(
            WorkflowExecution execution,
            WorkflowPlan plan,
            String skippedNodeId,
            String skipSourceId,
            Set<String> visited) {

        // Avoid revisiting nodes
        if (!visited.add(skippedNodeId)) {
            return;
        }

        logger.info("[SkipPropagation] Propagating skip from {} (source: {})", skippedNodeId, skipSourceId);

        List<String> successorIds = graphAnalyzer.findSuccessorsFromEdges(plan, skippedNodeId);

        if (successorIds.isEmpty()) {
            logger.debug("[SkipPropagation] No successors found for: {}", skippedNodeId);
            return;
        }

        logger.info("[SkipPropagation] Found {} successors for {}: {}", successorIds.size(), skippedNodeId, successorIds);

        for (String successorId : successorIds) {
            if (visited.contains(successorId)) {
                continue;
            }

            // Check if this successor is a merge node
            if (graphAnalyzer.isMergeNode(plan, successorId)) {
                edgeStatusService.markEdgeSkipped(execution, skippedNodeId, successorId);
                logger.info("[SkipPropagation] Edge to merge node SKIPPED (merge node stays actionable): {} -> {}",
                    skippedNodeId, successorId);
                visited.add(successorId);
                continue;
            }

            // Check if this successor is a direct destination of another decision
            if (graphAnalyzer.isDirectDecisionDestination(plan, successorId, skipSourceId)) {
                logger.info("[SkipPropagation] Successor {} is a direct destination of another decision, not skipping", successorId);
                edgeStatusService.markEdgeSkipped(execution, skippedNodeId, successorId);
                visited.add(successorId);
                continue;
            }

            // Mark the successor as skipped
            String skipReason = "Predecessor " + skippedNodeId + " was skipped (originally skipped by " + skipSourceId + ")";
            StepExecutionResult skipResult = StepExecutionResult.skipped(successorId, skipReason);
            execution.setStepResult(successorId, skipResult);

            // Use StepCompletionOrchestrator for skipped successor
            String successorLabel = LabelNormalizer.extractLabel(successorId);
            stepCompletionOrchestrator.completeSkippedStep(
                execution, successorId, successorLabel, skipReason, skipSourceId, 0);

            edgeStatusService.markEdgeSkipped(execution, skippedNodeId, successorId);
            logger.info("[SkipPropagation] Skipped edge: {} -> {}", skippedNodeId, successorId);

            // Recursively propagate to successors
            propagateSkipToSuccessors(execution, plan, successorId, skipSourceId, visited);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DELEGATED METHODS - Core Node Skip Handling
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Add core nodes to skippedStepIds if their predecessors are all skipped.
     */
    public void addSkippedCores(WorkflowPlan plan, Set<String> completedStepIds, Set<String> skippedStepIds) {
        virtualNodeSkipHandler.addSkippedCores(plan, completedStepIds, skippedStepIds);
    }

    /**
     * Removes steps from skippedStepIds if they have at least one COMPLETED predecessor.
     */
    public void removeSkippedStepsWithCompletedPredecessors(
            WorkflowPlan plan,
            Set<String> completedStepIds,
            Set<String> skippedStepIds) {
        skippedStepCleanup.removeSkippedStepsWithCompletedPredecessors(plan, completedStepIds, skippedStepIds);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DELEGATED METHODS - Reachability Analysis
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Check if a dependency is unreachable because all of its predecessors are skipped.
     */
    public boolean isDependencyUnreachable(
            WorkflowPlan plan,
            ExecutionGraph graph,
            String nodeId,
            Set<String> skippedStepIds,
            Set<String> completedStepIds) {
        return reachabilityAnalyzer.isDependencyUnreachable(plan, graph, nodeId, skippedStepIds, completedStepIds);
    }

    /**
     * Shallow check for unreachability.
     */
    public boolean isDependencyUnreachableShallow(
            WorkflowPlan plan,
            ExecutionGraph graph,
            String nodeId,
            Set<String> skippedStepIds,
            Set<String> completedStepIds) {
        return reachabilityAnalyzer.isDependencyUnreachableShallow(plan, graph, nodeId, skippedStepIds, completedStepIds);
    }

    /**
     * Recursively check if all predecessors of a node are unreachable.
     */
    public boolean areAllPredecessorsUnreachable(
            WorkflowPlan plan,
            ExecutionGraph graph,
            String nodeId,
            List<String> predecessors,
            Set<String> skippedStepIds,
            Set<String> completedStepIds,
            Set<String> visitedNodes) {
        return reachabilityAnalyzer.areAllPredecessorsUnreachable(
            plan, graph, nodeId, predecessors, skippedStepIds, completedStepIds, visitedNodes);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DELEGATED METHODS - Parent/Child Checks
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Check if a dependency has a parent node that is completed.
     */
    public boolean isDependencyParentCompleted(String dependencyId, Set<String> completedStepIds) {
        return parentChildChecker.isDependencyParentCompleted(dependencyId, completedStepIds);
    }

    /**
     * Check if a dependency has a parent node that is skipped.
     */
    public boolean isDependencyParentSkipped(String dependencyId, Set<String> skippedStepIds) {
        return parentChildChecker.isDependencyParentSkipped(dependencyId, skippedStepIds);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITY METHODS - Delegated to SkipGraphAnalyzer
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Normalize a node ID to ensure consistent format.
     */
    public String normalizeNodeId(String nodeId) {
        return graphAnalyzer.normalizeNodeId(nodeId);
    }

    /**
     * Extract label from a prefixed node ID.
     */
    public String extractLabel(String nodeId) {
        return graphAnalyzer.extractLabel(nodeId);
    }
}
