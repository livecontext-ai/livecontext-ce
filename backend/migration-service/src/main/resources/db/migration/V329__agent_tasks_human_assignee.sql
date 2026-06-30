-- Jira-style human assignee / reviewer for the agent task board.
--
-- A task's assignee is an agent (assigned_to_agent_id) XOR a human
-- (assigned_to_user_id); the same exclusivity holds for the reviewer
-- (reviewer_agent_id XOR reviewer_user_id). A HUMAN assignee never triggers
-- auto-execution: the centralized worker kickoff only fires when
-- assigned_to_agent_id is non-null, so a row assigned to a person stays put
-- in its column until the person moves it (no shimmer, no in_progress).
--
-- No FK: user ids live in the auth schema and cross-schema FKs are forbidden,
-- mirroring the existing created_by_user_id TEXT column on this table.
ALTER TABLE agent.agent_tasks ADD COLUMN IF NOT EXISTS assigned_to_user_id TEXT;
ALTER TABLE agent.agent_tasks ADD COLUMN IF NOT EXISTS reviewer_user_id TEXT;
