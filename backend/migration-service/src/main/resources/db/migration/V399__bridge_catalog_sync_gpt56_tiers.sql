-- V399 - Bridge catalog sync: add the GPT-5.6 codex tiers, drop the phantom bare gpt-5.6
--
-- Context
-- -------
-- OpenAI ships the GPT-5.6 generation as three codenamed tiers, NOT a bare
-- "gpt-5.6":
--   * gpt-5.6-sol   = frontier  (in $5 / out $30 per 1M)
--   * gpt-5.6-terra = balanced  (in $2.50 / out $15)
--   * gpt-5.6-luna  = fast/cheap (in $1 / out $6)
-- (analogous to normal/mini/nano). We also add gpt-5.5, which was previously
-- surfaced only by the now-removed codex discovery pattern.
--
-- The removed pattern (`^gpt-5\.\d+(-mini|-codex)?$`) matched a bare "gpt-5.6"
-- from the openai feed and auto-derived a `codex/gpt-5.6` catalog row. That id
-- is a real openai *API* model but is NOT routable via Codex with a ChatGPT
-- account: every run returned a typed 400 "The 'gpt-5.6' model is not
-- supported when using Codex with a ChatGPT account". codex is now curated-only
-- (see BridgeAllowlist.DISCOVERY_PATTERNS docblock), so this migration:
--   1. seeds the 4 real routable ids (paired change: BridgeAllowlist.MODELS
--      codex += these + agent/monolith `providers.codex.models` CSV; the
--      tri-parity guard BridgeProvidersHavePricingTest lists this file);
--   2. reconciles agent.model_config_overrides to the curated codex set,
--      DELETING any non-curated codex row (the phantom bare gpt-5.6, plus any
--      other pattern-derived id such as gpt-5.1) so it leaves the picker;
--   3. soft-closes the matching auth.model_pricing rows (preserve history).
--
-- Pricing follows V130/V378: bridge rows carry the underlying openai list price
-- (LiteLLM 2026-07 snapshot); BridgeModelDeriver keeps it in sync on later syncs.
-- Additive + idempotent: ON CONFLICT DO UPDATE realigns a row a feed sync may
-- already have created.

SET lock_timeout = '10s';
SET statement_timeout = '30s';
SET search_path TO agent;

-- ---------------------------------------------------------------------------
-- 1. Reconcile agent.model_config_overrides to the curated codex set.
--    Remove any codex row that is no longer allow-listed (phantom bare
--    gpt-5.6 + any other pattern-derived id). Runs BEFORE the upsert so the
--    NOT IN set already contains the ids we are about to (re)insert.
-- ---------------------------------------------------------------------------

DELETE FROM model_config_overrides
 WHERE provider = 'codex'
   AND model_id NOT IN ('gpt-5.6-sol', 'gpt-5.6-terra', 'gpt-5.6-luna', 'gpt-5.5',
                        'gpt-5.4', 'gpt-5.4-mini', 'gpt-5.3-codex', 'gpt-5.2');

-- ---------------------------------------------------------------------------
-- 2. agent.model_config_overrides: catalog rows (picker) for the 4 new ids.
-- ---------------------------------------------------------------------------

INSERT INTO model_config_overrides
    (provider, model_id, display_name, enabled, source, bundle_version,
     price_input, price_output, last_synced_at, provider_kind)
VALUES
    ('codex', 'gpt-5.6-sol',   'GPT-5.6 Sol',   TRUE, 'curated', 1, 5,   30, NOW(), 'bridge'),
    ('codex', 'gpt-5.6-terra', 'GPT-5.6 Terra', TRUE, 'curated', 1, 2.5, 15, NOW(), 'bridge'),
    ('codex', 'gpt-5.6-luna',  'GPT-5.6 Luna',  TRUE, 'curated', 1, 1,   6,  NOW(), 'bridge'),
    ('codex', 'gpt-5.5',       'GPT-5.5',       TRUE, 'curated', 1, 5,   30, NOW(), 'bridge')
ON CONFLICT (provider, model_id)
DO UPDATE SET enabled        = TRUE,
              display_name   = EXCLUDED.display_name,
              price_input    = EXCLUDED.price_input,
              price_output   = EXCLUDED.price_output,
              provider_kind  = 'bridge',
              source         = 'curated',
              last_synced_at = NOW();

-- ---------------------------------------------------------------------------
-- 3. auth.model_pricing: billing mirror (CreditService source of truth).
-- ---------------------------------------------------------------------------
-- 3a. Soft-close non-curated codex pricing rows (the phantom bare gpt-5.6 etc.).
UPDATE auth.model_pricing
   SET is_active    = false,
       effective_to = CURRENT_DATE
 WHERE provider = 'codex'
   AND is_active
   AND model NOT IN ('gpt-5.6-sol', 'gpt-5.6-terra', 'gpt-5.6-luna', 'gpt-5.5',
                     'gpt-5.4', 'gpt-5.4-mini', 'gpt-5.3-codex', 'gpt-5.2');

-- 3b. Close any active row for the new ids from an earlier date, preserving the
--     one-active-row invariant (V336) before inserting today's row.
UPDATE auth.model_pricing
   SET is_active    = false,
       effective_to = CURRENT_DATE
 WHERE provider = 'codex'
   AND model IN ('gpt-5.6-sol', 'gpt-5.6-terra', 'gpt-5.6-luna', 'gpt-5.5')
   AND is_active
   AND effective_from <> CURRENT_DATE;

INSERT INTO auth.model_pricing
    (provider, model, input_rate, output_rate, fixed_cost, effective_from, is_active, provider_kind)
VALUES
    ('codex', 'gpt-5.6-sol',   5,   30, 0, CURRENT_DATE, true, 'bridge'),
    ('codex', 'gpt-5.6-terra', 2.5, 15, 0, CURRENT_DATE, true, 'bridge'),
    ('codex', 'gpt-5.6-luna',  1,   6,  0, CURRENT_DATE, true, 'bridge'),
    ('codex', 'gpt-5.5',       5,   30, 0, CURRENT_DATE, true, 'bridge')
ON CONFLICT (provider, model, effective_from)
DO UPDATE SET input_rate    = EXCLUDED.input_rate,
              output_rate   = EXCLUDED.output_rate,
              fixed_cost    = 0,
              is_active     = true,
              provider_kind = 'bridge';
