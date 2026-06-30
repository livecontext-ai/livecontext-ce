-- Add memory_enabled flag to agent webhook tokens
-- When true, webhook conversations load previous conversation history
ALTER TABLE agent.agent_webhook_tokens
    ADD COLUMN memory_enabled BOOLEAN NOT NULL DEFAULT false;
