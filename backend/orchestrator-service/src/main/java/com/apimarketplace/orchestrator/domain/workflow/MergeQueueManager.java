package com.apimarketplace.orchestrator.domain.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import com.apimarketplace.orchestrator.utils.WorkflowUtils;

/**
 * Manages merge queue states for workflow execution.
 * Handles QUEUE_1_TO_1 strategy for coherent merging (same item from all branches).
 */
public class MergeQueueManager {

    private final Map<String, MergeQueueState> mergeStates = new ConcurrentHashMap<>();

    public MergeQueueState getOrCreate(String mergeStepId, List<String> branches) {
        if (mergeStepId == null) {
            throw new IllegalArgumentException("mergeStepId cannot be null");
        }
        String normalized = WorkflowUtils.normalizeStepId(mergeStepId);
        String key = normalized != null ? normalized : mergeStepId;
        return mergeStates.computeIfAbsent(key, id -> new MergeQueueState(id, branches));
    }

    public MergeQueueState get(String mergeStepId) {
        if (mergeStepId == null) {
            return null;
        }
        String normalized = WorkflowUtils.normalizeStepId(mergeStepId);
        return mergeStates.get(normalized != null ? normalized : mergeStepId);
    }

    public Map<String, Object> getSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        mergeStates.forEach((key, state) -> snapshot.put(key, state.snapshot()));
        return snapshot;
    }

    public void restore(Map<String, ?> persistedStates) {
        mergeStates.clear();
        if (persistedStates == null || persistedStates.isEmpty()) {
            return;
        }
        for (Map.Entry<String, ?> entry : persistedStates.entrySet()) {
            String mergeId = entry.getKey();
            Object stateObj = entry.getValue();
            if (!(stateObj instanceof Map<?, ?> stateMap)) {
                continue;
            }
            MergeQueueState restored = MergeQueueState.fromSnapshot(mergeId, stateMap);
            if (restored != null) {
                mergeStates.put(mergeId, restored);
            }
        }
    }

    public void clear() {
        mergeStates.clear();
    }

    /**
     * State for a single merge node, tracking entries per item and branch.
     */
    public static final class MergeQueueState {
        private final String mergeId;
        private final Set<String> expectedBranches;
        private final Map<String, Map<String, MergeQueueEntry>> itemEntries;
        private final Set<String> mergedItems;
        private final AtomicInteger totalMerged;
        private final ReentrantLock lock;

        MergeQueueState(String mergeId, List<String> branches) {
            this.mergeId = mergeId;
            this.totalMerged = new AtomicInteger();
            this.lock = new ReentrantLock();
            this.itemEntries = new LinkedHashMap<>();
            this.mergedItems = new LinkedHashSet<>();
            this.expectedBranches = new LinkedHashSet<>();

            if (branches != null && !branches.isEmpty()) {
                for (String branch : branches) {
                    this.expectedBranches.add(normalizeBranchId(branch));
                }
            }
        }

        public MergeQueueResult enqueue(String branchId, MergeQueueEntry entry) {
            String normalizedBranch = normalizeBranchId(branchId);
            String itemId = entry.itemId();

            lock.lock();
            try {
                if (mergedItems.contains(itemId)) {
                    return MergeQueueResult.waiting(snapshotDepths(), totalMerged.get());
                }

                Map<String, MergeQueueEntry> branchEntries = itemEntries.computeIfAbsent(itemId, k -> new LinkedHashMap<>());
                branchEntries.put(normalizedBranch, entry);

                boolean allBranchesReady = expectedBranches.stream().allMatch(branchEntries::containsKey);

                if (!allBranchesReady) {
                    return MergeQueueResult.waiting(snapshotDepths(), totalMerged.get());
                }

                Map<String, MergeQueueEntry> mergedTuple = new LinkedHashMap<>(branchEntries);
                mergedItems.add(itemId);
                itemEntries.remove(itemId);

                int mergedCount = totalMerged.incrementAndGet();
                return MergeQueueResult.merged(mergedTuple, snapshotDepths(), mergedCount);
            } finally {
                lock.unlock();
            }
        }

        public boolean isItemReadyForMerge(String itemId) {
            lock.lock();
            try {
                if (mergedItems.contains(itemId)) {
                    return true;
                }
                Map<String, MergeQueueEntry> branchEntries = itemEntries.get(itemId);
                if (branchEntries == null) {
                    return false;
                }
                return expectedBranches.stream().allMatch(branchEntries::containsKey);
            } finally {
                lock.unlock();
            }
        }

        public Set<String> getPendingBranchesForItem(String itemId) {
            lock.lock();
            try {
                if (mergedItems.contains(itemId)) {
                    return Set.of();
                }
                Map<String, MergeQueueEntry> branchEntries = itemEntries.getOrDefault(itemId, Map.of());
                Set<String> pending = new LinkedHashSet<>(expectedBranches);
                pending.removeAll(branchEntries.keySet());
                return pending;
            } finally {
                lock.unlock();
            }
        }

        public MergeQueueResult forceMergeForItem(String itemId, String triggerId, int absoluteIndex, String tenantId) {
            lock.lock();
            try {
                if (mergedItems.contains(itemId)) {
                    return MergeQueueResult.waiting(snapshotDepths(), totalMerged.get());
                }

                Map<String, MergeQueueEntry> branchEntries = itemEntries.getOrDefault(itemId, new LinkedHashMap<>());
                Map<String, MergeQueueEntry> mergedTuple = new LinkedHashMap<>();

                for (String branch : expectedBranches) {
                    MergeQueueEntry entry = branchEntries.get(branch);
                    if (entry != null) {
                        mergedTuple.put(branch, entry);
                    } else {
                        Map<String, Object> skippedPayload = new HashMap<>();
                        skippedPayload.put("_skipped", true);
                        skippedPayload.put("_skipReason", "Branch not executed for item");
                        skippedPayload.put("_forceMerged", true);
                        mergedTuple.put(branch, new MergeQueueEntry(branch, itemId, triggerId, absoluteIndex, tenantId, skippedPayload));
                    }
                }

                mergedItems.add(itemId);
                itemEntries.remove(itemId);

                int mergedCount = totalMerged.incrementAndGet();
                return MergeQueueResult.merged(mergedTuple, snapshotDepths(), mergedCount);
            } finally {
                lock.unlock();
            }
        }

        public boolean isItemMerged(String itemId) {
            lock.lock();
            try {
                return mergedItems.contains(itemId);
            } finally {
                lock.unlock();
            }
        }

        public int getReceivedBranchesCount(String itemId) {
            lock.lock();
            try {
                Map<String, MergeQueueEntry> branchEntries = itemEntries.get(itemId);
                return branchEntries != null ? branchEntries.size() : 0;
            } finally {
                lock.unlock();
            }
        }

        public int getExpectedBranchesCount() {
            return expectedBranches.size();
        }

        public Map<String, Object> snapshot() {
            lock.lock();
            try {
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("mergeId", mergeId);
                snapshot.put("expectedBranches", new ArrayList<>(expectedBranches));
                snapshot.put("mergedCount", totalMerged.get());
                snapshot.put("mergedItems", new ArrayList<>(mergedItems));

                Map<String, Object> pendingItems = new LinkedHashMap<>();
                itemEntries.forEach((itemId, branches) -> {
                    Map<String, Object> branchData = new LinkedHashMap<>();
                    branches.forEach((branch, entry) -> branchData.put(branch, entry.toMap()));
                    pendingItems.put(itemId, branchData);
                });
                snapshot.put("pendingItems", pendingItems);
                snapshot.put("queueDepths", snapshotDepths());
                return snapshot;
            } finally {
                lock.unlock();
            }
        }

        static MergeQueueState fromSnapshot(String mergeId, Map<?, ?> snapshot) {
            if (mergeId == null || snapshot == null || snapshot.isEmpty()) {
                return null;
            }

            List<String> branches = new ArrayList<>();
            Object branchesObj = snapshot.get("expectedBranches");
            if (branchesObj instanceof Iterable<?> iterable) {
                for (Object b : iterable) {
                    if (b != null) branches.add(b.toString());
                }
            }

            if (branches.isEmpty()) {
                Object queuesObj = snapshot.get("queues");
                if (queuesObj instanceof Map<?, ?> queuesMap) {
                    for (Object key : queuesMap.keySet()) {
                        if (key != null) branches.add(key.toString());
                    }
                }
            }

            MergeQueueState state = new MergeQueueState(mergeId, branches);

            Object mergedItemsObj = snapshot.get("mergedItems");
            if (mergedItemsObj instanceof Iterable<?> iterable) {
                for (Object item : iterable) {
                    if (item != null) state.mergedItems.add(item.toString());
                }
            }

            Object pendingItemsObj = snapshot.get("pendingItems");
            if (pendingItemsObj instanceof Map<?, ?> pendingMap) {
                pendingMap.forEach((itemIdObj, branchesObj2) -> {
                    String itemId = itemIdObj != null ? itemIdObj.toString() : null;
                    if (itemId != null && branchesObj2 instanceof Map<?, ?> branchesMap) {
                        Map<String, MergeQueueEntry> branchEntries = state.itemEntries.computeIfAbsent(itemId, k -> new LinkedHashMap<>());
                        branchesMap.forEach((branchObj, entryObj) -> {
                            String branch = branchObj != null ? branchObj.toString() : null;
                            if (branch != null && entryObj instanceof Map<?, ?> entryMap) {
                                MergeQueueEntry entry = MergeQueueEntry.fromMap(entryMap);
                                if (entry != null) branchEntries.put(branch, entry);
                            }
                        });
                    }
                });
            }

            Object mergedCount = snapshot.get("mergedCount");
            if (mergedCount instanceof Number number) {
                state.totalMerged.set(number.intValue());
            }
            return state;
        }

        private static String normalizeBranchId(String branchId) {
            if (branchId == null) return "unknown";
            String normalized = WorkflowUtils.normalizeStepId(branchId);
            return normalized != null ? normalized : branchId;
        }

        private Map<String, Integer> snapshotDepths() {
            Map<String, Integer> depths = new LinkedHashMap<>();
            for (String branch : expectedBranches) {
                int receivedCount = 0;
                for (Map<String, MergeQueueEntry> branchEntries : itemEntries.values()) {
                    if (branchEntries.containsKey(branch)) receivedCount++;
                }
                depths.put(branch, receivedCount);
            }
            return depths;
        }
    }

    public static final class MergeQueueEntry {
        private final String branchId;
        private final String itemId;
        private final String triggerId;
        private final int absoluteIndex;
        private final String tenantId;
        private final Map<String, Object> payload;
        private final long enqueuedAt;

        public MergeQueueEntry(String branchId, String itemId, String triggerId, int absoluteIndex, String tenantId, Map<String, Object> payload) {
            this(branchId, itemId, triggerId, absoluteIndex, tenantId, payload, System.currentTimeMillis());
        }

        private MergeQueueEntry(String branchId, String itemId, String triggerId, int absoluteIndex, String tenantId, Map<String, Object> payload, long enqueuedAt) {
            this.branchId = branchId;
            this.itemId = itemId;
            this.triggerId = triggerId;
            this.absoluteIndex = absoluteIndex;
            this.tenantId = tenantId;
            this.payload = payload != null ? new HashMap<>(payload) : new HashMap<>();
            this.enqueuedAt = enqueuedAt;
        }

        public String branchId() { return branchId; }
        public String itemId() { return itemId; }
        public String triggerId() { return triggerId; }
        public int absoluteIndex() { return absoluteIndex; }
        public String tenantId() { return tenantId; }
        public Map<String, Object> payload() { return Collections.unmodifiableMap(payload); }
        public long enqueuedAt() { return enqueuedAt; }

        Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("branchId", branchId);
            data.put("itemId", itemId);
            data.put("triggerId", triggerId);
            data.put("absoluteIndex", absoluteIndex);
            data.put("tenantId", tenantId);
            data.put("payload", new HashMap<>(payload));
            data.put("enqueuedAt", enqueuedAt);
            return data;
        }

        static MergeQueueEntry fromMap(Map<?, ?> source) {
            if (source == null || source.isEmpty()) return null;
            String branchId = asString(source.get("branchId"));
            String itemId = asString(source.get("itemId"));
            String triggerId = asString(source.get("triggerId"));
            Integer absoluteIndex = asInteger(source.get("absoluteIndex"));
            String tenantId = asString(source.get("tenantId"));
            Map<String, Object> payload = new HashMap<>();
            Object payloadObj = source.get("payload");
            if (payloadObj instanceof Map<?, ?> map) {
                map.forEach((key, value) -> { if (key != null) payload.put(key.toString(), value); });
            }
            if (itemId == null) return null;
            long enqueuedAt = System.currentTimeMillis();
            Object enqueuedAtObj = source.get("enqueuedAt");
            if (enqueuedAtObj instanceof Number number) {
                enqueuedAt = number.longValue();
            } else if (enqueuedAtObj instanceof String str) {
                try { enqueuedAt = Long.parseLong(str); } catch (NumberFormatException ignored) {}
            }
            return new MergeQueueEntry(branchId, itemId, triggerId != null ? triggerId : "", absoluteIndex != null ? absoluteIndex : 0, tenantId != null ? tenantId : "", payload, enqueuedAt);
        }

        private static String asString(Object value) { return value != null ? value.toString() : null; }

        private static Integer asInteger(Object value) {
            if (value instanceof Number number) return number.intValue();
            if (value instanceof String str) {
                try { return Integer.parseInt(str); } catch (NumberFormatException ignored) { return null; }
            }
            return null;
        }
    }

    public static final class MergeQueueResult {
        private final boolean merged;
        private final Map<String, MergeQueueEntry> entries;
        private final Map<String, Integer> queueDepths;
        private final int mergedCount;

        private MergeQueueResult(boolean merged, Map<String, MergeQueueEntry> entries, Map<String, Integer> queueDepths, int mergedCount) {
            this.merged = merged;
            this.entries = entries != null ? new LinkedHashMap<>(entries) : Map.of();
            this.queueDepths = queueDepths != null ? new LinkedHashMap<>(queueDepths) : Map.of();
            this.mergedCount = mergedCount;
        }

        public static MergeQueueResult waiting(Map<String, Integer> queueDepths, int mergedCount) {
            return new MergeQueueResult(false, null, queueDepths, mergedCount);
        }

        public static MergeQueueResult merged(Map<String, MergeQueueEntry> entries, Map<String, Integer> queueDepths, int mergedCount) {
            return new MergeQueueResult(true, entries, queueDepths, mergedCount);
        }

        public boolean merged() { return merged; }
        public Map<String, MergeQueueEntry> entries() { return Collections.unmodifiableMap(entries); }
        public Map<String, Integer> queueDepths() { return Collections.unmodifiableMap(queueDepths); }
        public int mergedCount() { return mergedCount; }
    }
}
