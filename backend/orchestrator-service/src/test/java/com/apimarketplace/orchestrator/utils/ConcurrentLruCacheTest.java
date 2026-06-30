package com.apimarketplace.orchestrator.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConcurrentLruCache")
class ConcurrentLruCacheTest {

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {
        @Test
        @DisplayName("Should create cache with valid maxSize")
        void shouldCreateCacheWithValidMaxSize() {
            ConcurrentLruCache<String, String> cache = new ConcurrentLruCache<>(100);
            assertNotNull(cache);
        }

        @Test
        @DisplayName("Should throw for zero maxSize")
        void shouldThrowForZeroMaxSize() {
            assertThrows(IllegalArgumentException.class, () -> new ConcurrentLruCache<>(0));
        }

        @Test
        @DisplayName("Should throw for negative maxSize")
        void shouldThrowForNegativeMaxSize() {
            assertThrows(IllegalArgumentException.class, () -> new ConcurrentLruCache<>(-1));
        }
    }

    @Nested
    @DisplayName("computeIfAbsent()")
    class ComputeIfAbsentTests {
        @Test
        @DisplayName("Should compute and cache value")
        void shouldComputeAndCacheValue() {
            ConcurrentLruCache<String, String> cache = new ConcurrentLruCache<>(10);
            AtomicInteger computeCount = new AtomicInteger(0);

            String result = cache.computeIfAbsent("key1", k -> {
                computeCount.incrementAndGet();
                return "value1";
            });

            assertEquals("value1", result);
            assertEquals(1, computeCount.get());
        }

        @Test
        @DisplayName("Should return cached value on second access")
        void shouldReturnCachedValueOnSecondAccess() {
            ConcurrentLruCache<String, String> cache = new ConcurrentLruCache<>(10);
            AtomicInteger computeCount = new AtomicInteger(0);

            cache.computeIfAbsent("key1", k -> {
                computeCount.incrementAndGet();
                return "value1";
            });

            String result = cache.computeIfAbsent("key1", k -> {
                computeCount.incrementAndGet();
                return "value2";
            });

            assertEquals("value1", result);
            assertEquals(1, computeCount.get());
        }

        @Test
        @DisplayName("Should throw for null key")
        void shouldThrowForNullKey() {
            ConcurrentLruCache<String, String> cache = new ConcurrentLruCache<>(10);
            assertThrows(NullPointerException.class, () ->
                cache.computeIfAbsent(null, k -> "value"));
        }

        @Test
        @DisplayName("Should throw for null loader")
        void shouldThrowForNullLoader() {
            ConcurrentLruCache<String, String> cache = new ConcurrentLruCache<>(10);
            assertThrows(NullPointerException.class, () ->
                cache.computeIfAbsent("key", null));
        }
    }

    @Nested
    @DisplayName("Eviction")
    class EvictionTests {
        @Test
        @DisplayName("Should evict oldest entries when exceeding maxSize")
        void shouldEvictOldestEntriesWhenExceedingMaxSize() {
            ConcurrentLruCache<Integer, String> cache = new ConcurrentLruCache<>(3);

            cache.computeIfAbsent(1, k -> "value1");
            cache.computeIfAbsent(2, k -> "value2");
            cache.computeIfAbsent(3, k -> "value3");
            cache.computeIfAbsent(4, k -> "value4");

            // First entry should be evicted
            AtomicInteger computeCount = new AtomicInteger(0);
            cache.computeIfAbsent(1, k -> {
                computeCount.incrementAndGet();
                return "recomputed1";
            });

            // Should have recomputed because 1 was evicted
            assertEquals(1, computeCount.get());
        }

        @Test
        @DisplayName("Should keep most recent entries")
        void shouldKeepMostRecentEntries() {
            ConcurrentLruCache<Integer, String> cache = new ConcurrentLruCache<>(3);

            cache.computeIfAbsent(1, k -> "value1");
            cache.computeIfAbsent(2, k -> "value2");
            cache.computeIfAbsent(3, k -> "value3");
            cache.computeIfAbsent(4, k -> "value4");

            // Entry 4 should still be cached
            AtomicInteger computeCount = new AtomicInteger(0);
            cache.computeIfAbsent(4, k -> {
                computeCount.incrementAndGet();
                return "recomputed4";
            });

            assertEquals(0, computeCount.get());
        }
    }

    @Nested
    @DisplayName("clear()")
    class ClearTests {
        @Test
        @DisplayName("Should clear all entries")
        void shouldClearAllEntries() {
            ConcurrentLruCache<String, String> cache = new ConcurrentLruCache<>(10);

            cache.computeIfAbsent("key1", k -> "value1");
            cache.computeIfAbsent("key2", k -> "value2");

            cache.clear();

            AtomicInteger computeCount = new AtomicInteger(0);
            cache.computeIfAbsent("key1", k -> {
                computeCount.incrementAndGet();
                return "recomputed";
            });

            assertEquals(1, computeCount.get());
        }
    }

    @Nested
    @DisplayName("Thread safety")
    class ThreadSafetyTests {
        @Test
        @DisplayName("Should handle concurrent access")
        void shouldHandleConcurrentAccess() throws InterruptedException {
            ConcurrentLruCache<Integer, String> cache = new ConcurrentLruCache<>(100);
            int numThreads = 10;
            int operationsPerThread = 1000;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch latch = new CountDownLatch(numThreads);

            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < operationsPerThread; i++) {
                            int key = (threadId * operationsPerThread + i) % 50;
                            cache.computeIfAbsent(key, k -> "value" + k);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            executor.shutdown();

            // Should not throw any exceptions - just verify it completes
        }

        @Test
        @DisplayName("Should return consistent values for same key")
        void shouldReturnConsistentValuesForSameKey() throws InterruptedException {
            ConcurrentLruCache<String, String> cache = new ConcurrentLruCache<>(10);
            int numThreads = 10;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch latch = new CountDownLatch(numThreads);
            AtomicInteger inconsistencies = new AtomicInteger(0);

            for (int t = 0; t < numThreads; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < 100; i++) {
                            String value = cache.computeIfAbsent("sharedKey", k -> "sharedValue");
                            if (!"sharedValue".equals(value)) {
                                inconsistencies.incrementAndGet();
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            executor.shutdown();

            assertEquals(0, inconsistencies.get());
        }
    }
}
