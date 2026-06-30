package com.apimarketplace.orchestrator.services.streaming.emitter;

import java.util.Collections;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Provides snapshot access for Redis caching via StreamingBatchEmitter.
 *
 * <p>Snapshots are sent directly by the execution thread via SnapshotService.
 *
 * @see com.apimarketplace.orchestrator.services.streaming.SnapshotService
 */
@Component
public class StreamingBatchScheduler {

    private final StreamingBatchEmitter emitter;

    public StreamingBatchScheduler(StreamingBatchEmitter emitter) {
        this.emitter = emitter;
    }

    /**
     * Get snapshot for a specific run (used for Redis caching).
     *
     * @param runId The workflow run ID
     * @return The snapshot, or empty map if not found
     */
    public Map<String, Object> snapshotForRun(String runId) {
        if (runId == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> payload = emitter.snapshot(runId);
        return payload != null ? payload : Collections.emptyMap();
    }
}
