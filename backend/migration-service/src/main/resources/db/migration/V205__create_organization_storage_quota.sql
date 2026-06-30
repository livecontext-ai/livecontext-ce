-- PR18 - create per-org peer tables for storage quota / breakdown / history.
--
-- Context: V204 added storage.storage.organization_id. The quota machinery
-- today (storage.tenant_storage_quota etc) is keyed by tenant_id (= user_id).
-- In the strict-isolation org model, consumption from runs in org workspace
-- accumulates against the org's quota, distinct from the executing member's
-- personal quota.
--
-- This migration creates the THREE peer tables, all keyed by organization_id:
--   - organization_storage_quota      : per-org max/used/limits
--   - organization_storage_breakdown  : per-(org, category) usage rollup
--   - organization_storage_usage_history : per-org daily snapshots
--
-- No data backfill: the org quota tables are populated on-demand at first
-- write by QuotaService.getOrCreateOrgQuota (PR18.4). max_bytes is seeded
-- from the org owner's subscription via PlanResolutionService - mirroring
-- the per-tenant signup-time sync logic in V198.
--
-- Defaults match auth.plan FREE allowance (100 MB) - applied when an org has
-- no active owner subscription (degenerate state) or when seeding pre-write.
-- Live max_bytes is re-synced on owner subscription change via the existing
-- SubscriptionService.syncStorageQuotaToPlan hook extended in PR18.4.
--
-- Idempotent: CREATE TABLE IF NOT EXISTS + CREATE INDEX IF NOT EXISTS.

CREATE TABLE IF NOT EXISTS storage.organization_storage_quota (
    organization_id  VARCHAR(255) PRIMARY KEY,
    max_bytes        BIGINT NOT NULL DEFAULT 104857600,    -- 100 MB (FREE)
    used_bytes       BIGINT NOT NULL DEFAULT 0,
    soft_limit_bytes BIGINT NOT NULL DEFAULT 83886080,     -- 80 MB (80%)
    hard_limit_bytes BIGINT NOT NULL DEFAULT 104857600,    -- 100 MB
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE storage.organization_storage_quota IS
    'Per-org storage quota. Mirrors tenant_storage_quota; used when storage '
    'rows carry a non-NULL organization_id. max_bytes is sourced from the org '
    'owner''s active subscription plan (PlanResolutionService) and synced on '
    'owner subscription change.';

CREATE TABLE IF NOT EXISTS storage.organization_storage_breakdown (
    organization_id VARCHAR(255) NOT NULL,
    category        VARCHAR(50)  NOT NULL,
    used_bytes      BIGINT       NOT NULL DEFAULT 0,
    item_count      INTEGER      NOT NULL DEFAULT 0,
    calculated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (organization_id, category)
);

COMMENT ON TABLE storage.organization_storage_breakdown IS
    'Per-(org, category) usage rollup. Categories are produced by '
    'StorageReconciliationService; mirrors tenant_storage_breakdown but '
    'aggregates rows where organization_id is set.';

CREATE TABLE IF NOT EXISTS storage.organization_storage_usage_history (
    id              BIGSERIAL PRIMARY KEY,
    organization_id VARCHAR(255) NOT NULL,
    category        VARCHAR(50)  NOT NULL,
    used_bytes      BIGINT       NOT NULL DEFAULT 0,
    item_count      INTEGER      NOT NULL DEFAULT 0,
    snapshot_date   DATE         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (organization_id, category, snapshot_date)
);

CREATE INDEX IF NOT EXISTS idx_org_storage_history_org_date
    ON storage.organization_storage_usage_history (organization_id, snapshot_date);

COMMENT ON TABLE storage.organization_storage_usage_history IS
    'Per-org daily storage usage snapshots. Mirrors storage_usage_history; '
    'snapshot writer (StorageHistoryService) writes to both tables.';
