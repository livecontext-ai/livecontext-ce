-- Add credits_consumed column to agent_executions for per-execution credit tracking
ALTER TABLE agent.agent_executions
    ADD COLUMN IF NOT EXISTS credits_consumed NUMERIC(15,4) NOT NULL DEFAULT 0;

COMMENT ON COLUMN agent.agent_executions.credits_consumed IS 'Credits consumed by this specific execution (from auth-service credit consumption)';
