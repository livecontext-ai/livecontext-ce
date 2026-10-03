-- ======================================================================
-- V564: storage.restricted_run_since, when each run first held RESTRICTED
--       data, kept after the data itself is gone (CASA LC-066 / LC-011)
--
-- publication-service judges a showcase snapshot stored before the capture
-- checked it by comparing its capturedAt with the moment its source run first
-- held Gmail / Google Drive data. That moment was MIN(created_at) over the
-- run's RESTRICTED storage rows that still exist. Once the restricted-data
-- purge is armed it deletes the oldest of them first, so the minimum moves
-- LATER, and a snapshot captured after the run became restricted would be
-- judged clean, served, and stamped checked for good.
--
-- This table keeps the moment per run. Nothing deletes from it (a run id and a
-- timestamp are not restricted data). Writers:
--   - the payload write path (StorageService), on the first RESTRICTED row of a
--     run: INSERT ... ON CONFLICT DO NOTHING, set once;
--   - storage-service's purge (RestrictedStorageRetentionSweeper), BEFORE it
--     deletes any restricted row: the earliest restricted row of each affected
--     run, kept when earlier (rows tagged later by the backfill can be older);
--   - this migration, for every run that already holds restricted rows.
-- Readers take the earlier of this value and the oldest restricted row still
-- present, so the answer can only move EARLIER, never later.
--
-- Small (one row per run that ever held restricted data): plain CREATE TABLE
-- and a one-shot INSERT ... SELECT over the RESTRICTED rows, read through the
-- partial index idx_storage_restricted_run (V536). Idempotent: IF NOT EXISTS,
-- and the backfill only ever moves a value earlier.
--
-- Operator order (the project docs, LC-066): deploy this release and let
-- publication-service's legacy showcase sweep finish BEFORE arming the purge
-- (data-classification.restricted.sweep.enabled).
-- ======================================================================

CREATE TABLE IF NOT EXISTS storage.restricted_run_since (
    run_id           VARCHAR(255) PRIMARY KEY,
    restricted_since TIMESTAMPTZ  NOT NULL
);

COMMENT ON TABLE storage.restricted_run_since IS
    'CASA LC-066: when each run first held RESTRICTED (Gmail / Google Drive) data. Never deleted by the restricted-data purge, so the showcase check keeps the true moment after the rows are gone.';

INSERT INTO storage.restricted_run_since (run_id, restricted_since)
SELECT run_id, MIN(created_at)
FROM storage.storage
WHERE data_sensitivity = 'RESTRICTED'
  AND run_id IS NOT NULL
  AND created_at IS NOT NULL
GROUP BY run_id
ON CONFLICT (run_id) DO UPDATE
    SET restricted_since = EXCLUDED.restricted_since
    WHERE storage.restricted_run_since.restricted_since > EXCLUDED.restricted_since;
