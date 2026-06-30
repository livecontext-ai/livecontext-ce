-- Curated v1 seed for agent.model_config_overrides (catalog source of truth).
-- Mirrors the (provider, model, input_rate, output_rate) pairs already seeded
-- into auth.model_pricing by V80, so both schemas stay consistent.
--
-- Prices: USD per 1M tokens (provider list price).
-- Trigger derive_model_credits() populates credits_input / credits_output automatically.
--
-- display_name defaults to model_id (admin can refine from the UI).
-- tier / description / context_window remain NULL here - enriched later via
-- cloud bundle (PR2), admin panel, or scripts/models/sync_openrouter.py.
--
-- Idempotency: ON CONFLICT DO NOTHING - any row already edited by an admin
-- (present in DB pre-V109) is left untouched; only NEW provider+model pairs
-- are inserted.

SET lock_timeout = '10s';
SET statement_timeout = '120s';
SET search_path TO agent;

INSERT INTO model_config_overrides
    (provider, model_id, display_name, enabled, source, bundle_version,
     price_input, price_output, last_synced_at)
VALUES
    -- OpenAI
    ('openai',    'gpt-5.4',                    'gpt-5.4',                    TRUE, 'curated', 1,  2.50, 15.00, NOW()),
    ('openai',    'gpt-5.4-mini',               'gpt-5.4-mini',               TRUE, 'curated', 1,  0.75,  4.50, NOW()),
    ('openai',    'gpt-5.2',                    'gpt-5.2',                    TRUE, 'curated', 1,  1.75, 14.00, NOW()),
    ('openai',    'gpt-5',                      'gpt-5',                      TRUE, 'curated', 1,  1.25, 10.00, NOW()),
    ('openai',    'gpt-5-mini',                 'gpt-5-mini',                 TRUE, 'curated', 1,  0.25,  2.00, NOW()),
    ('openai',    'gpt-5.3-codex',              'gpt-5.3-codex',              TRUE, 'curated', 1,  2.00, 12.00, NOW()),
    ('openai',    'o4-mini',                    'o4-mini',                    TRUE, 'curated', 1,  1.10,  4.40, NOW()),
    ('openai',    'gpt-4o',                     'gpt-4o',                     TRUE, 'curated', 1,  2.50, 10.00, NOW()),
    ('openai',    'gpt-4o-mini',                'gpt-4o-mini',                TRUE, 'curated', 1,  0.15,  0.60, NOW()),
    ('openai',    'gpt-4.1',                    'gpt-4.1',                    TRUE, 'curated', 1,  2.00,  8.00, NOW()),
    ('openai',    'gpt-4.1-mini',               'gpt-4.1-mini',               TRUE, 'curated', 1,  0.40,  1.60, NOW()),
    ('openai',    'gpt-4.1-nano',               'gpt-4.1-nano',               TRUE, 'curated', 1,  0.10,  0.40, NOW()),
    ('openai',    'gpt-4-turbo',                'gpt-4-turbo',                TRUE, 'curated', 1, 10.00, 30.00, NOW()),
    ('openai',    'o3',                         'o3',                         TRUE, 'curated', 1, 10.00, 40.00, NOW()),
    ('openai',    'o3-mini',                    'o3-mini',                    TRUE, 'curated', 1,  1.10,  4.40, NOW()),
    -- Anthropic
    ('anthropic', 'claude-opus-4-6',            'claude-opus-4-6',            TRUE, 'curated', 1,  5.00, 25.00, NOW()),
    ('anthropic', 'claude-sonnet-4-6',          'claude-sonnet-4-6',          TRUE, 'curated', 1,  3.00, 15.00, NOW()),
    ('anthropic', 'claude-haiku-4-5',           'claude-haiku-4-5',           TRUE, 'curated', 1,  1.00,  5.00, NOW()),
    ('anthropic', 'claude-opus-4-6-cc',         'claude-opus-4-6-cc',         TRUE, 'curated', 1,  5.00, 25.00, NOW()),
    ('anthropic', 'claude-sonnet-4-6-cc',       'claude-sonnet-4-6-cc',       TRUE, 'curated', 1,  3.00, 15.00, NOW()),
    ('anthropic', 'claude-3-5-sonnet-20241022', 'claude-3-5-sonnet-20241022', TRUE, 'curated', 1,  3.00, 15.00, NOW()),
    ('anthropic', 'claude-3-opus-20240229',     'claude-3-opus-20240229',     TRUE, 'curated', 1, 15.00, 75.00, NOW()),
    ('anthropic', 'claude-3-sonnet-20240229',   'claude-3-sonnet-20240229',   TRUE, 'curated', 1,  3.00, 15.00, NOW()),
    ('anthropic', 'claude-3-haiku-20240307',    'claude-3-haiku-20240307',    TRUE, 'curated', 1,  0.25,  1.25, NOW()),
    -- Google
    ('google',    'gemini-2.5-pro',             'gemini-2.5-pro',             TRUE, 'curated', 1,  1.25, 10.00, NOW()),
    ('google',    'gemini-2.5-flash',           'gemini-2.5-flash',           TRUE, 'curated', 1,  0.30,  2.50, NOW()),
    ('google',    'gemini-3.1-pro-preview',     'gemini-3.1-pro-preview',     TRUE, 'curated', 1,  2.00, 12.00, NOW()),
    ('google',    'gemini-3.1-flash',           'gemini-3.1-flash',           TRUE, 'curated', 1,  0.30,  2.50, NOW()),
    ('google',    'gemini-3.1-flash-lite',      'gemini-3.1-flash-lite',      TRUE, 'curated', 1,  0.25,  1.50, NOW()),
    ('google',    'gemini-2.5-pro-preview',     'gemini-2.5-pro-preview',     TRUE, 'curated', 1,  1.25, 10.00, NOW()),
    ('google',    'gemini-2.5-flash-preview',   'gemini-2.5-flash-preview',   TRUE, 'curated', 1,  0.30,  2.50, NOW()),
    ('google',    'gemini-3-pro-preview',       'gemini-3-pro-preview',       TRUE, 'curated', 1,  1.25, 10.00, NOW()),
    ('google',    'gemini-3-flash-preview',     'gemini-3-flash-preview',     TRUE, 'curated', 1,  0.50,  3.00, NOW()),
    ('google',    'gemini-2.0-flash',           'gemini-2.0-flash',           TRUE, 'curated', 1,  0.10,  0.40, NOW()),
    ('google',    'gemini-1.5-pro',             'gemini-1.5-pro',             TRUE, 'curated', 1,  1.25,  5.00, NOW()),
    ('google',    'gemini-1.5-flash',           'gemini-1.5-flash',           TRUE, 'curated', 1,  0.075, 0.30, NOW()),
    ('google',    'gemini-pro',                 'gemini-pro',                 TRUE, 'curated', 1,  0.50,  1.50, NOW()),
    -- Mistral
    ('mistral',   'mistral-large-latest',       'mistral-large-latest',       TRUE, 'curated', 1,  2.00,  6.00, NOW()),
    ('mistral',   'mistral-medium-3',           'mistral-medium-3',           TRUE, 'curated', 1,  1.00,  3.00, NOW()),
    ('mistral',   'mistral-small-latest',       'mistral-small-latest',       TRUE, 'curated', 1,  0.20,  0.60, NOW()),
    ('mistral',   'codestral-latest',           'codestral-latest',           TRUE, 'curated', 1,  0.30,  0.90, NOW()),
    ('mistral',   'devstral-2',                 'devstral-2',                 TRUE, 'curated', 1,  0.40,  2.00, NOW()),
    ('mistral',   'devstral-small-2',           'devstral-small-2',           TRUE, 'curated', 1,  0.10,  0.30, NOW()),
    ('mistral',   'mistral-medium-latest',      'mistral-medium-latest',      TRUE, 'curated', 1,  1.00,  3.00, NOW()),
    -- DeepSeek
    ('deepseek',  'deepseek-chat',              'deepseek-chat',              TRUE, 'curated', 1,  0.28,  0.42, NOW()),
    ('deepseek',  'deepseek-coder',             'deepseek-coder',             TRUE, 'curated', 1,  0.28,  0.42, NOW()),
    ('deepseek',  'deepseek-reasoner',          'deepseek-reasoner',          TRUE, 'curated', 1,  0.28,  0.42, NOW()),
    -- xAI
    ('xai',       'grok-3-beta',                'grok-3-beta',                TRUE, 'curated', 1,  3.00, 15.00, NOW()),
    ('xai',       'grok-3-mini-beta',           'grok-3-mini-beta',           TRUE, 'curated', 1,  0.30,  0.50, NOW()),
    -- Perplexity
    ('perplexity','sonar-pro',                  'sonar-pro',                  TRUE, 'curated', 1,  3.00, 15.00, NOW()),
    ('perplexity','sonar-reasoning-pro',        'sonar-reasoning-pro',        TRUE, 'curated', 1,  2.00,  8.00, NOW()),
    -- Cohere
    ('cohere',    'command-r-plus-08-2024',     'command-r-plus-08-2024',     TRUE, 'curated', 1,  2.50, 10.00, NOW()),
    ('cohere',    'command-r-08-2024',          'command-r-08-2024',          TRUE, 'curated', 1,  0.15,  0.60, NOW()),
    -- z.ai (GLM) - provider key 'zai' (matches prod auth.model_pricing + agent-service routing).
    -- Prices mirror the live auth.model_pricing rows seeded at provider onboarding.
    ('zai',       'glm-5.1',                    'glm-5.1',                    TRUE, 'curated', 1,  1.40,  4.40, NOW()),
    ('zai',       'glm-5',                      'glm-5',                      TRUE, 'curated', 1,  1.00,  3.20, NOW()),
    ('zai',       'glm-5-turbo',                'glm-5-turbo',                TRUE, 'curated', 1,  1.20,  4.00, NOW()),
    -- OpenRouter (aggregator - prices mirror the underlying upstream)
    ('openrouter','anthropic/claude-sonnet-4-20250514', 'anthropic/claude-sonnet-4-20250514', TRUE, 'curated', 1, 3.00, 15.00, NOW()),
    ('openrouter','openai/gpt-5.4',             'openai/gpt-5.4',             TRUE, 'curated', 1,  2.50, 15.00, NOW()),
    ('openrouter','google/gemini-3-pro-preview','google/gemini-3-pro-preview',TRUE, 'curated', 1,  1.25, 10.00, NOW())
ON CONFLICT (provider, model_id) DO NOTHING;
