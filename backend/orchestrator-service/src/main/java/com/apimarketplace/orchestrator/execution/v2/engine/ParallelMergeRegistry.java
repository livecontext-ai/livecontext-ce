package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scoped registry of merge nodes deferred during parallel branch traversal.
 *
 * <p><b>Why it exists.</b> When the engine runs branches of a fork (explicit
 * {@code core:fork} or any node with multiple successors) in parallel, each
 * branch carries an isolated, immutable {@link ExecutionContext} that only
 * contains that branch's completions. A merge node (explicit {@code core:merge}
 * or implicit multi-predecessor node) reached inside a branch can therefore
 * never see its sibling predecessors as completed - it must be <i>deferred</i>
 * and executed at the fork's join point, after all branch contexts are merged.
 * This class is the single registry for those deferrals, replacing the
 * per-call {@code CopyOnWriteArrayList<ExecutionNode> waitingMergeNodes} +
 * {@code Set<String> processedMerges} pair that previously lived inline in
 * {@code UnifiedExecutionEngine.handleForkParallelExecution}.
 *
 * <h2>Scope chain</h2>
 *
 * <p>Registries form a parent chain mirroring nested fork scopes: the outermost
 * fork of a traversal creates a {@link #root()} scope; a nested fork inside a
 * branch creates a {@link #child()} scope. Each scope collects the merges
 * deferred by <i>its own</i> sub-branches. At a scope's join point, the engine
 * attempts each deferred merge against that scope's merged context:
 *
 * <ul>
 *   <li>executable → atomically {@linkplain #tryClaim claimed} and executed
 *       exactly once by the joining thread (the "last arriver" of that scope);</li>
 *   <li>not yet executable (predecessors live in an outer sibling branch) →
 *       {@linkplain #promoteUnclaimedToParent promoted} to the parent scope,
 *       whose join sees a wider merged context;</li>
 *   <li>still not executable at the root scope → left unexecuted on purpose:
 *       its remaining predecessors are awaiting a signal (or were abandoned by
 *       a failed branch). Resume goes through {@code SignalResumeService},
 *       which re-derives readiness from the persisted DB state, independent of
 *       this in-memory registry.</li>
 * </ul>
 *
 * <h2>Claim atomicity</h2>
 *
 * <p>The claim set lives on the <b>root</b> scope and is shared by every child
 * (same {@link ConcurrentHashMap} instance), so a merge node executes at most
 * once per fork scope-tree no matter which join level - or which thread -
 * reaches it first. {@link #tryClaim} is a {@code putIfAbsent}: exactly one
 * caller wins. Note the scope-tree boundary is intentional: a back-edge (loop)
 * re-traversal builds a fresh root scope, so a merge inside a loop body
 * legitimately re-executes on the next iteration.
 *
 * <p>Thread-safe: deferred map and claim map are concurrent; branch futures
 * complete-before the join reads them (happens-before via
 * {@code CompletableFuture.get}).
 */
public final class ParallelMergeRegistry {

    private final ParallelMergeRegistry parent;

    /** Merges deferred by this scope's branches, keyed by nodeId (deduplicated). */
    private final ConcurrentHashMap<String, ExecutionNode> deferred = new ConcurrentHashMap<>();

    /** Root-shared claim set - a nodeId present here has been executed (or is being executed). */
    private final ConcurrentHashMap<String, Boolean> claimed;

    private ParallelMergeRegistry(ParallelMergeRegistry parent, ConcurrentHashMap<String, Boolean> claimed) {
        this.parent = parent;
        this.claimed = claimed;
    }

    /** Creates the root scope of a fork scope-tree (fresh claim set). */
    public static ParallelMergeRegistry root() {
        return new ParallelMergeRegistry(null, new ConcurrentHashMap<>());
    }

    /** Creates a nested scope sharing the root's claim set. */
    public ParallelMergeRegistry child() {
        return new ParallelMergeRegistry(this, claimed);
    }

    public boolean hasParent() {
        return parent != null;
    }

    /**
     * Defers a merge node for execution at this scope's join point.
     * Idempotent per nodeId; no-op if the node was already claimed
     * (executed) anywhere in this scope-tree.
     */
    public void defer(ExecutionNode mergeNode) {
        String nodeId = mergeNode.getNodeId();
        if (claimed.containsKey(nodeId)) {
            return;
        }
        deferred.putIfAbsent(nodeId, mergeNode);
    }

    /**
     * Atomically claims a merge node for execution. Returns {@code true} for
     * exactly one caller per scope-tree; every other (concurrent or later)
     * caller gets {@code false} and must NOT execute the node.
     */
    public boolean tryClaim(String nodeId) {
        return claimed.putIfAbsent(nodeId, Boolean.TRUE) == null;
    }

    public boolean isClaimed(String nodeId) {
        return claimed.containsKey(nodeId);
    }

    /**
     * Snapshot of the merges deferred in THIS scope that have not been claimed
     * yet. The join loop iterates this until it makes no more progress.
     */
    public List<ExecutionNode> unclaimedDeferred() {
        List<ExecutionNode> result = new ArrayList<>();
        for (ExecutionNode node : deferred.values()) {
            if (!claimed.containsKey(node.getNodeId())) {
                result.add(node);
            }
        }
        return result;
    }

    /**
     * Moves this scope's unclaimed deferred merges up to the parent scope
     * (whose join point sees a wider merged context) and clears this scope.
     * Must only be called on scopes with a parent.
     *
     * @return the number of merges promoted
     */
    public int promoteUnclaimedToParent() {
        if (parent == null) {
            throw new IllegalStateException("Root scope has no parent to promote to");
        }
        int promoted = 0;
        for (ExecutionNode node : deferred.values()) {
            if (!claimed.containsKey(node.getNodeId())) {
                parent.defer(node);
                promoted++;
            }
        }
        deferred.clear();
        return promoted;
    }
}
