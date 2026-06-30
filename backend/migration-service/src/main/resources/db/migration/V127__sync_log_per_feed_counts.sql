-- V127 - Per-feed model counters on model_catalog_sync_log
--
-- Context
-- -------
-- The count-floor guard needs a per-feed baseline so a degraded run
-- (e.g. OpenRouter down, LiteLLM up) compares LiteLLM's count against
-- the last LiteLLM baseline, not against a combined "both" total that
-- would falsely inflate the comparison.
--
-- V125's log row only carries the combined `model_count` (e.g. 715 for a
-- "both" run); once written it can't be decomposed. This migration adds
-- two nullable ints capturing each feed's kept-model count at fetch time.
-- Legacy rows keep NULL; the guard treats NULL as "no baseline".
--
-- Both columns are nullable with no defaults - a run where a given feed
-- failed to fetch will have NULL for that feed, which is semantically
-- "no kept models for this feed" and correctly excludes that run from the
-- per-feed baseline pool.

SET lock_timeout = '10s';
SET statement_timeout = '30s';
SET search_path TO agent;

ALTER TABLE model_catalog_sync_log
    ADD COLUMN IF NOT EXISTS litellm_count    INTEGER,
    ADD COLUMN IF NOT EXISTS openrouter_count INTEGER;

COMMENT ON COLUMN model_catalog_sync_log.litellm_count IS
  'Kept-after-filters count from LiteLLM feed for this run. NULL if LiteLLM did not fetch. Powers per-feed count-floor baseline.';
COMMENT ON COLUMN model_catalog_sync_log.openrouter_count IS
  'Kept-after-filters count from OpenRouter feed for this run. NULL if OpenRouter did not fetch.';
