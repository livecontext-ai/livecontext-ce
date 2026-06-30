-- Backfill auth.model_pricing with the 12 provider/model pairs that landed in
-- agent.model_config_overrides via V112 but were never mirrored to the billing
-- table. Without this, ModelPricingService.calculateCost() for any of these
-- models falls back to the 1.00 / 4.00 USD-per-1M defaults, under- or
-- over-charging versus the V112 seed the admin UI displays.
--
-- Scope: xAI (2), Perplexity (2), Cohere (2), Zai (3), OpenRouter (3).
-- All other V112 pairs were already in V80. ON CONFLICT DO UPDATE makes this
-- migration idempotent on re-run and realigns prices with V112 if an earlier
-- seed drifted.
--
-- Going forward: CatalogBundleApplier publishes pricing to auth via
-- AuthPricingSyncClient on every bundle apply (P0, 2026-04-21), so new
-- pairs arriving from the cloud catalog land automatically without another
-- Flyway migration. This backfill is a one-time reconciliation.
--
-- Unit contract: input_rate / output_rate = USD per 1M tokens.
-- Formula: credits = rate × tokens / 1000.

SET lock_timeout = '10s';
SET statement_timeout = '60s';

INSERT INTO auth.model_pricing
    (provider, model, input_rate, output_rate, fixed_cost, effective_from, is_active)
VALUES
    -- xAI
    ('xai',         'grok-3-beta',                           3.00, 15.00, 0, CURRENT_DATE, true),
    ('xai',         'grok-3-mini-beta',                      0.30,  0.50, 0, CURRENT_DATE, true),
    -- Perplexity
    ('perplexity',  'sonar-pro',                             3.00, 15.00, 0, CURRENT_DATE, true),
    ('perplexity',  'sonar-reasoning-pro',                   2.00,  8.00, 0, CURRENT_DATE, true),
    -- Cohere
    ('cohere',      'command-r-plus-08-2024',                2.50, 10.00, 0, CURRENT_DATE, true),
    ('cohere',      'command-r-08-2024',                     0.15,  0.60, 0, CURRENT_DATE, true),
    -- z.ai (GLM)
    ('zai',         'glm-5.1',                               1.40,  4.40, 0, CURRENT_DATE, true),
    ('zai',         'glm-5',                                 1.00,  3.20, 0, CURRENT_DATE, true),
    ('zai',         'glm-5-turbo',                           1.20,  4.00, 0, CURRENT_DATE, true),
    -- OpenRouter (aggregator - prices mirror upstream)
    ('openrouter',  'anthropic/claude-sonnet-4-20250514',    3.00, 15.00, 0, CURRENT_DATE, true),
    ('openrouter',  'openai/gpt-5.4',                        2.50, 15.00, 0, CURRENT_DATE, true),
    ('openrouter',  'google/gemini-3-pro-preview',           1.25, 10.00, 0, CURRENT_DATE, true)
ON CONFLICT (provider, model, effective_from)
DO UPDATE SET input_rate  = EXCLUDED.input_rate,
              output_rate = EXCLUDED.output_rate,
              is_active   = true;
