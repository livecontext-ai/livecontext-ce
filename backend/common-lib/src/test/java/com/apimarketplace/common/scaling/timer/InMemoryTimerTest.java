package com.apimarketplace.common.scaling.timer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryTimerTest {

    private InMemoryTimer timer;

    @BeforeEach
    void setUp() {
        timer = new InMemoryTimer();
    }

    @AfterEach
    void tearDown() {
        // Cancel all pending timers to avoid dangling threads
    }

    @Test
    void schedule_fires_callback() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        timer.schedule("timer-1", Duration.ofMillis(50), latch::countDown);

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Timer callback should have fired");
    }

    @Test
    void isActive_returns_true_for_scheduled_timer() {
        timer.schedule("timer-1", Duration.ofSeconds(10), () -> {});

        assertTrue(timer.isActive("timer-1"));
    }

    @Test
    void isActive_returns_false_for_unknown_timer() {
        assertFalse(timer.isActive("nonexistent"));
    }

    @Test
    void isActive_returns_false_after_firing() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        timer.schedule("timer-1", Duration.ofMillis(50), latch::countDown);

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        // Brief pause to let the finally block in schedule remove it
        Thread.sleep(100);

        assertFalse(timer.isActive("timer-1"));
    }

    @Test
    void cancel_returns_true_for_active_timer() {
        timer.schedule("timer-1", Duration.ofSeconds(10), () -> {});

        assertTrue(timer.cancel("timer-1"));
        assertFalse(timer.isActive("timer-1"));
    }

    @Test
    void cancel_returns_false_for_unknown_timer() {
        assertFalse(timer.cancel("nonexistent"));
    }

    @Test
    void cancel_prevents_callback_from_firing() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        timer.schedule("timer-1", Duration.ofMillis(200), latch::countDown);
        timer.cancel("timer-1");

        assertFalse(latch.await(500, TimeUnit.MILLISECONDS),
                "Cancelled timer should not fire");
    }

    @Test
    void schedule_replaces_existing_timer() throws InterruptedException {
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);

        timer.schedule("timer-1", Duration.ofMillis(200), latch1::countDown);
        // Replace with a new timer
        timer.schedule("timer-1", Duration.ofMillis(50), latch2::countDown);

        assertTrue(latch2.await(2, TimeUnit.SECONDS), "Replacement timer should fire");
        assertFalse(latch1.await(500, TimeUnit.MILLISECONDS),
                "Original timer should have been cancelled");
    }

    @Test
    void reschedule_while_old_callback_running_keeps_new_timer_cancellable() throws InterruptedException {
        // Regression: the old callback's finally did an unconditional timers.remove(id), so a
        // reschedule that ran while the old callback was still executing had its NEW future's
        // mapping evicted - leaving it un-cancellable and firing unexpectedly.
        CountDownLatch oldRunning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        timer.schedule("t1", Duration.ofMillis(10), () -> {
            oldRunning.countDown();
            try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        });
        assertTrue(oldRunning.await(2, TimeUnit.SECONDS), "old callback should start");

        // Reschedule while the old callback is still blocked inside its body.
        timer.schedule("t1", Duration.ofSeconds(30), () -> { });

        // Let the old callback finish; its finally must NOT evict the new future's mapping.
        release.countDown();
        Thread.sleep(200);

        assertTrue(timer.isActive("t1"), "rescheduled timer must remain active");
        assertTrue(timer.cancel("t1"), "rescheduled timer must be cancellable");
    }
}
