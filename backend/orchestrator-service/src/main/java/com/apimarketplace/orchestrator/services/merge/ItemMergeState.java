package com.apimarketplace.orchestrator.services.merge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mutable state for a merge point scoped by parent item.
 *
 * <p><b>Thread-safety contract:</b> All mutations ({@link #withEntry}, {@link #withExpectedCount})
 * MUST be called under the per-{@code MergeKey} lock held by {@link ItemMergeCollector}.
 * Lock-free readers (e.g. {@link ItemAwareMergeStrategy}) call only query methods, which
 * return defensive copies or compute values on-the-fly from the internal collections.
 *
 * <p>This class was originally immutable (copy-on-write), but for a 539-item split the
 * O(N) deep copy on every {@code withEntry()} call created ~539 intermediate copies, with
 * the last ones being ~800 KB Humongous objects in G1GC.  The internal collections are now
 * mutated in-place; getters still return copies so external code cannot corrupt state.
 *
 * <p>Tracks all entries that need to be collected before merge can proceed.
 * Each source node can contribute multiple entries (one per Split child).
 *
 * <p>Example state for merge point "merge_1" with scope "0":
 * <pre>
 * sources: ["mcp:normal", "core:process"]
 * expectedCounts: {"mcp:normal": 1, "core:process": 3}
 * entries:
 *   "mcp:normal": [{itemId: "0", data: {...}}]
 *   "core:process": [
 *     {itemId: "0.1", data: {...}},
 *     {itemId: "0.2", data: {...}},
 *     {itemId: "0.3", data: {...}}
 *   ]
 * </pre>
 */
public final class ItemMergeState {

    private final String mergePointId;
    private final String scope;  // Parent itemId (e.g., "0", "0.1")
    private final Set<String> sourceNodeIds;
    // Mutable - guarded by ItemMergeCollector's per-MergeKey lock
    private final Map<String, Integer> expectedCounts;          // sourceNodeId → expected count
    private final Map<String, List<ItemMergeEntry>> entriesBySource;  // sourceNodeId → entries
    private final Map<String, ItemMergeEntry> entriesByItemId;  // compositeKey → entry (quick lookup)

    private ItemMergeState(
            String mergePointId,
            String scope,
            Set<String> sourceNodeIds,
            Map<String, Integer> expectedCounts,
            Map<String, List<ItemMergeEntry>> entriesBySource,
            Map<String, ItemMergeEntry> entriesByItemId) {
        this.mergePointId = mergePointId;
        this.scope = scope;
        // sourceNodeIds is set once at creation and never mutated - safe to wrap unmodifiable
        this.sourceNodeIds = Collections.unmodifiableSet(new HashSet<>(sourceNodeIds));
        // Internal maps are mutable; external access goes through defensive-copy getters
        this.expectedCounts = expectedCounts;
        this.entriesBySource = entriesBySource;
        this.entriesByItemId = entriesByItemId;
    }

    /**
     * Creates a new merge state for a merge point.
     *
     * @param mergePointId The merge point ID
     * @param scope The parent item scope (e.g., "0")
     * @param sourceNodeIds The source nodes that contribute to this merge
     * @return A new ItemMergeState
     */
    public static ItemMergeState create(String mergePointId, String scope, Set<String> sourceNodeIds) {
        Map<String, Integer> expectedCounts = new HashMap<>();
        Map<String, List<ItemMergeEntry>> entriesBySource = new HashMap<>();

        for (String sourceId : sourceNodeIds) {
            // Default expected count is 1 (for normal steps)
            // Split sources will call setExpectedCount() when they spawn items
            expectedCounts.put(sourceId, 1);
            entriesBySource.put(sourceId, new ArrayList<>());
        }

        return new ItemMergeState(
            mergePointId, scope, sourceNodeIds, expectedCounts,
            entriesBySource, new HashMap<>()
        );
    }

    /**
     * Updates the expected count for a source in-place.
     * Called when Split evaluates its list and knows how many items it will spawn.
     *
     * <p><b>Must be called under the per-MergeKey lock.</b>
     *
     * @param sourceNodeId The source node ID
     * @param count The expected number of entries from this source
     * @return {@code this} (for call-chain compatibility with the old copy-on-write API)
     */
    public ItemMergeState withExpectedCount(String sourceNodeId, int count) {
        expectedCounts.put(sourceNodeId, count);
        return this;
    }

    /**
     * Adds an entry in-place.
     *
     * <p><b>Must be called under the per-MergeKey lock.</b>
     *
     * @param entry The entry to add
     * @return {@code this} (for call-chain compatibility with the old copy-on-write API)
     */
    public ItemMergeState withEntry(ItemMergeEntry entry) {
        // Validate entry belongs to this scope
        String entryScope = entry.getScope();
        if (!scope.equals(entryScope) && !entry.itemId().equals(scope)) {
            throw new IllegalArgumentException(
                "Entry itemId '" + entry.itemId() + "' does not belong to scope '" + scope + "'"
            );
        }

        // Mutate in-place - caller holds the per-MergeKey lock
        entriesBySource.computeIfAbsent(entry.sourceNodeId(), k -> new ArrayList<>())
            .add(entry);
        entriesByItemId.put(entry.itemId() + ":" + entry.sourceNodeId(), entry);

        return this;
    }

    /**
     * Returns a structurally independent snapshot safe for lock-free readers.
     * Must be called under the per-MergeKey lock.
     */
    public ItemMergeState toSnapshot() {
        Map<String, List<ItemMergeEntry>> entriesCopy = new HashMap<>(entriesBySource.size());
        for (Map.Entry<String, List<ItemMergeEntry>> e : entriesBySource.entrySet()) {
            entriesCopy.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
        }
        return new ItemMergeState(
            mergePointId,
            scope,
            sourceNodeIds,
            Collections.unmodifiableMap(new HashMap<>(expectedCounts)),
            Collections.unmodifiableMap(entriesCopy),
            Collections.unmodifiableMap(new HashMap<>(entriesByItemId))
        );
    }

    /**
     * Checks if all expected entries have been received.
     */
    public boolean isComplete() {
        for (String sourceId : sourceNodeIds) {
            int expected = expectedCounts.getOrDefault(sourceId, 1);
            int received = entriesBySource.getOrDefault(sourceId, List.of()).size();
            if (received < expected) {
                return false;
            }
        }
        return true;
    }

    /**
     * Gets the completion progress as a fraction.
     *
     * @return Progress between 0.0 and 1.0
     */
    public double getProgress() {
        int totalExpected = 0;
        int totalReceived = 0;

        for (String sourceId : sourceNodeIds) {
            totalExpected += expectedCounts.getOrDefault(sourceId, 1);
            totalReceived += entriesBySource.getOrDefault(sourceId, List.of()).size();
        }

        return totalExpected > 0 ? (double) totalReceived / totalExpected : 1.0;
    }

    /**
     * Gets detailed progress information.
     */
    public Map<String, SourceProgress> getDetailedProgress() {
        Map<String, SourceProgress> progress = new HashMap<>();

        for (String sourceId : sourceNodeIds) {
            int expected = expectedCounts.getOrDefault(sourceId, 1);
            List<ItemMergeEntry> entries = entriesBySource.getOrDefault(sourceId, List.of());
            int received = entries.size();
            int success = (int) entries.stream().filter(ItemMergeEntry::isSuccess).count();
            int failed = (int) entries.stream().filter(ItemMergeEntry::isFailed).count();
            int skipped = (int) entries.stream().filter(ItemMergeEntry::isSkipped).count();

            progress.put(sourceId, new SourceProgress(expected, received, success, failed, skipped));
        }

        return progress;
    }

    /**
     * Gets all entries for a source node, sorted by item index.
     */
    public List<ItemMergeEntry> getEntriesForSource(String sourceNodeId) {
        List<ItemMergeEntry> entries = entriesBySource.getOrDefault(sourceNodeId, List.of());
        // Sort by itemIndex for consistent ordering
        return entries.stream()
            .sorted(Comparator.comparingInt(ItemMergeEntry::itemIndex))
            .toList();
    }

    /**
     * Gets all entries across all sources, grouped by itemId.
     * For Split items, groups results from all sources for the same item.
     */
    public Map<String, List<ItemMergeEntry>> getEntriesGroupedByItem() {
        Map<String, List<ItemMergeEntry>> grouped = new HashMap<>();

        for (List<ItemMergeEntry> entries : entriesBySource.values()) {
            for (ItemMergeEntry entry : entries) {
                grouped.computeIfAbsent(entry.itemId(), k -> new ArrayList<>()).add(entry);
            }
        }

        return grouped;
    }

    /**
     * Gets Split results as an ordered list.
     * Only includes entries from Split children (items with dots in ID).
     */
    public List<ItemMergeEntry> getSplitResults() {
        List<ItemMergeEntry> splitEntries = new ArrayList<>();

        for (List<ItemMergeEntry> entries : entriesBySource.values()) {
            for (ItemMergeEntry entry : entries) {
                if (entry.isSplitChild()) {
                    splitEntries.add(entry);
                }
            }
        }

        // Sort by itemIndex for consistent ordering
        splitEntries.sort(Comparator.comparingInt(ItemMergeEntry::itemIndex));
        return splitEntries;
    }

    /**
     * Gets entries from normal (non-Split) sources.
     */
    public List<ItemMergeEntry> getNormalResults() {
        List<ItemMergeEntry> normalEntries = new ArrayList<>();

        for (List<ItemMergeEntry> entries : entriesBySource.values()) {
            for (ItemMergeEntry entry : entries) {
                if (!entry.isSplitChild()) {
                    normalEntries.add(entry);
                }
            }
        }

        return normalEntries;
    }

    /**
     * Checks if any entry failed.
     */
    public boolean hasFailures() {
        for (List<ItemMergeEntry> entries : entriesBySource.values()) {
            for (ItemMergeEntry entry : entries) {
                if (entry.isFailed()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Counts total successful entries.
     */
    public int getSuccessCount() {
        int count = 0;
        for (List<ItemMergeEntry> entries : entriesBySource.values()) {
            count += entries.stream().filter(ItemMergeEntry::isSuccess).count();
        }
        return count;
    }

    /**
     * Counts total failed entries.
     */
    public int getFailedCount() {
        int count = 0;
        for (List<ItemMergeEntry> entries : entriesBySource.values()) {
            count += entries.stream().filter(ItemMergeEntry::isFailed).count();
        }
        return count;
    }

    // === Getters ===

    public String getMergePointId() {
        return mergePointId;
    }

    public String getScope() {
        return scope;
    }

    public Set<String> getSourceNodeIds() {
        return sourceNodeIds;
    }

    /**
     * Returns a snapshot copy of expected counts.
     * Safe for lock-free readers.
     */
    public Map<String, Integer> getExpectedCounts() {
        return Collections.unmodifiableMap(new HashMap<>(expectedCounts));
    }

    /**
     * Progress information for a single source.
     */
    public record SourceProgress(
        int expected,
        int received,
        int success,
        int failed,
        int skipped
    ) {
        public boolean isComplete() {
            return received >= expected;
        }

        public double getProgress() {
            return expected > 0 ? (double) received / expected : 1.0;
        }
    }
}
