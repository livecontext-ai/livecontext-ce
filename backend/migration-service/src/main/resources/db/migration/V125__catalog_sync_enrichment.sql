-- V125 - Catalog sync enrichment
-- (originally drafted as V121; renumbered because V121 was taken by a
-- parallel CE-install-state migration that landed first.)
--
-- Context
-- -------
-- Phase 1 of the LiteLLM + OpenRouter live-catalog sync. Today
-- model_config_overrides carries a thin shape (price, context, tools, vision).
-- LiteLLM exposes 138 distinct fields; most aren't queried in hot paths but
-- are valuable for the admin UI (tooltips, filters) and for future billing
-- granularity (batch pricing, cache pricing, deprecation tracking).
--
-- Design
-- ------
-- Two complementary storage strategies:
--   1. Dedicated columns for fields we query or display (prices, caps, flags,
--      dates). Keeps JSONB probing out of hot paths.
--   2. feed_metadata JSONB dumps the raw LiteLLM / OpenRouter entry so new
--      fields can be surfaced later without a schema migration.
--
-- Also introduces model_catalog_sync_log - observability for admin-triggered
-- and scheduled sync runs (added / updated / deprecated / guard failures).
--
-- Idempotent: every ADD COLUMN uses IF NOT EXISTS; table uses CREATE TABLE IF
-- NOT EXISTS so replays after a partial rollout are safe.

SET lock_timeout = '10s';
SET statement_timeout = '120s';
SET search_path TO agent;

-- ---------------------------------------------------------------------------
-- 1. Enrichment columns on model_config_overrides
-- ---------------------------------------------------------------------------

-- Lifecycle dates
ALTER TABLE model_config_overrides ADD COLUMN IF NOT EXISTS deprecation_date DATE;
ALTER TABLE model_config_overrides ADD COLUMN IF NOT EXISTS release_date     DATE;

-- Capability flags (beyond the existing supports_tools / supports_vision)
ALTER TABLE model_config_overrides ADD COLUMN IF NOT EXISTS supports_prompt_caching BOOLEAN;
ALTER TABLE model_config_overrides ADD COLUMN IF NOT EXISTS supports_reasoning      BOOLEAN;
ALTER TABLE model_config_overrides ADD COLUMN IF NOT EXISTS supports_computer_use   BOOLEAN;
ALTER TABLE model_config_overrides ADD COLUMN IF NOT EXISTS supports_response_schema BOOLEAN;
ALTER TABLE model_config_overrides ADD COLUMN IF NOT EXISTS supports_web_search     BOOLEAN;

-- Chat / embedding / image / audio - matches LiteLLM's "mode" field.
ALTER TABLE model_config_overrides ADD COLUMN IF NOT EXISTS mode TEXT;

-- Pricing variants (USD per 1M tokens - same unit as price_input / price_output).
-- Batches / flex are 50% discount tiers on OpenAI + Anthropic; cache_read is
-- ~10% of standard; cache_write is ~125% of standard.
ALTER TABLE model_config_overrides ADD COLUMN IF NOT EXISTS price_input_batch   NUMERIC(10,4);
ALTER TABLE model_config_overrides ADD COLUMN IF NOT EXISTS price_output_batch  NUMERIC(10,4);
ALTER TABLE model_config_overrides ADD COLUMN IF NOT EXISTS price_cache_read    NUMERIC(10,4);
ALTER TABLE model_config_overrides ADD COLUMN IF NOT EXISTS price_cache_write   NUMERIC(10,4);

-- Derived price_floor_* = min(standard, batch, flex). Computed by the sync
-- service, not a DB trigger - the feeds don't expose flex consistently and
-- we want explicit control over which variants count as "floor".
ALTER TABLE model_config_overrides ADD COLUMN IF NOT EXISTS price_floor_input  NUMERIC(10,4);
ALTER TABLE model_config_overrides ADD COLUMN IF NOT EXISTS price_floor_output NUMERIC(10,4);

-- Multi-modality support. Arrays mirror LiteLLM's supported_endpoints +
-- supported_modalities / supported_output_modalities. TEXT[] (not JSONB) so
-- we can index and filter with @> cheaply later if needed.
ALTER TABLE model_config_overrides ADD COLUMN IF NOT EXISTS supported_endpoints         TEXT[];
ALTER TABLE model_config_overrides ADD COLUMN IF NOT EXISTS supported_modalities        TEXT[];
ALTER TABLE model_config_overrides ADD COLUMN IF NOT EXISTS supported_output_modalities TEXT[];

-- Raw feed payload (future-proofing). Fields that aren't yet surfaced in
-- dedicated columns remain queryable via feed_metadata->'raw' without a new
-- migration. Structure: {source, source_sha, fetched_at, raw: {…}}.
ALTER TABLE model_config_overrides ADD COLUMN IF NOT EXISTS feed_metadata JSONB;

COMMENT ON COLUMN model_config_overrides.feed_metadata IS
  'Raw feed payload + provenance. Shape: {source: litellm|openrouter, source_sha, fetched_at, raw: <verbatim feed entry>}. Populated by ModelCatalogSyncService; admin-edited rows keep whatever was last synced.';
COMMENT ON COLUMN model_config_overrides.price_floor_input IS
  'min(price_input, price_input_batch) - cheapest per-1M input price a caller can obtain. Computed by sync service.';
COMMENT ON COLUMN model_config_overrides.release_date IS
  'Best-effort: derived from model_id date suffix (YYYYMMDD or YYYY-MM-DD) when present; otherwise admin-set.';

-- ---------------------------------------------------------------------------
-- 2. Sync observability log
-- ---------------------------------------------------------------------------
--
-- One row per sync attempt (dry-run OR apply). Enables admin UI to show a
-- history tab and lets operators post-mortem a bad sync by diffing
-- guard_failures across runs.

CREATE TABLE IF NOT EXISTS model_catalog_sync_log (
    id             BIGSERIAL PRIMARY KEY,
    source         TEXT NOT NULL,           -- 'litellm' | 'openrouter' | 'both'
    fetched_at     TIMESTAMPTZ NOT NULL,    -- when the feed was fetched
    model_count    INTEGER NOT NULL,        -- total models kept after filters
    checksum       TEXT,                    -- SHA-256 of fetched bytes (null if fetch failed)
    triggered_by   TEXT NOT NULL,           -- actor X-User-ID, or 'scheduler' for auto runs
    dry_run        BOOLEAN NOT NULL,
    outcome        TEXT NOT NULL,           -- 'OK' | 'ABORTED_GUARD' | 'FETCH_ERROR' | 'SCHEMA_ERROR' | 'APPLY_ERROR'
    error_detail   TEXT,                    -- null on OK
    guard_failures JSONB,                   -- {countFloor: {...}, priceSanity: [...], schemaErrors: [...]}
    added_count    INTEGER NOT NULL DEFAULT 0,
    updated_count  INTEGER NOT NULL DEFAULT 0,
    deprecated_count INTEGER NOT NULL DEFAULT 0,
    flagged_count  INTEGER NOT NULL DEFAULT 0,  -- rows that tripped price-sanity
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_sync_log_source   CHECK (source IN ('litellm','openrouter','both')),
    CONSTRAINT chk_sync_log_outcome  CHECK (outcome IN (
        'OK','ABORTED_GUARD','FETCH_ERROR','SCHEMA_ERROR','APPLY_ERROR'))
);

CREATE INDEX IF NOT EXISTS idx_sync_log_created_at
    ON model_catalog_sync_log (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_sync_log_outcome
    ON model_catalog_sync_log (outcome, created_at DESC)
    WHERE outcome != 'OK';

COMMENT ON TABLE model_catalog_sync_log IS
  'History of ModelCatalogSyncService runs. Append-only: operators grep this table after a bad sync to recover the flagged rows and the guard that caught them.';
