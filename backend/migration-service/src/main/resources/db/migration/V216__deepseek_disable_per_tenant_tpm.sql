-- ---------------------------------------------------------------------------
-- V216: Disable per-tenant TPM preflight for DeepSeek models
--
-- WHY: ProviderRateLimiter.acquireWithWait runs a "request_exceeds_tenant_capacity"
-- preflight that fails NON-RETRYABLY when a single request's estimated tokens
-- exceed rate_limit_tpm_per_tenant. For deepseek-chat the cap was 20 000 (stamped
-- by CatalogMergeService.applyRateLimitDefaults from ai.agent.defaults.rate-
-- limit-tpm-per-tenant in agent-service/application.yml), and prod was rejecting
-- legitimate 35-40K-token agent prompts with:
--
--   "Stream error: Request estimated at 40216 tokens exceeds per-tenant TPM
--    capacity (20000) for deepseek/deepseek-chat - no rate-limit wait can
--    satisfy it. Reduce prompt size or raise the tenant cap."
--
-- The 20 000 default was sized for budget chat models when LiteLLM didn't report
-- per-model TPM; it's far below DeepSeek's real per-tenant ceiling (we self-cap)
-- and below a typical workflow agent prompt size (history + tools + system =
-- 30-40K tokens easily).
--
-- WHY -1 instead of NULL or a higher value:
--   - hasTenantTokenLimit() returns (tokensPerMinutePerTenant >= 0), so -1
--     evaluates to FALSE and the preflight short-circuit at line 365-379 is
--     skipped entirely for these models.
--   - NULL is re-stamped to 20 000 by CatalogMergeService.applyRateLimitDefaults
--     on every catalog bundle sync; -1 + lock survives.
--   - The GLOBAL TPM (rate_limit_tpm) is intact and still protects DeepSeek's
--     shared cloud quota against single-tenant abuse.
--
-- LOCK: 'rateLimitTpmPerTenant' is added to user_modified_fields so
-- CatalogMergeService's UPSERT path (the CASE…WHEN NOT 'rateLimitTpmPerTenant'
-- = ANY(user_modified_fields) THEN EXCLUDED) leaves the -1 in place.
--
-- REVERSIBLE: see commented rollback at the end.
-- ---------------------------------------------------------------------------

SET search_path TO agent;

INSERT INTO model_config_overrides (
    provider, model_id,
    display_name, enabled, source, bundle_version,
    rate_limit_tpm_per_tenant,
    user_modified_fields,
    created_at, updated_at
)
VALUES
    ('deepseek', 'deepseek-chat',  'deepseek-chat',  TRUE, 'curated', 1, -1, ARRAY['rateLimitTpmPerTenant'], NOW(), NOW()),
    ('deepseek', 'deepseek-coder', 'deepseek-coder', TRUE, 'curated', 1, -1, ARRAY['rateLimitTpmPerTenant'], NOW(), NOW())
ON CONFLICT (provider, model_id) DO UPDATE
SET rate_limit_tpm_per_tenant = -1,
    -- Append 'rateLimitTpmPerTenant' to existing user_modified_fields without
    -- creating duplicates. ARRAY(SELECT DISTINCT unnest(...)) is the canonical
    -- pattern in this codebase (see V158 admin-protected upserts).
    user_modified_fields = ARRAY(
        SELECT DISTINCT unnest(
            COALESCE(model_config_overrides.user_modified_fields, ARRAY[]::TEXT[])
            || ARRAY['rateLimitTpmPerTenant']
        )
    ),
    updated_at = NOW();

-- ---------------------------------------------------------------------------
-- Rollback (manual, if needed - NOT executed by Flyway):
--
-- UPDATE agent.model_config_overrides
-- SET rate_limit_tpm_per_tenant = NULL,  -- or a chosen positive value
--     user_modified_fields = array_remove(user_modified_fields, 'rateLimitTpmPerTenant'),
--     updated_at = NOW()
-- WHERE provider = 'deepseek' AND model_id IN ('deepseek-chat', 'deepseek-coder');
--
-- After reverting, CachedModelRateLimitProvider re-hydrates within 30 s (its
-- @Scheduled fixedRate). Restart agent-service to force-flush if needed.
-- ---------------------------------------------------------------------------
