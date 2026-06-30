package com.apimarketplace.orchestrator.services.streaming;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToLongFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Single-pod {@link InMemoryRunSeqBackend} - preserves the original
 * {@code WsEventSequencer} AtomicLong semantics exactly.
 */
@DisplayName("InMemoryRunSeqBackend")
class InMemoryRunSeqBackendTest {

    private InMemoryRunSeqBackend backend;
    private final ToLongFunction<String> zeroSeed = runId -> 0L;

    @BeforeEach
    void setUp() {
        backend = new InMemoryRunSeqBackend();
    }

    @Test
    @DisplayName("next is strictly monotonic per runId")
    void nextMonotonic() {
        assertEquals(1L, backend.next("run-1", zeroSeed));
        assertEquals(2L, backend.next("run-1", zeroSeed));
        assertEquals(3L, backend.next("run-1", zeroSeed));
    }

    @Test
    @DisplayName("different runIds get independent counters")
    void independentCounters() {
        assertEquals(1L, backend.next("run-A", zeroSeed));
        assertEquals(1L, backend.next("run-B", zeroSeed));
        assertEquals(2L, backend.next("run-A", zeroSeed));
    }

    @Test
    @DisplayName("first next seeds from the supplied seed loader (DB high-water + 1)")
    void seedsFromLoaderOnFirstCall() {
        AtomicInteger seedCalls = new AtomicInteger();
        ToLongFunction<String> seed500 = runId -> { seedCalls.incrementAndGet(); return 500L; };

        assertEquals(501L, backend.next("run-x", seed500));
        assertEquals(502L, backend.next("run-x", seed500));
        // Seed loader consulted exactly once - subsequent calls use the in-memory atomic.
        assertEquals(1, seedCalls.get());
    }

    @Test
    @DisplayName("current is read-only: does NOT create or leak state for unknown runIds")
    void currentReadOnlyNoLeak() {
        assertEquals(0L, backend.current("never-bumped", zeroSeed));
        assertEquals(0, backend.size(), "current() on an unknown run must not insert a counter");
        assertTrue(backend.peek("never-bumped").isEmpty());
    }

    @Test
    @DisplayName("current returns the live counter for a bumped run without bumping it")
    void currentReturnsLiveValue() {
        backend.next("run-1", zeroSeed);
        backend.next("run-1", zeroSeed);
        assertEquals(2L, backend.current("run-1", zeroSeed));
        assertEquals(2L, backend.current("run-1", zeroSeed), "current() must not bump");
    }

    @Test
    @DisplayName("peek exposes the local high-water; remove purges it")
    void peekAndRemove() {
        backend.next("run-1", zeroSeed);
        backend.next("run-1", zeroSeed);
        assertEquals(OptionalLong.of(2L), backend.peek("run-1"));

        backend.remove("run-1");
        assertTrue(backend.peek("run-1").isEmpty());
        assertEquals(0, backend.size());
    }

    @Test
    @DisplayName("concurrent next produces strictly distinct values (split parallel safety)")
    void concurrentDistinct() throws InterruptedException {
        int threads = 16;
        int callsPerThread = 100;
        int total = threads * callsPerThread;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(total);
        java.util.Set<Long> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        AtomicInteger collisions = new AtomicInteger();

        for (int i = 0; i < total; i++) {
            pool.submit(() -> {
                try {
                    long s = backend.next("run-concurrent", zeroSeed);
                    if (!seen.add(s)) collisions.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(0, collisions.get());
        assertEquals(total, seen.size());
    }
}
