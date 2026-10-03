package com.apimarketplace.interfaces.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CASA LC-037 (gap 1): thin HTTP client to orchestrator's plan-membership check, used to bind a
 * bare {@code GET /api/interfaces/{id}} to the ONE workflow an APPLICATION share token actually
 * covers.
 *
 * <p>An APPLICATION share token resolves the visitor to the OWNER's real identity (see
 * {@code com.apimarketplace.common.web.ShareContextResourceBinding}'s javadoc), so a plain
 * org-scope lookup on the interface row authorizes the visitor against the owner's ENTIRE
 * interface library, not just the interface(s) the shared workflow's plan actually references.
 * {@code InterfaceEntity.sourceWorkflowId} is never populated on creation, so membership can only
 * be answered by asking orchestrator to re-derive it from the plan (the same derivation
 * {@code WorkflowExecutionService.snapshotInterfacesForRun} already performs).
 *
 * <p>Results are cached in memory for a few seconds (mirrors the gateway's
 * {@code ShareTokenResolutionService}, CASA LC-086) so a shared application page that reloads its
 * interface repeatedly does not hammer orchestrator on every request.
 */
public class OrchestratorInterfaceMembershipClient {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorInterfaceMembershipClient.class);

    /** Same bound as ShareTokenResolutionService.CACHE_TTL_MS: short enough that a revoked
     * share or an edited plan is reflected within one refresh, long enough to absorb a
     * page's repeated reads. */
    static final long CACHE_TTL_MS = 5_000L;
    static final int MAX_CACHE_ENTRIES = 10_000;

    private record CacheEntry(boolean referenced, long expiresAt) {}

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final RestClient restClient;
    private final java.util.function.LongSupplier clock;

    public OrchestratorInterfaceMembershipClient(String orchestratorUrl) {
        this(orchestratorUrl, System::currentTimeMillis);
    }

    /** Test seam: injectable wall clock. */
    OrchestratorInterfaceMembershipClient(String orchestratorUrl, java.util.function.LongSupplier clock) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(orchestratorUrl)
                .requestFactory(factory)
                .build();
        this.clock = clock;
    }

    /**
     * @return true when {@code workflowId}'s current plan references {@code interfaceId}. Any
     *         transport/lookup failure returns {@code false} (fail closed) - the caller's binding
     *         check treats that identically to "not referenced".
     */
    public boolean isReferencedByWorkflow(String workflowId, String interfaceId) {
        if (workflowId == null || workflowId.isBlank() || interfaceId == null || interfaceId.isBlank()) {
            return false;
        }
        String cacheKey = workflowId + ':' + interfaceId;
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt() > clock.getAsLong()) {
            return cached.referenced();
        }
        try {
            Boolean referenced = restClient.get()
                    .uri("/api/internal/workflows/{workflowId}/interfaces/{interfaceId}/referenced",
                            workflowId, interfaceId)
                    .retrieve()
                    .body(Boolean.class);
            boolean result = Boolean.TRUE.equals(referenced);
            putBounded(cacheKey, new CacheEntry(result, clock.getAsLong() + CACHE_TTL_MS));
            return result;
        } catch (Exception e) {
            log.warn("[OrchestratorInterfaceMembershipClient] membership check failed workflow={} interface={} cause={}",
                    workflowId, interfaceId, e.toString());
            return false;
        }
    }

    private void putBounded(String key, CacheEntry entry) {
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            long now = clock.getAsLong();
            cache.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                cache.clear();
            }
        }
        cache.put(key, entry);
    }

    /** Number of cached entries (test seam). */
    int cacheSize() {
        return cache.size();
    }
}
