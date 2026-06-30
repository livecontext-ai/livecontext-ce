-- V240 - Historical: rewrote the COMMENT ON COLUMN descriptions for the V224
-- columns to flag them DORMANT post ADR-009 owner-pays rollback. The columns
-- themselves are dropped by V243; this migration is preserved for prod replay
-- compatibility (already applied 2026-05-17 - Flyway history requires the
-- script to remain at this version).
--
-- Idempotent: COMMENT ON COLUMN is replace-unconditionally.

COMMENT ON COLUMN auth.subscription.organization_id IS
    'DORMANT (ADR-009, 2026-05-17). Dropped by V243.';

COMMENT ON COLUMN auth.credit_ledger.organization_id IS
    'DORMANT (ADR-009, 2026-05-17). Dropped by V243.';
