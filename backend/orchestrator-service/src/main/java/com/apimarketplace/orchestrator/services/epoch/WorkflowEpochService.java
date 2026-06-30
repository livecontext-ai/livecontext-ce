package com.apimarketplace.orchestrator.services.epoch;

import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository.EpochCountRow;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository.EpochHeaderRow;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository.EpochHeaderWithEpochRow;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository.EpochTimestampRow;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service for recording and querying per-epoch node/edge status counts,
 * and managing epoch lifecycle (open/close with full EpochState JSONB).
 *
 * <p>Writes are additive (UPSERT increments) alongside existing StateSnapshot.
 * Reads build structured maps for the REST endpoint.
 *
 * <p>Header operations (open/close/get) store the full {@link EpochState} in the
 * workflow_epochs table, making it the single source of truth for epoch data.
 */
@Service
public class WorkflowEpochService {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowEpochService.class);

    private static final String TYPE_NODE = "NODE";
    private static final String TYPE_EDGE = "EDGE";

    private final WorkflowEpochRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Optional Redis overlay for active-epoch running state (P2.3 site 7).
     * Field-injected so unit tests using the legacy 2-arg constructor still
     * work without a Redis-backed tracker - the overlay simply skips when
     * the tracker is absent and the diagnostic falls back to JSONB
     * (which is empty post-elide for active epochs, but accurate for
     * closed epochs that drained their running set at close time).
     */
    @Autowired(required = false)
    private RunningNodeTracker runningNodeTracker;

    public WorkflowEpochService(WorkflowEpochRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WRITE - Record counts
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Record a single node status count for a specific epoch (default trigger).
     */
    public void recordNodeCount(String runId, int epoch, String nodeKey, String status) {
        recordNodeCount(runId, epoch, nodeKey, status, null);
    }

    /**
     * Record a single node status count for a specific epoch with explicit triggerId.
     */
    public void recordNodeCount(String runId, int epoch, String nodeKey, String status, String triggerId) {
        String normalizedStatus = normalizeStatus(status);
        if (triggerId != null) {
            repository.upsert(runId, triggerId, epoch, TYPE_NODE, nodeKey, normalizedStatus);
        } else {
            repository.upsert(runId, epoch, TYPE_NODE, nodeKey, normalizedStatus);
        }
        logger.debug("[WorkflowEpoch] Recorded node: runId={}, epoch={}, key={}, status={}, triggerId={}",
                runId, epoch, nodeKey, normalizedStatus, triggerId);
    }

    /**
     * Record a batch of edge status counts for a specific epoch (default trigger).
     *
     * @param runId     the workflow run ID
     * @param epoch     the current epoch
     * @param edgeBatch map of edge keys (e.g., "from->to") to status strings
     */
    public void recordEdgeCounts(String runId, int epoch, Map<String, String> edgeBatch) {
        recordEdgeCounts(runId, epoch, edgeBatch, null);
    }

    /**
     * Record a batch of edge status counts for a specific epoch with explicit triggerId.
     */
    public void recordEdgeCounts(String runId, int epoch, Map<String, String> edgeBatch, String triggerId) {
        if (edgeBatch == null || edgeBatch.isEmpty()) return;

        // Normalize statuses before batch insert
        Map<String, String> normalized = new LinkedHashMap<>(edgeBatch.size());
        for (Map.Entry<String, String> entry : edgeBatch.entrySet()) {
            normalized.put(entry.getKey(), normalizeStatus(entry.getValue()));
        }
        if (triggerId != null) {
            repository.upsertBatch(runId, triggerId, epoch, TYPE_EDGE, normalized);
        } else {
            repository.upsertBatch(runId, epoch, TYPE_EDGE, normalized);
        }
        logger.debug("[WorkflowEpoch] Recorded {} edges: runId={}, epoch={}, triggerId={}",
                normalized.size(), runId, epoch, triggerId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE - Open / Update / Close epoch headers
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Open a new epoch: serialize the initial EpochState and upsert a header row.
     */
    public void openEpoch(String runId, String triggerId, int epoch, EpochState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            repository.upsertHeader(runId, triggerId, epoch, json);
            logger.debug("[WorkflowEpoch] Opened epoch header: runId={}, triggerId={}, epoch={}", runId, triggerId, epoch);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize EpochState for open: runId=" + runId + ", epoch=" + epoch, e);
        }
    }

    /**
     * Update an existing epoch header with the latest EpochState JSONB.
     */
    public void updateEpochState(String runId, String triggerId, int epoch, EpochState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            repository.upsertHeader(runId, triggerId, epoch, json);
            logger.debug("[WorkflowEpoch] Updated epoch header: runId={}, triggerId={}, epoch={}", runId, triggerId, epoch);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize EpochState for update: runId=" + runId + ", epoch=" + epoch, e);
        }
    }

    /**
     * Close an epoch: serialize the final EpochState and mark the header row as inactive.
     *
     * @param durationMs epoch duration (closed_at - started_at) computed by caller;
     *                   stored in workflow_epochs.duration_ms for per-epoch queries
     */
    public void closeEpoch(String runId, String triggerId, int epoch, EpochState finalState, long durationMs) {
        try {
            String json = objectMapper.writeValueAsString(finalState);
            repository.closeEpochHeader(runId, triggerId, epoch, json, durationMs);
            logger.debug("[WorkflowEpoch] Closed epoch header: runId={}, triggerId={}, epoch={}, durationMs={}",
                    runId, triggerId, epoch, durationMs);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize EpochState for close: runId=" + runId + ", epoch=" + epoch, e);
        }
    }

    /**
     * @deprecated Use {@link #closeEpoch(String, String, int, EpochState, long)} with duration.
     */
    public void closeEpoch(String runId, String triggerId, int epoch, EpochState finalState) {
        closeEpoch(runId, triggerId, epoch, finalState, 0L);
    }

    /**
     * Get the full EpochState for a specific run and epoch (any trigger).
     * Returns null if the header row doesn't exist.
     */
    public EpochState getFullEpochState(String runId, int epoch) {
        EpochHeaderRow header = repository.getEpochHeader(runId, epoch);
        return deserializeHeader(header);
    }

    /**
     * Get the full EpochState for a specific run, trigger, and epoch.
     * Returns null if the header row doesn't exist.
     */
    public EpochState getFullEpochState(String runId, String triggerId, int epoch) {
        EpochHeaderRow header = repository.getEpochHeader(runId, triggerId, epoch);
        return deserializeHeader(header);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // READ - Query counts
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get epoch state for a specific run and epoch (across all triggers).
     * Returns a structured map: { epoch, nodes: {key: {status: count}}, edges: {key: {status: count}} }
     *
     * <p>If a header row exists, also includes: readyNodeIds, awaitingSignalNodeIds, decisionBranches.
     */
    public Map<String, Object> getEpochState(String runId, int epoch) {
        List<EpochCountRow> rows = repository.findByRunIdAndEpoch(runId, epoch);
        Map<String, Object> result = buildStateMap(epoch, rows);

        // Enrich with header data if available
        EpochHeaderRow header = repository.getEpochHeader(runId, epoch);
        enrichWithHeader(result, header, runId, epoch);

        return result;
    }

    /**
     * Get epoch state for a specific run, trigger, and epoch.
     */
    public Map<String, Object> getEpochState(String runId, String triggerId, int epoch) {
        List<EpochCountRow> rows = repository.findByRunIdTriggerAndEpoch(runId, triggerId, epoch);
        Map<String, Object> result = buildStateMap(epoch, rows);

        EpochHeaderRow header = repository.getEpochHeader(runId, triggerId, epoch);
        enrichWithHeader(result, header, runId, epoch);

        return result;
    }

    /**
     * Get accumulated state across all epochs for a run (across all triggers).
     * Returns a structured map: { epoch: -1, nodes: {key: {status: count}}, edges: {key: {status: count}} }
     */
    public Map<String, Object> getAccumulatedState(String runId) {
        List<EpochCountRow> rows = repository.getAccumulatedCounts(runId);
        return buildStateMap(-1, rows);
    }

    /**
     * Get accumulated state across all epochs for a specific trigger.
     */
    public Map<String, Object> getAccumulatedState(String runId, String triggerId) {
        List<EpochCountRow> rows = repository.getAccumulatedCountsByTrigger(runId, triggerId);
        return buildStateMap(-1, rows);
    }

    /**
     * Get accumulated node counts across all epochs, keyed by label (prefix stripped).
     * Used by the /status-counts endpoint as primary data source.
     *
     * @return Map of nodeLabel -> {status -> count}, e.g. {"step1": {"COMPLETED": 3, "FAILED": 1}}
     */
    public Map<String, Map<String, Long>> getAccumulatedNodeCounts(String runId) {
        return buildNodeCountsFromRows(repository.getAccumulatedCounts(runId));
    }

    /**
     * Get accumulated edge counts across all epochs, keyed verbatim by edge id ("from->to").
     * Edge keys are stored port-preserved and label-normalized - see
     * {@link com.apimarketplace.orchestrator.services.streaming.EdgeStatusService} write path.
     *
     * <p>Each edge tracks how many items actually traversed it, recorded at the source side.
     * Callers MUST use this - never derive an edge's count from its target node's counts,
     * which conflates all incoming edges of a multi-predecessor node (shared merge fan-in).
     *
     * @return Map of edgeId -> {status -> count}, e.g. {"trigger:scheduler->core:sharedmerge": {"completed": 5}}
     */
    public Map<String, Map<String, Long>> getAccumulatedEdgeCounts(String runId) {
        return buildEdgeCountsFromRows(repository.getAccumulatedCounts(runId));
    }

    /**
     * Single-query variant returning both accumulated node and edge counts in one shot.
     * The /status-counts endpoint uses this to avoid running the (already heavy)
     * accumulated-counts SQL twice per poll.
     *
     * @return record carrying the same maps as {@link #getAccumulatedNodeCounts(String)} and
     *         {@link #getAccumulatedEdgeCounts(String)}, populated from a single repository call
     */
    public AccumulatedCounts getAccumulatedCounts(String runId) {
        List<EpochCountRow> rows = repository.getAccumulatedCounts(runId);
        return new AccumulatedCounts(
                buildNodeCountsFromRows(rows),
                buildEdgeCountsFromRows(rows)
        );
    }

    private Map<String, Map<String, Long>> buildNodeCountsFromRows(List<EpochCountRow> rows) {
        Map<String, Map<String, Long>> result = new HashMap<>();
        for (EpochCountRow row : rows) {
            if (!TYPE_NODE.equals(row.entryType())) continue;
            // Strip prefix: "mcp:step1" -> "step1", "trigger:webhook1" -> "webhook1"
            String label = LabelNormalizer.extractLabel(row.entryKey());
            result.computeIfAbsent(label, k -> new HashMap<>())
                    .merge(row.status().toLowerCase(), (long) row.count(), Long::sum);
        }
        return result;
    }

    private Map<String, Map<String, Long>> buildEdgeCountsFromRows(List<EpochCountRow> rows) {
        Map<String, Map<String, Long>> result = new HashMap<>();
        for (EpochCountRow row : rows) {
            if (!TYPE_EDGE.equals(row.entryType())) continue;
            // Edge keys are stored verbatim ("from->to") - no prefix stripping.
            result.computeIfAbsent(row.entryKey(), k -> new HashMap<>())
                    .merge(row.status().toLowerCase(), (long) row.count(), Long::sum);
        }
        return result;
    }

    /**
     * Bundle of accumulated node and edge counts produced from a single repository query.
     */
    public record AccumulatedCounts(
            Map<String, Map<String, Long>> nodes,
            Map<String, Map<String, Long>> edges) {}

    /**
     * List epoch timestamps for timeline display.
     * Source of truth for epoch start/end - replaces the growing metadata.epochTimestamps array.
     */
    public List<EpochTimestampRow> listEpochTimestamps(String runId) {
        return repository.listEpochTimestamps(runId);
    }

    /**
     * List all epoch headers for a run in one query (avoids N+1).
     * Returns headers with epoch number included.
     */
    public List<EpochHeaderWithEpochRow> listEpochHeaders(String runId) {
        return repository.listEpochHeaders(runId);
    }

    /**
     * Get the epoch header for a specific run and epoch (any trigger).
     */
    public EpochHeaderRow getEpochHeader(String runId, int epoch) {
        return repository.getEpochHeader(runId, epoch);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private Map<String, Object> buildStateMap(int epoch, List<EpochCountRow> rows) {
        Map<String, Map<String, Integer>> nodes = new HashMap<>();
        Map<String, Map<String, Integer>> edges = new HashMap<>();

        for (EpochCountRow row : rows) {
            Map<String, Map<String, Integer>> target = TYPE_NODE.equals(row.entryType()) ? nodes : edges;
            target.computeIfAbsent(row.entryKey(), k -> new HashMap<>())
                    .put(row.status(), row.count());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("epoch", epoch);
        result.put("nodes", nodes);
        result.put("edges", edges);
        return result;
    }

    /**
     * Enrich a state map with data from the epoch header row (EpochState fields).
     *
     * <p>For ACTIVE epochs and elide-default-ON tenants, {@code runningNodeIds}
     * in JSONB is empty by design - Redis holds the authoritative running set
     * under the per-epoch key {@code orchestrator:running:{runId}:{epoch}}.
     * This method overlays the Redis tracker (when present) on top of JSONB
     * so the diagnostic API surfaces actually-running nodes for active epochs.
     *
     * <p>For CLOSED epochs the overlay is intentionally skipped - the running
     * set was already drained at close time, and the per-epoch Redis key was
     * deleted. Reading Redis for a closed epoch would just return empty
     * (which is what JSONB also has post-drain), so the round-trip is wasted.
     */
    private void enrichWithHeader(Map<String, Object> result, EpochHeaderRow header, String runId, int epoch) {
        if (header == null || header.epochStateJson() == null) return;

        try {
            EpochState state = objectMapper.readValue(header.epochStateJson(), EpochState.class);
            result.put("isActive", header.isActive());
            result.put("startedAt", header.startedAt());
            result.put("closedAt", header.closedAt());
            result.put("triggerId", header.triggerId());
            if (header.durationMs() != null) {
                result.put("durationMs", header.durationMs());
            }
            result.put("readyNodeIds", state.getReadyNodeIds());
            result.put("awaitingSignalNodeIds", state.getAwaitingSignalNodeIds());
            result.put("decisionBranches", state.getDecisionBranchesMap());
            result.put("completedNodeIds", state.getCompletedNodeIds());
            result.put("failedNodeIds", state.getFailedNodeIds());
            result.put("skippedNodeIds", state.getSkippedNodeIds());
            // P2.3 site 7: overlay Redis running-state on top of JSONB for ACTIVE epochs.
            // Post-elide (default since 2026-05-08), JSONB.runningNodeIds is empty for
            // active epochs - Redis is the only authoritative source. For closed epochs,
            // JSONB was drained at close time so we skip the Redis lookup (would return
            // empty anyway). Fail-OPEN: a Redis hiccup leaves only the JSONB view, which
            // for a default-ON tenant means an empty set - equivalent to "no diagnostic
            // info available" rather than corrupted data.
            Set<String> running = new HashSet<>(state.getRunningNodeIds());
            if (header.isActive() && runningNodeTracker != null) {
                Map<String, Integer> redisRunning = runningNodeTracker.getRunningCounts(runId, epoch);
                running.addAll(redisRunning.keySet());
            }
            result.put("runningNodeIds", running);
        } catch (JsonProcessingException e) {
            logger.warn("[WorkflowEpoch] Failed to deserialize epoch header: {}", e.getMessage());
        }
    }

    /**
     * Deserialize an EpochHeaderRow into an EpochState. Returns null on failure.
     */
    private EpochState deserializeHeader(EpochHeaderRow header) {
        if (header == null || header.epochStateJson() == null) return null;
        try {
            return objectMapper.readValue(header.epochStateJson(), EpochState.class);
        } catch (JsonProcessingException e) {
            logger.error("[WorkflowEpoch] Failed to deserialize EpochState from header: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Normalize status strings: SUCCESS -> COMPLETED, ERROR -> FAILED.
     * Other statuses pass through unchanged.
     */
    String normalizeStatus(String status) {
        if (status == null) return "UNKNOWN";
        return switch (status.toUpperCase()) {
            case "SUCCESS" -> "COMPLETED";
            case "ERROR" -> "FAILED";
            default -> status.toUpperCase();
        };
    }
}
