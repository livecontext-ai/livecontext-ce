-- V378 - Bridge catalog sync: add Claude Fable 5 to the claude-code bridge
--
-- Context
-- -------
-- Anthropic released Claude Fable 5 (model id `claude-fable-5`), a NEW model
-- family ("fable"). The direct-API `anthropic` provider already surfaces it
-- via the LiteLLM feed sync, but the claude-code bridge catalog is gated by
-- the hand-curated BridgeAllowlist (see V128 docblock): the pattern
-- auto-discovery layer only matched the opus/sonnet/haiku families, so a new
-- family requires this paired change:
--
--   * BridgeAllowlist.MODELS["claude-code"] += claude-fable-5, and the
--     claude-code DISCOVERY_PATTERN gains the `fable` family
--     (backend/shared-agent-lib/.../bridge/BridgeAllowlist.java);
--   * agent-service application.yml + monolith application-ce.yml
--     `providers.claude-code.models` CSV += claude-fable-5
--     (BridgeProvidersHavePricingTest asserts tri-parity);
--   * this migration seeds the DB rows so the model shows up without waiting
--     for the next feed sync.
--
-- Pricing follows the V130 product decision: bridge rows carry the price of
-- the underlying cloud model. Claude Fable 5 list price (platform.claude.com,
-- 2026-07): $10 input / $50 output per 1M tokens. BridgeModelDeriver keeps it
-- in sync with LiteLLM on subsequent syncs.
--
-- Additive + idempotent: ON CONFLICT DO UPDATE realigns a row that a feed
-- sync may already have created.

SET lock_timeout = '10s';
SET statement_timeout = '30s';
SET search_path TO agent;

-- ---------------------------------------------------------------------------
-- 1. agent.model_config_overrides - catalog row (picker).
-- ---------------------------------------------------------------------------

INSERT INTO model_config_overrides
    (provider, model_id, display_name, enabled, source, bundle_version,
     price_input, price_output, last_synced_at, provider_kind)
VALUES
    ('claude-code', 'claude-fable-5', 'Claude Fable 5', TRUE, 'curated', 1, 10, 50, NOW(), 'bridge')
ON CONFLICT (provider, model_id)
DO UPDATE SET enabled        = TRUE,
              price_input    = 10,
              price_output   = 50,
              provider_kind  = 'bridge',
              source         = 'curated',
              last_synced_at = NOW();

-- ---------------------------------------------------------------------------
-- 2. auth.model_pricing - billing mirror (CreditService source of truth).
-- ---------------------------------------------------------------------------
-- The ON CONFLICT target includes effective_from, so it only dedupes a row
-- created the SAME day. Close any active row from an earlier date first
-- (e.g. a CE that applied a cloud catalog bundle carrying this pair before
-- upgrading to this build) to preserve the one-active-row invariant (V336).

UPDATE auth.model_pricing
   SET is_active    = false,
       effective_to = CURRENT_DATE
 WHERE provider = 'claude-code'
   AND model = 'claude-fable-5'
   AND is_active
   AND effective_from <> CURRENT_DATE;

INSERT INTO auth.model_pricing
    (provider, model, input_rate, output_rate, fixed_cost, effective_from, is_active, provider_kind)
VALUES
    ('claude-code', 'claude-fable-5', 10, 50, 0, CURRENT_DATE, true, 'bridge')
ON CONFLICT (provider, model, effective_from)
DO UPDATE SET input_rate    = 10,
              output_rate   = 50,
              fixed_cost    = 0,
              is_active     = true,
              provider_kind = 'bridge';
