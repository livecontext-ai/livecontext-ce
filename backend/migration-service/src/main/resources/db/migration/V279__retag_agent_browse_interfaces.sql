-- ============================================================================
-- V279: Retag agent-browse interfaces previously misclassified as web_search.
-- ============================================================================
--
-- Before this migration:
--   WebSearchToolsProvider.persistAndEnrichResult persisted EVERY successful
--   tool call (search, fetch, agent_browse, browse_*) into
--   interface.interfaces with interface_type='web_search'. The frontend
--   discriminated by visualization marker (web_search vs agent_browse) at
--   the chat layer, not by the persisted row's type column.
--
-- After commit f600c8885 (2026-05-21):
--   search/fetch results render inline via FaviconStack - no Interface row
--   is created. Only browser-agent actions still persist.
--
-- After 2026-05-22 follow-up (this migration + WebSearchToolsProvider edit):
--   - search/fetch persistence path removed entirely.
--   - browser-agent persistence now writes interface_type='agent_browse'
--     via the new InterfaceService.createOrUpdateAgentBrowseInterface +
--     repository.findAgentBrowseInterface (was findWebSearchInterface).
--   - The interface_type='web_search' bucket is archived and one-shot
--     purged on prod immediately after this deploy.
--
-- This migration covers the rolling-deploy window AND retroactively saves
-- any pre-existing agent_browse rows that were stored with the wrong type.
--
-- Identification key: data->'results'->0->>'action' = 'agent_browse'.
-- This relies on a browser-agent session ALWAYS opening with the action
-- 'agent_browse' (session-bootstrap) before any follow-up browse_status /
-- browse_intervene / browse_abort / browse_screenshot - results[0] is the
-- session opener, never a status poll. Confirmed empirically against prod
-- on 2026-05-22:
--   SELECT data->'results'->0->>'action' AS first_action, COUNT(*)
--   FROM interface.interfaces
--   WHERE interface_type='web_search' GROUP BY 1;
--   →  search:42, fetch:7, agent_browse:1 - zero `browse_*` first-actions.
--
-- If a future code path ever persists a browse_* action FIRST (without the
-- agent_browse opener), it would slip past this WHERE clause and be lost
-- in the one-shot prod purge. The current persistAndEnrichResult flow
-- (WebSearchToolsProvider) does not have that shape, but the assumption
-- is worth flagging.
--
-- Idempotent: re-runs match zero rows (since the type column has already
-- been advanced), so the migration is safe to replay against the same DB.
-- ----------------------------------------------------------------------------

UPDATE interface.interfaces
SET interface_type = 'agent_browse',
    updated_at = NOW()
WHERE interface_type = 'web_search'
  AND data IS NOT NULL
  AND jsonb_typeof(data->'results') = 'array'
  AND jsonb_array_length(data->'results') > 0
  AND data->'results'->0->>'action' = 'agent_browse';
