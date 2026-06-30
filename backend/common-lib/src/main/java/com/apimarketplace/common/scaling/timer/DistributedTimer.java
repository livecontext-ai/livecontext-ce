package com.apimarketplace.common.scaling.timer;

import java.time.Duration;

/**
 * Abstraction for a distributed timer.
 * <p>
 * InMemory (single-instance mode): backed by ScheduledExecutorService.
 * Redis (multi-instance mode): backed by Redis sorted sets with server-side timestamps.
 */
public interface DistributedTimer {

    /**
     * Schedule a callback to execute after a delay.
     *
     * @param timerId  unique identifier for this timer
     * @param delay    how long to wait before executing
     * @param callback the action to execute when the timer fires
     */
    void schedule(String timerId, Duration delay, Runnable callback);

    /**
     * Cancel a previously scheduled timer.
     *
     * @param timerId the timer to cancel
     * @return true if the timer was found and cancelled
     */
    boolean cancel(String timerId);

    /**
     * Check if a timer is currently scheduled and has not yet fired.
     *
     * @param timerId the timer to check
     * @return true if the timer is still active
     */
    boolean isActive(String timerId);
}
