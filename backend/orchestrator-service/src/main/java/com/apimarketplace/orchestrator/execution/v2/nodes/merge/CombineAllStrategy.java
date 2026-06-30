package com.apimarketplace.orchestrator.execution.v2.nodes.merge;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.OutputUnwrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Combine All Merge Strategy.
 *
 * This strategy:
 * - Waits for all sources to complete
 * - Combines ALL data into a flat list
 * - Does NOT skip if some sources failed (lenient mode)
 *
 * Use case: Aggregate data from multiple sources where partial results are acceptable.
 * Example: fetch from multiple APIs, combine whatever succeeded.
 */
public class CombineAllStrategy implements MergeStrategy {

    private static final Logger logger = LoggerFactory.getLogger(CombineAllStrategy.class);

    @Override
    public String name() {
        return "COMBINE_ALL";
    }

    @Override
    public boolean canMerge(List<String> sourceNodeIds, ExecutionContext context) {
        // All sources must be completed (even if failed/skipped)
        for (String sourceId : sourceNodeIds) {
            if (!context.isCompleted(sourceId)) {
                logger.debug("COMBINE_ALL: Waiting for source {}", sourceId);
                return false;
            }
        }
        return true;
    }

    @Override
    public Map<String, Object> merge(List<String> sourceNodeIds, ExecutionContext context) {
        Map<String, Object> mergedData = new LinkedHashMap<>();
        List<Object> allItems = new ArrayList<>();
        Map<String, Object> sourceOutputs = new LinkedHashMap<>();
        int successCount = 0;
        int failedCount = 0;

        for (String sourceId : sourceNodeIds) {
            if (context.isSuccess(sourceId)) {
                successCount++;
                Optional<Object> output = context.getStepOutput(sourceId);
                if (output.isPresent()) {
                    Object rawData = output.get();
                    // Unwrap the "output" wrapper added by ExecutionContext.withResult()
                    Object data = unwrapOutput(rawData);
                    sourceOutputs.put(sourceId, data);
                    flattenAndAdd(data, allItems);
                }
            } else {
                failedCount++;
                sourceOutputs.put(sourceId, Map.of("status", "skipped_or_failed"));
            }
        }

        mergedData.put("strategy", name());
        mergedData.put("source_count", sourceNodeIds.size());
        mergedData.put("success_count", successCount);
        mergedData.put("failed_count", failedCount);
        mergedData.put("sources", sourceOutputs);
        mergedData.put("merged_items", allItems);
        mergedData.put("item_count", allItems.size());

        logger.debug("COMBINE_ALL merge completed: sources={}, success={}, failed={}, items={}",
            sourceNodeIds.size(), successCount, failedCount, allItems.size());

        return mergedData;
    }

    /**
     * Unwraps the "output" wrapper added by ExecutionContext.withResult().
     * Delegates to {@link OutputUnwrapper#unwrapOutputObject(Object)}.
     */
    private Object unwrapOutput(Object data) {
        return OutputUnwrapper.unwrapOutputObject(data);
    }

    @SuppressWarnings("unchecked")
    private void flattenAndAdd(Object data, List<Object> allItems) {
        if (data instanceof List) {
            allItems.addAll((List<?>) data);
        } else if (data instanceof Map) {
            Map<?, ?> mapData = (Map<?, ?>) data;
            // Check for common list keys
            for (String key : Arrays.asList("items", "data", "results", "records")) {
                if (mapData.containsKey(key)) {
                    Object nested = mapData.get(key);
                    if (nested instanceof List) {
                        allItems.addAll((List<?>) nested);
                        return;
                    }
                }
            }
            // If no nested list, add the map itself
            allItems.add(data);
        } else if (data != null) {
            allItems.add(data);
        }
    }

    @Override
    public boolean shouldSkip(List<String> sourceNodeIds, ExecutionContext context) {
        // Skip only if ALL sources failed/skipped
        for (String sourceId : sourceNodeIds) {
            if (context.isSuccess(sourceId)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String getSkipReason(List<String> sourceNodeIds, ExecutionContext context) {
        return "All sources failed or were skipped";
    }
}
