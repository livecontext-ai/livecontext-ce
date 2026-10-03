-- V537: ShedLock table for storage-service (CASA LC-011).
-- The restricted-data retention sweep (RestrictedStorageRetentionSweeper) is a
-- @Scheduled job; with several storage-service replicas it must run on one at a
-- time. Same shape as the other services' shedlock tables (e.g. V69).

CREATE TABLE IF NOT EXISTS storage.shedlock (
    name VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMPTZ NOT NULL,
    locked_at TIMESTAMPTZ NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
