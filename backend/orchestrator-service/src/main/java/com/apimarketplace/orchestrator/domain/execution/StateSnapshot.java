package com.apimarketplace.orchestrator.domain.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Immutable snapshot of workflow execution state.
 *
 * This is the SINGLE SOURCE OF TRUTH for execution status.
 * Stored as JSONB in workflow_runs.state_snapshot.
 *
 * <p><b>Version 3 (Multi-DAG):</b> Per-DAG per-epoch state via {@code dags} field.
 * Flat fields (completedNodeIds, etc.) are backward-compatible views computed from
 * the union of all DAGs' current epochs.
 *
 * <p><b>Version 2 (Legacy):</b> Flat fields only. On deserialization, migrated to
 * a single-DAG structure with triggerId "trigger:default".
 *
 * Design principles:
 * - Immutable: all mutations return new instances
 * - Complete: contains ALL state needed for display (no reconstruction needed)
 * - Atomic: updated in single transaction on each node completion
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StateSnapshot {

    /**
     * V2-to-V3 migration sentinel DAG key. Runs migrated from the flat V2 format
     * (and flat writes that land before any real DAG exists) key their single DAG
     * under this id. It never receives trigger fires, so nothing can ever close an
     * epoch that becomes active on it - sentinel-aware call sites below must never
     * count or reactivate its epochs when real trigger DAGs exist. NOTE: a real
     * trigger labeled "Default" normalizes to the same key (pre-existing collision,
     * shared with getDefaultTriggerId and the V164 trigger_id column default).
     */
    public static final String DEFAULT_TRIGGER_SENTINEL = "trigger:default";

    // Version for backwards compatibility (v3 = multi-DAG)
    private final int version;

    // Monotonic sequence number - incremented on every DB write.
    // Used by the frontend to discard stale WS events.
    private final long seq;

    // Per-DAG per-epoch state (primary storage in v3)
    private final Map<String, DagState> dags;

    // Flat node status sets (v2 compat: union of all DAGs' current epochs)
    // Flat views over `dags` - lazily computed on first read. Volatile so the
    // publication of the computed Set is safe across threads (StateSnapshot is
    // immutable from the caller's perspective; the lazy field is an internal
    // memoization). Per the OOM 2026-05-06 12:22 audit, eager construction of
    // these 9 flat views (6 sets + 3 maps below) was the dominant per-mutation
    // allocation: every `markNodeCompleted/Failed/...` chain allocates a new
    // StateSnapshot, and 99% of those mutations are followed by another mutation,
    // not by a flat-view read. Lazy = ~70% fewer Set allocations on the hot path.
    private volatile Set<String> completedNodeIds;
    private volatile Set<String> failedNodeIds;
    private volatile Set<String> skippedNodeIds;
    private volatile Set<String> runningNodeIds;
    private volatile Set<String> readyNodeIds;

    // Counts per node (for "3/5 completed" display) - global, never reset
    private final Map<String, NodeCounts> nodes;

    // Edge counts (for edge status display) - global, never reset
    private final Map<String, EdgeCounts> edges;

    // Decision branches taken (for skip propagation) - flat view from dags, lazy
    private volatile Map<String, Set<String>> decisionBranches;

    // Loop state (for iteration tracking) - flat view from dags, lazy
    private volatile Map<String, LoopState> loops;

    // Split context state (for parallel item tracking) - flat view from dags, lazy
    private volatile Map<String, SplitState> splits;

    // Signal system: nodes awaiting signal resolution - flat view from dags, lazy
    private volatile Set<String> awaitingSignalNodeIds;

    // Cumulative execution duration across all closed epochs (ms) - global, never reset.
    // Incremented by each epoch's (closed_at - started_at) at epoch close time.
    // Same accumulation pattern as NodeCounts.totalExecutionTimeMs.
    private final long totalDurationMs;

    // Timestamp
    private final Instant lastUpdated;

    @JsonCreator
    public StateSnapshot(
            @JsonProperty("version") Integer version,
            @JsonProperty("seq") Long seq,
            @JsonProperty("dags") Map<String, DagState> dags,
            @JsonProperty("completedNodeIds") Set<String> completedNodeIds,
            @JsonProperty("failedNodeIds") Set<String> failedNodeIds,
            @JsonProperty("skippedNodeIds") Set<String> skippedNodeIds,
            @JsonProperty("runningNodeIds") Set<String> runningNodeIds,
            @JsonProperty("readyNodeIds") Set<String> readyNodeIds,
            @JsonProperty("nodes") Map<String, NodeCounts> nodes,
            @JsonProperty("edges") Map<String, EdgeCounts> edges,
            @JsonProperty("decisionBranches") Map<String, Set<String>> decisionBranches,
            @JsonProperty("loops") Map<String, LoopState> loops,
            @JsonProperty("splits") Map<String, SplitState> splits,
            @JsonProperty("awaitingSignalNodeIds") Set<String> awaitingSignalNodeIds,
            @JsonProperty("totalDurationMs") Long totalDurationMs,
            @JsonProperty("lastUpdated") Instant lastUpdated) {
        this.version = version != null ? version : 3;
        this.seq = seq != null ? seq : 0;
        this.nodes = nodes != null ? Map.copyOf(nodes) : Map.of();
        this.edges = edges != null ? Map.copyOf(edges) : Map.of();
        this.totalDurationMs = totalDurationMs != null ? totalDurationMs : 0L;
        this.lastUpdated = lastUpdated != null ? lastUpdated : Instant.now();

        if (dags != null && !dags.isEmpty()) {
            // V3: dags is primary, flat fields are LAZILY computed in getters.
            // No eager allocation here - saves 9 Set/Map per mutation.
            this.dags = Map.copyOf(dags);
            // (caches stay null - getters memoize on first access)
        } else {
            // V2 backward compat: flat fields are the input, migrate them into a
            // default dag so dags becomes the source of truth post-construction.
            // Caches stay null - getters lazily recompute from dags. This keeps
            // the "cache always equals recompute(dags)" invariant in lockstep
            // (audit 2026-05-06: pre-staged caches could diverge from dags when
            // migrateFlatToDags early-returns Map.of() for branch/loop/split-only
            // inputs).
            this.dags = migrateFlatToDags(
                    completedNodeIds != null ? Set.copyOf(completedNodeIds) : Set.of(),
                    failedNodeIds != null ? Set.copyOf(failedNodeIds) : Set.of(),
                    skippedNodeIds != null ? Set.copyOf(skippedNodeIds) : Set.of(),
                    runningNodeIds != null ? Set.copyOf(runningNodeIds) : Set.of(),
                    readyNodeIds != null ? Set.copyOf(readyNodeIds) : Set.of(),
                    awaitingSignalNodeIds != null ? Set.copyOf(awaitingSignalNodeIds) : Set.of(),
                    decisionBranches != null ? deepCopySetMap(decisionBranches) : Map.of(),
                    loops != null ? Map.copyOf(loops) : Map.of(),
                    splits != null ? Map.copyOf(splits) : Map.of());
        }
    }

    /**
     * Internal constructor for mutations - uses pre-built dags.
     * Flat fields are computed from dags.
     */
    private StateSnapshot(int version, long seq, Map<String, DagState> dags,
                          Map<String, NodeCounts> nodes, Map<String, EdgeCounts> edges,
                          long totalDurationMs, Instant lastUpdated) {
        this.version = version;
        this.seq = seq;
        this.dags = dags != null ? Map.copyOf(dags) : Map.of();
        this.nodes = nodes != null ? Map.copyOf(nodes) : Map.of();
        this.edges = edges != null ? Map.copyOf(edges) : Map.of();
        this.totalDurationMs = totalDurationMs;
        this.lastUpdated = lastUpdated != null ? lastUpdated : Instant.now();
        // Flat views (completedNodeIds, failedNodeIds, ..., decisionBranches, loops,
        // splits) are LAZILY computed in their getters from `dags`. The mutation
        // hot path (markNodeCompleted → markEdgeIncremented → ...) only reads
        // `dags`/`nodes`/`edges`, never the flat views - eager construction here
        // would allocate 9 collections per mutation that nobody reads.
    }

    /**
     * Create from dags with pre-computed flat fields (internal fast path).
     * Preserves totalDurationMs from the calling instance.
     */
    private StateSnapshot fromDags(int version, long seq, Map<String, DagState> dags,
                                   Map<String, NodeCounts> nodes, Map<String, EdgeCounts> edges) {
        return new StateSnapshot(version, seq, dags, nodes, edges, this.totalDurationMs, Instant.now());
    }

    /**
     * Create an empty snapshot.
     */
    public static StateSnapshot empty() {
        return new StateSnapshot(3, 0L, Map.of(), Map.of(), Map.of(), 0L, Instant.now());
    }

    /**
     * Check if this snapshot has no data.
     */
    public boolean isEmpty() {
        // Use getters so the lazy caches populate (rather than the volatile field
        // direct access which may still be null on a fresh V3 instance).
        return getCompletedNodeIds().isEmpty() && getFailedNodeIds().isEmpty() &&
                getSkippedNodeIds().isEmpty() && getRunningNodeIds().isEmpty() &&
                getAwaitingSignalNodeIds().isEmpty() &&
                nodes.isEmpty() && edges.isEmpty();
    }

    // ========================================================================
    // DAG-SCOPED STATE ACCESS
    // ========================================================================

    /**
     * Get the DagState for a specific trigger.
     */
    public DagState getDagState(String triggerId) {
        return dags.getOrDefault(triggerId, DagState.initial());
    }

    /**
     * Get or create the current EpochState for a trigger.
     */
    public EpochState getEpochState(String triggerId) {
        return getDagState(triggerId).currentEpochState();
    }

    /**
     * Get the EpochState for a specific trigger and epoch.
     */
    public EpochState getEpochState(String triggerId, int epoch) {
        EpochState es = getDagState(triggerId).getEpochState(epoch);
        return es != null ? es : EpochState.fresh();
    }

    // ========================================================================
    // DAG-SCOPED NODE STATUS MUTATIONS (current epoch)
    // ========================================================================

    /**
     * Mark a node as completed within a specific DAG's current epoch.
     * Also increments the global NodeCounts.
     */
    public StateSnapshot markNodeCompleted(String triggerId, String nodeId) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial()).ensureCurrentEpochInitialized();
        EpochState epoch = dagState.currentEpochState().markNodeCompleted(nodeId);
        newDags.put(triggerId, dagState.withCurrentEpochState(epoch));

        Map<String, NodeCounts> newNodes = new HashMap<>(nodes);
        NodeCounts current = newNodes.getOrDefault(nodeId, NodeCounts.zero());
        newNodes.put(nodeId, current.increment("COMPLETED"));

        return fromDags(version, seq, newDags, newNodes, edges);
    }

    /**
     * Mark a node as failed within a specific DAG/epoch.
     */
    public StateSnapshot markNodeFailed(String triggerId, String nodeId) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial()).ensureCurrentEpochInitialized();
        EpochState epoch = dagState.currentEpochState().markNodeFailed(nodeId);
        newDags.put(triggerId, dagState.withCurrentEpochState(epoch));

        Map<String, NodeCounts> newNodes = new HashMap<>(nodes);
        NodeCounts current = newNodes.getOrDefault(nodeId, NodeCounts.zero());
        newNodes.put(nodeId, current.increment("FAILED"));

        return fromDags(version, seq, newDags, newNodes, edges);
    }

    /**
     * Mark a node as skipped within a specific DAG/epoch.
     */
    public StateSnapshot markNodeSkipped(String triggerId, String nodeId) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial()).ensureCurrentEpochInitialized();
        EpochState epoch = dagState.currentEpochState().markNodeSkipped(nodeId);
        newDags.put(triggerId, dagState.withCurrentEpochState(epoch));

        Map<String, NodeCounts> newNodes = new HashMap<>(nodes);
        NodeCounts current = newNodes.getOrDefault(nodeId, NodeCounts.zero());
        newNodes.put(nodeId, current.increment("SKIPPED"));

        return fromDags(version, seq, newDags, newNodes, edges);
    }

    /**
     * Mark a node as awaiting signal within a specific DAG/epoch.
     */
    public StateSnapshot markNodeAwaitingSignal(String triggerId, String nodeId) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial()).ensureCurrentEpochInitialized();
        EpochState epoch = dagState.currentEpochState().markNodeAwaitingSignal(nodeId);
        newDags.put(triggerId, dagState.withCurrentEpochState(epoch));
        return fromDags(version, seq, newDags, nodes, edges);
    }

    /**
     * Resolve a node's signal wait within a specific DAG/epoch.
     */
    public StateSnapshot resolveAwaitingSignal(String triggerId, String nodeId) {
        return resolveAwaitingSignal(triggerId, nodeId, 0L);
    }

    /**
     * Resolve a node's signal wait within a specific DAG/epoch, with wait duration.
     *
     * @param durationMs the wall-clock time the node spent waiting for the signal
     *                   (resolvedAt - createdAt). 0 means no timing recorded.
     */
    public StateSnapshot resolveAwaitingSignal(String triggerId, String nodeId, long durationMs) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial()).ensureCurrentEpochInitialized();
        EpochState epoch = dagState.currentEpochState().resolveAwaitingSignal(nodeId);
        newDags.put(triggerId, dagState.withCurrentEpochState(epoch));

        Map<String, NodeCounts> newNodes = new HashMap<>(nodes);
        NodeCounts current = newNodes.getOrDefault(nodeId, NodeCounts.zero());
        newNodes.put(nodeId, current.incrementWithTiming("COMPLETED", durationMs));

        return fromDags(version, seq, newDags, newNodes, edges);
    }

    /**
     * Add a running node within a specific DAG/epoch.
     */
    public StateSnapshot addRunningNode(String triggerId, String nodeId) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial()).ensureCurrentEpochInitialized();
        EpochState epoch = dagState.currentEpochState().addRunningNode(nodeId);
        newDags.put(triggerId, dagState.withCurrentEpochState(epoch));
        return fromDags(version, seq, newDags, nodes, edges);
    }

    /**
     * Set ready nodes for a specific DAG/epoch.
     */
    public StateSnapshot withReadyNodes(String triggerId, Set<String> readyNodes) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial()).ensureCurrentEpochInitialized();
        EpochState epoch = dagState.currentEpochState().withReadyNodes(readyNodes);
        newDags.put(triggerId, dagState.withCurrentEpochState(epoch));
        return fromDags(version, seq, newDags, nodes, edges);
    }

    /**
     * Add a ready node to a specific DAG/epoch.
     */
    public StateSnapshot addReadyNode(String triggerId, String nodeId) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial()).ensureCurrentEpochInitialized();
        EpochState epoch = dagState.currentEpochState().addReadyNode(nodeId);
        newDags.put(triggerId, dagState.withCurrentEpochState(epoch));
        return fromDags(version, seq, newDags, nodes, edges);
    }

    /**
     * Move a node from RUNNING back to READY - error-recovery inverse of the
     * claim performed by {@code StateSnapshotService.claimNodeForExecution}.
     *
     * <p>Scans all DAGs' active epochs (falling back to the current epoch) for the node in
     * {@code runningNodeIds} and re-adds it as ready <em>in that same DAG/epoch</em>. When no
     * DAG tracks the node as running (post-elide JSONB where running lives only in Redis),
     * falls back to adding READY in the default DAG's current epoch.
     *
     * <p>Callers must have verified the node is not in any resolved set - this method does
     * not re-check.
     */
    public StateSnapshot releaseRunningToReady(String nodeId) {
        for (var entry : dags.entrySet()) {
            String triggerId = entry.getKey();
            DagState ds = entry.getValue();
            if (ds.hasActiveEpochs()) {
                for (int activeEpoch : ds.getActiveEpochs()) {
                    EpochState es = ds.getEpochState(activeEpoch);
                    if (es != null && es.getRunningNodeIds().contains(nodeId)) {
                        Map<String, DagState> newDags = new HashMap<>(dags);
                        newDags.put(triggerId, ds.withEpochState(activeEpoch, es.releaseRunningToReady(nodeId)));
                        return fromDags(version, seq, newDags, nodes, edges);
                    }
                }
            } else if (ds.currentEpochState().getRunningNodeIds().contains(nodeId)) {
                Map<String, DagState> newDags = new HashMap<>(dags);
                newDags.put(triggerId, ds.withCurrentEpochState(ds.currentEpochState().releaseRunningToReady(nodeId)));
                return fromDags(version, seq, newDags, nodes, edges);
            }
        }
        // Not tracked as running anywhere (running elided to Redis) - flat fallback.
        return addReadyNode(nodeId);
    }

    /**
     * Reactivate the current epoch for a DAG, adding it back to activeEpochs
     * so flat views include its state. Used after closing all epochs in WAITING_TRIGGER.
     */
    public StateSnapshot reactivateDagEpoch(String triggerId) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.get(triggerId);
        if (dagState != null) {
            newDags.put(triggerId, dagState.reactivateCurrentEpoch());
        }
        return fromDags(version, seq, newDags, nodes, edges);
    }

    /**
     * Remove a ready node from a specific DAG/epoch.
     */
    public StateSnapshot removeReadyNode(String triggerId, String nodeId) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial()).ensureCurrentEpochInitialized();
        EpochState epoch = dagState.currentEpochState().removeReadyNode(nodeId);
        newDags.put(triggerId, dagState.withCurrentEpochState(epoch));
        return fromDags(version, seq, newDags, nodes, edges);
    }

    /**
     * Record a decision branch within a specific DAG (current epoch).
     */
    public StateSnapshot recordDecisionBranch(String triggerId, String nodeId, String branch) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial()).ensureCurrentEpochInitialized();
        EpochState es = dagState.currentEpochState().recordDecisionBranch(nodeId, branch);
        newDags.put(triggerId, dagState.withCurrentEpochState(es));
        return fromDags(version, seq, newDags, nodes, edges);
    }

    /**
     * Record a decision branch within a specific DAG and specific epoch.
     */
    public StateSnapshot recordDecisionBranch(String triggerId, String nodeId, int epoch, String branch) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial());
        EpochState es = dagState.getEpochState(epoch);
        if (es == null) es = EpochState.fresh();
        newDags.put(triggerId, dagState.withEpochState(epoch, es.recordDecisionBranch(nodeId, branch)));
        return fromDags(version, seq, newDags, nodes, edges);
    }

    /**
     * Update loop state within a specific DAG/epoch.
     */
    public StateSnapshot updateLoopState(String triggerId, String loopId, LoopState state) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial()).ensureCurrentEpochInitialized();
        EpochState epoch = dagState.currentEpochState().updateLoopState(loopId, state);
        newDags.put(triggerId, dagState.withCurrentEpochState(epoch));
        return fromDags(version, seq, newDags, nodes, edges);
    }

    /**
     * Update split state within a specific DAG/epoch.
     */
    public StateSnapshot updateSplitState(String triggerId, String splitId, SplitState state) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial()).ensureCurrentEpochInitialized();
        EpochState epoch = dagState.currentEpochState().updateSplitState(splitId, state);
        newDags.put(triggerId, dagState.withCurrentEpochState(epoch));
        return fromDags(version, seq, newDags, nodes, edges);
    }

    // ========================================================================
    // DAG-SCOPED RESET
    // ========================================================================

    /**
     * Reset a specific DAG for a new epoch.
     * Advances the DAG's epoch counter and creates a fresh EpochState.
     * Other DAGs are NOT affected. Global NodeCounts/EdgeCounts are preserved.
     *
     * @param triggerId the trigger ID to reset
     * @param newGlobalEpoch the new global epoch number
     * @return new snapshot with the DAG reset
     */
    public StateSnapshot resetDag(String triggerId, int newGlobalEpoch) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial());
        newDags.put(triggerId, dagState.advanceEpoch(newGlobalEpoch));
        return fromDags(version, seq, newDags, nodes, edges);
    }

    /**
     * Prepare a DAG for the next trigger cycle WITHOUT creating an active epoch.
     *
     * <p>Creates an EpochState with the trigger as ready, but does NOT add the epoch
     * to activeEpochs. This prevents "phantom epochs" that block WAITING_TRIGGER.
     * The real activation happens via {@link #openEpochForDag} when the trigger fires.
     *
     */
    public StateSnapshot prepareDagForNextCycle(String triggerId, int newGlobalEpoch, String readyNodeId) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial());
        newDags.put(triggerId, dagState.prepareNextCycle(newGlobalEpoch, readyNodeId));
        return fromDags(version, seq, newDags, nodes, edges);
    }

    /**
     * Open an epoch for a DAG: add it to activeEpochs and create EpochState if absent.
     * Unlike resetDag(), this preserves existing EpochState and does NOT increment fireCount.
     * Must be called before execution begins so that computeFlatSet() includes this epoch's data.
     *
     */
    public StateSnapshot openEpochForDag(String triggerId, int epoch) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial());
        newDags.put(triggerId, dagState.openEpoch(epoch));
        return fromDags(version, seq, newDags, nodes, edges);
    }

    /**
     * Initialize a DAG with a specific epoch if not already present.
     */
    public StateSnapshot ensureDagInitialized(String triggerId, int epoch) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial());
        if (dagState.getEpochState(epoch) == null) {
            newDags.put(triggerId, dagState.withEpochState(epoch, EpochState.fresh()));
            return fromDags(version, seq, newDags, nodes, edges);
        }
        return this;
    }

    /**
     * Replace the entire DagState for a trigger.
     */
    public StateSnapshot withDagState(String triggerId, DagState dagState) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        newDags.put(triggerId, dagState);
        return fromDags(version, seq, newDags, nodes, edges);
    }

    // ========================================================================
    // EPOCH-SCOPED MUTATIONS (explicit epoch parameter)
    // Used for parallel epoch execution where multiple epochs are active
    // simultaneously within the same DAG.
    // ========================================================================

    /**
     * Mark a node as completed within a specific DAG and specific epoch.
     */
    public StateSnapshot markNodeCompleted(String triggerId, String nodeId, int epoch) {
        return markNodeCompleted(triggerId, nodeId, epoch, 0L);
    }

    /**
     * Mark a node as completed within a specific DAG and specific epoch, with timing.
     */
    public StateSnapshot markNodeCompleted(String triggerId, String nodeId, int epoch, long durationMs) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial());
        EpochState es = dagState.getEpochState(epoch);
        if (es == null) es = EpochState.fresh();
        newDags.put(triggerId, dagState.withEpochState(epoch, es.markNodeCompleted(nodeId)));

        Map<String, NodeCounts> newNodes = new HashMap<>(nodes);
        NodeCounts current = newNodes.getOrDefault(nodeId, NodeCounts.zero());
        newNodes.put(nodeId, current.incrementWithTiming("COMPLETED", durationMs));

        return fromDags(version, seq, newDags, newNodes, edges);
    }

    /**
     * Mark a node as completed in the EpochState <em>only</em> - does NOT touch global
     * {@link NodeCounts}. Used by the split-async barrier seal
     * ({@code StepCompletionOrchestrator.recordSplitAggregateIfMissing}) where per-item
     * completions have already incremented {@code NodeCounts} via
     * {@link #incrementNodeCountsOnly}: rerunning a full {@link #markNodeCompleted} at
     * the seal would double-count one item (Phase 2.E was incomplete - it suppressed
     * the per-item global mark but the aggregate seal still re-incremented). The
     * companion to {@link #incrementNodeCountsOnly}: split-async = per-item updates
     * {@code NodeCounts}, seal updates {@code EpochState.completedNodeIds}.
     *
     * <p>Idempotent - adding to a set already containing the id is a no-op.
     */
    public StateSnapshot markNodeCompletedEpochOnly(String triggerId, String nodeId, int epoch) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial());
        EpochState es = dagState.getEpochState(epoch);
        if (es == null) es = EpochState.fresh();
        newDags.put(triggerId, dagState.withEpochState(epoch, es.markNodeCompleted(nodeId)));
        return fromDags(version, seq, newDags, nodes, edges);
    }

    /**
     * Failed twin of {@link #markNodeCompletedEpochOnly} - see that method for rationale.
     */
    public StateSnapshot markNodeFailedEpochOnly(String triggerId, String nodeId, int epoch) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial());
        EpochState es = dagState.getEpochState(epoch);
        if (es == null) es = EpochState.fresh();
        newDags.put(triggerId, dagState.withEpochState(epoch, es.markNodeFailed(nodeId)));
        return fromDags(version, seq, newDags, nodes, edges);
    }

    /**
     * Mark a node as failed within a specific DAG and specific epoch.
     */
    public StateSnapshot markNodeFailed(String triggerId, String nodeId, int epoch) {
        return markNodeFailed(triggerId, nodeId, epoch, 0L);
    }

    /**
     * Mark a node as failed within a specific DAG and specific epoch, with timing.
     */
    public StateSnapshot markNodeFailed(String triggerId, String nodeId, int epoch, long durationMs) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial());
        EpochState es = dagState.getEpochState(epoch);
        if (es == null) es = EpochState.fresh();
        newDags.put(triggerId, dagState.withEpochState(epoch, es.markNodeFailed(nodeId)));

        Map<String, NodeCounts> newNodes = new HashMap<>(nodes);
        NodeCounts current = newNodes.getOrDefault(nodeId, NodeCounts.zero());
        newNodes.put(nodeId, current.incrementWithTiming("FAILED", durationMs));

        return fromDags(version, seq, newDags, newNodes, edges);
    }

    /**
     * Mark a node as having had per-item failures inside a split context (Phase 2.A).
     * The node remains in {@code completedNodeIds}; this set tracks for inspector
     * display and observability. Idempotent.
     */
    public StateSnapshot markNodePartialFailure(String triggerId, String nodeId, int epoch) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial());
        EpochState es = dagState.getEpochState(epoch);
        if (es == null) es = EpochState.fresh();
        newDags.put(triggerId, dagState.withEpochState(epoch, es.markNodePartialFailure(nodeId)));
        return fromDags(version, seq, newDags, nodes, edges);
    }

    /**
     * Mark a node as skipped within a specific DAG and specific epoch.
     */
    public StateSnapshot markNodeSkipped(String triggerId, String nodeId, int epoch) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial());
        EpochState es = dagState.getEpochState(epoch);
        if (es == null) es = EpochState.fresh();
        newDags.put(triggerId, dagState.withEpochState(epoch, es.markNodeSkipped(nodeId)));

        Map<String, NodeCounts> newNodes = new HashMap<>(nodes);
        NodeCounts current = newNodes.getOrDefault(nodeId, NodeCounts.zero());
        newNodes.put(nodeId, current.increment("SKIPPED"));

        return fromDags(version, seq, newDags, newNodes, edges);
    }

    /**
     * Resolve an awaiting-signal node as SKIPPED without incrementing completed counts.
     */
    public StateSnapshot skipAwaitingSignal(String triggerId, String nodeId, int epoch) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial());
        EpochState es = dagState.getEpochState(epoch);
        if (es == null) es = EpochState.fresh();
        newDags.put(triggerId, dagState.withEpochState(epoch, es.skipAwaitingSignal(nodeId)));

        Map<String, NodeCounts> newNodes = new HashMap<>(nodes);
        NodeCounts current = newNodes.getOrDefault(nodeId, NodeCounts.zero());
        newNodes.put(nodeId, current.increment("SKIPPED"));

        return fromDags(version, seq, newDags, newNodes, edges);
    }

    /**
     * Increment only the global NodeCounts for a node without touching EpochState.
     * Used for per-item SKIPPED records in split branches where the node itself
     * is not globally skipped but individual items are.
     */
    public StateSnapshot incrementNodeCountsOnly(String nodeId, String status, int count) {
        if (count <= 0) return this;
        Map<String, NodeCounts> newNodes = new HashMap<>(nodes);
        NodeCounts current = newNodes.getOrDefault(nodeId, NodeCounts.zero());
        newNodes.put(nodeId, current.incrementBy(status, count));
        return fromDags(version, seq, dags, newNodes, edges);
    }

    /**
     * Add a running node within a specific DAG and specific epoch.
     */
    public StateSnapshot addRunningNode(String triggerId, String nodeId, int epoch) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial());
        EpochState es = dagState.getEpochState(epoch);
        if (es == null) es = EpochState.fresh();
        newDags.put(triggerId, dagState.withEpochState(epoch, es.addRunningNode(nodeId)));
        return fromDags(version, seq, newDags, nodes, edges);
    }

    /**
     * Remove a ready node from a specific DAG and specific epoch.
     */
    public StateSnapshot removeReadyNode(String triggerId, String nodeId, int epoch) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial());
        EpochState es = dagState.getEpochState(epoch);
        if (es == null) es = EpochState.fresh();
        newDags.put(triggerId, dagState.withEpochState(epoch, es.removeReadyNode(nodeId)));
        return fromDags(version, seq, newDags, nodes, edges);
    }

    /**
     * Add a ready node to a specific DAG and specific epoch.
     */
    public StateSnapshot addReadyNode(String triggerId, String nodeId, int epoch) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial());
        EpochState es = dagState.getEpochState(epoch);
        if (es == null) es = EpochState.fresh();
        newDags.put(triggerId, dagState.withEpochState(epoch, es.addReadyNode(nodeId)));
        return fromDags(version, seq, newDags, nodes, edges);
    }

    /**
     * Mark a node as awaiting signal within a specific DAG and specific epoch.
     */
    public StateSnapshot markNodeAwaitingSignal(String triggerId, String nodeId, int epoch) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial());
        EpochState es = dagState.getEpochState(epoch);
        if (es == null) es = EpochState.fresh();
        newDags.put(triggerId, dagState.withEpochState(epoch, es.markNodeAwaitingSignal(nodeId)));
        return fromDags(version, seq, newDags, nodes, edges);
    }

    /**
     * Resolve a node's signal wait within a specific DAG and specific epoch.
     */
    public StateSnapshot resolveAwaitingSignal(String triggerId, String nodeId, int epoch) {
        return resolveAwaitingSignal(triggerId, nodeId, epoch, 0L, false);
    }

    /**
     * Resolve a node's signal wait within a specific DAG and specific epoch, with wait duration.
     *
     * @param durationMs the wall-clock time the node spent waiting for the signal
     *                   (resolvedAt - createdAt). 0 means no timing recorded.
     */
    public StateSnapshot resolveAwaitingSignal(String triggerId, String nodeId, int epoch, long durationMs) {
        return resolveAwaitingSignal(triggerId, nodeId, epoch, durationMs, false);
    }

    /**
     * Resolve a node's signal wait within a specific DAG and specific epoch, with wait duration.
     *
     * @param durationMs        the wall-clock time the node spent waiting for the signal
     * @param keepInAwaiting    if true, the node stays in awaitingSignalNodeIds (split context:
     *                          one item's signal resolved but other items still have pending signals)
     */
    public StateSnapshot resolveAwaitingSignal(String triggerId, String nodeId, int epoch, long durationMs, boolean keepInAwaiting) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial());
        EpochState es = dagState.getEpochState(epoch);
        if (es == null) es = EpochState.fresh();
        DagState updatedDag = dagState.withEpochState(epoch, es.resolveAwaitingSignal(nodeId, keepInAwaiting));
        newDags.put(triggerId, updatedDag);

        Map<String, NodeCounts> newNodes = new HashMap<>(nodes);
        NodeCounts current = newNodes.getOrDefault(nodeId, NodeCounts.zero());
        newNodes.put(nodeId, current.incrementWithTiming("COMPLETED", durationMs));

        return fromDags(version, seq, newDags, newNodes, edges);
    }

    /**
     * Ensure a node is in completedNodeIds for a specific epoch, without incrementing NodeCounts.
     * Idempotent: if already completed, returns this snapshot unchanged.
     *
     * <p>Used as a safety net for lost-update scenarios in H2 where concurrent
     * resolveAwaitingSignal() calls may overwrite each other's EpochState changes.
     */
    public StateSnapshot ensureNodeCompletedInEpoch(String triggerId, String nodeId, int epoch) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial());
        EpochState es = dagState.getEpochState(epoch);
        if (es == null) return this; // No EpochState to fix
        if (es.getCompletedNodeIds().contains(nodeId)) return this; // Already correct

        // Add to completedNodeIds and remove from awaitingSignalNodeIds
        EpochState fixed = es.resolveAwaitingSignal(nodeId);
        newDags.put(triggerId, dagState.withEpochState(epoch, fixed));

        // Do NOT increment NodeCounts - that was already done by resolveAwaitingSignal()
        return fromDags(version, seq, newDags, nodes, edges);
    }

    /**
     * Restore a previously-pruned epoch's state into a specific DAG.
     * Used when a non-blocking signal resolves after the epoch was closed and pruned.
     * Does NOT re-activate the epoch - that's handled by resolveAwaitingSignal.
     */
    public StateSnapshot withRestoredEpochState(String triggerId, int epoch, EpochState state) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial());
        newDags.put(triggerId, dagState.withEpochState(epoch, state));
        return fromDags(version, seq, newDags, nodes, edges);
    }

    /**
     * Close and prune a specific epoch for a DAG. Removes from activeEpochs AND
     * from the epochs map. The epoch's full state lives in workflow_epochs table.
     * This keeps StateSnapshot JSONB at constant size (only active epochs).
     *
     * @param epochDurationMs duration of this epoch (closed_at - started_at) to accumulate
     */
    public StateSnapshot closeAndPruneEpochForDag(String triggerId, int epoch, long epochDurationMs) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial());
        newDags.put(triggerId, dagState.closeAndPruneEpoch(epoch));
        long newTotalDuration = this.totalDurationMs + Math.max(0, epochDurationMs);
        return new StateSnapshot(version, seq, newDags, nodes, edges, newTotalDuration, Instant.now());
    }

    /**
     * @deprecated Use {@link #closeAndPruneEpochForDag(String, int, long)} with duration.
     */
    public StateSnapshot closeAndPruneEpochForDag(String triggerId, int epoch) {
        return closeAndPruneEpochForDag(triggerId, epoch, 0L);
    }

    /**
     * Close and prune ALL active epochs for a specific DAG.
     * Used by SBS mode to auto-close previous epochs before opening a new one.
     *
     * @return new StateSnapshot with all active epochs for the DAG closed
     */
    public StateSnapshot closeAllActiveEpochsForDag(String triggerId) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState dagState = newDags.getOrDefault(triggerId, DagState.initial());
        newDags.put(triggerId, dagState.closeAllActiveEpochs());
        return new StateSnapshot(version, seq, newDags, nodes, edges, totalDurationMs, Instant.now());
    }

    /**
     * Check if any DAG has any active epochs.
     *
     * <p>Sentinel-aware: when at least one REAL trigger DAG exists, active epochs on
     * the {@code "trigger:default"} migration sentinel are ignored. The sentinel never
     * receives trigger fires, so an epoch that leaks onto it (e.g. via a flat-fallback
     * write during a rerun) can never be closed by any cycle - counting it would pin
     * this method to {@code true} forever and the run could never re-arm to
     * WAITING_TRIGGER (the zombie scanner then fails the armed run). A legacy
     * V2-migrated run whose ONLY DAG is the sentinel keeps its active epochs counted.
     */
    public boolean hasAnyActiveEpoch() {
        boolean hasRealDag = false;
        for (String key : dags.keySet()) {
            if (!DEFAULT_TRIGGER_SENTINEL.equals(key)) {
                hasRealDag = true;
                break;
            }
        }
        for (var entry : dags.entrySet()) {
            if (hasRealDag && DEFAULT_TRIGGER_SENTINEL.equals(entry.getKey())) {
                continue;
            }
            if (entry.getValue().hasActiveEpochs()) {
                return true;
            }
        }
        return false;
    }

    // ========================================================================
    // FLAT (LEGACY) NODE STATUS MUTATIONS - delegate to default DAG
    // These methods operate on all DAGs (find the node in any DAG).
    // Prefer DAG-scoped methods when triggerId is known.
    // ========================================================================

    /**
     * Mark a node as completed (flat/legacy - searches all DAGs).
     */
    public StateSnapshot markNodeCompleted(String nodeId) {
        String triggerId = findDagContaining(nodeId);
        return markNodeCompleted(triggerId, nodeId);
    }

    /**
     * Mark a node as failed (flat/legacy - searches all DAGs).
     */
    public StateSnapshot markNodeFailed(String nodeId) {
        String triggerId = findDagContaining(nodeId);
        return markNodeFailed(triggerId, nodeId);
    }

    /**
     * Mark a node as skipped (flat/legacy - searches all DAGs).
     */
    public StateSnapshot markNodeSkipped(String nodeId) {
        String triggerId = findDagContaining(nodeId);
        return markNodeSkipped(triggerId, nodeId);
    }

    /**
     * Set the ready nodes (flat/legacy - distributes across default DAG).
     */
    public StateSnapshot withReadyNodes(Set<String> newReadyNodes) {
        String triggerId = getDefaultTriggerId();
        return withReadyNodes(triggerId, newReadyNodes != null ? newReadyNodes : Set.of());
    }

    /**
     * Add a node to ready set (flat/legacy - uses default DAG).
     */
    public StateSnapshot addReadyNode(String nodeId) {
        String triggerId = getDefaultTriggerId();
        return addReadyNode(triggerId, nodeId);
    }

    /**
     * Remove a node from ready set (flat/legacy - searches all DAGs).
     */
    public StateSnapshot removeReadyNode(String nodeId) {
        String triggerId = findDagContaining(nodeId);
        return removeReadyNode(triggerId, nodeId);
    }

    /**
     * Mark a node as awaiting signal (flat/legacy).
     */
    public StateSnapshot markNodeAwaitingSignal(String nodeId) {
        String triggerId = findDagContaining(nodeId);
        return markNodeAwaitingSignal(triggerId, nodeId);
    }

    /**
     * Resolve a node's signal wait (flat/legacy).
     */
    public StateSnapshot resolveAwaitingSignal(String nodeId) {
        return resolveAwaitingSignal(nodeId, 0L);
    }

    /**
     * Resolve a node's signal wait (flat/legacy) with wait duration.
     */
    public StateSnapshot resolveAwaitingSignal(String nodeId, long durationMs) {
        String triggerId = findDagContaining(nodeId);
        return resolveAwaitingSignal(triggerId, nodeId, durationMs);
    }

    /**
     * Add a node to the running set (flat/legacy).
     */
    public StateSnapshot addRunningNode(String nodeId) {
        String triggerId = findDagContaining(nodeId);
        return addRunningNode(triggerId, nodeId);
    }

    /**
     * Selectively remove specific nodes from all DAGs' current epoch states.
     *
     * <p>Used by step rerun to reset only the target + downstream steps while preserving
     * unrelated nodes (e.g., trigger remains COMPLETED). Previously this method did a full
     * epoch reset to {@code EpochState.fresh()}, which wiped all nodes including the trigger,
     * causing the rerun execution to skip the target node with "Prerequisites not met".
     *
     * <p>When the current epoch state is empty (e.g., after AUTO execution closed and pruned
     * the epoch), this method restores completion state for non-reset nodes from global
     * {@link NodeCounts}. NodeCounts survive epoch close/prune and serve as the durable
     * record of which nodes have executed. Without this restoration, the execution context
     * for the rerun target would see no completed predecessors and skip the node.
     *
     * @param nodeIdsToReset the set of node IDs to remove from all tracking sets
     */
    public StateSnapshot resetDag(Set<String> nodeIdsToReset) {
        return resetDag(nodeIdsToReset, null);
    }

    /**
     * Owner-scoped variant of {@link #resetDag(Set)} for step rerun.
     *
     * <p>When {@code ownerTriggerId} is known, ONLY the owner DAG goes through the
     * reset-and-reactivate branches; every other DAG is never reactivated, only stale
     * copies of the reset nodes are stripped from its current epoch. A rerun executes
     * outside a trigger fire, so a non-owner epoch reactivated here would never be
     * closed by any cycle and the run could never re-arm to WAITING_TRIGGER
     * (multi-trigger workflows). On a legacy sentinel-only run the sentinel itself is
     * that non-owner DAG: its reset nodes are stripped without reactivation (flat
     * views still read its dormant current epoch via the no-active-epochs fallback)
     * and the subsequent ready-marking creates the real owner DAG. {@code null}
     * keeps the legacy all-DAGs reactivation for callers that do not know the owner
     * (SBS paths, whose epochs are closed by the SBS cycle management).
     *
     * @param nodeIdsToReset the set of node IDs to remove from all tracking sets
     * @param ownerTriggerId the DAG owning the rerun target; {@code null} = all DAGs
     */
    public StateSnapshot resetDag(Set<String> nodeIdsToReset, String ownerTriggerId) {
        Map<String, DagState> newDags = new HashMap<>();
        boolean hasRealDag = false;
        for (String key : dags.keySet()) {
            if (!DEFAULT_TRIGGER_SENTINEL.equals(key)) {
                hasRealDag = true;
                break;
            }
        }
        for (var entry : dags.entrySet()) {
            DagState ds = entry.getValue();
            // Never REACTIVATE the "trigger:default" migration sentinel when real
            // trigger DAGs exist: no trigger fire can ever close a sentinel epoch, so
            // reactivating it here (the rerun path) would pin hasAnyActiveEpoch() to
            // true forever and the run could never re-arm to WAITING_TRIGGER. Active
            // sentinel epochs already leaked by earlier reruns are healed (closed and
            // pruned - they carry no recoverable work);
            // real DAGs carry the state the rerun's flat view needs.
            if (hasRealDag && DEFAULT_TRIGGER_SENTINEL.equals(entry.getKey())) {
                newDags.put(entry.getKey(), ds.hasActiveEpochs() ? ds.closeAllActiveEpochs() : ds);
                continue;
            }
            // Sibling DAG of an owner-scoped rerun: never reactivate it (a rerun
            // executes outside a trigger fire, so nothing would ever close a sibling
            // epoch reactivated here - multi-trigger runs could never re-arm). Only
            // strip stale copies of the reset nodes, without reactivation.
            if (ownerTriggerId != null && !ownerTriggerId.equals(entry.getKey())) {
                EpochState siblingEpoch = ds.currentEpochState();
                boolean hasStaleNodes = nodeIdsToReset.stream().anyMatch(id ->
                        siblingEpoch.getCompletedNodeIds().contains(id) || siblingEpoch.getFailedNodeIds().contains(id) ||
                        siblingEpoch.getSkippedNodeIds().contains(id) ||
                        siblingEpoch.getReadyNodeIds().contains(id) || siblingEpoch.getAwaitingSignalNodeIds().contains(id));
                newDags.put(entry.getKey(),
                        hasStaleNodes ? ds.withCurrentEpochState(siblingEpoch.removeNodes(nodeIdsToReset)) : ds);
                continue;
            }
            EpochState es = ds.currentEpochState();
            // Check if any of the nodeIdsToReset are in this epoch's state.
            // P2.3 site 9 - `runningNodeIds` NOT consulted (lives in Redis only post-elide).
            // A strictly-running node won't trigger reactivation here, but `removeNodes`
            // below still strips it from all sets blindly if it ever lands.
            boolean hasNodesInEpoch = nodeIdsToReset.stream().anyMatch(id ->
                    es.getCompletedNodeIds().contains(id) || es.getFailedNodeIds().contains(id) ||
                    es.getSkippedNodeIds().contains(id) ||
                    es.getReadyNodeIds().contains(id) || es.getAwaitingSignalNodeIds().contains(id));
            if (hasNodesInEpoch) {
                // Selectively remove only the specified nodes, preserving the rest
                // AND reactivate the epoch so computeFlatSet can see the changes
                DagState updated = ds.withCurrentEpochState(es.removeNodes(nodeIdsToReset));
                newDags.put(entry.getKey(), updated.reactivateCurrentEpoch());
            } else if (es.isEmpty()) {
                // Epoch was pruned (e.g., after AUTO execution closeAndPruneEpoch).
                // Restore completion state from global NodeCounts for non-reset nodes
                // so that predecessors of the rerun target are seen as completed.
                EpochState restored = restoreEpochFromNodeCounts(es, nodeIdsToReset);
                DagState updated = ds.withCurrentEpochState(restored);
                newDags.put(entry.getKey(), updated.reactivateCurrentEpoch());
            } else {
                // Epoch has state but none of the reset nodes are in it - still reactivate
                // to ensure flat views include this epoch's data during rerun execution.
                newDags.put(entry.getKey(), ds.reactivateCurrentEpoch());
            }
        }
        return fromDags(version, seq, newDags, nodes, edges);
    }

    /**
     * Remove specific node IDs from a specific DAG's current epoch state only.
     *
     * <p>Unlike {@link #resetDag(Set)} which operates on ALL DAGs and reactivates them
     * (designed for step rerun), this method targets a single DAG and does NOT reactivate
     * any epochs. It only removes node IDs from the tracking sets of the specified DAG's
     * current epoch. Other DAGs are left completely untouched.
     *
     * <p>Used by loop back-edge iteration to "un-complete" body nodes so they can be
     * re-executed in the next loop iteration. The epoch is already active during execution,
     * so no reactivation is needed.
     *
     * @param triggerId the trigger/DAG ID to target
     * @param nodeIdsToReset the set of node IDs to remove from all tracking sets
     */
    public StateSnapshot removeNodesFromDag(String triggerId, Set<String> nodeIdsToReset) {
        Map<String, DagState> newDags = new HashMap<>(dags);
        DagState ds = newDags.get(triggerId);
        if (ds == null) {
            return this; // DAG not found - no-op
        }
        EpochState es = ds.currentEpochState();
        DagState updated = ds.withCurrentEpochState(es.removeNodes(nodeIdsToReset));
        newDags.put(triggerId, updated);
        return fromDags(version, seq, newDags, nodes, edges);
    }

    /**
     * Restore epoch state from global NodeCounts for nodes NOT in the reset set.
     *
     * <p>NodeCounts accumulate across all epochs and are never pruned, so they serve
     * as a durable record of which nodes have executed. This method uses them to
     * populate the epoch's completedNodeIds for upstream (non-reset) nodes.
     *
     * @param baseEpoch the base epoch state (may already have some nodes, e.g., trigger in ready)
     * @param nodeIdsToReset nodes being reset - excluded from restoration
     * @return epoch state with completion data restored for non-reset nodes
     */
    private EpochState restoreEpochFromNodeCounts(EpochState baseEpoch, Set<String> nodeIdsToReset) {
        EpochState result = baseEpoch;
        for (var entry : nodes.entrySet()) {
            String nodeId = entry.getKey();
            NodeCounts counts = entry.getValue();
            // Skip nodes that are being reset
            if (nodeIdsToReset.contains(nodeId)) continue;
            // Skip nodes already tracked in the epoch
            if (baseEpoch.getCompletedNodeIds().contains(nodeId)
                    || baseEpoch.getFailedNodeIds().contains(nodeId)
                    || baseEpoch.getSkippedNodeIds().contains(nodeId)) continue;
            // Restore from NodeCounts: completed takes priority over failed
            if (counts.completed() > 0) {
                result = result.markNodeCompleted(nodeId);
            } else if (counts.failed() > 0) {
                result = result.markNodeFailed(nodeId);
            }
        }
        return result;
    }

    /**
     * Record a decision branch (flat/legacy).
     */
    public StateSnapshot recordDecisionBranch(String nodeId, String branch) {
        String triggerId = findDagContaining(nodeId);
        return recordDecisionBranch(triggerId, nodeId, branch);
    }

    /**
     * Update loop state (flat/legacy).
     */
    public StateSnapshot updateLoopState(String loopId, LoopState state) {
        String triggerId = findDagContaining(loopId);
        return updateLoopState(triggerId, loopId, state);
    }

    /**
     * Update split state (flat/legacy).
     */
    public StateSnapshot updateSplitState(String splitId, SplitState state) {
        String triggerId = findDagContaining(splitId);
        return updateSplitState(triggerId, splitId, state);
    }

    // ========================================================================
    // LEGACY COUNT METHODS
    // ========================================================================

    /**
     * Increment a node's global status count (without touching epoch state).
     * Used by tests to simulate NodeCounts from previous epochs.
     */
    public StateSnapshot incrementNodeCount(String nodeId, String status) {
        Map<String, NodeCounts> newNodes = new HashMap<>(this.nodes);
        NodeCounts current = newNodes.getOrDefault(nodeId, NodeCounts.zero());
        newNodes.put(nodeId, current.increment(status));
        return fromDags(version, seq, dags, newNodes, edges);
    }

    /**
     * Increment an edge's status count.
     */
    public StateSnapshot incrementEdge(String from, String to, String status) {
        String edgeKey = from + "->" + to;
        Map<String, EdgeCounts> newEdges = new HashMap<>(this.edges);
        EdgeCounts current = newEdges.getOrDefault(edgeKey, EdgeCounts.zero());
        newEdges.put(edgeKey, current.increment(status));
        return fromDags(version, seq, dags, nodes, newEdges);
    }

    // ========================================================================
    // SEQ & UTILITY
    // ========================================================================

    /**
     * Increment the monotonic sequence number.
     * Called by StateSnapshotService.saveSnapshot() before every DB write.
     */
    public StateSnapshot withIncrementedSeq() {
        return new StateSnapshot(version, seq + 1, dags, nodes, edges, totalDurationMs, lastUpdated);
    }

    // ========================================================================
    // GETTERS - Flat views (union across all DAGs' current epochs)
    // ========================================================================

    public int getVersion() { return version; }
    public long getSeq() { return seq; }
    public Map<String, DagState> getDags() { return dags; }

    /**
     * Flat-view getters use the double-checked-volatile-read pattern so the
     * computed Set/Map is published safely across threads (StateSnapshot is
     * immutable from the caller's perspective; the cache is internal memoization).
     * Worst case under contention: two threads each compute once and one wins
     * the volatile write - same content because computeFlat* is deterministic.
     */
    public Set<String> getCompletedNodeIds() {
        Set<String> c = completedNodeIds;
        if (c != null) return c;
        c = computeFlatSet(dags, EpochState::getCompletedNodeIds);
        completedNodeIds = c;
        return c;
    }

    public Set<String> getFailedNodeIds() {
        Set<String> c = failedNodeIds;
        if (c != null) return c;
        c = computeFlatSet(dags, EpochState::getFailedNodeIds);
        failedNodeIds = c;
        return c;
    }

    public Set<String> getSkippedNodeIds() {
        Set<String> c = skippedNodeIds;
        if (c != null) return c;
        c = computeFlatSet(dags, EpochState::getSkippedNodeIds);
        skippedNodeIds = c;
        return c;
    }

    public Set<String> getRunningNodeIds() {
        Set<String> c = runningNodeIds;
        if (c != null) return c;
        c = computeFlatSet(dags, EpochState::getRunningNodeIds);
        runningNodeIds = c;
        return c;
    }

    public Set<String> getReadyNodeIds() {
        Set<String> c = readyNodeIds;
        if (c != null) return c;
        c = computeFlatSet(dags, EpochState::getReadyNodeIds);
        readyNodeIds = c;
        return c;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public Set<String> getReadyNodes() { return getReadyNodeIds(); }

    /**
     * Flat union of {@link EpochState#getPartialFailedNodeIds()} across active epochs:
     * nodes that COMPLETED in the current pass but had per-item failures (split
     * continue-anyway). State reconstruction uses this CURRENT-pass evidence to derive
     * PARTIAL_SUCCESS instead of the accumulated NodeCounts - accumulated counts keep a
     * stale {@code failed} entry after a rerun fixed a previously-FAILED node, and that
     * history must not demote the fresh COMPLETED status. {@code @JsonIgnore}: derived
     * view, must not widen the persisted JSONB payload. Not memoized (API path only).
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public Set<String> getPartialFailedNodeIds() {
        return computeFlatSet(dags, EpochState::getPartialFailedNodeIds);
    }

    public Map<String, NodeCounts> getNodes() { return nodes; }
    public Map<String, EdgeCounts> getEdges() { return edges; }

    public Map<String, Set<String>> getDecisionBranches() {
        Map<String, Set<String>> c = decisionBranches;
        if (c != null) return c;
        c = computeFlatBranches(dags);
        decisionBranches = c;
        return c;
    }

    public Map<String, LoopState> getLoops() {
        Map<String, LoopState> c = loops;
        if (c != null) return c;
        c = computeFlatLoops(dags);
        loops = c;
        return c;
    }

    public Map<String, SplitState> getSplits() {
        Map<String, SplitState> c = splits;
        if (c != null) return c;
        c = computeFlatSplits(dags);
        splits = c;
        return c;
    }

    public Set<String> getAwaitingSignalNodeIds() {
        Set<String> c = awaitingSignalNodeIds;
        if (c != null) return c;
        c = computeFlatSet(dags, EpochState::getAwaitingSignalNodeIds);
        awaitingSignalNodeIds = c;
        return c;
    }
    public long getTotalDurationMs() { return totalDurationMs; }
    public Instant getLastUpdated() { return lastUpdated; }

    /**
     * Get counts for a specific node.
     */
    public NodeCounts getNodeCounts(String nodeId) {
        return nodes.getOrDefault(nodeId, NodeCounts.zero());
    }

    /**
     * Get counts for a specific edge.
     */
    public EdgeCounts getEdgeCounts(String from, String to) {
        String edgeKey = from + "->" + to;
        return edges.getOrDefault(edgeKey, EdgeCounts.zero());
    }

    /**
     * Get branches taken for a decision node (flat view).
     */
    public Set<String> getDecisionBranches(String nodeId) {
        return getDecisionBranches().getOrDefault(nodeId, Set.of());
    }

    /**
     * Get loop state (flat view).
     */
    public LoopState getLoopState(String loopId) { return getLoops().get(loopId); }

    /**
     * Get split state (flat view).
     */
    public SplitState getSplitState(String splitId) { return getSplits().get(splitId); }

    // ========================================================================
    // DAG-SCOPED GETTERS
    // ========================================================================

    /**
     * Get completed node IDs for a specific DAG/epoch.
     */
    public Set<String> getCompletedNodeIds(String triggerId, int epoch) {
        return getEpochState(triggerId, epoch).getCompletedNodeIds();
    }

    /**
     * Terminal node IDs (COMPLETED union FAILED union SKIPPED) for a specific DAG/epoch when
     * {@code triggerId != null && epoch >= 0}; otherwise the flat union across all active
     * epochs/DAGs. This is the canonical "already reached a terminal status" set used to skip
     * re-executing a node when a resume / re-drive path re-derives successors OUTSIDE the
     * {@code ReadyNodeCalculator} gate (which already skips terminal nodes). Call sites:
     * {@code SignalResumeService.filterOutAlreadyTerminalNodes} and
     * {@code AgentAsyncCompletionService} per-item successor dispatch. epoch 0 is a real,
     * common first-fire epoch, so the epoch-scoped branch uses {@code epoch >= 0} - a
     * {@code > 0} gate would silently downgrade epoch 0 to the cross-epoch flat union.
     */
    public Set<String> getTerminalNodeIds(String triggerId, int epoch) {
        Set<String> terminal = new java.util.HashSet<>();
        if (triggerId != null && epoch >= 0) {
            EpochState es = getEpochState(triggerId, epoch);
            terminal.addAll(es.getCompletedNodeIds());
            terminal.addAll(es.getFailedNodeIds());
            terminal.addAll(es.getSkippedNodeIds());
        } else {
            terminal.addAll(getCompletedNodeIds());
            terminal.addAll(getFailedNodeIds());
            terminal.addAll(getSkippedNodeIds());
        }
        return terminal;
    }

    /**
     * Get ready node IDs for a specific DAG/epoch.
     */
    public Set<String> getReadyNodeIds(String triggerId, int epoch) {
        return getEpochState(triggerId, epoch).getReadyNodeIds();
    }

    // ========================================================================
    // INTERNAL HELPERS
    // ========================================================================

    /**
     * Find which DAG contains a node (in any status set of any active epoch).
     * Returns the default trigger ID if no DAG contains it.
     *
     * <p>Made public for the patch-path builders ({@code RemoveReadyNodePatchBuilder},
     * etc.) so the call site in {@code StateSnapshotService} can resolve the
     * triggerId before delegating to the builder, mirroring the resolution
     * the flat-method mutator (e.g. {@link #removeReadyNode(String)}) does
     * internally.
     */
    public String findDagContaining(String nodeId) {
        for (var entry : dags.entrySet()) {
            DagState ds = entry.getValue();
            // Search active epochs first, then fall back to current epoch
            if (ds.hasActiveEpochs()) {
                for (int activeEpoch : ds.getActiveEpochs()) {
                    EpochState es = ds.getEpochState(activeEpoch);
                    if (es != null && epochContainsNode(es, nodeId)) {
                        return entry.getKey();
                    }
                }
            } else {
                if (epochContainsNode(ds.currentEpochState(), nodeId)) {
                    return entry.getKey();
                }
            }
        }
        return getDefaultTriggerId();
    }

    private static boolean epochContainsNode(EpochState es, String nodeId) {
        // P2.3 site 9 - `runningNodeIds` intentionally NOT consulted. Post-elide
        // (default-ON for all tenants), running state lives ONLY in Redis
        // (RunningNodeTracker), never in JSONB. A node strictly in the
        // running state is therefore not locatable via this flat predicate;
        // callers needing to operate on a running node MUST use the DAG-scoped
        // APIs that carry the triggerId explicitly.
        return es.getCompletedNodeIds().contains(nodeId) ||
                es.getFailedNodeIds().contains(nodeId) ||
                es.getSkippedNodeIds().contains(nodeId) ||
                es.getReadyNodeIds().contains(nodeId) ||
                es.getAwaitingSignalNodeIds().contains(nodeId);
    }

    /**
     * Get the default trigger ID. For single-DAG workflows this is the only trigger.
     * For empty snapshots, returns a sentinel value.
     *
     * Prefers real triggers over the "trigger:default" sentinel.
     * "trigger:default" is a V2→V3 migration artifact that should not receive
     * new data when a real trigger DagState exists.
     */
    public String getDefaultTriggerId() {
        if (dags.size() == 1) {
            return dags.keySet().iterator().next();
        }
        if (dags.isEmpty()) {
            return DEFAULT_TRIGGER_SENTINEL;
        }
        // Prefer a real trigger over "trigger:default"
        for (String key : dags.keySet()) {
            if (!DEFAULT_TRIGGER_SENTINEL.equals(key)) {
                return key;
            }
        }
        return dags.keySet().iterator().next();
    }

    /**
     * Compute the flat union of a field across all active epochs of all DAGs.
     * If a DAG has activeEpochs, unions those; otherwise falls back to currentEpochState().
     * This ensures parallel epochs all contribute to the flat view.
     */
    private static Set<String> computeFlatSet(Map<String, DagState> dags,
                                                java.util.function.Function<EpochState, Set<String>> getter) {
        Set<String> result = new HashSet<>();
        for (DagState ds : dags.values()) {
            if (ds.hasActiveEpochs()) {
                for (int activeEpoch : ds.getActiveEpochs()) {
                    EpochState es = ds.getEpochState(activeEpoch);
                    if (es != null) result.addAll(getter.apply(es));
                }
            } else {
                result.addAll(getter.apply(ds.currentEpochState()));
            }
        }
        return Set.copyOf(result);
    }

    private static Map<String, Set<String>> computeFlatBranches(Map<String, DagState> dags) {
        Map<String, Set<String>> result = new HashMap<>();
        for (DagState ds : dags.values()) {
            if (ds.hasActiveEpochs()) {
                for (int activeEpoch : ds.getActiveEpochs()) {
                    EpochState es = ds.getEpochState(activeEpoch);
                    if (es != null) {
                        for (var entry : es.getDecisionBranchesMap().entrySet()) {
                            result.computeIfAbsent(entry.getKey(), k -> new HashSet<>()).addAll(entry.getValue());
                        }
                    }
                }
            } else {
                for (var entry : ds.currentEpochState().getDecisionBranchesMap().entrySet()) {
                    result.computeIfAbsent(entry.getKey(), k -> new HashSet<>()).addAll(entry.getValue());
                }
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, LoopState> computeFlatLoops(Map<String, DagState> dags) {
        Map<String, LoopState> result = new HashMap<>();
        for (DagState ds : dags.values()) {
            if (ds.hasActiveEpochs()) {
                for (int activeEpoch : ds.getActiveEpochs()) {
                    EpochState es = ds.getEpochState(activeEpoch);
                    if (es != null) result.putAll(es.getLoops());
                }
            } else {
                result.putAll(ds.currentEpochState().getLoops());
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, SplitState> computeFlatSplits(Map<String, DagState> dags) {
        Map<String, SplitState> result = new HashMap<>();
        for (DagState ds : dags.values()) {
            if (ds.hasActiveEpochs()) {
                for (int activeEpoch : ds.getActiveEpochs()) {
                    EpochState es = ds.getEpochState(activeEpoch);
                    if (es != null) result.putAll(es.getSplits());
                }
            } else {
                result.putAll(ds.currentEpochState().getSplits());
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, DagState> migrateFlatToDags(
            Set<String> completed, Set<String> failed, Set<String> skipped,
            Set<String> running, Set<String> ready, Set<String> awaiting,
            Map<String, Set<String>> branches, Map<String, LoopState> loops,
            Map<String, SplitState> splits) {
        if (completed.isEmpty() && failed.isEmpty() && skipped.isEmpty() &&
                running.isEmpty() && ready.isEmpty() && awaiting.isEmpty()) {
            return Map.of();
        }
        EpochState epoch = new EpochState(completed, failed, skipped, running, ready, awaiting,
                branches, loops, splits, Instant.now());
        DagState dagState = new DagState(0, 0, 0, Map.of(0, epoch));
        return Map.of(DEFAULT_TRIGGER_SENTINEL, dagState);
    }

    private static Map<String, Set<String>> deepCopySetMap(Map<String, Set<String>> original) {
        Map<String, Set<String>> copy = new HashMap<>();
        for (var entry : original.entrySet()) {
            copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return Map.copyOf(copy);
    }

    // ========================================================================
    // NESTED RECORDS
    // ========================================================================

    /**
     * Node execution counts.
     *
     * <p>Tracks both cumulative ({@code totalExecutionTimeMs}) and most-recent
     * ({@code lastExecutionTimeMs}) durations. The cumulative value sums all
     * executions across every epoch/spawn; the last-execution value records
     * only the duration of the most recent completion - used for accurate
     * {@code startTime = lastEndTimeMs - lastExecutionTimeMs} computation.
     *
     * <p>Backwards-compatible: old JSONB without {@code lastExecutionTimeMs}
     * deserializes to 0 (Jackson primitive default), and consumers fall back
     * to {@code totalExecutionTimeMs}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NodeCounts(
            int running,
            int completed,
            int failed,
            int skipped,
            long totalExecutionTimeMs,
            long lastEndTimeMs,
            long lastExecutionTimeMs) {

        public static NodeCounts zero() {
            return new NodeCounts(0, 0, 0, 0, 0L, 0L, 0L);
        }

        public NodeCounts increment(String status) {
            return incrementWithTiming(status, 0L);
        }

        public NodeCounts incrementWithTiming(String status, long durationMs) {
            long newTotalTime = totalExecutionTimeMs;
            long newLastEnd = lastEndTimeMs;
            long newLastExec = lastExecutionTimeMs;
            if (durationMs > 0 && !"SKIPPED".equalsIgnoreCase(status)) {
                newTotalTime += durationMs;
                newLastEnd = System.currentTimeMillis();
                newLastExec = durationMs;
            }
            return switch (status.toUpperCase()) {
                case "RUNNING" -> this;
                case "COMPLETED", "SUCCESS" -> new NodeCounts(running, completed + 1, failed, skipped, newTotalTime, newLastEnd, newLastExec);
                case "FAILED", "ERROR" -> new NodeCounts(running, completed, failed + 1, skipped, newTotalTime, newLastEnd, newLastExec);
                case "SKIPPED" -> new NodeCounts(running, completed, failed, skipped + 1, totalExecutionTimeMs, lastEndTimeMs, lastExecutionTimeMs);
                default -> this;
            };
        }

        public NodeCounts incrementBy(String status, int count) {
            return switch (status.toUpperCase()) {
                case "COMPLETED", "SUCCESS" -> new NodeCounts(running, completed + count, failed, skipped, totalExecutionTimeMs, lastEndTimeMs, lastExecutionTimeMs);
                case "FAILED", "ERROR" -> new NodeCounts(running, completed, failed + count, skipped, totalExecutionTimeMs, lastEndTimeMs, lastExecutionTimeMs);
                case "SKIPPED" -> new NodeCounts(running, completed, failed, skipped + count, totalExecutionTimeMs, lastEndTimeMs, lastExecutionTimeMs);
                default -> this;
            };
        }

        public int total() {
            return running + completed + failed + skipped;
        }

        /**
         * Return the duration to use for display: prefer last execution,
         * fall back to total for old snapshots where lastExecutionTimeMs == 0.
         */
        public long displayDurationMs() {
            return lastExecutionTimeMs > 0 ? lastExecutionTimeMs : totalExecutionTimeMs;
        }

        public Map<String, Integer> toMap() {
            return Map.of(
                    "RUNNING", running,
                    "COMPLETED", completed,
                    "FAILED", failed,
                    "SKIPPED", skipped,
                    "TOTAL", total()
            );
        }
    }

    /**
     * Edge traversal counts.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EdgeCounts(
            int running,
            int completed,
            int skipped) {

        public static EdgeCounts zero() {
            return new EdgeCounts(0, 0, 0);
        }

        public EdgeCounts increment(String status) {
            return switch (status.toUpperCase()) {
                case "RUNNING" -> this;
                case "COMPLETED", "SUCCESS" -> new EdgeCounts(running, completed + 1, skipped);
                case "SKIPPED" -> new EdgeCounts(running, completed, skipped + 1);
                default -> this;
            };
        }

        public int total() {
            return running + completed + skipped;
        }

        public Map<String, Integer> toMap() {
            return Map.of(
                    "RUNNING", running,
                    "COMPLETED", completed,
                    "SKIPPED", skipped,
                    "TOTAL", total()
            );
        }
    }

    /**
     * Loop iteration state.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoopState(
            int currentIndex,
            int totalItems,
            String status) {

        public static LoopState initial(int totalItems) {
            return new LoopState(0, totalItems, "ITERATING");
        }

        public LoopState nextIteration() {
            return new LoopState(currentIndex + 1, totalItems, "ITERATING");
        }

        public LoopState complete() {
            return new LoopState(currentIndex, totalItems, "COMPLETED");
        }

        public boolean isComplete() {
            return "COMPLETED".equals(status) || currentIndex >= totalItems;
        }
    }

    /**
     * Split context state for parallel item processing.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SplitState(
            int itemCount,
            int completedCount,
            int failedCount) {

        public static SplitState initial(int itemCount) {
            return new SplitState(itemCount, 0, 0);
        }

        public SplitState incrementCompleted() {
            return new SplitState(itemCount, completedCount + 1, failedCount);
        }

        public SplitState incrementFailed() {
            return new SplitState(itemCount, completedCount, failedCount + 1);
        }

        public boolean isComplete() {
            return completedCount + failedCount >= itemCount;
        }

        public int pendingCount() {
            return itemCount - completedCount - failedCount;
        }
    }
}
