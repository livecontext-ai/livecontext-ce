-- V307: workspace hard-delete support.
--
-- The DELETE /organizations/{id} flow soft-deletes (sets deleted_at). A daily cron
-- hard-purges the OPERATIONAL data after a 30-day grace window, but RETAINS the
-- financial ledger (credit_ledger / usage_cycle / audit) and keeps the organization
-- row itself as a tombstone so owner-pays ledger references stay valid (ADR-009).
--
-- purged_at lets the cron be idempotent and distinguishes the three states:
--   deleted_at NULL                  -> active workspace
--   deleted_at set, purged_at NULL   -> soft-deleted, in grace window (restorable)
--   deleted_at set, purged_at set    -> tombstone: operational data purged,
--                                       financial ledger retained, org row kept

ALTER TABLE auth.organization
    ADD COLUMN IF NOT EXISTS purged_at TIMESTAMP NULL;

ALTER TABLE auth.organization
    ADD COLUMN IF NOT EXISTS deleted_by BIGINT NULL;

COMMENT ON COLUMN auth.organization.purged_at IS
    'Set when the workspace hard-purge ran (operational data deleted, financial ledger retained). NULL = not yet purged.';
COMMENT ON COLUMN auth.organization.deleted_by IS
    'User id that initiated the workspace soft-delete (audit). NULL for active orgs.';

-- Supports the purge cron lookup (find soft-deleted, not-yet-purged orgs).
CREATE INDEX IF NOT EXISTS idx_organization_pending_purge
    ON auth.organization (deleted_at)
    WHERE deleted_at IS NOT NULL AND purged_at IS NULL;
