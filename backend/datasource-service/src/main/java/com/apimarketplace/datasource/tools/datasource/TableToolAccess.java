package com.apimarketplace.datasource.tools.datasource;

import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;

import java.util.List;
import java.util.Optional;

/**
 * Shared {@code allowedTableIds} ("approved table list") enforcement for the table-tool modules
 * ({@link DataSourceTableModule}, {@link DataSourceRowModule}, {@link DataSourceSchemaModule},
 * {@link TablePublishModule}).
 *
 * <p>The list is resolved from the canonical CREDENTIALS channel via
 * {@link ToolAccessControl#getAllowedIds(java.util.Map, String)} - the same channel every other
 * resource tool uses (workflow/application/file) and the channel {@code grantCreatedResource}
 * writes to. Historically the table module read this list from {@code context.variables()} while
 * the create-grant appended to {@code context.credentials()}, so a just-created table was never
 * added to the agent's own allow-list (a silent no-op). Routing every read through here keeps the
 * grant round-trip intact and gives the row/schema/publish modules the allow-list enforcement the
 * table CRUD module already had.
 *
 * <p>Semantics (from {@code getAllowedIds}): {@code null} = unrestricted (no allow-list configured),
 * {@code []} = explicit no-access, a populated list = scoped to those table ids.
 */
final class TableToolAccess {

    private TableToolAccess() {}

    /** The agent's approved table-id list, or {@code null} when unrestricted. */
    static List<String> allowedTableIds(ToolExecutionContext context) {
        return ToolAccessControl.getAllowedIds(
                context != null ? context.credentials() : null, "table");
    }

    /**
     * Returns a PERMISSION_DENIED failure when the agent has a restricted table allow-list that
     * does NOT contain {@code tableId}; empty otherwise (unrestricted, or the id is allowed, or the
     * id is absent - a missing id is left for the caller's own MISSING_PARAMETER handling).
     */
    static Optional<ToolExecutionResult> denyIfNotAllowed(ToolExecutionContext context, Object tableId) {
        List<String> allowed = allowedTableIds(context);
        if (allowed != null && tableId != null && !allowed.contains(String.valueOf(tableId))) {
            return Optional.of(ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
                    "This table is not in your approved table list."));
        }
        return Optional.empty();
    }
}
