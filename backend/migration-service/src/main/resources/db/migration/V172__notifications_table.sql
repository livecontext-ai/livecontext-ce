-- V172: Materialise the notification event log.
--
-- V171 shipped an on-demand bell that scanned `workflow_runs` directly. Audit found:
--   • read cost O(users × failed_runs_in_window × jsonb_extractions) per poll
--   • `(metadata->>'__versionReplay__')::boolean` cast bug - `__versionReplay__` is
--     stored as INTEGER planVersion (EditorRunResolver), not boolean → query throws
--     `invalid input syntax for type boolean: "5"` on every replay run
--   • OOM risk via PgResultSet over-fetch (recent prod incidents 2026-05-06/07)
--
-- V172 pivots to push-on-event:
--   • NotificationEmitter (orchestrator) writes one row per terminal failed pinned
--     run, idempotent on (tenant_id, category, source_id)
--   • Bell read becomes a single indexed range scan, no JSONB filtering, no JOIN
--     to workflows in the WHERE clause
--   • V171's `notification_read_state` table is kept untouched (cursor model
--     still applies for unread/read computation in the rewritten service)
--
-- Migration order matters: backfill BEFORE dropping the V171 partial index so the
-- LATERAL backfill can use the index. The index becomes orphan write-amp once the
-- on-demand query is retired and is dropped at the end.

CREATE TABLE IF NOT EXISTS orchestrator.notifications (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     VARCHAR(255) NOT NULL,
    category      VARCHAR(40)  NOT NULL,
    severity      VARCHAR(16)  NOT NULL,
    subject_type  VARCHAR(20)  NOT NULL,
    subject_id    UUID         NOT NULL,
    source_id     VARCHAR(255) NOT NULL,
    run_id        UUID,
    run_id_public VARCHAR(255),
    plan_version  INTEGER,
    payload       JSONB        NOT NULL,
    occurred_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_notifications_dedup
        UNIQUE (tenant_id, category, source_id),
    CONSTRAINT chk_notif_severity_v1
        CHECK (severity IN ('error', 'warning', 'info')),
    CONSTRAINT chk_notif_subject_type_v1
        CHECK (subject_type IN ('WORKFLOW')),
    CONSTRAINT chk_notif_payload_v1
        CHECK ((payload ? 'status') AND (payload ? 'endedAt'))
);

COMMENT ON TABLE  orchestrator.notifications IS
    'Bell event log - one row per failed pinned-run. V172 scope: WORKFLOW subject only. '
    'Future: APPLICATION/AGENT (extend chk_notif_subject_type_v1).';
COMMENT ON COLUMN orchestrator.notifications.category IS
    'Coarse-grained event family. V1: ''RUN_FAILED''. Future: ''USER_APPROVAL'', ''CRED_EXPIRED''.';
COMMENT ON COLUMN orchestrator.notifications.subject_type IS
    'Aggregation grain - bell shows one row per (subject_type, subject_id, category).';
COMMENT ON COLUMN orchestrator.notifications.source_id IS
    'Idempotency key for INSERT ON CONFLICT. V1: run_id::text. V2 reusable per-epoch: run_id || '':'' || epoch.';
COMMENT ON COLUMN orchestrator.notifications.payload IS
    'Required keys: status (string), endedAt (ISO-8601 UTC). Optional: errorMessage, failedNodeId, runIdPublic.';

CREATE INDEX IF NOT EXISTS idx_notifications_tenant_occurred
    ON orchestrator.notifications (tenant_id, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- Backfill: best-effort historical seed so the bell is not empty on day 1.
-- Per-tenant LATERAL with bounded LIMIT 50 prevents one noisy tenant from
-- starving the rest. ON CONFLICT DO NOTHING is defensive (table is fresh).
--
-- Critical: this runs BEFORE `DROP INDEX idx_workflow_runs_tenant_failed_ended`
-- below, so the partial index supports the LATERAL filter on `status='FAILED'`.
-- The CANCELLED/TIMEOUT branches scan without that index - acceptable for a
-- 30d window; in steady state the emitter writes forward and backfill is a
-- one-shot cost.
--
-- TZ note: `to_char(... AT TIME ZONE 'UTC', ...)` is required because the
-- Flyway connection's session timezone is not pinned; without `AT TIME ZONE`
-- the emitted "Z" suffix would lie about the offset.
-- ---------------------------------------------------------------------------
INSERT INTO orchestrator.notifications (
    tenant_id, category, severity, subject_type, subject_id, source_id,
    run_id, run_id_public, plan_version, payload, occurred_at)
SELECT t.tenant_id,
       'RUN_FAILED',
       'error',
       'WORKFLOW',
       sub.workflow_id,
       sub.id::text,
       sub.id,
       sub.run_id_public,
       sub.plan_version,
       jsonb_build_object(
           -- lower(): match the live-emitter wire shape produced by
           -- RunStatus.toWireValue() (lowercase: "failed"/"cancelled"/"timeout").
           -- Without this the backfilled rows would diverge from forward-emitted
           -- rows on `payload.status` casing.
           'status',      lower(sub.status::text),
           'endedAt',     to_char(sub.ended_at AT TIME ZONE 'UTC',
                                  'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
           'runIdPublic', sub.run_id_public),
       sub.ended_at
FROM (SELECT DISTINCT tenant_id
        FROM orchestrator.workflows
       WHERE pinned_version IS NOT NULL) t,
LATERAL (
    SELECT r.id, r.workflow_id, r.run_id_public, r.plan_version,
           r.status, r.ended_at
      FROM orchestrator.workflow_runs r
      JOIN orchestrator.workflows w ON w.id = r.workflow_id
     WHERE r.tenant_id = t.tenant_id
       AND r.status IN ('FAILED', 'CANCELLED', 'TIMEOUT')
       AND r.ended_at IS NOT NULL
       AND r.ended_at > now() - INTERVAL '30 days'
       AND w.pinned_version IS NOT NULL
       AND w.pinned_version = r.plan_version
       AND r.run_id_public NOT LIKE 'showcase_%'
       AND COALESCE((r.metadata->>'__editorRun__')::boolean, false) = false
       AND NOT (r.metadata ? '__versionReplay__')
       AND COALESCE((r.metadata->>'__agentInitiated__')::boolean, false) = false
     ORDER BY r.ended_at DESC
     LIMIT 50
) sub
ON CONFLICT (tenant_id, category, source_id) DO NOTHING;

-- The V171 partial index existed solely to accelerate the on-demand bell
-- query that V172 retires. Without consumers it becomes pure write-amp on
-- every workflow_runs lifecycle write - drop it.
DROP INDEX IF EXISTS orchestrator.idx_workflow_runs_tenant_failed_ended;
