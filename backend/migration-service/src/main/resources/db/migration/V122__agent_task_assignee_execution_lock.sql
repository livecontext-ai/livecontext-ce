-- Assignee execution CAS lock - prevents double-dispatch of the worker agent when
-- kickoff happens from multiple paths (REST POST /tasks, PATCH status=in_progress,
-- PATCH agentId=X, MCP agent(action='assign')). Mirrors reviewer_execution_id (V90).

SET search_path TO agent, public;

ALTER TABLE agent_tasks
    ADD COLUMN assignee_execution_id UUID;

CREATE INDEX idx_agent_tasks_assignee_execution_id
    ON agent_tasks (assignee_execution_id);
