package com.apimarketplace.orchestrator.services.merge;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Result of a merge operation.
 *
 * <p>Contains the merged data and metadata about the merge process.
 */
public record MergeResult(
    Status status,
    String mergePointId,
    String scope,
    MergedData data,
    MergeMetadata metadata
) {
    /**
     * Merge status.
     */
    public enum Status {
        /** Merge completed successfully with all items */
        COMPLETE,
        /** Merge completed with some failed/skipped items */
        PARTIAL,
        /** Merge is still waiting for items */
        WAITING,
        /** Merge failed completely */
        FAILED
    }

    /**
     * Creates a successful complete merge result.
     */
    public static MergeResult complete(String mergePointId, String scope, MergedData data, MergeMetadata metadata) {
        return new MergeResult(Status.COMPLETE, mergePointId, scope, data, metadata);
    }

    /**
     * Creates a partial merge result (some items failed/skipped).
     */
    public static MergeResult partial(String mergePointId, String scope, MergedData data, MergeMetadata metadata) {
        return new MergeResult(Status.PARTIAL, mergePointId, scope, data, metadata);
    }

    /**
     * Creates a waiting result (not all items received yet).
     */
    public static MergeResult waiting(String mergePointId, String scope, MergeMetadata metadata) {
        return new MergeResult(Status.WAITING, mergePointId, scope, null, metadata);
    }

    /**
     * Creates a failed merge result.
     */
    public static MergeResult failed(String mergePointId, String scope, String errorMessage) {
        MergeMetadata metadata = new MergeMetadata(
            0, 0, 0, 0, 0.0, errorMessage, Instant.now()
        );
        return new MergeResult(Status.FAILED, mergePointId, scope, null, metadata);
    }

    /**
     * Checks if merge is complete and data is available.
     */
    public boolean isReady() {
        return status == Status.COMPLETE || status == Status.PARTIAL;
    }

    /**
     * Checks if merge is still waiting for items.
     */
    public boolean isWaiting() {
        return status == Status.WAITING;
    }

    /**
     * The merged data from all sources.
     *
     * @param normalResults Results from normal (non-Split) sources
     * @param splitResults Results from Split sources, ordered by index
     * @param combined All results combined (normal first, then split in order)
     * @param bySource Results grouped by source node ID
     * @param byItem Results grouped by item ID (for Split items)
     */
    public record MergedData(
        List<Map<String, Object>> normalResults,
        List<SplitItemResult> splitResults,
        List<Map<String, Object>> combined,
        Map<String, List<Map<String, Object>>> bySource,
        Map<String, Map<String, Object>> byItem
    ) {
        /**
         * Creates an empty merged data.
         */
        public static MergedData empty() {
            return new MergedData(
                List.of(), List.of(), List.of(), Map.of(), Map.of()
            );
        }

        /**
         * Gets the total number of results.
         */
        public int getTotalCount() {
            return combined.size();
        }

        /**
         * Gets the number of Split results.
         */
        public int getSplitCount() {
            return splitResults.size();
        }

        /**
         * Checks if there are any Split results.
         */
        public boolean hasSplitResults() {
            return !splitResults.isEmpty();
        }
    }

    /**
     * A single Split item result with its metadata.
     *
     * @param itemId The item ID (e.g., "0.1", "0.2")
     * @param index The item index (0-based)
     * @param data The result data
     * @param success Whether this item succeeded
     * @param sourceNodeId The source node that produced this result
     */
    public record SplitItemResult(
        String itemId,
        int index,
        Map<String, Object> data,
        boolean success,
        String sourceNodeId
    ) {}

    /**
     * Metadata about the merge operation.
     *
     * @param totalExpected Total expected entries
     * @param totalReceived Total received entries
     * @param successCount Number of successful entries
     * @param failedCount Number of failed entries
     * @param progress Completion progress (0.0 to 1.0)
     * @param errorMessage Error message if failed
     * @param completedAt When merge completed
     */
    public record MergeMetadata(
        int totalExpected,
        int totalReceived,
        int successCount,
        int failedCount,
        double progress,
        String errorMessage,
        Instant completedAt
    ) {
        /**
         * Creates metadata from an ItemMergeState.
         */
        public static MergeMetadata fromState(ItemMergeState state) {
            int totalExpected = state.getExpectedCounts().values().stream()
                .mapToInt(Integer::intValue).sum();
            int totalReceived = state.getDetailedProgress().values().stream()
                .mapToInt(ItemMergeState.SourceProgress::received).sum();

            return new MergeMetadata(
                totalExpected,
                totalReceived,
                state.getSuccessCount(),
                state.getFailedCount(),
                state.getProgress(),
                null,
                Instant.now()
            );
        }
    }
}
