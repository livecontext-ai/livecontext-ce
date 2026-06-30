-- V138: production_run_id FK on workflows + bootstrap rearm for existing pinned workflows
-- (PR3 of the unified trigger redesign)
--
-- Adds the canonical "run-of-record" pointer that PR4+ dispatch uses to find the right
-- production run in O(1) instead of querying by (workflow_id, plan_version) at every tick.
-- The bootstrap pass at the end re-arms existing pinned workflows that were paused by
-- pre-PR1 dispatcher auto-disable, so the prod Gmail Auto-Labeler revives at first deploy.
--
-- Design ref: the project docs (round 7, audit 9.22/10)
--
-- Round-7 audit consensus: pin_armed_at column NOT added (zero readers; pin time captured
-- in metric + Loki log). Tracking stays in workflows.updated_at and the Loki log line.

-- =============================================================================
-- 1. Add production_run_id FK
-- =============================================================================
ALTER TABLE orchestrator.workflows
    ADD COLUMN IF NOT EXISTS production_run_id UUID NULL
        REFERENCES orchestrator.workflow_runs(id) ON DELETE SET NULL;

-- Speed up the dispatch hot path lookup `WHERE production_run_id = ?`.
CREATE INDEX IF NOT EXISTS idx_workflows_production_run_id
    ON orchestrator.workflows (production_run_id)
 WHERE production_run_id IS NOT NULL;

-- =============================================================================
-- 2. Partial unique index - at most one WAITING_TRIGGER PRODUCTION run per
--    (workflow_id, plan_version). PR4 PinTransaction.rearm relies on this to
--    catch DataIntegrityViolationException and re-SELECT the existing row.
--
--    Rationale: today the run-role discriminator lives in metadata.__editorRun__.
--    PR4 will introduce a workflow_runs.run_role enum column (separate migration),
--    but for PR3 we use the existing JSON metadata flag so the unique index keys on
--    `(metadata->>'__editorRun__') IS DISTINCT FROM 'true'` to exclude editor runs.
-- =============================================================================
CREATE UNIQUE INDEX IF NOT EXISTS idx_runs_one_production_waiting
    ON orchestrator.workflow_runs (workflow_id, plan_version)
 WHERE status = 'WAITING_TRIGGER'
   AND ((metadata->>'__editorRun__') IS NULL
        OR (metadata->>'__editorRun__') <> 'true');

-- =============================================================================
-- 3. BOOTSTRAP - backfill production_run_id for every pinned workflow
--    that has a TRUSTED run at the pinned version. Mirrors WorkflowPinService
--    pin-time validation: {COMPLETED, WAITING_TRIGGER, RUNNING, PAUSED}.
--    The TRUSTED set excludes CANCELLED/TIMEOUT/FAILED - same root-cause filter
--    as PR1's RunSelectionPolicy.LATEST_TRUSTED.
-- =============================================================================
WITH best_run AS (
  SELECT DISTINCT ON (r.workflow_id)
         r.workflow_id, r.id AS run_id
    FROM orchestrator.workflow_runs r
    JOIN orchestrator.workflows w ON w.id = r.workflow_id
   WHERE w.pinned_version IS NOT NULL
     AND r.plan_version = w.pinned_version
     AND r.status IN ('WAITING_TRIGGER', 'RUNNING', 'PAUSED', 'COMPLETED')
     AND ((r.metadata->>'__editorRun__') IS NULL
          OR (r.metadata->>'__editorRun__') <> 'true')
   ORDER BY r.workflow_id, r.started_at DESC
)
UPDATE orchestrator.workflows w
   SET production_run_id = br.run_id,
       updated_at        = now()
  FROM best_run br
 WHERE w.id                = br.workflow_id
   AND w.production_run_id IS NULL;

-- =============================================================================
-- 4. BOOTSTRAP - rearm trigger config rows whose workflow now has a
--    production_run_id. Clears stale state from the pre-PR1 era where dispatchers
--    auto-disabled triggers on missing-run / terminal-run conditions.
--    Round-7 contract: dispatch never mutates state - but rows disabled BEFORE
--    PR1 shipped need to be cleaned up here.
-- =============================================================================
DO $$
DECLARE t TEXT;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'scheduled_executions',
    'standalone_webhooks',
    'standalone_chat_endpoints',
    'standalone_form_endpoints'
  ] LOOP
    EXECUTE format($f$
      UPDATE "trigger".%I s
         SET state                = 'ACTIVE',
             last_disabled_reason = NULL,
             last_disabled_at     = NULL
       WHERE s.workflow_id IN (
         SELECT id FROM orchestrator.workflows
          WHERE production_run_id IS NOT NULL
       )
         AND s.state = 'ARCHIVED'
         AND s.last_disabled_reason = 'LEGACY_DISABLED'
    $f$, t);
  END LOOP;
END $$;

-- For schedule + standalone_webhooks + standalone_chat_endpoints + standalone_form_endpoints
-- also flip the legacy boolean back to TRUE so PR2-PR4 dispatch paths that still read
-- the legacy column see the rearmed state. PR5 drops the legacy columns.
UPDATE "trigger".scheduled_executions s
   SET enabled  = TRUE,
       is_active = TRUE
 WHERE s.state                = 'ACTIVE'
   AND s.last_disabled_at IS NULL
   AND (s.enabled = FALSE OR s.is_active = FALSE)
   AND s.workflow_id IN (
     SELECT id FROM orchestrator.workflows WHERE production_run_id IS NOT NULL
   );

UPDATE "trigger".standalone_webhooks s
   SET is_active = TRUE
 WHERE s.state                = 'ACTIVE'
   AND s.last_disabled_at IS NULL
   AND s.is_active            = FALSE
   AND s.workflow_id IN (
     SELECT id FROM orchestrator.workflows WHERE production_run_id IS NOT NULL
   );

UPDATE "trigger".standalone_chat_endpoints s
   SET is_active = TRUE
 WHERE s.state                = 'ACTIVE'
   AND s.last_disabled_at IS NULL
   AND s.is_active            = FALSE
   AND s.workflow_id IN (
     SELECT id FROM orchestrator.workflows WHERE production_run_id IS NOT NULL
   );

UPDATE "trigger".standalone_form_endpoints s
   SET is_active = TRUE
 WHERE s.state                = 'ACTIVE'
   AND s.last_disabled_at IS NULL
   AND s.is_active            = FALSE
   AND s.workflow_id IN (
     SELECT id FROM orchestrator.workflows WHERE production_run_id IS NOT NULL
   );

-- =============================================================================
-- 5. BOOTSTRAP - paranoid guard. Workflows that ARE pinned but have NO trusted run
--    at the pinned version end up with production_run_id IS NULL after step 3.
--    Mark their triggers SUSPENDED_NO_RUN with a clear reason so the admin UI
--    surfaces an actionable signal instead of silent paused state.
-- =============================================================================
DO $$
DECLARE t TEXT;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'scheduled_executions',
    'standalone_webhooks',
    'standalone_chat_endpoints',
    'standalone_form_endpoints'
  ] LOOP
    EXECUTE format($f$
      UPDATE "trigger".%I s
         SET state                = 'SUSPENDED_NO_RUN',
             last_disabled_reason = 'BOOTSTRAP_NO_TRUSTED_RUN',
             last_disabled_at     = now()
       WHERE s.workflow_id IN (
         SELECT id FROM orchestrator.workflows
          WHERE pinned_version IS NOT NULL
            AND production_run_id IS NULL
       )
         AND s.state = 'ACTIVE'
    $f$, t);
  END LOOP;
END $$;
