package com.apimarketplace.orchestrator.execution.v2.state;

import java.util.List;

/**
 * Immutable state for Split execution.
 * Tracks the list of items and current processing index.
 *
 * Created on first split execution, updated after each item completes.
 */
public record SplitState(
    List<Object> items,           // The list of items to iterate over
    int currentIndex,             // Current item index (0-based)
    int maxItems,                 // Maximum items to process
    String splitStrategy,         // "stop-on-error" or "continue-anyway"
    boolean terminated            // True if split has exited
) {

    /**
     * Create initial split state.
     */
    public static SplitState create(List<Object> items, int maxItems, String splitStrategy) {
        return new SplitState(items, 0, maxItems, splitStrategy, false);
    }

    /**
     * Increment the index (move to next item).
     */
    public SplitState incrementIndex() {
        return new SplitState(items, currentIndex + 1, maxItems, splitStrategy, terminated);
    }

    /**
     * Mark the split as terminated.
     */
    public SplitState terminate(String reason) {
        return new SplitState(items, currentIndex, maxItems, splitStrategy, true);
    }

    /**
     * Check if there are more items to process.
     */
    public boolean hasMoreItems() {
        return currentIndex < items.size() && currentIndex < maxItems && !terminated;
    }

    /**
     * Get the current item being processed.
     */
    public Object getCurrentItem() {
        if (currentIndex >= 0 && currentIndex < items.size()) {
            return items.get(currentIndex);
        }
        return null;
    }

    /**
     * Get total item count.
     */
    public int getItemCount() {
        return Math.min(items.size(), maxItems);
    }
}
