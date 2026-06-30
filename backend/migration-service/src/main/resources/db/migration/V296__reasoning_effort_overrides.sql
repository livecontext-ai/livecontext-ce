-- V296 - Per-agent + per-model reasoning-effort overrides for CLI/bridge providers
-- (claude-code, codex). The bridge adapters map the chosen level to each CLI's own
-- flag (e.g. Codex `-c model_reasoning_effort="high"`); unsupported/unknown levels
-- are dropped at the adapter so the CLI falls back to its own default.
--
-- Precedence at dispatch time (resolved by ReasoningEffortResolver, most specific first):
--   per-conversation override (chat selector)
--     > agents.reasoning_effort (per-agent setting)
--     > model_config_overrides.default_reasoning_effort (per-model admin default)
--     > CLI's own default.
--
-- Both columns are nullable; NULL = "inherit / no override". Accepted values:
-- minimal | low | medium | high | xhigh (validated in the service layer via
-- ReasoningEffort.fromString; the DB stores the canonical lowercase wire form).
-- VARCHAR(16) comfortably fits the longest level. No index: these are read by
-- agent id / (provider, model_id) lookup at dispatch time, never used as a filter
-- predicate. IF NOT EXISTS keeps the migration idempotent (matches V125 style).

-- Schema-qualified: the per-migration default search_path is `orchestrator, public`
-- (see beforeEachMigrate.sql), but both tables live in the `agent` schema
-- (cf. V100 `agent.agents`, V109 `SET search_path TO agent`).
ALTER TABLE agent.agents
    ADD COLUMN IF NOT EXISTS reasoning_effort VARCHAR(16);

ALTER TABLE agent.model_config_overrides
    ADD COLUMN IF NOT EXISTS default_reasoning_effort VARCHAR(16);
