-- Fix-forward for V384: the Moonshot (Kimi) models it seeded
-- (kimi-k2-0711-preview, moonshot-v1-128k, moonshot-v1-32k) were retired by
-- Moonshot on 2026-05-25 (EOL). Replace them with the current live models
-- Kimi K2.6 and K2.5, and correct billing to the official list prices.
--
-- Prices: USD per 1M tokens (official public list price).
--   kimi-k2.6: 0.95 / 4.00     kimi-k2.5: 0.60 / 3.00
--
-- Only the curated rows V384 inserted are removed (source='curated'); an admin
-- addition of the same id (unlikely in the hours since V384) is left untouched.

SET lock_timeout = '10s';
SET statement_timeout = '60s';

-- Catalog (agent schema) --------------------------------------------------
SET search_path TO agent;

DELETE FROM model_config_overrides
 WHERE provider = 'moonshot'
   AND model_id IN ('kimi-k2-0711-preview', 'moonshot-v1-128k', 'moonshot-v1-32k')
   AND source = 'curated';

INSERT INTO model_config_overrides
    (provider, model_id, display_name, enabled, source, bundle_version,
     price_input, price_output, last_synced_at)
VALUES
    ('moonshot', 'kimi-k2.6', 'Kimi K2.6', TRUE, 'curated', 1, 0.95, 4.00, NOW()),
    ('moonshot', 'kimi-k2.5', 'Kimi K2.5', TRUE, 'curated', 1, 0.60, 3.00, NOW())
ON CONFLICT (provider, model_id) DO NOTHING;

-- Billing (auth schema) ---------------------------------------------------
DELETE FROM auth.model_pricing
 WHERE provider = 'moonshot'
   AND model IN ('kimi-k2-0711-preview', 'moonshot-v1-128k', 'moonshot-v1-32k');

INSERT INTO auth.model_pricing
    (provider, model, input_rate, output_rate, fixed_cost, effective_from, is_active)
VALUES
    ('moonshot', 'kimi-k2.6', 0.95, 4.00, 0, CURRENT_DATE, true),
    ('moonshot', 'kimi-k2.5', 0.60, 3.00, 0, CURRENT_DATE, true)
ON CONFLICT (provider, model, effective_from)
DO UPDATE SET input_rate  = EXCLUDED.input_rate,
              output_rate = EXCLUDED.output_rate,
              is_active   = true;
