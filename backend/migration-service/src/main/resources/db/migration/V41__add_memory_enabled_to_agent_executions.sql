-- Add memory_enabled column to agent_executions for observability tracking.
-- Tracks whether the agent had access to its conversation history during execution.
-- Applies to all agent types: sub-agent, workflow, webhook, schedule, widget, chat.
ALTER TABLE agent.agent_executions ADD COLUMN IF NOT EXISTS memory_enabled BOOLEAN;
