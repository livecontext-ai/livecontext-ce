-- ======================================================================
-- V562: storage.storage.retention_expires_at, the restricted-data retention
--       deadline (CASA LC-011)
--
-- Why a NEW column and not expires_at: expires_at is the caller's TTL, and
-- every release before this one enforces it on its own (the hourly generic
-- cleanup soft-deletes expired rows, RESTRICTED ones included while the
-- restricted-data purge is off; reads hide expired rows; the execution-log
-- purger hard-deletes DELETED rows). Writing the 30-day retention deadline
-- into expires_at would let a previous release, still running during a
-- rolling update, after a helm rollback or in a CE downgrade, delete the
-- Gmail / Drive backlog while the purge is supposed to be off. No previous
-- release reads retention_expires_at, so a rollback cannot act on it.
--
-- Nullable, no default: pods of the previous release keep INSERTing rows
-- without it; storage-service's RestrictedStorageBackfill fills it on
-- RESTRICTED rows (created_at + retention-days) and the write path stamps it
-- on every new RESTRICTED row. ADD COLUMN with no default is metadata-only,
-- so the ALTER holds its ACCESS EXCLUSIVE lock for milliseconds; lock_timeout
-- makes it FAIL FAST (retried by the next deploy) instead of queueing behind
-- a long transaction, same pattern as V535 / V561.
--
-- The indexes run OUTSIDE Flyway's transaction (CREATE INDEX CONCURRENTLY,
-- same pattern as V536). On crash PostgreSQL leaves the index INVALID:
-- DROP INDEX CONCURRENTLY the invalid one and re-run (flyway repair first if
-- needed); IF NOT EXISTS would otherwise skip an INVALID index.
-- ======================================================================

-- flyway:executeInTransaction=false

SET lock_timeout = '5s';
SET statement_timeout = '60s';

ALTER TABLE storage.storage
    ADD COLUMN IF NOT EXISTS retention_expires_at TIMESTAMPTZ;

-- RESET before the CONCURRENTLY builds below: plain SET is session-scoped and a 5s lock_timeout
-- would abort an index build on a production-sized table (see V535's trailer).
RESET lock_timeout;
RESET statement_timeout;

-- storage-service RestrictedStorageRetentionSweeper: restricted rows whose deadline (the earlier
-- of the caller's expires_at and retention_expires_at) has passed, keyset on (deadline, id).
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_storage_restricted_deadline
    ON storage.storage ((LEAST(expires_at, retention_expires_at)), id)
    WHERE data_sensitivity = 'RESTRICTED';

-- storage-service RestrictedStorageBackfill catch-up: restricted rows with no retention deadline
-- yet. Empty once the catch-up has run, so the daily re-check is one probe of an empty index.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_storage_restricted_retention_unset
    ON storage.storage (id)
    WHERE data_sensitivity = 'RESTRICTED' AND retention_expires_at IS NULL;
