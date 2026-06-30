package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.common.scaling.timer.InMemoryTimer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SignalTimerScheduler")
class SignalTimerSchedulerTest {

    private SignalTimerScheduler scheduler;
    private final CopyOnWriteArrayList<Long> firedSignals = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        scheduler = new SignalTimerScheduler(new InMemoryTimer());
        firedSignals.clear();
    }

    @Nested
    @DisplayName("schedule()")
    class ScheduleTests {

        @Test
        @DisplayName("Should fire callback after delay")
        void shouldFireAfterDelay() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            scheduler.setExpirationCallback(id -> {
                firedSignals.add(id);
                latch.countDown();
            });

            scheduler.schedule(1L, Instant.now().plusMillis(100));

            assertTrue(latch.await(2, TimeUnit.SECONDS), "Timer should fire within 2s");
            assertEquals(List.of(1L), firedSignals);
        }

        @Test
        @DisplayName("Should fire immediately for past expiresAt")
        void shouldFireImmediatelyForPastExpiration() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            scheduler.setExpirationCallback(id -> {
                firedSignals.add(id);
                latch.countDown();
            });

            scheduler.schedule(2L, Instant.now().minusSeconds(10));

            assertTrue(latch.await(2, TimeUnit.SECONDS), "Timer should fire immediately");
            assertEquals(List.of(2L), firedSignals);
        }

        @Test
        @DisplayName("Should not fire for null expiresAt")
        void shouldNotFireForNullExpiration() throws InterruptedException {
            scheduler.setExpirationCallback(firedSignals::add);

            scheduler.schedule(3L, null);

            Thread.sleep(200);
            assertTrue(firedSignals.isEmpty());
            assertEquals(0, scheduler.activeTimerCount());
        }

        @Test
        @DisplayName("Should replace existing timer for same signal ID")
        void shouldReplaceExistingTimer() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            scheduler.setExpirationCallback(id -> {
                firedSignals.add(id);
                latch.countDown();
            });

            // Schedule far in the future
            scheduler.schedule(4L, Instant.now().plusSeconds(60));
            assertEquals(1, scheduler.activeTimerCount());

            // Replace with near-immediate
            scheduler.schedule(4L, Instant.now().plusMillis(50));
            assertEquals(1, scheduler.activeTimerCount());

            assertTrue(latch.await(2, TimeUnit.SECONDS), "Replacement timer should fire");
            assertEquals(1, firedSignals.size());
            assertEquals(4L, firedSignals.get(0));
        }

        @Test
        @DisplayName("Should track active timer count")
        void shouldTrackActiveTimerCount() {
            scheduler.setExpirationCallback(firedSignals::add);

            scheduler.schedule(10L, Instant.now().plusSeconds(60));
            scheduler.schedule(11L, Instant.now().plusSeconds(60));
            scheduler.schedule(12L, Instant.now().plusSeconds(60));

            assertEquals(3, scheduler.activeTimerCount());
        }
    }

    @Nested
    @DisplayName("cancel()")
    class CancelTests {

        @Test
        @DisplayName("Should cancel a scheduled timer")
        void shouldCancelTimer() throws InterruptedException {
            scheduler.setExpirationCallback(firedSignals::add);

            scheduler.schedule(5L, Instant.now().plusMillis(200));
            assertEquals(1, scheduler.activeTimerCount());

            scheduler.cancel(5L);
            assertEquals(0, scheduler.activeTimerCount());

            Thread.sleep(400);
            assertTrue(firedSignals.isEmpty(), "Cancelled timer should not fire");
        }

        @Test
        @DisplayName("Should handle cancel for non-existent timer gracefully")
        void shouldHandleCancelForNonExistentTimer() {
            // Should not throw
            scheduler.cancel(999L);
            assertEquals(0, scheduler.activeTimerCount());
        }

        @Test
        @DisplayName("Should cancel all timers in batch")
        void shouldCancelAllTimers() {
            scheduler.setExpirationCallback(firedSignals::add);

            scheduler.schedule(20L, Instant.now().plusSeconds(60));
            scheduler.schedule(21L, Instant.now().plusSeconds(60));
            scheduler.schedule(22L, Instant.now().plusSeconds(60));
            assertEquals(3, scheduler.activeTimerCount());

            scheduler.cancelAll(List.of(20L, 21L, 22L));
            assertEquals(0, scheduler.activeTimerCount());
        }
    }

    @Nested
    @DisplayName("recoverPendingTimers()")
    class RecoverTests {

        @Test
        @DisplayName("Should recover pending timers from info list")
        void shouldRecoverPendingTimers() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(2);
            scheduler.setExpirationCallback(id -> {
                firedSignals.add(id);
                latch.countDown();
            });

            List<SignalTimerScheduler.SignalTimerInfo> timers = List.of(
                new SignalTimerScheduler.SignalTimerInfo(30L, Instant.now().plusMillis(50)),
                new SignalTimerScheduler.SignalTimerInfo(31L, Instant.now().plusMillis(100))
            );

            scheduler.recoverPendingTimers(timers);
            assertEquals(2, scheduler.activeTimerCount());

            assertTrue(latch.await(2, TimeUnit.SECONDS), "Recovered timers should fire");
            assertEquals(2, firedSignals.size());
            assertTrue(firedSignals.contains(30L));
            assertTrue(firedSignals.contains(31L));
        }

        @Test
        @DisplayName("Should skip null expiresAt in recovery list")
        void shouldSkipNullExpiresAt() {
            scheduler.setExpirationCallback(firedSignals::add);

            List<SignalTimerScheduler.SignalTimerInfo> timers = List.of(
                new SignalTimerScheduler.SignalTimerInfo(40L, null),
                new SignalTimerScheduler.SignalTimerInfo(41L, Instant.now().plusSeconds(60))
            );

            scheduler.recoverPendingTimers(timers);
            assertEquals(1, scheduler.activeTimerCount());
        }
    }

    @Nested
    @DisplayName("destroy()")
    class DestroyTests {

        @Test
        @DisplayName("Should clear all timers on destroy")
        void shouldClearTimersOnDestroy() throws Exception {
            scheduler.setExpirationCallback(firedSignals::add);

            scheduler.schedule(50L, Instant.now().plusSeconds(60));
            scheduler.schedule(51L, Instant.now().plusSeconds(60));
            assertEquals(2, scheduler.activeTimerCount());

            scheduler.destroy();
            assertEquals(0, scheduler.activeTimerCount());
        }
    }

    @Nested
    @DisplayName("callback error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should not propagate callback exceptions")
        void shouldNotPropagateCallbackExceptions() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            scheduler.setExpirationCallback(id -> {
                latch.countDown();
                throw new RuntimeException("Simulated callback error");
            });

            scheduler.schedule(60L, Instant.now().plusMillis(50));

            assertTrue(latch.await(2, TimeUnit.SECONDS), "Timer should fire even with error");
            // Scheduler should still be functional after error
            Thread.sleep(100); // Allow timer cleanup
            assertEquals(0, scheduler.activeTimerCount());
        }

        @Test
        @DisplayName("Should handle no callback set gracefully")
        void shouldHandleNoCallbackGracefully() throws InterruptedException {
            // No callback set - should not throw
            scheduler.schedule(70L, Instant.now().plusMillis(50));

            Thread.sleep(200);
            assertEquals(0, scheduler.activeTimerCount());
        }
    }
}
