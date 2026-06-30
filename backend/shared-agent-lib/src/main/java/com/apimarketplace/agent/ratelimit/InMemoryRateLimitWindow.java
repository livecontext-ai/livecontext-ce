package com.apimarketplace.agent.ratelimit;

import java.util.LinkedList;

/**
 * In-memory sliding window backed by a LinkedList.
 * Suitable for single-instance deployments. Not shared across JVMs.
 *
 * <p>Thread safety: callers must synchronize externally (as ProviderRateLimiter does).</p>
 */
public class InMemoryRateLimitWindow implements RateLimitWindow {

    private final long windowSizeSeconds;
    private final LinkedList<Entry> entries = new LinkedList<>();
    private long lastAccessTime = System.currentTimeMillis();

    public InMemoryRateLimitWindow(int windowSizeSeconds) {
        this.windowSizeSeconds = windowSizeSeconds;
    }

    @Override
    public void add(long timestamp, int value) {
        entries.add(new Entry(timestamp, value));
        lastAccessTime = timestamp;
    }

    @Override
    public void cleanup(long cutoffTimestamp) {
        entries.removeIf(e -> e.timestamp < cutoffTimestamp);
        lastAccessTime = System.currentTimeMillis();
    }

    @Override
    public int getSum() {
        return entries.stream().mapToInt(e -> e.value).sum();
    }

    @Override
    public int getCount() {
        return entries.size();
    }

    @Override
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public long getOldestTimestamp() {
        return entries.isEmpty() ? 0 : entries.getFirst().timestamp;
    }

    @Override
    public long getLastAccessTime() {
        return lastAccessTime;
    }

    private record Entry(long timestamp, int value) {}
}
