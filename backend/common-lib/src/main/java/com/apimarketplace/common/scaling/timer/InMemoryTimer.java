package com.apimarketplace.common.scaling.timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * In-memory implementation of {@link DistributedTimer}.
 * Uses a ScheduledExecutorService to manage timer callbacks.
 * Implements {@link AutoCloseable} for resource cleanup on shutdown.
 */
public class InMemoryTimer implements DistributedTimer, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(InMemoryTimer.class);

    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();

    public InMemoryTimer() {
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "distributed-timer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Constructor for testing with a custom scheduler.
     */
    InMemoryTimer(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public void schedule(String timerId, Duration delay, Runnable callback) {
        // Cancel any existing timer with the same ID
        cancel(timerId);

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                callback.run();
            } catch (Exception e) {
                log.error("Timer '{}' callback failed: {}", timerId, e.getMessage(), e);
            } finally {
                timers.remove(timerId);
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);

        timers.put(timerId, future);
        log.debug("Scheduled timer '{}' with delay {}ms", timerId, delay.toMillis());
    }

    @Override
    public boolean cancel(String timerId) {
        ScheduledFuture<?> future = timers.remove(timerId);
        if (future != null) {
            boolean cancelled = future.cancel(false);
            log.debug("Cancelled timer '{}': {}", timerId, cancelled);
            return cancelled;
        }
        return false;
    }

    @Override
    public boolean isActive(String timerId) {
        ScheduledFuture<?> future = timers.get(timerId);
        return future != null && !future.isDone() && !future.isCancelled();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        timers.clear();
    }
}
