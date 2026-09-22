package com.apimarketplace.agent.resolver;

import java.util.Optional;

/**
 * Resolves LLM provider API keys from external storage (e.g., database).
 * Implementations are optional - providers fall back to @Value-injected keys when no resolver is available.
 */
public interface LlmCredentialResolver {

    /**
     * Resolve an API key for the given provider name, using the in-flight
     * request's userId (read via
     * {@code com.apimarketplace.common.web.TenantResolver#currentRequestUserId()}).
     * Suitable for request-less callers (catalog discovery, the admin status page);
     * completion calls resolve through the two-arg form with the request's tenant.
     *
     * @param providerName the provider identifier (e.g., "anthropic", "openai", "google", "mistral", "deepseek")
     * @return the API key if found in external storage, empty otherwise
     */
    Optional<String> resolveApiKey(String providerName);

    /**
     * Resolve an API key with an explicit userId. Use this from contexts
     * where userId is known but no servlet request is bound
     * (e.g. workflow-execution threads in {@code BrowserAgentModule}, async
     * schedulers). The userId threads into both the cache slot and the
     * repository lookup, so user-then-platform resolution and the cache
     * agree. Pass {@code null} to skip the user lookup entirely.
     *
     * <p>Default delegates to the single-arg form for non-cache
     * implementations; {@link com.apimarketplace.agent.credential.CachedLlmCredentialResolver}
     * overrides to honour the explicit userId.
     */
    default Optional<String> resolveApiKey(String userId, String providerName) {
        return resolveApiKey(providerName);
    }

    /**
     * The user's OWN saved key for the provider, and only that: no platform fallback.
     * An execution pinned to the user's key resolves through this, so it can never
     * silently run on the platform key. The default answers empty because it cannot
     * tell a user key from a platform key; an implementation that can read the user's
     * credential overrides it, and until then an OWN_KEY-pinned call fails closed.
     */
    default Optional<String> resolveUserApiKey(String userId, String providerName) {
        return Optional.empty();
    }

    /**
     * Invalidate cached key for a specific provider.
     */
    default void invalidate(String providerName) {
        // no-op by default
    }

    /**
     * Invalidate ONE user's cached keys for a provider, after that user saved, removed or
     * switched their own key: their next call must reflect it without waiting out the TTL
     * and without touching any other user's slot.
     */
    default void invalidate(String userId, String providerName) {
        // no-op by default
    }

    /**
     * Invalidate all cached keys.
     */
    default void invalidateAll() {
        // no-op by default
    }
}
