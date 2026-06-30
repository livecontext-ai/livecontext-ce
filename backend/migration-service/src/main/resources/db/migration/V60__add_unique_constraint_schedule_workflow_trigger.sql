-- ============================================================================
-- V60: Add unique constraint on (workflow_id, trigger_id) for scheduled_executions
--
-- Root cause: no DB-level uniqueness existed, so concurrent save+run or
-- standalone-link could insert duplicate rows for the same workflow+trigger.
-- The findByWorkflowIdAndTriggerId query then crashed with NonUniqueResultException.
-- ============================================================================

-- 1. Delete duplicate rows, keeping the one with the most executions (or newest)
DELETE FROM "trigger".scheduled_executions
WHERE id NOT IN (
    SELECT DISTINCT ON (workflow_id, trigger_id) id
    FROM "trigger".scheduled_executions
    WHERE workflow_id IS NOT NULL AND trigger_id IS NOT NULL
    ORDER BY workflow_id, trigger_id, execution_count DESC, created_at DESC
)
AND workflow_id IS NOT NULL
AND trigger_id IS NOT NULL;

-- 2. Add the unique constraint
ALTER TABLE "trigger".scheduled_executions
    ADD CONSTRAINT uq_schedule_workflow_trigger UNIQUE (workflow_id, trigger_id);
