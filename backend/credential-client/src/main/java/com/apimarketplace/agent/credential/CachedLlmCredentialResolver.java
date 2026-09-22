package com.apimarketplace.agent.credential;

import com.apimarketplace.agent.resolver.LlmCredentialResolver;
import com.apimarketplace.common.web.TenantResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached implementation of {@link LlmCredentialResolver}.
 * Reads API keys from the database via {@link LlmCredentialRepository} and caches them for 5 minutes.
 *
 * <p>Lives in {@code credential-client} (the lightweight HTTP-client jar every
 * consumer already pulls in) so that
 * {@code com.apimarketplace.agent.provider.AbstractLLMProvider}'s
 * {@code @Autowired(required=false)} setter receives a real bean in every
 * service that instantiates LLM providers in-process. Previously this bean
 * lived in {@code shared-agent-lib}, which dragged 20+ unrelated provider
 * beans / schedulers into every consumer's Spring context - a blast radius
 * out of proportion to "we just need credential resolution". The package
 * name {@code com.apimarketplace.agent.credential} is preserved verbatim
 * across the 2026-05-28 move so consumer
 * {@code @ComponentScan("com.apimarketplace.agent")} declarations continue
 * to auto-wire the bean without scan edits.
 *
 * <p><b>Consumer wiring:</b> any service whose {@code @ComponentScan}
 * reaches {@code com.apimarketplace.agent} (or this exact sub-package)
 * auto-wires this bean. agent-service and orchestrator-service do.
 * conversation-service does NOT (its scan is narrower, and chat LLM calls
 * dispatch to agent-service via HTTP rather than instantiating providers
 * in-process - so it doesn't need the bean).
 *
 * <p><b>Cache + credential edits</b> (2026-05-28): the 5-minute TTL means a
 * user who flips their credential's {@code mode} ({@code no_proxy} ⇄
 * {@code proxy}) or rotates the {@code api_key} will see the old key
 * resolved for up to 5 minutes. The credential save path SHOULD call
 * {@link #invalidate(String)} after persisting a change for the affected
 * provider; this currently only happens on admin platform-credential edits
 * (via {@code LlmProviderStatusController.invalidateCache}). Per-user
 * cred-save invalidation hook is a follow-up - track as a known lag, not a
 * correctness bug, since the chain always converges within TTL.
 */
@Slf4j
@Component
public class CachedLlmCredentialResolver implements LlmCredentialResolver {

    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes

    private final LlmCredentialRepository repository;
    private final ConcurrentHashMap<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

    public CachedLlmCredentialResolver(LlmCredentialRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<String> resolveApiKey(String providerName) {
        return resolveApiKey(currentUserId(), providerName);
    }

    @Override
    public Optional<String> resolveApiKey(String userId, String providerName) {
        // Shared credentials depend on the workspace as well as the user.
        // Match the organization context forwarded by the repository's HTTP client.
        CacheKey cacheKey = cacheKeyFor(userId, providerName, false);
        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            return entry.value;
        }

        Optional<String> key = repository.findApiKeyByProviderName(userId, providerName);
        if (key.isPresent()) {
            cache.put(cacheKey, new CacheEntry(key));
            log.debug("Resolved API key from DB for provider: {} (user-scoped={})",
                    providerName, userId != null);
        } else {
            // Don't cache a negative result: a user who adds (or rotates in) a key
            // right after a miss would otherwise be stuck with "no key" for the
            // full TTL. Dropping the slot makes the next resolve re-query the DB.
            cache.remove(cacheKey);
        }
        return key;
    }

    @Override
    public Optional<String> resolveUserApiKey(String userId, String providerName) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        // Its own slot: the user-first slot may legitimately hold the PLATFORM key (a
        // user with no saved key), which must never be served to an OWN_KEY-pinned call.
        CacheKey cacheKey = cacheKeyFor(userId, providerName, true);
        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            return entry.value;
        }
        Optional<String> key = repository.findUserApiKeyByProviderName(userId, providerName);
        if (key.isPresent()) {
            cache.put(cacheKey, new CacheEntry(key));
        } else {
            // Same rule as the user-first slot: never cache a miss.
            cache.remove(cacheKey);
        }
        return key;
    }

    @Override
    public void invalidate(String userId, String providerName) {
        if (userId == null || providerName == null) {
            return;
        }
        // An edit may arrive without an organization context. Evict both routes
        // across every workspace for this user and provider.
        cache.keySet().removeIf(k -> Objects.equals(k.userId(), userId)
                && Objects.equals(k.providerName(), providerName));
        log.debug("Invalidated cached {} key for user {}", providerName, userId);
    }

    @Override
    public void invalidate(String providerName) {
        // Drop both routes and all workspace/user slots for this provider.
        if (providerName == null) {
            return;
        }
        cache.keySet().removeIf(k -> Objects.equals(k.providerName(), providerName));
        log.debug("Invalidated cache for provider: {}", providerName);
    }

    @Override
    public void invalidateAll() {
        cache.clear();
        log.debug("Invalidated all cached LLM credentials");
    }

    private static CacheKey cacheKeyFor(String userId, String providerName, boolean userOnly) {
        return new CacheKey(userId, TenantResolver.currentRequestOrganizationId(), providerName, userOnly);
    }

    private record CacheKey(String userId, String organizationId, String providerName, boolean userOnly) {}

    /**
     * Source of the in-flight user id. Production reads from
     * {@link TenantResolver#currentRequestUserId()} (servlet request header).
     * Tests override this to inject a userId without binding a servlet
     * request - credential-client's test scope does not pull jakarta.servlet.
     */
    protected String currentUserId() {
        return TenantResolver.currentRequestUserId();
    }

    private static class CacheEntry {
        final Optional<String> value;
        final long timestamp;

        CacheEntry(Optional<String> value) {
            this.value = value;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }
}
