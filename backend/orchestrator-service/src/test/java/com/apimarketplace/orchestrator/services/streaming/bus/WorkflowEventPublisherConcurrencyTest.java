package com.apimarketplace.orchestrator.services.streaming.bus;

import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.streaming.SeqPublishLockStripes;
import com.apimarketplace.orchestrator.services.streaming.WsEventSequencer;
import com.apimarketplace.orchestrator.services.streaming.events.StepLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Regression: concurrent publish() calls on the same runId must deliver
 * events to Redis in seq order. Without per-runId serialization of the
 * {@code nextSeq → publishSequenced} window, parallel triggers can deliver
 * inverted seqs (e.g. seq=6 before seq=5), and the frontend strict-{@code <}
 * stale filter ({@code WorkflowRunManager.handleBatchUpdate}) drops the
 * older arrival silently - freezing the run page.
 *
 * <p>This test stresses the assign-then-publish window with a slow
 * {@code redisPublisher.publishSequenced} mock so any unprotected call site
 * exposes the race deterministically.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowEventPublisher - concurrent multi-trigger ordering")
class WorkflowEventPublisherConcurrencyTest {

    @Mock
    private WorkflowEventBus bus;

    @Mock
    private com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher redisPublisher;

    @Mock
    private WorkflowRunRepository runRepository;

    private WorkflowEventPublisher publisher;
    private WsEventSequencer sequencer;
    private SeqPublishLockStripes stripes;

    @BeforeEach
    void setUp() {
        // No DB seed - counter starts at 0.
        when(runRepository.findByRunIdPublic(anyString())).thenReturn(Optional.empty());

        sequencer = new WsEventSequencer(runRepository);
        stripes = new SeqPublishLockStripes();

        publisher = new WorkflowEventPublisher(bus, true);
        ReflectionTestUtils.setField(publisher, "redisPublisher", redisPublisher);
        ReflectionTestUtils.setField(publisher, "wsEventSequencer", sequencer);
        ReflectionTestUtils.setField(publisher, "seqPublishLockStripes", stripes);
    }

    @Test
    @DisplayName("Concurrent publishes on same runId deliver to Redis in strictly-increasing seq order")
    void concurrentPublishesPreserveSeqOrderOnSameRunId() throws InterruptedException {
        String runId = "run-multi-trigger";
        int threads = 16;
        int eventsPerThread = 30;

        List<Long> deliveredSeqs = new ArrayList<>();

        // Slow publishSequenced widens the assign-then-publish window so that
        // an unprotected implementation deterministically inverts.
        doAnswer(invocation -> {
            long seq = invocation.getArgument(3);
            // Simulate Redis network latency with jitter.
            try {
                Thread.sleep(0, ThreadLocalRandom.current().nextInt(50_000, 200_000));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            synchronized (deliveredSeqs) {
                deliveredSeqs.add(seq);
            }
            return null;
        }).when(redisPublisher).publishSequenced(anyString(), anyString(), any(), org.mockito.ArgumentMatchers.anyLong());

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < eventsPerThread; i++) {
                        publisher.emitStep(runId, "mcp:step", null, StepLifecycle.RUNNING);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "Publish workers timed out");
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        // Every captured seq must be strictly larger than its predecessor -
        // the lock stripe guarantees nextSeq → publishSequenced is atomic per
        // runId, so the order Redis sees matches the order the FE expects.
        for (int i = 1; i < deliveredSeqs.size(); i++) {
            long prev = deliveredSeqs.get(i - 1);
            long cur = deliveredSeqs.get(i);
            assertTrue(cur > prev,
                "Out-of-order publishSequenced detected at index " + i + ": prev=" + prev + ", cur=" + cur);
        }
        // Sanity: we got the expected number of events.
        assertTrue(deliveredSeqs.size() == threads * eventsPerThread,
            "Expected " + (threads * eventsPerThread) + " events, got " + deliveredSeqs.size());
    }

    @Test
    @DisplayName("Concurrent publishes on DIFFERENT runIds do not serialize against each other")
    void concurrentPublishesOnDifferentRunIdsDoNotSerialize() throws InterruptedException {
        // Sanity: distinct runIds use distinct stripes (collision possible but
        // statistically rare). With two runIds chosen to land on different
        // stripes, throughput should be near 2× the single-run case. We just
        // assert no deadlock and ordering per-runId.
        String runA = "run-a";
        String runB = "run-b";
        // Probe: ensure they hash to different stripes so the test doesn't
        // accidentally collide. If they do collide, swap one until they differ.
        int stripeA = Math.floorMod(runA.hashCode(), SeqPublishLockStripes.STRIPES);
        int stripeB = Math.floorMod(runB.hashCode(), SeqPublishLockStripes.STRIPES);
        if (stripeA == stripeB) {
            runB = "run-b-alt"; // any string with different hash
        }

        List<Long> seqsA = new ArrayList<>();
        List<Long> seqsB = new ArrayList<>();

        doAnswer(invocation -> {
            String rid = invocation.getArgument(0);
            long seq = invocation.getArgument(3);
            if (rid.equals(runA)) {
                synchronized (seqsA) { seqsA.add(seq); }
            } else {
                synchronized (seqsB) { seqsB.add(seq); }
            }
            return null;
        }).when(redisPublisher).publishSequenced(anyString(), anyString(), any(), org.mockito.ArgumentMatchers.anyLong());

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch done = new CountDownLatch(8);
        final String finalRunB = runB;
        for (int t = 0; t < 4; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < 50; i++) {
                        publisher.emitStep(runA, "mcp:step", null, StepLifecycle.RUNNING);
                    }
                } finally {
                    done.countDown();
                }
            });
            pool.submit(() -> {
                try {
                    for (int i = 0; i < 50; i++) {
                        publisher.emitStep(finalRunB, "mcp:step", null, StepLifecycle.RUNNING);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS), "Publish workers timed out");
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        // Per-runId monotonicity preserved.
        for (int i = 1; i < seqsA.size(); i++) {
            assertTrue(seqsA.get(i) > seqsA.get(i - 1),
                "Run A out of order at index " + i);
        }
        for (int i = 1; i < seqsB.size(); i++) {
            assertTrue(seqsB.get(i) > seqsB.get(i - 1),
                "Run B out of order at index " + i);
        }
    }

    @Test
    @DisplayName("Two runIds COLLIDING on the same stripe still preserve per-runId monotonicity")
    void stripeCollisionPreservesPerRunIdMonotonicity() throws InterruptedException {
        // Construct two runIds that hash to the same stripe so the lock is
        // shared. Correctness must hold even though the two runs serialize
        // against each other.
        String runA = "run-collision-a";
        String runB = findRunIdHashCollidingWith(runA);

        List<Long> seqsA = new ArrayList<>();
        List<Long> seqsB = new ArrayList<>();

        doAnswer(invocation -> {
            String rid = invocation.getArgument(0);
            long seq = invocation.getArgument(3);
            // Add jitter so the unprotected path would inverse.
            try {
                Thread.sleep(0, ThreadLocalRandom.current().nextInt(50_000, 200_000));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (rid.equals(runA)) {
                synchronized (seqsA) { seqsA.add(seq); }
            } else {
                synchronized (seqsB) { seqsB.add(seq); }
            }
            return null;
        }).when(redisPublisher).publishSequenced(anyString(), anyString(), any(), org.mockito.ArgumentMatchers.anyLong());

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch done = new CountDownLatch(8);
        final String finalRunB = runB;
        for (int t = 0; t < 4; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < 30; i++) {
                        publisher.emitStep(runA, "mcp:step", null, StepLifecycle.RUNNING);
                    }
                } finally { done.countDown(); }
            });
            pool.submit(() -> {
                try {
                    for (int i = 0; i < 30; i++) {
                        publisher.emitStep(finalRunB, "mcp:step", null, StepLifecycle.RUNNING);
                    }
                } finally { done.countDown(); }
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS), "Publish workers timed out");
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        for (int i = 1; i < seqsA.size(); i++) {
            assertTrue(seqsA.get(i) > seqsA.get(i - 1),
                "Run A out of order under stripe collision at index " + i);
        }
        for (int i = 1; i < seqsB.size(); i++) {
            assertTrue(seqsB.get(i) > seqsB.get(i - 1),
                "Run B out of order under stripe collision at index " + i);
        }
    }

    @Test
    @DisplayName("Lock is released when publishSequenced throws - follow-up publishes still succeed")
    void lockReleasedOnPublishException() throws InterruptedException {
        String runId = "run-throw-then-recover";
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        // First call throws; subsequent calls succeed. If the lock leaked,
        // the second emitStep would hang (or fail under timeout below).
        doAnswer(invocation -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                throw new RuntimeException("simulated Redis blip");
            }
            return null;
        }).when(redisPublisher).publishSequenced(anyString(), anyString(), any(), org.mockito.ArgumentMatchers.anyLong());

        // Run from a worker so we can timeout if the lock leaked.
        Thread t = new Thread(() -> {
            publisher.emitStep(runId, "mcp:step", null, StepLifecycle.RUNNING);
            publisher.emitStep(runId, "mcp:step", null, StepLifecycle.RUNNING);
            publisher.emitStep(runId, "mcp:step", null, StepLifecycle.RUNNING);
        });
        t.start();
        t.join(5_000);
        assertTrue(!t.isAlive(), "Publish thread is still alive - lock likely leaked after exception");
        // 1 throw + 2 successes.
        assertTrue(calls.get() == 3, "Expected 3 publishSequenced calls, got " + calls.get());
    }

    private String findRunIdHashCollidingWith(String reference) {
        int targetStripe = Math.floorMod(reference.hashCode(), SeqPublishLockStripes.STRIPES);
        for (int i = 0; i < 1_000_000; i++) {
            String candidate = "collide-" + i;
            if (!candidate.equals(reference)
                && Math.floorMod(candidate.hashCode(), SeqPublishLockStripes.STRIPES) == targetStripe) {
                return candidate;
            }
        }
        throw new AssertionError("No colliding runId found in 1M attempts (improbable)");
    }
}
