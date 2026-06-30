package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionGraph;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.events.EdgeLifecycle;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import com.apimarketplace.orchestrator.utils.WorkflowUtils;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * V2 Edge status service - publishes edge events to WorkflowEventBus.
 * Also updates StateSnapshot for persistent edge counts.
 */
@Component
public class EdgeStatusService {

    private static final Logger logger = LoggerFactory.getLogger(EdgeStatusService.class);

    private final WorkflowEventPublisher eventPublisher;
    private final StateSnapshotService stateSnapshotService;

    /**
     * Thread-local batch collector for edge status writes.
     * When active, edge DB writes are collected and flushed in a single transaction.
     * Thread-safe: each execution thread has its own batch (no cross-thread sharing in AUTO mode).
     *
     * <p>Uses int[2] per edge key: [0]=completed count, [1]=skipped count.
     * This correctly accumulates when multiple items traverse the same edge
     * (e.g., split emitting N edges to the same target).
     */
    private static final ThreadLocal<Map<String, int[]>> pendingEdgeBatch = new ThreadLocal<>();

    public EdgeStatusService(
            WorkflowEventPublisher eventPublisher,
            StateSnapshotService stateSnapshotService) {
        this.eventPublisher = eventPublisher;
        this.stateSnapshotService = stateSnapshotService;
    }

    /**
     * Begin collecting edge DB writes instead of writing them individually.
     * Call {@link #flushEdgeBatch(String)} to write all collected edges in a single transaction.
     */
    public void beginEdgeBatch() {
        pendingEdgeBatch.set(new LinkedHashMap<>());
    }

    /**
     * Flush all collected edge writes in a single DB transaction and clear the batch.
     * If no batch is active or the batch is empty, returns empty map.
     * ThreadLocal is always cleaned up, even if the DB write fails.
     *
     * <p>Returns a map of edgeKey to (status, count) for each status that was accumulated.
     * Callers that need the counts (epoch recording) can iterate the entries.
     *
     * @param runId the workflow run ID
     * @return map of edgeKey to count-aware entries, or empty map
     */
    public Map<String, Map.Entry<String, Integer>> flushEdgeBatch(String runId) {
        Map<String, int[]> batch = pendingEdgeBatch.get();
        if (batch == null || batch.isEmpty()) {
            pendingEdgeBatch.remove();
            return Map.of();
        }
        try {
            // Separate completed and skipped into two DB calls because
            // recordEdgeStatusesBatch uses edgeKey as map key - same edge
            // can have both statuses (e.g., 3 items completed + 2 skipped).
            Map<String, Map.Entry<String, Integer>> completedMap = new LinkedHashMap<>();
            Map<String, Map.Entry<String, Integer>> skippedMap = new LinkedHashMap<>();
            for (var entry : batch.entrySet()) {
                String edgeKey = entry.getKey();
                int[] counts = entry.getValue();
                if (counts[0] > 0) {
                    completedMap.put(edgeKey, Map.entry("COMPLETED", counts[0]));
                }
                if (counts[1] > 0) {
                    skippedMap.put(edgeKey, Map.entry("SKIPPED", counts[1]));
                }
            }
            if (!completedMap.isEmpty()) {
                stateSnapshotService.recordEdgeStatusesBatch(runId, completedMap);
            }
            if (!skippedMap.isEmpty()) {
                stateSnapshotService.recordEdgeStatusesBatch(runId, skippedMap);
            }

            // Build return value for epoch recording.
            // Same edge can have both COMPLETED and SKIPPED entries (different items
            // routed to different branches). Use suffixed keys so callers see both.
            // The epoch table's unique constraint includes status, so both are recorded.
            Map<String, Map.Entry<String, Integer>> result = new LinkedHashMap<>();
            result.putAll(completedMap);
            for (var entry : skippedMap.entrySet()) {
                String key = entry.getKey();
                if (result.containsKey(key)) {
                    // Edge has both statuses - use suffixed key for skipped variant
                    result.put(key + "::SKIPPED", entry.getValue());
                } else {
                    result.put(key, entry.getValue());
                }
            }

            logger.debug("[EdgeStatusService] Flushed edge batch: runId={}, edgeCount={}", runId, batch.size());
            return result;
        } finally {
            pendingEdgeBatch.remove();
        }
    }

    public void markIncomingEdgesRunning(WorkflowExecution execution, String stepId) {
        updateIncomingEdges(execution, stepId, EdgeLifecycle.RUNNING, null);
    }

    public void markIncomingEdgesRunning(WorkflowExecution execution, String stepId, Integer itemIndex) {
        updateIncomingEdges(execution, stepId, EdgeLifecycle.RUNNING, itemIndex);
    }

    public void markIncomingEdgesCompleted(WorkflowExecution execution, String stepId) {
        updateIncomingEdges(execution, stepId, EdgeLifecycle.COMPLETED, null);
    }

    public void markIncomingEdgesCompleted(WorkflowExecution execution, String stepId, Integer itemIndex) {
        updateIncomingEdges(execution, stepId, EdgeLifecycle.COMPLETED, itemIndex);
    }

    public void markIncomingEdgesSkipped(WorkflowExecution execution, String stepId) {
        updateIncomingEdges(execution, stepId, EdgeLifecycle.SKIPPED, null);
    }

    public void markIncomingEdgesSkipped(WorkflowExecution execution, String stepId, Integer itemIndex) {
        updateIncomingEdges(execution, stepId, EdgeLifecycle.SKIPPED, itemIndex);
    }

    public void markEdgeRunning(WorkflowExecution execution, String from, String to) {
        emitEdgeEvent(execution, from, to, EdgeLifecycle.RUNNING, null);
    }

    public void markEdgeRunning(WorkflowExecution execution, String from, String to, Integer itemIndex) {
        emitEdgeEvent(execution, from, to, EdgeLifecycle.RUNNING, itemIndex, null);
    }

    public void markEdgeRunning(WorkflowExecution execution, String from, String to, Integer itemIndex, Integer iteration) {
        emitEdgeEvent(execution, from, to, EdgeLifecycle.RUNNING, itemIndex, iteration);
    }

    public void markEdgeCompleted(WorkflowExecution execution, String from, String to) {
        emitEdgeEvent(execution, from, to, EdgeLifecycle.COMPLETED, null);
    }

    public void markEdgeCompleted(WorkflowExecution execution, String from, String to, Integer itemIndex) {
        emitEdgeEvent(execution, from, to, EdgeLifecycle.COMPLETED, itemIndex, null);
    }

    public void markEdgeCompleted(WorkflowExecution execution, String from, String to, Integer itemIndex, Integer iteration) {
        emitEdgeEvent(execution, from, to, EdgeLifecycle.COMPLETED, itemIndex, iteration);
    }

    public void markEdgeSkipped(WorkflowExecution execution, String from, String to) {
        emitEdgeEvent(execution, from, to, EdgeLifecycle.SKIPPED, null, null);
    }

    public void markEdgeSkipped(WorkflowExecution execution, String from, String to, Integer itemIndex) {
        emitEdgeEvent(execution, from, to, EdgeLifecycle.SKIPPED, itemIndex, null);
    }

    public void markEdgeSkipped(WorkflowExecution execution, String from, String to, Integer itemIndex, Integer iteration) {
        emitEdgeEvent(execution, from, to, EdgeLifecycle.SKIPPED, itemIndex, iteration);
    }

    /**
     * V2: Register all workflow edges for status tracking.
     * Uses port-preserving normalization so branching node edges
     * (e.g., agent:classify:category_0 -> table:target) are registered
     * with their port, enabling per-port status tracking.
     */
    public void registerWorkflowEdges(WorkflowExecution execution) {
        if (execution == null || execution.getPlan() == null) {
            return;
        }

        logger.debug("[EdgeStatusService] registerWorkflowEdges - runId={}, edges count={}",
                   execution.getRunId(), execution.getPlan().getEdges().size());

        // V2: Register all simple edges with port-preserving normalization
        for (Edge edge : execution.getPlan().getEdges()) {
            if (edge == null || edge.from() == null || edge.to() == null) {
                continue;
            }

            String fromKey = normalizePreservingPort(edge.from());
            String toKey = normalizePreservingPort(edge.to());

            if (fromKey != null && toKey != null) {
                registerEdge(execution, fromKey, toKey);
            }
        }
    }

    public void markDescendantEdgesSkipped(WorkflowExecution execution,
                                           String rootStepId,
                                           Set<String> descendantFilter) {
        if (execution == null || rootStepId == null || execution.getPlan() == null) {
            return;
        }
        ExecutionGraph graph = execution.getPlan().getExecutionGraph();
        if (graph == null) {
            return;
        }
        String normalizedRoot = normalize(rootStepId);
        if (normalizedRoot == null) {
            return;
        }
        Set<String> normalizedFilter = null;
        if (descendantFilter != null && !descendantFilter.isEmpty()) {
            normalizedFilter = descendantFilter.stream()
                                               .map(this::normalize)
                                               .filter(Objects::nonNull)
                                               .collect(Collectors.toSet());
        }
        ArrayDeque<String> toVisit = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        toVisit.add(normalizedRoot);
        visited.add(normalizedRoot);

        while (!toVisit.isEmpty()) {
            String current = toVisit.poll();
            Set<String> dependents = graph.getDependents(current);
            if (dependents == null || dependents.isEmpty()) {
                continue;
            }
            for (String dependent : dependents) {
                String normalizedDependent = normalize(dependent);
                if (normalizedDependent == null) {
                    continue;
                }
                boolean shouldRecord = normalizedFilter == null || normalizedFilter.contains(normalizedDependent);
                if (shouldRecord) {
                    emitEdgeEvent(execution, current, normalizedDependent, EdgeLifecycle.SKIPPED, null);
                }
                if (visited.add(normalizedDependent)) {
                    toVisit.add(normalizedDependent);
                }
            }
        }
    }

    private void registerEdge(WorkflowExecution execution, String from, String to) {
        emitEdgeEvent(execution, from, to, EdgeLifecycle.REGISTERED, null);
    }

    private void updateIncomingEdges(WorkflowExecution execution, String stepId, EdgeLifecycle lifecycle, Integer itemIndex) {
        if (execution == null || stepId == null || execution.getPlan() == null) {
            return;
        }

        ExecutionGraph graph = execution.getPlan().getExecutionGraph();
        if (graph == null) {
            return;
        }
        Set<String> parents = graph.getDependencies(stepId);
        if (parents == null || parents.isEmpty()) {
            return;
        }
        String normalizedChild = normalize(stepId);
        for (String parent : parents) {
            emitEdgeEvent(execution, parent, normalizedChild, lifecycle, itemIndex);
        }
    }

    private void emitEdgeEvent(WorkflowExecution execution,
                               String from,
                               String to,
                               EdgeLifecycle lifecycle,
                               Integer itemIndex) {
        emitEdgeEvent(execution, from, to, lifecycle, itemIndex, null);
    }

    private void emitEdgeEvent(WorkflowExecution execution,
                               String from,
                               String to,
                               EdgeLifecycle lifecycle,
                               Integer itemIndex,
                               Integer iteration) {
        if (execution == null || from == null || to == null) {
            return;
        }

        // Use port-preserving normalization so branching node edges
        // (e.g., "agent:classify:category_0") retain their port in the edge key.
        // This matches the registration in registerWorkflowEdges().
        String normalizedFrom = normalizePreservingPort(from);
        String normalizedTo = normalizePreservingPort(to);
        if (normalizedFrom == null || normalizedTo == null) {
            return;
        }

        // Record in StateSnapshot (persistent, single source of truth)
        // Only record terminal states (COMPLETED, SKIPPED) to avoid running count issues
        if (lifecycle == EdgeLifecycle.COMPLETED || lifecycle == EdgeLifecycle.SKIPPED) {
            Map<String, int[]> batch = pendingEdgeBatch.get();
            if (batch != null) {
                // Batch mode: accumulate counts for single-transaction flush.
                // int[0]=completed, int[1]=skipped. Prevents dedup when multiple items
                // traverse the same edge (e.g., split emitting N edges to same target).
                String edgeKey = normalizedFrom + "->" + normalizedTo;
                int[] counts = batch.computeIfAbsent(edgeKey, k -> new int[2]);
                if (lifecycle == EdgeLifecycle.COMPLETED) counts[0]++;
                else counts[1]++;
            } else {
                // Immediate mode: write to DB now (fallback for non-batched callers)
                stateSnapshotService.recordEdgeStatus(
                    execution.getRunId(),
                    normalizedFrom,
                    normalizedTo,
                    lifecycle.name()
                );
            }
        }

        String edgeId = buildEdgeId(normalizedFrom, normalizedTo);
        eventPublisher.emitEdge(
                execution.getRunId(),
                edgeId,
                normalizedFrom,
                normalizedTo,
                lifecycle,
                itemIndex,
                iteration
        );
    }

    private String buildEdgeId(String from, String to) {
        return from + "->" + to;
    }

    /**
     * Normalizes an edge reference preserving the port suffix.
     * Used for edge registration and emission where ports distinguish
     * individual branch edges (e.g., classify categories to the same target).
     *
     * Examples:
     * - "agent:classifyemail:category_0" -> "agent:classifyemail:category_0" (port preserved)
     * - "core:decision:if" -> "core:decision:if" (port preserved)
     * - "mcp:step1" -> "mcp:step1" (no port, unchanged)
     */
    private String normalizePreservingPort(String ref) {
        if (ref == null) {
            return null;
        }

        // Remove item/iteration scopes first
        String cleaned = ref;
        int itemIdx = cleaned.indexOf("#item-");
        if (itemIdx > 0) {
            cleaned = cleaned.substring(0, itemIdx);
        }
        int iterIdx = cleaned.indexOf("#iter-");
        if (iterIdx > 0) {
            cleaned = cleaned.substring(0, iterIdx);
        }

        // Parse the full ref (preserving port)
        EdgeRefParser.EdgeRef parsed = EdgeRefParser.parse(cleaned);
        if (parsed != null) {
            String normalizedLabel = LabelNormalizer.normalizeLabel(parsed.nodeLabel());
            if (normalizedLabel != null) {
                String base = parsed.nodeType() + ":" + normalizedLabel;
                // Preserve port if present
                if (parsed.hasPort()) {
                    return base + ":" + parsed.port();
                }
                return base;
            }
        }

        // Fallback for non-standard formats
        String normalized = WorkflowUtils.normalizeStepId(cleaned);
        return normalized != null ? normalized : cleaned;
    }

    /**
     * Normalizes a step/trigger/core/agent node ID for streaming events.
     * Strips ports to get the base node key. Used for node-level operations
     * like updateIncomingEdges and markDescendantEdgesSkipped.
     *
     * Uses EdgeRefParser.getNodeKey() to properly extract node key from port-based references.
     * This correctly handles:
     * - "agent:classifyemail:category_0" -> "agent:classifyemail"
     * - "core:decision:if" -> "core:decision"
     * - "mcp:step1" -> "mcp:step1" (no port, unchanged)
     */
    private String normalize(String stepId) {
        if (stepId == null) {
            return null;
        }

        // Remove item/iteration scopes first
        String cleaned = stepId;
        int itemIndex = cleaned.indexOf("#item-");
        if (itemIndex > 0) {
            cleaned = cleaned.substring(0, itemIndex);
        }
        int iterIndex = cleaned.indexOf("#iter-");
        if (iterIndex > 0) {
            cleaned = cleaned.substring(0, iterIndex);
        }

        // Use EdgeRefParser to extract node key (handles ports correctly)
        // This converts "agent:classifyemail:category_0" -> "agent:classifyemail"
        // and "core:decision:if" -> "core:decision"
        String nodeKey = EdgeRefParser.getNodeKey(cleaned);
        if (nodeKey != null) {
            // EdgeRefParser successfully parsed - nodeKey is already in correct format
            // Just normalize the label part for consistency
            EdgeRefParser.EdgeRef ref = EdgeRefParser.parse(nodeKey);
            if (ref != null) {
                String normalizedLabel = LabelNormalizer.normalizeLabel(ref.nodeLabel());
                if (normalizedLabel != null) {
                    return ref.nodeType() + ":" + normalizedLabel;
                }
            }
            return nodeKey;
        }

        // Fallback for non-standard formats
        String normalized = WorkflowUtils.normalizeStepId(cleaned);
        return normalized != null ? normalized : cleaned;
    }
}
