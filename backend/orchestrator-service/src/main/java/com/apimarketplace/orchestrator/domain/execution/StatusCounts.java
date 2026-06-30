package com.apimarketplace.orchestrator.domain.execution;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Aggregated status counts for a node or edge across all items.
 * Thread-safe for concurrent updates.
 */
public final class StatusCounts {

    private final AtomicInteger running;
    private final AtomicInteger completed;
    private final AtomicInteger failed;
    private final AtomicInteger skipped;
    private final AtomicInteger total;

    public StatusCounts() {
        this.running = new AtomicInteger(0);
        this.completed = new AtomicInteger(0);
        this.failed = new AtomicInteger(0);
        this.skipped = new AtomicInteger(0);
        this.total = new AtomicInteger(0);
    }

    public StatusCounts(int running, int completed, int failed, int skipped, int total) {
        this.running = new AtomicInteger(running);
        this.completed = new AtomicInteger(completed);
        this.failed = new AtomicInteger(failed);
        this.skipped = new AtomicInteger(skipped);
        this.total = new AtomicInteger(total);
    }

    // --- Increment methods ---

    public void incrementRunning() {
        running.incrementAndGet();
    }

    public void decrementRunning() {
        running.decrementAndGet();
    }

    public void incrementCompleted() {
        completed.incrementAndGet();
    }

    public void decrementCompleted() {
        completed.decrementAndGet();
    }

    public void incrementFailed() {
        failed.incrementAndGet();
    }

    public void decrementFailed() {
        failed.decrementAndGet();
    }

    public void incrementSkipped() {
        skipped.incrementAndGet();
    }

    public void decrementSkipped() {
        skipped.decrementAndGet();
    }

    public void setTotal(int value) {
        total.set(value);
    }

    public void incrementTotal() {
        total.incrementAndGet();
    }

    // --- Getters ---

    public int getRunning() {
        return running.get();
    }

    public int getCompleted() {
        return completed.get();
    }

    public int getFailed() {
        return failed.get();
    }

    public int getSkipped() {
        return skipped.get();
    }

    public int getTotal() {
        return total.get();
    }

    public int getProcessed() {
        return completed.get() + failed.get() + skipped.get();
    }

    // --- Status queries ---

    /**
     * Returns true if all items have been processed (no running).
     */
    public boolean isComplete() {
        return running.get() == 0 && getProcessed() == total.get() && total.get() > 0;
    }

    /**
     * Returns true if there's at least one completed.
     */
    public boolean hasCompleted() {
        return completed.get() > 0;
    }

    /**
     * Returns true if all processed items are skipped.
     */
    public boolean isAllSkipped() {
        return getProcessed() > 0 && completed.get() == 0 && failed.get() == 0;
    }

    /**
     * Returns the aggregate status based on counts.
     */
    public NodeStatus getAggregateStatus() {
        if (running.get() > 0) {
            return NodeStatus.RUNNING;
        }
        if (failed.get() > 0) {
            return NodeStatus.FAILED;
        }
        if (completed.get() > 0) {
            return NodeStatus.COMPLETED;
        }
        if (skipped.get() > 0) {
            return NodeStatus.SKIPPED;
        }
        return NodeStatus.PENDING;
    }

    // --- Conversion ---

    /**
     * Convert to map for streaming event payload.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("running", running.get());
        map.put("completed", completed.get());
        map.put("failed", failed.get());
        map.put("skipped", skipped.get());
        map.put("total", total.get());
        return map;
    }

    /**
     * Create from map (e.g., from persisted data).
     */
    public static StatusCounts fromMap(Map<String, Object> map) {
        if (map == null) {
            return new StatusCounts();
        }
        // Read "completed" with fallback to "success" for backward compatibility
        int completedVal = toInt(map.get("completed"));
        if (completedVal == 0 && map.containsKey("success")) {
            completedVal = toInt(map.get("success"));
        }
        // Read "failed" with fallback to "failure" for backward compatibility
        int failedVal = toInt(map.get("failed"));
        if (failedVal == 0 && map.containsKey("failure")) {
            failedVal = toInt(map.get("failure"));
        }
        return new StatusCounts(
            toInt(map.get("running")),
            completedVal,
            failedVal,
            toInt(map.get("skipped")),
            toInt(map.get("total"))
        );
    }

    private static int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Create a copy of this StatusCounts (snapshot).
     */
    public StatusCounts snapshot() {
        return new StatusCounts(
            running.get(),
            completed.get(),
            failed.get(),
            skipped.get(),
            total.get()
        );
    }

    @Override
    public String toString() {
        return String.format("StatusCounts{running=%d, completed=%d, failed=%d, skipped=%d, total=%d}",
            running.get(), completed.get(), failed.get(), skipped.get(), total.get());
    }
}
