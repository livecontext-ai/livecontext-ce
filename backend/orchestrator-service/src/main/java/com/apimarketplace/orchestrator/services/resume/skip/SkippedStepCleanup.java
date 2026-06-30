package com.apimarketplace.orchestrator.services.resume.skip;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.utils.WorkflowUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cleans up incorrectly skipped steps that should be ready due to completed predecessors.
 *
 * This handles the case where a step (especially a merge node) was marked SKIPPED
 * during an earlier loop iteration by a decision node, but now has another path
 * from a completed predecessor.
 *
 * Extracted from SkipPropagationService for Single Responsibility Principle.
 *
 * @see SkipPropagationService
 */
@Component
public class SkippedStepCleanup {

    private static final Logger logger = LoggerFactory.getLogger(SkippedStepCleanup.class);

    private final SkipGraphAnalyzer graphAnalyzer;

    public SkippedStepCleanup(SkipGraphAnalyzer graphAnalyzer) {
        this.graphAnalyzer = graphAnalyzer;
    }

    /**
     * Removes steps from skippedStepIds if they have at least one COMPLETED predecessor.
     *
     * <p>For example:
     * <ul>
     *   <li>Step X is a merge node with predecessors: decision:if_else_copy (skipped) AND agent:ai_agent</li>
     *   <li>In loop iteration 1, decision:if_else_copy skips Step X</li>
     *   <li>In loop iteration 2, agent:ai_agent completes</li>
     *   <li>Step X should now be READY (not skipped) because it has a completed predecessor</li>
     * </ul>
     *
     * @param plan The workflow plan
     * @param completedStepIds Set of completed step IDs
     * @param skippedStepIds Set of skipped step IDs (will be modified)
     */
    public void removeSkippedStepsWithCompletedPredecessors(
            WorkflowPlan plan,
            Set<String> completedStepIds,
            Set<String> skippedStepIds) {

        if (plan == null || skippedStepIds.isEmpty()) {
            return;
        }

        // Create a copy to iterate over (since we'll be modifying skippedStepIds)
        Set<String> toCheck = new HashSet<>(skippedStepIds);
        Set<String> toRemove = new HashSet<>();

        for (String skippedId : toCheck) {
            // Find all predecessors of this step
            List<String> predecessors = graphAnalyzer.findPredecessorsFromEdges(plan, skippedId);

            if (predecessors.isEmpty()) {
                continue;
            }

            // Check if any predecessor is completed
            if (hasCompletedPredecessor(predecessors, completedStepIds)) {
                logger.info("[removeSkippedStepsWithCompletedPredecessors] Step {} has completed predecessor, removing from skipped set",
                    skippedId);
                toRemove.add(skippedId);
            }
        }

        if (!toRemove.isEmpty()) {
            logger.info("[removeSkippedStepsWithCompletedPredecessors] Removing {} steps from skipped set: {}",
                toRemove.size(), toRemove);
            skippedStepIds.removeAll(toRemove);
        }
    }

    /**
     * Check if any predecessor in the list is completed.
     */
    private boolean hasCompletedPredecessor(List<String> predecessors, Set<String> completedStepIds) {
        for (String pred : predecessors) {
            String normalizedPred = WorkflowUtils.normalizeStepIdSafe(pred);

            boolean predCompleted = completedStepIds.contains(pred) ||
                                    completedStepIds.contains(normalizedPred);

            // Also check if predecessor is a sub-node and its parent is completed
            if (!predCompleted && pred.contains("::")) {
                String parentId = pred.substring(0, pred.indexOf("::"));
                predCompleted = completedStepIds.contains(parentId);
            }

            if (predCompleted) {
                return true;
            }
        }
        return false;
    }
}
