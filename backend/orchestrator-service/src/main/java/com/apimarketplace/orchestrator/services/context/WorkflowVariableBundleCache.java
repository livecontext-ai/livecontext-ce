package com.apimarketplace.orchestrator.services.context;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * Per-run cache of the workflow-variable bundle ({@code {{$vars.*}}}).
 *
 * <p>The bundle is fetched from auth-service (decrypted, typed values) at most
 * once per run and reused by every node / item / epoch of that run - execution
 * context construction happens once per node in SBS mode, so fetching inline
 * would otherwise hammer auth-service.
 *
 * <p><b>Freshness contract:</b> a run sees a stable snapshot of the variables
 * for up to TTL; runs paused longer than TTL (signals, approvals) refetch on
 * resume and may observe updated values - acceptable, variables are config,
 * not per-run inputs. Terminated runs are evicted via {@link RunCacheRegistry}.
 *
 * <p><b>Failure contract:</b> CredentialClient is best-effort (empty map on
 * auth-service failure). The empty result IS cached - a transient outage
 * degrades that run to "no variables" for up to TTL rather than retry-storming
 * a struggling auth-service.
 */
@Component
public class WorkflowVariableBundleCache implements RunScopedCache {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowVariableBundleCache.class);

    static final Duration TTL = Duration.ofMinutes(10);
    static final long MAX_SIZE = 2_000L;

    private final Cache<String, Map<String, Object>> cache;
    private final CredentialClient credentialClient;

    public WorkflowVariableBundleCache(CredentialClient credentialClient) {
        this.credentialClient = credentialClient;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(TTL)
                .maximumSize(MAX_SIZE)
                .recordStats()
                .build();
    }

    /**
     * The run's variable bundle (variable name to typed value). Never null;
     * empty when the scope has no variables or auth-service is unreachable.
     */
    public Map<String, Object> getBundle(String runId, String tenantId, String organizationId) {
        if (runId == null || runId.isBlank() || tenantId == null || tenantId.isBlank()) {
            return Map.of();
        }
        return cache.get(runId, k -> {
            Map<String, Object> bundle = credentialClient.getWorkflowVariablesBundle(tenantId, organizationId);
            logger.debug("[WorkflowVariableBundleCache] Fetched {} variable(s) for runId={} (org={})",
                    bundle.size(), runId, organizationId);
            return bundle;
        });
    }

    @Override
    public void cleanupRun(String runId) {
        if (runId != null) {
            cache.invalidate(runId);
        }
    }

    @Override
    public String getCacheName() {
        return "WorkflowVariableBundleCache";
    }

    @Override
    public CacheDomain getDomain() {
        return CacheDomain.EXECUTION;
    }

    @Override
    public int getCacheSize() {
        return (int) cache.estimatedSize();
    }
}
