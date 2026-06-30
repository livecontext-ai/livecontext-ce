-- Create shedlock table in trigger schema for distributed scheduler locking.
-- This allows trigger-service to use ShedLock independently of orchestrator-service.
CREATE TABLE IF NOT EXISTS "trigger".shedlock (
    name VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMPTZ NOT NULL,
    locked_at TIMESTAMPTZ NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
