-- V225 - V224 follow-up: add the credit/subscription org indexes via
-- CREATE INDEX CONCURRENTLY so prod (HOT, large auth.credit_ledger) does
-- not freeze on the migration window. Round-2 audit blocker fix.
--
-- Flyway directive: `executeInTransaction=false` is REQUIRED (NOT
-- `transactional=false` - that's a different non-existent flag and would be
-- silently ignored, leaving the script wrapped in a tx, where Postgres
-- rejects CREATE INDEX CONCURRENTLY with 'cannot run inside a transaction
-- block'). See V149/V150/V204/V208-V214 for the canonical pattern. The
-- directive tells Flyway to run each statement in autocommit mode.
--
-- Idempotency: `CREATE INDEX CONCURRENTLY IF NOT EXISTS` - safe to re-run
-- and safe when V224's original non-CONCURRENT statements created them on
-- environments that already migrated (the IF NOT EXISTS short-circuits).
--
-- Post-deploy invariant: `SELECT * FROM pg_index WHERE NOT indisvalid` MUST
-- return zero rows for the 4 new indexes. A partially-failed CONCURRENTLY
-- leaves indisvalid=false rows that need manual `DROP INDEX` + re-run.
-- ---------------------------------------------------------------------------
-- flyway:executeInTransaction=false
-- ---------------------------------------------------------------------------

-- Partial unique: at most ONE active subscription per org. Personal
-- subscriptions are unconstrained (one per user via existing UNIQUE on
-- provider_subscription_id; null org_id rows skip this index entirely).
-- ACTIVE-only filter - past CANCELLED / EXPIRED rows in the same org are
-- normal history and must not block a new active row.
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS idx_subscription_active_per_org
    ON auth.subscription (organization_id)
    WHERE organization_id IS NOT NULL AND status = 'active';

-- Org-scope read path: "what's MY org's active subscription?" - strictly
-- bounded to non-null org_id rows so the index is small.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_subscription_organization_status
    ON auth.subscription (organization_id, status)
    WHERE organization_id IS NOT NULL;

-- Org-scope ledger reads: "show me my org's credit history" - strictly
-- bounded to non-null org_id rows. Per PR8 pattern, treat NULL as personal-
-- scope so legacy rows never leak into org views.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_credit_ledger_org_created
    ON auth.credit_ledger (organization_id, created_at DESC)
    WHERE organization_id IS NOT NULL;

-- Idempotency check by (org_id, source_id) - pack-upgrade, marketplace,
-- workflow-node, web-search, image-generation paths all use source_id for
-- dedup. With org_id in the picture, a same-source from a personal and an
-- org workspace are distinct events.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_credit_ledger_org_source
    ON auth.credit_ledger (organization_id, source_id)
    WHERE organization_id IS NOT NULL AND source_id IS NOT NULL;
