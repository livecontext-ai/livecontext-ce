package com.apimarketplace.agent.ratelimit;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for rate limiting per LLM provider.
 *
 * Configure limits in application.properties:
 * rate-limit.strategy=GLOBAL (or PER_TENANT or HYBRID)
 * rate-limit.providers.openai.tokens-per-minute=90000
 * rate-limit.providers.openai.requests-per-minute=3500
 * rate-limit.providers.openai.tokens-per-minute-per-tenant=10000
 * rate-limit.providers.openai.requests-per-minute-per-tenant=100
 */
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "rate-limit")
@Getter
public class RateLimitConfig {

    /**
     * Enable/disable rate limiting globally.
     * Default: true
     */
    private boolean enabled = true;

    /**
     * Rate limiting strategy.
     * - GLOBAL: All users share the same limits (default)
     * - PER_TENANT: Each tenant has independent limits
     * - HYBRID: Both global and per-tenant limits enforced
     */
    private RateLimitStrategy strategy = RateLimitStrategy.GLOBAL;

    /**
     * Default mode for rate limit handling.
     * - FAIL_FAST: Throw exception immediately when limit exceeded
     * - WAIT: Block until rate limit allows (invisible to caller, default)
     * - TRY_ACQUIRE: Return result, caller decides what to do
     * - QUEUE: Queue request for later processing
     */
    private RateLimitMode defaultMode = RateLimitMode.WAIT;

    /**
     * Maximum wait time for WAIT mode (in seconds).
     * If rate limit not available within this time, throws exception.
     * Default: 60 seconds
     */
    private int maxWaitTimeSeconds = 60;

    /**
     * Per-provider rate limits.
     * Key: provider name (openai, anthropic, google, mistral, deepseek)
     * Value: ProviderLimit with TPM and RPM
     */
    private Map<String, ProviderLimit> providers = new HashMap<>();

    /**
     * Default limits when provider-specific limits not configured.
     * These are very permissive defaults - override in application.yml for production.
     *
     * Global: 10M TPM, 10K RPM (shared by all tenants)
     * Per-tenant: 1M TPM, 1K RPM (per tenant)
     */
    private ProviderLimit defaults = new ProviderLimit(10_000_000, 10_000, 1_000_000, 1_000);

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setStrategy(RateLimitStrategy strategy) {
        this.strategy = strategy;
    }

    public void setDefaultMode(RateLimitMode defaultMode) {
        this.defaultMode = defaultMode;
    }

    public void setMaxWaitTimeSeconds(int maxWaitTimeSeconds) {
        this.maxWaitTimeSeconds = maxWaitTimeSeconds;
    }

    public void setProviders(Map<String, ProviderLimit> providers) {
        this.providers = providers;
        log.info("Configured rate limits for {} providers with strategy: {}", providers.size(), strategy);
    }

    public void setDefaults(ProviderLimit defaults) {
        this.defaults = defaults;
    }

    /**
     * Get rate limit for a provider, or default if not configured.
     */
    public ProviderLimit getProviderLimit(String providerName) {
        return providers.getOrDefault(providerName.toLowerCase(), defaults);
    }

    /**
     * Rate limit configuration for a single provider.
     */
    @Getter
    public static class ProviderLimit {
        /**
         * Global Tokens Per Minute limit (shared by all tenants).
         * Set to -1 to disable global TPM limiting.
         */
        private int tokensPerMinute;

        /**
         * Global Requests Per Minute limit (shared by all tenants).
         * Set to -1 to disable global RPM limiting.
         */
        private int requestsPerMinute;

        /**
         * Per-Tenant Tokens Per Minute limit.
         * Set to -1 to disable per-tenant TPM limiting.
         */
        private int tokensPerMinutePerTenant;

        /**
         * Per-Tenant Requests Per Minute limit.
         * Set to -1 to disable per-tenant RPM limiting.
         */
        private int requestsPerMinutePerTenant;

        public ProviderLimit() {
            this.tokensPerMinute = -1;
            this.requestsPerMinute = -1;
            this.tokensPerMinutePerTenant = -1;
            this.requestsPerMinutePerTenant = -1;
        }

        public ProviderLimit(int tokensPerMinute, int requestsPerMinute) {
            this.tokensPerMinute = tokensPerMinute;
            this.requestsPerMinute = requestsPerMinute;
            this.tokensPerMinutePerTenant = -1;
            this.requestsPerMinutePerTenant = -1;
        }

        public ProviderLimit(int tokensPerMinute, int requestsPerMinute,
                            int tokensPerMinutePerTenant, int requestsPerMinutePerTenant) {
            this.tokensPerMinute = tokensPerMinute;
            this.requestsPerMinute = requestsPerMinute;
            this.tokensPerMinutePerTenant = tokensPerMinutePerTenant;
            this.requestsPerMinutePerTenant = requestsPerMinutePerTenant;
        }

        public void setTokensPerMinute(int tokensPerMinute) {
            this.tokensPerMinute = tokensPerMinute;
        }

        public void setRequestsPerMinute(int requestsPerMinute) {
            this.requestsPerMinute = requestsPerMinute;
        }

        public void setTokensPerMinutePerTenant(int tokensPerMinutePerTenant) {
            this.tokensPerMinutePerTenant = tokensPerMinutePerTenant;
        }

        public void setRequestsPerMinutePerTenant(int requestsPerMinutePerTenant) {
            this.requestsPerMinutePerTenant = requestsPerMinutePerTenant;
        }

        /**
         * Whether this dimension has a configured limit.
         * {@code -1} = disabled (no limit), {@code 0} = block all traffic,
         * {@code > 0} = allow up to N per minute.
         */
        public boolean hasGlobalTokenLimit() {
            return tokensPerMinute >= 0;
        }

        public boolean hasGlobalRequestLimit() {
            return requestsPerMinute >= 0;
        }

        public boolean hasTenantTokenLimit() {
            return tokensPerMinutePerTenant >= 0;
        }

        public boolean hasTenantRequestLimit() {
            return requestsPerMinutePerTenant >= 0;
        }

        public boolean hasTokenLimit() {
            return hasGlobalTokenLimit();
        }

        public boolean hasRequestLimit() {
            return hasGlobalRequestLimit();
        }
    }
}
