package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.apimarketplace.orchestrator.utils.EdgeRefParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

/**
 * Handles merge node execution in the context of split.
 *
 * <p>When a merge node is reached after a split, this handler:
 * <ol>
 *   <li>Finds the active SplitContext</li>
 *   <li>Aggregates all results from upstream nodes</li>
 *   <li>Marks the SplitContext as "closed" for this scope</li>
 *   <li>Returns aggregated data for downstream nodes</li>
 * </ol>
 *
 * <p>After the merge, downstream nodes will execute ONCE (not N times)
 * because the SplitContext is no longer active for them.
 *
 * <p>Usage:
 * <pre>
 * // In MergeNode or engine:
 * if (splitMergeHandler.isSplitMerge(runId, nodeId, nodeMap)) {
 *     return splitMergeHandler.handleMerge(runId, nodeId, context, nodeMap);
 * }
 * </pre>
 */
@Service
public class SplitMergeHandler {

    private static final Logger logger = LoggerFactory.getLogger(SplitMergeHandler.class);

    private final SplitContextManager contextManager;

    /**
     * Optional durable per-item output store. Backfills {@link #aggregateResults} when the in-memory
     * {@link SplitContext#resultsByNode} is missing a node's per-item slots - the same cross-pod async/
     * signal resume, restart, or read-before-async-seal gap that {@code SplitAggregateHandler} and
     * {@code SplitAwareNodeExecutor.injectPredecessorPerItemOutputs} guard against. Null in unit tests
     * that don't exercise the fallback → warm-path behavior is unchanged.
     */
    private com.apimarketplace.orchestrator.services.StepOutputService stepOutputService;

    public SplitMergeHandler(SplitContextManager contextManager) {
        this.contextManager = contextManager;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setStepOutputService(
            com.apimarketplace.orchestrator.services.StepOutputService stepOutputService) {
        this.stepOutputService = stepOutputService;
    }

    /**
     * Checks if this merge node is merging split results.
     *
     * @param runId the workflow run ID
     * @param nodeId the merge node ID
     * @param workflowItemIndex the workflow item index (for scoping)
     * @param nodeMap map of all nodes
     * @return true if there's an active SplitContext upstream
     */
    public boolean isSplitMerge(
            String runId,
            String nodeId,
            int workflowItemIndex,
            Map<String, ExecutionNode> nodeMap) {

        if (contextManager.findActiveContext(runId, nodeId, workflowItemIndex, nodeMap).isEmpty()) {
            return false;
        }

        // Branch-rejoin merges (e.g., classify → 5 branches → merge) should execute
        // per-item inside the split, not aggregate N items into 1.
        // Only true split-aggregation merges (end of split scope) should be handled here.
        if (isBranchRejoinMerge(nodeId, nodeMap)) {
            logger.info("[SplitMerge] Merge {} is a branch-rejoin (predecessors share common ancestor), " +
                "will execute per-item instead of aggregating", nodeId);
            return false;
        }

        return true;
    }

    /**
     * Detects if a merge node is a branch-rejoin (rejoining branches from a classify/decision/fork)
     * rather than a split-aggregation (collecting N split items into 1).
     *
     * <p>A branch-rejoin merge has multiple predecessors that all share a common immediate
     * predecessor - the branching node (classify, decision, fork) that created the branches.
     * A split-aggregation merge has a single predecessor or predecessors from unrelated paths.
     *
     * <p>Example branch-rejoin: classify → [label_a, label_b, label_c] → merge
     * All three labels share "classify" as common predecessor → branch-rejoin.
     *
     * <p>Third consumer of the graph-size-bounded ancestor walk (besides the merge scoping in
     * {@link #splitSubgraphAncestors} and the warm-skip veto in
     * {@link SplitAwareNodeExecutor#inMemorySlotsComplete}): the old constant 50-node cap could
     * hide a common ancestor deeper than 50 nodes in a predecessor's closure and MISCLASSIFY a
     * branch-rejoin as a split-aggregation on a >50-node plan; the graph-size bound classifies by
     * the FULL closure, so the topologically correct answer is returned regardless of plan size.
     *
     * @param mergeNodeId the merge node ID
     * @param nodeMap map of all execution nodes
     * @return true if this is a branch-rejoin merge
     */
    static boolean isBranchRejoinMerge(String mergeNodeId, Map<String, ExecutionNode> nodeMap) {
        ExecutionNode mergeNode = nodeMap.get(mergeNodeId);
        if (mergeNode == null) return false;

        List<String> predecessors = mergeNode.getPredecessorIds();
        if (predecessors.size() <= 1) return false;

        // Walk backwards from each predecessor (BFS) to collect all transitive ancestors.
        // If all predecessors share a common ancestor, this merge rejoins branches from
        // that ancestor - it's a branch-rejoin, not a split-aggregation.
        // This handles both shallow (classify → [A, B] → merge) and deep
        // (classify → [A → X, B → Y] → merge) topologies.
        Set<String> commonAncestors = null;
        for (String predId : predecessors) {
            String lookupId = resolveBaseNodeId(predId, nodeMap);
            ExecutionNode predNode = nodeMap.get(lookupId);
            if (predNode == null) {
                logger.debug("[SplitMerge] Predecessor {} not found in nodeMap, cannot determine branch-rejoin", predId);
                return false;
            }

            Set<String> transitiveAncestors = collectTransitiveAncestors(lookupId, nodeMap);
            if (transitiveAncestors.isEmpty()) {
                // Predecessor has no ancestors (root node or unresolvable) - can't be branch-rejoin
                return false;
            }

            if (commonAncestors == null) {
                commonAncestors = new HashSet<>(transitiveAncestors);
            } else {
                commonAncestors.retainAll(transitiveAncestors);
            }

            if (commonAncestors.isEmpty()) {
                // Early exit: no common ancestors possible
                return false;
            }
        }

        if (commonAncestors == null || commonAncestors.isEmpty()) {
            return false;
        }

        // Filter out split nodes from common ancestors. If the only shared ancestor
        // is the split node itself, this is a split-aggregation (not branch-rejoin).
        // Example false positive: split → [step_a, step_b] → merge - both share "split"
        // as ancestor, but this merge should aggregate, not rejoin.
        commonAncestors.removeIf(ancestorId -> {
            ExecutionNode ancestorNode = nodeMap.get(ancestorId);
            return ancestorNode != null && ancestorNode.isSplitNode();
        });

        boolean isBranchRejoin = !commonAncestors.isEmpty();
        if (isBranchRejoin) {
            logger.debug("[SplitMerge] Branch-rejoin detected: merge={}, commonAncestors={}",
                mergeNodeId, commonAncestors);
        }
        return isBranchRejoin;
    }

    /**
     * The outcome of a bounded ancestor walk.
     *
     * @param ancestors the ancestors collected before the walk stopped
     * @param truncated {@code true} when the graph-size processing bound fired with work still
     *                  queued, i.e. {@code ancestors} is a PARTIAL view of the real ancestor set.
     *                  Unreachable on any real plan (visited-dedup + bound >= graph size, see
     *                  {@link #collectTransitiveAncestors(String, Map, Set)}), so it only signals
     *                  a genuinely pathological input. A caller that needs an authoritative answer
     *                  (every ancestor seen) MUST still treat a truncated walk as "unknown" rather
     *                  than as "no more ancestors": the difference is invisible in
     *                  {@code ancestors} alone.
     */
    record AncestorWalk(Set<String> ancestors, boolean truncated) {}

    /**
     * BFS backwards from a node to collect all transitive ancestors (excluding the node itself).
     * Cycle-safe via the visited set and bounded by the graph size (see the 3-arg overload).
     */
    private static Set<String> collectTransitiveAncestors(String startNodeId, Map<String, ExecutionNode> nodeMap) {
        return collectTransitiveAncestors(startNodeId, nodeMap, Set.of()).ancestors();
    }

    /**
     * BFS backwards from a node to collect all transitive ancestors (excluding the node itself),
     * stopping at a boundary and reporting whether the graph-size processing bound truncated the
     * result.
     *
     * <p>{@code stopNodeKeys} are treated as walls: a resolved ancestor listed there is neither
     * returned nor expanded, so the walk never escapes past it. Callers scoping to a split
     * subgraph pass the split's base node key, which keeps the result to the per-item nodes
     * BETWEEN the split and {@code startNodeId} (see
     * {@link SplitAwareNodeExecutor#inMemorySlotsComplete}). An empty set walks the whole graph.
     *
     * <p>Cycle-safe and bounded: a {@code visited} set makes each node enqueue at most once, and
     * the processing bound is the ACTUAL graph size ({@code max(50, nodeMap.size() + 1)}; 50 is a
     * floor, never a cap below the graph). Because of the visited-dedup, a walk over a real plan
     * (every referenced predecessor resolvable in {@code nodeMap}) processes at most one entry per
     * distinct node and therefore can NEVER exhaust the bound: truncation is unreachable there.
     * The bound only fires on a genuinely pathological input: predecessor lists referencing more
     * distinct UNRESOLVABLE ids than the bound's remaining slack (bound minus the resolvable
     * closure; on a tiny map that slack is the whole 50 floor). When it does fire with nodes
     * still queued, {@link AncestorWalk#truncated()} is {@code true} so callers can fail safe
     * instead of mistaking a partial set for a complete one.
     */
    static AncestorWalk collectTransitiveAncestors(String startNodeId, Map<String, ExecutionNode> nodeMap,
            Set<String> stopNodeKeys) {
        Set<String> ancestors = new HashSet<>();
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();

        if (nodeMap == null) return new AncestorWalk(ancestors, false);

        // Seed with direct predecessors of the start node
        ExecutionNode startNode = nodeMap.get(startNodeId);
        if (startNode == null) return new AncestorWalk(ancestors, false);

        for (String predId : startNode.getPredecessorIds()) {
            enqueueAncestor(predId, nodeMap, stopNodeKeys, visited, queue);
        }

        // Bound the walk by the ACTUAL graph size, floored at 50 so a tiny or partial nodeMap
        // never lowers the historical bound. The visited set already enqueues each resolved id
        // at most once, so on a real plan (every referenced predecessor resolvable in nodeMap)
        // processed can never exceed the number of distinct nodes and the bound is unreachable.
        // It only fires on a genuinely pathological input, i.e. predecessor lists referencing
        // more distinct unresolvable ids than the bound's remaining slack (bound minus the
        // resolvable closure) - a pure defensive net.
        int maxNodes = Math.max(50, nodeMap.size() + 1);
        int processed = 0;
        while (!queue.isEmpty() && processed < maxNodes) {
            String current = queue.poll();
            processed++;
            ancestors.add(current);

            ExecutionNode currentNode = nodeMap.get(current);
            if (currentNode == null) continue;

            for (String predId : currentNode.getPredecessorIds()) {
                enqueueAncestor(predId, nodeMap, stopNodeKeys, visited, queue);
            }
        }

        // Work still queued means the bound cut the walk short: the caller is holding a partial set.
        return new AncestorWalk(ancestors, !queue.isEmpty());
    }

    /**
     * Resolves {@code predId} to its base node id and enqueues it unless it is a stop boundary
     * or already visited.
     */
    private static void enqueueAncestor(String predId, Map<String, ExecutionNode> nodeMap,
            Set<String> stopNodeKeys, Set<String> visited, Queue<String> queue) {
        if (predId == null) return;
        String resolved = resolveBaseNodeId(predId, nodeMap);
        if (stopNodeKeys.contains(resolved)) return; // boundary: not an ancestor, never expanded
        if (visited.add(resolved)) {
            queue.add(resolved);
        }
    }

    /**
     * Resolves a node ID (possibly with port suffix) to its base node ID for nodeMap lookup.
     */
    static String resolveBaseNodeId(String nodeId, Map<String, ExecutionNode> nodeMap) {
        if (nodeMap.containsKey(nodeId)) return nodeId;
        EdgeRefParser.EdgeRef ref = EdgeRefParser.parse(nodeId);
        if (ref != null && ref.port() != null && !ref.port().isEmpty()) {
            return ref.nodeType() + ":" + ref.nodeLabel();
        }
        return nodeId;
    }

    /**
     * Handles merge execution for split, aggregating all results.
     *
     * @param runId the workflow run ID
     * @param nodeId the merge node ID
     * @param workflowItemIndex the workflow item index (for scoping)
     * @param context the execution context
     * @param nodeMap map of all nodes
     * @return the merge execution result with aggregated data
     */
    public NodeExecutionResult handleMerge(
            String runId,
            String nodeId,
            int workflowItemIndex,
            ExecutionContext context,
            Map<String, ExecutionNode> nodeMap) {

        Optional<SplitContext> splitContextOpt = contextManager.findActiveContext(runId, nodeId, workflowItemIndex, nodeMap);

        if (splitContextOpt.isEmpty()) {
            logger.warn("[SplitMerge] No SplitContext found for merge: nodeId={}, workflowItem={}", nodeId, workflowItemIndex);
            return createEmptyMergeResult(nodeId, context);
        }

        SplitContext splitContext = splitContextOpt.get();
        String contextKey = splitContext.splitNodeId(); // This is the scoped key (e.g., "core:split:0" or "core:split:0/s1")

        // Extract the actual splitNodeId from the context key (handles nested /sN suffix)
        String splitNodeId = SplitContextManager.extractBaseSplitNodeId(contextKey);

        logger.info("[SplitMerge] Handling split merge: nodeId={}, split={}, contextKey={}, workflowItem={}, itemCount={}",
            nodeId, splitNodeId, contextKey, workflowItemIndex, splitContext.itemCount());

        // Aggregate all results from the split context (durable-backfilled per item).
        Map<String, Object> aggregatedData = aggregateResults(splitContext, context, nodeId, nodeMap);

        // Mark the context as closed by removing it (scoped to workflow item)
        // This ensures downstream nodes don't see the split context
        contextManager.removeContext(runId, splitNodeId, workflowItemIndex);

        logger.info("[SplitMerge] Split context closed: nodeId={}, split={}, workflowItem={}", nodeId, splitNodeId, workflowItemIndex);

        // Build merge result
        Map<String, Object> output = new HashMap<>();
        output.put("node_type", "MERGE");
        output.put("split_merge", true);
        output.put("split_id", splitNodeId);
        output.put("item_count", splitContext.itemCount());
        output.put("aggregated_results", aggregatedData);
        output.put("item_index", context.itemIndex());
        output.put("item_id", context.itemId());

        // Include individual items for easy access
        output.put("items", splitContext.items());

        // Flatten the latest results to "results" key for easy access
        List<Object> latestResults = splitContext.getLatestResults();
        if (!latestResults.isEmpty()) {
            output.put("results", latestResults);
        }

        return new NodeExecutionResult(
            nodeId,
            NodeStatus.COMPLETED,
            output,
            Optional.empty(),
            Map.of("split_merge", true),
            0
        );
    }

    /**
     * Aggregates results from all nodes in the SplitContext, backfilling any per-item slot the
     * in-memory {@link SplitContext#resultsByNode} is missing from the durable step-output store.
     */
    private Map<String, Object> aggregateResults(SplitContext splitContext, ExecutionContext context,
                                                 String mergeNodeId, Map<String, ExecutionNode> nodeMap) {
        Map<String, Object> aggregated = new HashMap<>();

        Map<String, List<Object>> allResults = mergeDurablePerItem(splitContext, context, mergeNodeId, nodeMap);

        for (Map.Entry<String, List<Object>> entry : allResults.entrySet()) {
            String nodeId = entry.getKey();
            List<Object> results = entry.getValue();

            // Store results by node ID
            aggregated.put(nodeId, results);

            // Also extract and aggregate specific fields if results are maps
            aggregateFieldsFromResults(aggregated, nodeId, results);
        }

        // Add summary statistics
        aggregated.put("total_items", splitContext.itemCount());
        aggregated.put("nodes_executed", allResults.size());

        return aggregated;
    }

    /**
     * Per-node per-item results starting from the in-memory {@link SplitContext} and backfilling any
     * missing node/slot from the durable step-output store. Recovers the branch-rejoin / aggregation
     * merge from the cross-pod async/signal resume ({@code restoreContext} rebuilds items only), a
     * restart, or a read before an async barrier sealed - the same class the {@code core:aggregate}
     * node and {@code SplitAwareNodeExecutor.injectPredecessorPerItemOutputs} are already guarded for.
     *
     * <p>Skips the split node's own key. No-op (returns the in-memory view, no DB read) when no durable
     * source is wired - so existing unit tests and the warm path are byte-identical.
     */
    private Map<String, List<Object>> mergeDurablePerItem(SplitContext splitContext, ExecutionContext context,
                                                          String mergeNodeId, Map<String, ExecutionNode> nodeMap) {
        int itemCount = splitContext.itemCount();

        // Mutable item-indexed copy of the in-memory per-node results (padded to itemCount).
        Map<String, List<Object>> merged = new HashMap<>();
        for (Map.Entry<String, List<Object>> e : splitContext.getAllResults().entrySet()) {
            List<Object> padded = new ArrayList<>(e.getValue() != null ? e.getValue() : List.of());
            while (padded.size() < itemCount) padded.add(null);
            merged.put(e.getKey(), padded);
        }

        if (stepOutputService == null || context == null || itemCount <= 0 || nodeMap == null) {
            return merged;
        }

        String baseSplitKey = SplitContextManager.extractBaseSplitNodeId(splitContext.splitNodeId());

        // Backfill ONLY the merge's split-SUBGRAPH ancestors (nodes strictly between the split and
        // this merge), unioned with whatever is already in memory. This deliberately excludes the
        // split node, its own ancestors (trigger / pre-split), and unrelated sibling/post-merge epoch
        // nodes, so a durable epoch row for e.g. trigger:manual never pollutes aggregated_results or
        // inflates nodes_executed. Same intent as SplitAggregateHandler.resolvePerItemResults (bound the
        // durable fold to the split's own subgraph), reached by a different mechanism: that path scopes
        // by the node ids referenced in the aggregate's expressions, this one by topological
        // split-subgraph ancestry.
        Set<String> eligible = new HashSet<>(merged.keySet());
        eligible.addAll(splitSubgraphAncestors(mergeNodeId, baseSplitKey, nodeMap));
        eligible.remove(baseSplitKey);
        if (eligible.isEmpty()) {
            return merged;
        }

        // Warm path: every eligible node already present with a non-null slot for every item → no DB read.
        // (An entirely-absent node or a null slot is the cross-pod / restart / async-not-sealed gap we recover.)
        boolean anyMissing = false;
        for (String nid : eligible) {
            List<Object> slots = merged.get(nid);
            if (slots == null || slots.size() < itemCount) {
                anyMissing = true;
                break;
            }
            for (int i = 0; i < itemCount; i++) {
                if (slots.get(i) == null) {
                    anyMissing = true;
                    break;
                }
            }
            if (anyMissing) break;
        }
        if (!anyMissing) {
            return merged; // warm - no durable read
        }

        Map<String, Map<Integer, Object>> durable;
        try {
            durable = stepOutputService.loadPerItemOutputsByStepKey(
                context.runId(), context.epoch(), context.tenantId());
        } catch (Exception ex) {
            logger.warn("[SplitMerge] Durable per-item backfill failed (run={}, epoch={}): {} - using in-memory only",
                context.runId(), context.epoch(), ex.getMessage());
            return merged;
        }
        if (durable == null || durable.isEmpty()) {
            return merged;
        }

        int recovered = 0;
        for (String nid : eligible) {
            Map<Integer, Object> byItem = durable.get(nid);
            if (byItem == null || byItem.isEmpty()) {
                continue;
            }
            List<Object> slots = merged.get(nid);
            if (slots == null) {
                slots = new ArrayList<>(itemCount);
                for (int i = 0; i < itemCount; i++) slots.add(null);
                merged.put(nid, slots);
            }
            while (slots.size() < itemCount) slots.add(null);
            for (int i = 0; i < itemCount; i++) {
                if (slots.get(i) == null) {
                    Object durVal = byItem.get(i);
                    if (durVal != null) {
                        slots.set(i, durVal);
                        recovered++;
                    }
                }
            }
        }
        if (recovered > 0) {
            logger.info("[SplitMerge] Recovered {} per-item slot(s) from the durable store for merge aggregation "
                    + "(in-memory SplitContext incomplete): split={}, run={}, epoch={}",
                recovered, baseSplitKey, context.runId(), context.epoch());
        }
        return merged;
    }

    /**
     * The merge's split-SUBGRAPH ancestors: nodes that are transitive ancestors of the merge but NOT
     * the split node nor any ancestor of the split (trigger / pre-split). Computed by subtracting the
     * split's ancestor closure (plus the split itself) from the merge's, reusing the same audited BFS
     * as {@link #isBranchRejoinMerge}. Empty when the nodeMap can't resolve the topology.
     *
     * <p><b>Walk truncation is unreachable on any real plan</b>: the walk is bounded by the actual
     * graph size ({@code max(50, nodeMap.size() + 1)}) and the visited-dedup processes each
     * distinct node at most once, so a plan whose predecessors all resolve in {@code nodeMap} can
     * never exhaust the bound (see {@link #collectTransitiveAncestors(String, Map, Set)}). The
     * truncated flag, the WARN below, and the fail-open in
     * {@link SplitAwareNodeExecutor#inMemorySlotsComplete} remain as pure defensive nets for a
     * genuinely pathological input (a graph referencing more distinct unresolvable ids than the
     * bound's remaining slack).
     *
     * <p><b>If that net ever fires, truncation is still NOT fail-open here, unlike
     * {@code inMemorySlotsComplete}, because the two walks fail in OPPOSITE directions and only
     * one of them can drop data:</b>
     * <ul>
     *   <li>The SPLIT walk ({@code splitAndAbove}) truncating is already safe: a short subtrahend
     *       removes FEWER nodes, so {@code eligible} grows. The consequence is the pollution this
     *       method exists to avoid (a pre-split node such as {@code trigger:manual} reaching
     *       aggregated_results / nodes_executed), never a dropped per-item value.</li>
     *   <li>The MERGE walk truncating is the real hazard: a short ancestor set makes
     *       {@code eligible} too small, so a per-item node beyond the bound is neither counted by
     *       the warm check nor backfilled, and the aggregate silently loses its values. This is
     *       logged at WARN so the condition is observable rather than invisible.</li>
     * </ul>
     *
     * <p>The residual pathological case is NOT auto-corrected because every available correction
     * trades that (now unreachable-on-real-plans) drop for a worse or wider change: unioning the
     * durable key universe would pour EVERY epoch node (pre-split, sibling, post-merge) into
     * aggregated_results, which is precisely the pollution the scoping was built to prevent; and
     * walling the merge walk at the split would change which nodes qualify in a rare topology (a
     * pre-split node with an edge directly into the split body is currently subtracted, and a
     * walled walk would keep it). Either is a merge-path behaviour change that belongs in its own
     * audited fix.
     */
    private Set<String> splitSubgraphAncestors(String mergeNodeId, String baseSplitKey,
                                               Map<String, ExecutionNode> nodeMap) {
        AncestorWalk mergeWalk = collectTransitiveAncestors(mergeNodeId, nodeMap, Set.of());
        Set<String> mergeAncestors = mergeWalk.ancestors();
        if (mergeAncestors.isEmpty()) {
            return Set.of();
        }
        Set<String> splitAndAbove = collectTransitiveAncestors(baseSplitKey, nodeMap, Set.of()).ancestors();
        splitAndAbove.add(baseSplitKey);
        mergeAncestors.removeAll(splitAndAbove);
        if (mergeWalk.truncated()) {
            logger.warn("[SplitMerge] Ancestor walk hit the graph-size bound for merge={} (split={}), "
                    + "which no real plan can do (pathological graph): the split-subgraph scope is "
                    + "PARTIAL, so a per-item node beyond the bound may be excluded from the durable "
                    + "backfill and lost from the aggregate",
                mergeNodeId, baseSplitKey);
        }
        return mergeAncestors;
    }

    /**
     * Extracts common fields from result maps and aggregates them.
     */
    @SuppressWarnings("unchecked")
    private void aggregateFieldsFromResults(
            Map<String, Object> aggregated,
            String nodeId,
            List<Object> results) {

        // If all results are maps, extract common fields
        List<Map<String, Object>> mapResults = results.stream()
            .filter(r -> r instanceof Map)
            .map(r -> (Map<String, Object>) r)
            .toList();

        if (mapResults.isEmpty() || mapResults.size() != results.size()) {
            return; // Not all results are maps
        }

        Map<String, Object> firstResult = mapResults.get(0);
        for (String key : firstResult.keySet()) {
            // Check if all results have this key
            boolean allHaveKey = mapResults.stream().allMatch(m -> m.containsKey(key));
            if (allHaveKey) {
                // Aggregate values for this key
                List<Object> values = mapResults.stream()
                    .map(m -> m.get(key))
                    .toList();
                aggregated.put(nodeId + "." + key, values);
            }
        }
    }

    /**
     * Creates an empty merge result when no SplitContext is found.
     */
    private NodeExecutionResult createEmptyMergeResult(String nodeId, ExecutionContext context) {
        Map<String, Object> output = new HashMap<>();
        output.put("node_type", "MERGE");
        output.put("split_merge", false);
        output.put("item_count", 0);
        output.put("item_index", context.itemIndex());
        output.put("item_id", context.itemId());

        return new NodeExecutionResult(
            nodeId,
            NodeStatus.COMPLETED,
            output,
            Optional.empty(),
            Map.of(),
            0
        );
    }

    /**
     * Gets the SplitContext for a specific split node.
     *
     * @param runId the workflow run ID
     * @param splitNodeId the split node ID
     * @param workflowItemIndex the workflow item index (from trigger)
     * @return the SplitContext if found
     */
    public Optional<SplitContext> getSplitContext(String runId, String splitNodeId, int workflowItemIndex) {
        return contextManager.getContext(runId, splitNodeId, workflowItemIndex);
    }
}
