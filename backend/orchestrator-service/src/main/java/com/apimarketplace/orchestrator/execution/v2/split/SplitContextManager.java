package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages SplitContext instances per workflow run and workflow item.
 *
 * <p>Domain: CONTROL_FLOW - Handles parallel split execution contexts.
 *
 * <p>Context keys are scoped by workflow item index to ensure isolation:
 * - Workflow item 0's split context is separate from item 1's
 * - Merge waits only for sub-items of the same workflow item
 *
 * <p>Key format: splitNodeId:workflowItemIndex (single-level)
 * or splitNodeId:workflowItemIndex/sN (nested, where N is parent item index)
 * Example: "core:processmessages:0", "core:inner_loop:0/s1"
 *
 * <p>Nested split support: When a split runs inside another split scope,
 * each invocation of the inner split (one per parent item) gets a unique
 * context key by appending the parent scope suffix. This prevents overwrites
 * when the same inner split node creates contexts for different parent items.
 *
 * <p>Thread-safe: Uses ConcurrentHashMap for concurrent access.
 *
 * @see RunScopedCache
 */
@Service
public class SplitContextManager implements RunScopedCache {

    private static final Logger logger = LoggerFactory.getLogger(SplitContextManager.class);

    /**
     * Keys that {@code SignalContextResolver.buildSplitItemData} adds to a signal's
     * {@code split_item_data} blob PURELY so {@link #restoreContext} can rebuild the split
     * scope on another pod (cross-instance resume at {@code replicas>=2}). They are NOT
     * display data and MUST be stripped before exposing {@code split_item_data} as
     * {@code itemContext} to the frontend (see {@link #toDisplayItemContext}); otherwise the
     * approver preview shows an internal split node id and the full items list bloats every
     * REST + WS signals payload (O(N^2) over the wire).
     */
    public static final Set<String> RESTORATION_KEYS =
        Set.of("splitNodeId", "items", "itemIndex", "workflowItemIndex");

    /**
     * Project a persisted {@code split_item_data} blob to the DISPLAY-only view the run
     * inspector consumes as {@code itemContext}: drops the {@link #RESTORATION_KEYS},
     * keeping only the per-item display fields ({@code current_item} / {@code current_index}).
     * Returns the input unchanged when null/empty. This is what keeps the approver preview
     * correct and the signals payload small after the cross-pod restore fields were added.
     */
    public static Map<String, Object> toDisplayItemContext(Map<String, Object> splitItemData) {
        if (splitItemData == null || splitItemData.isEmpty()) {
            return splitItemData;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : splitItemData.entrySet()) {
            if (!RESTORATION_KEYS.contains(e.getKey())) {
                view.put(e.getKey(), e.getValue());
            }
        }
        return view;
    }

    /**
     * Contexts stored per run: runId or runId:epoch -> (contextKey -> SplitContext)
     * contextKey = splitNodeId:workflowItemIndex or splitNodeId:workflowItemIndex/sN
     *
     * For parallel epochs, the key is "runId:epoch" to isolate split contexts per epoch.
     * Legacy callers that don't pass epoch use "runId" directly.
     */
    private final Map<String, Map<String, SplitContext>> contextsByRun = new ConcurrentHashMap<>();

    /**
     * Builds a context key scoped to the workflow item.
     *
     * @param splitNodeId the split node ID (e.g., "core:processmessages")
     * @param workflowItemIndex the workflow item index (from trigger)
     * @return the scoped key (e.g., "core:processmessages:0")
     */
    public static String buildContextKey(String splitNodeId, int workflowItemIndex) {
        return splitNodeId + ":" + workflowItemIndex;
    }

    /**
     * Builds a context key scoped to the workflow item AND parent split scope.
     * Used for nested splits to ensure each inner split invocation has a unique key.
     *
     * @param splitNodeId the split node ID (e.g., "core:inner_loop")
     * @param workflowItemIndex the workflow item index (from trigger)
     * @param parentScopeKey the parent split scope suffix (e.g., "s0", "s1"), or null for top-level splits
     * @return the scoped key (e.g., "core:inner_loop:0/s0" or "core:inner_loop:0" if no parent)
     */
    public static String buildContextKey(String splitNodeId, int workflowItemIndex, String parentScopeKey) {
        String baseKey = splitNodeId + ":" + workflowItemIndex;
        if (parentScopeKey != null && !parentScopeKey.isEmpty()) {
            return baseKey + "/" + parentScopeKey;
        }
        return baseKey;
    }

    /**
     * Extracts the parent split scope key from an ExecutionContext.
     * If the context is inside a parent split, returns "sN" where N is the parent's current item index.
     * Returns null if not inside a parent split scope.
     *
     * @param context the execution context (may contain parent split info in global data)
     * @return the parent scope key (e.g., "s0", "s1") or null
     */
    public static String extractParentScopeKey(ExecutionContext context) {
        if (context == null) {
            return null;
        }
        Optional<Object> currentSplitId = context.getGlobalData("current_split_id");
        if (currentSplitId.isEmpty()) {
            return null;
        }
        Optional<Object> parentIndex = context.getGlobalData("index");
        if (parentIndex.isEmpty()) {
            return null;
        }
        Object idx = parentIndex.get();
        int parentItemIndex = idx instanceof Number ? ((Number) idx).intValue() : 0;
        return "s" + parentItemIndex;
    }

    /**
     * Extracts the base split node ID from a scoped context key.
     * Handles both simple keys ("core:processmessages:0" -> "core:processmessages")
     * and nested keys ("core:inner_loop:0/s1" -> "core:inner_loop").
     *
     * @param scopedKey the full scoped context key
     * @return the base split node ID without workflow item index or scope suffix
     */
    public static String extractBaseSplitNodeId(String scopedKey) {
        if (scopedKey == null) {
            return scopedKey;
        }
        // Strip /sN suffix first if present
        String stripped = scopedKey;
        int slashIdx = stripped.indexOf('/');
        if (slashIdx > 0) {
            stripped = stripped.substring(0, slashIdx);
        }
        // Now extract base node ID by removing the last :N (workflow item index)
        int lastColonIndex = stripped.lastIndexOf(':');
        if (lastColonIndex > 0) {
            String potentialIndex = stripped.substring(lastColonIndex + 1);
            try {
                Integer.parseInt(potentialIndex);
                return stripped.substring(0, lastColonIndex);
            } catch (NumberFormatException e) {
                // Not a numeric suffix, return as-is
            }
        }
        return stripped;
    }

    /**
     * Extracts the parent scope suffix from a scoped context key.
     * For "core:inner_loop:0/s1", returns "s1".
     * For "core:processmessages:0", returns null.
     *
     * @param scopedKey the full scoped context key
     * @return the scope suffix (e.g., "s1") or null if not a nested key
     */
    public static String extractScopeSuffix(String scopedKey) {
        if (scopedKey == null) {
            return null;
        }
        int slashIdx = scopedKey.indexOf('/');
        if (slashIdx > 0 && slashIdx < scopedKey.length() - 1) {
            return scopedKey.substring(slashIdx + 1);
        }
        return null;
    }

    /**
     * Build the run-level key for contextsByRun, optionally epoch-scoped.
     */
    private static String buildRunKey(String runId, int epoch) {
        return runId + ":" + epoch;
    }

    /**
     * Creates a new SplitContext for the given split node and workflow item.
     *
     * @param runId the workflow run ID
     * @param splitNodeId the split node ID
     * @param workflowItemIndex the workflow item index (from trigger)
     * @param items the list of items to iterate over (spawned by split)
     * @return the created SplitContext
     */
    public SplitContext createContext(String runId, String splitNodeId, int workflowItemIndex, List<Object> items) {
        return createContext(runId, splitNodeId, workflowItemIndex, null, items);
    }

    /**
     * Creates a new epoch-scoped SplitContext for the given split node and workflow item.
     *
     * @param runId the workflow run ID
     * @param epoch the epoch for isolation
     * @param splitNodeId the split node ID
     * @param workflowItemIndex the workflow item index (from trigger)
     * @param parentScopeKey the parent split scope suffix (e.g., "s0"), or null for top-level
     * @param items the list of items to iterate over (spawned by split)
     * @return the created SplitContext
     */
    public SplitContext createContext(String runId, int epoch, String splitNodeId, int workflowItemIndex,
                                     String parentScopeKey, List<Object> items) {
        String runKey = buildRunKey(runId, epoch);
        String contextKey = buildContextKey(splitNodeId, workflowItemIndex, parentScopeKey);
        SplitContext context = SplitContext.create(contextKey, items);

        contextsByRun
            .computeIfAbsent(runKey, k -> new ConcurrentHashMap<>())
            .put(contextKey, context);

        // Also store under runId for backward compat
        contextsByRun
            .computeIfAbsent(runId, k -> new ConcurrentHashMap<>())
            .put(contextKey, context);

        logger.info("[SplitContextManager] Created epoch-scoped context: runId={}, epoch={}, key={}, itemCount={}, parentScope={}",
            runId, epoch, contextKey, context.itemCount(), parentScopeKey);

        return context;
    }

    /**
     * Creates a new SplitContext for the given split node and workflow item,
     * with optional parent scope key for nested splits.
     *
     * @param runId the workflow run ID
     * @param splitNodeId the split node ID
     * @param workflowItemIndex the workflow item index (from trigger)
     * @param parentScopeKey the parent split scope suffix (e.g., "s0"), or null for top-level
     * @param items the list of items to iterate over (spawned by split)
     * @return the created SplitContext
     */
    public SplitContext createContext(String runId, String splitNodeId, int workflowItemIndex,
                                     String parentScopeKey, List<Object> items) {
        String contextKey = buildContextKey(splitNodeId, workflowItemIndex, parentScopeKey);
        SplitContext context = SplitContext.create(contextKey, items);

        contextsByRun
            .computeIfAbsent(runId, k -> new ConcurrentHashMap<>())
            .put(contextKey, context);

        logger.info("[SplitContextManager] Created context: runId={}, key={}, itemCount={}, parentScope={}",
            runId, contextKey, context.itemCount(), parentScopeKey);

        return context;
    }

    /**
     * Legacy method for backward compatibility - uses itemIndex 0.
     */
    public SplitContext createContext(String runId, String splitNodeId, List<Object> items) {
        return createContext(runId, splitNodeId, 0, items);
    }

    /**
     * Gets an existing SplitContext by node ID and workflow item index.
     *
     * @param runId the workflow run ID
     * @param splitNodeId the split node ID
     * @param workflowItemIndex the workflow item index
     * @return the context, or empty if not found
     */
    public Optional<SplitContext> getContext(String runId, String splitNodeId, int workflowItemIndex) {
        return getContext(runId, splitNodeId, workflowItemIndex, (String) null);
    }

    /**
     * Gets an existing SplitContext by node ID, workflow item index, and parent scope key.
     *
     * @param runId the workflow run ID
     * @param splitNodeId the split node ID
     * @param workflowItemIndex the workflow item index
     * @param parentScopeKey the parent split scope suffix (e.g., "s0"), or null
     * @return the context, or empty if not found
     */
    public Optional<SplitContext> getContext(String runId, String splitNodeId, int workflowItemIndex,
                                            String parentScopeKey) {
        String contextKey = buildContextKey(splitNodeId, workflowItemIndex, parentScopeKey);
        Map<String, SplitContext> runContexts = contextsByRun.get(runId);
        if (runContexts == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(runContexts.get(contextKey));
    }

    /**
     * Gets an existing epoch-scoped SplitContext.
     *
     * @param runId the workflow run ID
     * @param epoch the epoch
     * @param splitNodeId the split node ID
     * @param workflowItemIndex the workflow item index
     * @param parentScopeKey the parent split scope suffix, or null
     * @return the context, or empty if not found
     */
    public Optional<SplitContext> getContext(String runId, int epoch, String splitNodeId,
                                            int workflowItemIndex, String parentScopeKey) {
        String runKey = buildRunKey(runId, epoch);
        String contextKey = buildContextKey(splitNodeId, workflowItemIndex, parentScopeKey);
        Map<String, SplitContext> runContexts = contextsByRun.get(runKey);
        if (runContexts == null) {
            // Fall back to run-level
            runContexts = contextsByRun.get(runId);
        }
        if (runContexts == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(runContexts.get(contextKey));
    }

    /**
     * Legacy method - uses itemIndex 0.
     */
    public Optional<SplitContext> getContext(String runId, String splitNodeId) {
        return getContext(runId, splitNodeId, 0);
    }

    /**
     * Updates a SplitContext with new results.
     *
     * @param runId the workflow run ID
     * @param splitNodeId the split node ID that owns the context
     * @param workflowItemIndex the workflow item index
     * @param executingNodeId the node that produced results
     * @param results the results to store
     * @return the updated context, or empty if not found
     */
    public Optional<SplitContext> storeResults(
            String runId,
            String splitNodeId,
            int workflowItemIndex,
            String executingNodeId,
            List<Object> results) {

        // Try the exact key first, then try to find a scoped key that matches
        String contextKey = buildContextKey(splitNodeId, workflowItemIndex);
        Map<String, SplitContext> runContexts = contextsByRun.get(runId);
        if (runContexts == null) {
            logger.warn("[SplitContextManager] No contexts found for run: runId={}", runId);
            return Optional.empty();
        }

        SplitContext existing = runContexts.get(contextKey);
        if (existing == null) {
            // Try to find a scoped key (for nested splits, key might have /sN suffix)
            String matchedKey = findScopedKey(runContexts, contextKey);
            if (matchedKey != null) {
                existing = runContexts.get(matchedKey);
                contextKey = matchedKey;
            }
        }

        if (existing == null) {
            logger.warn("[SplitContextManager] Context not found: runId={}, key={}",
                runId, contextKey);
            return Optional.empty();
        }

        // compute() (vs put) so a concurrent storeItemResult on the same contextKey can't be
        // clobbered by an interleaved batch storeResults that snapshotted a stale list.
        // Returning null when `current == null` (the entry was concurrently removed by a
        // cleanup) honors the removal instead of reviving the context - matches the behavior
        // of storeItemResultByScopedKey below.
        final String finalKey = contextKey;
        SplitContext updated = runContexts.compute(finalKey, (k, current) -> {
            if (current == null) {
                return null;
            }
            return current.withResults(executingNodeId, results);
        });

        if (updated == null) {
            logger.debug("[SplitContextManager] Context concurrently removed during storeResults: runId={}, key={}",
                runId, contextKey);
            return Optional.empty();
        }

        logger.debug("[SplitContextManager] Stored results: runId={}, key={}, node={}, resultCount={}",
            runId, contextKey, executingNodeId, results.size());

        return Optional.ofNullable(updated);
    }

    /**
     * Legacy method - uses itemIndex 0.
     */
    public Optional<SplitContext> storeResults(
            String runId,
            String splitNodeId,
            String executingNodeId,
            List<Object> results) {
        return storeResults(runId, splitNodeId, 0, executingNodeId, results);
    }

    /**
     * Atomically stores a single per-item result into the SplitContext for an executing node.
     *
     * <p>Used by chained downstream nodes inside a parallel split traversal (auto mode):
     * each item executes in its own traversal, calls this method with its own
     * {@code itemIndex}, and slots its result without clobbering siblings. Without this,
     * a downstream {@link SplitAggregateHandler} reading {@code splitContext.getAllResults()}
     * sees only the immediate-successor's per-item results - chained nodes resolve to a
     * single (last-wins) value, producing the same value N times in the aggregate output.
     *
     * <p>Concurrency: uses {@link Map#compute} on the inner per-run map so two parallel
     * item traversals storing into the same {@code executingNodeId} list don't race.
     *
     * @param runId the workflow run ID
     * @param splitNodeId the split node ID that owns the context
     * @param workflowItemIndex the workflow item index
     * @param executingNodeId the node that produced this per-item result
     * @param itemIndex the sub-item index within the split (0..totalItems-1)
     * @param totalItems the split's total item count (pre-sizes the list)
     * @param result the per-item output to store
     * @return the updated context, or empty if not found
     */
    public Optional<SplitContext> storeItemResult(
            String runId,
            String splitNodeId,
            int workflowItemIndex,
            String executingNodeId,
            int itemIndex,
            int totalItems,
            Object result) {

        Map<String, SplitContext> runContexts = contextsByRun.get(runId);
        if (runContexts == null) {
            return Optional.empty();
        }

        String contextKey = buildContextKey(splitNodeId, workflowItemIndex);
        if (!runContexts.containsKey(contextKey)) {
            String matchedKey = findScopedKey(runContexts, contextKey);
            if (matchedKey == null) {
                return Optional.empty();
            }
            contextKey = matchedKey;
        }

        return storeItemResultByScopedKey(runId, contextKey, executingNodeId, itemIndex, totalItems, result);
    }

    /**
     * Atomically stores a single per-item result using the EXACT scoped context key
     * ({@code splitNodeId:workflowItemIndex} or {@code splitNodeId:workflowItemIndex/sN}).
     *
     * <p>Prefer this overload over {@link #storeItemResult(String, String, int, String, int, int, Object)}
     * when the caller already holds the resolved {@link SplitContext#splitNodeId()}: it bypasses
     * {@code findScopedKey}'s first-match scan, which is non-deterministic when several inner-scope
     * contexts share the same workflow item (nested splits with sibling {@code /sN} keys).
     *
     * @param runId the workflow run ID
     * @param scopedKey the exact context key (must match a key already in the per-run map)
     * @param executingNodeId the node that produced this per-item result
     * @param itemIndex the sub-item index within the split
     * @param totalItems the split's total item count (pre-sizes the list)
     * @param result the per-item output to store
     * @return the updated context, or empty if the scoped key is not registered
     */
    public Optional<SplitContext> storeItemResultByScopedKey(
            String runId,
            String scopedKey,
            String executingNodeId,
            int itemIndex,
            int totalItems,
            Object result) {

        Map<String, SplitContext> runContexts = contextsByRun.get(runId);
        if (runContexts == null || !runContexts.containsKey(scopedKey)) {
            return Optional.empty();
        }

        SplitContext updated = runContexts.compute(scopedKey, (k, existing) -> {
            if (existing == null) {
                return null;
            }
            return existing.withResultAtIndex(executingNodeId, itemIndex, totalItems, result);
        });

        if (updated != null) {
            logger.debug("[SplitContextManager] Stored item result: runId={}, key={}, node={}, itemIndex={}/{}",
                runId, scopedKey, executingNodeId, itemIndex, totalItems);
        }
        return Optional.ofNullable(updated);
    }

    /**
     * Finds the active SplitContext for a given node by traversing predecessors.
     * Scoped to the specified workflow item index.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Start from the given nodeId</li>
     *   <li>BFS traverse predecessors</li>
     *   <li>If a predecessor is a split node with an active context for this item, return it</li>
     *   <li>If a predecessor is a MERGE node, stop (exits split scope)</li>
     *   <li>Continue until no more predecessors</li>
     * </ol>
     *
     * @param runId the workflow run ID
     * @param nodeId the node to find context for
     * @param workflowItemIndex the workflow item index
     * @param nodeMap map of nodeId to ExecutionNode (for traversing predecessors)
     * @return the active SplitContext, or empty if not in a split scope
     */
    public Optional<SplitContext> findActiveContext(
            String runId,
            String nodeId,
            int workflowItemIndex,
            Map<String, ExecutionNode> nodeMap) {
        return findActiveContext(runId, nodeId, workflowItemIndex, nodeMap, (String) null);
    }

    /**
     * Finds the active SplitContext for a given node by traversing predecessors.
     * Scoped to the specified workflow item index and optional parent scope.
     *
     * <p>For nested splits, the parentScopeKey disambiguates between inner split contexts
     * created for different parent items. For example, with outer split [A, B] and inner
     * split [1, 2, 3], the inner split for item A has parentScopeKey="s0" and for item B
     * has parentScopeKey="s1".
     *
     * @param runId the workflow run ID
     * @param nodeId the node to find context for
     * @param workflowItemIndex the workflow item index
     * @param nodeMap map of nodeId to ExecutionNode (for traversing predecessors)
     * @param parentScopeKey the parent split scope key (e.g., "s0"), or null for top-level
     * @return the active SplitContext, or empty if not in a split scope
     */
    public Optional<SplitContext> findActiveContext(
            String runId,
            String nodeId,
            int workflowItemIndex,
            Map<String, ExecutionNode> nodeMap,
            String parentScopeKey) {

        Map<String, SplitContext> runContexts = contextsByRun.get(runId);
        if (runContexts == null || runContexts.isEmpty()) {
            logger.debug("[SplitContextManager] findActiveContext: No contexts for runId={}, nodeId={}", runId, nodeId);
            return Optional.empty();
        }

        logger.debug("[SplitContextManager] findActiveContext: runId={}, nodeId={}, workflowItemIndex={}, parentScope={}, contextKeys={}, nodeMapSize={}",
            runId, nodeId, workflowItemIndex, parentScopeKey, runContexts.keySet(), nodeMap.size());

        // BFS to find split ancestor
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(nodeId);

        // Track if this is the starting node (we don't stop at MERGE for the starting node itself)
        boolean isStartingNode = true;

        // Log initial predecessors for debugging
        ExecutionNode startNode = nodeMap.get(nodeId);
        if (startNode != null) {
            logger.debug("[SplitContextManager] BFS starting: nodeId={}, predecessors={}",
                nodeId, startNode.getPredecessorIds());
        }

        while (!queue.isEmpty()) {
            String currentId = queue.poll();
            if (visited.contains(currentId)) {
                continue;
            }
            visited.add(currentId);

            // Handle node IDs with ports (e.g., "agent:classifyemail:category_promotions")
            // Need to extract base node ID for nodeMap lookup
            String lookupId = currentId;
            EdgeRefParser.EdgeRef ref = EdgeRefParser.parse(currentId);
            if (ref != null && ref.port() != null && !ref.port().isEmpty()) {
                // This is a node ID with a port, extract the base node ID
                lookupId = ref.nodeType() + ":" + ref.nodeLabel();
                logger.debug("[SplitContextManager] BFS: Extracted base nodeId={} from predecessorId={}", lookupId, currentId);
            }

            ExecutionNode currentNode = nodeMap.get(lookupId);
            if (currentNode == null) {
                logger.debug("[SplitContextManager] BFS: currentId={} (lookupId={}) not found in nodeMap", currentId, lookupId);
                continue;
            }

            logger.debug("[SplitContextManager] BFS: currentId={} (lookupId={}), type={}", currentId, lookupId, currentNode.getType());

            // Check if this is a split node with an active context for this workflow item
            if (currentNode.isSplitNode()) {
                // Use lookupId (without port) for context key since that's how it was stored
                // Try with parentScopeKey first (for nested splits), then without (for top-level)
                SplitContext context = null;
                String matchedKey = null;

                if (parentScopeKey != null && !parentScopeKey.isEmpty()) {
                    matchedKey = buildContextKey(lookupId, workflowItemIndex, parentScopeKey);
                    context = runContexts.get(matchedKey);
                }

                if (context == null) {
                    // Try without parent scope (backward-compatible for single-level splits)
                    matchedKey = buildContextKey(lookupId, workflowItemIndex);
                    context = runContexts.get(matchedKey);
                }

                if (context == null) {
                    // Try to find any scoped key matching this split node (fallback for nested splits)
                    String baseKey = buildContextKey(lookupId, workflowItemIndex);
                    String foundKey = findScopedKey(runContexts, baseKey);
                    if (foundKey != null) {
                        context = runContexts.get(foundKey);
                        matchedKey = foundKey;
                    }
                }

                if (context != null) {
                    logger.debug("[SplitContextManager] Found active context: node={}, key={}",
                        nodeId, matchedKey);
                    return Optional.of(context);
                }
            }

            // If this is an AGGREGATE node (and not the starting node), stop traversal (exits split scope).
            // Aggregate nodes close the split context: nodes downstream are NOT in split scope.
            if (!isStartingNode && currentNode.isAggregateNode()) {
                logger.debug("[SplitContextManager] Hit aggregate node, stopping traversal: node={}, boundary={}",
                    nodeId, lookupId);
                return Optional.empty();
            }

            // If this is a MERGE node (and not the starting node), check if it's a split-aggregation
            // merge or a branch-rejoin merge. Branch-rejoin merges (e.g., fork → [A, B] → merge)
            // are transparent in split scope - the split context continues through them.
            // Only split-aggregation merges (collecting N split items → 1) close the split scope.
            if (!isStartingNode && currentNode.isMergeNode()) {
                boolean branchRejoin = SplitMergeHandler.isBranchRejoinMerge(lookupId, nodeMap);
                if (!branchRejoin) {
                    logger.debug("[SplitContextManager] Hit split-aggregation merge, stopping traversal: node={}, boundary={}",
                        nodeId, lookupId);
                    return Optional.empty();
                }
                logger.debug("[SplitContextManager] Branch-rejoin merge, continuing traversal: node={}, merge={}",
                    nodeId, lookupId);
            }

            isStartingNode = false;

            // Add predecessors to queue (now available via ExecutionNode interface)
            List<String> predecessors = currentNode.getPredecessorIds();
            logger.debug("[SplitContextManager] BFS: currentId={} has predecessors={}", currentId, predecessors);
            queue.addAll(predecessors);
        }

        logger.debug("[SplitContextManager] findActiveContext: No split context found for nodeId={}, visited={}", nodeId, visited);
        return Optional.empty();
    }

    /**
     * Finds a scoped context key that matches a base key prefix.
     * Used for nested splits where keys have a /sN suffix.
     *
     * @param runContexts the contexts for this run
     * @param baseKey the base key to search for (e.g., "core:inner_loop:0")
     * @return the matching key with scope suffix, or null if not found
     */
    private String findScopedKey(Map<String, SplitContext> runContexts, String baseKey) {
        for (String key : runContexts.keySet()) {
            if (key.startsWith(baseKey + "/")) {
                return key;
            }
        }
        return null;
    }

    /**
     * Legacy method - uses itemIndex 0.
     */
    public Optional<SplitContext> findActiveContext(
            String runId,
            String nodeId,
            Map<String, ExecutionNode> nodeMap) {
        return findActiveContext(runId, nodeId, 0, nodeMap);
    }

    /**
     * Checks if a node is within an active split scope for the given workflow item.
     */
    public boolean isInSplitScope(String runId, String nodeId, int workflowItemIndex, Map<String, ExecutionNode> nodeMap) {
        return findActiveContext(runId, nodeId, workflowItemIndex, nodeMap).isPresent();
    }

    /**
     * Legacy method - uses itemIndex 0.
     */
    public boolean isInSplitScope(String runId, String nodeId, Map<String, ExecutionNode> nodeMap) {
        return isInSplitScope(runId, nodeId, 0, nodeMap);
    }

    /**
     * Restore split context from persisted signal data after restart.
     * Called by SignalResumeService before resuming execution of a signal
     * that was registered inside a split scope.
     *
     * @param runId the workflow run ID
     * @param nodeId the node ID that had the signal (for logging)
     * @param splitItemData persisted split context data from workflow_signal_waits.split_item_data
     */
    @SuppressWarnings("unchecked")
    public void restoreContext(String runId, String nodeId, Map<String, Object> splitItemData) {
        if (splitItemData == null || splitItemData.isEmpty()) {
            return;
        }

        String splitNodeId = (String) splitItemData.get("splitNodeId");
        List<Object> items = (List<Object>) splitItemData.get("items");
        // The context key is scoped to the OUTER workflow item index (the split's own
        // position within a parent iteration), NOT the sub-item index this delivery
        // covers. AgentNode persists both in splitItemData (itemIndex = sub-item 0..N,
        // workflowItemIndex = outer iteration); older producers wrote only itemIndex,
        // so we fall back to it for backwards compat. Using the sub-item here would
        // create a spurious context per arrival (core:split:1, :2, :3, …) shadowing the
        // real one at :0 and breaking per-item predecessor output injection on spawns 1..N.
        Object workflowItemIndexObj = splitItemData.get("workflowItemIndex");
        int workflowItemIndex;
        if (workflowItemIndexObj instanceof Number n) {
            workflowItemIndex = n.intValue();
        } else {
            Object legacy = splitItemData.get("itemIndex");
            workflowItemIndex = legacy instanceof Number ln ? ln.intValue() : 0;
        }

        if (splitNodeId == null || items == null) {
            logger.warn("[SplitContextManager] restoreContext: Missing splitNodeId or items in splitItemData: runId={}, nodeId={}",
                runId, nodeId);
            return;
        }

        // Check if context already exists (idempotent)
        Optional<SplitContext> existing = getContext(runId, splitNodeId, workflowItemIndex);
        if (existing.isPresent()) {
            logger.debug("[SplitContextManager] restoreContext: Context already exists, skipping: runId={}, splitNodeId={}, workflowItemIndex={}",
                runId, splitNodeId, workflowItemIndex);
            return;
        }

        // Reconstruct SplitContext
        createContext(runId, splitNodeId, workflowItemIndex, items);
        logger.info("[SplitContextManager] Restored split context from DB: runId={}, nodeId={}, splitNodeId={}, workflowItemIndex={}",
            runId, nodeId, splitNodeId, workflowItemIndex);
    }

    /**
     * Removes a specific SplitContext.
     * Also removes any nested (scoped) contexts that match the base key.
     *
     * @param runId the workflow run ID
     * @param splitNodeId the split node ID
     * @param workflowItemIndex the workflow item index
     */
    public void removeContext(String runId, String splitNodeId, int workflowItemIndex) {
        String contextKey = buildContextKey(splitNodeId, workflowItemIndex);
        Map<String, SplitContext> runContexts = contextsByRun.get(runId);
        if (runContexts != null) {
            // Remove exact key
            runContexts.remove(contextKey);
            // Also remove any scoped variants (e.g., key/s0, key/s1)
            runContexts.keySet().removeIf(k -> k.startsWith(contextKey + "/"));
            logger.debug("[SplitContextManager] Removed context: runId={}, key={} (and scoped variants)",
                runId, contextKey);
        }
    }

    /**
     * Legacy method - uses itemIndex 0.
     */
    public void removeContext(String runId, String splitNodeId) {
        removeContext(runId, splitNodeId, 0);
    }

    /**
     * Finds the active epoch-scoped SplitContext for a given node.
     *
     * @param runId the workflow run ID
     * @param epoch the epoch
     * @param nodeId the node to find context for
     * @param workflowItemIndex the workflow item index
     * @param nodeMap map of nodeId to ExecutionNode
     * @param parentScopeKey parent scope key, or null
     * @return the active SplitContext, or empty if not in a split scope
     */
    public Optional<SplitContext> findActiveContext(
            String runId,
            int epoch,
            String nodeId,
            int workflowItemIndex,
            Map<String, ExecutionNode> nodeMap,
            String parentScopeKey) {

        String runKey = buildRunKey(runId, epoch);
        Map<String, SplitContext> runContexts = contextsByRun.get(runKey);
        if (runContexts == null || runContexts.isEmpty()) {
            // Fall back to run-level contexts
            return findActiveContext(runId, nodeId, workflowItemIndex, nodeMap, parentScopeKey);
        }

        // Same BFS algorithm as the run-level version but searching in epoch-scoped contexts
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(nodeId);
        boolean isStartingNode = true;

        while (!queue.isEmpty()) {
            String currentId = queue.poll();
            if (visited.contains(currentId)) continue;
            visited.add(currentId);

            String lookupId = currentId;
            EdgeRefParser.EdgeRef ref = EdgeRefParser.parse(currentId);
            if (ref != null && ref.port() != null && !ref.port().isEmpty()) {
                lookupId = ref.nodeType() + ":" + ref.nodeLabel();
            }

            ExecutionNode currentNode = nodeMap.get(lookupId);
            if (currentNode == null) continue;

            if (currentNode.isSplitNode()) {
                SplitContext context = null;
                String matchedKey = null;

                if (parentScopeKey != null && !parentScopeKey.isEmpty()) {
                    matchedKey = buildContextKey(lookupId, workflowItemIndex, parentScopeKey);
                    context = runContexts.get(matchedKey);
                }
                if (context == null) {
                    matchedKey = buildContextKey(lookupId, workflowItemIndex);
                    context = runContexts.get(matchedKey);
                }
                if (context == null) {
                    String baseKey = buildContextKey(lookupId, workflowItemIndex);
                    String foundKey = findScopedKey(runContexts, baseKey);
                    if (foundKey != null) {
                        context = runContexts.get(foundKey);
                    }
                }

                if (context != null) return Optional.of(context);
            }

            if (!isStartingNode && currentNode.isAggregateNode()) {
                return Optional.empty();
            }

            if (!isStartingNode && currentNode.isMergeNode()) {
                if (!SplitMergeHandler.isBranchRejoinMerge(lookupId, nodeMap)) {
                    return Optional.empty();
                }
            }

            isStartingNode = false;
            queue.addAll(currentNode.getPredecessorIds());
        }

        return Optional.empty();
    }

    /**
     * Clears all SplitContexts for a specific epoch within a run.
     *
     * @param runId the workflow run ID
     * @param epoch the epoch to clear
     */
    public void clearEpoch(String runId, int epoch) {
        String runKey = buildRunKey(runId, epoch);
        contextsByRun.remove(runKey);
        logger.debug("[SplitContextManager] Cleared contexts for epoch: runId={}, epoch={}", runId, epoch);
    }

    /**
     * Clears all SplitContexts for a run.
     *
     * @param runId the workflow run ID
     */
    public void clearRun(String runId) {
        contextsByRun.remove(runId);
        // Also remove epoch-scoped entries
        String prefix = runId + ":";
        contextsByRun.keySet().removeIf(k -> k.startsWith(prefix));
        logger.debug("[SplitContextManager] Cleared all contexts for run: runId={}", runId);
    }

    /**
     * Gets all SplitContexts for a run (for debugging/testing).
     */
    public Map<String, SplitContext> getAllContexts(String runId) {
        return contextsByRun.getOrDefault(runId, Map.of());
    }

    /**
     * Checks if any SplitContext exists for a run.
     */
    public boolean hasContexts(String runId) {
        Map<String, SplitContext> runContexts = contextsByRun.get(runId);
        return runContexts != null && !runContexts.isEmpty();
    }

    /**
     * Gets the total number of active runs with contexts.
     */
    public int getActiveRunCount() {
        return contextsByRun.size();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RunScopedCache Implementation
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void cleanupRun(String runId) {
        clearRun(runId);
    }

    @Override
    public String getCacheName() {
        return "SplitContextCache";
    }

    @Override
    public CacheDomain getDomain() {
        return CacheDomain.CONTROL_FLOW;
    }

    @Override
    public int getCacheSize() {
        return contextsByRun.values().stream()
            .mapToInt(Map::size)
            .sum();
    }
}
