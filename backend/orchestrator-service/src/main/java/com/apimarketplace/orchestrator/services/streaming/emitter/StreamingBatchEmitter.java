package com.apimarketplace.orchestrator.services.streaming.emitter;

import com.apimarketplace.orchestrator.services.streaming.context.RunContextRegistry;
import com.apimarketplace.orchestrator.services.streaming.state.RunStateStore;
import com.apimarketplace.orchestrator.services.streaming.state.RunStateStore.RunSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds streaming payloads from RunState snapshots.
 *
 * <p>Delegates lastPayload caching to {@link RunContextRegistry}.
 *
 * <p>Used by StreamingBatchScheduler for Redis caching.
 * Note: Snapshot emission is now handled directly by SnapshotService.
 */
@Component
public class StreamingBatchEmitter {

    private static final Logger logger = LoggerFactory.getLogger(StreamingBatchEmitter.class);

    private final RunContextRegistry contextRegistry;

    public StreamingBatchEmitter(@Lazy RunContextRegistry contextRegistry) {
        this.contextRegistry = contextRegistry;
    }

    /**
     * Get snapshot for a specific run.
     *
     * @param runId The workflow run ID
     * @return The snapshot payload, or last known payload if not found
     */
    public Map<String, Object> snapshot(String runId) {
        RunSnapshot snapshot = contextRegistry.snapshot(runId);
        if (snapshot == null) {
            Map<String, Object> lastPayload = contextRegistry.getLastPayload(runId);
            return lastPayload != null ? lastPayload : Collections.emptyMap();
        }
        Map<String, Object> payload = buildPayload(snapshot);
        contextRegistry.setLastPayload(runId, payload);
        return payload;
    }

    /**
     * Get the last emitted payload for a run.
     *
     * @param runId The workflow run ID
     * @return The last payload, or null if not found
     */
    public Map<String, Object> getLastPayload(String runId) {
        return contextRegistry.getLastPayload(runId);
    }

    private Map<String, Object> buildPayload(RunSnapshot snapshot) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "batch-update");
        payload.put("timestamp", Instant.now().toEpochMilli());
        payload.put("nodes", snapshot.steps());

        if (!snapshot.edges().isEmpty()) {
            payload.put("edges", snapshot.edges());
        }
        if (!snapshot.loops().isEmpty()) {
            payload.put("loops", snapshot.loops());
        }
        if (!snapshot.merges().isEmpty()) {
            payload.put("merges", snapshot.merges());
        }
        if (!snapshot.logs().isEmpty()) {
            payload.put("logs", snapshot.logs());
        }
        if (snapshot.workflowStatus() != null) {
            payload.put("workflowStatus", snapshot.workflowStatus());
        }
        if (snapshot.workflowStatistics() != null) {
            payload.put("workflowStatistics", snapshot.workflowStatistics());
        }
        if (snapshot.agentToolCalls() != null && !snapshot.agentToolCalls().isEmpty()) {
            payload.put("agentToolCalls", snapshot.agentToolCalls());
        }
        if (snapshot.terminal()) {
            payload.put("terminal", true);
        }
        return payload;
    }
}
