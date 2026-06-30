-- ============================================================================
-- V59: Auto-pin workflows that have production triggers but no pinned_version
--
-- Context: as part of the strict pin enforcement refactor, production triggers
-- (schedule, webhook, public form, public chat, chained workflow) now ONLY fire
-- against the workflow's pinned version. Workflows that previously relied on the
-- "use latest run" fallback would silently stop firing after deployment.
--
-- Safety net: for every workflow with at least one production trigger in its
-- latest plan version and no pinned_version, pin it to the highest available
-- version. Datasource and error triggers are out of scope (they don't go
-- through ProductionRunResolver).
--
-- Idempotent: only updates rows where pinned_version IS NULL.
-- ============================================================================

SET search_path TO orchestrator;

-- Strategy: prefer the plan_version of the most recent non-CANCELLED, non-editor
-- run (what was actually deployed in production by the trigger system). Fall
-- back to the highest version in the plan_versions table only when no such
-- runs exist. Editor/builder simulate runs are EXCLUDED - they're marked
-- __editorRun__ in metadata and would otherwise pin drafts as production.
WITH last_run_version AS (
    SELECT DISTINCT ON (workflow_id)
        workflow_id,
        plan_version
    FROM workflow_runs
    WHERE plan_version IS NOT NULL
      AND status <> 'CANCELLED'
      AND COALESCE((metadata ->> '__editorRun__')::boolean, FALSE) = FALSE
    ORDER BY workflow_id, started_at DESC
),
fallback_max_version AS (
    SELECT workflow_id, MAX(version) AS version
    FROM workflow_plan_versions
    GROUP BY workflow_id
),
candidate AS (
    SELECT
        COALESCE(fmv.workflow_id, lrv.workflow_id) AS workflow_id,
        COALESCE(lrv.plan_version, fmv.version)    AS version
    FROM fallback_max_version fmv
    FULL OUTER JOIN last_run_version lrv ON lrv.workflow_id = fmv.workflow_id
),
production_workflows AS (
    SELECT c.workflow_id, c.version
    FROM candidate c
    JOIN workflow_plan_versions wpv
      ON wpv.workflow_id = c.workflow_id AND wpv.version = c.version
    WHERE EXISTS (
        SELECT 1
        FROM jsonb_array_elements(COALESCE(wpv.plan -> 'triggers', '[]'::jsonb)) AS t
        WHERE t ->> 'type' IN ('schedule', 'webhook', 'form', 'chat', 'workflow')
    )
)
UPDATE workflows w
SET pinned_version = pw.version
FROM production_workflows pw
WHERE w.id = pw.workflow_id
  AND w.pinned_version IS NULL;
