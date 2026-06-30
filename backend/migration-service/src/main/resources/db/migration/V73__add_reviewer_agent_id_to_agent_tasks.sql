-- Add reviewer_agent_id column to agent_tasks (FK with SET NULL on delete)
ALTER TABLE agent.agent_tasks ADD COLUMN reviewer_agent_id UUID
    REFERENCES agent.agents(id) ON DELETE SET NULL;
