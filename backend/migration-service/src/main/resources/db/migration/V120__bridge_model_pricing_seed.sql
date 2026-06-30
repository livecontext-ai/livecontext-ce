-- Seed the 11 CLI-bridge (provider, model) pairs into BOTH
--   (a) auth.model_pricing               - billing source of truth
--   (b) agent.model_config_overrides     - catalog source of truth
--
-- Context
-- -------
-- V112 seeded the bridge *models* under their REAL provider names
-- (e.g. (anthropic, claude-opus-4-6-cc)) because the model_id is the same -
-- the distinction "this call goes through the local CLI vs the cloud API" is
-- represented by the provider name (claude-code vs anthropic), not the model.
-- V117 then expected rows under the bridge provider names to flip their
-- provider_kind to 'bridge' and zero their rates, but its UPDATE matched 0
-- rows: no V112 row lived under provider IN ('claude-code', 'codex',
-- 'gemini-cli', 'mistral-vibe').
--
-- Runtime consequence: ModelPricingService.hasPricing('claude-code',
-- 'claude-sonnet-4-6-cc') returns false → CreditService rejects every
-- bridge chat turn with 402 PAYMENT_REQUIRED / MODEL_UNSUPPORTED, even
-- when the user has credits. See prod incident diagnosed 2026-04-21.
--
-- Fix
-- ---
-- Explicitly insert the 11 bridge pairs with:
--   * input_rate = 0, output_rate = 0, fixed_cost = 0
--     - bridge calls are flat-rate via the admin's CLI subscription; there
--     is no per-token cost to pass through (confirmed product direction
--     2026-04-21: "pour l'instant pas de marge").
--   * provider_kind = 'bridge'
--   * is_active = true
-- Mirror the same pairs in agent.model_config_overrides with price_input=0,
-- price_output=0, provider_kind='bridge', source='curated', bundle_version=1
-- so the admin UI model picker surfaces them with the right label/kind and
-- ModelPricingOutboxPublisher won't fight the seeded rates on next bundle sync.
--
-- Both inserts run in the same Flyway TX - partial seed is impossible.
--
-- Idempotent: ON CONFLICT DO UPDATE realigns rates/kind/is_active if a drifted
-- row exists (e.g. a future bundle applied non-zero rates to a bridge row -
-- this re-asserts the invariant).
--
-- Guard invariant: BridgeProvidersHavePricingTest (auth-service) enumerates
-- bridge providers from application.yml and asserts every configured bridge
-- model has a matching auth.model_pricing row with kind='bridge' + rates=0.

SET lock_timeout = '10s';
SET statement_timeout = '60s';

-- ---------------------------------------------------------------------------
-- 1. auth.model_pricing - billing source of truth
-- ---------------------------------------------------------------------------

INSERT INTO auth.model_pricing
    (provider, model, input_rate, output_rate, fixed_cost, effective_from, is_active, provider_kind)
VALUES
    -- Claude Code (2)
    ('claude-code',  'claude-opus-4-6-cc',        0, 0, 0, CURRENT_DATE, true, 'bridge'),
    ('claude-code',  'claude-sonnet-4-6-cc',      0, 0, 0, CURRENT_DATE, true, 'bridge'),
    -- Codex (3)
    ('codex',        'gpt-5.4',                   0, 0, 0, CURRENT_DATE, true, 'bridge'),
    ('codex',        'gpt-5.4-mini',              0, 0, 0, CURRENT_DATE, true, 'bridge'),
    ('codex',        'gpt-5.3-codex',             0, 0, 0, CURRENT_DATE, true, 'bridge'),
    -- Gemini CLI (4)
    ('gemini-cli',   'gemini-2.5-pro',            0, 0, 0, CURRENT_DATE, true, 'bridge'),
    ('gemini-cli',   'gemini-2.5-flash',          0, 0, 0, CURRENT_DATE, true, 'bridge'),
    ('gemini-cli',   'gemini-3.1-pro-preview',    0, 0, 0, CURRENT_DATE, true, 'bridge'),
    ('gemini-cli',   'gemini-3.1-flash',          0, 0, 0, CURRENT_DATE, true, 'bridge'),
    -- Mistral Vibe (2)
    ('mistral-vibe', 'devstral-2',                0, 0, 0, CURRENT_DATE, true, 'bridge'),
    ('mistral-vibe', 'devstral-small-2',          0, 0, 0, CURRENT_DATE, true, 'bridge')
ON CONFLICT (provider, model, effective_from)
DO UPDATE SET input_rate    = 0,
              output_rate   = 0,
              fixed_cost    = 0,
              is_active     = true,
              provider_kind = 'bridge';

-- ---------------------------------------------------------------------------
-- 2. agent.model_config_overrides - catalog source of truth
-- ---------------------------------------------------------------------------
-- source='curated' + bundle_version=1 matches the V112 convention.
-- display_name defaults to model_id (admin can refine from the UI).
-- The derive_model_credits() trigger will set credits_input/credits_output = 0
-- automatically (price × markup × 10 = 0 × 1.20 × 10 = 0).

SET search_path TO agent;

INSERT INTO model_config_overrides
    (provider, model_id, display_name, enabled, source, bundle_version,
     price_input, price_output, last_synced_at, provider_kind)
VALUES
    -- Claude Code (2)
    ('claude-code',  'claude-opus-4-6-cc',        'claude-opus-4-6-cc',        TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    ('claude-code',  'claude-sonnet-4-6-cc',      'claude-sonnet-4-6-cc',      TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    -- Codex (3)
    ('codex',        'gpt-5.4',                   'gpt-5.4',                   TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    ('codex',        'gpt-5.4-mini',              'gpt-5.4-mini',              TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    ('codex',        'gpt-5.3-codex',             'gpt-5.3-codex',             TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    -- Gemini CLI (4)
    ('gemini-cli',   'gemini-2.5-pro',            'gemini-2.5-pro',            TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    ('gemini-cli',   'gemini-2.5-flash',          'gemini-2.5-flash',          TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    ('gemini-cli',   'gemini-3.1-pro-preview',    'gemini-3.1-pro-preview',    TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    ('gemini-cli',   'gemini-3.1-flash',          'gemini-3.1-flash',          TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    -- Mistral Vibe (2)
    ('mistral-vibe', 'devstral-2',                'devstral-2',                TRUE, 'curated', 1, 0, 0, NOW(), 'bridge'),
    ('mistral-vibe', 'devstral-small-2',          'devstral-small-2',          TRUE, 'curated', 1, 0, 0, NOW(), 'bridge')
ON CONFLICT (provider, model_id)
DO UPDATE SET enabled        = TRUE,
              price_input    = 0,
              price_output   = 0,
              provider_kind  = 'bridge',
              last_synced_at = NOW();
