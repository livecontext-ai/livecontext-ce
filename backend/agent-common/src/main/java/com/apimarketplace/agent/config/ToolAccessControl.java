package com.apimarketplace.agent.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Centralized registry for tool action types (READ/WRITE) and access mode enforcement.
 * <p>
 * Each tool module can check access with a single call:
 * {@code ToolAccessControl.checkWriteAccess(context.credentials(), "table", action)}
 * <p>
 * Access modes are stored in credentials as {@code <category>AccessMode -> "read"|"write"}.
 * Default (null/absent) = "write" (full access) for backward compatibility.
 */
public final class ToolAccessControl {

    private ToolAccessControl() {}

    /** READ actions per tool category - anything NOT listed here is considered WRITE. */
    private static final Map<String, Set<String>> READ_ACTIONS = Map.ofEntries(
        Map.entry("table",       Set.of("get", "list", "query_rows", "help")),
        // read_rows / find_rows are builder-internal TABLE reads dispatched through
        // WorkflowBuilderProvider.execute (the workflow tool), so they are gated on the
        // "workflow" access mode here. They are pure reads (they never mutate the plan or
        // the rows), so a read-mode builder agent (workflowAccessMode='read') MUST be able
        // to inspect a workflow's table data - without them, the gate would wrongly deny a
        // read. The write counterparts insert_row/update_row/delete_row stay WRITE.
        // mock_suggest is a pure read: it proposes a mock output and mutates nothing.
        Map.entry("workflow",    Set.of("load", "get", "list", "describe", "validate", "runs", "get_run", "wait_run", "get_node_output", "search", "help", "get_plan", "read_rows", "find_rows", "mock_suggest")),
        Map.entry("interface",   Set.of("get", "list", "help")),
        Map.entry("agent",       Set.of("get", "list", "help", "inbox", "outbox", "review_inbox", "backlog", "recurrence_list", "get_history", "search_messages")),
        Map.entry("application", Set.of("search", "my", "get", "visualize", "help")),
        Map.entry("skill",       Set.of("get", "list", "list_folders", "help")),
        Map.entry("catalog",     Set.of("search", "response_schema", "help")),
        Map.entry("web_search",  Set.of("search", "fetch")),
        // Files: read actions. Write actions (create_folder / move_to_folder) are NOT
        // listed, so a read-only agent (fileAccessMode='read') is blocked from them.
        // Singular "file" matches the allow-list category (CREDENTIAL_KEYS) and the
        // "fileAccessMode" credential key (checkWriteAccess derives category+"AccessMode").
        Map.entry("file",        Set.of("list", "get", "view", "visualize", "help"))
    );

    /**
     * Check if an action is a READ action for the given tool category.
     */
    public static boolean isReadAction(String category, String action) {
        Set<String> reads = READ_ACTIONS.get(category);
        return reads != null && reads.contains(action);
    }

    /**
     * Check if a WRITE action is allowed given the access mode from credentials.
     * <p>
     * Returns empty if allowed, or a failure message if denied.
     * READ actions are always allowed regardless of mode.
     *
     * @param credentials the agent's credentials map (from ToolExecutionContext)
     * @param category    the tool category (e.g., "table", "workflow")
     * @param action      the action being performed (e.g., "create", "query_rows")
     * @return empty if allowed, error message if denied
     */
    public static Optional<String> checkWriteAccess(Map<String, Object> credentials,
                                                     String category, String action) {
        // READ actions always pass
        if (isReadAction(category, action)) {
            return Optional.empty();
        }

        // No credentials = no restriction
        if (credentials == null) {
            return Optional.empty();
        }

        // Check category-level access mode. Tool controllers receive plain keys
        // ("agentAccessMode"), while in-process agent loop credentials carry the
        // same value with the internal namespace ("__agentAccessMode__").
        String modeKey = category + "AccessMode";
        Object modeValue = credentials.get(modeKey);
        if (modeValue == null) {
            modeValue = credentials.get("__" + modeKey + "__");
        }
        if (modeValue == null) {
            return Optional.empty(); // no mode set = full access (default)
        }

        String mode = modeValue.toString().trim();
        if ("read".equalsIgnoreCase(mode)) {
            return Optional.of("Access denied: " + category + " is configured as read-only. "
                    + "The action '" + action + "' requires write access. "
                    + "Read-only allows: " + READ_ACTIONS.getOrDefault(category, Set.of()));
        }
        if (!"write".equalsIgnoreCase(mode)) {
            return Optional.of("Access denied: invalid " + category + " access mode '" + mode + "'. "
                    + "Expected 'read' or 'write'.");
        }

        return Optional.empty();
    }

    // ==================== Resource credential keys ====================

    private static final Map<String, String> CREDENTIAL_KEYS = Map.of(
        "workflow",    "allowedWorkflowIds",
        "table",       "allowedTableIds",
        "interface",   "allowedInterfaceIds",
        "agent",       "allowedAgentIds",
        "application", "allowedApplicationIds",
        "file",        "allowedFileIds"
    );

    /**
     * Resolve a resource allow-list from runtime credentials.
     * Plain keys from tool controllers win over internal namespaced keys from
     * in-process agent-loop credentials.
     * <p>
     * The per-family GRANT sentinel ({@code <family>Grant} ∈ {@code {all|none|custom}})
     * is translated to this list convention at every credential-emit point
     * (AgentContextBuilder / AgentNode / SubAgentExecutionHandler): {@code "all"} →
     * the key is OMITTED, {@code "none"} → {@code []}, {@code "custom"} → the list.
     * By the time credentials reach here the grant has already become an
     * absent/empty/populated list, so this resolver is purely list-driven and never
     * needs to inspect a grant key.
     *
     * @return null when unrestricted (key absent), empty list when explicitly no
     * access ({@code []}), or the configured resource ID list.
     */
    @SuppressWarnings("unchecked")
    public static List<String> getAllowedIds(Map<String, Object> credentials, String category) {
        if (credentials == null || category == null) {
            return null;
        }

        String key = CREDENTIAL_KEYS.get(category);
        if (key == null) {
            return null;
        }

        Object value = credentials.get(key);
        if (value == null) {
            value = credentials.get(namespacedKey(key));
        }

        if (value instanceof List<?> list) {
            // Stringify each element rather than an unchecked cast: a numeric-ID
            // resource (tables) can be forwarded as List<Integer> (e.g. from an
            // agent created via MCP), which would never match a String comparison
            // at the call site. UUID-based resources are already strings → no-op.
            return list.stream().map(String::valueOf).toList();
        }
        return null;
    }

    /**
     * Auto-grant access to a newly created resource.
     * If the agent has a restricted allowed-list for this resource type,
     * the created ID is appended so the agent can access its own creation.
     * If there is no restriction (key absent), this is a no-op.
     *
     * @param credentials the agent's mutable credentials map
     * @param category    the resource type (e.g., "workflow", "table")
     * @param createdId   the ID of the newly created resource
     */
    @SuppressWarnings("unchecked")
    public static void grantCreatedResource(Map<String, Object> credentials,
                                             String category, String createdId) {
        if (credentials == null || createdId == null || category == null) return;

        String key = CREDENTIAL_KEYS.get(category);
        if (key == null) return;

        // Synchronized on credentials map to handle parallel tool execution safely
        synchronized (credentials) {
            Object value = credentials.get(key);
            String writeKey = key;
            String namespacedKey = namespacedKey(key);
            if (value == null && credentials.containsKey(namespacedKey)) {
                value = credentials.get(namespacedKey);
                writeKey = namespacedKey;
            }
            if (value instanceof List<?> existing) {
                // Restricted list exists - append the new ID
                List<String> mutable = new ArrayList<>((List<String>) existing);
                if (!mutable.contains(createdId)) {
                    mutable.add(createdId);
                    credentials.put(writeKey, mutable);
                }
            }
            // If key is absent (null) → unrestricted access, no-op needed
        }
    }

    private static String namespacedKey(String key) {
        return "__" + key + "__";
    }
}
