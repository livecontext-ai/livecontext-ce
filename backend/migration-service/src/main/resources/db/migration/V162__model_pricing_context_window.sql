-- V162 - Add context_window + max_output_tokens to auth.model_pricing.
--
-- Used by budget guards (TenantBudgetGuard, AgentBudgetGuard, bridge JS
-- budgetGuards.js) to compute `worstCaseSingleIter = cost(contextWindow,
-- maxOutputTokens)` - the absolute upper bound of what a single iteration can
-- spend. This bound closes the step-function projection bug that caused the
-- -11305 incident: moving averages dilute sudden context bursts, but worstCase
-- is invariant.
--
-- Source of truth: this column lives on `auth.model_pricing` per CLAUDE.md
-- "Each service MUST only query its own DB schema" - auth-service can't read
-- agent.model_config_overrides directly. Values seeded below are taken from
-- public provider docs at the time of V162. They are NOT auto-synced from
-- agent.model_config_overrides - adding a model later (via CatalogBundleApplier
-- or ModelCatalogService) requires either a follow-up migration or extending
-- the bundle apply logic to also write here. Until that sync exists, models
-- with NULL context_window fall back to growth-only projection in guards
-- (legacy behavior, identical to pre-V162) - see TenantBudgetGuard.check().
--
-- Nullable on purpose - pre-V162 rows + unknown/seed-missing models. Java/JS
-- guards treat NULL as "unknown" and fall back to growth+lastDelta projection
-- (no worstCase ceiling). The Phase 1C `BUDGET_GUARD_REQUIRE_CTX_WINDOW` flag
-- can flip this to fail-closed once we are certain every active model is
-- seeded - DO NOT enable that flag before confirming 100% catalog coverage.

ALTER TABLE auth.model_pricing
    ADD COLUMN IF NOT EXISTS context_window    INTEGER,
    ADD COLUMN IF NOT EXISTS max_output_tokens INTEGER;

COMMENT ON COLUMN auth.model_pricing.context_window IS
    'Max prompt+completion tokens the model can hold. Drives worstCaseSingleIter '
    'in budget guards. Seeded manually in V162 from public provider docs; not '
    'auto-synced from agent.model_config_overrides. NULL = unknown → guards fall '
    'back to growth-only projection (same as pre-V162). Phase 1C flag '
    'BUDGET_GUARD_REQUIRE_CTX_WINDOW flips this to fail-closed.';
COMMENT ON COLUMN auth.model_pricing.max_output_tokens IS
    'Max completion tokens the model can emit in one call. Combined with '
    'context_window to compute worstCaseSingleIter. NULL = unknown.';

-- ───────────────────────────────────────────────────────────────────────────
-- Seed only models that are actually present in V4 / V112 / V116 / V120 /
-- V128. Each UPDATE targets an exact (provider, model) row that exists. Values
-- come from provider docs (Anthropic Sonnet 200K/8K, Opus 200K/8K; OpenAI
-- GPT-4o 128K/16K, GPT-4.1 1M/32K, o3/o4 200K/100K; Google Gemini 1.5 1M/8K,
-- 2.5 1M/65K - preview variants up to 2M; bridge -cc variants get the same
-- shape as the direct API).
-- ───────────────────────────────────────────────────────────────────────────

-- Anthropic direct API rows (V4 + V80 + V116). The modern Claude family on
-- direct API includes the actual incident-class names (claude-opus-4-6,
-- claude-sonnet-4-6, claude-haiku-4-5). Conservative 200K/64K matches
-- Anthropic published non-beta limits.
UPDATE auth.model_pricing SET context_window = 200000, max_output_tokens = 8192
    WHERE provider = 'anthropic' AND model = 'claude-3-5-sonnet-20241022';
UPDATE auth.model_pricing SET context_window = 200000, max_output_tokens = 4096
    WHERE provider = 'anthropic' AND model = 'claude-3-opus-20240229';
UPDATE auth.model_pricing SET context_window = 200000, max_output_tokens = 4096
    WHERE provider = 'anthropic' AND model = 'claude-3-sonnet-20240229';
UPDATE auth.model_pricing SET context_window = 200000, max_output_tokens = 4096
    WHERE provider = 'anthropic' AND model = 'claude-3-haiku-20240307';
UPDATE auth.model_pricing SET context_window = 200000, max_output_tokens = 64000
    WHERE provider = 'anthropic' AND model = 'claude-opus-4-6';
UPDATE auth.model_pricing SET context_window = 200000, max_output_tokens = 64000
    WHERE provider = 'anthropic' AND model = 'claude-sonnet-4-6';
UPDATE auth.model_pricing SET context_window = 200000, max_output_tokens = 64000
    WHERE provider = 'anthropic' AND model = 'claude-haiku-4-5';

-- claude-code bridge (V128 supersedes V120 - drops the `-cc` suffix and uses
-- the canonical CLI ids as the model name). Anthropic Opus/Sonnet via the local
-- CLI accept the beta `max-tokens-3-5-sonnet` header for 64K max output, but
-- the *prompt* context window remains 200K on direct API and 1M on the
-- documented `*-1m` betas. Conservative: 200K ctx for the canonical ids
-- (matches what Anthropic publishes for non-beta), 64K max output.
-- THIS IS THE INCIDENT MODEL - `claude-code/claude-opus-4-6` was the row
-- that drove -11305. ctxWindow MUST be set here for the worstCase ceiling.
UPDATE auth.model_pricing SET context_window = 200000, max_output_tokens = 64000
    WHERE provider = 'claude-code' AND model = 'claude-opus-4-7';
UPDATE auth.model_pricing SET context_window = 200000, max_output_tokens = 64000
    WHERE provider = 'claude-code' AND model = 'claude-opus-4-6';
UPDATE auth.model_pricing SET context_window = 200000, max_output_tokens = 64000
    WHERE provider = 'claude-code' AND model = 'claude-sonnet-4-6';
UPDATE auth.model_pricing SET context_window = 200000, max_output_tokens = 64000
    WHERE provider = 'claude-code' AND model = 'claude-sonnet-4-5';
UPDATE auth.model_pricing SET context_window = 200000, max_output_tokens = 64000
    WHERE provider = 'claude-code' AND model = 'claude-haiku-4-5';

-- OpenAI direct (V4 + V116 + V120)
UPDATE auth.model_pricing SET context_window = 128000, max_output_tokens = 16384
    WHERE provider = 'openai' AND model = 'gpt-4o';
UPDATE auth.model_pricing SET context_window = 128000, max_output_tokens = 16384
    WHERE provider = 'openai' AND model = 'gpt-4o-mini';
UPDATE auth.model_pricing SET context_window = 128000, max_output_tokens = 4096
    WHERE provider = 'openai' AND model = 'gpt-4-turbo';
UPDATE auth.model_pricing SET context_window = 1000000, max_output_tokens = 32768
    WHERE provider = 'openai' AND model = 'gpt-4.1';
UPDATE auth.model_pricing SET context_window = 1000000, max_output_tokens = 32768
    WHERE provider = 'openai' AND model = 'gpt-4.1-mini';
UPDATE auth.model_pricing SET context_window = 1000000, max_output_tokens = 32768
    WHERE provider = 'openai' AND model = 'gpt-4.1-nano';
UPDATE auth.model_pricing SET context_window = 200000, max_output_tokens = 100000
    WHERE provider = 'openai' AND model = 'o3';
UPDATE auth.model_pricing SET context_window = 200000, max_output_tokens = 100000
    WHERE provider = 'openai' AND model = 'o3-mini';
UPDATE auth.model_pricing SET context_window = 200000, max_output_tokens = 100000
    WHERE provider = 'openai' AND model = 'o4-mini';
UPDATE auth.model_pricing SET context_window = 400000, max_output_tokens = 128000
    WHERE provider = 'openai' AND model = 'gpt-5.3-codex';
UPDATE auth.model_pricing SET context_window = 400000, max_output_tokens = 128000
    WHERE provider = 'openai' AND model = 'gpt-5.4';
UPDATE auth.model_pricing SET context_window = 400000, max_output_tokens = 128000
    WHERE provider = 'openai' AND model = 'gpt-5.4-mini';
UPDATE auth.model_pricing SET context_window = 400000, max_output_tokens = 128000
    WHERE provider = 'openai' AND model = 'gpt-5.2';
UPDATE auth.model_pricing SET context_window = 400000, max_output_tokens = 128000
    WHERE provider = 'openai' AND model = 'gpt-5';
UPDATE auth.model_pricing SET context_window = 400000, max_output_tokens = 128000
    WHERE provider = 'openai' AND model = 'gpt-5-mini';

-- Google direct (V4 + V116 + V120)
UPDATE auth.model_pricing SET context_window = 1000000, max_output_tokens = 8192
    WHERE provider = 'google' AND model = 'gemini-1.5-pro';
UPDATE auth.model_pricing SET context_window = 1000000, max_output_tokens = 8192
    WHERE provider = 'google' AND model = 'gemini-1.5-flash';
UPDATE auth.model_pricing SET context_window = 32760, max_output_tokens = 8192
    WHERE provider = 'google' AND model = 'gemini-pro';
UPDATE auth.model_pricing SET context_window = 1000000, max_output_tokens = 65536
    WHERE provider = 'google' AND model = 'gemini-2.5-pro';
UPDATE auth.model_pricing SET context_window = 1000000, max_output_tokens = 65536
    WHERE provider = 'google' AND model = 'gemini-2.5-flash';
UPDATE auth.model_pricing SET context_window = 1000000, max_output_tokens = 65536
    WHERE provider = 'google' AND model = 'gemini-3.1-pro-preview';
UPDATE auth.model_pricing SET context_window = 1000000, max_output_tokens = 65536
    WHERE provider = 'google' AND model = 'gemini-3.1-flash';
UPDATE auth.model_pricing SET context_window = 1000000, max_output_tokens = 65536
    WHERE provider = 'google' AND model = 'gemini-3.1-flash-lite';
UPDATE auth.model_pricing SET context_window = 1000000, max_output_tokens = 65536
    WHERE provider = 'google' AND model = 'gemini-3-pro-preview';
UPDATE auth.model_pricing SET context_window = 1000000, max_output_tokens = 65536
    WHERE provider = 'google' AND model = 'gemini-3-flash-preview';
UPDATE auth.model_pricing SET context_window = 1000000, max_output_tokens = 65536
    WHERE provider = 'google' AND model = 'gemini-2.5-pro-preview';
UPDATE auth.model_pricing SET context_window = 1000000, max_output_tokens = 65536
    WHERE provider = 'google' AND model = 'gemini-2.5-flash-preview';
UPDATE auth.model_pricing SET context_window = 1048576, max_output_tokens = 8192
    WHERE provider = 'google' AND model = 'gemini-2.0-flash';

-- ───────────────────────────────────────────────────────────────────────────
-- Other direct-API providers (V80 + V116). Values from public docs as of
-- 2026-05. Conservative - when uncertain, prefer documented official limit
-- over speculative beta extensions.
-- ───────────────────────────────────────────────────────────────────────────

-- Mistral direct
UPDATE auth.model_pricing SET context_window = 128000, max_output_tokens = 8192
    WHERE provider = 'mistral' AND model = 'mistral-large-latest';
UPDATE auth.model_pricing SET context_window = 128000, max_output_tokens = 8192
    WHERE provider = 'mistral' AND model = 'mistral-medium-latest';
UPDATE auth.model_pricing SET context_window = 128000, max_output_tokens = 8192
    WHERE provider = 'mistral' AND model = 'mistral-medium-3';
UPDATE auth.model_pricing SET context_window = 128000, max_output_tokens = 8192
    WHERE provider = 'mistral' AND model = 'mistral-small-latest';
UPDATE auth.model_pricing SET context_window = 256000, max_output_tokens = 8192
    WHERE provider = 'mistral' AND model = 'codestral-latest';
UPDATE auth.model_pricing SET context_window = 256000, max_output_tokens = 8192
    WHERE provider = 'mistral' AND model = 'devstral-2';
UPDATE auth.model_pricing SET context_window = 128000, max_output_tokens = 8192
    WHERE provider = 'mistral' AND model = 'devstral-small-2';

-- DeepSeek
UPDATE auth.model_pricing SET context_window = 128000, max_output_tokens = 8192
    WHERE provider = 'deepseek' AND model = 'deepseek-chat';
UPDATE auth.model_pricing SET context_window = 128000, max_output_tokens = 8192
    WHERE provider = 'deepseek' AND model = 'deepseek-coder';
UPDATE auth.model_pricing SET context_window = 64000, max_output_tokens = 8192
    WHERE provider = 'deepseek' AND model = 'deepseek-reasoner';

-- xAI
UPDATE auth.model_pricing SET context_window = 131072, max_output_tokens = 8192
    WHERE provider = 'xai' AND model = 'grok-3-beta';
UPDATE auth.model_pricing SET context_window = 131072, max_output_tokens = 8192
    WHERE provider = 'xai' AND model = 'grok-3-mini-beta';

-- Perplexity
UPDATE auth.model_pricing SET context_window = 200000, max_output_tokens = 8192
    WHERE provider = 'perplexity' AND model = 'sonar-pro';
UPDATE auth.model_pricing SET context_window = 128000, max_output_tokens = 8192
    WHERE provider = 'perplexity' AND model = 'sonar-reasoning-pro';

-- Cohere
UPDATE auth.model_pricing SET context_window = 128000, max_output_tokens = 4096
    WHERE provider = 'cohere' AND model = 'command-r-plus-08-2024';
UPDATE auth.model_pricing SET context_window = 128000, max_output_tokens = 4096
    WHERE provider = 'cohere' AND model = 'command-r-08-2024';

-- Z.AI (GLM family)
UPDATE auth.model_pricing SET context_window = 128000, max_output_tokens = 8192
    WHERE provider = 'zai' AND model = 'glm-5';
UPDATE auth.model_pricing SET context_window = 128000, max_output_tokens = 8192
    WHERE provider = 'zai' AND model = 'glm-5-turbo';
UPDATE auth.model_pricing SET context_window = 128000, max_output_tokens = 8192
    WHERE provider = 'zai' AND model = 'glm-5.1';

-- OpenRouter relay rows (V116). Same context windows as the underlying provider
-- they relay to, since OpenRouter passes the prompt through unchanged.
UPDATE auth.model_pricing SET context_window = 200000, max_output_tokens = 64000
    WHERE provider = 'openrouter' AND model = 'anthropic/claude-sonnet-4-20250514';
UPDATE auth.model_pricing SET context_window = 400000, max_output_tokens = 128000
    WHERE provider = 'openrouter' AND model = 'openai/gpt-5.4';
UPDATE auth.model_pricing SET context_window = 1000000, max_output_tokens = 65536
    WHERE provider = 'openrouter' AND model = 'google/gemini-3-pro-preview';

-- Bridge variants (V128 - replaces V120, see comment above on naming). Conservative
-- defaults: same shape as the direct API equivalents where known.
UPDATE auth.model_pricing SET context_window = 1000000, max_output_tokens = 65536
    WHERE provider = 'gemini-cli' AND model = 'gemini-3.1-pro-preview';
UPDATE auth.model_pricing SET context_window = 1000000, max_output_tokens = 65536
    WHERE provider = 'gemini-cli' AND model = 'gemini-3-flash-preview';
UPDATE auth.model_pricing SET context_window = 1000000, max_output_tokens = 65536
    WHERE provider = 'gemini-cli' AND model = 'gemini-2.5-pro';
UPDATE auth.model_pricing SET context_window = 1000000, max_output_tokens = 65536
    WHERE provider = 'gemini-cli' AND model = 'gemini-2.5-flash';
UPDATE auth.model_pricing SET context_window = 400000, max_output_tokens = 128000
    WHERE provider = 'codex' AND model = 'gpt-5.4';
UPDATE auth.model_pricing SET context_window = 400000, max_output_tokens = 128000
    WHERE provider = 'codex' AND model = 'gpt-5.4-mini';
UPDATE auth.model_pricing SET context_window = 400000, max_output_tokens = 128000
    WHERE provider = 'codex' AND model = 'gpt-5.3-codex';
UPDATE auth.model_pricing SET context_window = 400000, max_output_tokens = 128000
    WHERE provider = 'codex' AND model = 'gpt-5.2';
-- Mistral devstral 2 (V128) - context window per official Mistral docs (2025).
UPDATE auth.model_pricing SET context_window = 256000, max_output_tokens = 8192
    WHERE provider = 'mistral-vibe' AND model = 'devstral-2';
UPDATE auth.model_pricing SET context_window = 128000, max_output_tokens = 8192
    WHERE provider = 'mistral-vibe' AND model = 'devstral-small-2';

CREATE INDEX IF NOT EXISTS idx_model_pricing_provider_model
    ON auth.model_pricing(provider, model)
    WHERE is_active = true;
