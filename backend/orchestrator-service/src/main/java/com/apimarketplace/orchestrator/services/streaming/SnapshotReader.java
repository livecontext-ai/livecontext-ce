package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunStatusEntity;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowRunStatusService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.state.StateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads snapshots for initial WebSocket connection with proper transaction boundaries.
 *
 * <p>This service ensures that DB connections are released immediately after reading,
 * preventing connection leaks.
 *
 * <p>Key design principle: All DB reads happen in transactional methods that complete
 * quickly and release the DB connection immediately.
 *
 * <p>Usage:
 * <pre>
 * Optional&lt;SnapshotData&gt; snapshot = sseSnapshotReader.readSnapshot(runId);
 * // Use snapshot data for WebSocket or Redis publishing
 * </pre>
 */
@Service
public class SnapshotReader {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotReader.class);

    private final StateSnapshotService stateSnapshotService;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowRunStatusService workflowRunStatusService;

    public SnapshotReader(
            StateSnapshotService stateSnapshotService,
            WorkflowRunRepository workflowRunRepository,
            WorkflowRunStatusService workflowRunStatusService) {
        this.stateSnapshotService = stateSnapshotService;
        this.workflowRunRepository = workflowRunRepository;
        this.workflowRunStatusService = workflowRunStatusService;
    }

    /**
     * Reads snapshot for initial connection.
     *
     * <p>This method uses REQUIRES_NEW propagation to ensure a completely
     * isolated transaction that releases the DB connection immediately
     * after the read completes, regardless of any outer transaction context.
     *
     * @param runId The workflow run ID
     * @return Optional containing the snapshot data, or empty if not found
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<SnapshotData> readSnapshot(String runId) {
        if (runId == null) {
            return Optional.empty();
        }

        logger.debug("[SnapshotReader] Reading snapshot for runId={}", runId);

        try {
            // First, try to get live snapshot from StateSnapshot (current execution state)
            StateSnapshot stateSnapshot = stateSnapshotService.getSnapshot(runId);

            if (stateSnapshot != null && !stateSnapshot.isEmpty()) {
                Map<String, Object> snapshot = buildSnapshotFromState(runId, stateSnapshot);
                logger.info("[SnapshotReader] Built live snapshot for runId={}, nodes={}, edges={}",
                    runId, stateSnapshot.getNodes().size(), stateSnapshot.getEdges().size());
                return Optional.of(new SnapshotData(snapshot, null, SnapshotSource.LIVE));
            }

            // Fall back to completed workflow status (for reconnection to finished workflows)
            return readFromWorkflowStatus(runId);

        } catch (Exception e) {
            logger.warn("[SnapshotReader] Error reading snapshot for runId={}: {}", runId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Reads snapshot from workflow status entity (for completed workflows).
     */
    private Optional<SnapshotData> readFromWorkflowStatus(String runId) {
        Optional<WorkflowRunEntity> runEntityOpt = workflowRunRepository.findByRunIdPublic(runId);
        if (runEntityOpt.isEmpty()) {
            logger.debug("[SnapshotReader] No workflow run found for runId={}", runId);
            return Optional.empty();
        }

        WorkflowRunEntity runEntity = runEntityOpt.get();
        UUID workflowRunId = runEntity.getId();

        Optional<WorkflowRunStatusEntity> statusEntityOpt = workflowRunStatusService.findByRunId(workflowRunId);
        if (statusEntityOpt.isEmpty()) {
            logger.debug("[SnapshotReader] No status entity found for runId={}", runId);
            return Optional.empty();
        }

        WorkflowRunStatusEntity statusEntity = statusEntityOpt.get();
        Map<String, Object> payload = statusEntity.getPayload();

        if (payload != null && !payload.isEmpty()) {
            String status = statusEntity.getStatus().getValue().toUpperCase();
            logger.info("[SnapshotReader] Loaded completed snapshot for runId={} (status={})", runId, status);
            return Optional.of(new SnapshotData(payload, status, SnapshotSource.DATABASE));
        }

        return Optional.empty();
    }

    /**
     * Builds snapshot from StateSnapshot.
     */
    private Map<String, Object> buildSnapshotFromState(String runId, StateSnapshot stateSnapshot) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("runId", runId);
        snapshot.put("timestamp", System.currentTimeMillis());

        // Build steps with statusCounts
        List<Map<String, Object>> steps = buildSteps(stateSnapshot);
        snapshot.put("steps", steps);

        // Build edges
        List<Map<String, Object>> edges = buildEdges(stateSnapshot);
        snapshot.put("edges", edges);

        // Interfaces (empty - no longer tracked in-memory)
        snapshot.put("interfaces", List.of());

        // Empty collections for other fields
        snapshot.put("loops", List.of());
        snapshot.put("merges", List.of());
        snapshot.put("logs", List.of());
        snapshot.put("agentToolCalls", List.of());

        return snapshot;
    }

    private List<Map<String, Object>> buildSteps(StateSnapshot stateSnapshot) {
        List<Map<String, Object>> steps = new ArrayList<>();

        for (Map.Entry<String, StateSnapshot.NodeCounts> entry : stateSnapshot.getNodes().entrySet()) {
            String nodeId = entry.getKey();
            StateSnapshot.NodeCounts counts = entry.getValue();

            if (counts.total() == 0) {
                continue;
            }

            Map<String, Object> step = new LinkedHashMap<>();
            step.put("id", nodeId);
            step.put("label", StateUtils.extractNodeLabel(nodeId));

            // Determine status from counts
            String status = determineStatus(counts);
            step.put("status", status);

            // Add statusCounts
            Map<String, Object> statusCounts = new LinkedHashMap<>();
            statusCounts.put("running", counts.running());
            statusCounts.put("completed", counts.completed());
            statusCounts.put("failed", counts.failed());
            statusCounts.put("skipped", counts.skipped());
            statusCounts.put("total", counts.total());
            step.put("statusCounts", statusCounts);

            steps.add(step);
        }

        // Sort by ID for consistent ordering
        steps.sort((a, b) -> {
            String first = Objects.toString(a.get("id"), "");
            String second = Objects.toString(b.get("id"), "");
            return first.compareTo(second);
        });

        return steps;
    }

    private String determineStatus(StateSnapshot.NodeCounts counts) {
        if (counts.running() > 0) {
            return "running";
        } else if (counts.failed() > 0) {
            return "failed";
        } else if (counts.skipped() > 0 && counts.completed() == 0) {
            return "skipped";
        } else if (counts.completed() > 0) {
            return "completed";
        }
        return "pending";
    }

    private List<Map<String, Object>> buildEdges(StateSnapshot stateSnapshot) {
        List<Map<String, Object>> edges = new ArrayList<>();

        for (Map.Entry<String, StateSnapshot.EdgeCounts> entry : stateSnapshot.getEdges().entrySet()) {
            String edgeKey = entry.getKey(); // "from->to"
            StateSnapshot.EdgeCounts counts = entry.getValue();

            // Skip edges with no activity
            if (counts.total() == 0) {
                continue;
            }

            // Parse from->to
            String[] parts = edgeKey.split("->");
            if (parts.length != 2) {
                continue;
            }
            String from = parts[0];
            String to = parts[1];

            // Skip virtual loop nodes
            if (StateUtils.isVirtualLoopNodeId(from) || StateUtils.isVirtualLoopNodeId(to)) {
                continue;
            }

            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("id", edgeKey);
            edge.put("from", from);
            edge.put("to", to);
            edge.put("running", counts.running());
            edge.put("completed", counts.completed());
            edge.put("skipped", counts.skipped());

            edges.add(edge);
        }

        // Sort by ID for consistent ordering
        edges.sort((a, b) -> {
            String first = Objects.toString(a.get("id"), "");
            String second = Objects.toString(b.get("id"), "");
            return first.compareTo(second);
        });

        return edges;
    }

    /**
     * Snapshot data returned by the reader.
     */
    public record SnapshotData(
            Map<String, Object> snapshot,
            String terminalStatus,
            SnapshotSource source
    ) {
        public boolean hasTerminalStatus() {
            return terminalStatus != null;
        }
    }

    /**
     * Source of the snapshot data.
     */
    public enum SnapshotSource {
        LIVE,      // From current execution state
        DATABASE   // From completed workflow status
    }
}
