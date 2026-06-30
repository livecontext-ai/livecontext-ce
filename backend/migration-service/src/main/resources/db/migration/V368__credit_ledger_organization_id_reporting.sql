-- V368 - Re-introduce credit_ledger.organization_id as a REPORTING dimension.
-- (Renumbered from V366: the reward-code system took V366/V367 first and is
-- already applied in prod; this dev migration moves after it.)
--
-- ADR-0010 (see docs/adr/0010_per_workspace_usage_reporting.md). ADR-009
-- dropped the V224 organization_id column (V243) because owner-pays is the
-- ONLY credit ROUTING and nothing read/wrote the column. ADR-009 explicitly
-- listed "no 'this org spent X this month' dashboard slice" as an accepted
-- negative, "out of scope until product asks for it". Product now asks for it:
-- the Settings -> Quota & Usage workspace filter must show real per-workspace
-- consumption.
--
-- This re-adds the column STRICTLY as a descriptive tag ("which workspace did
-- this consumption happen in"). It does NOT revive org wallets / org
-- subscriptions (the V224 routing substrate stays gone). Routing + balance are
-- untouched: owner-pays still resolves the payer and the wallet is the owner's
-- single subscription. The column only lets the usage reads (summary / history
-- / analytics) be sliced by the active workspace.
--
-- NULLABLE on purpose (no backfill): historical rows, system grants, admin
-- adjustments and any consume path without an active workspace context carry
-- NULL. The Quota page treats NULL as belonging to the "All workspaces"
-- aggregate only (a per-workspace view shows just that org's rows). We never
-- guess a workspace for a historical row - retroactive attribution is unknown.
--
-- Metadata-only ALTER (nullable, no DEFAULT) -> instant even on the hot
-- auth.credit_ledger table. The index is built CONCURRENTLY (SHARE UPDATE
-- EXCLUSIVE, keeps writes flowing) so it cannot run inside the migration
-- transaction.
-- ---------------------------------------------------------------------------
-- flyway:executeInTransaction=false
-- ---------------------------------------------------------------------------

ALTER TABLE auth.credit_ledger
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(64);

COMMENT ON COLUMN auth.credit_ledger.organization_id IS
    'V368 (ADR-0010) - descriptive workspace tag: the organization the consumption '
    'happened in. Reporting dimension ONLY; does NOT affect routing/balance '
    '(owner-pays keys the wallet on user_id). NULL = unattributed (historical row, '
    'system grant/adjustment, or no active workspace context) -> shown only under '
    'the "All workspaces" usage aggregate, never in a per-workspace slice.';

-- Covers the org-scoped usage reads: summary / daily-analytics / distinct-lists
-- (user_id = payer AND organization_id = active-org, amount < 0, created_at win)
-- and the paged history list (same prefix, all amounts, ORDER BY created_at DESC).
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_cl_user_org_created
    ON auth.credit_ledger (user_id, organization_id, created_at DESC);
