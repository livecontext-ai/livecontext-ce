package com.apimarketplace.orchestrator.execution.v2.split;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable context for split execution.
 *
 * <p>Stores spawned items and results from downstream nodes.
 * Each split node creates one context that is shared by all downstream nodes
 * until a merge node closes it.
 *
 * <p>Design principles:
 * <ul>
 *   <li>Immutable - all mutations return new instances</li>
 *   <li>Thread-safe - results map uses ConcurrentHashMap internally</li>
 *   <li>Simple - no iteration tracking, just data storage</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * // split creates context with items
 * SplitContext ctx = SplitContext.create("core:split1", items);
 *
 * // Downstream nodes store their results
 * ctx = ctx.withResults("mcp:step1", resultsList);
 *
 * // Access items and results
 * List<Object> items = ctx.items();
 * List<Object> step1Results = ctx.getResults("mcp:step1");
 * </pre>
 */
public record SplitContext(
    String splitNodeId,
    List<Object> items,
    Map<String, List<Object>> resultsByNode
) {

    /**
     * Creates a new SplitContext with the given items.
     *
     * @param splitNodeId the node ID of the split that created this context
     * @param items the list of items to iterate over
     * @return a new SplitContext
     */
    public static SplitContext create(String splitNodeId, List<Object> items) {
        return new SplitContext(
            splitNodeId,
            items != null ? List.copyOf(items) : List.of(),
            new ConcurrentHashMap<>()
        );
    }

    /**
     * Returns the number of items in this context.
     */
    public int itemCount() {
        return items.size();
    }

    /**
     * Returns true if there are no items.
     */
    public boolean isEmpty() {
        return items.isEmpty();
    }

    /**
     * Gets a specific item by index.
     *
     * @param index the item index (0-based)
     * @return the item at the given index
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public Object getItem(int index) {
        return items.get(index);
    }

    /**
     * Stores results for a node.
     * Returns a new context with the updated results.
     *
     * @param nodeId the node that produced these results
     * @param results the list of results (one per item)
     * @return a new SplitContext with the results stored
     */
    public SplitContext withResults(String nodeId, List<Object> results) {
        Map<String, List<Object>> newResults = new ConcurrentHashMap<>(resultsByNode);
        // Use ArrayList wrapped as unmodifiable rather than List.copyOf: per-item results
        // legitimately carry nulls (a node that skipped some items, or a sparse batch
        // from an async barrier where upstream filtering produced non-contiguous indices
        // - see AgentAsyncCompletionService.storeSplitBatchInContext). List.copyOf
        // throws NPE on null elements.
        List<Object> stored = results != null
            ? Collections.unmodifiableList(new ArrayList<>(results))
            : List.of();
        newResults.put(nodeId, stored);
        return new SplitContext(splitNodeId, items, newResults);
    }

    /**
     * Stores the result for a single item slot, leaving other slots untouched.
     * Used by chained downstream nodes that execute one item at a time inside a
     * parallel split traversal (auto mode), where {@link #withResults} would
     * clobber slots populated by sibling per-item executions.
     *
     * <p>If no list exists yet for {@code nodeId}, a list of size {@code totalItems}
     * pre-filled with nulls is created. Existing lists are padded to {@code totalItems}
     * if shorter. Out-of-range or negative indices are no-ops.
     *
     * @param nodeId the node that produced this per-item result
     * @param itemIndex the sub-item index within the split (0..totalItems-1)
     * @param totalItems the split's total item count (used to pre-size the list)
     * @param result the per-item result to store at {@code itemIndex}
     * @return a new SplitContext with the slot updated, or {@code this} if {@code itemIndex} is invalid
     */
    public SplitContext withResultAtIndex(String nodeId, int itemIndex, int totalItems, Object result) {
        // Reject null results so a stale slot from a prior write isn't silently zeroed by a
        // subsequent failed/no-output call. Callers that genuinely want to clear a slot must
        // build a new context via withResults() with explicit nulls in the list.
        if (nodeId == null || itemIndex < 0 || result == null) {
            return this;
        }
        int targetSize = Math.max(totalItems, itemIndex + 1);

        Map<String, List<Object>> newResults = new ConcurrentHashMap<>(resultsByNode);
        List<Object> existing = newResults.get(nodeId);
        List<Object> mutable = new ArrayList<>(targetSize);
        if (existing != null) {
            mutable.addAll(existing);
        }
        while (mutable.size() < targetSize) {
            mutable.add(null);
        }
        mutable.set(itemIndex, result);
        newResults.put(nodeId, Collections.unmodifiableList(mutable));
        return new SplitContext(splitNodeId, items, newResults);
    }

    /**
     * Gets results for a specific node.
     *
     * @param nodeId the node ID
     * @return the results list, or empty list if no results stored
     */
    public List<Object> getResults(String nodeId) {
        return resultsByNode.getOrDefault(nodeId, List.of());
    }

    /**
     * Gets the results from the most recently executed node.
     * Useful for accessing "previous step" results.
     *
     * @return the latest results, or empty list if no results stored
     */
    public List<Object> getLatestResults() {
        if (resultsByNode.isEmpty()) {
            return List.of();
        }
        // Return results from any node (in practice, caller should specify nodeId)
        return resultsByNode.values().stream()
            .filter(r -> !r.isEmpty())
            .findFirst()
            .orElse(List.of());
    }

    /**
     * Gets all stored results.
     *
     * @return unmodifiable view of all results by node ID
     */
    public Map<String, List<Object>> getAllResults() {
        return Collections.unmodifiableMap(resultsByNode);
    }

    /**
     * Checks if results exist for a node.
     */
    public boolean hasResults(String nodeId) {
        return resultsByNode.containsKey(nodeId);
    }

    /**
     * Clears results for a specific node (used for rerun).
     *
     * @param nodeId the node ID to clear
     * @return a new SplitContext with the node's results cleared
     */
    public SplitContext clearResults(String nodeId) {
        Map<String, List<Object>> newResults = new ConcurrentHashMap<>(resultsByNode);
        newResults.remove(nodeId);
        return new SplitContext(splitNodeId, items, newResults);
    }

    /**
     * Clears all downstream results (used for split rerun).
     *
     * @return a new SplitContext with only items, no results
     */
    public SplitContext clearAllResults() {
        return new SplitContext(splitNodeId, items, new ConcurrentHashMap<>());
    }
}
