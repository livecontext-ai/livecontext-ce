SET search_path TO orchestrator;

-- 1. Add missing duration_ms column to workflow_epochs table
-- Required by WorkflowEpochRepository.CLOSE_HEADER_SQL for epoch duration tracking
ALTER TABLE workflow_epochs ADD COLUMN IF NOT EXISTS duration_ms BIGINT;

-- 2. Fix workflow_step_data unique constraint: add trigger_id (required by v6 ON CONFLICT clause)
-- Drop the old v5 constraint (without trigger_id) and create v6 (with trigger_id)
-- First ensure trigger_id is NOT NULL (backfill any nulls)
UPDATE workflow_step_data SET trigger_id = 'trigger:default' WHERE trigger_id IS NULL;
ALTER TABLE workflow_step_data ALTER COLUMN trigger_id SET NOT NULL;
ALTER TABLE workflow_step_data ALTER COLUMN trigger_id SET DEFAULT 'trigger:default';

-- Drop old constraint if it exists
ALTER TABLE workflow_step_data DROP CONSTRAINT IF EXISTS idx_workflow_step_data_unique_v5;

-- Create new constraint with trigger_id included
ALTER TABLE workflow_step_data ADD CONSTRAINT idx_workflow_step_data_unique_v6
    UNIQUE (workflow_run_id, step_alias, trigger_id, iteration, item_index, epoch, spawn, status);
