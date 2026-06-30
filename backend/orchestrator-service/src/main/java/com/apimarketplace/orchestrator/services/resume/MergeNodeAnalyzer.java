package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import com.apimarketplace.orchestrator.utils.WorkflowUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for analyzing merge nodes and their dependencies in workflow execution.
 *
 * <p>A merge node is a node that has multiple incoming edges from different branches.
 * This service determines:
 * <ul>
 *   <li>Whether a node is a merge node</li>
 *   <li>Which predecessors feed into a merge node</li>
 *   <li>Whether dependencies are reachable or unreachable</li>
 *   <li>Skip propagation through core nodes</li>
 * </ul>
 *
 * <p>Part of the WorkflowResumeService refactoring - extracted for Single Responsibility.
 */
@Service
public class MergeNodeAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(MergeNodeAnalyzer.class);

    /**
     * V2: Checks if a step is a merge node (has multiple incoming edges from different sources).
     *
     * @param plan The workflow plan
     * @param stepId The step ID to check
     * @return true if the step is a merge node
     */
    public boolean isMergeNode(WorkflowPlan plan, String stepId) {
        if (plan == null || plan.getEdges() == null) {
            return false;
        }

        // V2: Count how many edges point to this step from different sources
        // In V2, all edges are simple from/to, ports indicate which branch
        // IMPORTANT: Skip iterate edges (loop back-edges) - these are not forward dependencies
        Set<String> sources = new HashSet<>();
        for (Edge edge : plan.getEdges()) {
            if (edge.to() == null) {
                continue;
            }

            // Skip iterate edges - these are loop back-edges, not forward dependencies
            String toPort = com.apimarketplace.orchestrator.utils.EdgeRefParser.getPort(edge.to());
            if ("iterate".equals(toPort)) {
                continue;
            }

            // Get the target node key (without port)
            String toKey = com.apimarketplace.orchestrator.utils.EdgeRefParser.getNodeKey(edge.to());
            String stepKey = com.apimarketplace.orchestrator.utils.EdgeRefParser.getNodeKey(stepId);

            if (toKey != null && toKey.equals(stepKey)) {
                // Get the source node key (without port)
                String fromKey = com.apimarketplace.orchestrator.utils.EdgeRefParser.getNodeKey(edge.from());
                if (fromKey != null) {
                    sources.add(fromKey);
                }
            }
        }

        boolean isMerge = sources.size() > 1;
        if (isMerge) {
            logger.debug("[isMergeNode] Step {} is a MERGE node with {} sources: {}",
                stepId, sources.size(), sources);
        }
        return isMerge;
    }

    /**
     * V2: Finds all predecessors of a step from the edges.
     *
     * @param plan The workflow plan
     * @param stepId The step ID
     * @return List of predecessor step IDs
     */
    public List<String> findPredecessorsFromEdges(WorkflowPlan plan, String stepId) {
        List<String> predecessors = new ArrayList<>();
        if (plan == null || plan.getEdges() == null) {
            return predecessors;
        }

        String stepKey = com.apimarketplace.orchestrator.utils.EdgeRefParser.getNodeKey(stepId);
        if (stepKey == null) {
            stepKey = stepId;
        }

        for (Edge edge : plan.getEdges()) {
            if (edge.to() == null) {
                continue;
            }

            // Skip iterate edges - these are loop back-edges, not forward dependencies
            String toPort = com.apimarketplace.orchestrator.utils.EdgeRefParser.getPort(edge.to());
            if ("iterate".equals(toPort)) {
                continue;
            }

            // V2: Get the target node key (without port)
            String toKey = com.apimarketplace.orchestrator.utils.EdgeRefParser.getNodeKey(edge.to());
            if (toKey == null || !toKey.equals(stepKey)) {
                continue;
            }

            // V2: Get the source node key (without port)
            String fromKey = com.apimarketplace.orchestrator.utils.EdgeRefParser.getNodeKey(edge.from());
            if (fromKey != null) {
                predecessors.add(fromKey);
            }
        }

        logger.debug("[findPredecessorsFromEdges] Step {} has predecessors: {}", stepId, predecessors);
        return predecessors;
    }

    /**
     * Checks if a dependency is unreachable (all its predecessors are skipped).
     *
     * @param plan The workflow plan
     * @param graph The execution graph
     * @param dependencyId The dependency to check
     * @param skippedStepIds Set of skipped step IDs
     * @param completedStepIds Set of completed step IDs
     * @return true if the dependency is unreachable
     */
    public boolean isDependencyUnreachable(
            WorkflowPlan plan,
            ExecutionGraph graph,
            String dependencyId,
            Set<String> skippedStepIds,
            Set<String> completedStepIds) {

        // Get all predecessors of this dependency
        Set<String> predecessors = graph.getDependencies(dependencyId);
        if (predecessors == null || predecessors.isEmpty()) {
            // No predecessors means it's a root node - not unreachable
            return false;
        }

        // Check if ALL predecessors are skipped
        boolean allSkipped = predecessors.stream()
            .allMatch(pred -> {
                String normalizedPred = WorkflowUtils.normalizeStepIdSafe(pred);
                return skippedStepIds.contains(normalizedPred) ||
                       skippedStepIds.contains(pred);
            });

        if (allSkipped) {
            logger.debug("[isDependencyUnreachable] Dependency {} is UNREACHABLE (all predecessors skipped): {}",
                dependencyId, predecessors);
            return true;
        }

        return false;
    }

    /**
     * Checks if a dependency's parent is completed (for sub-node IDs like loop::condition_checker).
     *
     * @param dependencyId The dependency to check
     * @param completedStepIds Set of completed step IDs
     * @return true if the parent is completed
     */
    public boolean isDependencyParentCompleted(String dependencyId, Set<String> completedStepIds) {
        if (dependencyId == null) {
            return false;
        }

        // Check for sub-node pattern: "core:xxx::condition_checker" -> parent is "core:xxx"
        int doubleColonIndex = dependencyId.indexOf("::");
        if (doubleColonIndex > 0) {
            String parentId = dependencyId.substring(0, doubleColonIndex);
            if (completedStepIds.contains(parentId)) {
                logger.debug("[isDependencyParentCompleted] Dependency {} parent {} is completed",
                    dependencyId, parentId);
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if a dependency's parent is skipped (for sub-node IDs like loop::condition_checker).
     *
     * @param dependencyId The dependency to check
     * @param skippedStepIds Set of skipped step IDs
     * @return true if the parent is skipped
     */
    public boolean isDependencyParentSkipped(String dependencyId, Set<String> skippedStepIds) {
        if (dependencyId == null) {
            return false;
        }

        // Check for sub-node pattern: "core:xxx::condition_checker" -> parent is "core:xxx"
        int doubleColonIndex = dependencyId.indexOf("::");
        if (doubleColonIndex > 0) {
            String parentId = dependencyId.substring(0, doubleColonIndex);
            if (skippedStepIds.contains(parentId)) {
                logger.debug("[isDependencyParentSkipped] Dependency {} parent {} is skipped",
                    dependencyId, parentId);
                return true;
            }
        }

        return false;
    }

    /**
     * V2: Checks if a loop's entry step was skipped (meaning the loop is unreachable).
     *
     * @param plan The workflow plan
     * @param loopId The loop ID
     * @param skippedStepIds Set of skipped step IDs
     * @return true if the loop's entry was skipped
     */
    public boolean isLoopEntrySkipped(WorkflowPlan plan, String loopId, Set<String> skippedStepIds) {
        if (plan == null || plan.getEdges() == null) {
            return false;
        }

        // V2: Find forward edges that point to this loop (skip iterate back-edges)
        for (Edge edge : plan.getEdges()) {
            if (edge.to() == null) {
                continue;
            }

            // Skip iterate edges - these are loop back-edges, not entry edges
            String toPort = com.apimarketplace.orchestrator.utils.EdgeRefParser.getPort(edge.to());
            if ("iterate".equals(toPort)) {
                continue;
            }

            String toKey = com.apimarketplace.orchestrator.utils.EdgeRefParser.getNodeKey(edge.to());
            if (toKey == null || !toKey.equals(loopId)) {
                continue;
            }

            // Check if the source of this edge is skipped
            String fromKey = com.apimarketplace.orchestrator.utils.EdgeRefParser.getNodeKey(edge.from());
            if (fromKey != null) {
                String normalizedFrom = WorkflowUtils.normalizeStepIdSafe(fromKey);
                if (skippedStepIds.contains(normalizedFrom) || skippedStepIds.contains(fromKey)) {
                    logger.debug("[isLoopEntrySkipped] Loop {} entry step {} is skipped", loopId, fromKey);
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * V2: Adds skipped core nodes based on their predecessors being skipped.
     *
     * @param plan The workflow plan
     * @param completedStepIds Set of completed step IDs
     * @param skippedStepIds Set of skipped step IDs (modified)
     */
    public void addSkippedCores(
            WorkflowPlan plan,
            Set<String> completedStepIds,
            Set<String> skippedStepIds) {

        if (plan == null || plan.getCores() == null) {
            return;
        }

        for (Core core : plan.getCores()) {
            String coreKey = computeCoreKey(core);
            if (coreKey == null) continue;

            // Skip if already completed or skipped
            if (completedStepIds.contains(coreKey) || skippedStepIds.contains(coreKey)) {
                continue;
            }

            // V2: Find edges that point to this core node
            // Skip iterate edges (loop back-edges) - they are not forward dependencies
            boolean predecessorSkipped = false;
            for (Edge edge : plan.getEdges()) {
                if (edge.to() == null) {
                    continue;
                }

                // Skip iterate edges - these are loop back-edges, not forward dependencies
                String toPort = com.apimarketplace.orchestrator.utils.EdgeRefParser.getPort(edge.to());
                if ("iterate".equals(toPort)) {
                    continue;
                }

                String toKey = com.apimarketplace.orchestrator.utils.EdgeRefParser.getNodeKey(edge.to());
                if (toKey == null || !toKey.equals(coreKey)) {
                    continue;
                }

                // Check if the source is skipped
                String fromKey = com.apimarketplace.orchestrator.utils.EdgeRefParser.getNodeKey(edge.from());
                if (fromKey != null) {
                    String normalizedFrom = WorkflowUtils.normalizeStepIdSafe(fromKey);
                    if (skippedStepIds.contains(normalizedFrom) || skippedStepIds.contains(fromKey)) {
                        predecessorSkipped = true;
                        break;
                    }
                }
            }

            if (predecessorSkipped) {
                logger.info("[addSkippedCores] Core node {} marked as skipped (predecessor skipped)", coreKey);
                skippedStepIds.add(coreKey);
            }
        }
    }

    /**
     * Adds completed loop condition_checkers for loops that have truly terminated.
     * The condition_checker virtual node represents the EXIT point of a loop.
     *
     * @param plan The workflow plan
     * @param stepEntities The step data entities
     * @param completedStepIds Set of completed step IDs (modified)
     */
    public void addCompletedLoopConditionCheckers(
            WorkflowPlan plan,
            List<com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity> stepEntities,
            Set<String> completedStepIds) {

        if (plan == null || plan.getCores() == null) {
            return;
        }

        // Build a map of step alias -> entities for quick lookup
        Map<String, List<com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity>> stepsByAlias =
            new HashMap<>();
        for (var entity : stepEntities) {
            stepsByAlias.computeIfAbsent(entity.getStepAlias(), k -> new ArrayList<>()).add(entity);
        }

        for (Core core : plan.getCores()) {
            if (!"loop".equals(core.type())) {
                continue;
            }

            String loopLabel = core.label() != null ? core.label() : core.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(loopLabel);
            String loopKey = "core:" + normalizedLabel;

            // Check if loop is completed
            if (!completedStepIds.contains(loopKey)) {
                continue;
            }

            // Check if loop has terminated (has exit reason or condition_result=false)
            List<com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity> loopEntities =
                stepsByAlias.get(normalizedLabel);
            if (loopEntities == null) {
                loopEntities = stepsByAlias.get(loopLabel);
            }

            if (loopEntities != null && !loopEntities.isEmpty()) {
                var latestEntity = loopEntities.get(loopEntities.size() - 1);
                boolean hasExitReason = latestEntity.getLoopExitReason() != null
                    && !latestEntity.getLoopExitReason().isEmpty();
                boolean conditionFalse = Boolean.FALSE.equals(latestEntity.getConditionResult());

                if (hasExitReason || conditionFalse) {
                    // Loop has terminated - mark condition_checker as completed
                    String conditionCheckerId = loopKey + "::condition_checker";
                    if (!completedStepIds.contains(conditionCheckerId)) {
                        logger.info("[addCompletedLoopConditionCheckers] Adding {} (loop terminated)",
                            conditionCheckerId);
                        completedStepIds.add(conditionCheckerId);
                    }
                }
            }
        }
    }

    /**
     * Computes the normalized key for a Core based on its type and label.
     */
    private String computeCoreKey(Core core) {
        if (core == null) return null;
        String label = core.label() != null ? core.label() : core.id();
        String normalizedLabel = LabelNormalizer.normalizeLabel(label);
        if (normalizedLabel == null) return null;

        if ("loop".equals(core.type())) {
            return "core:" + normalizedLabel;
        } else {
            return "core:" + normalizedLabel;
        }
    }

    /**
     * Normalizes a core node ID to a standard format.
     */
    private String normalizeCoreId(String coreId) {
        if (coreId == null) return null;
        String normalized = LabelNormalizer.extractCoreLabel(coreId);
        return "core:" + (normalized != null ? normalized : coreId);
    }
}
