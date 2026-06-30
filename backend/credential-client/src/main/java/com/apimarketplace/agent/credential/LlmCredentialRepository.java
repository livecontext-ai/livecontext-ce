package com.apimarketplace.agent.credential;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.CredentialSummaryDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Repository for reading LLM provider API keys via credential-client HTTP calls.
 * Delegates to auth-service's InternalCredentialController via CredentialClient (HTTP).
 *
 * <p>Lives in {@code credential-client} (the lightweight HTTP-client jar every
 * consumer already depends on) so every service auto-wires the same resolver
 * without dragging in shared-agent-lib's LLM-provider bean blast (Reactor
 * Netty + jtokkit + 10+ {@code @Component}/{@code @Scheduled} provider classes
 * the consumer doesn't need). The package name {@code com.apimarketplace.agent.credential}
 * is preserved across the 2026-05-28 move so consumer
 * {@code @ComponentScan("com.apimarketplace.agent")} declarations continue to
 * pick up the beans with zero scan changes.
 *
 * <p>Resolution chain (in order): user's default credential for {@code llm_<provider>}
 * → admin-configured platform credential for {@code llm_<provider>}. The
 * {@code @Value}-injected env-var tail that {@code AbstractLLMProvider} layers
 * on top lives in the provider itself.
 *
 * <p>User-credential {@code mode} field (V275 2026-05-28): a user's saved LLM
 * credential carries {@code credentialData.mode = "no_proxy" | "proxy"} (default
 * {@code no_proxy} when absent). {@code no_proxy} = use the raw {@code api_key}
 * directly against the upstream provider - user's billing. {@code proxy} = skip
 * this credential, fall through to the platform key - platform's billing. The
 * resolution flow is uniform either way (no per-node branching), matching the
 * email-node credential pattern.
 */
@Slf4j
@Repository
public class LlmCredentialRepository {

    /**
     * Convention: integration_name = {@value #INTEGRATION_PREFIX} + provider name.
     * No hardcoded allow-list - any provider that appears in {@code ai.agent.providers.*}
     * (YAML) maps here automatically. A typo or unknown name will just miss in
     * {@code platform_credentials} and resolve to {@code Optional.empty()} downstream.
     */
    static final String INTEGRATION_PREFIX = "llm_";

    private static final long HAS_DB_KEY_CACHE_TTL_MS = 60_000; // 1 minute

    private final CredentialClient credentialClient;
    private final ConcurrentHashMap<String, CachedBoolean> hasDbKeyCache = new ConcurrentHashMap<>();

    public LlmCredentialRepository(CredentialClient credentialClient) {
        this.credentialClient = credentialClient;
    }

    private record CachedBoolean(boolean value, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    /**
     * Resolve an API key for the given provider, preferring the in-flight
     * user's default credential and falling back to the platform credential.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>If {@link TenantResolver#currentRequestUserId()} returns a userId,
     *       look up that user's default credential for integration
     *       {@code "llm_" + providerName} via
     *       {@link CredentialClient#getDefaultCredential(String, String)} and
     *       read the {@code api_key} field from its
     *       {@link CredentialSummaryDto#getCredentialData() credentialData}.
     *       This is the per-user override: when a user has saved their own
     *       OpenAI/Anthropic/… key in Settings → Credentials, it takes
     *       precedence over the admin-configured platform key.</li>
     *   <li>Otherwise, or if no user key is found, fall back to
     *       {@link CredentialClient#getPlatformCredentialForIntegration(String)}
     *       (the original behaviour - admin-configured key in
     *       {@code auth.platform_credentials}).</li>
     * </ol>
     *
     * <p>Returns decrypted key if found and enabled, empty if both layers miss.
     * The {@code @Value}-injected env-var fallback that
     * {@link com.apimarketplace.agent.provider.AbstractLLMProvider} layers on
     * top of this resolver lives in the provider itself (so we don't depend on
     * Spring {@code Environment} here).
     */
    public Optional<String> findApiKeyByProviderName(String providerName) {
        return findApiKeyByProviderName(TenantResolver.currentRequestUserId(), providerName);
    }

    /**
     * Variant of {@link #findApiKeyByProviderName(String)} that takes an explicit
     * userId - for callsites that already have the userId in hand (e.g.
     * orchestrator's {@code BrowserAgentModule}, where the workflow context
     * carries the tenant explicitly) or for async paths where no servlet
     * request is bound to {@code RequestContextHolder}. Pass {@code null} to
     * skip the user-cred step and resolve from platform credentials only.
     */
    public Optional<String> findApiKeyByProviderName(String userId, String providerName) {
        String integrationName = toIntegrationName(providerName);
        if (integrationName == null) {
            log.debug("Unknown provider name: {}", providerName);
            return Optional.empty();
        }

        // 1) Per-user default credential - overrides the platform key when set.
        //    Mirrors SendEmailNode's getDefaultCredential(tenantId, "smtp") flow,
        //    extended to LLM providers via the "llm_<provider>" integration name
        //    convention. The credential's `mode` field decides direction:
        //      - "no_proxy" (default when absent) → use the user's api_key directly
        //      - "proxy" → opt out: fall through to the platform key (user picked
        //        platform-managed routing, e.g. for managed billing)
        //    The branching lives ONLY here so the caller's flow is uniform.
        if (userId != null && !userId.isBlank()) {
            try {
                Optional<CredentialSummaryDto> userCred =
                        credentialClient.getDefaultCredential(userId, integrationName);
                if (userCred.isPresent()) {
                    Map<String, Object> data = userCred.get().getCredentialData();
                    if (isProxyMode(data)) {
                        log.debug("User cred for {}={} is proxy-mode - falling through to platform",
                                userId, integrationName);
                    } else {
                        Optional<String> userKey = extractApiKey(data);
                        if (userKey.isPresent()) {
                            return userKey;
                        }
                        log.debug("User cred for {}={} has no_proxy mode but api_key blank/missing - falling through",
                                userId, integrationName);
                    }
                }
            } catch (Exception e) {
                // Best-effort: a credential-service failure on the user lookup
                // falls through to platform - strictly more conservative than a
                // hard failure that breaks the LLM call altogether.
                log.warn("Failed to resolve user credential for user={}, integration={}: {}",
                        userId, integrationName, e.getMessage());
            }
        }

        // 2) Platform credential - admin-configured key in auth.platform_credentials.
        try {
            return credentialClient.getPlatformCredentialForIntegration(integrationName);
        } catch (Exception e) {
            log.error("Failed to get platform API key for provider {}: {}", providerName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Returns true when the user's credential is configured as
     * {@code mode="proxy"}, opting into platform-managed routing instead of
     * direct upstream use of the api_key. Any other value (including absent)
     * is treated as {@code no_proxy} so existing credentials without the
     * field migrate transparently to direct-use behavior.
     */
    private static boolean isProxyMode(Map<String, Object> credentialData) {
        if (credentialData == null) {
            return false;
        }
        Object mode = credentialData.get("mode");
        return mode instanceof String s && "proxy".equalsIgnoreCase(s.trim());
    }

    /**
     * Pulls the {@code api_key} entry out of a decrypted credential-data map.
     * The credential-data shape for LLM creds matches the platform-credential
     * column name and the {@code authType=api_key} extraction path in
     * {@code InternalCredentialController} (lines 254/264), so a user-saved
     * LLM credential stores the secret under exactly this key.
     */
    private static Optional<String> extractApiKey(Map<String, Object> credentialData) {
        if (credentialData == null) {
            return Optional.empty();
        }
        Object value = credentialData.get("api_key");
        if (value instanceof String s && !s.isBlank()) {
            return Optional.of(s);
        }
        return Optional.empty();
    }

    /**
     * Check if a DB-stored key (user-default OR platform) is resolvable for
     * the given provider. Results are cached for 1 minute to avoid N+1 HTTP
     * calls when filtering the provider catalog (called once per provider in
     * {@code ModelCatalogService}).
     *
     * <p>Cache key is scoped to the in-flight user so userA having a personal
     * OpenAI key does not leak a "true" answer into userB's catalog filter
     * (and vice-versa). When no request is bound the key falls back to a
     * platform-only sentinel - matches the resolution path used at lookup time.
     */
    public boolean hasDbKey(String providerName) {
        String userId = TenantResolver.currentRequestUserId();
        String cacheKey = cacheKeyFor(userId, providerName);
        CachedBoolean cached = hasDbKeyCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.value;
        }
        boolean result = findApiKeyByProviderName(userId, providerName).isPresent();
        hasDbKeyCache.put(cacheKey, new CachedBoolean(result,
                System.currentTimeMillis() + HAS_DB_KEY_CACHE_TTL_MS));
        return result;
    }

    /**
     * Clear cached hasDbKey result for a single provider across every user
     * slot. Called after admin saves/deletes a platform credential - the
     * platform-credential change can flip the answer for every user.
     */
    public void clearHasDbKeyCache(String providerName) {
        if (providerName == null) {
            return;
        }
        String suffix = ":" + providerName;
        hasDbKeyCache.keySet().removeIf(k -> k.endsWith(suffix));
    }

    private static String cacheKeyFor(String userId, String providerName) {
        return (userId == null ? "__platform__" : userId) + ":" + providerName;
    }

    /**
     * Clear all cached hasDbKey results.
     * Called after admin invalidates all credentials.
     */
    public void clearHasDbKeyCacheAll() {
        hasDbKeyCache.clear();
    }

    public static String toIntegrationName(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            return null;
        }
        return INTEGRATION_PREFIX + providerName;
    }

    public static String toProviderName(String integrationName) {
        if (integrationName == null || !integrationName.startsWith(INTEGRATION_PREFIX)) {
            return null;
        }
        String stripped = integrationName.substring(INTEGRATION_PREFIX.length());
        return stripped.isBlank() ? null : stripped;
    }
}
