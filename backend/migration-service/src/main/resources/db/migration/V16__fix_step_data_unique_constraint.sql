SET search_path TO orchestrator;

-- Fix workflow_step_data unique constraint: add trigger_id (required by v6 ON CONFLICT clause)
-- V15 only added duration_ms to workflow_epochs; this completes the constraint fix.

-- Backfill any null trigger_ids
UPDATE workflow_step_data SET trigger_id = 'trigger:default' WHERE trigger_id IS NULL;
ALTER TABLE workflow_step_data ALTER COLUMN trigger_id SET NOT NULL;
ALTER TABLE workflow_step_data ALTER COLUMN trigger_id SET DEFAULT 'trigger:default';

-- Drop old constraint (without trigger_id) and create new one (with trigger_id)
ALTER TABLE workflow_step_data DROP CONSTRAINT IF EXISTS idx_workflow_step_data_unique_v5;

-- Also drop v6 if it somehow already exists (idempotent)
ALTER TABLE workflow_step_data DROP CONSTRAINT IF EXISTS idx_workflow_step_data_unique_v6;

ALTER TABLE workflow_step_data ADD CONSTRAINT idx_workflow_step_data_unique_v6
    UNIQUE (workflow_run_id, step_alias, trigger_id, iteration, item_index, epoch, spawn, status);
