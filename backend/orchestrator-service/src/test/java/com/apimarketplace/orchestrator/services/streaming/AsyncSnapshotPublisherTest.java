package com.apimarketplace.orchestrator.services.streaming;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;

import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("Plan v4 §8 - AsyncSnapshotPublisher")
class AsyncSnapshotPublisherTest {

    @Mock SnapshotService snapshotService;
    @Mock TaskExecutor executor;

    SimpleMeterRegistry meterRegistry;
    AsyncSnapshotPublisher publisherAsyncOn;
    AsyncSnapshotPublisher publisherAsyncOff;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        publisherAsyncOn = new AsyncSnapshotPublisher(snapshotService, executor, meterRegistry, true);
        publisherAsyncOff = new AsyncSnapshotPublisher(snapshotService, executor, meterRegistry, false);
    }

    @Nested
    @DisplayName("max-seq guard - drops stale (nodeId, epochId) events")
    class StaleSeqGuard {

        @Test
        @DisplayName("First publish for (nodeId, epoch) goes through; second with same seq drops as stale")
        void firstAcceptedSecondStale() {
            mockSyncExecutor();

            publisherAsyncOn.publishAsync("run-1", 10L, "mcp:step1", 1);
            publisherAsyncOn.publishAsync("run-1", 10L, "mcp:step1", 1);

            // The dedup guard could ALSO catch this (same runId+seq within 100ms),
            // so the second drop may attribute to dedup, NOT stale.
            // What we can verify deterministically: only ONE dispatched.
            assertThat(meterRegistry.counter("orchestrator.async_publish.dispatched_count").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("Lower seq for the same (nodeId, epoch) drops as stale (not dedup - different seq)")
        void lowerSeqDropsStale() {
            mockSyncExecutor();

            publisherAsyncOn.publishAsync("run-1", 100L, "mcp:step1", 1);
            publisherAsyncOn.publishAsync("run-1", 50L, "mcp:step1", 1);

            assertThat(meterRegistry.counter("orchestrator.async_publish.stale_drop_count").count())
                    .isEqualTo(1.0);
            assertThat(meterRegistry.counter("orchestrator.async_publish.dispatched_count").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("Different (nodeId, epoch) is tracked independently")
        void differentNodeEpochIndependent() {
            mockSyncExecutor();

            publisherAsyncOn.publishAsync("run-1", 100L, "mcp:step1", 1);
            publisherAsyncOn.publishAsync("run-1", 50L, "mcp:step2", 1);  // different node
            publisherAsyncOn.publishAsync("run-1", 50L, "mcp:step1", 2);  // same node, different epoch

            // 50L and 50L would dedup-collide on (runId=run-1, seq=50) so 1 hits dedup
            // First publish ok, second ok (different node), third deduped (same runId+seq=50)
            assertThat(meterRegistry.counter("orchestrator.async_publish.stale_drop_count").count())
                    .isEqualTo(0.0);
        }

        @Test
        @DisplayName("nodeId == null skips the stale guard entirely (run-level events)")
        void nullNodeIdSkipsGuard() {
            mockSyncExecutor();

            publisherAsyncOn.publishAsync("run-1", 10L, null, 0);
            publisherAsyncOn.publishAsync("run-1", 20L, null, 0);

            // Both go through (dedup is on (runId, seq); 10 ≠ 20 so no dedup)
            assertThat(meterRegistry.counter("orchestrator.async_publish.dispatched_count").count())
                    .isEqualTo(2.0);
            assertThat(meterRegistry.counter("orchestrator.async_publish.stale_drop_count").count())
                    .isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Caffeine dedup - 100ms window on (runId, seq)")
    class DedupGuard {

        @Test
        @DisplayName("Same (runId, seq) within 100ms - second drops via dedup")
        void sameRunIdSameSeqDedups() {
            mockSyncExecutor();

            // null nodeId → stale guard bypassed; only dedup applies
            publisherAsyncOn.publishAsync("run-1", 42L, null, 0);
            publisherAsyncOn.publishAsync("run-1", 42L, null, 0);

            assertThat(meterRegistry.counter("orchestrator.async_publish.dedup_drop_count").count())
                    .isEqualTo(1.0);
            assertThat(meterRegistry.counter("orchestrator.async_publish.dispatched_count").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("Different runId, same seq - both go through (dedup is per-runId)")
        void differentRunIdSameSeqBothGoThrough() {
            mockSyncExecutor();

            publisherAsyncOn.publishAsync("run-1", 42L, null, 0);
            publisherAsyncOn.publishAsync("run-2", 42L, null, 0);

            assertThat(meterRegistry.counter("orchestrator.async_publish.dispatched_count").count())
                    .isEqualTo(2.0);
            assertThat(meterRegistry.counter("orchestrator.async_publish.dedup_drop_count").count())
                    .isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Plan v4 §8 - minimal overload publishAsync(runId) for markDirty path")
    class MinimalOverload {

        @Test
        @DisplayName("Flag ON - dispatches to executor + calls markDirty in worker")
        void asyncOnDispatchesToExecutor() {
            mockSyncExecutor();

            publisherAsyncOn.publishAsync("run-1");

            verify(snapshotService, times(1)).markDirty(eq("run-1"));
            assertThat(meterRegistry.counter("orchestrator.async_publish.dispatched_count").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("Flag OFF - runs markDirty inline on caller thread (no executor)")
        void asyncOffRunsInline() {
            publisherAsyncOff.publishAsync("run-1");

            verify(executor, never()).execute(any(Runnable.class));
            verify(snapshotService, times(1)).markDirty(eq("run-1"));
        }

        @Test
        @DisplayName("null runId → no-op (defensive)")
        void nullRunIdIsNoOp() {
            publisherAsyncOn.publishAsync((String) null);

            verify(executor, never()).execute(any(Runnable.class));
            verify(snapshotService, never()).markDirty(any());
        }

        @Test
        @DisplayName("Executor REJECTED → drop counter + WARN log; markDirty NOT called")
        void rejectedDispatchDrops() {
            doThrow(new RejectedExecutionException("queue full"))
                    .when(executor).execute(any(Runnable.class));

            publisherAsyncOn.publishAsync("run-1");

            assertThat(meterRegistry.counter("orchestrator.async_publish.drop_count").count())
                    .isEqualTo(1.0);
            verify(snapshotService, never()).markDirty(any());
        }
    }

    @Nested
    @DisplayName("Executor bounded - REJECTED → drop + metric")
    class ExecutorRejection {

        @Test
        @DisplayName("RejectedExecutionException from executor increments drop counter and logs")
        void rejectedDispatchDrops() {
            doThrow(new RejectedExecutionException("queue full"))
                    .when(executor).execute(any(Runnable.class));

            publisherAsyncOn.publishAsync("run-1", 1L, "mcp:step1", 0);

            assertThat(meterRegistry.counter("orchestrator.async_publish.drop_count").count())
                    .isEqualTo(1.0);
            // markDirty NOT called: REJECTED means the runnable never ran
            verify(snapshotService, never()).markDirty(any());
        }
    }

    @Nested
    @DisplayName("Feature flag - async-publish OFF runs synchronously, guards still apply")
    class FeatureFlagOff {

        @Test
        @DisplayName("Flag OFF - markDirty called synchronously on caller thread")
        void flagOffSyncPath() {
            publisherAsyncOff.publishAsync("run-1", 1L, "mcp:step1", 0);

            // Executor NEVER touched
            verify(executor, never()).execute(any(Runnable.class));
            // markDirty called inline
            verify(snapshotService, times(1)).markDirty(eq("run-1"));
        }

        @Test
        @DisplayName("Flag OFF - dedup guard still drops duplicate (runId, seq)")
        void flagOffDedupStillApplies() {
            publisherAsyncOff.publishAsync("run-1", 1L, null, 0);
            publisherAsyncOff.publishAsync("run-1", 1L, null, 0);

            verify(snapshotService, times(1)).markDirty(eq("run-1"));
            assertThat(meterRegistry.counter("orchestrator.async_publish.dedup_drop_count").count())
                    .isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Run lifecycle - onRunTerminated clears per-(nodeId, epochId) tracking")
    class RunLifecycle {

        @Test
        @DisplayName("After onRunTerminated, the stale guard for the run is reset")
        void terminatedRunResetsStaleGuard() {
            mockSyncExecutor();

            publisherAsyncOn.publishAsync("run-1", 100L, "mcp:step1", 0);
            publisherAsyncOn.onRunTerminated("run-1");
            // After reset, a new fire at seq=50 should NOT be flagged stale
            publisherAsyncOn.publishAsync("run-1", 50L, "mcp:step1", 0);

            assertThat(meterRegistry.counter("orchestrator.async_publish.stale_drop_count").count())
                    .isEqualTo(0.0);
            assertThat(meterRegistry.counter("orchestrator.async_publish.dispatched_count").count())
                    .isEqualTo(2.0);
        }

        @Test
        @DisplayName("onRunTerminated for run-1 does NOT clear run-2 tracking")
        void terminationIsRunScoped() {
            mockSyncExecutor();

            publisherAsyncOn.publishAsync("run-1", 100L, "mcp:step1", 0);
            publisherAsyncOn.publishAsync("run-2", 100L, "mcp:step1", 0);
            publisherAsyncOn.onRunTerminated("run-1");
            // run-2 still tracked: seq 50 < 100 → stale drop
            publisherAsyncOn.publishAsync("run-2", 50L, "mcp:step1", 0);

            assertThat(meterRegistry.counter("orchestrator.async_publish.stale_drop_count").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("RunScopedCache.cleanupRun delegates to onRunTerminated - RunCacheRegistry auto-wire path")
        void runScopedCacheContract() {
            mockSyncExecutor();
            publisherAsyncOn.publishAsync("run-1", 100L, "mcp:step1", 0);
            assertThat(publisherAsyncOn.getCacheSize()).isEqualTo(1);

            // Same call the RunCacheRegistry will invoke on run termination
            publisherAsyncOn.cleanupRun("run-1");

            assertThat(publisherAsyncOn.getCacheSize()).isZero();
            assertThat(publisherAsyncOn.getCacheName())
                    .isEqualTo("AsyncSnapshotPublisher.maxSeqByNodeEpoch");
            assertThat(publisherAsyncOn.getDomain())
                    .isEqualTo(com.apimarketplace.orchestrator.services.cache.RunScopedCache.CacheDomain.STREAMING);
        }
    }

    /** Run executor.execute(r) inline so we can assert downstream state. */
    private void mockSyncExecutor() {
        doAnswer(inv -> {
            Runnable r = inv.getArgument(0);
            r.run();
            return null;
        }).when(executor).execute(any(Runnable.class));
    }
}
