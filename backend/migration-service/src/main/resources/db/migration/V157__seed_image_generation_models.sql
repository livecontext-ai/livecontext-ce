-- ---------------------------------------------------------------------------
-- V157: Seed image-generation models in agent.model_config_overrides
--
-- Until V156 the image-gen catalog lived ONLY in:
--   * a Java constant (ImageProviderCatalog.ALL - 8 entries)
--   * auth.model_pricing rows seeded by V141 (billing only)
--
-- That meant image-gen models had no presence in the per-category ranking /
-- enable system, and the catalog-bundle pipeline (which serializes
-- model_config_overrides) couldn't distribute them cloud → CE.
--
-- This migration:
--   1. Inserts the 8 image-gen rows into model_config_overrides with
--      mode='image' and provider_kind='cloud' (matching V141's billing rows).
--   2. Backfills model_category_settings(category='image_generation') so the
--      sidecar from V156 covers all 3 initial categories on a fresh deploy.
--
-- Source of truth alignment: the (provider, model_id, display_name, tier)
-- tuples below MUST stay in sync with ImageProviderCatalog.ALL. The
-- ImageProvidersHavePricingTest drift guard is extended in a follow-up to
-- assert this table also contains every entry.
-- ---------------------------------------------------------------------------

SET search_path = agent, public;

-- 1. Insert image-gen rows. ON CONFLICT keeps existing customizations.
INSERT INTO model_config_overrides
    (provider, model_id, enabled, display_name, description, tier, ranking,
     recommended, mode, provider_kind, source, is_custom,
     created_at, updated_at)
VALUES
    -- OpenAI gpt-image-1.5 (3 quality tiers, ranked low → high)
    ('openai', 'gpt-image-1.5-low',
     TRUE, 'GPT Image 1.5 (Low)',
     'OpenAI image generation, square 1024x1024, low quality. $0.009/image.',
     'budget', 100, FALSE, 'image', 'cloud', 'manual', FALSE, now(), now()),
    ('openai', 'gpt-image-1.5-medium',
     TRUE, 'GPT Image 1.5 (Medium)',
     'OpenAI image generation, square 1024x1024, medium quality. $0.034/image.',
     'mid', 101, TRUE, 'image', 'cloud', 'manual', FALSE, now(), now()),
    ('openai', 'gpt-image-1.5-high',
     TRUE, 'GPT Image 1.5 (High)',
     'OpenAI image generation, square 1024x1024, high quality. $0.133/image.',
     'high', 102, FALSE, 'image', 'cloud', 'manual', FALSE, now(), now()),
    -- OpenAI gpt-image-1-mini (cheapest tier)
    ('openai', 'gpt-image-1-mini-low',
     TRUE, 'GPT Image 1 Mini (Low)',
     'OpenAI mini image generation, square 1024x1024, low quality. $0.005/image.',
     'budget', 103, FALSE, 'image', 'cloud', 'manual', FALSE, now(), now()),
    ('openai', 'gpt-image-1-mini-medium',
     TRUE, 'GPT Image 1 Mini (Medium)',
     'OpenAI mini image generation, square 1024x1024, medium quality. $0.011/image.',
     'budget', 104, FALSE, 'image', 'cloud', 'manual', FALSE, now(), now()),
    ('openai', 'gpt-image-1-mini-high',
     TRUE, 'GPT Image 1 Mini (High)',
     'OpenAI mini image generation, square 1024x1024, high quality. $0.036/image.',
     'mid', 105, FALSE, 'image', 'cloud', 'manual', FALSE, now(), now()),
    -- Google Gemini image
    ('google', 'gemini-2.5-flash-image',
     TRUE, 'Gemini 2.5 Flash Image',
     'Google low-latency image generation ("nano-banana"), 1024x1024. $0.039/image.',
     'mid', 106, TRUE, 'image', 'cloud', 'manual', FALSE, now(), now()),
    ('google', 'gemini-3-pro-image',
     TRUE, 'Gemini 3 Pro Image',
     'Google high-fidelity image generation, 1k-2k square tier. $0.134/image.',
     'high', 107, FALSE, 'image', 'cloud', 'manual', FALSE, now(), now())
ON CONFLICT (provider, model_id) DO UPDATE
SET mode          = EXCLUDED.mode,
    provider_kind = EXCLUDED.provider_kind,
    -- Re-align display fields only when the existing row is still the
    -- factory default (source='manual' AND no custom edits). Admin-edited
    -- rows keep their values so re-running this migration on a deploy that
    -- already has tweaks doesn't stomp them.
    display_name  = CASE
        WHEN model_config_overrides.source = 'manual'
             AND COALESCE(array_length(model_config_overrides.user_modified_fields, 1), 0) = 0
        THEN EXCLUDED.display_name
        ELSE model_config_overrides.display_name
    END,
    description   = CASE
        WHEN model_config_overrides.source = 'manual'
             AND COALESCE(array_length(model_config_overrides.user_modified_fields, 1), 0) = 0
        THEN EXCLUDED.description
        ELSE model_config_overrides.description
    END,
    updated_at    = now();

-- 2. Backfill image_generation category settings. Same shape as V156's
-- chat / browser_agent backfill - sidecar row inherits the row's ranking
-- + enabled at insert time. ON CONFLICT skip preserves admin edits.
INSERT INTO model_category_settings (model_config_id, category, rank, enabled)
SELECT id,
       'image_generation',
       ranking,
       COALESCE(enabled, TRUE)
FROM   model_config_overrides
WHERE  mode = 'image'
  AND  deprecated_at IS NULL
ON CONFLICT (model_config_id, category) DO NOTHING;
