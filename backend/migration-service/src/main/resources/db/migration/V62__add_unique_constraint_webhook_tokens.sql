-- ============================================================================
-- V62: Add unique constraint on (workflow_id, trigger_id) for webhook_tokens
--
-- Same pattern as V60 for scheduled_executions.
-- webhook_tokens.findByWorkflowIdAndTriggerId returns Optional but had no
-- DB-level uniqueness, so concurrent ensureTokenForTrigger calls or
-- duplicate saves could insert multiple rows for the same workflow+trigger.
-- ============================================================================

-- 1. Delete duplicate rows, keeping the one with the latest updated_at
DELETE FROM "trigger".webhook_tokens
WHERE id NOT IN (
    SELECT DISTINCT ON (workflow_id, trigger_id) id
    FROM "trigger".webhook_tokens
    ORDER BY workflow_id, trigger_id, updated_at DESC, id DESC
);

-- 2. Add the unique constraint
ALTER TABLE "trigger".webhook_tokens
    ADD CONSTRAINT uq_webhook_token_workflow_trigger UNIQUE (workflow_id, trigger_id);
