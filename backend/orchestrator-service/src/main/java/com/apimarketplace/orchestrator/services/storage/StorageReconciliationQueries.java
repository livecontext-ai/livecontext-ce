package com.apimarketplace.orchestrator.services.storage;

/**
 * Native SQL queries for local storage reconciliation.
 * Each query returns {usedBytes, itemCount} for a single tenant and category.
 * Uses pg_column_size() for JSONB columns (most accurate on-disk size).
 *
 * Only contains queries for schemas owned by orchestrator-service.
 * Remote categories (AGENTS, INTERFACES, CONVERSATIONS, DATATABLES, PUBLICATIONS)
 * are fetched via HTTP clients from their respective services.
 */
public final class StorageReconciliationQueries {

    private StorageReconciliationQueries() {}

    public static final String STEP_OUTPUTS = """
        SELECT COALESCE(SUM(s.size_bytes), 0), COUNT(*)
        FROM storage.storage s
        WHERE s.tenant_id = :tid AND s.status = 'ACTIVE'
          AND s.storage_type = 'JSON'
          AND (s.source_type IS NULL OR s.source_type NOT IN ('S3_FILE','CHAT_ATTACHMENT'))
        """;

    public static final String FILES = """
        SELECT COALESCE(SUM(s.size_bytes), 0), COUNT(*)
        FROM storage.storage s
        WHERE s.tenant_id = :tid AND s.status = 'ACTIVE'
          AND (
            s.source_type IN ('S3_FILE','CHAT_ATTACHMENT')
            OR s.storage_type IN ('BINARY','TEXT')
          )
        """;

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
    // {@code storage.storage.organization_id} directly, matching the column the
    // gauge already uses ({@code QuotaService.updateOrganizationUsage} →
    // {@code StorageRepository.calculateOrganizationUsage} sums by
    // {@code organization_id} too). Same source of truth ⇒ the storage gauge
    // and the per-category stacked bar agree byte-for-byte modulo the
    // categories pinned to per-service rollups (AGENTS, INTERFACES,
    // CONVERSATIONS, DATATABLES, PUBLICATIONS), which stay as their last
    // incrementally tracked value in {@code org_storage_breakdown}.
    //
    // Earlier shape (2026-05-21, replaced before merge): joined
    // {@code workflows w ON w.id::text = s.workflow_id}. Verified broken on
    // prod tenant 1 (0 / 140 799 active rows matched). The column named
    // {@code storage.storage.workflow_id} despite its name carries a per-step
    // UUID, not the workflows PK - using it as a JOIN key drops every row.
    //
    // Why we don't route via {@code run_id → workflow_runs → workflows} either:
    // (a) it would silently drop rows whose run was deleted by retention
    // (FK cascade); (b) it would NOT include rows the gauge counts where
    // {@code organization_id} is stamped but {@code run_id} is NULL
    // (chat attachments outside a run, ad-hoc uploads, …); (c) the gauge
    // already aggregates by {@code organization_id} - anything else lets
    // gauge and breakdown drift again, which is the exact bug we're fixing.
    //
    // Index used: {@code idx_storage_org_status_created} (partial,
    // {@code WHERE organization_id IS NOT NULL AND status='ACTIVE'}).
    // ========================================================================

    public static final String STEP_OUTPUTS_BY_ORG = """
        SELECT COALESCE(SUM(s.size_bytes), 0), COUNT(*)
        FROM storage.storage s
        WHERE s.organization_id = :oid
          AND s.status = 'ACTIVE'
          AND s.storage_type = 'JSON'
          AND (s.source_type IS NULL OR s.source_type NOT IN ('S3_FILE','CHAT_ATTACHMENT'))
        """;

    public static final String FILES_BY_ORG = """
        SELECT COALESCE(SUM(s.size_bytes), 0), COUNT(*)
        FROM storage.storage s
        WHERE s.organization_id = :oid
          AND s.status = 'ACTIVE'
          AND (
            s.source_type IN ('S3_FILE','CHAT_ATTACHMENT')
            OR s.storage_type IN ('BINARY','TEXT')
          )
        """;

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
}
