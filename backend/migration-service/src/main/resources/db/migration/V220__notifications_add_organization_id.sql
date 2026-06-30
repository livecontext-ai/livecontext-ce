-- V220 - notifications.organization_id for bell org-scope routing.
--
-- Context: V172 created `orchestrator.notifications` keyed on `tenant_id`
-- only - at the time the V1 invariant `tenant_id == userId == X-User-ID`
-- meant org-mode fan-out wasn't required. PR15+ shipped `workflow_runs
-- .organization_id` (V209/V210/V218), and the frontend now always sends
-- `X-Active-Organization-ID` (gateway forwards as `X-Organization-ID`).
-- DashboardController.getHomeStatus deferred org fan-out with a comment
-- explicitly calling out the missing column.
--
-- Visible symptom: a teammate viewing the org workspace sees zero bell
-- rows for failed runs / pending approvals that happened in that org
-- because the bell read filtered `WHERE tenant_id = :userId` and the
-- emitter wrote the run-owner's user-id, not the workspace org-id.
--
-- This migration adds `organization_id` (nullable) + a partial index
-- matching the same access pattern as V215 (webhook_tokens) and V210
-- (agent_runtime). NULL means personal scope, per the V202/V210/V218
-- contract.
--
-- Backfill: propagate from `orchestrator.workflow_runs.organization_id`
-- via the row's `run_id` FK (V172 materialised emitter sets `run_id` for
-- RUN_FAILED + APPROVAL_PENDING rows; non-workflow rows like CRED_EXPIRED
-- have run_id IS NULL and stay personal-scope NULL - same semantics as
-- V218 cascade-from-parent pattern).
--
-- Idempotent: ADD COLUMN IF NOT EXISTS + CREATE INDEX IF NOT EXISTS +
-- WHERE organization_id IS NULL on the backfill. Safe to re-run.

ALTER TABLE orchestrator.notifications
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_notifications_organization_occurred
    ON orchestrator.notifications (organization_id, occurred_at DESC)
    WHERE organization_id IS NOT NULL;

-- Backfill: propagate the run's workspace tag onto the notification row.
-- Same join pattern V218 used to cascade agent_execution_* from
-- agent_executions, and V215's design intent for webhook_tokens.
UPDATE orchestrator.notifications n
   SET organization_id = r.organization_id
  FROM orchestrator.workflow_runs r
 WHERE n.run_id = r.id
   AND n.organization_id IS NULL
   AND r.organization_id IS NOT NULL;

COMMENT ON COLUMN orchestrator.notifications.organization_id IS
    'V220 - workspace tag for org-mode bell fan-out. NULL = personal scope (invariant: tenant_id = run owner user-id when org_id IS NULL). NotificationService filters strict in org scope (organization_id = :orgId) or strict-null in personal scope (organization_id IS NULL AND tenant_id = :tenantId).';
