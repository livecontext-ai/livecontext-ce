-- V99: Per-agent guard overrides (nullable Integer columns)
-- SUPERSEDED BY V100 - the three per-resource columns below (max_agents_per_turn,
-- max_skills_per_turn, sub_agent_max_per_turn) are DROPPED in V100 and replaced by
-- a single unified max_per_resource_per_turn column applied uniformly across
-- agent / skill / sub_agent / interface / workflow / table creates. The loop_*
-- columns stay as-is. Historical shape below for reference only - a fresh Flyway
-- run will create these columns here and drop them one migration later.
--
-- Historical resolution order (V99, now superseded by V100):
--   1) AgentEntity.<column> when non-null and > 0
--   2) AgentDefaultsConfig.<yaml-value>
--
-- See V100 for the current unified-cap resolution order.

ALTER TABLE agent.agents
    ADD COLUMN IF NOT EXISTS max_agents_per_turn       INTEGER,
    ADD COLUMN IF NOT EXISTS max_skills_per_turn       INTEGER,
    ADD COLUMN IF NOT EXISTS sub_agent_max_per_turn    INTEGER,
    ADD COLUMN IF NOT EXISTS loop_identical_stop       INTEGER,
    ADD COLUMN IF NOT EXISTS loop_consecutive_stop     INTEGER;

-- Defensive CHECK constraints: overrides are always positive when set.
-- NULL is allowed (= "use YAML default").
ALTER TABLE agent.agents
    ADD CONSTRAINT chk_agent_max_agents_per_turn_positive
        CHECK (max_agents_per_turn IS NULL OR max_agents_per_turn > 0),
    ADD CONSTRAINT chk_agent_max_skills_per_turn_positive
        CHECK (max_skills_per_turn IS NULL OR max_skills_per_turn > 0),
    ADD CONSTRAINT chk_agent_sub_agent_max_per_turn_positive
        CHECK (sub_agent_max_per_turn IS NULL OR sub_agent_max_per_turn > 0),
    ADD CONSTRAINT chk_agent_loop_identical_stop_valid
        CHECK (loop_identical_stop IS NULL OR loop_identical_stop >= 2),
    ADD CONSTRAINT chk_agent_loop_consecutive_stop_valid
        CHECK (loop_consecutive_stop IS NULL OR loop_consecutive_stop >= 4);

COMMENT ON COLUMN agent.agents.max_agents_per_turn IS
    'Per-agent cap on agent(action=create) calls per turn. NULL = fall back to ai.agent.defaults.max-agents-per-turn (5).';
COMMENT ON COLUMN agent.agents.max_skills_per_turn IS
    'Per-agent cap on skill(action=create) calls per turn. NULL = fall back to ai.agent.defaults.max-skills-per-turn (10).';
COMMENT ON COLUMN agent.agents.sub_agent_max_per_turn IS
    'Per-agent cap on agent(action=execute) sub-agent invocations per turn. NULL = fall back to ai.agent.defaults.sub-agent-max-per-turn (3).';
COMMENT ON COLUMN agent.agents.loop_identical_stop IS
    'Per-agent LoopDetector identical-call hard-stop threshold. NULL = fall back to ai.agent.defaults.loop-identical-stop (15).';
COMMENT ON COLUMN agent.agents.loop_consecutive_stop IS
    'Per-agent LoopDetector consecutive-call hard-stop threshold. NULL = fall back to ai.agent.defaults.loop-consecutive-stop (40).';
