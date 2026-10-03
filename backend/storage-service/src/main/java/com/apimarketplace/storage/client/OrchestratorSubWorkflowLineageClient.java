package com.apimarketplace.storage.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CASA LC-037 (gap 2): thin HTTP client to orchestrator's sub-workflow-lineage check, used to
 * extend an APPLICATION share's file access to a file produced INSIDE a {@code core:sub_workflow}
 * child run.
 *
 * <p>Such a file is stamped ({@code storage.storage.workflow_id}) with the CHILD sub-workflow's
 * own id, never the shared PARENT workflow's, so the direct equality
 * {@code com.apimarketplace.common.web.ShareContextResourceBinding.permitsWorkflowResource}
 * performs always fails for it - by design, that method denies (fail closed) anything it cannot
 * prove. This client answers the follow-up question orchestrator alone can answer: was the file's
 * {@code runId} genuinely invoked as a sub-workflow by SOME run of the shared parent workflow?
 * See orchestrator's {@code InternalAccessController#isSubWorkflowDescendant} for the exact
 * evidence used (the {@code core:sub_workflow} node's own persisted step output).
 *
 * <p>Results are cached in memory for a few seconds (mirrors the gateway's
 * {@code ShareTokenResolutionService}, CASA LC-086) so a shared application repeatedly reading the
 * same sub-workflow file does not hammer orchestrator on every request.
 */
public class OrchestratorSubWorkflowLineageClient {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorSubWorkflowLineageClient.class);

    static final long CACHE_TTL_MS = 5_000L;
    static final int MAX_CACHE_ENTRIES = 10_000;

    private record CacheEntry(boolean descendant, long expiresAt) {}

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final RestClient restClient;
    private final java.util.function.LongSupplier clock;

    public OrchestratorSubWorkflowLineageClient(String orchestratorUrl) {
        this(orchestratorUrl, System::currentTimeMillis);
    }

    /** Test seam: injectable wall clock. */
    OrchestratorSubWorkflowLineageClient(String orchestratorUrl, java.util.function.LongSupplier clock) {
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
     * @return true when {@code childRunId} was genuinely invoked as a {@code core:sub_workflow}
     *         child by SOME run of {@code parentWorkflowId}. Any transport/lookup failure returns
     *         {@code false} (fail closed) - the caller's binding check treats that identically to
     *         "not a descendant".
     */
    public boolean isSubWorkflowDescendant(String parentWorkflowId, String childRunId) {
        if (parentWorkflowId == null || parentWorkflowId.isBlank()
                || childRunId == null || childRunId.isBlank()) {
            return false;
        }
        String cacheKey = parentWorkflowId + ':' + childRunId;
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt() > clock.getAsLong()) {
            return cached.descendant();
        }
        try {
            Boolean descendant = restClient.get()
                    .uri("/api/internal/workflows/{workflowId}/sub-workflow-runs/{childRunId}/descendant",
                            parentWorkflowId, childRunId)
                    .retrieve()
                    .body(Boolean.class);
            boolean result = Boolean.TRUE.equals(descendant);
            putBounded(cacheKey, new CacheEntry(result, clock.getAsLong() + CACHE_TTL_MS));
            return result;
        } catch (Exception e) {
            log.warn("[OrchestratorSubWorkflowLineageClient] lineage check failed parentWorkflow={} childRun={} cause={}",
                    parentWorkflowId, childRunId, e.toString());
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
