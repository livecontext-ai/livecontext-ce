package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import org.springframework.stereotype.Service;

/**
 * Formerly managed in-memory deduplication of step persistence.
 * Now a no-op: deduplication is handled at DB level via
 * INSERT ... ON CONFLICT DO NOTHING on idx_workflow_step_data_unique_v6.
 *
 * Kept as a RunScopedCache implementation to avoid breaking the cache registry.
 */
@Service
public class PersistenceDeduplicationService implements RunScopedCache {

    @Override
    public void cleanupRun(String runId) {
        // No-op: no in-memory state to clean
    }

    @Override
    public String getCacheName() {
        return "PersistenceDeduplicationCache";
    }

    @Override
    public CacheDomain getDomain() {
        return CacheDomain.PERSISTENCE;
    }

    @Override
    public int getCacheSize() {
        return 0;
    }
}
