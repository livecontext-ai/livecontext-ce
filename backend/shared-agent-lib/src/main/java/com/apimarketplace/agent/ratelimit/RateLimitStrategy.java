package com.apimarketplace.agent.ratelimit;

/**
 * Strategy for rate limiting.
 */
public enum RateLimitStrategy {
    /**
     * Global rate limiting - all users share the same limits.
     * Useful when you have a single API key for all users.
     *
     * Example:
     * - Limit: 90,000 TPM
     * - User A uses 50,000 tokens
     * - User B uses 40,000 tokens
     * - User C is blocked (would exceed 90,000)
     */
    GLOBAL,

    /**
     * Per-tenant rate limiting - each tenant has independent limits.
     * Useful for multi-tenant applications where each tenant should have fair usage.
     *
     * Example:
     * - Limit: 10,000 TPM per tenant
     * - User A uses 9,000 tokens (90% of their limit)
     * - User B uses 2,000 tokens (20% of their limit)
     * - User C uses 5,000 tokens (50% of their limit)
     * - All users can continue independently
     */
    PER_TENANT,

    /**
     * Hybrid - global limit AND per-tenant limit.
     * Ensures both fair usage per tenant and total API limit compliance.
     *
     * Example:
     * - Global limit: 90,000 TPM
     * - Per-tenant limit: 10,000 TPM
     * - User A can't use more than 10,000 TPM (fair share)
     * - All users combined can't exceed 90,000 TPM (API limit)
     */
    HYBRID
}
