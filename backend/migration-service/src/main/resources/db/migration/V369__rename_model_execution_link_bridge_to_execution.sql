-- Model execution links: generalise the target from a CLI bridge to ANY execution
-- provider. A link can now route a billed model through a CLI bridge (claude-code,
-- codex, ...) OR a regular API provider (e.g. openrouter / a multi-provider gateway),
-- so the columns are renamed bridge_* -> execution_* to match the semantics. V365
-- created them as bridge_provider / bridge_model.
--
-- The UNIQUE constraint (uq_model_execution_links_billed) and the partial index
-- (idx_model_execution_links_enabled) key on billed_provider/billed_model, so they
-- are unaffected by this rename. Cloud-only feature; the table exists but is inert in CE.

ALTER TABLE agent.model_execution_links RENAME COLUMN bridge_provider TO execution_provider;
ALTER TABLE agent.model_execution_links RENAME COLUMN bridge_model TO execution_model;
