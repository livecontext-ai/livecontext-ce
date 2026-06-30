package com.apimarketplace.orchestrator.services.streaming.state;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.events.AgentToolCallEvent;
import com.apimarketplace.orchestrator.services.streaming.events.EdgeLifecycle;
import com.apimarketplace.orchestrator.services.streaming.events.LoopEvent;
import com.apimarketplace.orchestrator.services.streaming.events.LoopEventType;
import com.apimarketplace.orchestrator.services.streaming.events.MergeEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the in-memory state for a single workflow run.
 * Tracks steps, edges, loops, merges, logs, and workflow status.
 * Thread-safe for concurrent updates.
 */
public final class RunState {

    private static final Logger log = LoggerFactory.getLogger(RunState.class);

    private final String runId;
    private final StateSnapshotService stateSnapshotService;
    private final Map<String, Map<String, Object>> steps = new ConcurrentHashMap<>();
    // edges cache removed - now reads from DB via StateSnapshotService
    private final Map<String, Map<String, Object>> loops = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> merges = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> logs = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> agentToolCalls = Collections.synchronizedList(new ArrayList<>());
    private volatile Map<String, Object> workflowStatus;
    private volatile Map<String, Object> workflowStatistics;
    private volatile boolean terminal;

    public RunState(String runId, StateSnapshotService stateSnapshotService) {
        this.runId = runId;
        this.stateSnapshotService = stateSnapshotService;
    }

    public String getRunId() {
        return runId;
    }

    /**
     * Update step state. StatusCounts are read from DB in snapshot(), not cached here.
     */
    public void updateStep(String runId, String stepId, Map<String, Object> payload) {
        if (stepId == null || payload == null) {
            return;
        }

        steps.compute(stepId, (key, existing) -> {
            Map<String, Object> result = existing != null ? new LinkedHashMap<>(existing) : new LinkedHashMap<>();

            // Update all fields from new payload (except internal fields)
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                if (!entry.getKey().startsWith("_")) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }

            // statusCounts will be read from DB in snapshot()
            return result;
        });
    }

    public void updateEdge(String edgeId, String from, String to, EdgeLifecycle lifecycle, Integer itemIndex) {
        updateEdge(edgeId, from, to, lifecycle, itemIndex, null);
    }

    /**
     * Edge updates are now persisted directly to DB via StateSnapshotService.
     * This method is kept for API compatibility but does not cache in memory.
     */
    public void updateEdge(String edgeId, String from, String to, EdgeLifecycle lifecycle, Integer itemIndex, Integer iteration) {
        // No-op: edges are read from DB in snapshot(), not cached in memory
        log.debug("[RunState] updateEdge called (no-op, reads from DB): edgeId={}, lifecycle={}", edgeId, lifecycle);
    }

    public void updateWorkflowStatus(Map<String, Object> payload,
                                      String status,
                                      String message,
                                      boolean isTerminal) {
        Map<String, Object> snapshot = payload != null
            ? new LinkedHashMap<>(payload)
            : new LinkedHashMap<>();
        snapshot.put("status", status);
        if (message != null) {
            snapshot.put("message", message);
        }
        snapshot.putIfAbsent("timestamp", System.currentTimeMillis());
        this.workflowStatus = snapshot;
        if (isTerminal) {
            this.terminal = true;
        }
    }

    public void updateWorkflowStatistics(Map<String, Object> payload) {
        if (payload == null) {
            return;
        }
        this.workflowStatistics = new LinkedHashMap<>(payload);
    }

    public void updateLoop(String loopId, LoopEvent event) {
        if (loopId == null) {
            return;
        }

        // Use compute to handle concurrent updates and aggregate across items
        loops.compute(loopId, (key, existing) -> {
            Map<String, Object> result = existing != null ? new LinkedHashMap<>(existing) : new LinkedHashMap<>();

            result.put("loopId", loopId);
            result.put("type", event.type().name());
            result.put("timestamp", event.timestamp());

            // Extract iteration info from new event
            Map<String, Object> newPayload = event.payload();
            int newIteration = 0;
            int newItemIndex = -1;
            Integer newMaxIterations = null;
            String newExitReason = null;

            if (newPayload != null) {
                Object iterObj = newPayload.get("currentIteration");
                if (iterObj == null) {
                    iterObj = newPayload.get("completedIterations");
                }
                if (iterObj instanceof Number) {
                    newIteration = ((Number) iterObj).intValue();
                }

                Object itemIdxObj = newPayload.get("itemIndex");
                if (itemIdxObj instanceof Number) {
                    newItemIndex = ((Number) itemIdxObj).intValue();
                }

                Object maxIterObj = newPayload.get("maxIterations");
                if (maxIterObj instanceof Number) {
                    newMaxIterations = ((Number) maxIterObj).intValue();
                }

                newExitReason = (String) newPayload.get("exitReason");
            }

            // Get existing aggregated values
            @SuppressWarnings("unchecked")
            Map<String, Object> existingPayload = (Map<String, Object>) result.get("payload");
            int maxIterationSeen = 0;
            int totalIterations = 0;
            int completedItems = 0;

            if (existingPayload != null) {
                Object maxIterSeenObj = existingPayload.get("maxIterationSeen");
                if (maxIterSeenObj instanceof Number) {
                    maxIterationSeen = ((Number) maxIterSeenObj).intValue();
                }
                Object totalIterObj = existingPayload.get("totalIterations");
                if (totalIterObj instanceof Number) {
                    totalIterations = ((Number) totalIterObj).intValue();
                }
                Object completedItemsObj = existingPayload.get("completedItems");
                if (completedItemsObj instanceof Number) {
                    completedItems = ((Number) completedItemsObj).intValue();
                }
            }

            // Update aggregates
            maxIterationSeen = Math.max(maxIterationSeen, newIteration);
            totalIterations = maxIterationSeen + 1; // Total iterations = max + 1 (0-based indexing)

            // If this is a COMPLETED event, increment completed items
            if (event.type() == LoopEventType.COMPLETED) {
                completedItems += 1;
            }

            // Build aggregated payload
            Map<String, Object> aggregatedPayload = new LinkedHashMap<>();
            aggregatedPayload.put("loopNodeId", loopId);
            aggregatedPayload.put("currentIteration", maxIterationSeen);
            aggregatedPayload.put("completedIterations", maxIterationSeen);
            aggregatedPayload.put("maxIterationSeen", maxIterationSeen);
            aggregatedPayload.put("totalIterations", totalIterations);
            aggregatedPayload.put("completedItems", completedItems);

            if (newMaxIterations != null) {
                aggregatedPayload.put("maxIterations", newMaxIterations);
            } else if (existingPayload != null && existingPayload.containsKey("maxIterations")) {
                aggregatedPayload.put("maxIterations", existingPayload.get("maxIterations"));
            }

            if (newExitReason != null) {
                aggregatedPayload.put("exitReason", newExitReason);
            }

            // Keep the last item index for debugging
            if (newItemIndex >= 0) {
                aggregatedPayload.put("lastItemIndex", newItemIndex);
            }

            // Copy over other fields from the original payload
            if (newPayload != null) {
                if (newPayload.containsKey("loopLabel")) {
                    aggregatedPayload.put("loopLabel", newPayload.get("loopLabel"));
                }
            }

            result.put("payload", aggregatedPayload);

            log.debug("Loop state updated: loopId={}, maxIteration={}, totalIterations={}, completedItems={}, newEventIter={}",
                loopId, maxIterationSeen, totalIterations, completedItems, newIteration);

            return result;
        });
    }

    public void updateMerge(String mergeId, MergeEvent event) {
        if (mergeId == null) {
            return;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("mergeId", mergeId);
        snapshot.put("type", event.type().name());
        snapshot.put("timestamp", event.timestamp());
        if (event.payload() != null) {
            snapshot.put("payload", new LinkedHashMap<>(event.payload()));
        }
        merges.put(mergeId, snapshot);
    }

    public void updateRetry(String stepId, int retryIndex, Map<String, Object> payload) {
        if (stepId == null) {
            return;
        }
        steps.computeIfPresent(stepId, (key, existing) -> {
            Map<String, Object> copy = new LinkedHashMap<>(existing);
            copy.put("retryIndex", retryIndex);
            if (payload != null) {
                payload.forEach((k, v) -> {
                    if (v != null) {
                        copy.put(k, v);
                    }
                });
            }
            return copy;
        });
    }

    public void appendLog(String level, String message, long timestamp) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("level", level != null ? level : "INFO");
        entry.put("message", message);
        entry.put("timestamp", timestamp);
        logs.add(entry);
        if (logs.size() > 2000) {
            logs.remove(0);
        }
    }

    /**
     * Update agent tool call tracking for real-time streaming display.
     *
     * @param event The agent tool call event
     */
    public void updateAgentToolCall(AgentToolCallEvent event) {
        if (event == null) {
            return;
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("nodeId", event.nodeId());
        entry.put("toolName", event.toolName());
        entry.put("toolCallId", event.toolCallId());
        entry.put("phase", event.phase().name());
        entry.put("itemIndex", event.itemIndex());
        if (event.iteration() != null) {
            entry.put("iteration", event.iteration());
        }
        entry.put("timestamp", event.timestamp());
        if (event.payload() != null && !event.payload().isEmpty()) {
            entry.put("payload", new LinkedHashMap<>(event.payload()));
        }

        agentToolCalls.add(entry);

        // Keep only recent tool calls (last 100 per run)
        if (agentToolCalls.size() > 100) {
            agentToolCalls.remove(0);
        }

        log.debug("Agent tool call recorded: nodeId={}, tool={}, phase={}",
            event.nodeId(), event.toolName(), event.phase());
    }

    public RunStateStore.RunSnapshot snapshot() {
        // Read node counts from DB
        StateSnapshot dbSnapshot = stateSnapshotService != null
            ? stateSnapshotService.getSnapshot(runId)
            : StateSnapshot.empty();
        Map<String, StateSnapshot.NodeCounts> dbNodes = dbSnapshot.getNodes();

        List<Map<String, Object>> orderedSteps = steps.values().stream()
            .map(value -> {
                // Copy the map but exclude internal fields
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                    if (!entry.getKey().startsWith("_")) {
                        copy.put(entry.getKey(), entry.getValue());
                    }
                }

                // Get statusCounts from DB (single source of truth)
                // DB keys use full prefixed IDs (e.g., "mcp:my_step"), matching the step's "id" field
                String stepId = Objects.toString(copy.get("id"), "");
                StateSnapshot.NodeCounts counts = dbNodes.get(stepId);
                if (counts != null && counts.total() > 0) {
                    Map<String, Object> statusCounts = new LinkedHashMap<>();
                    statusCounts.put("running", counts.running());
                    statusCounts.put("completed", counts.completed());
                    statusCounts.put("failed", counts.failed());
                    statusCounts.put("skipped", counts.skipped());
                    statusCounts.put("total", counts.total());
                    copy.put("statusCounts", statusCounts);
                }

                return copy;
            })
            // Filter out virtual loop nodes (handles both formats: ::controller and controller suffix)
            .filter(step -> {
                String id = Objects.toString(step.get("id"), "");
                return !StateUtils.isVirtualLoopNodeId(id);
            })
            .sorted((a, b) -> {
                String first = Objects.toString(a.get("id"), "");
                String second = Objects.toString(b.get("id"), "");
                return first.compareTo(second);
            })
            .toList();

        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        // Read edges from DB (already loaded above)
        List<Map<String, Object>> edgePayloads = new ArrayList<>();
        Map<String, StateSnapshot.EdgeCounts> dbEdges = dbSnapshot.getEdges();
        log.info("[RunState] snapshot() - Reading {} nodes, {} edges from DB for runId={}", dbNodes.size(), dbEdges.size(), runId);

        for (Map.Entry<String, StateSnapshot.EdgeCounts> entry : dbEdges.entrySet()) {
                String edgeKey = entry.getKey(); // "from->to"
                StateSnapshot.EdgeCounts counts = entry.getValue();

                // Parse from->to
                String[] parts = edgeKey.split("->");
                if (parts.length != 2) {
                    continue;
                }
                String from = parts[0];
                String to = parts[1];

                // Build payload
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("id", edgeKey);
                payload.put("from", from);
                payload.put("to", to);
                payload.put("running", counts.running());
                payload.put("completed", counts.completed());
                payload.put("skipped", counts.skipped());

                // Apply filters
                boolean hasActivity = StateUtils.hasEdgeActivity(payload);
                boolean isVirtualLoop = StateUtils.isVirtualLoopNodeId(edgeKey)
                    || StateUtils.isVirtualLoopNodeId(from)
                    || StateUtils.isVirtualLoopNodeId(to);

            if (hasActivity && !isVirtualLoop) {
                edgePayloads.add(payload);
                log.info("  Edge: {} | running={} completed={} skipped={} | passesFilter=true",
                    edgeKey, counts.running(), counts.completed(), counts.skipped());
            }
        }

        // Sort edges
        edgePayloads.sort((a, b) -> {
            String first = Objects.toString(a.get("id"), "");
            String second = Objects.toString(b.get("id"), "");
            return first.compareTo(second);
        });

        log.info("[RunState] snapshot() - Edges from DB after filters: {}", edgePayloads.size());
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        List<Map<String, Object>> loopSnapshots = loops.values().stream()
            .map(value -> (Map<String, Object>) new LinkedHashMap<>(value))
            .sorted((a, b) -> {
                String first = Objects.toString(a.get("loopId"), "");
                String second = Objects.toString(b.get("loopId"), "");
                return first.compareTo(second);
            })
            .toList();

        List<Map<String, Object>> mergeSnapshots = merges.values().stream()
            .map(value -> (Map<String, Object>) new LinkedHashMap<>(value))
            .sorted((a, b) -> {
                String first = Objects.toString(a.get("mergeId"), "");
                String second = Objects.toString(b.get("mergeId"), "");
                return first.compareTo(second);
            })
            .toList();

        List<Map<String, Object>> logSnapshots = new ArrayList<>();
        synchronized (logs) {
            logs.forEach(entry -> logSnapshots.add(new LinkedHashMap<>(entry)));
        }

        Map<String, Object> workflowStatusSnapshot = workflowStatus != null
            ? new LinkedHashMap<>(workflowStatus)
            : null;
        Map<String, Object> workflowStatsSnapshot = workflowStatistics != null
            ? new LinkedHashMap<>(workflowStatistics)
            : null;

        // Dynamically recalculate progress from step statuses
        if (workflowStatusSnapshot != null && !orderedSteps.isEmpty()) {
            int stepsCompleted = 0;
            int stepsFailed = 0;
            int stepsSkipped = 0;
            int totalSteps = orderedSteps.size();

            for (Map<String, Object> step : orderedSteps) {
                String status = Objects.toString(step.get("status"), "").toLowerCase();
                String backendStatus = Objects.toString(step.get("backendStatus"), "").toLowerCase();
                String effectiveStatus = !backendStatus.isEmpty() ? backendStatus : status;

                if ("completed".equals(effectiveStatus) || "success".equals(effectiveStatus)) {
                    stepsCompleted++;
                } else if ("failed".equals(effectiveStatus) || "error".equals(effectiveStatus)) {
                    stepsFailed++;
                } else if ("skipped".equals(effectiveStatus)) {
                    stepsSkipped++;
                }
            }

            int processed = stepsCompleted + stepsFailed + stepsSkipped;
            double progressPct = totalSteps > 0 ? (double) processed / totalSteps * 100.0 : 0.0;

            workflowStatusSnapshot.put("stepsCompleted", stepsCompleted);
            workflowStatusSnapshot.put("stepsFailed", stepsFailed);
            workflowStatusSnapshot.put("stepsSkipped", stepsSkipped);
            workflowStatusSnapshot.put("totalSteps", totalSteps);
            workflowStatusSnapshot.put("overallProgressPct", progressPct);
        }

        // Snapshot agent tool calls
        List<Map<String, Object>> agentToolCallSnapshots = new ArrayList<>();
        synchronized (agentToolCalls) {
            agentToolCalls.forEach(entry -> agentToolCallSnapshots.add(new LinkedHashMap<>(entry)));
        }

        return new RunStateStore.RunSnapshot(
            orderedSteps,
            edgePayloads,
            workflowStatusSnapshot,
            workflowStatsSnapshot,
            loopSnapshots,
            mergeSnapshots,
            logSnapshots,
            agentToolCallSnapshots,
            terminal
        );
    }
}
