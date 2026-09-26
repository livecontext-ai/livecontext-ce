package com.apimarketplace.orchestrator.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LocalRunExecutionTracker")
class LocalRunExecutionTrackerTest {

    private final LocalRunExecutionTracker tracker = new LocalRunExecutionTracker();

    @Test
    @DisplayName("counts a run from submission until it completes")
    void countsWhileRunning() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> run = tracker.runAsync(() -> {
            started.countDown();
            await(release);
        });

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(tracker.activeCount()).isEqualTo(1);
        release.countDown();
        run.get(5, TimeUnit.SECONDS);
        assertThat(tracker.activeCount()).isZero();
    }

    @Test
    @DisplayName("is counted before the task is even picked up by the pool")
    void countedBeforeTheTaskRuns() {
        // An executor that never runs the task: the run is queued, not started, yet it is
        // already this instance's work and the drain must see it.
        tracker.runAsync(() -> { }, task -> { });

        assertThat(tracker.activeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a run that throws is released too")
    void releasedOnException() {
        CompletableFuture<Void> run = tracker.runAsync(() -> { throw new IllegalStateException("boom"); });

        assertThatThrownBy(() -> run.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(tracker.activeCount()).isZero();
    }

    @Test
    @DisplayName("a submission the executor rejects is not left counted")
    void rejectedSubmissionIsReleased() {
        assertThatThrownBy(() -> tracker.runAsync(() -> { }, task -> { throw new RejectedExecutionException("full"); }))
            .isInstanceOf(RejectedExecutionException.class);

        assertThat(tracker.activeCount()).isZero();
    }

    @Test
    @DisplayName("cancelling the returned future does not skip the release: the count follows the task body")
    void cancellingTheFutureDoesNotLeakTheCount() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        CompletableFuture<Void> run = tracker.runAsync(() -> {
            started.countDown();
            await(release);
            finished.countDown();
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        run.cancel(true);
        assertThat(tracker.activeCount()).as("the task is still running").isEqualTo(1);
        release.countDown();
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (tracker.activeCount() != 0 && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertThat(tracker.activeCount()).isZero();
    }

    @Test
    @DisplayName("two runs execute concurrently on the default pool (never serialized behind one worker)")
    void concurrentRunsAreNotSerialized() throws Exception {
        CountDownLatch bothRunning = new CountDownLatch(2);
        CompletableFuture<Void> a = tracker.runAsync(() -> { bothRunning.countDown(); await(bothRunning); });
        CompletableFuture<Void> b = tracker.runAsync(() -> { bothRunning.countDown(); await(bothRunning); });

        CompletableFuture.allOf(a, b).get(5, TimeUnit.SECONDS);
        assertThat(tracker.activeCount()).isZero();
    }

    @Test
    @DisplayName("cancelling the future BEFORE the task started still runs it and releases the count")
    void cancellingBeforeStartDoesNotLeakTheCount() {
        java.util.List<Runnable> held = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
        CompletableFuture<Void> run = tracker.runAsync(() -> ran.set(true), held::add);
        assertThat(tracker.activeCount()).isEqualTo(1);

        run.cancel(true);
        held.forEach(Runnable::run);

        assertThat(ran).as("the run is not stopped by cancelling the caller's handle").isTrue();
        assertThat(tracker.activeCount()).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
