-- V539: persisted backoff for the three CE bundle pollers (model catalog, skills, API catalog).
--
-- A poller that fails used to keep its 15-minute cadence forever. For the API catalog that is a
-- 32 MB download per quarter hour per broken install (measured 2026-09-26: six installs, ~780 MB
-- an hour, none ever getting closer to success). The worst case never recorded a failure at all:
-- an OutOfMemoryError during apply escapes the scheduler's catch.
--
-- backoff_level   : consecutive attempts that did not end in success. Raised when an attempt
--                   STARTS and reset to 0 only by a completed success, so an attempt that crashed
--                   or was killed counts as a failure by default.
-- next_attempt_at : the scheduled poll (and the startup sync) stays quiet until then. A manual
--                   "sync now" ignores it. NULL = no wait.
--
-- Written only by a targeted UPDATE from the scheduler; the entities map both columns read-only so
-- the existing whole-row saves (applier success, failure bookkeeping) never overwrite them.
--
-- Every reference is schema-qualified: beforeEachMigrate resets search_path to orchestrator.
-- Idempotent.

ALTER TABLE agent.catalog_bundle_sync_status
    ADD COLUMN IF NOT EXISTS backoff_level   INT         NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ NULL;

ALTER TABLE agent.skill_bundle_sync_status
    ADD COLUMN IF NOT EXISTS backoff_level   INT         NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ NULL;

ALTER TABLE catalog.api_catalog_bundle_sync_status
    ADD COLUMN IF NOT EXISTS backoff_level   INT         NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ NULL;
