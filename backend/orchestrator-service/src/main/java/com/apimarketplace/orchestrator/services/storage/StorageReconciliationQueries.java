package com.apimarketplace.orchestrator.services.storage;

import com.apimarketplace.common.storage.service.StorageRowCategories;

/**
 * Native SQL queries for local storage reconciliation.
 * Each query returns {usedBytes, itemCount} for a single tenant and category.
 * Uses pg_column_size() for JSONB columns (most accurate on-disk size).
 *
 * Only contains queries for schemas owned by orchestrator-service.
 * Remote categories (AGENTS, INTERFACES, CONVERSATIONS, DATATABLES, PUBLICATIONS)
 * are fetched via HTTP clients from their respective services.
 *
 * <p><b>FILES and STEP_OUTPUTS partition {@code storage.storage}</b>: their predicates come from
 * {@link StorageRowCategories}, which is also what the save and delete paths classify with, and
 * they are exact complements. Every ACTIVE row therefore lands in exactly one of the two, which
 * is what makes "the categories add up to the gauge" checkable rather than hoped for. Before
 * 2026-09-18 the FILES predicate tested {@code source_type IN ('S3_FILE', ...)} although
 * {@code S3_FILE} is a STORAGE type; 16 GB of one production tenant's files matched neither
 * category and were reported nowhere.
 */
public final class StorageReconciliationQueries {

    private StorageReconciliationQueries() {}

    private static final String FILES_PREDICATE = StorageRowCategories.filesSqlPredicate("s");
    private static final String STEP_OUTPUTS_PREDICATE = StorageRowCategories.stepOutputsSqlPredicate("s");

    /**
     * Which rows count as held bytes at all, before any category is chosen.
     *
     * <p>Manual folders are excluded. A folder is a sentinel row with no payload
     * ({@code size_bytes = 0}), so it never moved the byte totals, but it has no business in an
     * ITEM count either: a user who made three folders has not stored three step outputs. It is
     * also the one row class the counters cannot keep straight, since creating a folder credits
     * nothing while deleting one debits an item. The exclusion applies to the two categories AND
     * to the total they are checked against, so the partition still covers exactly what it claims.
     */
    private static final String HELD_ROWS = "s.status = 'ACTIVE' AND NOT COALESCE(s.is_folder, false)";

    public static final String STEP_OUTPUTS = """
        SELECT COALESCE(SUM(s.size_bytes), 0), COUNT(*)
        FROM storage.storage s
        WHERE s.tenant_id = :tid AND %s
          AND %s
        """.formatted(HELD_ROWS, STEP_OUTPUTS_PREDICATE);

    public static final String FILES = """
        SELECT COALESCE(SUM(s.size_bytes), 0), COUNT(*)
        FROM storage.storage s
        WHERE s.tenant_id = :tid AND %s
          AND %s
        """.formatted(HELD_ROWS, FILES_PREDICATE);

    public static final String EXECUTION_DATA = """
        SELECT COALESCE(SUM(
            COALESCE(pg_column_size(wr.state_snapshot), 0) +
            COALESCE(pg_column_size(wr.plan), 0) +
            COALESCE(pg_column_size(wr.trigger_payload), 0) +
            COALESCE(pg_column_size(wr.metadata), 0)
        ), 0), COUNT(*)
        FROM orchestrator.workflow_runs wr
        WHERE wr.tenant_id = :tid
        """;

    /**
     * Configuration from orchestrator-owned tables only (workflows + plan versions).
     * Skills storage is reported by agent-service.
     */
    public static final String CONFIGURATION_WORKFLOWS = """
        SELECT COALESCE((
            SELECT SUM(
                COALESCE(pg_column_size(w.plan), 0) +
                COALESCE(pg_column_size(w.data_inputs), 0)
            )
            FROM orchestrator.workflows w
            WHERE w.tenant_id = :tid
        ), 0) +
        COALESCE((
            SELECT SUM(pg_column_size(wpv.plan))
            FROM orchestrator.workflow_plan_versions wpv
            JOIN orchestrator.workflows w ON w.id = wpv.workflow_id
            WHERE w.tenant_id = :tid
        ), 0),
        COALESCE((
            SELECT COUNT(*) FROM orchestrator.workflows WHERE tenant_id = :tid
        ), 0)
        """;

    // ========================================================================
    // Org-scoped variants (Issue #149) - categorize from
    // {@code storage.storage.organization_id} directly, which is the column the
    // org rollup is keyed by. Same source of truth as the tenant variants above,
    // same two complementary predicates, so the org breakdown and the tenant
    // breakdown describe the same rows the same way.
    //
    // Earlier shape (2026-05-21, replaced before merge): joined
    // {@code workflows w ON w.id::text = s.workflow_id}. Verified broken on
    // prod tenant 1 (0 / 140 799 active rows matched). The column named
    // {@code storage.storage.workflow_id} despite its name carries a per-step
    // UUID, not the workflows PK - using it as a JOIN key drops every row.
    //
    // Why we don't route via {@code run_id → workflow_runs → workflows} either:
    // (a) it would silently drop rows whose run was deleted by retention
    // (FK cascade); (b) it would NOT include rows where {@code organization_id}
    // is stamped but {@code run_id} is NULL (chat attachments outside a run,
    // ad-hoc uploads, …); (c) the rollup is keyed by {@code organization_id} -
    // anything else lets the two scopes drift again.
    //
    // Index used: {@code idx_storage_org_status_created} (partial,
    // {@code WHERE organization_id IS NOT NULL AND status='ACTIVE'}).
    // ========================================================================

    public static final String STEP_OUTPUTS_BY_ORG = """
        SELECT COALESCE(SUM(s.size_bytes), 0), COUNT(*)
        FROM storage.storage s
        WHERE s.organization_id = :oid
          AND %s
          AND %s
        """.formatted(HELD_ROWS, STEP_OUTPUTS_PREDICATE);

    public static final String FILES_BY_ORG = """
        SELECT COALESCE(SUM(s.size_bytes), 0), COUNT(*)
        FROM storage.storage s
        WHERE s.organization_id = :oid
          AND %s
          AND %s
        """.formatted(HELD_ROWS, FILES_PREDICATE);

    public static final String EXECUTION_DATA_BY_ORG = """
        SELECT COALESCE(SUM(
            COALESCE(pg_column_size(wr.state_snapshot), 0) +
            COALESCE(pg_column_size(wr.plan), 0) +
            COALESCE(pg_column_size(wr.trigger_payload), 0) +
            COALESCE(pg_column_size(wr.metadata), 0)
        ), 0), COUNT(*)
        FROM orchestrator.workflow_runs wr
        JOIN orchestrator.workflows w ON w.id = wr.workflow_id
        WHERE w.organization_id = :oid
        """;

    public static final String CONFIGURATION_WORKFLOWS_BY_ORG = """
        SELECT COALESCE((
            SELECT SUM(
                COALESCE(pg_column_size(w.plan), 0) +
                COALESCE(pg_column_size(w.data_inputs), 0)
            )
            FROM orchestrator.workflows w
            WHERE w.organization_id = :oid
        ), 0) +
        COALESCE((
            SELECT SUM(pg_column_size(wpv.plan))
            FROM orchestrator.workflow_plan_versions wpv
            JOIN orchestrator.workflows w ON w.id = wpv.workflow_id
            WHERE w.organization_id = :oid
        ), 0),
        COALESCE((
            SELECT COUNT(*) FROM orchestrator.workflows WHERE organization_id = :oid
        ), 0)
        """;

    /**
     * Total ACTIVE bytes a tenant holds in {@code storage.storage}, independent of any category.
     *
     * <p>The reference the two category predicates must add up to. Used by the invariant test and
     * by nothing on the request path, so its cost is paid only where it buys a proof.
     */
    public static final String TOTAL_ACTIVE_BYTES = """
        SELECT COALESCE(SUM(s.size_bytes), 0), COUNT(*)
        FROM storage.storage s
        WHERE s.tenant_id = :tid AND %s
        """.formatted(HELD_ROWS);

    /** Total held bytes an organization has in {@code storage.storage}. */
    public static final String TOTAL_ACTIVE_BYTES_BY_ORG = """
        SELECT COALESCE(SUM(s.size_bytes), 0), COUNT(*)
        FROM storage.storage s
        WHERE s.organization_id = :oid AND %s
        """.formatted(HELD_ROWS);

    /**
     * Tenants the daily pass must reconcile: those that already have breakdown rows, UNION those
     * that hold storage rows.
     *
     * <p>Enumerating from the breakdown table alone was self-limiting: a tenant that had never
     * been reconciled was never found, so it never was. Measured on production 2026-09-18: 30
     * tenants held ACTIVE rows and had no breakdown row at all.
     *
     * <p>Tenant ids starting with an underscore are system tenants, not users: {@code
     * StorageService.validateQuota} already refuses to enforce a quota on them, so giving them a
     * quota row and five HTTP calls a night buys nothing. Written as LEFT(...) rather than a LIKE
     * pattern, because an underscore is a single-character wildcard in LIKE and would need escaping.
     */
    public static final String TENANTS_TO_RECONCILE = """
        SELECT tenant_id FROM storage.tenant_storage_breakdown
        WHERE LEFT(tenant_id, 1) <> '_'
        UNION
        SELECT DISTINCT tenant_id FROM storage.storage
        WHERE status = 'ACTIVE' AND LEFT(tenant_id, 1) <> '_'
        """;

    /** Organizations the daily pass must reconcile. Same rule as {@link #TENANTS_TO_RECONCILE}. */
    public static final String ORGS_TO_RECONCILE = """
        SELECT organization_id FROM storage.org_storage_breakdown
        UNION
        SELECT DISTINCT organization_id FROM storage.storage
        WHERE status = 'ACTIVE' AND organization_id IS NOT NULL
        """;
}
