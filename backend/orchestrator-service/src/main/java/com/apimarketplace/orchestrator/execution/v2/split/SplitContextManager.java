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
        // Stamped, not only bucketed: the run-level copy written just below is keyed WITHOUT the
        // epoch, and that copy is the one every reader on this pod resolves (nothing reads the
        // runId:epoch bucket). Without the stamp it would read as UNKNOWN and be reused by a
        // later epoch's delivery instead of rebuilt (see restoreContext).
        SplitContext context = SplitContext.create(contextKey, items, epoch);

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
        return createContext(runId, splitNodeId, workflowItemIndex, parentScopeKey, items,
            SplitContext.UNKNOWN_EPOCH);
    }

    /**
     * Creates a new SplitContext for the given split node and workflow item, stamped with the
     * epoch that spawned the items.
     *
     * <p>The stamp is what {@link #restoreContext(String, String, Map, int)} compares against to
     * tell a context belonging to the CURRENT epoch (preserve it, it carries the per-item
     * results) from one left behind by an earlier epoch of the same run (rebuild it). Callers
     * that know their epoch MUST use this overload; the 5-arg one stamps
     * {@link SplitContext#UNKNOWN_EPOCH}, which is never treated as stale.
     *
     * @param runId the workflow run ID
     * @param splitNodeId the split node ID
     * @param workflowItemIndex the workflow item index (from trigger)
     * @param parentScopeKey the parent split scope suffix (e.g., "s0"), or null for top-level
     * @param items the list of items to iterate over (spawned by split)
     * @param epoch the epoch this split spawned in, or {@link SplitContext#UNKNOWN_EPOCH}
     * @return the created SplitContext
     */
    public SplitContext createContext(String runId, String splitNodeId, int workflowItemIndex,
                                     String parentScopeKey, List<Object> items, int epoch) {
        String contextKey = buildContextKey(splitNodeId, workflowItemIndex, parentScopeKey);
        SplitContext context = SplitContext.create(contextKey, items, epoch);

        contextsByRun
            .computeIfAbsent(runId, k -> new ConcurrentHashMap<>())
            .put(contextKey, context);

        logger.info("[SplitContextManager] Created context: runId={}, key={}, itemCount={}, parentScope={}, epoch={}",
            runId, contextKey, context.itemCount(), parentScopeKey, epoch);

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
     * Restore split context from persisted resume data, without epoch attribution.
     *
     * <p>NOT for production use: with no epoch this cannot tell a scope belonging to the resume
     * from one an earlier epoch left on this pod, so it keeps the pre-2026-08-14 "already exists
     * -> skip". Every production caller (signal resume, async delivery, and
     * {@link SplitContextRehydrator}) passes its epoch through
     * {@link #restoreContext(String, String, Map, int)}, and
     * {@code SplitContextEpochStampInvariantTest} fails the build if a new one does not.
     *
     * @param runId the workflow run ID
     * @param nodeId the node ID that had the signal (for logging)
     * @param splitItemData persisted split context data (splitNodeId + items + indices)
     */
    public void restoreContext(String runId, String nodeId, Map<String, Object> splitItemData) {
        restoreContext(runId, nodeId, splitItemData, SplitContext.UNKNOWN_EPOCH);
    }

    /**
     * Restore split context from persisted resume data, for a delivery known to belong to
     * {@code epoch}.
     *
     * <p>Same contract as {@link #restoreContext(String, String, Map)} plus ONE rule: a context
     * already in memory for this key is reused only when it belongs to the same (or a newer)
     * epoch. A context left behind by an OLDER epoch is rebuilt from {@code splitItemData}.
     *
     * <p>Why this matters in production (proven on prod 2026-08-14, run
     * {@code run_<id>}, on the pairs 80 -> 81 and 96 -> 97): the orchestrator
     * runs several replicas, and an async agent delivery is handled by whichever pod consumes the
     * Redis message, not by the pod that ran the split. The in-memory key carries no epoch, so a
     * pod that ran epoch N and not epoch N+1 still holds epoch N's context. The pre-fix
     * "already exists -> skip" then handed the N+1 fan-out epoch N's item list AND epoch N's
     * per-item results: the run executed the previous epoch's items (prod symptom: a mail move
     * addressing the UID of a mail filed six hours earlier, plus phantom SKIPPED rows at item
     * indices no item justifies). Rebuilding drops {@code resultsByNode}, which is the CORRECT
     * state for a pod that did not run this epoch: an empty map makes
     * {@code SplitAwareNodeExecutor.inMemorySlotsComplete} return false, so the epoch-scoped
     * durable backfill serves the real per-item values.
     *
     * <p><b>Scope, stated so nobody assumes more.</b> Two limits, both pre-existing:
     * <ul>
     *   <li><b>Base key only.</b> The lookup uses {@code splitNodeId:workflowItemIndex}; the
     *       resume blob carries no parent scope, so a NESTED scope ({@code …:0/sN}) is neither
     *       inspected nor rebuilt, and {@code findActiveContext} prefers that scoped key when a
     *       {@code parentScopeKey} is in play. A nested split whose inner scope is stale is NOT
     *       covered here.</li>
     *   <li><b>One slot per key, all epochs.</b> Every epoch of a run shares this single
     *       run-level entry (the {@code runId:epoch} bucket exists but nothing reads it), so a
     *       rebuild takes the slot from an older epoch that is still in flight on this pod: its
     *       results are recoverable from the epoch-scoped durable store, its item list is not.
     *       The hazard CLASS is not new - the split node's own {@code createContext} already
     *       overwrites that slot unconditionally when the next epoch's split runs on a pod, and
     *       {@code SplitContextStaleEpochRestoreTest#olderInFlightEpochLosesTheSameThingEitherWay}
     *       asserts both paths leave identical state - but this WIDENS where it can happen:
     *       before, a pod that had not run epoch N+1's split kept epoch N's slot; now it drops
     *       it on the first N+1 delivery. It is also the reason the comparison is strictly
     *       "older loses" rather than "different loses": a late delivery for a PAST epoch must
     *       not take the slot from the epoch currently executing. Since 2026-09-26
     *       {@link SplitContextRehydrator} also calls this from every node execution on a resume
     *       (not only on deliveries), so an older in-flight epoch on this pod loses its slot at
     *       the first node of a newer epoch that reads the same split; same trade-off, wider.</li>
     * </ul>
     *
     * <p>An {@link SplitContext#UNKNOWN_EPOCH} on either side keeps the pre-fix skip.
     *
     * @param runId the workflow run ID
     * @param nodeId the node ID that had the signal/agent (for logging)
     * @param splitItemData persisted split context data (splitNodeId + items + indices)
     * @param epoch the epoch this delivery belongs to, or {@link SplitContext#UNKNOWN_EPOCH}
     */
    @SuppressWarnings("unchecked")
    public void restoreContext(String runId, String nodeId, Map<String, Object> splitItemData, int epoch) {
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

        // Check if context already exists (idempotent), and whether the one in memory belongs to
        // an OLDER epoch of this run - on another pod it can, and reusing it replays that epoch.
        // Decided and applied under the map's own lock so two deliveries of the same new epoch
        // racing on this pod cannot both rebuild (the second sees the first's fresh stamp).
        final String contextKey = buildContextKey(splitNodeId, workflowItemIndex);
        final List<Object> restoredItems = items;
        // 0 = reused as-is, 1 = built (nothing in memory), 2 = rebuilt over an older epoch
        int[] outcome = {0};

        SplitContext resolved = contextsByRun
            .computeIfAbsent(runId, k -> new ConcurrentHashMap<>())
            .compute(contextKey, (k, current) -> {
                if (current != null && !isStaleForEpoch(current, epoch)) {
                    outcome[0] = 0;
                    return current;
                }
                outcome[0] = (current == null) ? 1 : 2;
                return SplitContext.create(contextKey, restoredItems, epoch);
            });

        switch (outcome[0]) {
            case 2 -> logger.info(
                "[SplitContextManager] Rebuilt a split context left behind by an older epoch: runId={}, nodeId={}, key={}, epoch={}, itemCount={}",
                runId, nodeId, contextKey, epoch, resolved.itemCount());
            // itemCount is on BOTH info lines on purpose: comparing the fan-out size a pod uses
            // against the split's own "Created context ... itemCount=N" is how this class of bug
            // is read off the logs.
            case 1 -> logger.info(
                "[SplitContextManager] Restored split context from the resume data: runId={}, nodeId={}, splitNodeId={}, workflowItemIndex={}, epoch={}, itemCount={}",
                runId, nodeId, splitNodeId, workflowItemIndex, epoch, resolved.itemCount());
            default -> logger.debug(
                "[SplitContextManager] restoreContext: Context already exists for this epoch, skipping: runId={}, splitNodeId={}, workflowItemIndex={}, epoch={}",
                runId, splitNodeId, workflowItemIndex, epoch);
        }
    }

    /**
     * Is {@code current} a context left behind by an EARLIER epoch than {@code epoch}?
     *
     * <p>Requires both stamps to be known: an {@link SplitContext#UNKNOWN_EPOCH} on either side
     * means the caller could not attribute an epoch, and guessing there would rebuild a live
     * context (dropping its per-item results) on a legitimate same-epoch re-entry. Strictly
     * older, never merely different, so a late delivery for a past epoch cannot overwrite the
     * context the current epoch is executing on.
     */
    private static boolean isStaleForEpoch(SplitContext current, int epoch) {
        return epoch != SplitContext.UNKNOWN_EPOCH
            && current.epoch() != SplitContext.UNKNOWN_EPOCH
            && current.epoch() < epoch;
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
     * The split node whose scope {@code nodeId} sits in, read from the GRAPH only (no in-memory
     * context needed), or {@code null} when none can be found.
     *
     * <p>Same traversal and the same scope boundaries as {@link #findActiveContext}: walks
     * predecessors (port suffixes stripped), stops at an aggregate or a split-aggregation merge
     * other than the starting node, and returns the first split node reached. It exists for the
     * pod that has to rebuild a context it never created: the orchestrator runs several replicas,
     * and a signal resume or an async delivery can execute a split-scope node on a pod whose
     * memory holds no context for that split.
     *
     * @return the split node id (no port), or {@code null}
     */
    public static String findUpstreamSplitNodeId(String nodeId, Map<String, ExecutionNode> nodeMap) {
        if (nodeId == null || nodeMap == null || nodeMap.isEmpty()) {
            return null;
        }
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(nodeId);
        boolean isStartingNode = true;

        while (!queue.isEmpty()) {
            String currentId = queue.poll();
            if (!visited.add(currentId)) {
                continue;
            }
            String lookupId = currentId;
            EdgeRefParser.EdgeRef ref = EdgeRefParser.parse(currentId);
            if (ref != null && ref.port() != null && !ref.port().isEmpty()) {
                lookupId = ref.nodeType() + ":" + ref.nodeLabel();
            }
            ExecutionNode currentNode = nodeMap.get(lookupId);
            if (currentNode == null) {
                continue;
            }
            if (!isStartingNode && currentNode.isSplitNode()) {
                return lookupId;
            }
            if (!isStartingNode && currentNode.isAggregateNode()) {
                return null;
            }
            if (!isStartingNode && currentNode.isMergeNode()
                    && !SplitMergeHandler.isBranchRejoinMerge(lookupId, nodeMap)) {
                return null;
            }
            isStartingNode = false;
            queue.addAll(currentNode.getPredecessorIds());
        }
        return null;
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
