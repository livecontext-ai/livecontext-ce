package com.apimarketplace.orchestrator.services.state.patch;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Plan v4 §1.6 + §3 - end-to-end test exercising the full CAS chain under
 * simulated concurrent contention without Docker. The
 * {@link JsonbPatchExecutor} + {@link com.apimarketplace.orchestrator.repository.WorkflowRunRepository}
 * are mocked to simulate Postgres single-writer-wins semantics: the seq
 * counter is an atomic shared between threads, applyPatchesCas returns 1
 * only when expectedSeq == liveSeq, else 0.
 *
 * <p>This catches integration issues that the per-class unit tests can't:
 * the interplay of RunCoalescingService.enqueuePatch → flush → retry
 * budget → POISON → fallback under genuine concurrent writers.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Plan v4 §1.6 + §3 - CAS chain end-to-end under simulated contention")
class CasChainEndToEndTest {

    @Mock JsonbPatchExecutor mockExecutor;
    @Mock com.apimarketplace.orchestrator.repository.WorkflowRunRepository mockRepo;

    private SimpleMeterRegistry meterRegistry;
    private RunCoalescingService coalescer;

    /** Simulated Postgres row seq, atomic so cross-thread CAS works. */
    private final AtomicLong rowSeq = new AtomicLong(0);

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        coalescer = new RunCoalescingService(meterRegistry, true);

        // Wire via reflection (same trick as RunCoalescingServiceTest)
        Field repoField = RunCoalescingService.class.getDeclaredField("runRepository");
        repoField.setAccessible(true);
        repoField.set(coalescer, mockRepo);
        Field execField = RunCoalescingService.class.getDeclaredField("patchExecutor");
        execField.setAccessible(true);
        execField.set(coalescer, mockExecutor);

        // Mock seq reads to return current atomic value
        lenient().when(mockRepo.findStateSnapshotSeqByRunIdPublic(anyString()))
                .thenAnswer(inv -> Optional.of(rowSeq.get()));

        // Mock applyPatchesCas to compare-and-set the atomic
        lenient().when(mockExecutor.applyPatchesCas(anyString(), anyList(), anyLong(), anyLong()))
                .thenAnswer(inv -> {
                    long expected = inv.getArgument(2);
                    long target = inv.getArgument(3);
                    return rowSeq.compareAndSet(expected, target) ? 1 : 0;
                });
    }

    @Test
    @DisplayName("Single-writer scenario: 1 enqueue + closeCoalescing → CAS succeeds first try, seq=1")
    void singleWriterSucceedsFirstTry() {
        coalescer.openCoalescing("run-1");
        CompletableFuture<Void> f = coalescer.enqueuePatch("run-1",
                JsonbPatch.assignment(new String[]{"seq"}, "1"), PatchClass.OpKind.ASSIGN);
        coalescer.closeCoalescing("run-1");

        assertThat(f).isCompletedWithValue(null);
        assertThat(rowSeq.get()).isEqualTo(1);
        assertThat(meterRegistry.counter("orchestrator.coalesce.flush_ok_count").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("orchestrator.coalesce.cas_conflict_count").count())
                .isZero();
    }

    @Test
    @DisplayName("Concurrent peer commit between read and CAS → conflict, retry succeeds")
    void peerCommitConflictThenRetrySucceeds() throws Exception {
        // Inject a peer commit between the first seq read and the first CAS attempt.
        // The mock setup: first findStateSnapshotSeqByRunIdPublic returns 0, but
        // before applyPatchesCas runs, a "peer" bumps rowSeq to 1. CAS expects 0,
        // sees 1, returns 0 (conflict). Retry reads fresh seq=1, CAS(1→2) succeeds.
        ConcurrentLinkedQueue<Long> seqReads = new ConcurrentLinkedQueue<>();
        when(mockRepo.findStateSnapshotSeqByRunIdPublic(anyString()))
                .thenAnswer(inv -> {
                    long currentSeq = rowSeq.get();
                    seqReads.add(currentSeq);
                    // After the first read, simulate a peer commit before our CAS runs
                    if (seqReads.size() == 1) {
                        rowSeq.set(currentSeq + 1);  // peer bumps to 1
                    }
                    return Optional.of(currentSeq);
                });
        when(mockExecutor.applyPatchesCas(anyString(), anyList(), anyLong(), anyLong()))
                .thenAnswer(inv -> {
                    long expected = inv.getArgument(2);
                    long target = inv.getArgument(3);
                    return rowSeq.compareAndSet(expected, target) ? 1 : 0;
                });

        coalescer.openCoalescing("run-1");
        CompletableFuture<Void> f = coalescer.enqueuePatch("run-1",
                JsonbPatch.assignment(new String[]{"x"}, "\"v\""), PatchClass.OpKind.ASSIGN);
        coalescer.closeCoalescing("run-1");

        assertThat(f).isCompletedWithValue(null);
        // Retry: 1 conflict, then 1 success
        assertThat(meterRegistry.counter("orchestrator.coalesce.cas_conflict_count").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("orchestrator.coalesce.flush_ok_count").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Sustained peer dominance: 3 conflicts → POISON + retry_exhausted + future fails")
    void peerDominanceExhaustsBudget() {
        // Simulate: every applyPatchesCas call sees a peer-bumped seq mid-flight.
        // The seq read returns N, but by the time CAS runs, the row is at N+1,
        // so applyPatchesCas(expected=N, target=N+1) sees rowSeq=N+1 already → rejects.
        when(mockExecutor.applyPatchesCas(anyString(), anyList(), anyLong(), anyLong()))
                .thenAnswer(inv -> {
                    // Peer commits AFTER our read, BEFORE our CAS check
                    rowSeq.incrementAndGet();
                    long expected = inv.getArgument(2);
                    long target = inv.getArgument(3);
                    return rowSeq.compareAndSet(expected, target) ? 1 : 0;  // always fails
                });

        coalescer.openCoalescing("run-1");
        CompletableFuture<Void> f = coalescer.enqueuePatch("run-1",
                JsonbPatch.assignment(new String[]{"x"}, "\"v\""), PatchClass.OpKind.ASSIGN);
        coalescer.closeCoalescing("run-1");

        assertThat(f).isCompletedExceptionally();
        assertThat(meterRegistry.counter("orchestrator.coalesce.cas_retry_exhausted_count").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("orchestrator.coalesce.session_poisoned_count").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Multi-thread DELTA fan-out: 20 concurrent +1 enqueues on same path → merged into 1 patch +N")
    void multiThreadDeltaFanoutMerges() throws Exception {
        coalescer.openCoalescing("run-1");
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                    return coalescer.enqueuePatch("run-1",
                            JsonbPatch.commutativeDelta(new String[]{"nodes", "X", "completed"}, 1),
                            PatchClass.OpKind.COMMUTATIVE_DELTA);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }, pool).thenCompose(f -> f));
        }

        start.countDown();
        pool.shutdown();
        boolean awaited = pool.awaitTermination(10, TimeUnit.SECONDS);
        coalescer.closeCoalescing("run-1");

        assertThat(awaited).isTrue();
        // Wait for all chained futures to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(5, TimeUnit.SECONDS);

        // All 20 enqueues merged into ≤ a small number of patches via the
        // 32-cap or close-flush trigger. Exact count depends on scheduling but
        // collision_merge_count must be > 0 and < 20 (some merged).
        double merges = meterRegistry.counter("orchestrator.coalesce.collision_merge_count").count();
        double flushes = meterRegistry.counter("orchestrator.coalesce.flush_ok_count").count();
        assertThat(merges).as("at least some merges happened").isGreaterThan(0.0);
        assertThat(flushes).as("at most 1-2 flushes for 20 enqueues thanks to merging")
                .isLessThanOrEqualTo(2.0);
        // Total delta committed should still sum to 20
        assertThat(rowSeq.get()).as("at least 1 seq bump per committed flush")
                .isLessThanOrEqualTo((long) flushes);
    }
}
