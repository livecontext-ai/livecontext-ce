-- Per-agent override for context compaction (COLD-summary) enablement + cadence.
--
-- Rationale: compaction (the COLD-summary pass that shrinks long conversations)
-- is globally opt-in via conversation.compaction.enabled (default false) with a
-- fixed cadence (conversation.compaction.cadenceTurns, default 5). Users asked to
-- choose, per agent / per conversation / per workspace-default, whether compaction
-- runs AND after how many turns. This mirrors the existing per-agent "limits"
-- advanced-settings pattern (max_per_resource_per_turn, loop_identical_stop, ...).
--
-- Columns are nullable by design. NULL semantics (resolved in CompactionConfigResolver):
--   compaction_enabled:
--     TRUE/FALSE → use verbatim (per-agent override of the global master switch).
--     NULL       → inherit the next tier (conversation override, then YAML default).
--   compaction_after_turns:
--     >= 1 → minimum new turns between summary regenerations for this agent.
--     NULL → inherit (conversation override, then conversation.compaction.cadenceTurns).
--
-- No backfill: existing rows stay NULL and inherit the global behaviour unchanged.
-- The per-conversation override lives in conversation.chat_config JSONB (no DDL here);
-- the per-(user, workspace) default lives in the chat-defaults store (no DDL here).
ALTER TABLE agent.agents
  ADD COLUMN compaction_enabled     BOOLEAN NULL,
  ADD COLUMN compaction_after_turns INTEGER NULL
    CONSTRAINT chk_agent_compaction_after_turns CHECK (compaction_after_turns IS NULL OR compaction_after_turns >= 1);

COMMENT ON COLUMN agent.agents.compaction_enabled IS
  'Per-agent compaction master switch. NULL ⇒ inherit (conversation override, then conversation.compaction.enabled YAML default).';

COMMENT ON COLUMN agent.agents.compaction_after_turns IS
  'Per-agent compaction cadence: minimum new turns between COLD-summary regenerations. NULL ⇒ inherit (conversation override, then conversation.compaction.cadenceTurns).';
