package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Phase B.1 (archi-refoundation 2026-05-04) - markDirty + cleanup + lock
 * regression tests.
 *
 * <p>Critical invariants:
 * <ul>
 *   <li>markDirty short-circuits on terminal runs (no publish on ghosts)</li>
 *   <li>activeRunsCache prevents repeated DB queries on hot path (audits B+C v5 NA-2)</li>
 *   <li>cleanupRun pre-warms tombstone (defends against late signal-resume)</li>
 *   <li>RunScopedCache contract for auto-registration</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SnapshotService - markDirty + cleanup integration (Phase B.1)")
class SnapshotServiceMarkDirtyTest {

    @Mock
    private StateSnapshotService stateSnapshotService;
    @Mock
    private WorkflowStreamingService streamingService;
    @Mock
    private RunningNodeTracker runningNodeTracker;
    @Mock
    private WorkflowEpochService workflowEpochService;
    @Mock
    private WorkflowRunRepository runRepository;

    private SnapshotService snapshotService;

    @BeforeEach
    void setUp() {
        snapshotService = new SnapshotService(
                stateSnapshotService,
                streamingService,
                runningNodeTracker,
                workflowEpochService,
                runRepository,
                60L,    // activeCacheTtlSeconds
                1800L   // terminatedCacheTtlSeconds
        );
    }

    @Test
    @DisplayName("markDirty short-circuits on terminal runs (no publish on ghosts)")
    void markDirtyShortCircuitsOnTerminal() {
        when(runRepository.findStatusByRunIdPublic("run-completed"))
                .thenReturn(Optional.of(RunStatus.COMPLETED));

        snapshotService.markDirty("run-completed");

        // No streaming/publish should happen
        verifyNoInteractions(streamingService);
    }

    @Test
    @DisplayName("markDirty short-circuits on missing runs (treat as ghost)")
    void markDirtyTreatsMissingAsTerminal() {
        when(runRepository.findStatusByRunIdPublic("run-ghost"))
                .thenReturn(Optional.empty());

        snapshotService.markDirty("run-ghost");

        verifyNoInteractions(streamingService);
    }

    @Test
    @DisplayName("Positive cache: subsequent markDirty on active run does NOT re-query DB (hot path)")
    void activeCacheAvoidsRepeatedDbQueries() {
        when(runRepository.findStatusByRunIdPublic("run-active"))
                .thenReturn(Optional.of(RunStatus.RUNNING));

        // First call: DB query (cache miss)
        snapshotService.markDirty("run-active");
        // Subsequent calls: cache hit, NO DB query
        snapshotService.markDirty("run-active");
        snapshotService.markDirty("run-active");
        snapshotService.markDirty("run-active");

        verify(runRepository, times(1)).findStatusByRunIdPublic("run-active");
    }

    @Test
    @DisplayName("Tombstone cache: cleanupRun on a TERMINAL run pre-warms tombstone, late markDirty short-circuits")
    void cleanupPreWarmsTombstoneOnTerminal() {
        // Run is active during the live phase
        when(runRepository.findStatusByRunIdPublic("run-x"))
                .thenReturn(Optional.of(RunStatus.RUNNING));
        snapshotService.markDirty("run-x");
        verify(runRepository, times(1)).findStatusByRunIdPublic("run-x");

        // Run reaches a terminal state, then cleanupRun runs.
        clearInvocations(runRepository);
        when(runRepository.findStatusByRunIdPublic("run-x"))
                .thenReturn(Optional.of(RunStatus.COMPLETED));
        snapshotService.cleanupRun("run-x");
        // cleanupRun queried DB once to verify "actually terminal" before tombstoning.
        verify(runRepository, times(1)).findStatusByRunIdPublic("run-x");

        // Late markDirty (e.g. signal resume async tardif) hits the tombstone - NO new DB query
        clearInvocations(runRepository);
        snapshotService.markDirty("run-x");
        verifyNoMoreInteractions(runRepository);
    }

    @Test
    @DisplayName("Reusable trigger regression: cleanupRun on WAITING_TRIGGER does NOT tombstone, next fire's markDirty still publishes")
    void cleanupOnWaitingTriggerDoesNotPoisonTombstone() {
        // Fire #1 epoch close - RunContextRegistry.closeEpochForDagByTriggerId
        // calls cacheRegistry.cleanupRun(runId) here even though the run is
        // still WAITING_TRIGGER (non-terminal for reusable triggers).
        when(runRepository.findStatusByRunIdPublic("run-reusable"))
                .thenReturn(Optional.of(RunStatus.WAITING_TRIGGER));
        snapshotService.cleanupRun("run-reusable");
        verify(runRepository, times(1)).findStatusByRunIdPublic("run-reusable");

        // Fire #2 starts - markDirty must NOT be short-circuited by a stale tombstone.
        // Pre-fix: cleanupRun unconditionally `terminatedRunsCache.put(runId, TRUE)`,
        // so isTerminal would return true here and markDirty would no-op,
        // suppressing every batch-update for fire #2 → frozen UI.
        clearInvocations(runRepository);
        when(runRepository.findStatusByRunIdPublic("run-reusable"))
                .thenReturn(Optional.of(RunStatus.RUNNING));
        snapshotService.markDirty("run-reusable");
        // The streamingService is invoked (fail-open, publish proceeds) - assert
        // by checking that isTerminal performed the DB lookup (cache miss).
        verify(runRepository, times(1)).findStatusByRunIdPublic("run-reusable");
    }

    @Test
    @DisplayName("DB unreachable falls back fail-open (publish, not silence)")
    void dbFailureFailsOpen() {
        when(runRepository.findStatusByRunIdPublic("run-db-down"))
                .thenThrow(new RuntimeException("DB connection refused"));

        // Should NOT throw, should NOT block the publish - fail-open is "publish anyway"
        assertDoesNotThrow(() -> snapshotService.markDirty("run-db-down"));
    }

    @Test
    @DisplayName("Null runId is a defensive no-op")
    void nullRunIdNoOp() {
        assertDoesNotThrow(() -> snapshotService.markDirty(null));
        verifyNoInteractions(runRepository, streamingService);
    }

    @Test
    @DisplayName("Implements RunScopedCache contract for auto-registration with RunCacheRegistry")
    void runScopedCacheContract() {
        assertEquals("SnapshotService", snapshotService.getCacheName());
        assertEquals(RunScopedCache.CacheDomain.STREAMING, snapshotService.getDomain());
    }

    @Test
    @DisplayName("Concurrent doSendSnapshot vs cleanupRun: tombstone set under lock - no leak in lastPublishedSeq (B.4 race close)")
    void concurrentDoSendSnapshotVsCleanupCannotLeakLastPublishedSeq() throws Exception {
        // Audit 2026-05-06 round 3 P0 regression guard. Round-2's first attempt
        // raced cleanupRun against markDirty, but markDirty short-circuits at
        // its own isTerminal() gate when the DB returns COMPLETED, so the
        // publish path never ran and the test passed VACUOUSLY on both pre-fix
        // and post-fix code (3/5 audits flagged this).
        //
        // The actual race the fix closes is between cleanupRun and a
        // doSendSnapshot reaching the lock-protected publish block - typically
        // a deferred re-entry scheduled by the throttleScheduler after a
        // tryLock-fail. doSendSnapshot only checks the in-lock tombstone at
        // line ~443 (no isTerminal/DB query), so the leak is possible whenever
        // it acquires the stripe lock AFTER cleanupRun released it but BEFORE
        // cleanupRun's tombstone-set ran (pre-fix only - tombstone was set
        // outside the lock).
        //
        // We exercise this by invoking the private doSendSnapshot directly
        // via ReflectionTestUtils (same pattern as SnapshotServiceContentionTest)
        // and racing it 200 times against cleanupRun. Setup uses a real
        // empty StateSnapshot so buildSnapshotFromDb succeeds and the
        // lastPublishedSeq.put at line 493 is reachable when not tombstoned.
        //
        // Post-fix invariant: 0 leaks across 200 iterations. Pre-fix would
        // leak intermittently when cleanup wins the lock race and the racer
        // hits the unlock→tombstone-set window.
        com.apimarketplace.orchestrator.domain.execution.StateSnapshot emptySnap =
                com.apimarketplace.orchestrator.domain.execution.StateSnapshot.empty();
        org.mockito.Mockito.lenient().when(stateSnapshotService.getSnapshot(anyString()))
                .thenReturn(emptySnap);
        org.mockito.Mockito.lenient().when(stateSnapshotService.getPlanNodeIds(anyString()))
                .thenReturn(java.util.Set.of());
        org.mockito.Mockito.lenient().when(runningNodeTracker.getRunningCountsAcrossEpochs(anyString()))
                .thenReturn(java.util.Map.of());
        when(runRepository.findStatusByRunIdPublic(anyString()))
                .thenReturn(Optional.of(RunStatus.COMPLETED));

        java.lang.reflect.Field seqField = SnapshotService.class.getDeclaredField("lastPublishedSeq");
        seqField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<String, Long> seqMap =
                (java.util.concurrent.ConcurrentHashMap<String, Long>) seqField.get(snapshotService);

        java.util.concurrent.ExecutorService exec =
                java.util.concurrent.Executors.newFixedThreadPool(4);
        int iterations = 200;
        int leakCount = 0;
        try {
            for (int i = 0; i < iterations; i++) {
                String runId = "race-run-" + i;
                java.util.concurrent.CountDownLatch start =
                        new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.Future<?> cleanup = exec.submit(() -> {
                    try { start.await(); } catch (InterruptedException ignored) {}
                    snapshotService.cleanupRun(runId);
                });
                java.util.concurrent.Future<?> publish = exec.submit(() -> {
                    try { start.await(); } catch (InterruptedException ignored) {}
                    org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                            snapshotService, "doSendSnapshot", runId);
                });
                start.countDown();
                cleanup.get(5, java.util.concurrent.TimeUnit.SECONDS);
                publish.get(5, java.util.concurrent.TimeUnit.SECONDS);

                if (seqMap.containsKey(runId)) {
                    leakCount++;
                }
            }
        } finally {
            exec.shutdownNow();
        }

        assertEquals(0, leakCount,
                "Expected 0 lastPublishedSeq leaks across " + iterations + " concurrent "
                        + "(cleanupRun, doSendSnapshot) pairs. Got " + leakCount + ".\n"
                        + "Pre-fix race: cleanupRun released the stripe lock without yet "
                        + "setting the tombstone; doSendSnapshot then acquired the lock, saw "
                        + "no tombstone at line 443, proceeded to publish, and populated "
                        + "lastPublishedSeq AFTER terminal - a leak.\n"
                        + "Post-fix: tombstone is set INSIDE the lock before any cache mutation, "
                        + "so any racer that takes the lock observes it and bails before line 493's put.");
    }

    @Test
    @DisplayName("cleanupRun on TERMINAL is idempotent (safe to call multiple times)")
    void cleanupIsIdempotent() {
        when(runRepository.findStatusByRunIdPublic("run-y"))
                .thenReturn(Optional.of(RunStatus.COMPLETED));

        // Multiple cleanups - must not throw or change observable behavior
        assertDoesNotThrow(() -> {
            snapshotService.cleanupRun("run-y");
            snapshotService.cleanupRun("run-y");
            snapshotService.cleanupRun("run-y");
        });

        // Subsequent markDirty still short-circuits via tombstone
        clearInvocations(runRepository);
        snapshotService.markDirty("run-y");
        verifyNoInteractions(runRepository);
    }
}
