package com.apimarketplace.orchestrator.domain.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-epoch execution state within a single DAG.
 *
 * <p>Each epoch represents one complete trigger fire. All node execution tracking
 * (completed, failed, skipped, running, ready) is scoped to this epoch.
 * Branching state (decisions, loops, splits) is also epoch-scoped since
 * a new epoch means re-evaluation of all conditions.
 *
 * <p>Immutable - all mutations return new instances.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class EpochState {

    private final Set<String> completedNodeIds;
    private final Set<String> failedNodeIds;
    /**
     * Set of nodes that completed globally (in {@link #completedNodeIds}) but had at
     * least one per-item failure inside a split context.
     *
     * <p><strong>Inspector / observability only.</strong> NOT consulted by readiness
     * gates - those nodes are in {@code completedNodeIds}, so the standard COMPLETED
     * path applies. Use this set to drive UI partial-success badges, retry-suggestion
     * tooling, or per-item error reports.
     *
     * <p>Forward-compat: deserializing an old EpochState JSONB payload (without this
     * field) defaults to {@code Set.of()} via {@link #EpochState(Set, Set, Set, Set,
     * Set, Set, Set, Map, Map, Map, Instant) the canonical constructor}'s null guard
     * + {@code @JsonIgnoreProperties(ignoreUnknown = true)} on the class.
     */
    private final Set<String> partialFailedNodeIds;
    private final Set<String> skippedNodeIds;
    private final Set<String> runningNodeIds;
    private final Set<String> readyNodeIds;
    private final Set<String> awaitingSignalNodeIds;
    private final Map<String, Set<String>> decisionBranches;
    private final Map<String, StateSnapshot.LoopState> loops;
    private final Map<String, StateSnapshot.SplitState> splits;
    private final Instant startedAt;

    /**
     * Canonical constructor (Jackson-bound). Includes {@code partialFailedNodeIds}.
     */
    @JsonCreator
    public EpochState(
            @JsonProperty("completedNodeIds") Set<String> completedNodeIds,
            @JsonProperty("failedNodeIds") Set<String> failedNodeIds,
            @JsonProperty("partialFailedNodeIds") Set<String> partialFailedNodeIds,
            @JsonProperty("skippedNodeIds") Set<String> skippedNodeIds,
            @JsonProperty("runningNodeIds") Set<String> runningNodeIds,
            @JsonProperty("readyNodeIds") Set<String> readyNodeIds,
            @JsonProperty("awaitingSignalNodeIds") Set<String> awaitingSignalNodeIds,
            @JsonProperty("decisionBranches") Map<String, Set<String>> decisionBranches,
            @JsonProperty("loops") Map<String, StateSnapshot.LoopState> loops,
            @JsonProperty("splits") Map<String, StateSnapshot.SplitState> splits,
            @JsonProperty("startedAt") Instant startedAt) {
        this.completedNodeIds = completedNodeIds != null ? Set.copyOf(completedNodeIds) : Set.of();
        this.failedNodeIds = failedNodeIds != null ? Set.copyOf(failedNodeIds) : Set.of();
        this.partialFailedNodeIds = partialFailedNodeIds != null ? Set.copyOf(partialFailedNodeIds) : Set.of();
        this.skippedNodeIds = skippedNodeIds != null ? Set.copyOf(skippedNodeIds) : Set.of();
        this.runningNodeIds = runningNodeIds != null ? Set.copyOf(runningNodeIds) : Set.of();
        this.readyNodeIds = readyNodeIds != null ? Set.copyOf(readyNodeIds) : Set.of();
        this.awaitingSignalNodeIds = awaitingSignalNodeIds != null ? Set.copyOf(awaitingSignalNodeIds) : Set.of();
        this.decisionBranches = decisionBranches != null ? deepCopySetMap(decisionBranches) : Map.of();
        this.loops = loops != null ? Map.copyOf(loops) : Map.of();
        this.splits = splits != null ? Map.copyOf(splits) : Map.of();
        this.startedAt = startedAt != null ? startedAt : Instant.now();
    }

    /**
     * Backward-compat constructor (existing test fixtures + StateSnapshot mutators).
     * Defaults {@code partialFailedNodeIds} to empty.
     */
    public EpochState(
            Set<String> completedNodeIds,
            Set<String> failedNodeIds,
            Set<String> skippedNodeIds,
            Set<String> runningNodeIds,
            Set<String> readyNodeIds,
            Set<String> awaitingSignalNodeIds,
            Map<String, Set<String>> decisionBranches,
            Map<String, StateSnapshot.LoopState> loops,
            Map<String, StateSnapshot.SplitState> splits,
            Instant startedAt) {
        this(completedNodeIds, failedNodeIds, Set.of(), skippedNodeIds,
            runningNodeIds, readyNodeIds, awaitingSignalNodeIds,
            decisionBranches, loops, splits, startedAt);
    }

    /**
     * Create a fresh epoch state (start of a new epoch).
     */
    public static EpochState fresh() {
        return new EpochState(
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Map.of(), Map.of(), Map.of(), Instant.now()
        );
    }

    // ========================================================================
    // NODE STATUS MUTATIONS
    // ========================================================================

    public EpochState markNodeCompleted(String nodeId) {
        Set<String> newRunning = new HashSet<>(runningNodeIds);
        newRunning.remove(nodeId);
        Set<String> newReady = new HashSet<>(readyNodeIds);
        newReady.remove(nodeId);
        Set<String> newCompleted = new HashSet<>(completedNodeIds);
        newCompleted.add(nodeId);
        return new EpochState(newCompleted, failedNodeIds, partialFailedNodeIds, skippedNodeIds,
                newRunning, newReady, awaitingSignalNodeIds,
                decisionBranches, loops, splits, startedAt);
    }

    public EpochState markNodeFailed(String nodeId) {
        Set<String> newRunning = new HashSet<>(runningNodeIds);
        newRunning.remove(nodeId);
        Set<String> newReady = new HashSet<>(readyNodeIds);
        newReady.remove(nodeId);
        Set<String> newFailed = new HashSet<>(failedNodeIds);
        newFailed.add(nodeId);
        return new EpochState(completedNodeIds, newFailed, partialFailedNodeIds, skippedNodeIds,
                newRunning, newReady, awaitingSignalNodeIds,
                decisionBranches, loops, splits, startedAt);
    }

    /**
     * Mark a node as having had at least one per-item failure inside a split context,
     * while remaining COMPLETED globally. Used by Phase 2.E aggregate write at barrier seal.
     *
     * <p>Idempotent: re-applying for an already-marked node returns an equivalent state.
     */
    public EpochState markNodePartialFailure(String nodeId) {
        Set<String> newPartial = new HashSet<>(partialFailedNodeIds);
        newPartial.add(nodeId);
        return new EpochState(completedNodeIds, failedNodeIds, newPartial, skippedNodeIds,
                runningNodeIds, readyNodeIds, awaitingSignalNodeIds,
                decisionBranches, loops, splits, startedAt);
    }

    public EpochState markNodeSkipped(String nodeId) {
        Set<String> newReady = new HashSet<>(readyNodeIds);
        newReady.remove(nodeId);
        Set<String> newSkipped = new HashSet<>(skippedNodeIds);
        newSkipped.add(nodeId);
        return new EpochState(completedNodeIds, failedNodeIds, partialFailedNodeIds, newSkipped,
                runningNodeIds, newReady, awaitingSignalNodeIds,
                decisionBranches, loops, splits, startedAt);
    }

    public EpochState skipAwaitingSignal(String nodeId) {
        Set<String> newRunning = new HashSet<>(runningNodeIds);
        newRunning.remove(nodeId);
        Set<String> newReady = new HashSet<>(readyNodeIds);
        newReady.remove(nodeId);
        Set<String> newAwaiting = new HashSet<>(awaitingSignalNodeIds);
        newAwaiting.remove(nodeId);
        Set<String> newSkipped = new HashSet<>(skippedNodeIds);
        newSkipped.add(nodeId);
        return new EpochState(completedNodeIds, failedNodeIds, partialFailedNodeIds, newSkipped,
                newRunning, newReady, newAwaiting,
                decisionBranches, loops, splits, startedAt);
    }

    public EpochState markNodeAwaitingSignal(String nodeId) {
        Set<String> newRunning = new HashSet<>(runningNodeIds);
        newRunning.remove(nodeId);
        Set<String> newReady = new HashSet<>(readyNodeIds);
        newReady.remove(nodeId);
        Set<String> newAwaiting = new HashSet<>(awaitingSignalNodeIds);
        newAwaiting.add(nodeId);
        return new EpochState(completedNodeIds, failedNodeIds, partialFailedNodeIds, skippedNodeIds,
                newRunning, newReady, newAwaiting,
                decisionBranches, loops, splits, startedAt);
    }

    public EpochState resolveAwaitingSignal(String nodeId) {
        return resolveAwaitingSignal(nodeId, false);
    }

    /**
     * Resolve a node's signal wait.
     *
     * @param nodeId           the node whose signal was resolved
     * @param keepInAwaiting   if true, the node stays in awaitingSignalNodeIds and is NOT
     *                         added to completedNodeIds (split context: one item resolved but
     *                         other items still have pending signals). Adding to completedNodeIds
     *                         prematurely would cause ReadyNodeCalculator to trigger successors
     *                         before all split items are resolved.
     */
    public EpochState resolveAwaitingSignal(String nodeId, boolean keepInAwaiting) {
        Set<String> newAwaiting = new HashSet<>(awaitingSignalNodeIds);
        if (!keepInAwaiting) {
            newAwaiting.remove(nodeId);
        }
        // Only mark as completed when all signals are resolved (keepInAwaiting=false).
        // When keepInAwaiting=true, the node stays in awaitingSignalNodeIds only -
        // ReadyNodeCalculator must not see it as completed until all split items finish.
        Set<String> newCompleted = new HashSet<>(completedNodeIds);
        if (!keepInAwaiting) {
            newCompleted.add(nodeId);
        }
        return new EpochState(newCompleted, failedNodeIds, partialFailedNodeIds, skippedNodeIds,
                runningNodeIds, readyNodeIds, newAwaiting,
                decisionBranches, loops, splits, startedAt);
    }

    /**
     * Move a node from RUNNING back to READY without touching any other state
     * (loop/split/branch state preserved). Error-recovery inverse of a claim
     * whose execution died before producing any outcome.
     */
    public EpochState releaseRunningToReady(String nodeId) {
        Set<String> newRunning = new HashSet<>(runningNodeIds);
        newRunning.remove(nodeId);
        Set<String> newReady = new HashSet<>(readyNodeIds);
        newReady.add(nodeId);
        return new EpochState(completedNodeIds, failedNodeIds, partialFailedNodeIds, skippedNodeIds,
                newRunning, newReady, awaitingSignalNodeIds,
                decisionBranches, loops, splits, startedAt);
    }

    public EpochState addRunningNode(String nodeId) {
        Set<String> newRunning = new HashSet<>(runningNodeIds);
        newRunning.add(nodeId);
        Set<String> newReady = new HashSet<>(readyNodeIds);
        newReady.remove(nodeId);
        return new EpochState(completedNodeIds, failedNodeIds, partialFailedNodeIds, skippedNodeIds,
                newRunning, newReady, awaitingSignalNodeIds,
                decisionBranches, loops, splits, startedAt);
    }

    // ========================================================================
    // READY NODE MUTATIONS
    // ========================================================================

    public EpochState withReadyNodes(Set<String> newReadyNodes) {
        return new EpochState(completedNodeIds, failedNodeIds, partialFailedNodeIds, skippedNodeIds,
                runningNodeIds, newReadyNodes != null ? newReadyNodes : Set.of(), awaitingSignalNodeIds,
                decisionBranches, loops, splits, startedAt);
    }

    public EpochState addReadyNode(String nodeId) {
        Set<String> newReady = new HashSet<>(readyNodeIds);
        newReady.add(nodeId);
        return withReadyNodes(newReady);
    }

    public EpochState removeReadyNode(String nodeId) {
        Set<String> newReady = new HashSet<>(readyNodeIds);
        newReady.remove(nodeId);
        return withReadyNodes(newReady);
    }

    // ========================================================================
    // BRANCHING STATE MUTATIONS
    // ========================================================================

    public EpochState recordDecisionBranch(String nodeId, String branch) {
        Map<String, Set<String>> newBranches = new HashMap<>();
        for (var entry : decisionBranches.entrySet()) {
            newBranches.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        newBranches.computeIfAbsent(nodeId, k -> new HashSet<>()).add(branch);
        return new EpochState(completedNodeIds, failedNodeIds, partialFailedNodeIds, skippedNodeIds,
                runningNodeIds, readyNodeIds, awaitingSignalNodeIds,
                newBranches, loops, splits, startedAt);
    }

    public EpochState updateLoopState(String loopId, StateSnapshot.LoopState state) {
        Map<String, StateSnapshot.LoopState> newLoops = new HashMap<>(loops);
        newLoops.put(loopId, state);
        return new EpochState(completedNodeIds, failedNodeIds, partialFailedNodeIds, skippedNodeIds,
                runningNodeIds, readyNodeIds, awaitingSignalNodeIds,
                decisionBranches, newLoops, splits, startedAt);
    }

    public EpochState updateSplitState(String splitId, StateSnapshot.SplitState state) {
        Map<String, StateSnapshot.SplitState> newSplits = new HashMap<>(splits);
        newSplits.put(splitId, state);
        return new EpochState(completedNodeIds, failedNodeIds, partialFailedNodeIds, skippedNodeIds,
                runningNodeIds, readyNodeIds, awaitingSignalNodeIds,
                decisionBranches, loops, newSplits, startedAt);
    }

    // ========================================================================
    // SELECTIVE REMOVAL (for step rerun)
    // ========================================================================

    /**
     * Remove specific nodes from all tracking sets (completed, failed, skipped, running, ready,
     * awaiting signal) and from decision branches, loop states, and split states.
     * Preserves all other nodes.
     *
     * <p>Used by step rerun to selectively reset only the target + downstream steps
     * while keeping unrelated nodes (e.g., trigger) in their current state.
     *
     * <p>Loop/split state removal matters for rerun correctness: a rerun of (or through)
     * a loop or split node must restart it from scratch. A stale {@code LoopState}
     * (iteration counter) or {@code SplitState} (item progress) left behind would be
     * picked up by state reconstruction and by split-awareness checks
     * ({@code V2StepByStepService.isSplitAwareNode}) on the re-execution.
     *
     * @param nodeIdsToRemove the set of node IDs to remove from all tracking sets
     * @return a new EpochState with the specified nodes removed
     */
    public EpochState removeNodes(Set<String> nodeIdsToRemove) {
        Set<String> newCompleted = new HashSet<>(completedNodeIds);
        newCompleted.removeAll(nodeIdsToRemove);
        Set<String> newFailed = new HashSet<>(failedNodeIds);
        newFailed.removeAll(nodeIdsToRemove);
        Set<String> newPartialFailed = new HashSet<>(partialFailedNodeIds);
        newPartialFailed.removeAll(nodeIdsToRemove);
        Set<String> newSkipped = new HashSet<>(skippedNodeIds);
        newSkipped.removeAll(nodeIdsToRemove);
        Set<String> newRunning = new HashSet<>(runningNodeIds);
        newRunning.removeAll(nodeIdsToRemove);
        Set<String> newReady = new HashSet<>(readyNodeIds);
        newReady.removeAll(nodeIdsToRemove);
        Set<String> newAwaiting = new HashSet<>(awaitingSignalNodeIds);
        newAwaiting.removeAll(nodeIdsToRemove);

        // Also remove decision branches for the removed nodes
        Map<String, Set<String>> newBranches = new HashMap<>();
        for (var entry : decisionBranches.entrySet()) {
            if (!nodeIdsToRemove.contains(entry.getKey())) {
                newBranches.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
        }

        // Also remove loop/split state for the removed nodes - a rerun that resets a
        // loop or split node must restart it from iteration/item zero, not resume from
        // the stale pre-rerun counter.
        Map<String, StateSnapshot.LoopState> newLoops = removeKeys(loops, nodeIdsToRemove);
        Map<String, StateSnapshot.SplitState> newSplits = removeKeys(splits, nodeIdsToRemove);

        return new EpochState(newCompleted, newFailed, newPartialFailed, newSkipped,
                newRunning, newReady, newAwaiting,
                newBranches, newLoops, newSplits, startedAt);
    }

    private static <V> Map<String, V> removeKeys(Map<String, V> original, Set<String> keysToRemove) {
        boolean hasAny = false;
        for (String key : keysToRemove) {
            if (original.containsKey(key)) {
                hasAny = true;
                break;
            }
        }
        if (!hasAny) {
            return original;
        }
        Map<String, V> copy = new HashMap<>(original);
        copy.keySet().removeAll(keysToRemove);
        return copy;
    }

    // ========================================================================
    // QUERIES
    // ========================================================================

    public Set<String> getDecisionBranches(String nodeId) {
        return decisionBranches.getOrDefault(nodeId, Set.of());
    }

    public StateSnapshot.LoopState getLoopState(String loopId) {
        return loops.get(loopId);
    }

    public StateSnapshot.SplitState getSplitState(String splitId) {
        return splits.get(splitId);
    }

    /**
     * Check if this epoch has any active execution state.
     */
    public boolean isEmpty() {
        return completedNodeIds.isEmpty() && failedNodeIds.isEmpty() &&
                skippedNodeIds.isEmpty() && runningNodeIds.isEmpty() &&
                awaitingSignalNodeIds.isEmpty();
    }

    /**
     * Check if a node is resolved (completed, failed, or skipped) in this epoch.
     */
    public boolean isResolved(String nodeId) {
        return completedNodeIds.contains(nodeId)
                || failedNodeIds.contains(nodeId)
                || skippedNodeIds.contains(nodeId);
    }

    // ========================================================================
    // GETTERS
    // ========================================================================

    public Set<String> getCompletedNodeIds() { return completedNodeIds; }
    public Set<String> getFailedNodeIds() { return failedNodeIds; }
    public Set<String> getPartialFailedNodeIds() { return partialFailedNodeIds; }
    public boolean isPartialFailure(String nodeId) { return partialFailedNodeIds.contains(nodeId); }
    public Set<String> getSkippedNodeIds() { return skippedNodeIds; }
    public Set<String> getRunningNodeIds() { return runningNodeIds; }
    public Set<String> getReadyNodeIds() { return readyNodeIds; }
    public Set<String> getAwaitingSignalNodeIds() { return awaitingSignalNodeIds; }
    @JsonProperty("decisionBranches")
    public Map<String, Set<String>> getDecisionBranchesMap() { return decisionBranches; }
    public Map<String, StateSnapshot.LoopState> getLoops() { return loops; }
    public Map<String, StateSnapshot.SplitState> getSplits() { return splits; }
    public Instant getStartedAt() { return startedAt; }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private static Map<String, Set<String>> deepCopySetMap(Map<String, Set<String>> original) {
        Map<String, Set<String>> copy = new HashMap<>();
        for (var entry : original.entrySet()) {
            copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return Map.copyOf(copy);
    }
}
