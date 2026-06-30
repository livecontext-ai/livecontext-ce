-- V183 - Backfill orchestrator.workflow_runs.state_snapshot_seq from JSONB
--
-- Part of plan v4 §V183. The column was added in V178; the V181 trigger
-- (state_snapshot_seq_monotonicity) enforces monotone progression but does
-- NOT seed the column for pre-V178 rows that have a `seq` field inside
-- their `state_snapshot` JSONB.
--
-- Rows with `state_snapshot_seq = 0` AND `state_snapshot ? 'seq'` have a
-- legitimate JSONB-embedded seq that needs to be lifted into the SQL column,
-- so the post-plan-v4 CAS path (#1) sees a non-stale `expectedSeq`.
--
-- Idempotent: re-running is a no-op once seq columns are populated. Safe to
-- run alongside live traffic - the WHERE clause only touches rows where seq=0
-- which is the pre-backfill state. The V181 trigger allows equal-seq UPDATEs
-- (the predicate is `NEW.seq < OLD.seq`), so any concurrent writer that
-- increments seq concurrently with this backfill will not regress.
--
-- This migration uses single-statement UPDATE (no LOOP/COMMIT inside DO).
-- Reasons (audit B M1 of plan v4 round 2):
--   1. Flyway wraps each migration in a single tx by default; PG forbids
--      COMMIT inside a DO block running in an outer transaction. The
--      `-- @flyway.executeInTransaction=false` directive would unblock LOOP/
--      COMMIT but introduces partial-success risk (a crash mid-loop leaves
--      half the rows backfilled).
--   2. The expected row count in prod is small (≤ few thousand pre-V178
--      runs). A single UPDATE locks fewer rows than the batch approach
--      because we use the trigger-friendly form: only rows actually needing
--      backfill are touched (WHERE filter is selective).
--   3. The COALESCE handles both missing `seq` (sets to 0, no-op since
--      already 0) and present-but-non-numeric (defensive: skipped by the
--      ? 'seq' predicate).
--
-- If, in some unforeseen prod state, this migration takes > 5min, the deploy
-- runbook escalation is: ALTER TABLE workflow_runs DISABLE TRIGGER
-- state_snapshot_seq_monotonicity, manually run a chunked LOOP, re-enable
-- the trigger. The migration is idempotent so re-running V183 is safe.

-- IMPORTANT: workflow_runs.state_snapshot is TEXT, not JSONB (verified in
-- V1__create_orchestrator_schema.sql line 66). All JSONB operators (`->>`,
-- `?`, `#>>`) require an explicit CAST(... AS jsonb) - same pattern used
-- across JsonbPatchExecutor.java and the rest of the codebase.

UPDATE orchestrator.workflow_runs
SET state_snapshot_seq = COALESCE((CAST(state_snapshot AS jsonb)->>'seq')::bigint, 0)
WHERE state_snapshot_seq = 0
  AND state_snapshot IS NOT NULL
  AND state_snapshot <> ''
  AND CAST(state_snapshot AS jsonb) ? 'seq'
  AND (CAST(state_snapshot AS jsonb)->>'seq') ~ '^[0-9]+$';

-- Sanity: emit a NOTICE with the count of backfilled rows for the deploy log.
DO $$
DECLARE
  remaining BIGINT;
BEGIN
  SELECT COUNT(*) INTO remaining
  FROM orchestrator.workflow_runs
  WHERE state_snapshot_seq = 0
    AND state_snapshot IS NOT NULL
    AND state_snapshot <> ''
    AND CAST(state_snapshot AS jsonb) ? 'seq';
  IF remaining > 0 THEN
    RAISE NOTICE 'V183 backfill: % rows with seq=0 + JSONB.seq remain (non-numeric or NULL inside JSONB)', remaining;
  END IF;
END $$;
