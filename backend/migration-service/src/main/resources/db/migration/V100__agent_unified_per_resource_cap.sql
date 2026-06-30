-- V100: Unify the per-resource per-turn caps into a single generic column.
-- Clean break - no backward compatibility. Previous V99 per-resource columns are dropped.
--
-- Replaces the 3 per-resource caps (max_agents_per_turn / max_skills_per_turn /
-- sub_agent_max_per_turn) with one uniform cap applied per resource type at runtime.
-- Loop detector columns (loop_identical_stop / loop_consecutive_stop) stay as-is.
--
-- Resolution order at runtime (per resource type):
--   1) AgentEntity.max_per_resource_per_turn when non-null and > 0
--   2) AgentDefaultsConfig.max-per-resource-per-turn (YAML default 5)
--
-- Tracked resource types (counted separately, each capped by the same value):
--   agent / skill / sub_agent / interface / workflow / table

-- Drop the old per-resource constraints and columns.
ALTER TABLE agent.agents
    DROP CONSTRAINT IF EXISTS chk_agent_max_agents_per_turn_positive,
    DROP CONSTRAINT IF EXISTS chk_agent_max_skills_per_turn_positive,
    DROP CONSTRAINT IF EXISTS chk_agent_sub_agent_max_per_turn_positive;

ALTER TABLE agent.agents
    DROP COLUMN IF EXISTS max_agents_per_turn,
    DROP COLUMN IF EXISTS max_skills_per_turn,
    DROP COLUMN IF EXISTS sub_agent_max_per_turn;

-- Add the unified column.
ALTER TABLE agent.agents
    ADD COLUMN IF NOT EXISTS max_per_resource_per_turn INTEGER;

ALTER TABLE agent.agents
    ADD CONSTRAINT chk_agent_max_per_resource_per_turn_positive
        CHECK (max_per_resource_per_turn IS NULL OR max_per_resource_per_turn > 0);

COMMENT ON COLUMN agent.agents.max_per_resource_per_turn IS
    'Per-agent uniform cap on resource-creation calls per turn. Applied separately to each tracked resource type (agent/skill/sub_agent/interface/workflow/table). NULL = fall back to ai.agent.defaults.max-per-resource-per-turn (5).';
