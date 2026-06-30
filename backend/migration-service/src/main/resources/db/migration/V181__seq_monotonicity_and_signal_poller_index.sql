-- V179 - Seq monotonicity invariant + signal poller covering index
--
-- Part of the AUTO/SBS optimization bundle (rev v3, audit 8.4 + 7.4).
-- See .claude_full_optim_plan_v3.md §0 (#0) and §9 (#9).
--
-- ===== #0: state_snapshot_seq monotonicity =====
-- The state_snapshot_seq column (V178) is the CAS oracle for the optimistic
-- UPDATE path in StateSnapshotService. To guarantee correctness we enforce
-- at the storage layer that any UPDATE on workflow_runs cannot regress the
-- seq value. Application contract (audited via ArchUnit): only
-- JsonbPatchExecutor and saveSnapshotFullRewrite mutate state_snapshot,
-- and both bump seq atomically.
--
-- Trigger overhead at production rates (~10K UPDATE/s):
--   500 ns/UPDATE × 10K = 5 ms/s = 0.05% CPU. Acceptable.
--
-- BEFORE INSERT branch added per audit A v3 MUST-FIX 6: the V178 default=0
-- is the single legal initial value. INSERT with seq < 0 indicates a code
-- bug.

CREATE OR REPLACE FUNCTION orchestrator.enforce_state_snapshot_seq()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state_snapshot_seq < 0 THEN
            RAISE EXCEPTION 'state_snapshot_seq must be >= 0 on INSERT (was=%)',
                NEW.state_snapshot_seq;
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.state_snapshot_seq < OLD.state_snapshot_seq THEN
        RAISE EXCEPTION 'state_snapshot_seq must not regress (was=%, new=%)',
            OLD.state_snapshot_seq, NEW.state_snapshot_seq;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS state_snapshot_seq_monotonicity ON orchestrator.workflow_runs;

CREATE TRIGGER state_snapshot_seq_monotonicity
    BEFORE INSERT OR UPDATE ON orchestrator.workflow_runs
    FOR EACH ROW EXECUTE FUNCTION orchestrator.enforce_state_snapshot_seq();

COMMENT ON FUNCTION orchestrator.enforce_state_snapshot_seq IS
    'Enforces state_snapshot_seq monotonicity: every UPDATE must advance or '
    'leave seq unchanged; INSERT must have seq >= 0. The seq is the CAS oracle '
    'for the optimistic UPDATE path in StateSnapshotService. See plan v3 #0.';

-- ===== #9: signal_waits covering index for keyset pagination =====
-- The signal poller switches from LIMIT 50 to keyset pagination LIMIT 500.
-- Covering index on (status, signal_type, expires_at, id) supports:
--   * expired-timer scan: WHERE status = 'PENDING' AND signal_type = 'WAIT_TIMER'
--                          AND expires_at <= NOW() AND id > :lastId ORDER BY id
--   * expired-non-timer scan: idem with signal_type != 'WAIT_TIMER'
-- The included id at the end keeps the scan index-only.
--
-- This index supersedes idx_signal_waits_expires_at_status (kept for now,
-- can be dropped post-soak in a follow-up).

CREATE INDEX IF NOT EXISTS idx_signal_waits_keyset_poll
    ON orchestrator.workflow_signal_waits (status, signal_type, expires_at, id);

COMMENT ON INDEX orchestrator.idx_signal_waits_keyset_poll IS
    'Covering index for keyset-paginated signal poller. See plan v3 #9.';
