package com.apimarketplace.orchestrator.tools.workflow.builder;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configuration for workflow builder actions, aliases, and modifying actions.
 *
 * Extracted from WorkflowBuilderProvider for Single Responsibility Principle.
 *
 * @see WorkflowBuilderProvider
 */
public final class WorkflowBuilderActionConfig {

    private WorkflowBuilderActionConfig() {
        // Utility class
    }

    /**
     * Primary actions visible to LLM in error messages and documentation.
     */
    public static final List<String> PRIMARY_ACTIONS = List.of(
            // Session management. `finish` is the canonical "publish draft" action;
            // `create` is kept as a backward-compat alias and intentionally NOT
            // listed here so the LLM only sees the unambiguous name in help/errors.
            "init", "load", "save", "discard", "finish", "execute",
            // Node creation (unified)
            "add_node",
            // Visualization & validation
            "describe", "validate", "search",
            // Connections
            "connect", "disconnect",
            // Modification
            "modify", "remove", "undo",
            // Table operations
            "insert_row", "read_rows", "update_row", "delete_row", "find_rows",
            // Production pin lifecycle
            "pin", "unpin",
            // Marketplace publication lifecycle
            "publish", "unpublish",
            // Help
            "help"
    );

    /**
     * Internal/hidden actions (not shown to LLM but still valid).
     */
    public static final List<String> HIDDEN_ACTIONS = List.of(
            "get_plan", "set_plan",
            "get", "list", "delete", "runs", "get_run", "get_node_output",
            // Advance a paused run (gated in chat via ToolAuthorizationPolicy):
            "resolve_approval", "continue_interface",
            // `create` is the legacy name for `finish`. Still routes through the
            // dispatcher (handled in WorkflowBuilderProvider) for back-compat with
            // older callers; hidden from new docs to push the LLM toward `finish`.
            "create"
    );

    /**
     * Mapping of action aliases to canonical action names.
     */
    public static final Map<String, String> ACTION_ALIASES = Map.ofEntries(
            Map.entry("save_to_table", "insert_row"),
            Map.entry("write_row", "insert_row"),
            Map.entry("find", "find_rows"),
            Map.entry("search_rows", "find_rows"),
            Map.entry("query_rows", "find_rows"),
            Map.entry("list_nodes", "describe"),
            Map.entry("show", "describe"),
            Map.entry("status", "describe")
    );

    /**
     * All supported actions (primary + hidden + aliases) for validation.
     */
    public static final Set<String> ALL_ACTIONS = buildAllActions();

    private static Set<String> buildAllActions() {
        Set<String> all = new HashSet<>();
        all.addAll(PRIMARY_ACTIONS);
        all.addAll(HIDDEN_ACTIONS);
        all.addAll(ACTION_ALIASES.keySet());
        return Set.copyOf(all);
    }

    /**
     * Actions that modify the workflow state and trigger auto-save.
     *
     * <p>Note: this set is the auto-save trigger set, NOT the immutability gate.
     * It intentionally includes {@code read_rows} and {@code find_rows} because
     * those route through {@code WorkflowBuilderTableOperations} which historically
     * touches session state (cursor tracking, last-query memo). Do not reuse this
     * set for the APPLICATION immutability gate - use {@link #PLAN_MUTATING_ACTIONS}
     * instead, which excludes pure reads.
     */
    public static final Set<String> MODIFYING_ACTIONS = Set.of(
            "add_node",
            "insert_row", "read_rows", "update_row", "delete_row", "find_rows",
            "connect", "disconnect", "modify", "remove", "undo", "set_plan"
    );

    /**
     * Actions that mutate a loaded workflow's plan (or its referenced table rows).
     * Used by {@link WorkflowBuilderProvider} to short-circuit modifying actions on
     * an APPLICATION-type workflow with an immutability rejection. Distinct from
     * {@link #MODIFYING_ACTIONS}:
     *
     * <ul>
     *   <li>Excludes {@code read_rows} / {@code find_rows} - they are pure reads
     *       that must remain callable on an APPLICATION (the acquirer can inspect
     *       the data of their acquired app).</li>
     *   <li>Includes the same actual-mutation set: node/edge additions, removals,
     *       node-field edits, table row writes, full-plan overwrite.</li>
     * </ul>
     *
     * <p>Session-save actions ({@code save}, {@code finish}, {@code create}) are
     * intentionally NOT in this set: the dispatch-time guard only catches in-flight
     * mutations; the final save is gated by {@code WorkflowBuilderLoader.executeSave}
     * defense-in-depth (re-queries the live entity for the APPLICATION flag).
     */
    public static final Set<String> PLAN_MUTATING_ACTIONS = Set.of(
            "add_node",
            "insert_row", "update_row", "delete_row",
            "connect", "disconnect", "modify", "remove", "undo", "set_plan"
    );

    /**
     * Read-only inspection actions that MUST NOT auto-open / focus the side panel
     * on completion. The agent calls these to inspect run state or workflow shape
     * (e.g. {@code get_run} after an execute, {@code get_node_output} to drill into
     * a failed step, {@code describe} to enumerate nodes) - stealing focus to the
     * workflow tab on every such call interrupts whatever the user was reading.
     *
     * <p>Used by {@link WorkflowBuilderResultEnricher#addSessionSnapshot} to skip
     * the {@code visualization} metadata injection when the action is read-only.
     * Actions absent from this set (e.g. {@code add_node}, {@code connect},
     * {@code execute}) still get the visualization → side panel focuses as before.
     */
    public static final Set<String> READ_ONLY_ACTIONS = Set.of(
            "get", "list", "runs", "get_run", "get_node_output",
            "describe", "validate", "search", "help", "get_plan"
    );

    /**
     * Resolve an action alias to its canonical action name.
     *
     * @param action The action or alias
     * @return The canonical action name
     */
    public static String resolveAlias(String action) {
        return ACTION_ALIASES.getOrDefault(action, action);
    }

    /**
     * Check if an action is a modifying action.
     *
     * @param action The canonical action name
     * @return true if the action modifies the workflow
     */
    public static boolean isModifyingAction(String action) {
        return MODIFYING_ACTIONS.contains(action);
    }

    /**
     * Check if an action is read-only (must not steal side-panel focus).
     *
     * @param action The canonical action name
     * @return true if the action is purely informational / inspection
     * @see #READ_ONLY_ACTIONS
     */
    public static boolean isReadOnlyAction(String action) {
        return action != null && READ_ONLY_ACTIONS.contains(action);
    }

    /**
     * Check if an action is valid (primary, hidden, or alias).
     *
     * @param action The action to check
     * @return true if the action is valid
     */
    public static boolean isValidAction(String action) {
        return ALL_ACTIONS.contains(action);
    }
}
