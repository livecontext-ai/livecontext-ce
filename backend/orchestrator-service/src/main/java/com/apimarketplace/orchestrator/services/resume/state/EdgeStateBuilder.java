package com.apimarketplace.orchestrator.services.resume.state;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.execution.StatusCounts;
import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.apimarketplace.orchestrator.services.streaming.state.StateUtils;

import java.util.*;

/**
 * Handles building edge state objects for state reconstruction.
 * Single Responsibility: Edge state building operations.
 */
public class EdgeStateBuilder {

    private static final Logger logger = LoggerFactory.getLogger(EdgeStateBuilder.class);

    private final StateReconstructorHelper helper;
    private final StateSnapshotService stateSnapshotService;

    public EdgeStateBuilder(StateReconstructorHelper helper) {
        this(helper, null);
    }

    public EdgeStateBuilder(StateReconstructorHelper helper, StateSnapshotService stateSnapshotService) {
        this.helper = helper;
        this.stateSnapshotService = stateSnapshotService;
    }

    /**
     * V2: Build edge states from plan edges.
     * Backward-compatible overload - no StateSnapshot, so all plan edges yield zero-count
     * EdgeStates with status derived from completed/failed/skipped step IDs.
     *
     * <p>The {@code stepStatusCounts} parameter is unused: per-edge counts must come from
     * the StateSnapshot (or the epoch-counts table), never from the target node's
     * statusCounts - see {@link #buildEdgeStates(String, WorkflowPlan, Set, Set, Set, Map)}.
     */
    public List<WorkflowRunState.EdgeState> buildEdgeStates(
            WorkflowPlan plan,
            Set<String> completedStepIds,
            Set<String> failedStepIds,
            Set<String> skippedStepIds,
            Map<String, StatusCounts> stepStatusCounts) {
        return buildEdgeStates(null, plan, completedStepIds, failedStepIds, skippedStepIds, stepStatusCounts);
    }

    /**
     * V2: Build edge states from plan edges with StateSnapshot support.
     *
     * <p>Single source of truth: {@code StateSnapshot.edges} (per-edge counts emitted at the
     * source side by {@code EdgeStatusService}). Plan edges with no snapshot entry under any
     * port variant emit zero counts - they were never traversed.
     *
     * <p>The {@code stepStatusCounts} parameter is retained for caller signature stability
     * but is intentionally unused: inferring an edge's count from its target node's counts
     * incorrectly attributes a multi-predecessor target's executions to every incoming edge
     * (e.g. shared merge fan-in across multiple triggers).
     */
    public List<WorkflowRunState.EdgeState> buildEdgeStates(
            String runId,
            WorkflowPlan plan,
            Set<String> completedStepIds,
            Set<String> failedStepIds,
            Set<String> skippedStepIds,
            Map<String, StatusCounts> stepStatusCounts) {

        if (logger.isDebugEnabled()) {
            logger.debug("[buildEdgeStates] V2: Building edges. completed={}, skipped={}", completedStepIds, skippedStepIds);
        }

        // Load StateSnapshot if available
        StateSnapshot snapshot = null;
        if (runId != null && stateSnapshotService != null) {
            snapshot = stateSnapshotService.getSnapshot(runId);
            if (logger.isDebugEnabled() && snapshot != null && !snapshot.getEdges().isEmpty()) {
                logger.debug("[buildEdgeStates] Using StateSnapshot with {} edges", snapshot.getEdges().size());
            }
        }

        List<WorkflowRunState.EdgeState> edgeStates = new ArrayList<>();
        Set<String> addedEdges = new HashSet<>(); // Track added edges to avoid duplicates

        for (Edge edge : plan.getEdges()) {
            if (edge.from() == null || edge.to() == null) {
                continue;
            }

            // V2: Parse from and to using EdgeRefParser
            // Preserve port suffix for branching node edges (e.g., core:option:choice_0)
            // so each branch edge is tracked individually in the run state.
            EdgeRefParser.EdgeRef fromRef = EdgeRefParser.parse(edge.from());
            EdgeRefParser.EdgeRef toRef = EdgeRefParser.parse(edge.to());

            if (fromRef == null || toRef == null) {
                logger.warn("[buildEdgeStates] V2: Could not parse edge: {} -> {}", edge.from(), edge.to());
                continue;
            }

            // Use full ref with port for edge identification
            String fromKey = fromRef.hasPort()
                ? fromRef.getNodeKey() + ":" + fromRef.port()
                : fromRef.getNodeKey();
            String toKey = toRef.hasPort()
                ? toRef.getNodeKey() + ":" + toRef.port()
                : toRef.getNodeKey();

            String edgeKey = fromKey + "->" + toKey;
            if (!addedEdges.contains(edgeKey)) {
                // First try to get counts from StateSnapshot (uses port-qualified key)
                WorkflowRunState.EdgeState edgeState = createEdgeStateFromSnapshot(snapshot, fromKey, toKey);

                // For iterate edges (loop-back), the StateSnapshot stores the edge without
                // the :iterate port (BackEdgeHandler uses loopCoreKey = getNodeKey(to)).
                // Try without port as fallback.
                if (edgeState == null && toRef.hasPort()) {
                    String toNodeKey = toRef.getNodeKey();
                    edgeState = createEdgeStateFromSnapshot(snapshot, fromKey, toNodeKey);
                    // If found, use the plan's port-qualified keys for the EdgeState
                    if (edgeState != null) {
                        edgeState = new WorkflowRunState.EdgeState(
                            fromKey, toKey, edgeState.status(),
                            edgeState.completedCount(), edgeState.skippedCount(), edgeState.totalCount());
                    }
                }
                if (edgeState == null && fromRef.hasPort()) {
                    String fromNodeKey = fromRef.getNodeKey();
                    edgeState = createEdgeStateFromSnapshot(snapshot, fromNodeKey, toKey);
                    if (edgeState != null) {
                        edgeState = new WorkflowRunState.EdgeState(
                            fromKey, toKey, edgeState.status(),
                            edgeState.completedCount(), edgeState.skippedCount(), edgeState.totalCount());
                    }
                }

                if (edgeState == null) {
                    // Edge has no entry in StateSnapshot under any port variant - it was never
                    // traversed. Emit zero counts; status comes from the source/target node IDs
                    // (e.g. PENDING for an unfired trigger's edge).
                    //
                    // CRITICAL: do NOT infer counts from the target node's stepStatusCounts.
                    // For a multi-predecessor target (shared merge fan-in, multi-trigger), every
                    // incoming edge would receive the target's full count - attributing one
                    // trigger's executions to a sibling trigger that never fired.
                    String fromNodeKey = fromRef.getNodeKey();
                    String toNodeKey = toRef.getNodeKey();
                    RunStatus fallbackStatus = helper.determineEdgeStatus(fromNodeKey, toNodeKey, completedStepIds, failedStepIds, skippedStepIds);
                    edgeState = new WorkflowRunState.EdgeState(fromKey, toKey, fallbackStatus, 0, 0, 0);
                }

                edgeStates.add(edgeState);
                addedEdges.add(edgeKey);
                // Also track the snapshot key variant to avoid duplicates later
                String snapshotKey = fromRef.getNodeKey() + "->" + toRef.getNodeKey();
                addedEdges.add(snapshotKey);
            }
        }

        // Add edges from StateSnapshot that weren't covered by plan edges.
        // This catches edges recorded during execution under different key formats
        // (e.g., back-edges stored without port suffixes).
        if (snapshot != null) {
            for (Map.Entry<String, StateSnapshot.EdgeCounts> entry : snapshot.getEdges().entrySet()) {
                String snapshotEdgeKey = entry.getKey();
                StateSnapshot.EdgeCounts counts = entry.getValue();
                if (counts.total() == 0) continue;

                String[] parts = snapshotEdgeKey.split("->");
                if (parts.length != 2) continue;

                String from = parts[0];
                String to = parts[1];

                // Skip virtual loop nodes
                if (StateUtils.isVirtualLoopNodeId(from) || StateUtils.isVirtualLoopNodeId(to)) continue;

                if (!addedEdges.contains(snapshotEdgeKey)) {
                    WorkflowRunState.EdgeState edgeState = createEdgeStateFromSnapshot(snapshot, from, to);
                    if (edgeState != null) {
                        edgeStates.add(edgeState);
                        addedEdges.add(snapshotEdgeKey);
                    }
                }
            }
        }

        if (logger.isDebugEnabled()) {
            logger.debug("[buildEdgeStates] V2: Built {} edges", edgeStates.size());
            for (var es : edgeStates) {
                logger.debug("[buildEdgeStates] V2: Edge: {} -> {} = {}", es.from(), es.to(), es.status());
            }
        }

        return edgeStates;
    }

    /**
     * Creates EdgeState from StateSnapshot if available.
     * Returns null if no snapshot data exists for this edge.
     */
    private WorkflowRunState.EdgeState createEdgeStateFromSnapshot(StateSnapshot snapshot, String from, String to) {
        if (snapshot == null) {
            logger.debug("[createEdgeStateFromSnapshot] No snapshot available for edge {} -> {}", from, to);
            return null;
        }

        StateSnapshot.EdgeCounts edgeCounts = snapshot.getEdgeCounts(from, to);
        if (logger.isDebugEnabled()) {
            logger.debug("[createEdgeStateFromSnapshot] Edge {} -> {}: edgeCounts={}, snapshotEdges={}",
                from, to, edgeCounts, snapshot.getEdges().keySet());
        }
        if (edgeCounts == null || edgeCounts.total() == 0) {
            return null;
        }

        // Derive status from counts
        RunStatus status;
        if (edgeCounts.running() > 0) {
            status = RunStatus.RUNNING;
        } else if (edgeCounts.skipped() > 0 && edgeCounts.completed() == 0) {
            status = RunStatus.COMPLETED;
        } else if (edgeCounts.completed() > 0) {
            status = RunStatus.COMPLETED;
        } else {
            status = RunStatus.PENDING;
        }

        logger.debug("[createEdgeStateFromSnapshot] Edge {} -> {}: completed={}, skipped={}, running={}, status={}",
            from, to, edgeCounts.completed(), edgeCounts.skipped(), edgeCounts.running(), status);

        return new WorkflowRunState.EdgeState(from, to, status, edgeCounts.completed(), edgeCounts.skipped(), edgeCounts.total());
    }

}
