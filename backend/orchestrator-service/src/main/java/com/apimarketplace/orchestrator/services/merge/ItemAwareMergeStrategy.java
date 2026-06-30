package com.apimarketplace.orchestrator.services.merge;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.nodes.merge.MergeStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Item-aware merge strategy that properly handles Split results.
 *
 * <p>This strategy uses the {@link ItemMergeCollector} to track and merge
 * results from multiple sources, including Split child items.
 *
 * <p>Key features:
 * <ul>
 *   <li>Scopes merges by parent item ID (items never mix)</li>
 *   <li>Tracks expected vs received counts per source</li>
 *   <li>Preserves item ordering for Split results</li>
 *   <li>Handles mixed normal + Split sources</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * // Initialize merge point when Split spawns items
 * collector.initializeMergePoint(runId, "core:results", scope, sourceNodeIds);
 * collector.setExpectedCount(runId, "core:results", scope, "core:process", 3);
 *
 * // Record completions as items finish
 * collector.recordSuccess(runId, "core:results", itemId, index, sourceNodeId, data);
 *
 * // Check merge status
 * MergeResult result = collector.recordSuccess(...);
 * if (result.isReady()) {
 *     // Merge complete, proceed with merged data
 * }
 * </pre>
 */
public class ItemAwareMergeStrategy implements MergeStrategy {

    private static final Logger log = LoggerFactory.getLogger(ItemAwareMergeStrategy.class);

    private final ItemMergeCollector collector;

    public ItemAwareMergeStrategy(ItemMergeCollector collector) {
        this.collector = collector;
    }

    @Override
    public String name() {
        return "ITEM_AWARE";
    }

    @Override
    public boolean canMerge(List<String> sourceNodeIds, ExecutionContext context) {
        String runId = context.runId();
        String scope = ItemMergeScope.getParentScope(context.itemId());

        // Find the merge point ID from context
        String mergePointId = findMergePointId(sourceNodeIds, context);
        if (mergePointId == null) {
            log.warn("Cannot determine merge point ID for sources: {}", sourceNodeIds);
            return false;
        }

        // Check if collector has this merge point initialized
        ItemMergeState state = collector.getMergeState(runId, mergePointId, scope);
        if (state == null) {
            log.debug("Merge point not yet initialized: runId={}, mergePointId={}, scope={}",
                runId, mergePointId, scope);
            return false;
        }

        // Check if all expected items have been received
        boolean isComplete = state.isComplete();
        if (!isComplete) {
            log.debug("Merge waiting: runId={}, mergePointId={}, scope={}, progress={:.1f}%",
                runId, mergePointId, scope, state.getProgress() * 100);
        }

        return isComplete;
    }

    @Override
    public Map<String, Object> merge(List<String> sourceNodeIds, ExecutionContext context) {
        String runId = context.runId();
        String scope = ItemMergeScope.getParentScope(context.itemId());
        String mergePointId = findMergePointId(sourceNodeIds, context);

        if (mergePointId == null) {
            log.error("Cannot determine merge point ID");
            return Map.of("error", "Cannot determine merge point ID");
        }

        ItemMergeState state = collector.getMergeState(runId, mergePointId, scope);
        if (state == null) {
            log.error("Merge state not found: runId={}, mergePointId={}, scope={}",
                runId, mergePointId, scope);
            return Map.of("error", "Merge state not found");
        }

        // Build merged data from state
        return buildMergedData(state);
    }

    @Override
    public boolean shouldSkip(List<String> sourceNodeIds, ExecutionContext context) {
        String runId = context.runId();
        String scope = ItemMergeScope.getParentScope(context.itemId());
        String mergePointId = findMergePointId(sourceNodeIds, context);

        if (mergePointId == null) {
            return false;
        }

        ItemMergeState state = collector.getMergeState(runId, mergePointId, scope);
        if (state == null) {
            return false;
        }

        // Skip only if ALL entries are skipped (no successes, no failures)
        if (!state.isComplete()) {
            return false;
        }

        // Check if there are any success entries
        return state.getSuccessCount() == 0 && state.getFailedCount() == 0;
    }

    @Override
    public String getSkipReason(List<String> sourceNodeIds, ExecutionContext context) {
        return "All source branches were skipped";
    }

    /**
     * Builds the merged data output from the merge state.
     */
    private Map<String, Object> buildMergedData(ItemMergeState state) {
        Map<String, Object> output = new HashMap<>();

        // Get normal results
        List<ItemMergeEntry> normalEntries = state.getNormalResults();
        List<Map<String, Object>> normalResults = new ArrayList<>();
        for (ItemMergeEntry entry : normalEntries) {
            if (entry.isSuccess()) {
                normalResults.add(entry.data());
            }
        }

        // Get Split results (ordered by index)
        List<ItemMergeEntry> splitEntries = state.getSplitResults();
        List<Map<String, Object>> splitResults = new ArrayList<>();
        for (ItemMergeEntry entry : splitEntries) {
            if (entry.isSuccess()) {
                splitResults.add(entry.data());
            }
        }

        // Combined list (normal first, then split in order)
        List<Map<String, Object>> combined = new ArrayList<>();
        combined.addAll(normalResults);
        combined.addAll(splitResults);

        // Build output
        output.put("merged_results", combined);
        output.put("normal_results", normalResults);
        output.put("split_results", splitResults);
        output.put("item_count", combined.size());
        output.put("split_count", splitResults.size());
        output.put("normal_count", normalResults.size());

        // Add progress metadata
        output.put("success_count", state.getSuccessCount());
        output.put("failed_count", state.getFailedCount());
        output.put("has_failures", state.hasFailures());

        // Group by source for advanced use cases
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
        output.put("by_source", bySource);

        // Group Split by item ID
        Map<String, Map<String, Object>> byItem = new HashMap<>();
        for (ItemMergeEntry entry : splitEntries) {
            if (entry.isSuccess()) {
                byItem.put(entry.itemId(), entry.data());
            }
        }
        output.put("by_item", byItem);

        log.info("Merge completed: scope={}, totalItems={}, normalCount={}, splitCount={}, failures={}",
            state.getScope(), combined.size(), normalResults.size(),
            splitResults.size(), state.getFailedCount());

        return output;
    }

    /**
     * Finds the merge point ID from context or source configuration.
     */
    private String findMergePointId(List<String> sourceNodeIds, ExecutionContext context) {
        // Try to get from context
        return context.getGlobalData("current_merge_point_id")
            .map(Object::toString)
            .orElse(null);
    }

    /**
     * Factory method to create strategy with injected collector.
     */
    public static ItemAwareMergeStrategy create(ItemMergeCollector collector) {
        return new ItemAwareMergeStrategy(collector);
    }
}
