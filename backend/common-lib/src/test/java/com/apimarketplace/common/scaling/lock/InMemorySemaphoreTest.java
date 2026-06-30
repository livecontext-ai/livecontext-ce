package com.apimarketplace.common.scaling.lock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InMemorySemaphore")
class InMemorySemaphoreTest {

    private InMemorySemaphore semaphore;

    @BeforeEach
    void setUp() {
        semaphore = new InMemorySemaphore();
    }

    @Nested
    @DisplayName("tryAcquire")
    class TryAcquireTests {

        @Test
        @DisplayName("should acquire when permits available")
        void shouldAcquireWhenAvailable() {
            assertTrue(semaphore.tryAcquire("key1", 3, "owner-1"));
        }

        @Test
        @DisplayName("should acquire up to max permits")
        void shouldAcquireUpToMax() {
            assertTrue(semaphore.tryAcquire("key1", 2, "owner-1"));
            assertTrue(semaphore.tryAcquire("key1", 2, "owner-2"));
            assertFalse(semaphore.tryAcquire("key1", 2, "owner-3"));
        }

        @Test
        @DisplayName("should fail when all permits taken")
        void shouldFailWhenFull() {
            semaphore.tryAcquire("key1", 1, "owner-1");
            assertFalse(semaphore.tryAcquire("key1", 1, "owner-2"));
        }

        @Test
        @DisplayName("different keys are independent")
        void differentKeysIndependent() {
            semaphore.tryAcquire("key1", 1, "owner-1");
            assertTrue(semaphore.tryAcquire("key2", 1, "owner-2"));
        }
    }

    @Nested
    @DisplayName("release")
    class ReleaseTests {

        @Test
        @DisplayName("should allow re-acquisition after release")
        void shouldAllowReAcquisition() {
            semaphore.tryAcquire("key1", 1, "owner-1");
            assertFalse(semaphore.tryAcquire("key1", 1, "owner-2"));

            semaphore.release("key1", "owner-1");
            assertTrue(semaphore.tryAcquire("key1", 1, "owner-2"));
        }

        @Test
        @DisplayName("should not over-release beyond max permits")
        void shouldNotOverRelease() {
            semaphore.tryAcquire("key1", 2, "owner-1");
            // Release twice - only one permit was held
            semaphore.release("key1", "owner-1");
            semaphore.release("key1", "owner-1"); // should be a no-op (already at max)

            // Should still have exactly 2 available
            assertEquals(2, semaphore.availablePermits("key1", 2));
        }

        @Test
        @DisplayName("release on nonexistent key does not throw")
        void releaseNonexistentKey() {
            assertDoesNotThrow(() -> semaphore.release("nonexistent", "owner-1"));
        }
    }

    @Nested
    @DisplayName("availablePermits")
    class AvailablePermitsTests {

        @Test
        @DisplayName("should return maxPermits when no key exists")
        void shouldReturnMaxWhenNoKey() {
            assertEquals(5, semaphore.availablePermits("key1", 5));
        }

        @Test
        @DisplayName("should decrease as permits are acquired")
        void shouldDecrease() {
            semaphore.tryAcquire("key1", 3, "owner-1");
            assertEquals(2, semaphore.availablePermits("key1", 3));

            semaphore.tryAcquire("key1", 3, "owner-2");
            assertEquals(1, semaphore.availablePermits("key1", 3));
        }
    }

    @Nested
    @DisplayName("cleanupByPrefix")
    class CleanupTests {

        @Test
        @DisplayName("should remove matching keys")
        void shouldRemoveMatchingKeys() {
            semaphore.tryAcquire("run123:trigger1", 3, "owner-1");
            semaphore.tryAcquire("run123:trigger2", 3, "owner-2");
            semaphore.tryAcquire("run456:trigger1", 3, "owner-3");

            semaphore.cleanupByPrefix("run123:");

            assertEquals(3, semaphore.availablePermits("run123:trigger1", 3));
            assertEquals(3, semaphore.availablePermits("run123:trigger2", 3));
            // run456 should be untouched
            assertEquals(2, semaphore.availablePermits("run456:trigger1", 3));
        }
    }

    @Nested
    @DisplayName("Concurrency")
    class ConcurrencyTests {

        @Test
        @DisplayName("concurrent acquire should not exceed max permits")
        void concurrentAcquireShouldNotExceedMax() throws InterruptedException {
            int maxPermits = 5;
            int numThreads = 20;
            CountDownLatch latch = new CountDownLatch(numThreads);
            AtomicInteger acquired = new AtomicInteger(0);
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);

            for (int i = 0; i < numThreads; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        if (semaphore.tryAcquire("key1", maxPermits, "owner-" + idx)) {
                            acquired.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals(maxPermits, acquired.get());
            assertEquals(0, semaphore.availablePermits("key1", maxPermits));

            executor.shutdown();
        }

        @Test
        @DisplayName("concurrent release should not exceed max permits")
        void concurrentReleaseShouldNotExceedMax() throws InterruptedException {
            int maxPermits = 3;
            // Acquire all permits
            for (int i = 0; i < maxPermits; i++) {
                semaphore.tryAcquire("key1", maxPermits, "owner-" + i);
            }
            assertEquals(0, semaphore.availablePermits("key1", maxPermits));

            // Concurrently release more times than permits held
            int numThreads = 10;
            CountDownLatch latch = new CountDownLatch(numThreads);
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);

            for (int i = 0; i < numThreads; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        semaphore.release("key1", "owner-" + idx);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            // Should not exceed max permits
            assertTrue(semaphore.availablePermits("key1", maxPermits) <= maxPermits,
                    "Available permits should not exceed max: " +
                            semaphore.availablePermits("key1", maxPermits));

            executor.shutdown();
        }
    }
}
