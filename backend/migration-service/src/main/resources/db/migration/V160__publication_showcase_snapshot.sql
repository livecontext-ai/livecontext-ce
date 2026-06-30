-- ============================================================================
-- V160: Publication Showcase Snapshot
--
-- Adds a single JSONB column on publication.workflow_publications that holds
-- the complete frozen view of the publisher's source run (run state, steps,
-- edges, aggregated steps, per-epoch signals, epoch timestamps, and per-
-- interface pre-rendered templates + items).
--
-- Marketplace (anonymous) read paths read from this column directly so they
-- don't need to call the orchestrator at request time. The previous design
-- - cloning the source run into a `showcase_*` row in workflow_runs - is
-- being phased out; the clone is no longer the source of truth, the JSONB
-- is. See V161 for the backfill that captures existing clones into JSONB
-- and then deletes them.
-- ============================================================================

ALTER TABLE publication.workflow_publications
    ADD COLUMN IF NOT EXISTS showcase_snapshot JSONB,
    ADD COLUMN IF NOT EXISTS showcase_snapshot_captured_at TIMESTAMPTZ;
