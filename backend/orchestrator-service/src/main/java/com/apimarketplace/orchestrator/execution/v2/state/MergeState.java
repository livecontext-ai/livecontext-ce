package com.apimarketplace.orchestrator.execution.v2.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable merge state.
 * Tracks items collected from each source branch.
 *
 * Thread-safe for concurrent item collection.
 */
public class MergeState {

    private final Map<String, List<Object>> sourceItems;  // sourceNodeId -> items

    public MergeState() {
        this.sourceItems = new ConcurrentHashMap<>();
    }

    private MergeState(Map<String, List<Object>> sourceItems) {
        this.sourceItems = new ConcurrentHashMap<>(sourceItems);
    }

    /**
     * Adds an item from a source branch.
     */
    public MergeState addItem(String sourceNodeId, Object item) {
        Map<String, List<Object>> newItems = new HashMap<>(sourceItems);

        List<Object> items = newItems.computeIfAbsent(sourceNodeId, k -> new ArrayList<>());
        items.add(item);

        return new MergeState(newItems);
    }

    /**
     * Gets all items from a source.
     */
    public List<Object> getItems(String sourceNodeId) {
        return sourceItems.getOrDefault(sourceNodeId, List.of());
    }

    /**
     * Gets all items from all sources.
     */
    public Map<String, List<Object>> getAllItems() {
        return new HashMap<>(sourceItems);
    }

    /**
     * Checks if all sources have items.
     */
    public boolean hasItemsFromAllSources(List<String> expectedSources) {
        return expectedSources.stream()
            .allMatch(sourceId -> sourceItems.containsKey(sourceId)
                && !sourceItems.get(sourceId).isEmpty());
    }

    /**
     * Gets total item count across all sources.
     */
    public int getTotalItemCount() {
        return sourceItems.values().stream()
            .mapToInt(List::size)
            .sum();
    }
}
