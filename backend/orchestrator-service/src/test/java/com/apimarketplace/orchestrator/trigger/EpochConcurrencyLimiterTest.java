package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.common.scaling.lock.InMemorySemaphore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EpochConcurrencyLimiter")
class EpochConcurrencyLimiterTest {

    private static final int MAX = 3;

    private EpochConcurrencyLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new EpochConcurrencyLimiter(new InMemorySemaphore());
    }

    // ========================================================================
    // BASIC ACQUIRE / RELEASE
    // ========================================================================

    @Nested
    @DisplayName("tryAcquire()")
    class TryAcquireTests {

        @Test
        @DisplayName("Should succeed when under limit")
        void shouldSucceedUnderLimit() {
            assertTrue(limiter.tryAcquire("run1", "trigger:webhook", MAX));
        }

        @Test
        @DisplayName("Should succeed up to max concurrent")
        void shouldSucceedUpToMax() {
            assertTrue(limiter.tryAcquire("run1", "trigger:webhook", MAX));
            assertTrue(limiter.tryAcquire("run1", "trigger:webhook", MAX));
            assertTrue(limiter.tryAcquire("run1", "trigger:webhook", MAX));
        }

        @Test
        @DisplayName("Should fail when at max concurrent")
        void shouldFailAtMax() {
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            limiter.tryAcquire("run1", "trigger:webhook", MAX);

            assertFalse(limiter.tryAcquire("run1", "trigger:webhook", MAX));
        }

        @Test
        @DisplayName("Different triggers should have independent limits")
        void differentTriggersShouldBeIndependent() {
            // Fill up trigger A
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            assertFalse(limiter.tryAcquire("run1", "trigger:webhook", MAX));

            // Trigger B should still have capacity
            assertTrue(limiter.tryAcquire("run1", "trigger:chat", MAX));
        }

        @Test
        @DisplayName("Different runs should have independent limits")
        void differentRunsShouldBeIndependent() {
            // Fill up run1
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            assertFalse(limiter.tryAcquire("run1", "trigger:webhook", MAX));

            // run2 should still have capacity
            assertTrue(limiter.tryAcquire("run2", "trigger:webhook", MAX));
        }

        @Test
        @DisplayName("Backward-compatible 2-arg overload should default to max=1")
        void backwardCompatibleOverloadShouldDefaultToOne() {
            assertTrue(limiter.tryAcquire("run1", "trigger:x"));
            assertFalse(limiter.tryAcquire("run1", "trigger:x"));
        }
    }

    @Nested
    @DisplayName("Plan-based limits")
    class PlanBasedLimitsTests {

        @Test
        @DisplayName("FREE plan should allow only 1 concurrent epoch")
        void freePlanShouldAllowOne() {
            assertTrue(limiter.tryAcquire("run-free", "trigger:t", 1));
            assertFalse(limiter.tryAcquire("run-free", "trigger:t", 1));
        }

        @Test
        @DisplayName("STARTER plan should allow 10 concurrent epochs")
        void starterPlanShouldAllowTen() {
            for (int i = 0; i < 10; i++) {
                assertTrue(limiter.tryAcquire("run-starter", "trigger:t", 10),
                    "Should acquire slot " + (i + 1));
            }
            assertFalse(limiter.tryAcquire("run-starter", "trigger:t", 10));
        }

        @Test
        @DisplayName("PRO plan should allow 50 concurrent epochs")
        void proPlanShouldAllowFifty() {
            for (int i = 0; i < 50; i++) {
                assertTrue(limiter.tryAcquire("run-pro", "trigger:t", 50));
            }
            assertFalse(limiter.tryAcquire("run-pro", "trigger:t", 50));
        }
    }

    @Nested
    @DisplayName("release()")
    class ReleaseTests {

        @Test
        @DisplayName("Should free up a slot for new acquire")
        void shouldFreeSlot() {
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            assertFalse(limiter.tryAcquire("run1", "trigger:webhook", MAX));

            limiter.release("run1", "trigger:webhook");
            assertTrue(limiter.tryAcquire("run1", "trigger:webhook", MAX));
        }

        @Test
        @DisplayName("Release on non-existent key should be safe (no-op)")
        void releaseNonExistentShouldBeNoOp() {
            assertDoesNotThrow(() -> limiter.release("non-existent", "trigger:x"));
        }

        @Test
        @DisplayName("Multiple releases should free multiple slots")
        void multipleReleasesShouldFreeMultiple() {
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            limiter.tryAcquire("run1", "trigger:webhook", MAX);

            limiter.release("run1", "trigger:webhook");
            limiter.release("run1", "trigger:webhook");

            assertTrue(limiter.tryAcquire("run1", "trigger:webhook", MAX));
            assertTrue(limiter.tryAcquire("run1", "trigger:webhook", MAX));
            assertFalse(limiter.tryAcquire("run1", "trigger:webhook", MAX));
        }
    }

    // ========================================================================
    // hasActiveEpochs
    // ========================================================================

    @Nested
    @DisplayName("hasActiveEpochs()")
    class HasActiveEpochsTests {

        @Test
        @DisplayName("Should return false when no acquires")
        void shouldReturnFalseWhenNoAcquires() {
            assertFalse(limiter.hasActiveEpochs("run1", "trigger:webhook"));
        }

        @Test
        @DisplayName("Should return true after acquire")
        void shouldReturnTrueAfterAcquire() {
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            assertTrue(limiter.hasActiveEpochs("run1", "trigger:webhook"));
        }

        @Test
        @DisplayName("Should return false after all released")
        void shouldReturnFalseAfterAllReleased() {
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            limiter.tryAcquire("run1", "trigger:webhook", MAX);

            limiter.release("run1", "trigger:webhook");
            limiter.release("run1", "trigger:webhook");

            assertFalse(limiter.hasActiveEpochs("run1", "trigger:webhook"));
        }

        @Test
        @DisplayName("Should return true if partially released")
        void shouldReturnTrueIfPartiallyReleased() {
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            limiter.tryAcquire("run1", "trigger:webhook", MAX);

            limiter.release("run1", "trigger:webhook");

            assertTrue(limiter.hasActiveEpochs("run1", "trigger:webhook"));
        }

        @Test
        @DisplayName("hasActiveEpochs is O(1) - does NOT round-trip to the distributed semaphore (regression: hot-path Redis call, scalability audit 2026-05-06)")
        void hasActiveEpochsDoesNotCallDistributedSemaphore() {
            // Pre-fix this called distributedSemaphore.availablePermits which in
            // horizontal-scaling mode is a Redis round-trip per invocation. The
            // function is on the hot path of resetForNextCycle and the recovery
            // watchdog. After the fix, the check reads only the local ownerIds
            // deque - no semaphore interaction at all.
            java.util.concurrent.atomic.AtomicInteger availablePermitsCalls =
                new java.util.concurrent.atomic.AtomicInteger(0);
            com.apimarketplace.common.scaling.lock.DistributedSemaphore countingSem =
                new com.apimarketplace.common.scaling.lock.InMemorySemaphore() {
                    @Override
                    public int availablePermits(String key, int max) {
                        availablePermitsCalls.incrementAndGet();
                        return super.availablePermits(key, max);
                    }
                };
            EpochConcurrencyLimiter countingLimiter = new EpochConcurrencyLimiter(countingSem);
            countingLimiter.tryAcquire("run-1", "trigger:t", 5);
            int callsAfterAcquire = availablePermitsCalls.get(); // 1 from acquire's log line

            // The check itself MUST NOT call availablePermits.
            countingLimiter.hasActiveEpochs("run-1", "trigger:t");
            countingLimiter.hasActiveEpochs("run-1", "trigger:t");
            countingLimiter.hasAnyActiveEpochs("run-1");
            countingLimiter.hasAnyActiveEpochs("run-1");

            assertEquals(callsAfterAcquire, availablePermitsCalls.get(),
                "hasActiveEpochs / hasAnyActiveEpochs must not call DistributedSemaphore.availablePermits");
        }
    }

    // ========================================================================
    // hasAnyActiveEpochs (across all triggers for a run)
    // ========================================================================

    @Nested
    @DisplayName("hasAnyActiveEpochs()")
    class HasAnyActiveEpochsTests {

        @Test
        @DisplayName("Should return false for unknown run")
        void shouldReturnFalseForUnknownRun() {
            assertFalse(limiter.hasAnyActiveEpochs("unknown-run"));
        }

        @Test
        @DisplayName("Should return true when any trigger has active epochs")
        void shouldReturnTrueWhenAnyTriggerActive() {
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            assertTrue(limiter.hasAnyActiveEpochs("run1"));
        }

        @Test
        @DisplayName("Should check all triggers for the run")
        void shouldCheckAllTriggers() {
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            limiter.release("run1", "trigger:webhook");

            // webhook has no active epochs, but chat does
            limiter.tryAcquire("run1", "trigger:chat", MAX);

            assertTrue(limiter.hasAnyActiveEpochs("run1"));
        }

        @Test
        @DisplayName("Should not include other runs")
        void shouldNotIncludeOtherRuns() {
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            limiter.tryAcquire("run2", "trigger:webhook", MAX);

            limiter.release("run1", "trigger:webhook");

            assertFalse(limiter.hasAnyActiveEpochs("run1"));
            assertTrue(limiter.hasAnyActiveEpochs("run2"));
        }
    }

    // ========================================================================
    // cleanup
    // ========================================================================

    @Nested
    @DisplayName("cleanup()")
    class CleanupTests {

        @Test
        @DisplayName("Should remove all limiters for the run")
        void shouldRemoveAllForRun() {
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            limiter.tryAcquire("run1", "trigger:chat", MAX);

            limiter.cleanup("run1");

            assertFalse(limiter.hasActiveEpochs("run1", "trigger:webhook"));
            assertFalse(limiter.hasActiveEpochs("run1", "trigger:chat"));
            assertFalse(limiter.hasAnyActiveEpochs("run1"));
        }

        @Test
        @DisplayName("Should not affect other runs")
        void shouldNotAffectOtherRuns() {
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            limiter.tryAcquire("run2", "trigger:webhook", MAX);

            limiter.cleanup("run1");

            assertTrue(limiter.hasActiveEpochs("run2", "trigger:webhook"));
        }

        @Test
        @DisplayName("Should allow re-acquisition after cleanup")
        void shouldAllowReAcquisitionAfterCleanup() {
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            limiter.tryAcquire("run1", "trigger:webhook", MAX);

            limiter.cleanup("run1");

            // Should have fresh semaphore with full capacity
            assertTrue(limiter.tryAcquire("run1", "trigger:webhook", MAX));
            assertTrue(limiter.tryAcquire("run1", "trigger:webhook", MAX));
            assertTrue(limiter.tryAcquire("run1", "trigger:webhook", MAX));
        }
    }

    // ========================================================================
    // CONFIGURATION
    // ========================================================================

    @Nested
    @DisplayName("Configuration")
    class ConfigTests {

        @Test
        @DisplayName("Should expose maxConcurrentEpochs per key")
        void shouldExposeMax() {
            limiter.tryAcquire("run1", "trigger:a", 50);
            assertEquals(50, limiter.getMaxConcurrentEpochs("run1", "trigger:a"));
        }

        @Test
        @DisplayName("Should work with max=1 (sequential epochs)")
        void shouldWorkWithMaxOne() {
            assertTrue(limiter.tryAcquire("run1", "trigger:a", 1));
            assertFalse(limiter.tryAcquire("run1", "trigger:a", 1));

            limiter.release("run1", "trigger:a");
            assertTrue(limiter.tryAcquire("run1", "trigger:a", 1));
        }
    }

    // ========================================================================
    // CONCURRENT ACCESS STRESS TEST
    // ========================================================================

    @Nested
    @DisplayName("Concurrent stress tests")
    class ConcurrentStressTests {

        @Test
        @DisplayName("Concurrent acquires should never exceed max")
        void concurrentAcquiresShouldNeverExceedMax() throws InterruptedException {
            int threadCount = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger acquiredCount = new AtomicInteger(0);
            AtomicInteger failedCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        if (limiter.tryAcquire("run1", "trigger:webhook", MAX)) {
                            acquiredCount.incrementAndGet();
                        } else {
                            failedCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            startLatch.countDown(); // All threads start simultaneously
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            // Only 3 should have succeeded (max concurrent = 3)
            assertEquals(MAX, acquiredCount.get());
            assertEquals(threadCount - MAX, failedCount.get());
        }

        @Test
        @DisplayName("Concurrent acquire-release cycles should maintain consistency")
        void concurrentAcquireReleaseShouldMaintainConsistency() throws InterruptedException {
            int iterations = 100;
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(iterations * threadCount);
            AtomicInteger errors = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    for (int i = 0; i < iterations; i++) {
                        try {
                            if (limiter.tryAcquire("run1", "trigger:stress", MAX)) {
                                // Simulate some work
                                Thread.sleep(1);
                                limiter.release("run1", "trigger:stress");
                            }
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        } finally {
                            latch.countDown();
                        }
                    }
                });
            }

            assertTrue(latch.await(30, TimeUnit.SECONDS));
            executor.shutdown();

            assertEquals(0, errors.get());
            // After all releases, should have no active epochs
            assertFalse(limiter.hasActiveEpochs("run1", "trigger:stress"));
        }

        @Test
        @DisplayName("Concurrent cleanup should not cause exceptions")
        void concurrentCleanupShouldBeSafe() throws InterruptedException {
            // Acquire some slots
            limiter.tryAcquire("run1", "trigger:a", MAX);
            limiter.tryAcquire("run1", "trigger:b", MAX);
            limiter.tryAcquire("run1", "trigger:c", MAX);

            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        limiter.cleanup("run1");
                    } catch (Exception e) {
                        errors.add(e);
                    }
                });
            }

            startLatch.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            assertTrue(errors.isEmpty(), "Concurrent cleanup caused errors: " + errors);
        }
    }

    // ========================================================================
    // EDGE CASES
    // ========================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Release more than acquired should be safe - capped at max permits")
        void releaseMoreThanAcquiredIncreasesPermits() {
            // Over-release is now guarded: release() skips if available >= max
            limiter.tryAcquire("run1", "trigger:webhook", MAX);
            limiter.release("run1", "trigger:webhook");
            limiter.release("run1", "trigger:webhook"); // Extra release - should be no-op

            // Can still acquire up to MAX (3), but not more - extra release was capped
            assertTrue(limiter.tryAcquire("run1", "trigger:webhook", MAX));
            assertTrue(limiter.tryAcquire("run1", "trigger:webhook", MAX));
            assertTrue(limiter.tryAcquire("run1", "trigger:webhook", MAX));
            assertFalse(limiter.tryAcquire("run1", "trigger:webhook", MAX)); // 4th - blocked (no extra permit)
        }

        @Test
        @DisplayName("Null-like values should not crash")
        void nullLikeValuesShouldNotCrash() {
            // Empty string trigger
            assertTrue(limiter.tryAcquire("run1", "", MAX));
            limiter.release("run1", "");
        }

        @Test
        @DisplayName("Very long runId and triggerId should work")
        void veryLongIdsShouldWork() {
            String longRunId = "a".repeat(1000);
            String longTriggerId = "b".repeat(1000);

            assertTrue(limiter.tryAcquire(longRunId, longTriggerId, MAX));
            assertTrue(limiter.hasActiveEpochs(longRunId, longTriggerId));
            limiter.release(longRunId, longTriggerId);
            assertFalse(limiter.hasActiveEpochs(longRunId, longTriggerId));
        }
    }
}
