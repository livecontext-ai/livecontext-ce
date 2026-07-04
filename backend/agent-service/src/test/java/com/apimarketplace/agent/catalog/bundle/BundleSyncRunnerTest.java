package com.apimarketplace.agent.catalog.bundle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BundleSyncRunner}: the manual-sync RUNNING state that lets the admin UI
 * resume its loading indicator across a page navigation.
 */
@DisplayName("BundleSyncRunner")
class BundleSyncRunnerTest {

    @Test
    @DisplayName("startAsync runs the tick off-thread; running is true during, false after; startedAt is set")
    void runsTickAndTracksRunning() throws InterruptedException {
        BundleSyncRunner runner = new BundleSyncRunner();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();

        boolean started = runner.startAsync(() -> {
            runs.incrementAndGet();
            inside.countDown();
            try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });

        assertThat(started).isTrue();
        assertThat(inside.await(2, TimeUnit.SECONDS)).isTrue();
        // While the tick blocks, the runner reports running=true with a startedAt.
        assertThat(runner.state().running()).isTrue();
        assertThat(runner.state().startedAt()).isNotNull();

        release.countDown();
        awaitFalse(() -> runner.state().running());
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("A second startAsync while one is in flight is a no-op (returns false, tick not stacked)")
    void secondStartWhileRunningIsNoOp() throws InterruptedException {
        BundleSyncRunner runner = new BundleSyncRunner();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();

        runner.startAsync(() -> {
            runs.incrementAndGet();
            inside.countDown();
            try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        assertThat(inside.await(2, TimeUnit.SECONDS)).isTrue();

        boolean secondStarted = runner.startAsync(runs::incrementAndGet);
        assertThat(secondStarted).isFalse();

        release.countDown();
        awaitFalse(() -> runner.state().running());
        // Only the first tick ran.
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("cancel() flips running to false immediately and requests cancellation; no-op when idle")
    void cancelStopsRunning() throws InterruptedException {
        BundleSyncRunner runner = new BundleSyncRunner();
        // Idle cancel is a harmless no-op.
        runner.cancel();
        assertThat(runner.state().running()).isFalse();

        CountDownLatch inside = new CountDownLatch(1);
        runner.startAsync(() -> {
            inside.countDown();
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertThat(inside.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(runner.state().running()).isTrue();

        runner.cancel();
        // running is false immediately after cancel (UI stops spinning at once).
        assertThat(runner.state().running()).isFalse();
        assertThat(runner.state().cancelRequested()).isTrue();
    }

    /** Minimal poll-until helper (no awaitility dependency). */
    private static void awaitFalse(BooleanSupplier stillTrue) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (stillTrue.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition did not become false within 2s");
            }
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }
}
