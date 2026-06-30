package com.apimarketplace.orchestrator.execution.v2.nodes.merge;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.OutputUnwrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Queue 1-to-1 Merge Strategy.
 *
 * This strategy (per item, no cross-item mixing):
 * - Waits for ALL sources to complete (success, failed, or skipped)
 * - At least ONE source must succeed to continue
 * - Collects data from successful sources only
 * - Skips only if ALL sources failed/skipped
 *
 * Use case: Parallel branches where at least one must succeed.
 * Example: fetch from API A AND API B, continue if at least one succeeded.
 */
public class Queue1To1Strategy implements MergeStrategy {

    private static final Logger logger = LoggerFactory.getLogger(Queue1To1Strategy.class);

    @Override
    public String name() {
        return "QUEUE_1_TO_1";
    }

    @Override
    public boolean canMerge(List<String> sourceNodeIds, ExecutionContext context) {
        // All sources must be completed (success, failed, or skipped)
        for (String sourceId : sourceNodeIds) {
            if (!context.isCompleted(sourceId)) {
                logger.debug("QUEUE_1_TO_1: Waiting for source {}", sourceId);
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

        for (String sourceId : sourceNodeIds) {
            if (context.isSuccess(sourceId)) {
                successCount++;
                Optional<Object> output = context.getStepOutput(sourceId);
                if (output.isPresent()) {
                    Object rawData = output.get();
                    // Unwrap the "output" wrapper added by ExecutionContext.withResult()
                    // Structure: { "output": { actual data... } }
                    Object data = unwrapOutput(rawData);
                    sourceOutputs.put(sourceId, data);

                    // Collect items from successful sources only.
                    // Delegate "is this iterable, or a Map wrapping one?" to OutputUnwrapper so
                    // the recognized-key set stays consistent with SplitNode and any future
                    // iteration site (closes the 2026-05-14 merge/split asymmetry where
                    // Queue1To1Strategy only recognized `items` while real catalog tools also
                    // use `records`/`results`/`data`/etc.).
                    // Fallback policy DIFFERS from SplitNode: merge tolerates a non-iterable
                    // source by adding it as a single merged item (preserves the "merge of
                    // scalars or single config objects" use-case); Split fails loud instead.
                    Optional<List<Object>> extracted = OutputUnwrapper.tryUnwrapToList(data);
                    if (extracted.isPresent()) {
                        allItems.addAll(extracted.get());
                    } else {
                        allItems.add(data);
                    }
                }
            }
        }

        mergedData.put("strategy", name());
        mergedData.put("source_count", sourceNodeIds.size());
        mergedData.put("success_count", successCount);
        mergedData.put("sources", sourceOutputs);
        mergedData.put("merged_items", allItems);
        mergedData.put("item_count", allItems.size());

        logger.debug("QUEUE_1_TO_1 merge completed: sources={}, success={}, items={}",
            sourceNodeIds.size(), successCount, allItems.size());

        return mergedData;
    }

    /**
     * Unwraps the "output" wrapper added by ExecutionContext.withResult().
     * Delegates to {@link OutputUnwrapper#unwrapOutputObject(Object)}.
     */
    private Object unwrapOutput(Object data) {
        return OutputUnwrapper.unwrapOutputObject(data);
    }

    @Override
    public boolean shouldSkip(List<String> sourceNodeIds, ExecutionContext context) {
        // Skip only if ALL sources failed/skipped (need at least one success)
        for (String sourceId : sourceNodeIds) {
            if (context.isSuccess(sourceId)) {
                return false; // At least one succeeded, don't skip
            }
        }
        return true; // All failed/skipped
    }

    @Override
    public String getSkipReason(List<String> sourceNodeIds, ExecutionContext context) {
        return "All sources failed or were skipped (QUEUE_1_TO_1 requires at least one successful source)";
    }
}
