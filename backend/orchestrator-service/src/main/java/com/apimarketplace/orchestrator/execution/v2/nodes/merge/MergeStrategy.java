package com.apimarketplace.orchestrator.execution.v2.nodes.merge;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;

import java.util.List;
import java.util.Map;

/**
 * Strategy interface for merge operations.
 *
 * Different strategies handle different merge scenarios:
 * - QUEUE_1_TO_1: Parallel combination (waits for all sources)
 * - COMBINE_ALL: Flatten all source data into a single list
 * - FIRST_AVAILABLE: Use the first source that has data
 * - LATEST: Use the most recent data from any source
 */
public interface MergeStrategy {

    /**
     * Strategy name (used for logging and serialization).
     */
    String name();

    /**
     * Checks if all required sources are resolved.
     * Different strategies have different requirements:
     * - QUEUE_1_TO_1: ALL sources must be resolved
     * - FIRST_AVAILABLE: Only ONE source needs data
     *
     * @param sourceNodeIds List of source node IDs to check
     * @param context Execution context with step outputs and state
     * @return true if merge can proceed
     */
    boolean canMerge(List<String> sourceNodeIds, ExecutionContext context);

    /**
     * Performs the merge operation.
     *
     * @param sourceNodeIds List of source node IDs
     * @param context Execution context with step outputs
     * @return Merged data map
     */
    Map<String, Object> merge(List<String> sourceNodeIds, ExecutionContext context);

    /**
     * Checks if the merge should be skipped entirely.
     * For example, if all sources are skipped/failed in QUEUE_1_TO_1.
     *
     * @param sourceNodeIds List of source node IDs
     * @param context Execution context
     * @return true if merge should be skipped
     */
    default boolean shouldSkip(List<String> sourceNodeIds, ExecutionContext context) {
        return false;
    }

    /**
     * Gets the reason for skipping (if shouldSkip returns true).
     */
    default String getSkipReason(List<String> sourceNodeIds, ExecutionContext context) {
        return "Merge skipped";
    }
}
