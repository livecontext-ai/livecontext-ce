-- V149: CONCURRENTLY index on credit_ledger.pin_id (Phase 1a - additive).
--
-- Runs OUTSIDE Flyway's transaction wrapper because CREATE INDEX CONCURRENTLY
-- cannot be issued inside a tx. The header below tells Flyway 9.5+ to skip
-- the BEGIN/COMMIT it would normally emit around the script.
--
-- Lock posture: SHARE UPDATE EXCLUSIVE on credit_ledger - concurrent reads/writes
-- proceed during the build. Estimated 1-3 minutes on 50M rows (the partial
-- predicate `WHERE pin_id IS NOT NULL` keeps the index tiny: only RESERVE/MARKUP
-- ledger rows from the new flow get an entry).
--
-- On crash: PG marks the index INVALID. Recovery: drop it and re-run V149 (Flyway
-- will skip the migration since version 149 is recorded; use `flyway:repair` first
-- if needed, then re-trigger by bumping a no-op `--` comment).

-- flyway:executeInTransaction=false

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_cl_pin_id_recent
    ON auth.credit_ledger (pin_id)
    WHERE pin_id IS NOT NULL;
