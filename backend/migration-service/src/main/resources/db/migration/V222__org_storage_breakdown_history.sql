-- ============================================================================
-- V222: Org-scoped storage breakdown + usage history
-- Issue #149 - make /breakdown and /history meaningful in TEAM workspace.
--
-- Mirror of tenant_storage_breakdown / storage_usage_history with
-- organization_id as the first part of the PK. Trackers write to BOTH
-- tables (tenant + org) when the call is org-scoped; org table stays
-- empty for personal-scope work.
--
-- Reconciliation populates fresh org rows from the underlying owning
-- rows once a day (cron 3 AM) or on-demand via POST /recalculate
-- when X-Organization-ID is present.
--
-- Idempotent - IF NOT EXISTS guards on tables + indexes.
-- ============================================================================

SET search_path TO storage;

CREATE TABLE IF NOT EXISTS storage.org_storage_breakdown (
    organization_id VARCHAR(255) NOT NULL,
    category        VARCHAR(50)  NOT NULL,
    used_bytes      BIGINT       NOT NULL DEFAULT 0,
    item_count      INTEGER      NOT NULL DEFAULT 0,
    calculated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (organization_id, category)
);

CREATE INDEX IF NOT EXISTS idx_org_breakdown_org
    ON storage.org_storage_breakdown(organization_id);

CREATE TABLE IF NOT EXISTS storage.org_storage_usage_history (
    id              BIGSERIAL    PRIMARY KEY,
    organization_id VARCHAR(255) NOT NULL,
    category        VARCHAR(50)  NOT NULL,
    used_bytes      BIGINT       NOT NULL DEFAULT 0,
    item_count      INTEGER      NOT NULL DEFAULT 0,
    snapshot_date   DATE         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (organization_id, category, snapshot_date)
);

CREATE INDEX IF NOT EXISTS idx_org_storage_history_org_date
    ON storage.org_storage_usage_history(organization_id, snapshot_date);

COMMENT ON TABLE storage.org_storage_breakdown IS
    'Issue #149 - per-org per-category rollup mirroring tenant_storage_breakdown. '
    'Populated by StorageBreakdownService trackers (4-arg variants) when org context '
    'is present, and by StorageReconciliationService.reconcileOrganization() daily.';

COMMENT ON TABLE storage.org_storage_usage_history IS
    'Issue #149 - daily snapshot of org_storage_breakdown. Powers /api/storage/quota/history '
    'when the caller sends X-Organization-ID. Retained for 90 days, purged with the '
    'tenant history.';
