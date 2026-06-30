package com.apimarketplace.orchestrator.utils;

/**
 * Constants used throughout workflow execution.
 * Centralizes magic numbers and strings to improve maintainability.
 */
public final class ExecutionConstants {

    private ExecutionConstants() {
        // Prevent instantiation
    }

    // ============================================================
    // EXECUTION LIMITS
    // ============================================================

    /** Default maximum number of iterations for while loops */
    public static final int DEFAULT_MAX_LOOP_ITERATIONS = 100;

    /** Default batch size for trigger items */
    public static final int DEFAULT_TRIGGER_BATCH_SIZE = 100;

    /** Default maximum number of items from a datasource */
    public static final int DEFAULT_MAX_DATASOURCE_ITEMS = 10000;

    /** Default timeout for step execution in milliseconds */
    public static final long DEFAULT_STEP_TIMEOUT_MS = 30_000;

    /** Default timeout for workflow execution in milliseconds */
    public static final long DEFAULT_WORKFLOW_TIMEOUT_MS = 300_000;

    // ============================================================
    // ITEM INDEX CALCULATION
    // ============================================================

    /**
     * Multiplier for parent item index in split child items.
     * Used to create unique item indices: childIndex = parentIndex * MULTIPLIER + localIndex
     * Example: parent=2, local=3 -> child = 2*1000+3 = 2003
     */
    public static final int SPLIT_ITEM_INDEX_MULTIPLIER = 1000;

    // ============================================================
    // CONTENT TYPES
    // ============================================================

    /** JSON content type for storage */
    public static final String CONTENT_TYPE_JSON = "application/json";

    // ============================================================
    // NODE PREFIXES
    // ============================================================

    /** Prefix for trigger node IDs */
    public static final String PREFIX_TRIGGER = "trigger:";

    /** Prefix for step node IDs (MCP tools) */
    public static final String PREFIX_STEP = "mcp:";

    /** Prefix for table node IDs (CRUD operations) */
    public static final String PREFIX_TABLE = "table:";

    /** Prefix for agent node IDs */
    public static final String PREFIX_AGENT = "agent:";

    /** Prefix for core node references (decision, loop, split, merge, fork, transform, wait) */
    public static final String PREFIX_CORE = "core:";

    /** Prefix for note node IDs */
    public static final String PREFIX_NOTE = "note:";

    /** Prefix for interface node IDs */
    public static final String PREFIX_INTERFACE = "interface:";

    // ============================================================
    // STATUS VALUES
    // ============================================================

    /** Status value for skipped nodes */
    public static final String STATUS_SKIPPED = "SKIPPED";

    /** Status value for completed nodes */
    public static final String STATUS_COMPLETED = "COMPLETED";

    /** Status value for failed nodes */
    public static final String STATUS_FAILED = "FAILED";

    /** Status value for running nodes */
    public static final String STATUS_RUNNING = "RUNNING";

    /** Status value for ready nodes */
    public static final String STATUS_READY = "READY";

    /** Status value for pending nodes */
    public static final String STATUS_PENDING = "PENDING";

    // ============================================================
    // EVENT TYPES
    // ============================================================

    /** Event type for step-by-step ready state */
    public static final String EVENT_STEP_BY_STEP_READY = "STEP_BY_STEP_READY";

    /** Event type for step-by-step paused state */
    public static final String EVENT_STEP_BY_STEP_PAUSED = "STEP_BY_STEP_PAUSED";

    // ============================================================
    // CONTEXT KEYS
    // ============================================================

    /** Key for item index in output */
    public static final String KEY_ITEM_INDEX = "item_index";

    /** Alternative key for item index in output */
    public static final String KEY_ITEM_INDEX_ALT = "itemIndex";

    /** Key for epoch in output */
    public static final String KEY_EPOCH = "epoch";

    /** Key for spawn in output */
    public static final String KEY_SPAWN = "spawn";

    /** Key for HTTP status in output */
    public static final String KEY_HTTP_STATUS = "http_status";

    // ============================================================
    // DECISION BRANCH NAMES
    // ============================================================

    /** Branch name for 'if' condition (true) */
    public static final String BRANCH_IF = "if";

    /** Branch name for 'then' action */
    public static final String BRANCH_THEN = "then";

    /** Branch name for 'elsif' conditions */
    public static final String BRANCH_ELSIF = "elsif";

    /** Branch name for 'else' action (default) */
    public static final String BRANCH_ELSE = "else";

    // ============================================================
    // UTILITY METHODS
    // ============================================================

    /**
     * Extracts the node type prefix from a node ID.
     *
     * @param nodeId The node ID (e.g., "mcp:my_step")
     * @return The prefix (e.g., "mcp:") or null if no prefix found
     */
    public static String extractPrefix(String nodeId) {
        if (nodeId == null) {
            return null;
        }
        int colonIndex = nodeId.indexOf(':');
        if (colonIndex > 0) {
            return nodeId.substring(0, colonIndex + 1);
        }
        return null;
    }

    /**
     * Checks if a node ID has a specific prefix.
     *
     * @param nodeId The node ID to check
     * @param prefix The prefix to look for (e.g., PREFIX_STEP)
     * @return true if the node ID starts with the prefix
     */
    public static boolean hasPrefix(String nodeId, String prefix) {
        return nodeId != null && prefix != null && nodeId.startsWith(prefix);
    }
}
