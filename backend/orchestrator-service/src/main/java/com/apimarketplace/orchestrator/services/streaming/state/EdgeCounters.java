package com.apimarketplace.orchestrator.services.streaming.state;

import com.apimarketplace.orchestrator.services.streaming.events.EdgeLifecycle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks edge traversal counts with deduplication per (itemIndex, iteration) combination.
 * This prevents over-counting when the same edge is traversed multiple times
 * for the same item in the same iteration, while correctly counting
 * different iterations of loop internal edges.
 */
final class EdgeCounters {

    private static final Logger log = LoggerFactory.getLogger(EdgeCounters.class);

    private final String from;
    private final String to;

    // Track the final status per item to avoid double counting
    // Key: String.valueOf(itemIndex) + ":" + iteration
    private final ConcurrentHashMap<String, EdgeLifecycle> itemStatuses = new ConcurrentHashMap<>();

    EdgeCounters(String from, String to) {
        this.from = from;
        this.to = to;
    }

    void applyLifecycle(EdgeLifecycle lifecycle) {
        // Legacy: no itemIndex provided, ignore (node-level events are not counted)
        applyLifecycleForItem(lifecycle, null, null);
    }

    void applyLifecycleForItem(EdgeLifecycle lifecycle, Integer itemIndex) {
        applyLifecycleForItem(lifecycle, itemIndex, null);
    }

    void applyLifecycleForItem(EdgeLifecycle lifecycle, Integer itemIndex, Integer iteration) {
        if (lifecycle == null || lifecycle == EdgeLifecycle.REGISTERED) {
            return;
        }

        // For non-split workflows, itemIndex may be null - treat as single item (index 0)
        // Negative itemIndex indicates synthetic pre-populated counts - skip those
        int effectiveItemIndex = (itemIndex == null) ? 0 : itemIndex;
        if (effectiveItemIndex < 0 && effectiveItemIndex > -1000000) {
            // Skip truly negative indices (but allow synthetic pre-populated ones < -1000000)
            return;
        }

        // Use item:iteration key to properly count while loop iterations
        int iter = iteration != null ? iteration : 0;
        String key = effectiveItemIndex + ":" + iter;

        // Deduplicate by tracking per item
        itemStatuses.compute(key, (k, previousStatus) -> {
            if (previousStatus == null) {
                return lifecycle;
            }

            // Keep the most "final" status: COMPLETED/SKIPPED > RUNNING
            if (previousStatus == EdgeLifecycle.RUNNING) {
                return lifecycle;
            }

            // COMPLETED wins over SKIPPED (actual execution > predicted skip)
            if (lifecycle == EdgeLifecycle.COMPLETED && previousStatus == EdgeLifecycle.SKIPPED) {
                return EdgeLifecycle.COMPLETED;
            }

            // Already have a terminal status - keep it
            return previousStatus;
        });
    }

    /**
     * Pre-populate itemStatuses with accumulated counts from database.
     * Uses synthetic item indices similar to NodeEventStore.
     *
     * @param counts Map with keys: "completed", "skipped"
     */
    void prePopulateCounts(Map<String, Integer> counts) {
        int completedCount = getCountValue(counts, "completed", "success", "SUCCESS");
        int skippedCount = getCountValue(counts, "skipped", "SKIPPED");

        // Use synthetic item indices starting from -1000000 to avoid conflicts with real items
        int syntheticBase = -1000000;

        for (int i = 0; i < completedCount; i++) {
            String key = (syntheticBase - i) + ":0";
            itemStatuses.put(key, EdgeLifecycle.COMPLETED);
        }

        for (int i = 0; i < skippedCount; i++) {
            String key = (syntheticBase - completedCount - i) + ":0";
            itemStatuses.put(key, EdgeLifecycle.SKIPPED);
        }
    }

    private int getCountValue(Map<String, Integer> counts, String... keys) {
        for (String key : keys) {
            Integer value = counts.get(key);
            if (value != null && value > 0) {
                return value;
            }
        }
        return 0;
    }

    Map<String, Object> toPayload(String edgeId) {
        // Count from itemStatuses only (no longer using *NoItem legacy counters)
        long runningCount = 0;
        long completedCount = 0;
        long skippedCount = 0;

        for (EdgeLifecycle status : itemStatuses.values()) {
            switch (status) {
                case RUNNING -> runningCount++;
                case COMPLETED -> completedCount++;
                case SKIPPED -> skippedCount++;
                default -> {}
            }
        }

        log.info("[EdgeCounters] toPayload edgeId={} from={} to={} itemStatuses.size={} completed={} running={} skipped={} | itemStatuses.keys={}",
                edgeId, from, to, itemStatuses.size(), completedCount, runningCount, skippedCount, itemStatuses.keySet());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", edgeId);
        if (from != null) {
            payload.put("from", from);
        }
        if (to != null) {
            payload.put("to", to);
        }
        payload.put("running", runningCount);
        payload.put("completed", completedCount);
        payload.put("skipped", skippedCount);

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("RUNNING", runningCount);
        counts.put("COMPLETED", completedCount);
        counts.put("FAILED", 0L);
        counts.put("SKIPPED", skippedCount);
        long processed = completedCount + skippedCount;
        counts.put("PROCESSED", processed);
        counts.put("TOTAL", processed + runningCount);
        payload.put("statusCounts", counts);
        return payload;
    }
}
