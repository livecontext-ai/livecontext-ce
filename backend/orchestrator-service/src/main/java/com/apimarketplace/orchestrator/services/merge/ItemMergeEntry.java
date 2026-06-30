package com.apimarketplace.orchestrator.services.merge;

import java.time.Instant;
import java.util.Map;

/**
 * Represents a single result entry for merge collection.
 *
 * <p>Each entry captures the output of a node execution for a specific item.
 * Entries are collected by the merge system and grouped by scope.
 *
 * @param itemId The item ID (e.g., "0", "0.1", "0.1.2")
 * @param itemIndex The numeric index for DB storage
 * @param sourceNodeId The node that produced this result (e.g., "mcp:process")
 * @param data The output data from the node
 * @param status The completion status
 * @param errorMessage Error message if failed, null otherwise
 * @param completedAt When this entry was recorded
 */
public record ItemMergeEntry(
    String itemId,
    int itemIndex,
    String sourceNodeId,
    Map<String, Object> data,
    Status status,
    String errorMessage,
    Instant completedAt
) {
    /**
     * Completion status for a merge entry.
     */
    public enum Status {
        /** Node completed successfully */
        SUCCESS,
        /** Node execution failed */
        FAILED,
        /** Node was skipped (condition not met) */
        SKIPPED
    }

    /**
     * Creates a successful entry.
     */
    public static ItemMergeEntry success(String itemId, int itemIndex, String sourceNodeId, Map<String, Object> data) {
        return new ItemMergeEntry(
            itemId, itemIndex, sourceNodeId, data,
            Status.SUCCESS, null, Instant.now()
        );
    }

    /**
     * Creates a failed entry.
     */
    public static ItemMergeEntry failed(String itemId, int itemIndex, String sourceNodeId, String errorMessage) {
        return new ItemMergeEntry(
            itemId, itemIndex, sourceNodeId, Map.of(),
            Status.FAILED, errorMessage, Instant.now()
        );
    }

    /**
     * Creates a skipped entry.
     */
    public static ItemMergeEntry skipped(String itemId, int itemIndex, String sourceNodeId, String reason) {
        return new ItemMergeEntry(
            itemId, itemIndex, sourceNodeId, Map.of(),
            Status.SKIPPED, reason, Instant.now()
        );
    }

    /**
     * Checks if this entry represents a successful completion.
     */
    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    /**
     * Checks if this entry represents a failure.
     */
    public boolean isFailed() {
        return status == Status.FAILED;
    }

    /**
     * Checks if this entry was skipped.
     */
    public boolean isSkipped() {
        return status == Status.SKIPPED;
    }

    /**
     * Checks if this entry is resolved (success, failed, or skipped).
     */
    public boolean isResolved() {
        return status != null;
    }

    /**
     * Gets the scope this entry belongs to (parent itemId).
     */
    public String getScope() {
        return ItemMergeScope.getParentScope(itemId);
    }

    /**
     * Checks if this entry is a Split child.
     */
    public boolean isSplitChild() {
        return ItemMergeScope.isSplitChild(itemId);
    }
}
