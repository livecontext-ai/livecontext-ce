-- Stage 5.2b - per-agent override for the COLD summariser model.
--
-- Rationale: the summariser is a cost-sensitive choice. Some tenants want
-- everything on Haiku to cap spend; others want Sonnet on production
-- workflows for better summary fidelity. Rather than picking one global
-- default, we mirror the existing "limits" advanced-settings pattern and
-- let each agent override the summariser model independently of its
-- primary model.
--
-- Columns are nullable by design. NULL semantics (resolved in
-- AgentCompactionModelResolver):
--   1. Both columns non-null  → use them verbatim.
--   2. Either column null     → fall back to agent.model_provider /
--                               agent.model_name (primary model).
--   3. Primary also null      → fall back to AgentDefaultsConfig.compactionModel
--                               (YAML default, e.g. anthropic/claude-haiku-4-5).
--
-- No backfill: existing rows stay NULL and inherit the primary model.
-- Frontend ChatConfigPanel persists NULL when the user picks "Auto (same
-- as agent model)".
ALTER TABLE agent.agents
  ADD COLUMN compaction_model_provider VARCHAR(50)  NULL,
  ADD COLUMN compaction_model_name     VARCHAR(100) NULL;

COMMENT ON COLUMN agent.agents.compaction_model_provider IS
  'Override for cold-summariser model provider. NULL ⇒ inherit from agent.model_provider.';

COMMENT ON COLUMN agent.agents.compaction_model_name IS
  'Override for cold-summariser model name. NULL ⇒ inherit from agent.model_name.';
