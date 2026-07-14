package com.apimarketplace.orchestrator.services.merge;

import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for collecting and merging workflow execution results.
 *
 * <p>Domain: CONTROL_FLOW - Manages merge point states for parallel workflows.
 *
 * <p>This service manages merge states for workflow merge points, collecting
 * results from multiple sources (normal steps and Split items) and combining
 * them when all expected items have been received.
 *
 * <p>Merge points are scoped by parent itemId to ensure items from different
 * trigger executions never mix. For example:
 * <ul>
 *   <li>Trigger item "0" spawns Split children "0.1", "0.2", "0.3"</li>
 *   <li>These children merge in scope "0"</li>
 *   <li>Trigger item "1" spawns its own children "1.1", "1.2"</li>
 *   <li>These children merge in scope "1" (completely separate)</li>
 * </ul>
 *
 * <p>Thread-safe: Uses ConcurrentHashMap for state storage and synchronized
 * blocks for atomic operations on individual merge states.
 *
 * @see RunScopedCache
 */
@Service
public class ItemMergeCollector implements RunScopedCache {

    private static final Logger log = LoggerFactory.getLogger(ItemMergeCollector.class);

    /**
     * Key for merge state storage: runId:mergePointId:scope
     */
    private record MergeKey(String runId, String mergePointId, String scope) {
        @Override
        public String toString() {
            return runId + ":" + mergePointId + ":" + scope;
        }
    }

    /**
     * Storage for all merge states.
     * Key: MergeKey (runId:mergePointId:scope)
     * Value: ItemMergeState
     */
    private final ConcurrentHashMap<MergeKey, ItemMergeState> mergeStates = new ConcurrentHashMap<>();

    /**
     * Locks for synchronizing operations on specific merge points.
     */
    private final ConcurrentHashMap<MergeKey, Object> locks = new ConcurrentHashMap<>();

    /**
     * Initializes a merge point for a given scope.
     *
     * <p>Must be called before any items can be recorded for this merge point.
     * This sets up the expected sources and their default counts.
     *
     * @param runId The workflow run ID
     * @param mergePointId The merge point node ID (e.g., "core:collect_results")
     * @param scope The parent item scope (e.g., "0", "0.1")
     * @param sourceNodeIds The source nodes that will contribute to this merge
     * @return The initialized merge state
     */
    public ItemMergeState initializeMergePoint(
            String runId,
            String mergePointId,
            String scope,
            Set<String> sourceNodeIds) {

        MergeKey key = new MergeKey(runId, mergePointId, scope);
        Object lock = locks.computeIfAbsent(key, k -> new Object());

        synchronized (lock) {
            ItemMergeState existing = mergeStates.get(key);
            if (existing != null) {
                log.debug("Merge point already initialized: {}", key);
                return existing;
            }

            ItemMergeState state = ItemMergeState.create(mergePointId, scope, sourceNodeIds);
            mergeStates.put(key, state);

            log.info("Initialized merge point {} with {} sources: {}",
                key, sourceNodeIds.size(), sourceNodeIds);

            return state;
        }
    }

    /**
     * Sets the expected count for a Split source.
     *
     * <p>Called when a Split node evaluates its list and knows how many
     * items it will spawn.
     *
     * @param runId The workflow run ID
     * @param mergePointId The merge point node ID
     * @param scope The parent item scope
     * @param sourceNodeId The Split source node ID
     * @param expectedCount The number of items that will be spawned
     * @return The updated merge state, or null if merge point not found
     */
    public ItemMergeState setExpectedCount(
            String runId,
            String mergePointId,
            String scope,
            String sourceNodeId,
            int expectedCount) {

        MergeKey key = new MergeKey(runId, mergePointId, scope);
        Object lock = locks.computeIfAbsent(key, k -> new Object());

        synchronized (lock) {
            ItemMergeState state = mergeStates.get(key);
            if (state == null) {
                log.warn("Cannot set expected count - merge point not initialized: {}", key);
                return null;
            }

            // withExpectedCount mutates in-place and returns `this`
            state.withExpectedCount(sourceNodeId, expectedCount);
            // Re-put the same reference: ConcurrentHashMap.put is a volatile write that
            // publishes the in-place mutations to lock-free readers on other threads.
            mergeStates.put(key, state);

            log.debug("Set expected count for {} in {}: {}",
                sourceNodeId, key, expectedCount);

            return state;
        }
    }

    /**
     * Records a successful completion from a source node.
     *
     * @param runId The workflow run ID
     * @param mergePointId The merge point node ID
     * @param itemId The item ID (e.g., "0", "0.1", "0.2")
     * @param sourceNodeId The source node that completed
     * @param data The result data
     * @return MergeResult indicating current status (WAITING, COMPLETE, PARTIAL)
     */
    public MergeResult recordSuccess(
            String runId,
            String mergePointId,
            String itemId,
            int itemIndex,
            String sourceNodeId,
            Map<String, Object> data) {

        ItemMergeEntry entry = ItemMergeEntry.success(itemId, itemIndex, sourceNodeId, data);
        return recordEntry(runId, mergePointId, entry);
    }

    /**
     * Records a failure from a source node.
     *
     * @param runId The workflow run ID
     * @param mergePointId The merge point node ID
     * @param itemId The item ID
     * @param sourceNodeId The source node that failed
     * @param errorMessage The error message
     * @return MergeResult indicating current status
     */
    public MergeResult recordFailure(
            String runId,
            String mergePointId,
            String itemId,
            int itemIndex,
            String sourceNodeId,
            String errorMessage) {

        ItemMergeEntry entry = ItemMergeEntry.failed(itemId, itemIndex, sourceNodeId, errorMessage);
        return recordEntry(runId, mergePointId, entry);
    }

    /**
     * Records a skipped item from a source node.
     *
     * @param runId The workflow run ID
     * @param mergePointId The merge point node ID
     * @param itemId The item ID
     * @param sourceNodeId The source node that was skipped
     * @param reason The reason for skipping
     * @return MergeResult indicating current status
     */
    public MergeResult recordSkipped(
            String runId,
            String mergePointId,
            String itemId,
            int itemIndex,
            String sourceNodeId,
            String reason) {

        ItemMergeEntry entry = ItemMergeEntry.skipped(itemId, itemIndex, sourceNodeId, reason);
        return recordEntry(runId, mergePointId, entry);
    }

    /**
     * Records an entry and checks if merge is complete.
     */
    private MergeResult recordEntry(
            String runId,
            String mergePointId,
            ItemMergeEntry entry) {

        String scope = entry.getScope();
        MergeKey key = new MergeKey(runId, mergePointId, scope);
        Object lock = locks.computeIfAbsent(key, k -> new Object());

        synchronized (lock) {
            ItemMergeState state = mergeStates.get(key);
            if (state == null) {
                log.warn("Merge point not initialized for entry: {} (key: {})", entry.itemId(), key);
                return MergeResult.failed(mergePointId, scope,
                    "Merge point not initialized for scope: " + scope);
            }

            // withEntry mutates in-place and returns `this`
            state.withEntry(entry);
            // Re-put the same reference: ConcurrentHashMap.put is a volatile write that
            // publishes the in-place mutations to lock-free readers on other threads.
            mergeStates.put(key, state);

            log.debug("Recorded entry for {} from {} in {} (progress: {}%)",
                entry.itemId(), entry.sourceNodeId(), key, String.format("%.1f", state.getProgress() * 100));

            // Check if merge is complete
            if (state.isComplete()) {
                return buildMergeResult(state);
            } else {
                return MergeResult.waiting(
                    mergePointId,
                    scope,
                    MergeResult.MergeMetadata.fromState(state)
                );
            }
        }
    }

    /**
     * Builds the final merge result from a completed state.
     */
    private MergeResult buildMergeResult(ItemMergeState state) {
        // Get results organized by type
        List<ItemMergeEntry> normalEntries = state.getNormalResults();
        List<ItemMergeEntry> splitEntries = state.getSplitResults();

        // Build normal results list
        List<Map<String, Object>> normalResults = new ArrayList<>();
        for (ItemMergeEntry entry : normalEntries) {
            if (entry.isSuccess()) {
                normalResults.add(entry.data());
            }
        }

        // Build Split results list (preserving order)
        List<MergeResult.SplitItemResult> splitResults = new ArrayList<>();
        for (ItemMergeEntry entry : splitEntries) {
            splitResults.add(new MergeResult.SplitItemResult(
                entry.itemId(),
                entry.itemIndex(),
                entry.data(),
                entry.isSuccess(),
                entry.sourceNodeId()
            ));
        }

        // Build combined list
        List<Map<String, Object>> combined = new ArrayList<>();
        for (ItemMergeEntry entry : normalEntries) {
            if (entry.isSuccess()) {
                combined.add(entry.data());
            }
        }
        for (ItemMergeEntry entry : splitEntries) {
            if (entry.isSuccess()) {
                combined.add(entry.data());
            }
        }

        // Build by-source map
        Map<String, List<Map<String, Object>>> bySource = new HashMap<>();
        for (String sourceId : state.getSourceNodeIds()) {
            List<Map<String, Object>> sourceResults = new ArrayList<>();
            for (ItemMergeEntry entry : state.getEntriesForSource(sourceId)) {
                if (entry.isSuccess()) {
                    sourceResults.add(entry.data());
                }
            }
            bySource.put(sourceId, sourceResults);
        }

        // Build by-item map
        Map<String, Map<String, Object>> byItem = new HashMap<>();
        for (ItemMergeEntry entry : splitEntries) {
            if (entry.isSuccess()) {
                byItem.put(entry.itemId(), entry.data());
            }
        }

        MergeResult.MergedData data = new MergeResult.MergedData(
            normalResults,
            splitResults,
            combined,
            bySource,
            byItem
        );

        MergeResult.MergeMetadata metadata = MergeResult.MergeMetadata.fromState(state);

        // Determine final status
        if (state.hasFailures()) {
            log.info("Merge complete with failures for {}/{}: {} success, {} failed",
                state.getMergePointId(), state.getScope(),
                state.getSuccessCount(), state.getFailedCount());
            return MergeResult.partial(state.getMergePointId(), state.getScope(), data, metadata);
        } else {
            log.info("Merge complete for {}/{}: {} items merged",
                state.getMergePointId(), state.getScope(), combined.size());
            return MergeResult.complete(state.getMergePointId(), state.getScope(), data, metadata);
        }
    }

    /**
     * Gets the current merge state for inspection.
     *
     * @param runId The workflow run ID
     * @param mergePointId The merge point node ID
     * @param scope The parent item scope
     * @return The current merge state, or null if not initialized
     */
    public ItemMergeState getMergeState(String runId, String mergePointId, String scope) {
        MergeKey key = new MergeKey(runId, mergePointId, scope);
        ItemMergeState state = mergeStates.get(key);
        if (state == null) return null;
        Object lock = locks.get(key);
        if (lock == null) return state.toSnapshot();
        synchronized (lock) {
            state = mergeStates.get(key);
            return state != null ? state.toSnapshot() : null;
        }
    }

    /**
     * Gets detailed progress for a merge point.
     *
     * @param runId The workflow run ID
     * @param mergePointId The merge point node ID
     * @param scope The parent item scope
     * @return Progress information, or null if not initialized
     */
    public Map<String, ItemMergeState.SourceProgress> getProgress(
            String runId, String mergePointId, String scope) {

        ItemMergeState state = getMergeState(runId, mergePointId, scope);
        return state != null ? state.getDetailedProgress() : null;
    }

    /**
     * Checks if a merge point is complete.
     *
     * @param runId The workflow run ID
     * @param mergePointId The merge point node ID
     * @param scope The parent item scope
     * @return true if complete, false if waiting or not initialized
     */
    public boolean isComplete(String runId, String mergePointId, String scope) {
        ItemMergeState state = getMergeState(runId, mergePointId, scope);
        return state != null && state.isComplete();
    }

    /**
     * Cleans up merge states for a completed workflow run.
     *
     * <p>Should be called after workflow completion to free memory.
     *
     * @param runId The workflow run ID to clean up
     */
    @Override
    public void cleanupRun(String runId) {
        int removed = 0;
        for (MergeKey key : mergeStates.keySet()) {
            if (key.runId().equals(runId)) {
                mergeStates.remove(key);
                locks.remove(key);
                removed++;
            }
        }

        if (removed > 0) {
            log.debug("Cleaned up {} merge states for run {}", removed, runId);
        }
    }

    // MergeKey has no epoch field, so this clears ALL merge states for the run.
    // Safe at epoch boundary because the completed epoch's merges are all done,
    // and single-epoch-at-a-time workflows (the common case) have no sibling state.
    // TODO: add epoch to MergeKey for true epoch-scoped cleanup (parallel epoch safety)
    public void clearCompletedEpochMergeStates(String runId) {
        int removed = 0;
        var it = mergeStates.keySet().iterator();
        while (it.hasNext()) {
            MergeKey key = it.next();
            if (key.runId().equals(runId)) {
                it.remove();
                locks.remove(key);
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("[ItemMergeCollector] Cleared {} merge states at epoch boundary: runId={}", removed, runId);
        }
    }

    /**
     * Gets all active merge states (for debugging/monitoring).
     *
     * @return Map of merge keys to their states
     */
    public Map<String, ItemMergeState> getAllStates() {
        Map<String, ItemMergeState> result = new HashMap<>();
        for (Map.Entry<MergeKey, ItemMergeState> entry : mergeStates.entrySet()) {
            MergeKey key = entry.getKey();
            Object lock = locks.get(key);
            if (lock != null) {
                synchronized (lock) {
                    ItemMergeState state = mergeStates.get(key);
                    if (state != null) result.put(key.toString(), state.toSnapshot());
                }
            } else {
                result.put(key.toString(), entry.getValue());
            }
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RunScopedCache Implementation
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public String getCacheName() {
        return "ItemMergeCache";
    }

    @Override
    public CacheDomain getDomain() {
        return CacheDomain.CONTROL_FLOW;
    }

    @Override
    public int getCacheSize() {
        return mergeStates.size();
    }
}
