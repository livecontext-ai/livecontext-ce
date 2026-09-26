package com.apimarketplace.datasource.tools.datasource;

import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.services.DataSourceService;
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
     * The workspace's per-member rules on ONE table, for every table-tool action that names one:
     * the same rules the REST CRUD entry point applies ({@code CrudController.enforceReadAccess} /
     * {@code enforceWriteAccess}), keyed on the table's own workspace and the CALLER's role.
     * A member DENIED the table is told it does not exist (as for another workspace's table); a
     * member who may only read it is refused any write.
     *
     * <p>The tool used to skip both: row and schema actions go straight to the CRUD executor, and
     * update / delete passed no caller to the service, whose own gate then checked the table's
     * OWNER, who is never restricted. A restricted member reached, through an agent, rows and
     * writes the REST surface refuses them.
     *
     * <p>Applied only to a table inside the caller's workspace: for any other one the downstream
     * scope check answers not-found, and answering "read-only" here would reveal that it exists.
     * An unknown table, or one with no workspace, is also left to the downstream checks.
     */
    static Optional<ToolExecutionResult> denyIfMemberRestricted(DataSourceService dataSourceService,
                                                                ToolExecutionContext context, String tenantId,
                                                                Long tableId, boolean write) {
        if (tableId == null || dataSourceService == null) {
            return Optional.empty();
        }
        return denyIfMemberRestricted(dataSourceService, context, tenantId,
                dataSourceService.getDataSource(tableId).orElse(null), write);
    }

    /** Same rules, for a caller that has already loaded the table (no second lookup). */
    static Optional<ToolExecutionResult> denyIfMemberRestricted(DataSourceService dataSourceService,
                                                                ToolExecutionContext context, String tenantId,
                                                                DataSource ds, boolean write) {
        if (ds == null || dataSourceService == null) {
            return Optional.empty();
        }
        Long tableId = ds.id();
        String orgId = ds.organizationId();
        String callerOrgId = context != null ? context.orgId() : null;
        if (orgId == null || orgId.isBlank()
                || !ScopeGuard.isInStrictScope(tenantId, callerOrgId, ds.tenantId(), orgId)) {
            return Optional.empty();
        }
        String role = context != null ? context.orgRole() : null;
        String id = String.valueOf(tableId);
        if (!dataSourceService.canAccessViaOrg(orgId, tenantId, id, role)) {
            return Optional.of(ToolExecutionResult.failure(ToolErrorCode.DATASOURCE_NOT_FOUND,
                    "Data source not found: " + tableId));
        }
        if (write && !dataSourceService.canWriteViaOrg(orgId, tenantId, id, role)) {
            return Optional.of(ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
                    "You can read this table but not change it: your access to it in this workspace is read-only "
                    + "(set by a workspace admin, or by a viewer role)."));
        }
        return Optional.empty();
    }

    /**
     * The workspace role's own write block, for the one action that names no table yet: create.
     * A VIEWER may not create a table, exactly as the REST create endpoint refuses them.
     */
    static Optional<ToolExecutionResult> denyIfViewer(ToolExecutionContext context) {
        if (context != null && OrgAccessGuard.isRoleWriteBlocked(context.orgId(), context.orgRole())) {
            return Optional.of(ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
                    "Your role in this workspace is viewer (read-only): you cannot create a table."));
        }
        return Optional.empty();
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
