-- V243 - Drop the V224 org_id columns + V225 indexes on subscription and
-- credit_ledger. Owner-pays (ADR-009) is the only credit routing; nothing
-- in production writes or reads these columns. V226's backfill values are
-- now noise. Per the project rule "no dead legacy code", remove the
-- physical column.
--
-- Sequencing: V224 creates the columns, V225 builds 4 partial indexes,
-- V226 backfills personal-org-ids, V240 rewrites the column comments to
-- DORMANT. V243 drops the indexes BEFORE the columns - load-bearing:
-- PostgreSQL would cascade-drop dependent indexes on DROP COLUMN under an
-- ACCESS EXCLUSIVE lock, which blocks all writes to the hot
-- `auth.credit_ledger` table for the duration. Dropping each index
-- CONCURRENTLY first downgrades the lock to SHARE UPDATE EXCLUSIVE and
-- keeps writes flowing during the migration window.
--
-- Idempotent: DROP COLUMN IF EXISTS + DROP INDEX IF EXISTS - safe to re-run
-- on environments where prior cleanup already happened.
--
-- Reversal: re-running V224 + V225 + V226 would re-create the substrate
-- with NULL values. No code path consumes it.
-- ---------------------------------------------------------------------------
-- flyway:executeInTransaction=false
-- ---------------------------------------------------------------------------

DROP INDEX CONCURRENTLY IF EXISTS auth.idx_subscription_active_per_org;
DROP INDEX CONCURRENTLY IF EXISTS auth.idx_subscription_organization_status;
DROP INDEX CONCURRENTLY IF EXISTS auth.idx_credit_ledger_org_created;
DROP INDEX CONCURRENTLY IF EXISTS auth.idx_credit_ledger_org_source;

ALTER TABLE auth.subscription   DROP COLUMN IF EXISTS organization_id;
ALTER TABLE auth.credit_ledger  DROP COLUMN IF EXISTS organization_id;
