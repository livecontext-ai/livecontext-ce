-- V180 - Instance lease (heartbeat failover) + signal_waits.claimed_generation
--
-- Part of the AUTO/SBS optimization bundle (rev v3).
-- See .claude_full_optim_plan_v3.md §10 (#10).
--
-- ===== instance_lease =====
-- Each orchestrator replica heartbeats every 10s, holding a 30s lease.
-- Peer recovery steals signal_waits whose owner_instance has a stale lease
-- (lease_until < NOW()) via SELECT FOR UPDATE SKIP LOCKED - replaces the
-- legacy 5-min poll window with a ≤30s p99 recovery.
--
-- last_id is the per-replica keyset cursor for the signal poller (#9).

CREATE TABLE IF NOT EXISTS orchestrator.instance_lease (
    instance_id      VARCHAR(128) PRIMARY KEY,
    lease_until      TIMESTAMPTZ  NOT NULL,
    generation       BIGINT       NOT NULL DEFAULT 1,
    last_id          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_instance_lease_lease_until
    ON orchestrator.instance_lease (lease_until);

COMMENT ON TABLE orchestrator.instance_lease IS
    'Per-replica heartbeat lease for orchestrator failover. See plan v3 #10.';
COMMENT ON COLUMN orchestrator.instance_lease.generation IS
    'Monotonic generation counter, bumped on every heartbeat and on bootstrap. '
    'Persisted as the fencing token in signal_waits.claimed_generation so a '
    'restarted instance cannot resume signals it claimed pre-restart.';
COMMENT ON COLUMN orchestrator.instance_lease.last_id IS
    'Keyset pagination cursor for the signal poller (#9). Each replica '
    'progresses through its own range; reset to 0 on empty result set.';

-- ===== signal_waits.claimed_generation =====
-- Fencing token: when a peer steals an orphan signal it stamps the current
-- instance_lease.generation. Resume validates equality against the live
-- generation; mismatch → STALE_OWNERSHIP rejection + reset claim to NULL.

ALTER TABLE orchestrator.workflow_signal_waits
    ADD COLUMN IF NOT EXISTS claimed_by VARCHAR(128);

ALTER TABLE orchestrator.workflow_signal_waits
    ADD COLUMN IF NOT EXISTS claimed_generation BIGINT NOT NULL DEFAULT 0;

ALTER TABLE orchestrator.workflow_signal_waits
    ADD COLUMN IF NOT EXISTS retry_after TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_signal_waits_claimed_by
    ON orchestrator.workflow_signal_waits (claimed_by, claimed_generation);

COMMENT ON COLUMN orchestrator.workflow_signal_waits.claimed_by IS
    'Instance_id of the orchestrator replica currently holding this signal. '
    'NULL = unclaimed. Set by atomic CTE steal in heartbeat failover. '
    'See plan v3 #10.';
COMMENT ON COLUMN orchestrator.workflow_signal_waits.claimed_generation IS
    'Generation of instance_lease at claim time. Resume rejects if the '
    'live generation has advanced (instance restarted), preventing '
    'double-resume after a Redis-flap false-positive failover.';
COMMENT ON COLUMN orchestrator.workflow_signal_waits.retry_after IS
    'When a STALE_OWNERSHIP rejection resets claim to NULL, the row is '
    'set retry_after = NOW() + 5s so the rejecting instance does not '
    'immediately re-pick the same row in an infinite loop. The CTE steal '
    'filters on COALESCE(retry_after, ''1970-01-01'') <= NOW().';
