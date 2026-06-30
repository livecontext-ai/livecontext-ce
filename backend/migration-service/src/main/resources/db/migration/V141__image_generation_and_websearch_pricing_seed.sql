-- Seed billing rows for two new agent capabilities:
--   * web_search        - flat per-call billing (1 credit / search)
--   * image_generation  - per-image billing (gpt-image-1 low/med/high, gemini-2.5-flash-image)
--
-- Two billing paths
-- -----------------
-- Web search uses the existing ModelPricingService.calculateCost(p, m, 0, 0)
-- path, which returns just fixed_cost when both token counts are 0:
--     cost = input_rate × 0 / 1000 + output_rate × 0 / 1000 + fixed_cost
-- We therefore set input_rate=0, output_rate=0, fixed_cost=1.
--
-- Image generation introduces a new helper
--     ModelPricingService.calculateUnitCost(p, m, n) = input_rate × n + fixed_cost
-- (NO /1000 divisor) because per-image costs scale linearly with `n`.
-- gpt-image-1 supports 1..10 images per call; the actual count returned by
-- the provider (which may be < requested due to per-image content-mod) is
-- what gets billed.  We therefore store the per-image cost in input_rate
-- and leave fixed_cost = 0.
--
-- Pseudo-model billing keys
-- -------------------------
-- gpt-image-1-low / -medium / -high are NOT real OpenAI model names - they
-- are billing-only discriminators. The HTTP request to OpenAI sends
--     model='gpt-image-1', quality=low|medium|high
-- as separate fields. OpenAIImageProvider.toBillingModel(quality) maps the
-- quality flag to the pseudo-model used as the pricing lookup key. This
-- keeps the pricing table normalised on the cost dimension instead of
-- forcing the billing layer to understand provider-specific quality
-- semantics.
--
-- Idempotent: ON CONFLICT (provider, model, effective_from) DO UPDATE
-- realigns rates if a drifted row exists.
--
-- Guard invariant: ImageProvidersHavePricingTest enumerates the expected
-- (provider, model, input_rate, fixed_cost) tuples from a Java constant
-- and asserts every pair is present in this migration.

SET lock_timeout = '10s';
SET statement_timeout = '60s';

-- Pricing convention: 1 credit ≈ USD $0.001 (millicent). Matches the chat
-- billing convention where input_rate is "USD per 1M tokens" and the
-- formula `input_rate × tokens / 1000` produces credits-equivalent. For
-- image-gen the per-image USD price (e.g. $0.034) maps directly to a
-- per-image credit count (34) that calculateUnitCost reads from input_rate.
--
-- Sources (verified 2026-04-27):
--   * OpenAI gpt-image-1.5: $0.009 / $0.034 / $0.133 (low/med/high, 1024x1024)
--   * OpenAI gpt-image-1-mini: $0.005 / $0.011 / $0.036
--   * Google gemini-2.5-flash-image (nano-banana): $0.039 (1024x1024)
--   * Google gemini-3-pro-image: $0.134 (1k-2k tier, square)
--
-- Pseudo-model billing keys: encode the cost-affecting axis (quality tier)
-- in the model name so a single (provider, model) lookup resolves the right
-- per-image rate. The HTTP request to OpenAI sends model='gpt-image-1.5' +
-- quality='low' as separate fields; the OpenAIImageProvider.toBillingModel
-- helper maps the quality flag back to the pseudo-model key here.
--
-- Square (1024x1024) only in this seed - portrait/landscape pricing is
-- ~50% higher and warrants its own pseudo-model row when those sizes are
-- supported in the module. Limiting v1 to square keeps the catalog small.

INSERT INTO auth.model_pricing
    (provider, model, input_rate, output_rate, fixed_cost, effective_from, is_active, provider_kind)
VALUES
    -- Web search (flat 1 credit / search; uses fixed_cost path)
    ('websearch',  'default',                       0, 0,   1, CURRENT_DATE, true, 'cloud'),
    -- OpenAI gpt-image-1.5 (current default, square 1024x1024)
    ('openai',     'gpt-image-1.5-low',             9, 0,   0, CURRENT_DATE, true, 'cloud'),
    ('openai',     'gpt-image-1.5-medium',         34, 0,   0, CURRENT_DATE, true, 'cloud'),
    ('openai',     'gpt-image-1.5-high',          133, 0,   0, CURRENT_DATE, true, 'cloud'),
    -- OpenAI gpt-image-1-mini (cheapest tier, square 1024x1024)
    ('openai',     'gpt-image-1-mini-low',          5, 0,   0, CURRENT_DATE, true, 'cloud'),
    ('openai',     'gpt-image-1-mini-medium',      11, 0,   0, CURRENT_DATE, true, 'cloud'),
    ('openai',     'gpt-image-1-mini-high',        36, 0,   0, CURRENT_DATE, true, 'cloud'),
    -- Google Gemini 2.5 Flash Image ("nano-banana"; single tier)
    ('google',     'gemini-2.5-flash-image',       39, 0,   0, CURRENT_DATE, true, 'cloud'),
    -- Google Gemini 3 Pro Image (1k-2k tier, square)
    ('google',     'gemini-3-pro-image',          134, 0,   0, CURRENT_DATE, true, 'cloud')
ON CONFLICT (provider, model, effective_from)
DO UPDATE SET input_rate    = EXCLUDED.input_rate,
              output_rate   = EXCLUDED.output_rate,
              fixed_cost    = EXCLUDED.fixed_cost,
              is_active     = true,
              provider_kind = EXCLUDED.provider_kind;
