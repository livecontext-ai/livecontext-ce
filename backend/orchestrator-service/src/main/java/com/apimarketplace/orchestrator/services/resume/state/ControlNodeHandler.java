package com.apimarketplace.orchestrator.services.resume.state;

import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import com.apimarketplace.orchestrator.utils.WorkflowUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Handles core node operations (decisions) for state reconstruction.
 * Single Responsibility: Core node handling operations.
 */
public class ControlNodeHandler {

    private static final Logger logger = LoggerFactory.getLogger(ControlNodeHandler.class);

    private final StateReconstructorHelper helper;

    public ControlNodeHandler(StateReconstructorHelper helper) {
        this.helper = helper;
    }

    /**
     * Removes steps from skippedStepIds if they have at least one COMPLETED predecessor.
     *
     * This handles the case where a step (especially a merge node) was marked SKIPPED
     * during an earlier loop iteration by a decision node, but now has another path
     * from a completed predecessor.
     *
     * @param plan The workflow plan
     * @param completedStepIds Set of completed step IDs
     * @param skippedStepIds Set of skipped step IDs (will be modified)
     */
    public void removeSkippedStepsWithCompletedPredecessors(WorkflowPlan plan, Set<String> completedStepIds, Set<String> skippedStepIds) {
        if (plan == null || skippedStepIds.isEmpty()) {
            return;
        }

        // Create a copy to iterate over (since we'll be modifying skippedStepIds)
        Set<String> toCheck = new HashSet<>(skippedStepIds);
        Set<String> toRemove = new HashSet<>();

        for (String skippedId : toCheck) {
            // Find all predecessors of this step
            List<String> predecessors = findPredecessorsFromEdges(plan, skippedId);

            if (predecessors.isEmpty()) {
                continue;
            }

            // Check if any predecessor is completed
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
                    logger.info("[removeSkippedStepsWithCompletedPredecessors] Step {} has completed predecessor {}, removing from skipped set",
                        skippedId, pred);
                    toRemove.add(skippedId);
                    break; // One completed predecessor is enough
                }
            }
        }

        if (!toRemove.isEmpty()) {
            logger.info("[removeSkippedStepsWithCompletedPredecessors] Removing {} steps from skipped set: {}",
                toRemove.size(), toRemove);
            skippedStepIds.removeAll(toRemove);
        }
    }

    /**
     * Find all successor node IDs (nodes that this node has edges pointing TO).
     */
    private Set<String> findSuccessorsFromEdges(WorkflowPlan plan, String nodeId) {
        Set<String> successors = new HashSet<>();

        if (plan.getEdges() == null) {
            return successors;
        }

        for (Edge edge : plan.getEdges()) {
            if (edge.from() == null || edge.to() == null) {
                continue;
            }

            String fromKey = EdgeRefParser.getNodeKey(edge.from());
            if (fromKey == null) {
                fromKey = edge.from();
            }

            if (fromKey.equals(nodeId) || edge.from().equals(nodeId)) {
                String toKey = EdgeRefParser.getNodeKey(edge.to());
                if (toKey == null) {
                    toKey = edge.to();
                }
                successors.add(toKey);
            }
        }

        return successors;
    }

    /**
     * V2: Find all predecessor node IDs (nodes that have edges pointing TO this node).
     * In V2, this is simple: find all edges where to matches the nodeId.
     */
    public List<String> findPredecessorsFromEdges(WorkflowPlan plan, String nodeId) {
        List<String> predecessors = new ArrayList<>();

        if (plan.getEdges() == null) {
            return predecessors;
        }

        for (Edge edge : plan.getEdges()) {
            if (edge.to() == null || edge.from() == null) {
                continue;
            }

            // V2: Parse the to reference
            String toKey = EdgeRefParser.getNodeKey(edge.to());
            if (toKey == null) {
                toKey = edge.to();
            }

            // Check if this edge points to our node
            if (toKey.equals(nodeId) || edge.to().equals(nodeId)) {
                // Get the predecessor (from node, without port)
                String fromKey = EdgeRefParser.getNodeKey(edge.from());
                if (fromKey == null) {
                    fromKey = helper.normalizeNodeId(edge.from());
                }
                if (!predecessors.contains(fromKey)) {
                    predecessors.add(fromKey);
                }
            }
        }

        return predecessors;
    }
}
