-- V178 - A2 (audit Opus A 2026-05-09): out-of-tx snapshot read cache
--
-- Adds a monotonic sequence number to workflow_runs alongside state_snapshot.
-- The column is incremented by every state_snapshot writer (full rewrite +
-- jsonb_set patch path) so the SnapshotService.getSnapshot() out-of-tx cache
-- can validate its parsed StateSnapshot against the current DB version
-- WITHOUT pulling the (potentially TOAST-detoasted) state_snapshot TEXT and
-- re-parsing it through Jackson on every SSE poll.
--
-- Hot path target: SnapshotService:548 (SSE), StateReconstructor (resume),
-- AgentWorkflowFireService:1003. At ~100 SSE polls/s/run × 1000 runs and a
-- ~10-30KB state_snapshot, the parse step dominates Java alloc tick.
--
-- Postgres 11+ ADD COLUMN BIGINT NOT NULL DEFAULT 0 is metadata-only
-- (no table rewrite). Existing rows logically read 0; the next snapshot
-- mutation increments to 1 and the cache populates from there.

ALTER TABLE orchestrator.workflow_runs
    ADD COLUMN state_snapshot_seq BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN orchestrator.workflow_runs.state_snapshot_seq IS
    'Monotonic version counter incremented atomically with every state_snapshot write. Used by the out-of-tx SnapshotService read cache to invalidate stale parsed StateSnapshots without reading/parsing the JSONB column.';

-- Race-3 mitigation (audit Opus C 2026-05-09): backfill the seq column
-- from the JSONB {seq} field for runs that existed before this migration.
-- Without this, every active run would carry seq_column=0 vs JSONB.seq=K
-- until the next mutator fired - causing the SnapshotService cache to
-- serve stale snapshots for the duration (5-30s of SSE freeze per run).
--
-- The cast extracts the JSON top-level "seq" int. Defensive COALESCE in
-- case the JSONB is malformed or the field is missing on legacy rows.
UPDATE orchestrator.workflow_runs
   SET state_snapshot_seq = COALESCE((state_snapshot::jsonb ->> 'seq')::bigint, 0)
 WHERE state_snapshot_seq = 0
   AND state_snapshot IS NOT NULL
   AND state_snapshot::jsonb ? 'seq';
