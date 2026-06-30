-- Model execution links: scope a link to an app surface (CLOUD-only, inert in CE).
--
-- A link maps a billed (provider, model) pair to an execution target. Until now a
-- link was GLOBAL: it applied to every run of that billed pair. This adds a `scope`
-- so the SAME billed pair can route differently per surface, keyed on the run's
-- logical origin (AgentExecutionRequestDto.source, surfaced at the resolve
-- chokepoint by AgentRemoteExecutionService.resolveActivitySource):
--   ALL (wildcard / default), CHAT, WORKFLOW, WEBHOOK, WIDGET, SCHEDULE, TASK,
--   TASK_REVIEW.
-- Resolution is exact-surface first, then a fallback to the ALL row.
--
-- Backward compatibility: the column DEFAULT 'ALL' backfills every existing row to
-- ALL in place, so all current links keep applying to every surface unchanged.
-- The unique key must widen from (billed_provider, billed_model) to include scope,
-- so the same billed pair can hold one ALL row plus one row per surface.

ALTER TABLE agent.model_execution_links
    ADD COLUMN IF NOT EXISTS scope VARCHAR(30) NOT NULL DEFAULT 'ALL';

-- Re-key on (billed_provider, billed_model, scope): one route per billed pair PER
-- surface (the old key allowed only one route per billed pair).
ALTER TABLE agent.model_execution_links
    DROP CONSTRAINT IF EXISTS uq_model_execution_links_billed;
ALTER TABLE agent.model_execution_links
    ADD CONSTRAINT uq_model_execution_links_billed_scope
    UNIQUE (billed_provider, billed_model, scope);

-- Widen the hot-lookup partial index to match the new key (resolve reads by billed
-- pair + scope).
DROP INDEX IF EXISTS agent.idx_model_execution_links_enabled;
CREATE INDEX IF NOT EXISTS idx_model_execution_links_enabled
    ON agent.model_execution_links (billed_provider, billed_model, scope)
    WHERE enabled = TRUE;
