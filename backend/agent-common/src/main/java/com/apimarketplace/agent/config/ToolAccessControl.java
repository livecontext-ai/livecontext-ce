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
        // present (on workflow, table, interface, agent and file alike) only switches what the
        // user is looking at, through the same checks as get; it mutates nothing.
        Map.entry("table",       Set.of("get", "list", "query_rows", "help", "present")),
        // read_rows / find_rows are builder-internal TABLE reads dispatched through
        // WorkflowBuilderProvider.execute (the workflow tool), so they are gated on the
        // "workflow" access mode here. They are pure reads (they never mutate the plan or
        // the rows), so a read-mode builder agent (workflowAccessMode='read') MUST be able
        // to inspect a workflow's table data - without them, the gate would wrongly deny a
        // read. The write counterparts insert_row/update_row/delete_row stay WRITE.
        // mock_suggest is a pure read: it proposes a mock output and mutates nothing.
        Map.entry("workflow",    Set.of("load", "get", "list", "describe", "validate", "runs", "get_run", "wait_run", "get_node_output", "search", "help", "get_plan", "read_rows", "find_rows", "mock_suggest", "present")),
        Map.entry("interface",   Set.of("get", "list", "help", "present")),
        // budgets is a pure read - it reports what capped agents have spent and mutates
        // nothing. Omitting it would make the action PERMISSION_DENIED for every agent
        // created with agentAccessMode=read, while the tool help advertises it to all of
        // them: an action visible to a caller that cannot call it.
        Map.entry("agent",       Set.of("get", "list", "help", "budgets", "inbox", "outbox", "review_inbox", "backlog", "recurrence_list", "get_history", "search_messages", "present")),
        // runs / get_run / get_node_output are the SAME three read actions the "workflow"
        // entry above already lists, and ApplicationCrudModule handles all three. Omitting
        // them here made an applicationAccessMode='read' agent be told it needs write access
        // to look at a run it is allowed to see (a fail-CLOSED misclassification).
        Map.entry("application", Set.of("search", "my", "get", "visualize", "help", "runs", "get_run", "get_node_output")),
        Map.entry("skill",       Set.of("get", "list", "list_folders", "help")),
        // Memory: save and delete are the only writes. `search` is a READ and is
        // listed as one - denying recall to a read-only agent would leave it with
        // a memory index in its context and no way to open anything in it.
        Map.entry("memory",      Set.of("get", "list", "search", "help")),
        // Mailbox: reading a folder, naming its folders and marking a message seen change
        // nothing outside the mailbox and nothing a reader would mind. Everything else is a
        // write, and two of them are irreversible: send reaches a person, delete removes what
        // only the provider still holds. mark_read is deliberately a READ even though it sets
        // a flag - a read-only inbox scanner that cannot mark what it processed re-reads the
        // same messages forever, which is the shape of agent this mode exists to allow. That
        // argument covers mark_read ONLY: mark_unread undoes a marker a person may have set,
        // so it changes what someone sees in their own inbox and stays a write. The same list
        // also gates the workspace VIEWER role, and a VIEWER is read-only everywhere else.
        Map.entry("mailbox",     Set.of("read", "folders", "mark_read", "help")),
        // WARNING - these two entries are INERT today, and the enforcement they imply does
        // not exist. checkWriteAccess derives its key as category + "AccessMode", so they would
        // need a "catalogAccessMode" / "web_searchAccessMode" credential to do anything, and NO
        // producer emits either: neither the ToolsConfig record, nor any relay's access-mode
        // list, nor the agent tool schema, nor the frontend payload builder. CatalogExecuteModule
        // does call checkWriteAccess(credentials, "catalog", ...), and that call always returns
        // "allowed" because the key is absent. Nothing is currently exposed by this: the catalog
        // family is gated by toolsConfig.mode instead, and web_search by its boolean toggle.
        // Left in place rather than deleted so that removing a permission check is a deliberate
        // decision rather than a side effect. Wiring a real axis means the full producer chain
        // (record + parser + every relay list + schema + help + frontend), not just a key here.
        Map.entry("catalog",     Set.of("search", "response_schema", "help")),
        Map.entry("web_search",  Set.of("search", "fetch")),
        // Files: read actions. Write actions (create_folder / move_to_folder) are NOT
        // listed, so a read-only agent (fileAccessMode='read') is blocked from them.
        // Singular "file" matches the allow-list category (CREDENTIAL_KEYS) and the
        // "fileAccessMode" credential key (checkWriteAccess derives category+"AccessMode").
        Map.entry("file",        Set.of("list", "get", "view", "visualize", "help", "present"))
    );

    /**
     * Categories whose {@code <category>AccessMode} credential is actually PRODUCED, in the
     * order the relays emit it. Every hop that forwards access modes MUST iterate this list
     * instead of repeating a literal, because a hand-copied list is how an axis goes inert:
     * the tool keeps calling {@link #checkWriteAccess}, the relay never carries the key, and
     * the tool then sees a caller with no stated permissions, which reads as ALLOWED. That is
     * silent, and it fails OPEN.
     *
     * <p>Adding a category here is what turns its axis on everywhere at once, so it belongs
     * here only once the rest of the chain exists: the {@code ToolsConfig} record and its
     * parser, the agent tool schema and help, and the frontend payload builder. Those four
     * cannot be derived from this list (typed record components and TS literals), so
     * {@code AccessModeProducerChainTest} pins them instead.
     *
     * <p>Deliberately NOT the key set of {@link #READ_ACTIONS}: {@code catalog} and
     * {@code web_search} are classified there but intentionally have no axis (see the WARNING
     * on those entries), and deriving from the key set would silently start enforcing catalog.
     */
    public static final List<String> ENFORCED_ACCESS_MODE_CATEGORIES = List.of(
        "table", "workflow", "interface", "agent", "application", "skill", "file", "memory", "mailbox");

    /**
     * The categories classified in {@link #READ_ACTIONS} that deliberately have NO access-mode
     * axis. Kept explicit so {@code AccessModeRegistryParityTest} can require every category to
     * be one or the other: a new tool category that is neither is a category whose read/write
     * switch nobody wired, which is exactly the omission this pair exists to make loud.
     */
    public static final Set<String> AXIS_LESS_CATEGORIES = Set.of("catalog", "web_search");

    /** Plain credential keys, as the tool controllers receive them. */
    public static final List<String> ACCESS_MODE_KEYS =
        ENFORCED_ACCESS_MODE_CATEGORIES.stream().map(c -> c + "AccessMode").toList();

    /** The same keys in the in-process agent-loop namespace ({@code __<key>__}). */
    public static final List<String> INTERNAL_ACCESS_MODE_KEYS =
        ACCESS_MODE_KEYS.stream().map(k -> "__" + k + "__").toList();

    /**
     * Every category this class classifies reads for.
     *
     * <p>Exposed so the registry test can require each one to be either enforced or
     * explicitly axis-less. Without it that test can only compare the two public lists
     * against each other, which is true by construction: a new {@code READ_ACTIONS} entry
     * with no axis would slip through the very check meant to catch it.
     */
    public static Set<String> classifiedCategories() {
        return READ_ACTIONS.keySet();
    }

    /** The READ actions of one category, empty when the category is not classified. */
    public static Set<String> readActions(String category) {
        return READ_ACTIONS.getOrDefault(category, Set.of());
    }

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
