-- V128 - Bridge catalog reconciled with hand-curated allowlist
--
-- Context
-- -------
-- V120 seeded 11 bridge rows with a "-cc" suffix (e.g. claude-opus-4-6-cc).
-- Three problems:
--
-- 1. The "-cc" suffix is a platform invention; neither Anthropic API nor
--    the claude-code CLI recognizes it. The claude bridge adapter
--    (mcp/bridge/adapters/claude-adapter.mjs) currently works around this by
--    running a regex to extract just "opus"/"sonnet"/"haiku" and passing
--    that alias to the CLI - but that collapses 4.7 vs 4.6 vs 4.5 into the
--    same "opus" alias, so picking a specific version in the UI has no
--    runtime effect.
--
-- 2. V120's list is outdated (April 2026 adds Opus 4.7, codex GPT-5.4,
--    Gemini 3.1 Pro Preview, Devstral 2 canonical id, etc.).
--
-- 3. Previous approach "auto-derive bridges from LiteLLM" was audited by
--    3 independent reviewers (scores 5/6/5 out of 10) and found unsafe:
--    LiteLLM taxonomy doesn't match CLI routing tables for codex (filter
--    too loose), gemini-cli (preview lifecycle not encoded), and
--    mistral-vibe (config-file aliases differ from LiteLLM ids).
--
-- Resolution: hand-curated allowlist in
-- backend/shared-agent-lib/.../bridge/BridgeAllowlist.java. This migration
-- reconciles the DB with that allowlist. When the allowlist changes, a new
-- Vxxx__bridge_catalog_sync.sql migration is added.
--
-- Paired code changes (must ship together):
--   * mcp/bridge/adapters/claude-adapter.mjs → pass model verbatim
--     (no more regex alias extraction) so version selection is honoured.
--   * ModelCatalogSyncService → skip EXCLUDED_PROVIDERS bridges (unchanged).
--   * CatalogMergeService → allow bridge-to-bridge bundle updates so CE
--     follows cloud's allowlist when it evolves.
--
-- Idempotent: the DELETE+INSERT pattern lets admins replay by stripping
-- V128 from flyway_schema_history and re-running; the new INSERT uses
-- ON CONFLICT DO UPDATE to realign realize a drifted row.

SET lock_timeout = '10s';
SET statement_timeout = '60s';

-- ---------------------------------------------------------------------------
-- 1. Drop V120's outdated bridge rows (both catalog + pricing mirror).
-- ---------------------------------------------------------------------------
-- CASCADE-safe: bridge rows are not referenced by FKs.

DELETE FROM agent.model_config_overrides
 WHERE provider IN ('claude-code','codex','gemini-cli','mistral-vibe');

DELETE FROM auth.model_pricing
 WHERE provider IN ('claude-code','codex','gemini-cli','mistral-vibe');

-- ---------------------------------------------------------------------------
-- 2. Seed from BridgeAllowlist - canonical ids, no suffix.
-- ---------------------------------------------------------------------------
-- Every row: provider_kind='bridge', rates=0, is_active=true,
-- source='curated' (matches V112 convention for seed rows).

SET search_path TO agent;

INSERT INTO model_config_overrides
    (provider, model_id, display_name, enabled, source, bundle_version,
     price_input, price_output, last_synced_at, provider_kind)
VALUES
    -- Claude Code (5) - Anthropic API ids, adapter passes verbatim
    ('claude-code',  'claude-opus-4-7',        'Claude Opus 4.7',        TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    ('claude-code',  'claude-opus-4-6',        'Claude Opus 4.6',        TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    ('claude-code',  'claude-sonnet-4-6',      'Claude Sonnet 4.6',      TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    ('claude-code',  'claude-sonnet-4-5',      'Claude Sonnet 4.5',      TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    ('claude-code',  'claude-haiku-4-5',       'Claude Haiku 4.5',       TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    -- Codex (4) - ids exactly as codex --model expects
    ('codex',        'gpt-5.4',                'GPT-5.4',                TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    ('codex',        'gpt-5.4-mini',           'GPT-5.4 mini',           TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    ('codex',        'gpt-5.3-codex',          'GPT-5.3 Codex',          TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    ('codex',        'gpt-5.2',                'GPT-5.2',                TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    -- Gemini CLI (4) - ids exactly as gemini-cli --model expects
    ('gemini-cli',   'gemini-3.1-pro-preview', 'Gemini 3.1 Pro Preview', TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    ('gemini-cli',   'gemini-3-flash-preview', 'Gemini 3 Flash Preview', TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    ('gemini-cli',   'gemini-2.5-pro',         'Gemini 2.5 Pro',         TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    ('gemini-cli',   'gemini-2.5-flash',       'Gemini 2.5 Flash',       TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    -- Mistral Vibe (2) - aliases from ~/.vibe/config.toml active_model
    ('mistral-vibe', 'devstral-2',             'Devstral 2',             TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    ('mistral-vibe', 'devstral-small-2',       'Devstral Small 2',       TRUE, 'curated', 1, 0, 0, NOW(), 'bridge')
ON CONFLICT (provider, model_id)
DO UPDATE SET enabled        = TRUE,
              price_input    = 0,
              price_output   = 0,
              provider_kind  = 'bridge',
              source         = 'curated',
              last_synced_at = NOW();

-- ---------------------------------------------------------------------------
-- 3. Mirror into auth.model_pricing (billing source of truth).
-- ---------------------------------------------------------------------------

INSERT INTO auth.model_pricing
    (provider, model, input_rate, output_rate, fixed_cost, effective_from, is_active, provider_kind)
VALUES
    ('claude-code',  'claude-opus-4-7',        0, 0, 0, CURRENT_DATE, true, 'bridge'),
    ('claude-code',  'claude-opus-4-6',        0, 0, 0, CURRENT_DATE, true, 'bridge'),
    ('claude-code',  'claude-sonnet-4-6',      0, 0, 0, CURRENT_DATE, true, 'bridge'),
    ('claude-code',  'claude-sonnet-4-5',      0, 0, 0, CURRENT_DATE, true, 'bridge'),
    ('claude-code',  'claude-haiku-4-5',       0, 0, 0, CURRENT_DATE, true, 'bridge'),
    ('codex',        'gpt-5.4',                0, 0, 0, CURRENT_DATE, true, 'bridge'),
    ('codex',        'gpt-5.4-mini',           0, 0, 0, CURRENT_DATE, true, 'bridge'),
    ('codex',        'gpt-5.3-codex',          0, 0, 0, CURRENT_DATE, true, 'bridge'),
    ('codex',        'gpt-5.2',                0, 0, 0, CURRENT_DATE, true, 'bridge'),
    ('gemini-cli',   'gemini-3.1-pro-preview', 0, 0, 0, CURRENT_DATE, true, 'bridge'),
    ('gemini-cli',   'gemini-3-flash-preview', 0, 0, 0, CURRENT_DATE, true, 'bridge'),
    ('gemini-cli',   'gemini-2.5-pro',         0, 0, 0, CURRENT_DATE, true, 'bridge'),
    ('gemini-cli',   'gemini-2.5-flash',       0, 0, 0, CURRENT_DATE, true, 'bridge'),
    ('mistral-vibe', 'devstral-2',             0, 0, 0, CURRENT_DATE, true, 'bridge'),
    ('mistral-vibe', 'devstral-small-2',       0, 0, 0, CURRENT_DATE, true, 'bridge')
ON CONFLICT (provider, model, effective_from)
DO UPDATE SET input_rate    = 0,
              output_rate   = 0,
              fixed_cost    = 0,
              is_active     = true,
              provider_kind = 'bridge';
