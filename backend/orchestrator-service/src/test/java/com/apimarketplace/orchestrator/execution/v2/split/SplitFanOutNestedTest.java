package com.apimarketplace.orchestrator.execution.v2.split;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LC-064, audit round 2: the bounded fan-out pool refused NESTED splits. An outer item blocking on
 * its inner fan-out made the ForkJoinPool spawn a compensation thread per blocked worker, and once
 * the pool's thread ceiling was reached further blocking was rejected, failing the split. Here the
 * ceiling is shrunk to 4 threads so 300 outer items reproduce it cheaply.
 */
@DisplayName("Split fan-out - nested splits and the in-flight window (LC-064 round 2)")
class SplitFanOutNestedTest {

    private ForkJoinPool pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private SplitAwareNodeExecutor executorOn(ForkJoinPool pool) {
        return new SplitAwareNodeExecutor(null, null, null, null, null, null, pool);
    }

    @Test
    @DisplayName("300 outer items, each running a nested 3-item fan-out, complete on a 4-thread pool")
    void nestedFanOutCompletesUnderATinyThreadCeiling() throws Exception {
        pool = SplitAwareNodeExecutor.newSplitFanOutPool(2, 4);
        SplitAwareNodeExecutor executor = executorOn(pool);
        executor.setFanOutWindow(8);
        AtomicInteger innerRuns = new AtomicInteger();

        Semaphore outerWindow = executor.newFanOutWindow();
        List<CompletableFuture<Integer>> outer = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            outer.add(executor.submitFanOutItem(() -> {
                Semaphore innerWindow = executor.newFanOutWindow();
                List<CompletableFuture<Integer>> inner = new ArrayList<>();
                for (int j = 0; j < 3; j++) {
                    inner.add(executor.submitFanOutItem(() -> {
                        sleepQuietly(1);
                        return innerRuns.incrementAndGet();
                    }, innerWindow));
                }
                return CompletableFuture.allOf(inner.toArray(new CompletableFuture[0]))
                        .thenApply(v -> inner.size()).join();
            }, outerWindow));
        }

        CompletableFuture.allOf(outer.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);

        assertThat(outer).allSatisfy(f -> assertThat(f).isCompletedWithValue(3));
        assertThat(innerRuns.get()).isEqualTo(900);
        assertThat(pool.getPoolSize()).isLessThanOrEqualTo(4);
    }

    @Test
    @DisplayName("the window caps how many items of one split are in flight on the pool")
    void windowCapsInFlightItems() throws Exception {
        pool = SplitAwareNodeExecutor.newSplitFanOutPool(8, 16);
        SplitAwareNodeExecutor executor = executorOn(pool);
        executor.setFanOutWindow(3);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();

        Semaphore window = executor.newFanOutWindow();
        List<CompletableFuture<Integer>> items = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            items.add(executor.submitFanOutItem(() -> {
                int now = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(now, Math::max);
                sleepQuietly(5);
                inFlight.decrementAndGet();
                return 1;
            }, window));
        }
        CompletableFuture.allOf(items.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);

        assertThat(maxInFlight.get()).isLessThanOrEqualTo(3);
        assertThat(window.availablePermits()).isEqualTo(3);
    }

    @Test
    @DisplayName("a failing item surfaces as a failed future and still releases its window slot")
    void failingItemReleasesItsSlot() throws Exception {
        pool = SplitAwareNodeExecutor.newSplitFanOutPool(2, 4);
        SplitAwareNodeExecutor executor = executorOn(pool);
        executor.setFanOutWindow(1);
        Semaphore window = executor.newFanOutWindow();

        CompletableFuture<Integer> failed = executor.submitFanOutItem(() -> {
            throw new IllegalStateException("boom");
        }, window);
        CompletableFuture<Integer> next = executor.submitFanOutItem(() -> 7, window);

        assertThat(next.get(10, TimeUnit.SECONDS)).isEqualTo(7);
        assertThat(failed).isCompletedExceptionally();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
