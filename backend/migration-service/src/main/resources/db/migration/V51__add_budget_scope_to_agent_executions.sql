-- Add budget_scope column to agent_executions for observability of budget denials.
-- Populated by AgentObservabilityService when stop_reason = 'BUDGET_EXHAUSTED', sourced
-- from the run metrics map (key: budgetScope) set by AgentLoopService.evaluateGuard.
-- Values: 'tenant' (tenant balance ran out) | 'agent' (per-agent quota exhausted) | NULL.
-- Used by the StopReasonBadge frontend component to disambiguate denial cause.
ALTER TABLE agent.agent_executions ADD COLUMN IF NOT EXISTS budget_scope VARCHAR(10);
