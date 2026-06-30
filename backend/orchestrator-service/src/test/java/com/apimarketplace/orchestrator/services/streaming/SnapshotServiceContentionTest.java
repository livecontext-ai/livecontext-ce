package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: when {@code lastSendTime} is stale (fresh runId, post-cleanup),
 * a contender thread that fails to acquire the per-runId stripe lock used to
 * recurse synchronously into {@code sendSnapshot → doSendSnapshot}, blowing
 * the stack ({@link StackOverflowError}). Observed in prod 09:16 UTC pre-deploy
 * of the seq-stripe fix (3 occurrences during run cleanup).
 *
 * <p>Mechanism: with {@code lastSendTime=0L} (fresh or post-cleanup), {@code
 * sendSnapshot} sees {@code elapsed >= THROTTLE_MS}, calls {@code
 * doSendSnapshot}; if a peer thread holds the stripe lock and hasn't yet
 * reached the {@code lastSendTime.put} (line 384) - which can be a wide
 * window if the peer is paused by GC or an OS context switch - every
 * contender's {@code tryLock} fails, and the previous code recursed via
 * {@code sendSnapshot(runId)} on the same call stack.
 *
 * <p>Fix: schedule a deferred retry through the {@link #throttleScheduler}
 * instead of recursing synchronously.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SnapshotService - concurrent contention (SOE regression)")
class SnapshotServiceContentionTest {

    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private WorkflowStreamingService streamingService;
    @Mock private RunningNodeTracker runningNodeTracker;
    @Mock private WorkflowEpochService workflowEpochService;
    @Mock private WorkflowRunRepository runRepository;

    private SnapshotService snapshotService;

    @BeforeEach
    void setUp() {
        snapshotService = new SnapshotService(
                stateSnapshotService,
                streamingService,
                runningNodeTracker,
                workflowEpochService,
                runRepository,
                60L,
                1800L
        );
    }

    @Test
    @DisplayName("doSendSnapshot does not recurse synchronously when stripe lock is held - schedules deferred")
    void doSendSnapshotSchedulesDeferredOnLockContention() throws InterruptedException {
        String runId = "run-contention";

        // Steal the stripe lock from another thread to force tryLock false.
        @SuppressWarnings("unchecked")
        ReentrantLock[] sendLocks = (ReentrantLock[]) ReflectionTestUtils.getField(snapshotService, "sendLocks");
        int stripe = Math.floorMod(runId.hashCode(), sendLocks.length);
        ReentrantLock stripeLock = sendLocks[stripe];

        CountDownLatch holderAcquired = new CountDownLatch(1);
        CountDownLatch holderRelease = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            stripeLock.lock();
            try {
                holderAcquired.countDown();
                holderRelease.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                stripeLock.unlock();
            }
        }, "stripe-lock-holder");
        holder.setDaemon(true);
        holder.start();
        assertTrue(holderAcquired.await(2, TimeUnit.SECONDS), "Holder failed to acquire stripe lock");

        try {
            // Pre-fix: doSendSnapshot → tryLock fails → sendSnapshot(runId)
            // → reads lastSendTime=0L → elapsed >= THROTTLE_MS → doSendSnapshot
            // again → recursion → SOE within the test thread's stack.
            //
            // Post-fix: schedules a deferred future and returns. We assert by
            // (a) no StackOverflowError thrown, (b) a pendingSnapshots entry
            // exists for the runId.
            ReflectionTestUtils.invokeMethod(snapshotService, "doSendSnapshot", runId);

            @SuppressWarnings("unchecked")
            Map<String, ScheduledFuture<?>> pending = (Map<String, ScheduledFuture<?>>)
                    ReflectionTestUtils.getField(snapshotService, "pendingSnapshots");
            assertTrue(pending.containsKey(runId),
                    "Expected a deferred future to be scheduled for " + runId + " when stripe lock is held");
            ScheduledFuture<?> future = pending.get(runId);
            assertFalse(future.isDone(), "Deferred future should still be pending");
            // Cancel to keep the test deterministic.
            future.cancel(false);
        } finally {
            holderRelease.countDown();
            holder.join(2_000);
        }
    }

    @Test
    @DisplayName("100 concurrent doSendSnapshot calls under sustained lock contention do not StackOverflow")
    void heavyContentionDoesNotStackOverflow() throws InterruptedException {
        String runId = "run-storm";

        @SuppressWarnings("unchecked")
        ReentrantLock[] sendLocks = (ReentrantLock[]) ReflectionTestUtils.getField(snapshotService, "sendLocks");
        int stripe = Math.floorMod(runId.hashCode(), sendLocks.length);
        ReentrantLock stripeLock = sendLocks[stripe];

        CountDownLatch holderAcquired = new CountDownLatch(1);
        CountDownLatch holderRelease = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            stripeLock.lock();
            try {
                holderAcquired.countDown();
                holderRelease.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                stripeLock.unlock();
            }
        }, "stripe-lock-holder-2");
        holder.setDaemon(true);
        holder.start();
        assertTrue(holderAcquired.await(2, TimeUnit.SECONDS));

        int contenders = 100;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger soeCount = new AtomicInteger();
        ConcurrentHashMap<String, Throwable> errors = new ConcurrentHashMap<>();
        CountDownLatch done = new CountDownLatch(contenders);

        try {
            for (int i = 0; i < contenders; i++) {
                pool.submit(() -> {
                    try {
                        ReflectionTestUtils.invokeMethod(snapshotService, "doSendSnapshot", runId);
                    } catch (StackOverflowError soe) {
                        soeCount.incrementAndGet();
                    } catch (Throwable t) {
                        errors.put(Thread.currentThread().getName(), t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(15, TimeUnit.SECONDS), "Contenders timed out");
        } finally {
            holderRelease.countDown();
            holder.join(2_000);
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }

        // Cancel any scheduled futures the test left behind.
        @SuppressWarnings("unchecked")
        Map<String, ScheduledFuture<?>> pending = (Map<String, ScheduledFuture<?>>)
                ReflectionTestUtils.getField(snapshotService, "pendingSnapshots");
        for (ScheduledFuture<?> f : pending.values()) {
            f.cancel(false);
        }

        assertTrue(soeCount.get() == 0,
                "Expected no StackOverflowError under contention, got " + soeCount.get());
        assertTrue(errors.isEmpty(),
                "Unexpected errors: " + errors);
    }
}
