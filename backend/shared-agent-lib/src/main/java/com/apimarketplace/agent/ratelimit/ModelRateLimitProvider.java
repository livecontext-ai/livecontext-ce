package com.apimarketplace.agent.ratelimit;

/**
 * Provides model-specific rate limit overrides.
 *
 * <p>Implementations load overrides from external storage (e.g., database)
 * and cache them for fast in-memory lookup. The rate limiter calls this
 * on every request to resolve per-model limits.
 *
 * <p>Injected as {@code @Autowired(required = false)} into {@link ProviderRateLimiter}.
 * When absent, all rate limiting falls back to provider-level config.
 */
public interface ModelRateLimitProvider {

    /**
     * Get model-specific rate limit overrides.
     *
     * @param provider the LLM provider name (e.g., "openai", "anthropic")
     * @param modelId  the model identifier (e.g., "gpt-4o", "claude-opus-4-6")
     * @return model-specific limits, or {@code null} to use provider defaults entirely.
     *         Within a non-null return, individual {@code null} fields fall through
     *         to provider config for that dimension.
     */
    ModelRateLimit getModelLimit(String provider, String modelId);
}
