package com.apimarketplace.orchestrator.services.resume.skip;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Checks parent/child relationships for workflow nodes.
 * Handles special sub-node IDs with "::" separators.
 *
 * Extracted from SkipPropagationService for Single Responsibility Principle.
 *
 * @see SkipPropagationService
 */
@Component
public class ParentChildChecker {

    private static final Logger logger = LoggerFactory.getLogger(ParentChildChecker.class);

    /**
     * Check if a dependency has a parent node that is completed.
     *
     * <p>This handles special sub-node IDs with "::" separators where
     * if the parent is completed, we should treat the sub-node as completed too.
     *
     * @param dependencyId The dependency ID to check
     * @param completedStepIds Set of completed step IDs
     * @return true if the parent node is completed
     */
    public boolean isDependencyParentCompleted(String dependencyId, Set<String> completedStepIds) {
        if (dependencyId == null) {
            return false;
        }

        int doubleColonIndex = dependencyId.indexOf("::");
        if (doubleColonIndex > 0) {
            String parentId = dependencyId.substring(0, doubleColonIndex);
            if (completedStepIds.contains(parentId)) {
                logger.debug("isDependencyParentCompleted: {} has completed parent {}", dependencyId, parentId);
                return true;
            }
        }

        return false;
    }

    /**
     * Check if a dependency has a parent node that is skipped.
     *
     * <p>This handles special sub-node IDs with "::" separators where
     * if the parent is skipped, we should treat the sub-node as skipped too.
     *
     * @param dependencyId The dependency ID to check
     * @param skippedStepIds Set of skipped step IDs
     * @return true if the parent node is skipped
     */
    public boolean isDependencyParentSkipped(String dependencyId, Set<String> skippedStepIds) {
        if (dependencyId == null) {
            return false;
        }

        int doubleColonIndex = dependencyId.indexOf("::");
        if (doubleColonIndex > 0) {
            String parentId = dependencyId.substring(0, doubleColonIndex);
            if (skippedStepIds.contains(parentId)) {
                logger.debug("isDependencyParentSkipped: {} has skipped parent {}", dependencyId, parentId);
                return true;
            }
        }

        return false;
    }

}
