-- V72: Add task_id column to agent_executions for task↔execution linkage.
-- Nullable - only populated when an execution is associated with a task.
ALTER TABLE agent.agent_executions ADD COLUMN IF NOT EXISTS task_id UUID;

-- Index for querying executions by task
CREATE INDEX IF NOT EXISTS idx_agent_executions_task_id
    ON agent.agent_executions (task_id) WHERE task_id IS NOT NULL;
