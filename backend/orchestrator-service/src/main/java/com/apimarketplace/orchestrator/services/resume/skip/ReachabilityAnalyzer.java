package com.apimarketplace.orchestrator.services.resume.skip;

import com.apimarketplace.orchestrator.domain.workflow.ExecutionGraph;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.utils.WorkflowUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Analyzes node reachability in the workflow graph.
 *
 * Determines if a node is unreachable because all of its predecessors are skipped or unreachable.
 * This is used for merge node logic: a merge node is ready if at least one predecessor is completed
 * AND all other predecessors are either completed, skipped, or unreachable.
 *
 * Extracted from SkipPropagationService for Single Responsibility Principle.
 *
 * @see SkipPropagationService
 */
@Component
public class ReachabilityAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(ReachabilityAnalyzer.class);

    private final SkipGraphAnalyzer graphAnalyzer;
    private final ParentChildChecker parentChildChecker;

    public ReachabilityAnalyzer(SkipGraphAnalyzer graphAnalyzer, ParentChildChecker parentChildChecker) {
        this.graphAnalyzer = graphAnalyzer;
        this.parentChildChecker = parentChildChecker;
    }

    /**
     * Check if a dependency is unreachable because all of its predecessors are skipped or unreachable.
     *
     * <p>A node is considered unreachable if:
     * <ol>
     *   <li>It's explicitly skipped (in skippedStepIds)</li>
     *   <li>It's a merge node where ALL incoming edges come from skipped/unreachable nodes</li>
     *   <li>It has dependencies and ALL of them are skipped/unreachable</li>
     * </ol>
     *
     * @param plan The workflow plan
     * @param graph The execution graph (for dependencies)
     * @param nodeId The node to check
     * @param skippedStepIds Set of explicitly skipped step IDs
     * @param completedStepIds Set of completed step IDs
     * @return true if the node is unreachable
     */
    public boolean isDependencyUnreachable(
            WorkflowPlan plan,
            ExecutionGraph graph,
            String nodeId,
            Set<String> skippedStepIds,
            Set<String> completedStepIds) {

        // If already skipped, it's unreachable
        if (skippedStepIds.contains(nodeId)) {
            return true;
        }

        // If parent is skipped (for sub-nodes like loop:while::condition_checker)
        if (parentChildChecker.isDependencyParentSkipped(nodeId, skippedStepIds)) {
            return true;
        }

        // If completed, it's reachable (not unreachable)
        if (completedStepIds.contains(nodeId)) {
            return false;
        }

        // Check merge node special logic
        Set<String> dependencies = graph.getDependencies(nodeId);
        if (dependencies != null && !dependencies.isEmpty()) {
            logger.debug("isDependencyUnreachable: nodeId={}, graph dependencies={}", nodeId, dependencies);

            boolean isMergeNode = dependencies.size() > 1 || graphAnalyzer.isMergeNode(plan, nodeId);

            if (isMergeNode && isMergeNodeResolved(dependencies, skippedStepIds, completedStepIds, plan, graph, nodeId)) {
                // This merge node is ready/resolved - return true because it doesn't block downstream
                logger.debug("isDependencyUnreachable: {} is a resolved merge node", nodeId);
                return true;
            }
        }

        // Find all predecessors (nodes that have edges pointing TO this node)
        List<String> predecessors = graphAnalyzer.findPredecessorsFromEdges(plan, nodeId);

        logger.debug("isDependencyUnreachable: nodeId={}, predecessors={}", nodeId, predecessors);

        // If no predecessors and not completed/skipped, it's an entry point - still reachable
        if (predecessors.isEmpty()) {
            return false;
        }

        // Check if ALL predecessors are skipped or unreachable
        Set<String> visitedNodes = new HashSet<>();
        return areAllPredecessorsUnreachable(plan, graph, nodeId, predecessors, skippedStepIds, completedStepIds, visitedNodes);
    }

    /**
     * Check if a merge node is resolved (has one completed dependency and all others resolved).
     */
    private boolean isMergeNodeResolved(
            Set<String> dependencies,
            Set<String> skippedStepIds,
            Set<String> completedStepIds,
            WorkflowPlan plan,
            ExecutionGraph graph,
            String nodeId) {

        boolean anyCompleted = dependencies.stream()
            .anyMatch(dep -> {
                String normalizedDep = WorkflowUtils.normalizeStepIdSafe(dep);
                return completedStepIds.contains(normalizedDep) || completedStepIds.contains(dep);
            });

        boolean allOthersResolved = dependencies.stream()
            .allMatch(dep -> {
                String normalizedDep = WorkflowUtils.normalizeStepIdSafe(dep);
                if (completedStepIds.contains(normalizedDep) || completedStepIds.contains(dep)) {
                    return true;
                }
                if (skippedStepIds.contains(normalizedDep) || skippedStepIds.contains(dep)) {
                    return true;
                }
                if (parentChildChecker.isDependencyParentSkipped(dep, skippedStepIds)) {
                    return true;
                }
                // For nested check, avoid infinite recursion
                if (!normalizedDep.equals(nodeId)) {
                    return isDependencyUnreachableShallow(plan, graph, normalizedDep, skippedStepIds, completedStepIds);
                }
                return false;
            });

        return anyCompleted && allOthersResolved;
    }

    /**
     * Shallow check for unreachability - doesn't recurse deeply to avoid infinite loops.
     * Used for nested merge node checks.
     *
     * @param plan The workflow plan
     * @param graph The execution graph
     * @param nodeId The node to check
     * @param skippedStepIds Set of skipped step IDs
     * @param completedStepIds Set of completed step IDs
     * @return true if the node appears unreachable at shallow depth
     */
    public boolean isDependencyUnreachableShallow(
            WorkflowPlan plan,
            ExecutionGraph graph,
            String nodeId,
            Set<String> skippedStepIds,
            Set<String> completedStepIds) {

        if (skippedStepIds.contains(nodeId)) {
            return true;
        }
        if (parentChildChecker.isDependencyParentSkipped(nodeId, skippedStepIds)) {
            return true;
        }
        if (completedStepIds.contains(nodeId)) {
            return false;
        }

        // For shallow check, just look at direct predecessors from edges
        List<String> predecessors = graphAnalyzer.findPredecessorsFromEdges(plan, nodeId);
        if (predecessors.isEmpty()) {
            return false; // Entry point - reachable
        }

        // Check if all predecessors are skipped
        return predecessors.stream().allMatch(pred -> {
            String normalizedPred = WorkflowUtils.normalizeStepIdSafe(pred);
            return skippedStepIds.contains(normalizedPred) ||
                   skippedStepIds.contains(pred) ||
                   parentChildChecker.isDependencyParentSkipped(pred, skippedStepIds);
        });
    }

    /**
     * Recursively check if all predecessors of a node are unreachable.
     *
     * @param plan The workflow plan
     * @param graph The execution graph
     * @param nodeId The node being checked
     * @param predecessors The predecessor nodes to check
     * @param skippedStepIds Set of skipped step IDs
     * @param completedStepIds Set of completed step IDs
     * @param visitedNodes Set of already visited nodes (prevents infinite recursion)
     * @return true if all predecessors are unreachable
     */
    public boolean areAllPredecessorsUnreachable(
            WorkflowPlan plan,
            ExecutionGraph graph,
            String nodeId,
            List<String> predecessors,
            Set<String> skippedStepIds,
            Set<String> completedStepIds,
            Set<String> visitedNodes) {

        // Prevent infinite recursion
        if (!visitedNodes.add(nodeId)) {
            return true; // Treat cycles as unreachable to break the loop
        }

        for (String pred : predecessors) {
            String normalizedPred = WorkflowUtils.normalizeStepIdSafe(pred);

            // If predecessor is completed, this node is reachable
            if (completedStepIds.contains(normalizedPred) || completedStepIds.contains(pred)) {
                logger.debug("isDependencyUnreachable: {} has completed predecessor {}", nodeId, pred);
                return false;
            }

            // If predecessor is NOT skipped and NOT unreachable itself, this node is reachable
            boolean predSkipped = skippedStepIds.contains(normalizedPred) || skippedStepIds.contains(pred);
            if (!predSkipped) {
                // Check if predecessor itself is unreachable (recursive check)
                List<String> predPredecessors = graphAnalyzer.findPredecessorsFromEdges(plan, normalizedPred);
                if (predPredecessors.isEmpty()) {
                    // Predecessor has no predecessors and is not skipped/completed - it's an entry point, reachable
                    logger.debug("isDependencyUnreachable: {} has reachable entry-point predecessor {}", nodeId, pred);
                    return false;
                }

                // Recursively check if predecessor is unreachable
                if (!areAllPredecessorsUnreachable(plan, graph, normalizedPred, predPredecessors, skippedStepIds, completedStepIds, visitedNodes)) {
                    logger.debug("isDependencyUnreachable: {} has reachable predecessor {} (transitively)", nodeId, pred);
                    return false;
                }
            }
        }

        // All predecessors are either skipped or transitively unreachable
        logger.debug("isDependencyUnreachable: {} is UNREACHABLE (all predecessors skipped/unreachable)", nodeId);
        return true;
    }
}
