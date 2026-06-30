package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.apimarketplace.orchestrator.utils.EdgeRefParser;

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

    public SplitMergeHandler(SplitContextManager contextManager) {
        this.contextManager = contextManager;
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
     * BFS backwards from a node to collect all transitive ancestors (excluding the node itself).
     * Stops at a max depth to prevent infinite loops in cyclic graphs.
     */
    private static Set<String> collectTransitiveAncestors(String startNodeId, Map<String, ExecutionNode> nodeMap) {
        Set<String> ancestors = new HashSet<>();
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();

        // Seed with direct predecessors of the start node
        ExecutionNode startNode = nodeMap.get(startNodeId);
        if (startNode == null) return ancestors;

        for (String predId : startNode.getPredecessorIds()) {
            String resolved = resolveBaseNodeId(predId, nodeMap);
            if (!visited.contains(resolved)) {
                queue.add(resolved);
                visited.add(resolved);
            }
        }

        int maxNodes = 50; // safety limit on nodes processed
        int processed = 0;
        while (!queue.isEmpty() && processed < maxNodes) {
            String current = queue.poll();
            processed++;
            ancestors.add(current);

            ExecutionNode currentNode = nodeMap.get(current);
            if (currentNode == null) continue;

            for (String predId : currentNode.getPredecessorIds()) {
                String resolved = resolveBaseNodeId(predId, nodeMap);
                if (!visited.contains(resolved)) {
                    queue.add(resolved);
                    visited.add(resolved);
                }
            }
        }

        return ancestors;
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

        // Aggregate all results from the split context
        Map<String, Object> aggregatedData = aggregateResults(splitContext);

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
     * Aggregates results from all nodes in the SplitContext.
     */
    private Map<String, Object> aggregateResults(SplitContext splitContext) {
        Map<String, Object> aggregated = new HashMap<>();

        Map<String, List<Object>> allResults = splitContext.getAllResults();

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
