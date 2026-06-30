-- ---------------------------------------------------------------------------
-- V158: Image-generation catalog - pricing + rate limits + gpt-image-2
--
-- V157 inserted 8 image-gen rows but left price_input / rate_limit_* as NULL,
-- so the admin UI rendered them as "free" with no caps. This migration:
--
--   1. Adds gpt-image-2 (released 2026-04-21) - 3 quality tiers
--      (low / medium / high). NO mini variant - OpenAI didn't ship one.
--   2. Fills per-image USD pricing on every row (the column is documented as
--      "$/1M tokens" for chat - for image-gen we overload it as the per-image
--      USD rate; the runtime billing path uses auth.model_pricing which
--      already carries the credit-equivalent per-image rate seeded by V141 +
--      this migration).
--   3. Sets sane Tier-1 rate limits as defaults so admins don't deploy with
--      uncapped image-gen:
--        * OpenAI Tier 1 (official): 100,000 TPM, 5 IPM (= ~5 RPM).
--          Per-tenant defaults conservatively to 1/5 global.
--        * Google paid tier (gemini-image API): 1,000 RPM standard;
--          TPM gated behind AI Studio, defaulted at 1,000,000 here.
--          Per-tenant 1/5 global.
--   4. Mirrors the new gpt-image-2 rows into auth.model_pricing (per-image
--      credits - 1 credit ≈ $0.001) so ImageGenerationBillingStrategy can
--      bill them.
--   5. Backfills the V156 sidecar (image_generation) so the new rows surface
--      on the admin Image Generation tab immediately.
--
-- Idempotent: ON CONFLICT DO UPDATE so re-running (or running on a CE where
-- V157 was wiped by a Reset All) repairs the catalog instead of failing.
-- ---------------------------------------------------------------------------

SET search_path = agent, public;

-- 1. Upsert all 11 image-gen rows (8 from V157 + 3 new gpt-image-2) with
-- pricing + rate limits fully populated.
INSERT INTO model_config_overrides
    (provider, model_id, enabled, display_name, description, tier, ranking,
     recommended, mode, provider_kind, source, is_custom,
     price_input, price_output,
     rate_limit_tpm, rate_limit_rpm,
     rate_limit_tpm_per_tenant, rate_limit_rpm_per_tenant,
     created_at, updated_at)
VALUES
    -- ── OpenAI gpt-image-2 (released 2026-04-21, current default) ──────────
    ('openai', 'gpt-image-2-low',
     TRUE, 'GPT Image 2 (Low)',
     'OpenAI gpt-image-2 (current default, released 2026-04-21), 1024x1024, low quality. $0.006/image.',
     'budget', 90, FALSE, 'image', 'cloud', 'manual', FALSE,
     0.006, 0,
     100000, 5,
     20000, 2,
     now(), now()),
    ('openai', 'gpt-image-2-medium',
     TRUE, 'GPT Image 2 (Medium)',
     'OpenAI gpt-image-2 (current default), 1024x1024, medium quality. $0.053/image.',
     'mid', 91, TRUE, 'image', 'cloud', 'manual', FALSE,
     0.053, 0,
     100000, 5,
     20000, 2,
     now(), now()),
    ('openai', 'gpt-image-2-high',
     TRUE, 'GPT Image 2 (High)',
     'OpenAI gpt-image-2 (current default), 1024x1024, high quality. $0.211/image.',
     'high', 92, FALSE, 'image', 'cloud', 'manual', FALSE,
     0.211, 0,
     100000, 5,
     20000, 2,
     now(), now()),
    -- ── OpenAI gpt-image-1.5 (previous default, still available) ──────────
    ('openai', 'gpt-image-1.5-low',
     TRUE, 'GPT Image 1.5 (Low)',
     'OpenAI gpt-image-1.5, 1024x1024, low quality. $0.009/image.',
     'budget', 100, FALSE, 'image', 'cloud', 'manual', FALSE,
     0.009, 0,
     100000, 5,
     20000, 2,
     now(), now()),
    ('openai', 'gpt-image-1.5-medium',
     TRUE, 'GPT Image 1.5 (Medium)',
     'OpenAI gpt-image-1.5, 1024x1024, medium quality. $0.034/image.',
     'mid', 101, FALSE, 'image', 'cloud', 'manual', FALSE,
     0.034, 0,
     100000, 5,
     20000, 2,
     now(), now()),
    ('openai', 'gpt-image-1.5-high',
     TRUE, 'GPT Image 1.5 (High)',
     'OpenAI gpt-image-1.5, 1024x1024, high quality. $0.133/image.',
     'high', 102, FALSE, 'image', 'cloud', 'manual', FALSE,
     0.133, 0,
     100000, 5,
     20000, 2,
     now(), now()),
    -- ── OpenAI gpt-image-1-mini (cheapest tier) ────────────────────────────
    ('openai', 'gpt-image-1-mini-low',
     TRUE, 'GPT Image 1 Mini (Low)',
     'OpenAI mini image generation, 1024x1024, low quality. $0.005/image.',
     'budget', 103, FALSE, 'image', 'cloud', 'manual', FALSE,
     0.005, 0,
     100000, 5,
     20000, 2,
     now(), now()),
    ('openai', 'gpt-image-1-mini-medium',
     TRUE, 'GPT Image 1 Mini (Medium)',
     'OpenAI mini image generation, 1024x1024, medium quality. $0.011/image.',
     'budget', 104, FALSE, 'image', 'cloud', 'manual', FALSE,
     0.011, 0,
     100000, 5,
     20000, 2,
     now(), now()),
    ('openai', 'gpt-image-1-mini-high',
     TRUE, 'GPT Image 1 Mini (High)',
     'OpenAI mini image generation, 1024x1024, high quality. $0.036/image.',
     'mid', 105, FALSE, 'image', 'cloud', 'manual', FALSE,
     0.036, 0,
     100000, 5,
     20000, 2,
     now(), now()),
    -- ── Google Gemini image (Nano Banana, Nano Banana Pro) ─────────────────
    ('google', 'gemini-2.5-flash-image',
     TRUE, 'Gemini 2.5 Flash Image',
     'Google low-latency image generation ("nano-banana"), 1024x1024. $0.039/image.',
     'mid', 106, TRUE, 'image', 'cloud', 'manual', FALSE,
     0.039, 0,
     1000000, 1000,
     200000, 200,
     now(), now()),
    ('google', 'gemini-3-pro-image',
     TRUE, 'Gemini 3 Pro Image',
     'Google high-fidelity image generation, 1k-2k square tier. $0.134/image.',
     'high', 107, FALSE, 'image', 'cloud', 'manual', FALSE,
     0.134, 0,
     1000000, 1000,
     200000, 200,
     now(), now())
ON CONFLICT (provider, model_id) DO UPDATE
SET mode                       = EXCLUDED.mode,
    provider_kind              = EXCLUDED.provider_kind,
    -- Price + rate limit columns: bring them in line with the seed unless an
    -- admin already edited them (user_modified_fields tracks that). We
    -- overwrite NULLs unconditionally so V157-leftover NULLs become real
    -- values, and overwrite stale seed values when no admin edit is recorded.
    price_input                = CASE
        WHEN model_config_overrides.price_input IS NULL
             OR NOT ('priceInput' = ANY(model_config_overrides.user_modified_fields))
        THEN EXCLUDED.price_input
        ELSE model_config_overrides.price_input
    END,
    rate_limit_tpm             = CASE
        WHEN model_config_overrides.rate_limit_tpm IS NULL
             OR NOT ('rateLimitTpm' = ANY(model_config_overrides.user_modified_fields))
        THEN EXCLUDED.rate_limit_tpm
        ELSE model_config_overrides.rate_limit_tpm
    END,
    rate_limit_rpm             = CASE
        WHEN model_config_overrides.rate_limit_rpm IS NULL
             OR NOT ('rateLimitRpm' = ANY(model_config_overrides.user_modified_fields))
        THEN EXCLUDED.rate_limit_rpm
        ELSE model_config_overrides.rate_limit_rpm
    END,
    rate_limit_tpm_per_tenant  = CASE
        WHEN model_config_overrides.rate_limit_tpm_per_tenant IS NULL
             OR NOT ('rateLimitTpmPerTenant' = ANY(model_config_overrides.user_modified_fields))
        THEN EXCLUDED.rate_limit_tpm_per_tenant
        ELSE model_config_overrides.rate_limit_tpm_per_tenant
    END,
    rate_limit_rpm_per_tenant  = CASE
        WHEN model_config_overrides.rate_limit_rpm_per_tenant IS NULL
             OR NOT ('rateLimitRpmPerTenant' = ANY(model_config_overrides.user_modified_fields))
        THEN EXCLUDED.rate_limit_rpm_per_tenant
        ELSE model_config_overrides.rate_limit_rpm_per_tenant
    END,
    -- Display fields only realigned when nothing was admin-edited.
    display_name = CASE
        WHEN model_config_overrides.source = 'manual'
             AND COALESCE(array_length(model_config_overrides.user_modified_fields, 1), 0) = 0
        THEN EXCLUDED.display_name
        ELSE model_config_overrides.display_name
    END,
    description  = CASE
        WHEN model_config_overrides.source = 'manual'
             AND COALESCE(array_length(model_config_overrides.user_modified_fields, 1), 0) = 0
        THEN EXCLUDED.description
        ELSE model_config_overrides.description
    END,
    updated_at   = now();

-- 2. Mirror gpt-image-2 into auth.model_pricing (V141 seeded the others).
-- Per-image credits = USD × 1000 (1 credit ≈ $0.001).
INSERT INTO auth.model_pricing
    (provider, model, input_rate, output_rate, fixed_cost, effective_from, is_active, provider_kind)
VALUES
    ('openai', 'gpt-image-2-low',      6, 0, 0, CURRENT_DATE, true, 'cloud'),
    ('openai', 'gpt-image-2-medium',  53, 0, 0, CURRENT_DATE, true, 'cloud'),
    ('openai', 'gpt-image-2-high',   211, 0, 0, CURRENT_DATE, true, 'cloud')
ON CONFLICT (provider, model, effective_from)
DO UPDATE SET input_rate    = EXCLUDED.input_rate,
              output_rate   = EXCLUDED.output_rate,
              fixed_cost    = EXCLUDED.fixed_cost,
              is_active     = true,
              provider_kind = EXCLUDED.provider_kind;

-- 3. Sidecar backfill - every image-gen row gets a category='image_generation'
-- entry. ON CONFLICT DO NOTHING so admin-edited rows (rank/enabled changes)
-- survive a re-run.
INSERT INTO model_category_settings (model_config_id, category, rank, enabled)
SELECT id,
       'image_generation',
       ranking,
       COALESCE(enabled, TRUE)
FROM   model_config_overrides
WHERE  mode = 'image'
  AND  deprecated_at IS NULL
ON CONFLICT (model_config_id, category) DO NOTHING;
