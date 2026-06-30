package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for SBS (step-by-step) mode parallel epoch support.
 *
 * Verifies that SBS mode correctly:
 * - Detects epoch completion (no more ready/running nodes)
 * - Closes completed epochs (removes from activeEpochs)
 * - Releases concurrency slot on epoch close
 * - Transitions run status based on remaining active epochs
 * - Sends post-close snapshot for frontend update
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReusableTriggerService - SBS Parallel Epoch Support")
class ReusableTriggerServiceSbsEpochTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private TriggerEpochManager epochManager;
    @Mock private WorkflowStreamingService streamingService;
    @Mock private WorkflowExecutionService executionService;
    @Mock private com.apimarketplace.orchestrator.services.TriggerResolverService triggerResolverService;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private EpochConcurrencyLimiter epochConcurrencyLimiter;
    @Mock private com.apimarketplace.orchestrator.trigger.queue.ExecutionQueue executionQueueService;
    @Mock private UnifiedSignalService unifiedSignalService;
    @Mock private SnapshotService snapshotService;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private CreditBudgetService creditBudgetService;
    @Mock private com.apimarketplace.orchestrator.services.resume.WorkflowResumeService resumeService;

    private ReusableTriggerService service;

    private static final String RUN_ID = "run-sbs-1";
    private static final String TRIGGER_ID = "trigger:my_webhook";

    @BeforeEach
    void setUp() {
        service = new ReusableTriggerService(
                runRepository, mock(com.apimarketplace.orchestrator.repository.WorkflowRepository.class),
                mock(com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository.class),
                epochManager, streamingService,
                executionService, triggerResolverService, stateSnapshotService,
                epochConcurrencyLimiter, executionQueueService, creditClient, creditBudgetService);
        ReflectionTestUtils.setField(service, "unifiedSignalService", unifiedSignalService);
        ReflectionTestUtils.setField(service, "snapshotService", snapshotService);
        ReflectionTestUtils.setField(service, "resumeService", resumeService);
        // Self-injection for @Transactional proxy
        ReflectionTestUtils.setField(service, "self", service);
    }

    // ==================== Helpers ====================

    private WorkflowRunEntity createRun(RunStatus status) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        ReflectionTestUtils.setField(run, "runIdPublic", RUN_ID);
        run.setStatus(status);
        run.setUpdatedAt(Instant.now());
        run.setMetadata(new HashMap<>());
        return run;
    }

    private StateSnapshot buildSnapshot(Map<String, DagState> dags) {
        StateSnapshot snapshot = StateSnapshot.empty();
        for (var entry : dags.entrySet()) {
            snapshot = snapshot.withDagState(entry.getKey(), entry.getValue());
        }
        return snapshot;
    }

    private DagState buildDagWithEpoch(int epoch, Set<String> ready, Set<String> running, Set<String> completed) {
        EpochState epochState = new EpochState(
                completed, Set.of(), Set.of(), running, ready, Set.of(),
                Map.of(), Map.of(), Map.of(), Instant.now());
        Map<Integer, EpochState> epochs = new HashMap<>();
        epochs.put(epoch, epochState);
        return new DagState(epoch, 0, 1, epochs, Set.of(epoch));
    }

    private DagState buildDagWithTwoEpochs(
            int epoch1, Set<String> ready1, Set<String> running1, Set<String> completed1,
            int epoch2, Set<String> ready2, Set<String> running2, Set<String> completed2) {
        EpochState es1 = new EpochState(completed1, Set.of(), Set.of(), running1, ready1, Set.of(),
                Map.of(), Map.of(), Map.of(), Instant.now());
        EpochState es2 = new EpochState(completed2, Set.of(), Set.of(), running2, ready2, Set.of(),
                Map.of(), Map.of(), Map.of(), Instant.now());
        Map<Integer, EpochState> epochs = new HashMap<>();
        epochs.put(epoch1, es1);
        epochs.put(epoch2, es2);
        return new DagState(epoch2, 0, 2, epochs, Set.of(epoch1, epoch2));
    }

    // ==================== SBS Auto-Close Previous Epochs ====================

    @Nested
    @DisplayName("SBS auto-close previous epochs on new trigger fire")
    class SbsAutoCloseEpochs {

        @Test
        @DisplayName("Should auto-close active epochs when SBS trigger fires while RUNNING")
        void shouldAutoCloseActiveEpochsInSbsMode() {
            // Arrange: run is RUNNING (previous epoch still active), SBS mode
            WorkflowRunEntity run = createRun(RunStatus.RUNNING);
            run.setExecutionMode(ExecutionMode.STEP_BY_STEP);

            Set<Integer> closedEpochs = Set.of(1, 2);
            when(stateSnapshotService.closeAllActiveEpochs(RUN_ID, TRIGGER_ID)).thenReturn(closedEpochs);
            // After auto-close, the run entity is reloaded from DB to avoid stale snapshot overwrite
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            // Act: call executeTriggerInternal - it will auto-close then proceed
            // We expect it to fail later (no mocks for epoch manager etc.), but auto-close should happen first
            try {
                service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);
            } catch (Exception ignored) {
                // Expected - we only care about the auto-close behavior
            }

            // Assert: auto-close was called
            verify(stateSnapshotService).closeAllActiveEpochs(RUN_ID, TRIGGER_ID);
            // Release concurrency for each closed epoch (+ 1 from resetRunOnFailure = 3 total)
            verify(epochConcurrencyLimiter, atLeast(2)).release(RUN_ID, TRIGGER_ID);
            // Cancel blocking signals for each closed epoch
            verify(unifiedSignalService).cancelBlockingByDagAndEpoch(RUN_ID, TRIGGER_ID, 1);
            verify(unifiedSignalService).cancelBlockingByDagAndEpoch(RUN_ID, TRIGGER_ID, 2);
        }

        @Test
        @DisplayName("Should auto-close active epochs when SBS trigger fires while PAUSED")
        void shouldAutoCloseActiveEpochsWhenPaused() {
            WorkflowRunEntity run = createRun(RunStatus.PAUSED);
            run.setExecutionMode(ExecutionMode.STEP_BY_STEP);

            Set<Integer> closedEpochs = Set.of(3);
            when(stateSnapshotService.closeAllActiveEpochs(RUN_ID, TRIGGER_ID)).thenReturn(closedEpochs);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            try {
                service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);
            } catch (Exception ignored) {}

            verify(stateSnapshotService).closeAllActiveEpochs(RUN_ID, TRIGGER_ID);
            // At least 1 release from auto-close (+ 1 from resetRunOnFailure)
            verify(epochConcurrencyLimiter, atLeast(1)).release(RUN_ID, TRIGGER_ID);
            verify(unifiedSignalService).cancelBlockingByDagAndEpoch(RUN_ID, TRIGGER_ID, 3);
        }

        @Test
        @DisplayName("Should still auto-close when WAITING_TRIGGER (handles stale epochs from rerun)")
        void shouldAutoCloseEvenWhenWaitingTrigger() {
            // After a trigger rerun, status is WAITING_TRIGGER but reactivateCurrentEpoch()
            // may have put the old epoch back in activeEpochs. Auto-close should always run.
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);
            run.setExecutionMode(ExecutionMode.STEP_BY_STEP);

            // Simulate stale epoch from rerun
            Set<Integer> closedEpochs = Set.of(1);
            when(stateSnapshotService.closeAllActiveEpochs(RUN_ID, TRIGGER_ID)).thenReturn(closedEpochs);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            try {
                service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);
            } catch (Exception ignored) {}

            // Should call closeAllActiveEpochs even with WAITING_TRIGGER
            verify(stateSnapshotService).closeAllActiveEpochs(RUN_ID, TRIGGER_ID);
            verify(epochConcurrencyLimiter, atLeast(1)).release(RUN_ID, TRIGGER_ID);
            verify(unifiedSignalService).cancelBlockingByDagAndEpoch(RUN_ID, TRIGGER_ID, 1);
        }

        @Test
        @DisplayName("Should NOT auto-close in AUTOMATIC mode (parallel epochs allowed)")
        void shouldNotAutoCloseInAutoMode() {
            WorkflowRunEntity run = createRun(RunStatus.RUNNING);
            run.setExecutionMode(ExecutionMode.AUTOMATIC);

            // Need to mock runRepository.save to avoid NPE in subsequent code
            when(runRepository.save(any())).thenReturn(run);

            try {
                service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);
            } catch (Exception ignored) {}

            verify(stateSnapshotService, never()).closeAllActiveEpochs(anyString(), anyString());
        }

        @Test
        @DisplayName("Should NOT auto-close when forceAutoMode is true even in SBS")
        void shouldNotAutoCloseWhenForceAutoMode() {
            WorkflowRunEntity run = createRun(RunStatus.RUNNING);
            run.setExecutionMode(ExecutionMode.STEP_BY_STEP);

            try {
                service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), true);
            } catch (Exception ignored) {}

            verify(stateSnapshotService, never()).closeAllActiveEpochs(anyString(), anyString());
        }

        @Test
        @DisplayName("Should handle no active epochs to close gracefully")
        void shouldHandleNoActiveEpochsGracefully() {
            WorkflowRunEntity run = createRun(RunStatus.RUNNING);
            run.setExecutionMode(ExecutionMode.STEP_BY_STEP);

            when(stateSnapshotService.closeAllActiveEpochs(RUN_ID, TRIGGER_ID)).thenReturn(Set.of());

            try {
                service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);
            } catch (Exception ignored) {}

            verify(stateSnapshotService).closeAllActiveEpochs(RUN_ID, TRIGGER_ID);
            // Auto-close path cancelled nothing (0 epochs closed). The failure path
            // (resetRunOnFailure) issues exactly one cancel for the current epoch (0 by mock default)
            // - this guards against zombie signals in the just-failed epoch and is unrelated to auto-close.
            verify(unifiedSignalService, times(1))
                .cancelBlockingByDagAndEpoch(eq(RUN_ID), eq(TRIGGER_ID), anyInt());
        }

        @Test
        @DisplayName("Should handle null triggerId without auto-close")
        void shouldHandleNullTriggerId() {
            WorkflowRunEntity run = createRun(RunStatus.RUNNING);
            run.setExecutionMode(ExecutionMode.STEP_BY_STEP);

            try {
                service.executeTriggerInternal(run, null, TriggerType.WEBHOOK, Map.of(), false);
            } catch (Exception ignored) {}

            // null triggerId → skip auto-close block
            verify(stateSnapshotService, never()).closeAllActiveEpochs(anyString(), anyString());
        }
    }

    // ==================== closeEpochIfCompleteForSbs ====================

    @Nested
    @DisplayName("closeEpochIfCompleteForSbs")
    class CloseEpochIfComplete {

        @Test
        @DisplayName("Should close epoch when no ready or running nodes remain")
        void shouldCloseEpochWhenComplete() {
            // Epoch 1: all nodes completed, none ready/running
            DagState dag = buildDagWithEpoch(1, Set.of(), Set.of(),
                    Set.of(TRIGGER_ID, "mcp:step1", "mcp:step2"));
            StateSnapshot snapshot = buildSnapshot(Map.of(TRIGGER_ID, dag));

            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);
            when(stateSnapshotService.hasAnyActiveEpoch(RUN_ID)).thenReturn(false);

            WorkflowRunEntity run = createRun(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(run));

            service.closeEpochIfCompleteForSbs(RUN_ID, TRIGGER_ID, 1);

            // Should close the epoch
            verify(stateSnapshotService).closeEpoch(RUN_ID, TRIGGER_ID, 1);
            // Should release concurrency slot
            verify(epochConcurrencyLimiter).release(RUN_ID, TRIGGER_ID);
            // Should send snapshot
            verify(snapshotService).sendSnapshotImmediate(RUN_ID);
        }

        @Test
        @DisplayName("Should NOT close epoch when ready nodes remain")
        void shouldNotCloseWhenReadyNodesExist() {
            // Epoch 1: mcp:step2 still ready
            DagState dag = buildDagWithEpoch(1, Set.of("mcp:step2"), Set.of(),
                    Set.of(TRIGGER_ID, "mcp:step1"));
            StateSnapshot snapshot = buildSnapshot(Map.of(TRIGGER_ID, dag));

            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);

            service.closeEpochIfCompleteForSbs(RUN_ID, TRIGGER_ID, 1);

            // Should NOT close the epoch
            verify(stateSnapshotService, never()).closeEpoch(anyString(), anyString(), anyInt());
            verify(epochConcurrencyLimiter, never()).release(anyString(), anyString());
        }

        @Test
        @DisplayName("Should NOT close epoch when running nodes exist")
        void shouldNotCloseWhenRunningNodesExist() {
            // Epoch 1: mcp:step2 still running
            DagState dag = buildDagWithEpoch(1, Set.of(), Set.of("mcp:step2"),
                    Set.of(TRIGGER_ID, "mcp:step1"));
            StateSnapshot snapshot = buildSnapshot(Map.of(TRIGGER_ID, dag));

            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);

            service.closeEpochIfCompleteForSbs(RUN_ID, TRIGGER_ID, 1);

            verify(stateSnapshotService, never()).closeEpoch(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("Should NOT close epoch when awaiting signal nodes exist")
        void shouldNotCloseWhenAwaitingSignalNodesExist() {
            // Epoch 1: mcp:approval awaiting signal
            EpochState epochState = new EpochState(
                    Set.of(TRIGGER_ID, "mcp:step1"), Set.of(), Set.of(),
                    Set.of(), Set.of(), Set.of("mcp:approval"),
                    Map.of(), Map.of(), Map.of(), Instant.now());
            Map<Integer, EpochState> epochs = new HashMap<>();
            epochs.put(1, epochState);
            DagState dag = new DagState(1, 0, 1, epochs, Set.of(1));
            StateSnapshot snapshot = buildSnapshot(Map.of(TRIGGER_ID, dag));

            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);

            service.closeEpochIfCompleteForSbs(RUN_ID, TRIGGER_ID, 1);

            verify(stateSnapshotService, never()).closeEpoch(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("Should set WAITING_TRIGGER when last epoch closes and trigger is ready")
        void shouldSetWaitingTriggerWhenLastEpochCloses() {
            DagState dag = buildDagWithEpoch(1, Set.of(), Set.of(),
                    Set.of(TRIGGER_ID, "mcp:step1"));
            StateSnapshot snapshot = buildSnapshot(Map.of(TRIGGER_ID, dag));

            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);
            when(stateSnapshotService.hasAnyActiveEpoch(RUN_ID)).thenReturn(false);

            WorkflowRunEntity run = createRun(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(run));

            service.closeEpochIfCompleteForSbs(RUN_ID, TRIGGER_ID, 1);

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(RunStatus.WAITING_TRIGGER);
        }

        @Test
        @DisplayName("Should keep RUNNING when other epochs still active")
        void shouldKeepRunningWhenOtherEpochsActive() {
            // Two epochs: epoch 1 complete, epoch 2 still has ready nodes
            DagState dag = buildDagWithTwoEpochs(
                    1, Set.of(), Set.of(), Set.of(TRIGGER_ID, "mcp:step1"),
                    2, Set.of("mcp:step1"), Set.of(), Set.of(TRIGGER_ID));
            StateSnapshot snapshot = buildSnapshot(Map.of(TRIGGER_ID, dag));

            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);
            // After closing epoch 1, epoch 2 is still active
            when(stateSnapshotService.hasAnyActiveEpoch(RUN_ID)).thenReturn(true);

            WorkflowRunEntity run = createRun(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(run));

            service.closeEpochIfCompleteForSbs(RUN_ID, TRIGGER_ID, 1);

            // Should close epoch 1
            verify(stateSnapshotService).closeEpoch(RUN_ID, TRIGGER_ID, 1);

            // Status should remain RUNNING (epoch 2 still active)
            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository).save(captor.capture());
            // PAUSED instead of RUNNING - SBS mode uses PAUSED to indicate user action needed
            assertThat(captor.getValue().getStatus()).isIn(RunStatus.RUNNING, RunStatus.PAUSED);
        }

        @Test
        @DisplayName("Should ignore trigger nodes in ready set when checking completion")
        void shouldIgnoreTriggerNodesInReadySet() {
            // Epoch 1: all steps done, but trigger:my_webhook still in ready (from prepareNextEpochReady)
            // The trigger being "ready" should NOT prevent epoch close
            DagState dag = buildDagWithEpoch(1,
                    Set.of(TRIGGER_ID), // trigger ready (from prepareNextEpochReady)
                    Set.of(),
                    Set.of(TRIGGER_ID, "mcp:step1", "mcp:step2"));
            StateSnapshot snapshot = buildSnapshot(Map.of(TRIGGER_ID, dag));

            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);
            when(stateSnapshotService.hasAnyActiveEpoch(RUN_ID)).thenReturn(false);

            WorkflowRunEntity run = createRun(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(run));

            service.closeEpochIfCompleteForSbs(RUN_ID, TRIGGER_ID, 1);

            // Should still close the epoch (trigger in ready doesn't count)
            verify(stateSnapshotService).closeEpoch(RUN_ID, TRIGGER_ID, 1);
        }

        @Test
        @DisplayName("Should be safe to call when epoch doesn't exist")
        void shouldHandleMissingEpoch() {
            // No DAG for this trigger
            StateSnapshot snapshot = buildSnapshot(Map.of());
            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);

            // Should not throw
            service.closeEpochIfCompleteForSbs(RUN_ID, TRIGGER_ID, 1);

            verify(stateSnapshotService, never()).closeEpoch(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("SBS epoch close triggers cycle-end cache cleanup excluding STREAMING (regression: per-run caches leaked between fires until OOM, 2026-05-06)")
        void cycleEndCleanupRunsOnSbsEpochCloseWhenIdle() {
            // No other epochs active → run is fully idle after this close → safe to
            // clean per-run caches. STREAMING domain MUST be excluded so the seq
            // counter and active-run cache survive across fires (else fire #N+1
            // events get strict-< dropped by the FE - original 2026-05-05 incident).
            DagState dag = buildDagWithEpoch(1, Set.of(), Set.of(),
                    Set.of(TRIGGER_ID, "mcp:step1"));
            StateSnapshot snapshot = buildSnapshot(Map.of(TRIGGER_ID, dag));
            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);
            when(stateSnapshotService.hasAnyActiveEpoch(RUN_ID)).thenReturn(false);

            WorkflowRunEntity run = createRun(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(run));

            service.closeEpochIfCompleteForSbs(RUN_ID, TRIGGER_ID, 1);

            verify(resumeService).clearCachedStateForRerun(
                eq(RUN_ID),
                eq(java.util.Set.of(
                    com.apimarketplace.orchestrator.services.cache.RunScopedCache.CacheDomain.STREAMING)));
        }

        @Test
        @DisplayName("SBS epoch close skips cleanup when other epochs still active (parallel-epoch protection)")
        void cycleEndCleanupSkippedWhenOtherEpochsActive() {
            DagState dag = buildDagWithTwoEpochs(
                    1, Set.of(), Set.of(), Set.of(TRIGGER_ID, "mcp:step1"),
                    2, Set.of("mcp:step1"), Set.of(), Set.of(TRIGGER_ID));
            StateSnapshot snapshot = buildSnapshot(Map.of(TRIGGER_ID, dag));
            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);
            // Epoch 2 still active - anyActive=true, cleanup MUST NOT run (would
            // purge state in-flight for the still-running epoch).
            when(stateSnapshotService.hasAnyActiveEpoch(RUN_ID)).thenReturn(true);

            WorkflowRunEntity run = createRun(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(run));

            service.closeEpochIfCompleteForSbs(RUN_ID, TRIGGER_ID, 1);

            verify(resumeService, org.mockito.Mockito.never())
                .clearCachedStateForRerun(anyString(), org.mockito.ArgumentMatchers.anySet());
        }

        @Test
        @DisplayName("Cancels blocking signals before pruning the epoch (regression: WAIT_TIMER zombie loop, 2026-05-06)")
        void cancelsBlockingSignalsBeforePruningClosedEpoch() {
            // Epoch 1: ready empty, running empty - completion path triggered
            DagState dag = buildDagWithEpoch(1, Set.of(), Set.of(),
                    Set.of(TRIGGER_ID, "mcp:step1", "mcp:step2"));
            StateSnapshot snapshot = buildSnapshot(Map.of(TRIGGER_ID, dag));

            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);
            when(stateSnapshotService.hasAnyActiveEpoch(RUN_ID)).thenReturn(false);

            WorkflowRunEntity run = createRun(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(run));

            service.closeEpochIfCompleteForSbs(RUN_ID, TRIGGER_ID, 1);

            // Order matters: signals must be cancelled BEFORE the epoch is pruned,
            // otherwise zombie WAIT_TIMER signals are resolved later by the timer pollers
            // and re-execute nodes whose epoch state no longer exists.
            var inOrder = inOrder(unifiedSignalService, stateSnapshotService);
            inOrder.verify(unifiedSignalService).cancelBlockingByDagAndEpoch(RUN_ID, TRIGGER_ID, 1);
            inOrder.verify(stateSnapshotService).closeEpoch(RUN_ID, TRIGGER_ID, 1);
        }

        @Test
        @DisplayName("Defers release / clearCachedStateForRerun / sendPostResetSnapshot to AFTER commit when TX sync is active (regression: side-effects ran in-tx, race with peer instance acquiring slot before commit visible 2026-05-09)")
        void defersSideEffectsToAfterCommitWhenTxSyncActive() {
            DagState dag = buildDagWithEpoch(1, Set.of(), Set.of(),
                    Set.of(TRIGGER_ID, "mcp:step1"));
            StateSnapshot snapshot = buildSnapshot(Map.of(TRIGGER_ID, dag));
            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);
            when(stateSnapshotService.hasAnyActiveEpoch(RUN_ID)).thenReturn(false);

            WorkflowRunEntity run = createRun(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(run));

            // Simulate an active outer TX - registerSynchronization succeeds and the
            // afterCommit callback fires only when we manually drive it below.
            org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
            try {
                service.closeEpochIfCompleteForSbs(RUN_ID, TRIGGER_ID, 1);

                // DB writes already executed in-tx
                verify(stateSnapshotService).closeEpoch(RUN_ID, TRIGGER_ID, 1);
                verify(runRepository).save(any());

                // The 3 side-effects are DEFERRED - must not be observed yet.
                // Pre-fix this assertion would fail (release/cleanup/snapshot happened in-tx).
                verify(epochConcurrencyLimiter, never()).release(anyString(), anyString());
                verify(resumeService, never()).clearCachedStateForRerun(anyString(), anySet());
                verify(snapshotService, never()).sendSnapshotImmediate(anyString());

                // Drive afterCommit callbacks (simulates the outer TX committing).
                var syncs = org.springframework.transaction.support.TransactionSynchronizationManager
                        .getSynchronizations();
                assertThat(syncs).as("expected exactly one afterCommit registration").hasSize(1);
                for (var sync : syncs) {
                    sync.afterCommit();
                }

                // Now the deferred side-effects must have run, in declared order.
                var inOrder = inOrder(epochConcurrencyLimiter, resumeService, snapshotService);
                inOrder.verify(epochConcurrencyLimiter).release(RUN_ID, TRIGGER_ID);
                inOrder.verify(resumeService).clearCachedStateForRerun(eq(RUN_ID), anySet());
                inOrder.verify(snapshotService).sendSnapshotImmediate(RUN_ID);
            } finally {
                org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            }
        }

        @Test
        @DisplayName("After commit with peer epoch still active: defers release+snapshot but skips clearCachedStateForRerun (defensive: ensures the cache-skip branch is preserved across the afterCommit refactor)")
        void anyActiveDefersReleaseAndSnapshotButSkipsCacheCleanup() {
            // Two epochs active; epoch 1 closes, epoch 2 still has ready nodes.
            DagState dag = buildDagWithTwoEpochs(
                    1, Set.of(), Set.of(), Set.of(TRIGGER_ID, "mcp:step1"),
                    2, Set.of("mcp:step1"), Set.of(), Set.of(TRIGGER_ID));
            StateSnapshot snapshot = buildSnapshot(Map.of(TRIGGER_ID, dag));
            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);
            when(stateSnapshotService.hasAnyActiveEpoch(RUN_ID)).thenReturn(true);

            WorkflowRunEntity run = createRun(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(run));

            org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
            try {
                service.closeEpochIfCompleteForSbs(RUN_ID, TRIGGER_ID, 1);

                for (var sync : org.springframework.transaction.support.TransactionSynchronizationManager
                        .getSynchronizations()) {
                    sync.afterCommit();
                }

                verify(epochConcurrencyLimiter).release(RUN_ID, TRIGGER_ID);
                verify(snapshotService).sendSnapshotImmediate(RUN_ID);
                // Critical: cache cleanup MUST be skipped - purging streaming state
                // while a peer epoch is still emitting events would tear down its seq
                // counter mid-flight (regression: cycle-end purge between fires, OOM 2026-05-06).
                verify(resumeService, never()).clearCachedStateForRerun(anyString(), anySet());
            } finally {
                org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            }
        }

        @Test
        @DisplayName("Runs side-effects inline when no TX sync is active (recovery / unit-test path)")
        void runsSideEffectsInlineWhenNoTxSync() {
            DagState dag = buildDagWithEpoch(1, Set.of(), Set.of(),
                    Set.of(TRIGGER_ID, "mcp:step1"));
            StateSnapshot snapshot = buildSnapshot(Map.of(TRIGGER_ID, dag));
            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);
            when(stateSnapshotService.hasAnyActiveEpoch(RUN_ID)).thenReturn(false);

            WorkflowRunEntity run = createRun(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(run));

            // No TransactionSynchronizationManager.initSynchronization() - sync is NOT active.
            service.closeEpochIfCompleteForSbs(RUN_ID, TRIGGER_ID, 1);

            // runAfterCommitOrNow runs the action inline → side-effects observed immediately.
            verify(epochConcurrencyLimiter).release(RUN_ID, TRIGGER_ID);
            verify(resumeService).clearCachedStateForRerun(eq(RUN_ID), anySet());
            verify(snapshotService).sendSnapshotImmediate(RUN_ID);
        }

        @Test
        @DisplayName("Should be safe to call when epoch is not in activeEpochs")
        void shouldHandleInactiveEpoch() {
            // Epoch exists but not active (already closed)
            EpochState epochState = new EpochState(
                    Set.of(TRIGGER_ID, "mcp:step1"), Set.of(), Set.of(),
                    Set.of(), Set.of(), Set.of(),
                    Map.of(), Map.of(), Map.of(), Instant.now());
            Map<Integer, EpochState> epochs = new HashMap<>();
            epochs.put(1, epochState);
            // activeEpochs is empty - epoch 1 already closed
            DagState dag = new DagState(1, 0, 1, epochs, Set.of());
            StateSnapshot snapshot = buildSnapshot(Map.of(TRIGGER_ID, dag));

            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);

            service.closeEpochIfCompleteForSbs(RUN_ID, TRIGGER_ID, 1);

            // Already closed, should not close again
            verify(stateSnapshotService, never()).closeEpoch(anyString(), anyString(), anyInt());
        }
    }

    // ==================== Snapshot per-epoch ready nodes ====================

    @Nested
    @DisplayName("Per-epoch ready node information")
    class PerEpochReadyNodes {

        @Test
        @DisplayName("Epoch ready nodes should be accessible from EpochState")
        void epochStateContainsReadyNodes() {
            EpochState es = new EpochState(
                    Set.of(TRIGGER_ID), Set.of(), Set.of(),
                    Set.of(), Set.of("mcp:step1", "mcp:step2"), Set.of(),
                    Map.of(), Map.of(), Map.of(), Instant.now());

            assertThat(es.getReadyNodeIds()).containsExactlyInAnyOrder("mcp:step1", "mcp:step2");
        }

        @Test
        @DisplayName("Two active epochs should have independent ready node sets")
        void twoEpochsHaveIndependentReadyNodes() {
            DagState dag = buildDagWithTwoEpochs(
                    1, Set.of("mcp:step2"), Set.of(), Set.of(TRIGGER_ID, "mcp:step1"),
                    2, Set.of("mcp:step1"), Set.of(), Set.of(TRIGGER_ID));

            EpochState es1 = dag.getEpochState(1);
            EpochState es2 = dag.getEpochState(2);

            assertThat(es1.getReadyNodeIds()).containsExactly("mcp:step2");
            assertThat(es2.getReadyNodeIds()).containsExactly("mcp:step1");
        }
    }

    // ==================== SBS final snapshot (run-view live statusCounts) ====================

    @Nested
    @DisplayName("SBS fire pushes a final accumulated snapshot (run-view statusCounts live-update)")
    class SbsFinalSnapshot {

        private WorkflowExecution buildExecution() {
            Trigger trigger = new Trigger("wh1", "My Webhook", "receive_one", "webhook");
            WorkflowPlan plan = new WorkflowPlan(null, null, List.of(trigger), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
            return new WorkflowExecution(RUN_ID, plan, Map.of());
        }

        @Test
        @DisplayName("Should push a final batch-update snapshot after an SBS trigger fire (mirrors handleAutoMode)")
        void shouldSendSnapshotAfterStepByStepFire() {
            // Regression: handleStepByStepMode emitted only workflowStatus + readySteps and NEVER a
            // snapshot, unlike handleAutoMode. The frontend repaints node/edge statusCounts ONLY from
            // the batch-update snapshot (per-node stepStatus/edgeStatus deltas are intentionally
            // ignored), so without this push the run-view stayed one epoch stale until a manual refresh.
            WorkflowRunEntity run = createRun(RunStatus.RUNNING); // non-terminal
            when(stateSnapshotService.getReadyNodeIds(RUN_ID)).thenReturn(Set.of(TRIGGER_ID));
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            ReflectionTestUtils.invokeMethod(service, "handleStepByStepMode",
                    run, buildExecution(), Set.of(TRIGGER_ID), TRIGGER_ID, TriggerType.MANUAL, RUN_ID, 1);

            // Pre-fix this verify FAILS (no snapshot was ever pushed on the SBS path).
            verify(snapshotService).sendSnapshotImmediate(RUN_ID);
        }

        @Test
        @DisplayName("Should push the snapshot AFTER the status save so it reads committed state")
        void shouldSendSnapshotAfterStatusSave() {
            WorkflowRunEntity run = createRun(RunStatus.RUNNING);
            when(stateSnapshotService.getReadyNodeIds(RUN_ID)).thenReturn(Set.of(TRIGGER_ID));
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            ReflectionTestUtils.invokeMethod(service, "handleStepByStepMode",
                    run, buildExecution(), Set.of(TRIGGER_ID), TRIGGER_ID, TriggerType.MANUAL, RUN_ID, 1);

            org.mockito.InOrder inOrder = inOrder(runRepository, snapshotService);
            inOrder.verify(runRepository).save(any(WorkflowRunEntity.class));
            inOrder.verify(snapshotService).sendSnapshotImmediate(RUN_ID);
        }

        @Test
        @DisplayName("Should NOT push a snapshot when the run is already terminal (terminal guard intact)")
        void shouldNotSendSnapshotWhenTerminal() {
            // A run cancelled between trigger execution and this status write must not be touched:
            // no status overwrite AND no snapshot push.
            WorkflowRunEntity run = createRun(RunStatus.CANCELLED);
            when(stateSnapshotService.getReadyNodeIds(RUN_ID)).thenReturn(Set.of(TRIGGER_ID));
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            ReflectionTestUtils.invokeMethod(service, "handleStepByStepMode",
                    run, buildExecution(), Set.of(TRIGGER_ID), TRIGGER_ID, TriggerType.MANUAL, RUN_ID, 1);

            verify(snapshotService, never()).sendSnapshotImmediate(anyString());
            verify(runRepository, never()).save(any(WorkflowRunEntity.class));
        }
    }
}
