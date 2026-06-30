SET search_path TO agent;

-- 1. Track how many review attempts a task has undergone
ALTER TABLE agent_tasks
    ADD COLUMN review_attempt_count INTEGER NOT NULL DEFAULT 0;

-- 2. Link a task to the reviewer execution that is currently processing it (nullable)
ALTER TABLE agent_tasks
    ADD COLUMN reviewer_execution_id UUID;

-- 3. Unique agent name per tenant, but only among active (non-soft-deleted) agents
CREATE UNIQUE INDEX uq_agents_tenant_name_active
    ON agents (tenant_id, name)
    WHERE is_active = true;

-- 4. Index for CAS queries that look up tasks by reviewer_execution_id
CREATE INDEX idx_agent_tasks_reviewer_execution_id
    ON agent_tasks (reviewer_execution_id);
