package com.apimarketplace.orchestrator.services.resume.skip;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Handles skipping of virtual nodes in skip propagation.
 *
 * Extracted from SkipPropagationService for Single Responsibility Principle.
 *
 * @see SkipPropagationService
 */
@Component
public class VirtualNodeSkipHandler {

    private static final Logger logger = LoggerFactory.getLogger(VirtualNodeSkipHandler.class);

    public VirtualNodeSkipHandler(SkipGraphAnalyzer graphAnalyzer) {
        // kept for Spring wiring compatibility
    }

    /**
     * Add core nodes to skippedStepIds if their predecessors are all skipped.
     *
     * @param plan The workflow plan
     * @param completedStepIds Set of completed step IDs
     * @param skippedStepIds Set of skipped step IDs (will be modified)
     */
    public void addSkippedCores(WorkflowPlan plan, Set<String> completedStepIds, Set<String> skippedStepIds) {
        // No-op: legacy loop virtual node handling removed
    }

    /**
     * Check if a node ID represents a virtual node.
     *
     * @param nodeId The node ID to check
     * @return true if this is a virtual node (contains "::")
     */
    public boolean isVirtualNode(String nodeId) {
        return nodeId != null && nodeId.contains("::");
    }

    /**
     * Extract the parent node ID from a virtual node.
     *
     * @param virtualNodeId The virtual node ID (e.g., "core:while::condition_checker")
     * @return The parent node ID (e.g., "core:while"), or null if not a virtual node
     */
    public String extractParentNodeId(String virtualNodeId) {
        if (virtualNodeId == null) {
            return null;
        }
        int doubleColonIndex = virtualNodeId.indexOf("::");
        if (doubleColonIndex > 0) {
            return virtualNodeId.substring(0, doubleColonIndex);
        }
        return null;
    }
}
