-- V175: tenant_flags table for per-tenant kernel-runtime feature flags.
--
-- Backs the TenantFlagService (P2.3.3). Initial use case:
-- state-snapshot.elide-running-nodes per-tenant ramp. Schema is generic and
-- supports any future kernel-runtime flag.
--
-- Cardinality: O(active_flags × ramping_tenants). With ~10 flags and
-- ~100 tenants in steady state ≈ 1000 rows max - trivial.
--
-- Read pattern: O(1) in-memory cache served by TenantFlagService. The DB is
-- only read on application startup (cache load) and on-demand cache refresh.
-- Hot path (every saveSnapshot, every elide-serializer call) MUST NOT touch
-- this table - see audit B C5 cost contract.
--
-- Write pattern: low-frequency (operator-initiated flag flips, ~10s of
-- flips/year typical). Each write goes through TenantFlagService.flip() which
-- updates DB + cache + writes a flag_flip_audit row in the same TX.

-- "value" is quoted because it's a reserved word in some SQL dialects
-- (notably H2 in strict / non-PostgreSQL modes used by integration tests).
-- Hibernate's @Column(name="\"value\"") on TenantFlagEntity emits the same
-- quoted form, keeping prod (Postgres) and CI (H2) consistent.
CREATE TABLE IF NOT EXISTS orchestrator.tenant_flags (
    flag_name   TEXT        NOT NULL,
    tenant_id   TEXT        NOT NULL,
    "value"     BOOLEAN     NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  TEXT,
    PRIMARY KEY (flag_name, tenant_id)
);

-- Tenant-scoped queries ("what flags are on for this tenant?") are an admin
-- diagnostic pattern - secondary index keeps them fast.
CREATE INDEX IF NOT EXISTS idx_tenant_flags_tenant
    ON orchestrator.tenant_flags (tenant_id);

COMMENT ON TABLE orchestrator.tenant_flags IS
    'Per-tenant kernel-runtime feature flags. In-memory cached by TenantFlagService; DB is durable backing only. Default OFF (missing row = false).';
