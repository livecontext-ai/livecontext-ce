-- ============================================================================
-- V4: Seed auth data (plans, prices, model pricing)
-- All inserts are idempotent with ON CONFLICT DO NOTHING.
-- ============================================================================

SET search_path TO auth;

-- Plans
INSERT INTO plan (code, name, description, included_tool_credits, included_llm_tokens, included_storage_bytes) VALUES ('FREE', 'Free', 'Perfect for getting started', 1000, 1000, 104857600) ON CONFLICT (code) DO NOTHING;
INSERT INTO plan (code, name, description, included_tool_credits, included_llm_tokens, included_storage_bytes) VALUES ('STARTER', 'Starter', 'For individual developers', 5000, NULL, 1073741824) ON CONFLICT (code) DO NOTHING;
INSERT INTO plan (code, name, description, included_tool_credits, included_llm_tokens, included_storage_bytes) VALUES ('PRO', 'Pro', 'For small teams and projects', 5000, NULL, 10737418240) ON CONFLICT (code) DO NOTHING;
INSERT INTO plan (code, name, description, included_tool_credits, included_llm_tokens, included_storage_bytes) VALUES ('TEAM', 'Team', 'For growing teams', 5000, NULL, 107374182400) ON CONFLICT (code) DO NOTHING;
INSERT INTO plan (code, name, description, included_tool_credits, included_llm_tokens, included_storage_bytes) VALUES ('ENTERPRISE_BASIC', 'Enterprise Basic', 'Basic enterprise solution', 50000, NULL, 536870912000) ON CONFLICT (code) DO NOTHING;
INSERT INTO plan (code, name, description, included_tool_credits, included_llm_tokens, included_storage_bytes) VALUES ('ENTERPRISE_STANDARD', 'Enterprise Standard', 'Standard enterprise solution', 100000, NULL, 1073741824000) ON CONFLICT (code) DO NOTHING;
INSERT INTO plan (code, name, description, included_tool_credits, included_llm_tokens, included_storage_bytes) VALUES ('ENTERPRISE_PREMIUM', 'Enterprise Premium', 'Premium enterprise solution', 250000, NULL, 2684354560000) ON CONFLICT (code) DO NOTHING;
INSERT INTO plan (code, name, description, included_tool_credits, included_llm_tokens, included_storage_bytes) VALUES ('ENTERPRISE_ULTIMATE', 'Enterprise Ultimate', 'Ultimate enterprise solution', 500000, NULL, 5368709120000) ON CONFLICT (code) DO NOTHING;
INSERT INTO plan (code, name, description, included_tool_credits, included_llm_tokens, included_storage_bytes, payg_price_per_tool_credit, payg_price_per_llm_token, payg_price_per_gb_month) VALUES ('PAYG', 'Pay As You Go', 'Pay only for what you use', 0, NULL, 5368709120, 5, 1, 1000) ON CONFLICT (code) DO NOTHING;
INSERT INTO plan (code, name, description, included_tool_credits, included_llm_tokens, included_storage_bytes) VALUES ('CREDIT_PACK', 'Credit Pack', 'Standard credit pack', 0, NULL, 0) ON CONFLICT (code) DO NOTHING;
INSERT INTO plan (code, name, description, included_tool_credits, included_llm_tokens, included_storage_bytes) VALUES ('CREDIT_PACK_TEAM', 'Credit Pack Team', 'Team rate credit pack', 0, NULL, 0) ON CONFLICT (code) DO NOTHING;

-- Prices
INSERT INTO price (plan_id, cadence, amount_cents, currency, provider, provider_price_id) SELECT id, 'monthly', 0, 'usd', 'stripe', NULL FROM plan WHERE code = 'FREE' ON CONFLICT (plan_id, cadence) DO NOTHING;
INSERT INTO price (plan_id, cadence, amount_cents, currency, provider, provider_price_id) SELECT id, 'yearly', 0, 'usd', 'stripe', NULL FROM plan WHERE code = 'FREE' ON CONFLICT (plan_id, cadence) DO NOTHING;
INSERT INTO price (plan_id, cadence, amount_cents, currency, provider, provider_price_id) SELECT id, 'monthly', 1000, 'usd', 'stripe', 'price_1T4RwIGiUBDKoQQaHqQJ7cNy' FROM plan WHERE code = 'STARTER' ON CONFLICT (plan_id, cadence) DO NOTHING;
INSERT INTO price (plan_id, cadence, amount_cents, currency, provider, provider_price_id) SELECT id, 'yearly', 9600, 'usd', 'stripe', 'price_1T4RweGiUBDKoQQa5irDViII' FROM plan WHERE code = 'STARTER' ON CONFLICT (plan_id, cadence) DO NOTHING;
INSERT INTO price (plan_id, cadence, amount_cents, currency, provider, provider_price_id) SELECT id, 'monthly', 2400, 'usd', 'stripe', 'price_1T4RxsGiUBDKoQQaURbul012' FROM plan WHERE code = 'PRO' ON CONFLICT (plan_id, cadence) DO NOTHING;
INSERT INTO price (plan_id, cadence, amount_cents, currency, provider, provider_price_id) SELECT id, 'yearly', 23040, 'usd', 'stripe', 'price_1T4RyDGiUBDKoQQak9TGoeK7' FROM plan WHERE code = 'PRO' ON CONFLICT (plan_id, cadence) DO NOTHING;
INSERT INTO price (plan_id, cadence, amount_cents, currency, provider, provider_price_id) SELECT id, 'monthly', 4900, 'usd', 'stripe', 'price_1T4RzZGiUBDKoQQayoUV6btk' FROM plan WHERE code = 'TEAM' ON CONFLICT (plan_id, cadence) DO NOTHING;
INSERT INTO price (plan_id, cadence, amount_cents, currency, provider, provider_price_id) SELECT id, 'yearly', 47040, 'usd', 'stripe', 'price_1T4RzwGiUBDKoQQaTlfgccxJ' FROM plan WHERE code = 'TEAM' ON CONFLICT (plan_id, cadence) DO NOTHING;
INSERT INTO price (plan_id, cadence, amount_cents, currency, provider, provider_price_id) SELECT id, 'monthly', 50000, 'usd', 'stripe', 'price_1S4FVUGiUBDKoQQadGc1h4t0' FROM plan WHERE code = 'ENTERPRISE_BASIC' ON CONFLICT (plan_id, cadence) DO NOTHING;
INSERT INTO price (plan_id, cadence, amount_cents, currency, provider, provider_price_id) SELECT id, 'yearly', 480000, 'usd', 'stripe', 'price_1S4FViGiUBDKoQQavvjgVA7f' FROM plan WHERE code = 'ENTERPRISE_BASIC' ON CONFLICT (plan_id, cadence) DO NOTHING;
INSERT INTO price (plan_id, cadence, amount_cents, currency, provider, provider_price_id) SELECT id, 'monthly', 100000, 'usd', 'stripe', 'price_1S4FWCGiUBDKoQQaXVzZHWhf' FROM plan WHERE code = 'ENTERPRISE_STANDARD' ON CONFLICT (plan_id, cadence) DO NOTHING;
INSERT INTO price (plan_id, cadence, amount_cents, currency, provider, provider_price_id) SELECT id, 'yearly', 960000, 'usd', 'stripe', 'price_1S4FWYGiUBDKoQQaGbctyxiu' FROM plan WHERE code = 'ENTERPRISE_STANDARD' ON CONFLICT (plan_id, cadence) DO NOTHING;
INSERT INTO price (plan_id, cadence, amount_cents, currency, provider, provider_price_id) SELECT id, 'monthly', 200000, 'usd', 'stripe', 'price_1S4FWzGiUBDKoQQahsT8tzG3' FROM plan WHERE code = 'ENTERPRISE_PREMIUM' ON CONFLICT (plan_id, cadence) DO NOTHING;
INSERT INTO price (plan_id, cadence, amount_cents, currency, provider, provider_price_id) SELECT id, 'yearly', 1920000, 'usd', 'stripe', 'price_1S4FXFGiUBDKoQQaZTt2Pdnv' FROM plan WHERE code = 'ENTERPRISE_PREMIUM' ON CONFLICT (plan_id, cadence) DO NOTHING;
INSERT INTO price (plan_id, cadence, amount_cents, currency, provider, provider_price_id) SELECT id, 'monthly', 500000, 'usd', 'stripe', 'price_1S4FXnGiUBDKoQQaurlEGwEq' FROM plan WHERE code = 'ENTERPRISE_ULTIMATE' ON CONFLICT (plan_id, cadence) DO NOTHING;
INSERT INTO price (plan_id, cadence, amount_cents, currency, provider, provider_price_id) SELECT id, 'yearly', 4800000, 'usd', 'stripe', 'price_1S4FY6GiUBDKoQQa85cBPR0V' FROM plan WHERE code = 'ENTERPRISE_ULTIMATE' ON CONFLICT (plan_id, cadence) DO NOTHING;
INSERT INTO price (plan_id, cadence, amount_cents, currency, provider, provider_price_id) SELECT id, 'payg', 0, 'usd', 'stripe', NULL FROM plan WHERE code = 'PAYG' ON CONFLICT (plan_id, cadence) DO NOTHING;
INSERT INTO price (plan_id, cadence, amount_cents, currency, provider, provider_price_id) SELECT id, 'monthly', 100, 'usd', 'stripe', 'price_1T4RayGiUBDKoQQaUL1cylhC' FROM plan WHERE code = 'CREDIT_PACK' ON CONFLICT (plan_id, cadence) DO NOTHING;
INSERT INTO price (plan_id, cadence, amount_cents, currency, provider, provider_price_id) SELECT id, 'yearly', 960, 'usd', 'stripe', 'price_1T4RbcGiUBDKoQQaOHm2daAL' FROM plan WHERE code = 'CREDIT_PACK' ON CONFLICT (plan_id, cadence) DO NOTHING;
INSERT INTO price (plan_id, cadence, amount_cents, currency, provider, provider_price_id) SELECT id, 'monthly', 150, 'usd', 'stripe', 'price_1T4RjbGiUBDKoQQaGfRLvFKp' FROM plan WHERE code = 'CREDIT_PACK_TEAM' ON CONFLICT (plan_id, cadence) DO NOTHING;
INSERT INTO price (plan_id, cadence, amount_cents, currency, provider, provider_price_id) SELECT id, 'yearly', 1440, 'usd', 'stripe', 'price_1T4Rk4GiUBDKoQQamVpPfOEy' FROM plan WHERE code = 'CREDIT_PACK_TEAM' ON CONFLICT (plan_id, cadence) DO NOTHING;

-- Model pricing
INSERT INTO model_pricing (provider, model, input_rate, output_rate) VALUES
    ('openai', 'gpt-4o', 0.25, 1.0),
    ('openai', 'gpt-4o-mini', 0.015, 0.06),
    ('openai', 'gpt-4-turbo', 1.0, 3.0),
    ('openai', 'o3', 1.0, 4.0),
    ('openai', 'o3-mini', 0.11, 0.44),
    ('anthropic', 'claude-3-5-sonnet-20241022', 0.3, 1.5),
    ('anthropic', 'claude-3-opus-20240229', 1.5, 7.5),
    ('anthropic', 'claude-3-haiku-20240307', 0.025, 0.125),
    ('google', 'gemini-1.5-pro', 0.125, 0.5),
    ('google', 'gemini-1.5-flash', 0.0075, 0.03),
    ('mistral', 'mistral-large-latest', 0.2, 0.6),
    ('deepseek', 'deepseek-chat', 0.014, 0.028),
    ('deepseek', 'deepseek-reasoner', 0.055, 0.22),
    ('openai', 'gpt-4.1', 0.20, 0.80),
    ('openai', 'gpt-4.1-mini', 0.04, 0.16),
    ('openai', 'gpt-4.1-nano', 0.01, 0.04),
    ('openai', 'o4-mini', 0.11, 0.44),
    ('anthropic', 'claude-3-sonnet-20240229', 0.30, 1.5),
    ('google', 'gemini-pro', 0.05, 0.15),
    ('mistral', 'mistral-medium-latest', 0.13, 0.40),
    ('mistral', 'mistral-small-latest', 0.02, 0.06),
    ('mistral', 'codestral-latest', 0.10, 0.30),
    ('deepseek', 'deepseek-coder', 0.014, 0.028)
ON CONFLICT (provider, model, effective_from) DO NOTHING;
