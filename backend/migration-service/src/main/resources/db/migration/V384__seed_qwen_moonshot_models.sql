-- Seed the Qwen (Alibaba) and Moonshot (Kimi) providers into the curated
-- catalog + billing tables so cloud (which never runs the CE models.json seed)
-- lists and bills them. CE gets the same rows additively via
-- ModelSeedBootstrapService (model-catalog/models.json), so this migration keeps
-- the two editions consistent.
--
-- Prices: USD per 1M tokens (provider public list price, verify before relying
-- on them for billing). Trigger derive_model_credits() populates
-- credits_input / credits_output automatically for model_config_overrides.
--
-- Idempotency:
--   * model_config_overrides: ON CONFLICT (provider, model_id) DO NOTHING -
--     never clobbers an admin-edited row (matches the V112 insert-only floor).
--   * model_pricing: ON CONFLICT (provider, model, effective_from) DO UPDATE -
--     realigns rates on re-run (matches V116).

SET lock_timeout = '10s';
SET statement_timeout = '60s';

-- Catalog (agent schema, catalog source of truth) --------------------------
SET search_path TO agent;

INSERT INTO model_config_overrides
    (provider, model_id, display_name, enabled, source, bundle_version,
     price_input, price_output, last_synced_at)
VALUES
    -- Qwen (Alibaba)
    ('qwen',     'qwen-max',              'Qwen Max',          TRUE, 'curated', 1, 1.60, 6.40, NOW()),
    ('qwen',     'qwen-plus',             'Qwen Plus',         TRUE, 'curated', 1, 0.40, 1.20, NOW()),
    ('qwen',     'qwen-turbo',            'Qwen Turbo',        TRUE, 'curated', 1, 0.05, 0.20, NOW()),
    -- Moonshot (Kimi)
    ('moonshot', 'kimi-k2-0711-preview',  'Kimi K2',           TRUE, 'curated', 1, 0.60, 2.50, NOW()),
    ('moonshot', 'moonshot-v1-128k',      'Moonshot v1 128k',  TRUE, 'curated', 1, 2.00, 5.00, NOW()),
    ('moonshot', 'moonshot-v1-32k',       'Moonshot v1 32k',   TRUE, 'curated', 1, 1.20, 3.00, NOW())
ON CONFLICT (provider, model_id) DO NOTHING;

-- Billing (auth schema, the table ModelPricingService reads) ----------------
INSERT INTO auth.model_pricing
    (provider, model, input_rate, output_rate, fixed_cost, effective_from, is_active)
VALUES
    ('qwen',     'qwen-max',              1.60, 6.40, 0, CURRENT_DATE, true),
    ('qwen',     'qwen-plus',             0.40, 1.20, 0, CURRENT_DATE, true),
    ('qwen',     'qwen-turbo',            0.05, 0.20, 0, CURRENT_DATE, true),
    ('moonshot', 'kimi-k2-0711-preview',  0.60, 2.50, 0, CURRENT_DATE, true),
    ('moonshot', 'moonshot-v1-128k',      2.00, 5.00, 0, CURRENT_DATE, true),
    ('moonshot', 'moonshot-v1-32k',       1.20, 3.00, 0, CURRENT_DATE, true)
ON CONFLICT (provider, model, effective_from)
DO UPDATE SET input_rate  = EXCLUDED.input_rate,
              output_rate = EXCLUDED.output_rate,
              is_active   = true;
