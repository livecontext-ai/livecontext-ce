package com.apimarketplace.orchestrator.execution.v2.constants;

/**
 * Factory for cache key construction.
 *
 * Provides consistent key generation for all caches used in workflow execution.
 * Prevents typos and inconsistencies in key construction.
 *
 * Key Formats:
 * - Context cache: "{runId}:{itemId}"
 * - Tree cache: "{runId}"
 * - Execution cache: "{runId}"
 * - Trigger items cache: "{runId}"
 * - Paused workflow state: "{runId}"
 * - Evaluated control nodes: "{runId}"
 * - State managers: "{runId}"
 */
public final class CacheKeys {

    private CacheKeys() {
        // Utility class - prevent instantiation
    }

    // ========================================================================
    // SEPARATORS
    // ========================================================================

    /**
     * Standard separator for composite cache keys.
     */
    public static final String SEPARATOR = ":";

    /**
     * Dot separator for nested keys (e.g., split child items).
     */
    public static final String DOT_SEPARATOR = ".";

    // ========================================================================
    // V2StepByStepService CACHE KEYS
    // ========================================================================

    /**
     * Build context cache key.
     * Format: "{runId}:{itemId}"
     *
     * @param runId The workflow run ID
     * @param itemId The trigger item ID
     * @return Cache key
     */
    public static String contextCacheKey(String runId, String itemId) {
        return runId + SEPARATOR + itemId;
    }

    /**
     * Build tree cache key.
     * Format: "{runId}"
     *
     * @param runId The workflow run ID
     * @return Cache key
     */
    public static String treeCacheKey(String runId) {
        return runId;
    }

    /**
     * Build execution cache key.
     * Format: "{runId}"
     *
     * @param runId The workflow run ID
     * @return Cache key
     */
    public static String executionCacheKey(String runId) {
        return runId;
    }

    /**
     * Build trigger items cache key.
     * Format: "{runId}"
     *
     * @param runId The workflow run ID
     * @return Cache key
     */
    public static String triggerItemsCacheKey(String runId) {
        return runId;
    }

    // ========================================================================
    // WorkflowResumeService CACHE KEYS
    // ========================================================================

    /**
     * Build paused workflow state cache key.
     * Format: "{runId}"
     *
     * @param runId The workflow run ID
     * @return Cache key
     */
    public static String pausedWorkflowStateKey(String runId) {
        return runId;
    }

    /**
     * Build evaluated control nodes cache key.
     * Format: "{runId}"
     *
     * @param runId The workflow run ID
     * @return Cache key
     */
    public static String evaluatedCoresKey(String runId) {
        return runId;
    }

    /**
     * Build state manager cache key.
     * Format: "{runId}"
     *
     * @param runId The workflow run ID
     * @return Cache key
     */
    public static String stateManagerKey(String runId) {
        return runId;
    }

    // ========================================================================
    // CHILD ITEM KEYS (Split Parallel Execution)
    // ========================================================================

    /**
     * Build child item ID for split parallel execution.
     * Format: "{parentItemId}.{index}"
     *
     * @param parentItemId The parent trigger item ID
     * @param index The child item index
     * @return Child item ID
     */
    public static String childItemId(String parentItemId, int index) {
        return parentItemId + DOT_SEPARATOR + index;
    }

    /**
     * Check if an item ID is a child item (contains dot separator).
     *
     * @param itemId The item ID to check
     * @return true if it's a child item
     */
    public static boolean isChildItem(String itemId) {
        return itemId != null && itemId.contains(DOT_SEPARATOR);
    }

    /**
     * Extract parent item ID from a child item ID.
     * Example: "item-0.2" -> "item-0"
     *
     * @param childItemId The child item ID
     * @return Parent item ID, or null if not a child item
     */
    public static String extractParentItemId(String childItemId) {
        if (childItemId == null || !childItemId.contains(DOT_SEPARATOR)) {
            return null;
        }
        return childItemId.substring(0, childItemId.lastIndexOf(DOT_SEPARATOR));
    }

    /**
     * Extract child index from a child item ID.
     * Example: "item-0.2" -> 2
     *
     * @param childItemId The child item ID
     * @return Child index, or -1 if not a child item or invalid format
     */
    public static int extractChildIndex(String childItemId) {
        if (childItemId == null || !childItemId.contains(DOT_SEPARATOR)) {
            return -1;
        }
        try {
            String indexStr = childItemId.substring(childItemId.lastIndexOf(DOT_SEPARATOR) + 1);
            return Integer.parseInt(indexStr);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ========================================================================
    // KEY VALIDATION
    // ========================================================================

    /**
     * Validate a cache key is not null or blank.
     *
     * @param key The key to validate
     * @param keyName The name of the key (for error messages)
     * @throws IllegalArgumentException if key is null or blank
     */
    public static void validateKey(String key, String keyName) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(keyName + " cannot be null or blank");
        }
    }

    /**
     * Validate a run ID is not null or blank.
     *
     * @param runId The run ID to validate
     * @throws IllegalArgumentException if runId is null or blank
     */
    public static void validateRunId(String runId) {
        validateKey(runId, "runId");
    }

    /**
     * Validate an item ID is not null or blank.
     *
     * @param itemId The item ID to validate
     * @throws IllegalArgumentException if itemId is null or blank
     */
    public static void validateItemId(String itemId) {
        validateKey(itemId, "itemId");
    }
}
