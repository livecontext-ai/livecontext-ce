package com.apimarketplace.orchestrator.utils;

import java.util.*;

/**
 * Utilities for workflows.
 * Centralizes common data manipulation logic.
 *
 * Note: For node ID normalization and prefix handling, this class delegates to
 * {@link LabelNormalizer} which is the single source of truth.
 */
public final class WorkflowUtils {

    private WorkflowUtils() {
        // Utility class - private constructor
    }

    // ========================================================================
    // NODE ID METHODS - Delegate to LabelNormalizer
    // ========================================================================

    /**
     * Normalizes a step ID.
     * Delegates to {@link LabelNormalizer} for consistent normalization.
     *
     * @param stepId The step ID to normalize
     * @return The normalized step ID, or null if invalid
     */
    public static String normalizeStepId(String stepId) {
        if (stepId == null || stepId.isBlank()) {
            return null;
        }

        String trimmed = stepId.trim();

        // If already has a valid prefix, extract and re-normalize the label part
        if (LabelNormalizer.isNormalizedKey(trimmed)) {
            String prefix = LabelNormalizer.getNodeType(trimmed);
            String label = LabelNormalizer.extractLabelFromKey(trimmed);
            if (label == null || label.isBlank()) {
                return null;
            }
            return LabelNormalizer.computeNodeId(label, prefix);
        }

        // No prefix - default to mcp:
        return LabelNormalizer.mcpKey(trimmed);
    }

    /**
     * Normalizes a step ID safely (always returns a non-null value).
     * If normalization fails, returns the original ID.
     */
    public static String normalizeStepIdSafe(String stepId) {
        if (stepId == null) {
            return null;
        }
        String normalized = normalizeStepId(stepId);
        return normalized != null ? normalized : stepId;
    }

    // ========================================================================
    // ID GENERATION
    // ========================================================================

    /**
     * Generates a unique run ID.
     */
    public static String generateRunId() {
        return "run_" + System.currentTimeMillis() + "_" +
               UUID.randomUUID().toString().substring(0, 8);
    }

}
