package com.apimarketplace.orchestrator.services.merge;

/**
 * Utility class for handling item merge scopes.
 *
 * <p>The scope determines which items should be merged together.
 * Items are organized hierarchically using dot notation:
 * <ul>
 *   <li>"0" - Root trigger item</li>
 *   <li>"0.1", "0.2", "0.3" - Split children of item 0</li>
 *   <li>"0.1.1", "0.1.2" - Nested Split children of item 0.1</li>
 * </ul>
 *
 * <p>Merge scope rules:
 * <ul>
 *   <li>Split children (0.1, 0.2, 0.3) merge in parent scope "0"</li>
 *   <li>Nested children (0.1.1, 0.1.2) merge in parent scope "0.1"</li>
 *   <li>Root items (0, 1, 2) are their own scope</li>
 * </ul>
 */
public final class ItemMergeScope {

    private ItemMergeScope() {
        // Utility class
    }

    /**
     * Extracts the parent scope from an itemId.
     *
     * <p>Examples:
     * <ul>
     *   <li>"0.1" → "0" (Split child → parent)</li>
     *   <li>"0.1.2" → "0.1" (Nested child → parent)</li>
     *   <li>"0" → "0" (Root → self)</li>
     *   <li>"1" → "1" (Root → self)</li>
     * </ul>
     *
     * @param itemId The item ID (e.g., "0.1", "0.1.2", "0")
     * @return The parent scope
     */
    public static String getParentScope(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return "";
        }
        int lastDot = itemId.lastIndexOf('.');
        return lastDot > 0 ? itemId.substring(0, lastDot) : itemId;
    }

    /**
     * Gets the root scope (first level) from an itemId.
     *
     * <p>Examples:
     * <ul>
     *   <li>"0.1.2" → "0"</li>
     *   <li>"0.1" → "0"</li>
     *   <li>"0" → "0"</li>
     * </ul>
     *
     * @param itemId The item ID
     * @return The root scope
     */
    public static String getRootScope(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return "";
        }
        int firstDot = itemId.indexOf('.');
        return firstDot > 0 ? itemId.substring(0, firstDot) : itemId;
    }

    /**
     * Checks if an itemId is a child of a given scope.
     *
     * <p>Examples:
     * <ul>
     *   <li>isChildOf("0.1", "0") → true</li>
     *   <li>isChildOf("0.1.2", "0.1") → true</li>
     *   <li>isChildOf("0.1.2", "0") → true (nested child)</li>
     *   <li>isChildOf("1.1", "0") → false (different root)</li>
     *   <li>isChildOf("0", "0") → false (same level, not child)</li>
     * </ul>
     *
     * @param itemId The item ID to check
     * @param scope The parent scope
     * @return true if itemId is a child of scope
     */
    public static boolean isChildOf(String itemId, String scope) {
        if (itemId == null || scope == null) {
            return false;
        }
        return itemId.startsWith(scope + ".") && itemId.length() > scope.length() + 1;
    }

    /**
     * Checks if an itemId is a direct child (one level down) of a given scope.
     *
     * <p>Examples:
     * <ul>
     *   <li>isDirectChildOf("0.1", "0") → true</li>
     *   <li>isDirectChildOf("0.1.2", "0") → false (two levels down)</li>
     *   <li>isDirectChildOf("0.1.2", "0.1") → true</li>
     * </ul>
     *
     * @param itemId The item ID to check
     * @param scope The parent scope
     * @return true if itemId is a direct child of scope
     */
    public static boolean isDirectChildOf(String itemId, String scope) {
        if (!isChildOf(itemId, scope)) {
            return false;
        }
        // Check there's no additional dot after the scope prefix
        String suffix = itemId.substring(scope.length() + 1);
        return !suffix.contains(".");
    }

    /**
     * Extracts the child index from an itemId within a scope.
     *
     * <p>Examples:
     * <ul>
     *   <li>getChildIndex("0.1", "0") → 1</li>
     *   <li>getChildIndex("0.3", "0") → 3</li>
     *   <li>getChildIndex("0.1.2", "0.1") → 2</li>
     * </ul>
     *
     * @param itemId The item ID
     * @param scope The parent scope
     * @return The child index, or -1 if not a direct child
     */
    public static int getChildIndex(String itemId, String scope) {
        if (!isDirectChildOf(itemId, scope)) {
            return -1;
        }
        String suffix = itemId.substring(scope.length() + 1);
        try {
            return Integer.parseInt(suffix);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Gets the depth level of an itemId.
     *
     * <p>Examples:
     * <ul>
     *   <li>"0" → 0 (root level)</li>
     *   <li>"0.1" → 1 (first Split level)</li>
     *   <li>"0.1.2" → 2 (nested Split level)</li>
     * </ul>
     *
     * @param itemId The item ID
     * @return The depth level (number of dots)
     */
    public static int getDepth(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (char c : itemId.toCharArray()) {
            if (c == '.') {
                count++;
            }
        }
        return count;
    }

    /**
     * Checks if an itemId belongs to a Split (has a parent).
     *
     * @param itemId The item ID
     * @return true if the item is a Split child
     */
    public static boolean isSplitChild(String itemId) {
        return itemId != null && itemId.contains(".");
    }

    /**
     * Creates a child itemId from a parent scope and index.
     *
     * <p>Examples:
     * <ul>
     *   <li>createChildId("0", 1) → "0.1"</li>
     *   <li>createChildId("0.1", 2) → "0.1.2"</li>
     * </ul>
     *
     * @param parentScope The parent scope
     * @param index The child index (1-based typically)
     * @return The child itemId
     */
    public static String createChildId(String parentScope, int index) {
        return parentScope + "." + index;
    }
}
