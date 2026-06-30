-- V171: Notification bell read-state - minimalist MVP (option A).
--
-- Single-source notification system:
--   • Source: failed runs of pinned workflows (workflow_runs WHERE status='FAILED'
--     AND workflow.pinned_version=run.plan_version AND not editor/showcase/agent run)
--   • Read state: per-user single timestamp (last time user opened the bell)
--   • No materialised notifications table - events queried on-demand from
--     workflow_runs (zero write amplification per design v3 audit)
--
-- Future expansion sources: trigger.quota (V169 audit_log), cred.expired,
-- production_run.unarmed, dispatch.refused - all deferred to V172+.
--
-- Per CLAUDE.md "Each service queries its own schema": the read-state table
-- lives in `orchestrator` schema (alongside workflow_runs which is the only
-- source for MVP). Future cross-source scope may move it to a dedicated
-- service.

CREATE TABLE IF NOT EXISTS orchestrator.notification_read_state (
    user_id       VARCHAR(255) PRIMARY KEY,
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT '1970-01-01'::TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE  orchestrator.notification_read_state IS
    'Bell-read cursor per user - single timestamp, MVP scope. Updated on bell open.';
COMMENT ON COLUMN orchestrator.notification_read_state.last_seen_at IS
    'Events with ended_at > this are unread. Default 1970 = show all on first visit.';

-- Partial index over the bell-query hot path: FAILED runs by tenant, ordered.
-- Most workflow_runs rows are PENDING/RUNNING/COMPLETED; partial index keeps
-- it small (~1-5% of total) and the query selectivity matches.
CREATE INDEX IF NOT EXISTS idx_workflow_runs_tenant_failed_ended
    ON orchestrator.workflow_runs (tenant_id, ended_at DESC)
    WHERE status = 'FAILED';
