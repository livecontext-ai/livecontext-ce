-- Add 'in_review' to the allowed status values for agent_tasks.
-- When a reviewer is assigned, completed/failed tasks go to 'in_review' first.
ALTER TABLE agent.agent_tasks
    DROP CONSTRAINT IF EXISTS agent_tasks_status_check;

ALTER TABLE agent.agent_tasks
    ADD CONSTRAINT agent_tasks_status_check
        CHECK (status IN ('pending','in_progress','in_review','completed','failed','cancelled'));
