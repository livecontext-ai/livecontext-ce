-- Fix model pricing rates: align to 1 credit = $0.001 USD.
-- Rate = USD per 1M tokens (provider list price).
-- Formula: credits = rate × tokens / 1000.
--
-- V4 seed used rates that were 10x too low (old scale where 1 credit = $0.01).
-- This migration multiplies all existing rates by 10 to match the new scale,
-- then upserts any models that were missing or had wrong prices.

-- Step 1: multiply active rates by 10 (V4 scale → new scale)
-- Only active records are updated to avoid corrupting historical audit data.
UPDATE auth.model_pricing
SET input_rate  = input_rate * 10,
    output_rate = output_rate * 10
WHERE is_active = true;

-- Step 2: upsert correct prices for all known models (covers new models + fixes any rounding)
-- Uses ON CONFLICT to update existing rows.
INSERT INTO auth.model_pricing (provider, model, input_rate, output_rate, fixed_cost, effective_from, is_active)
VALUES
    -- OpenAI
    ('openai', 'gpt-5.4',       2.50, 15.00, 0, CURRENT_DATE, true),
    ('openai', 'gpt-5.4-mini',  0.75,  4.50, 0, CURRENT_DATE, true),
    ('openai', 'gpt-5.2',       1.75, 14.00, 0, CURRENT_DATE, true),
    ('openai', 'gpt-5',         1.25, 10.00, 0, CURRENT_DATE, true),
    ('openai', 'gpt-5-mini',    0.25,  2.00, 0, CURRENT_DATE, true),
    ('openai', 'gpt-5.3-codex', 2.00, 12.00, 0, CURRENT_DATE, true),
    ('openai', 'o4-mini',       1.10,  4.40, 0, CURRENT_DATE, true),
    ('openai', 'gpt-4o',        2.50, 10.00, 0, CURRENT_DATE, true),
    ('openai', 'gpt-4o-mini',   0.15,  0.60, 0, CURRENT_DATE, true),
    ('openai', 'gpt-4.1',       2.00,  8.00, 0, CURRENT_DATE, true),
    ('openai', 'gpt-4.1-mini',  0.40,  1.60, 0, CURRENT_DATE, true),
    ('openai', 'gpt-4.1-nano',  0.10,  0.40, 0, CURRENT_DATE, true),
    ('openai', 'gpt-4-turbo',  10.00, 30.00, 0, CURRENT_DATE, true),
    ('openai', 'o3',           10.00, 40.00, 0, CURRENT_DATE, true),
    ('openai', 'o3-mini',       1.10,  4.40, 0, CURRENT_DATE, true),
    -- Anthropic
    ('anthropic', 'claude-opus-4-6',           5.00, 25.00, 0, CURRENT_DATE, true),
    ('anthropic', 'claude-sonnet-4-6',         3.00, 15.00, 0, CURRENT_DATE, true),
    ('anthropic', 'claude-haiku-4-5',          1.00,  5.00, 0, CURRENT_DATE, true),
    ('anthropic', 'claude-opus-4-6-cc',        5.00, 25.00, 0, CURRENT_DATE, true),
    ('anthropic', 'claude-sonnet-4-6-cc',      3.00, 15.00, 0, CURRENT_DATE, true),
    ('anthropic', 'claude-3-5-sonnet-20241022', 3.00, 15.00, 0, CURRENT_DATE, true),
    ('anthropic', 'claude-3-opus-20240229',    15.00, 75.00, 0, CURRENT_DATE, true),
    ('anthropic', 'claude-3-sonnet-20240229',   3.00, 15.00, 0, CURRENT_DATE, true),
    ('anthropic', 'claude-3-haiku-20240307',    0.25,  1.25, 0, CURRENT_DATE, true),
    -- Google
    ('google', 'gemini-2.5-pro',          1.25, 10.00, 0, CURRENT_DATE, true),
    ('google', 'gemini-2.5-flash',        0.30,  2.50, 0, CURRENT_DATE, true),
    ('google', 'gemini-3.1-pro-preview',  2.00, 12.00, 0, CURRENT_DATE, true),
    ('google', 'gemini-3.1-flash',        0.30,  2.50, 0, CURRENT_DATE, true),
    ('google', 'gemini-3.1-flash-lite',   0.25,  1.50, 0, CURRENT_DATE, true),
    ('google', 'gemini-2.5-pro-preview',  1.25, 10.00, 0, CURRENT_DATE, true),
    ('google', 'gemini-2.5-flash-preview', 0.30, 2.50, 0, CURRENT_DATE, true),
    ('google', 'gemini-3-pro-preview',    1.25, 10.00, 0, CURRENT_DATE, true),
    ('google', 'gemini-3-flash-preview',  0.50,  3.00, 0, CURRENT_DATE, true),
    ('google', 'gemini-2.0-flash',        0.10,  0.40, 0, CURRENT_DATE, true),
    ('google', 'gemini-1.5-pro',          1.25,  5.00, 0, CURRENT_DATE, true),
    ('google', 'gemini-1.5-flash',        0.075, 0.30, 0, CURRENT_DATE, true),
    ('google', 'gemini-pro',              0.50,  1.50, 0, CURRENT_DATE, true),
    -- Mistral
    ('mistral', 'mistral-large-latest',   2.00,  6.00, 0, CURRENT_DATE, true),
    ('mistral', 'mistral-medium-3',       1.00,  3.00, 0, CURRENT_DATE, true),
    ('mistral', 'mistral-small-latest',   0.20,  0.60, 0, CURRENT_DATE, true),
    ('mistral', 'codestral-latest',       0.30,  0.90, 0, CURRENT_DATE, true),
    ('mistral', 'devstral-2',             0.40,  2.00, 0, CURRENT_DATE, true),
    ('mistral', 'devstral-small-2',       0.10,  0.30, 0, CURRENT_DATE, true),
    ('mistral', 'mistral-medium-latest',  1.00,  3.00, 0, CURRENT_DATE, true),
    -- DeepSeek
    ('deepseek', 'deepseek-chat',         0.28,  0.42, 0, CURRENT_DATE, true),
    ('deepseek', 'deepseek-coder',        0.28,  0.42, 0, CURRENT_DATE, true),
    ('deepseek', 'deepseek-reasoner',     0.28,  0.42, 0, CURRENT_DATE, true)
ON CONFLICT (provider, model, effective_from)
DO UPDATE SET input_rate  = EXCLUDED.input_rate,
              output_rate = EXCLUDED.output_rate,
              is_active   = true;
