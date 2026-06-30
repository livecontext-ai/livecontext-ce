package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
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
 * Tests for the signal-blocking fix in ReusableTriggerService.
 *
 * Verifies the critical fix: hasActiveSignalsForTrigger() now uses
 * hasBlockingSignalsForDagAndEpoch() instead of hasActiveSignalsForDag().
 *
 * This fixes TWO bugs:
 * 1. INTERFACE_SIGNAL (blocking when __continue is in actionMapping, non-blocking otherwise)
 *    was incorrectly treated as always non-blocking, causing epochs with blocking interface
 *    nodes to reset prematurely; now blocking is driven by the blocking=true column
 * 2. DAG-wide signal check (not epoch-scoped) caused bulk-resolved
 *    USER_APPROVAL epochs to skip reset when other epochs had active signals
 *    → Earlier epochs never got endedAt set
 *
 * Scope:
 * - handleAutoMode(): signal check before resetForNextCycle
 * - resetForNextCycle(): double-check under lock before reset
 * - Both use hasActiveSignalsForTrigger() which is the fixed method
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReusableTriggerService - Signal Blocking Fix")
class ReusableTriggerServiceSignalBlockingTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private TriggerEpochManager epochManager;
    @Mock private WorkflowStreamingService streamingService;
    @Mock private WorkflowExecutionService executionService;
    @Mock private com.apimarketplace.orchestrator.services.TriggerResolverService triggerResolverService;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private EpochConcurrencyLimiter epochConcurrencyLimiter;
    @Mock private com.apimarketplace.orchestrator.trigger.queue.ExecutionQueue executionQueueService;
    @Mock private UnifiedSignalService unifiedSignalService;
    @Mock private com.apimarketplace.orchestrator.execution.v2.async.PendingAgentRegistry pendingAgentRegistry;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private CreditBudgetService creditBudgetService;

    private ReusableTriggerService service;

    @BeforeEach
    void setUp() {
        service = new ReusableTriggerService(
                runRepository, mock(com.apimarketplace.orchestrator.repository.WorkflowRepository.class),
                mock(com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository.class),
                epochManager, streamingService,
                executionService, triggerResolverService, stateSnapshotService,
                epochConcurrencyLimiter, executionQueueService, creditClient, creditBudgetService);

        // Inject @Autowired fields via reflection
        ReflectionTestUtils.setField(service, "unifiedSignalService", unifiedSignalService);
        ReflectionTestUtils.setField(service, "pendingAgentRegistry", pendingAgentRegistry);
        // Self-proxy: in tests, just use the same instance (no @Transactional proxy needed)
        ReflectionTestUtils.setField(service, "self", service);
    }

    // ==================== Helper Methods ====================

    private WorkflowPlan buildPlan(List<Trigger> triggers) {
        return new WorkflowPlan(null, null, triggers, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
    }

    private WorkflowExecution buildExecution(String runId, WorkflowPlan plan) {
        return new WorkflowExecution(runId, plan, Map.of());
    }

    private WorkflowRunEntity createRun(String runId, RunStatus status) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        ReflectionTestUtils.setField(run, "runIdPublic", runId);
        run.setStatus(status);
        run.setUpdatedAt(Instant.now());
        return run;
    }

    // ==================== INTERFACE_SIGNAL auto-advance (non-blocking) ====================

    @Nested
    @DisplayName("INTERFACE_SIGNAL (auto-advance) should not block epoch reset")
    class InterfaceSignalNonBlockingTests {

        @Test
        @DisplayName("resetForNextCycle should proceed when only INTERFACE_SIGNAL is active (epoch-scoped)")
        void shouldResetWhenOnlyInterfaceSignalActive() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("m1", "Manual Start", "receive_one", "manual");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            // explicitEpoch=1, so getCurrentEpoch is NOT called

            // INTERFACE_SIGNAL is active but NOT blocking
            when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual_start", 1))
                .thenReturn(false);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:manual_start", false, 1);

            // Should proceed with reset (save the run)
            verify(runRepository).save(any(WorkflowRunEntity.class));
        }

        @Test
        @DisplayName("resetForNextCycle should NOT call hasActiveSignalsForDag (old non-epoch-scoped method)")
        void shouldNotUseOldDagWideMethod() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("m1", "Manual", "receive_one", "manual");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            // explicitEpoch=2, so getCurrentEpoch is NOT called
            when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 2))
                .thenReturn(false);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:manual", false, 2);

            // The old buggy method should NOT be called
            verify(unifiedSignalService, never()).hasActiveSignalsForDag(anyString(), anyString());
            // The new epoch-scoped blocking method SHOULD be called
            verify(unifiedSignalService).hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 2);
        }

        @Test
        @DisplayName("Should set endedAt on epoch when INTERFACE_SIGNAL is the only active signal")
        void shouldSetEndedAtWithInterfaceSignal() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("m1", "Start", "receive_one", "manual");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            // No blocking signals (INTERFACE_SIGNAL excluded)
            // explicitEpoch=3, so getCurrentEpoch is NOT called
            when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:start", 3))
                .thenReturn(false);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:start", false, 3);

            // Verify the run was saved
            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository).save(captor.capture());
            assertThat(captor.getValue().getMetadata()).containsEntry("lastCycleEpoch", 3);
        }
    }

    // ==================== USER_APPROVAL epoch-scoped ====================

    @Nested
    @DisplayName("USER_APPROVAL should use epoch-scoped blocking check")
    class UserApprovalEpochScopedTests {

        @Test
        @DisplayName("resetForNextCycle should skip reset when blocking signal exists in THIS epoch")
        void shouldSkipResetWhenBlockingSignalInSameEpoch() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("m1", "Manual", "receive_one", "manual");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            // explicitEpoch=2, so getCurrentEpoch is NOT called

            // Blocking signal EXISTS in epoch 2 (USER_APPROVAL pending)
            when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 2))
                .thenReturn(true);

            int result = service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:manual", false, 2);

            // Should skip reset (no save)
            verify(runRepository, never()).save(any());
            assertThat(result).isEqualTo(2); // Returns current epoch without reset
        }

        @Test
        @DisplayName("regression: resolved blocking signal with pending async resume still blocks epoch reset")
        void shouldSkipResetWhenSignalResumeStillFinalizing() {
            WorkflowRunEntity run = createRun("run-race", RunStatus.RUNNING);
            Trigger trigger = new Trigger("start", "Start", "receive_one", "manual");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-race", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-race")).thenReturn(Optional.of(run));
            when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch("run-race", "trigger:start", 1))
                .thenReturn(false);
            when(unifiedSignalService.hasPendingSignalResumesForDagAndEpoch("run-race", "trigger:start", 1))
                .thenReturn(true);

            int result = service.resetForNextCycle(run, execution, plan, "run-race",
                    TriggerType.MANUAL, "trigger:start", false, 1);

            verify(runRepository, never()).save(any());
            verify(unifiedSignalService).hasPendingSignalResumesForDagAndEpoch("run-race", "trigger:start", 1);
            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("Epoch 1 should reset even when epoch 2 has active USER_APPROVAL signal")
        void shouldResetEpoch1WhenEpoch2HasActiveSignal() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("m1", "Manual", "receive_one", "manual");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));

            // Epoch 1: no blocking signals (its USER_APPROVAL was already resolved)
            when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 1))
                .thenReturn(false);
            // Other epochs still active (epoch 2 has pending signal)
            when(stateSnapshotService.hasAnyActiveEpoch("run-1")).thenReturn(true);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:manual", false, 1);

            // Should proceed with reset for epoch 1
            verify(runRepository).save(any(WorkflowRunEntity.class));
            // Should close epoch 1
            verify(stateSnapshotService).closeEpoch("run-1", "trigger:manual", 1);
        }

        @Test
        @DisplayName("Epoch 2 should reset even when epoch 3 has active USER_APPROVAL signal")
        void shouldResetEpoch2WhenEpoch3HasActiveSignal() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("m1", "Manual", "receive_one", "manual");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));

            // Epoch 2: no blocking signals in this specific epoch
            when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 2))
                .thenReturn(false);
            // Other epochs still active
            when(stateSnapshotService.hasAnyActiveEpoch("run-1")).thenReturn(true);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:manual", false, 2);

            // Should proceed with reset for epoch 2
            verify(runRepository).save(any(WorkflowRunEntity.class));
            verify(stateSnapshotService).closeEpoch("run-1", "trigger:manual", 2);
        }

        @Test
        @DisplayName("Bulk resolve: all 3 epochs should reset independently")
        void shouldResetAllEpochsIndependentlyOnBulkResolve() {
            Trigger trigger = new Trigger("m1", "Manual", "receive_one", "manual");
            WorkflowPlan plan = buildPlan(List.of(trigger));

            // Simulate 3 sequential resets (as would happen during bulk signal resolution)
            for (int epoch = 1; epoch <= 3; epoch++) {
                WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
                WorkflowExecution execution = buildExecution("run-1", plan);

                when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
                // Each epoch has no blocking signals (just resolved)
                when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", epoch))
                    .thenReturn(false);
                // Other epochs might still be active
                when(stateSnapshotService.hasAnyActiveEpoch("run-1")).thenReturn(epoch < 3);

                service.resetForNextCycle(run, execution, plan, "run-1",
                        TriggerType.MANUAL, "trigger:manual", false, epoch);
            }

            // All 3 epochs should have been saved (reset completed)
            verify(runRepository, times(3)).save(any(WorkflowRunEntity.class));
            // All 3 epochs should be closed
            verify(stateSnapshotService).closeEpoch("run-1", "trigger:manual", 1);
            verify(stateSnapshotService).closeEpoch("run-1", "trigger:manual", 2);
            verify(stateSnapshotService).closeEpoch("run-1", "trigger:manual", 3);
        }
    }

    // ==================== Parallel epoch isolation ====================

    @Nested
    @DisplayName("Parallel epoch isolation - each epoch resets independently")
    class ParallelEpochIsolationTests {

        @Test
        @DisplayName("Run status should be RUNNING when other epochs are still active")
        void shouldStayRunningWhenOtherEpochsActive() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("wh1", "Webhook", "receive_one", "webhook");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:webhook", 1))
                .thenReturn(false);
            // Other epochs still active (epoch 2 running)
            when(stateSnapshotService.hasAnyActiveEpoch("run-1")).thenReturn(true);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.WEBHOOK, "trigger:webhook", false, 1);

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository).save(captor.capture());
            // Should stay RUNNING (not WAITING_TRIGGER) because other epochs active
            assertThat(captor.getValue().getStatus()).isEqualTo(RunStatus.RUNNING);
        }

        @Test
        @DisplayName("Run status should be WAITING_TRIGGER when last epoch completes")
        void shouldTransitionToWaitingTriggerWhenLastEpoch() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("wh1", "Webhook", "receive_one", "webhook");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:webhook", 3))
                .thenReturn(false);
            // No other active epochs
            when(stateSnapshotService.hasAnyActiveEpoch("run-1")).thenReturn(false);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.WEBHOOK, "trigger:webhook", false, 3);

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(RunStatus.WAITING_TRIGGER);
        }

        @Test
        @DisplayName("Only last epoch should call resetDagWithRerunPattern (no phantom epochs)")
        void shouldOnlyCreateNextEpochOnLastReset() {
            Trigger trigger = new Trigger("wh1", "Webhook", "receive_one", "webhook");
            WorkflowPlan plan = buildPlan(List.of(trigger));

            // Epoch 1 resets (epoch 2 still active)
            WorkflowRunEntity run1 = createRun("run-1", RunStatus.RUNNING);
            WorkflowExecution exec1 = buildExecution("run-1", plan);
            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run1));
            when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:webhook", 1))
                .thenReturn(false);
            when(stateSnapshotService.hasAnyActiveEpoch("run-1")).thenReturn(true);

            service.resetForNextCycle(run1, exec1, plan, "run-1",
                    TriggerType.WEBHOOK, "trigger:webhook", false, 1);

            // Should NOT create next epoch (other epochs still active)
            verify(epochManager, never()).resetDagWithRerunPattern(anyString(), any(), anyString());

            // Epoch 2 resets (last active epoch)
            WorkflowRunEntity run2 = createRun("run-1", RunStatus.RUNNING);
            WorkflowExecution exec2 = buildExecution("run-1", plan);
            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run2));
            when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:webhook", 2))
                .thenReturn(false);
            when(stateSnapshotService.hasAnyActiveEpoch("run-1")).thenReturn(false);

            service.resetForNextCycle(run2, exec2, plan, "run-1",
                    TriggerType.WEBHOOK, "trigger:webhook", false, 2);

            // NOW should create next epoch (last epoch completed)
            verify(epochManager).resetDagWithRerunPattern("run-1", plan, "trigger:webhook");
        }
    }

    // ==================== Mixed signal types ====================

    @Nested
    @DisplayName("Mixed signal types - blocking vs non-blocking")
    class MixedSignalTypeTests {

        @Test
        @DisplayName("Should skip reset when WAIT_TIMER signal is active in epoch")
        void shouldSkipResetForWaitTimer() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("m1", "Manual", "receive_one", "manual");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            // explicitEpoch=1, so getCurrentEpoch is NOT called

            // WAIT_TIMER is blocking
            when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 1))
                .thenReturn(true);

            int result = service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:manual", false, 1);

            // Should skip reset
            verify(runRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should skip reset when WEBHOOK_WAIT signal is active in epoch")
        void shouldSkipResetForWebhookWait() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("m1", "Manual", "receive_one", "manual");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            // explicitEpoch=1, so getCurrentEpoch is NOT called

            // WEBHOOK_WAIT is blocking
            when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 1))
                .thenReturn(true);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:manual", false, 1);

            verify(runRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should reset when blocking signal resolved but INTERFACE_SIGNAL still active")
        void shouldResetAfterBlockingResolvedWithInterfaceStillActive() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("m1", "Manual", "receive_one", "manual");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            // explicitEpoch=1, so getCurrentEpoch is NOT called

            // No BLOCKING signals (INTERFACE_SIGNAL still active but excluded)
            when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 1))
                .thenReturn(false);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:manual", false, 1);

            // Should proceed with reset
            verify(runRepository).save(any(WorkflowRunEntity.class));
        }
    }

    // ==================== Explicit epoch parameter ====================

    @Nested
    @DisplayName("Explicit epoch parameter handling")
    class ExplicitEpochTests {

        @Test
        @DisplayName("Should use explicitEpoch when >= 0 (not getCurrentEpoch)")
        void shouldUseExplicitEpochWhenProvided() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("m1", "Manual", "receive_one", "manual");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 5))
                .thenReturn(false);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:manual", false, 5);

            // Should use explicit epoch 5, NOT call getCurrentEpoch
            verify(epochManager, never()).getCurrentEpoch(any(WorkflowRunEntity.class), anyString());
            verify(unifiedSignalService).hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 5);
        }

        @Test
        @DisplayName("Should fall back to getCurrentEpoch when explicitEpoch is -1")
        void shouldFallbackToGetCurrentEpochWhenMinusOne() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("m1", "Manual", "receive_one", "manual");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), eq("trigger:manual"))).thenReturn(7);
            // explicitEpoch=-1 → resolves to epoch 7 via getCurrentEpoch
            // Then hasActiveSignalsForTrigger(runId, triggerId, 7) uses epoch-scoped check (epoch >= 0)
            when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 7))
                .thenReturn(false);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:manual", false, -1);

            // Should use getCurrentEpoch fallback (since explicitEpoch=-1)
            verify(epochManager).getCurrentEpoch(any(WorkflowRunEntity.class), eq("trigger:manual"));
            // Should use epoch-scoped blocking check with resolved epoch 7
            verify(unifiedSignalService).hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 7);
        }
    }

    // ==================== Null triggerId fallback ====================

    @Nested
    @DisplayName("Null triggerId - run-wide blocking check")
    class NullTriggerIdTests {

        @Test
        @DisplayName("Should use run-wide hasBlockingSignals when triggerId is null")
        void shouldUseRunWideBlockingCheckWhenTriggerIdNull() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("wh1", "Webhook", "receive_one", "webhook");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class))).thenReturn(2);
            // Run-wide blocking check (no triggerId to scope)
            when(unifiedSignalService.hasBlockingSignals("run-1")).thenReturn(false);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.WEBHOOK, null, false, -1);

            // Should use run-wide blocking check
            verify(unifiedSignalService).hasBlockingSignals("run-1");
            verify(unifiedSignalService, never()).hasBlockingSignalsForDagAndEpoch(anyString(), anyString(), anyInt());
            verify(unifiedSignalService, never()).hasActiveSignalsForDag(anyString(), anyString());
        }

        @Test
        @DisplayName("Should skip reset when run-wide blocking signals exist (null triggerId)")
        void shouldSkipResetWhenRunWideBlockingSignalsExist() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("wh1", "Webhook", "receive_one", "webhook");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class))).thenReturn(2);
            when(unifiedSignalService.hasBlockingSignals("run-1")).thenReturn(true);

            int result = service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.WEBHOOK, null, false, -1);

            verify(runRepository, never()).save(any());
        }
    }

    // ==================== Regression: old bug scenarios ====================

    @Nested
    @DisplayName("Regression tests - previously broken scenarios")
    class RegressionTests {

        @Test
        @DisplayName("BUG FIX: Interface node epoch should not stay running forever")
        void interfaceNodeEpochShouldNotStayRunning() {
            // Scenario:
            // trigger:start -> core:download -> interface:form
            // Download completes, interface registers INTERFACE_SIGNAL
            // Epoch should complete (endedAt set) despite INTERFACE_SIGNAL being active

            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("m1", "Manual", "receive_one", "manual");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));

            // INTERFACE_SIGNAL is active but NOT blocking
            when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 1))
                .thenReturn(false);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:manual", false, 1);

            verify(runRepository).save(any(WorkflowRunEntity.class));
        }

        @Test
        @DisplayName("BUG FIX: Bulk-resolved USER_APPROVAL - all epochs should get endedAt")
        void bulkResolvedApprovalAllEpochsShouldGetEndedAt() {
            // Scenario:
            // 3 epochs, each with USER_APPROVAL signal
            // Bulk resolve resolves all 3 signals
            // Each epoch's deferred reset should succeed independently
            // Previously: epoch 1 and 2 skipped reset because DAG-wide check saw signals from other epochs

            Trigger trigger = new Trigger("m1", "Manual", "receive_one", "manual");
            WorkflowPlan plan = buildPlan(List.of(trigger));

            for (int epoch = 1; epoch <= 3; epoch++) {
                WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
                WorkflowExecution execution = buildExecution("run-1", plan);

                when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
                // Each epoch: its OWN signal was just resolved → no blocking signals for THIS epoch
                when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", epoch))
                    .thenReturn(false);
                when(stateSnapshotService.hasAnyActiveEpoch("run-1")).thenReturn(epoch < 3);

                service.resetForNextCycle(run, execution, plan, "run-1",
                        TriggerType.MANUAL, "trigger:manual", false, epoch);
            }

            // All 3 epochs should be closed via stateSnapshotService
            verify(stateSnapshotService).closeEpoch("run-1", "trigger:manual", 1);
            verify(stateSnapshotService).closeEpoch("run-1", "trigger:manual", 2);
            verify(stateSnapshotService).closeEpoch("run-1", "trigger:manual", 3);
        }

        @Test
        @DisplayName("BUG FIX: Epoch-scoped check prevents cross-epoch interference")
        void epochScopedCheckPreventsCrossEpochInterference() {
            // Scenario:
            // Epoch 1: USER_APPROVAL resolved → deferred reset
            // Epoch 2: USER_APPROVAL still PENDING
            // Old bug: epoch 1's reset saw epoch 2's signal via DAG-wide check → skipped
            // Fix: epoch 1's reset uses epoch-scoped check → only checks epoch 1's signals

            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("m1", "Manual", "receive_one", "manual");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));

            // Epoch 1: no blocking signals (resolved)
            when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 1))
                .thenReturn(false);
            // Epoch 2 still has active signals (but should NOT affect epoch 1)
            when(stateSnapshotService.hasAnyActiveEpoch("run-1")).thenReturn(true);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:manual", false, 1);

            // Epoch 1 should reset successfully despite epoch 2 having active signals
            verify(runRepository).save(any(WorkflowRunEntity.class));
            verify(stateSnapshotService).closeEpoch("run-1", "trigger:manual", 1);

            // Status should stay RUNNING (epoch 2 still active)
            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(RunStatus.RUNNING);
        }
    }

    // ==================== Async-agent (PendingAgentRegistry) blocking ====================

    /**
     * hasActiveSignalsForTrigger() blocks an epoch reset on TWO independent conditions:
     * blocking SignalWait rows AND in-flight async agents (PendingAgentRegistry). The signal
     * branch is covered above; these tests pin the async-agent branch - the "classify after
     * guardrail split" guard: a split yields an async agent (NOT a signal), and if the epoch
     * closes early the classify successor arrives to an empty ready-node set and never runs.
     */
    @Nested
    @DisplayName("Async-agent in-flight should defer epoch reset (classify-after-guardrail-split guard)")
    class AsyncAgentBlockingTests {

        @Test
        @DisplayName("Skip reset when an async agent is still pending in THIS epoch (no blocking signal)")
        void shouldSkipResetWhenAsyncAgentPendingInEpoch() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("m1", "Manual", "receive_one", "manual");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            // No blocking SignalWait rows...
            when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 1))
                .thenReturn(false);
            // ...but an inline/classify/guardrail agent is still running async in epoch 1.
            when(pendingAgentRegistry.hasPendingFor("run-1", "trigger:manual", 1)).thenReturn(true);

            int result = service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:manual", false, 1);

            // Epoch must NOT close - else the classify successor arrives to an empty ready set.
            verify(runRepository, never()).save(any());
            verify(stateSnapshotService, never()).closeEpoch(anyString(), anyString(), anyInt());
            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("Proceed with reset when no blocking signal AND no pending async agent")
        void shouldResetWhenNoSignalAndNoPendingAgent() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("m1", "Manual", "receive_one", "manual");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 1))
                .thenReturn(false);
            when(pendingAgentRegistry.hasPendingFor("run-1", "trigger:manual", 1)).thenReturn(false);
            when(stateSnapshotService.hasAnyActiveEpoch("run-1")).thenReturn(false);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:manual", false, 1);

            // The async check was consulted and, being false, allowed the reset to proceed.
            verify(pendingAgentRegistry).hasPendingFor("run-1", "trigger:manual", 1);
            verify(runRepository).save(any(WorkflowRunEntity.class));
            verify(stateSnapshotService).closeEpoch("run-1", "trigger:manual", 1);
        }

        @Test
        @DisplayName("Null triggerId uses the run-wide pending-agent check (not the epoch-scoped one)")
        void shouldUseRunWidePendingCheckWhenTriggerIdNull() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("wh1", "Webhook", "receive_one", "webhook");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class))).thenReturn(2);
            when(unifiedSignalService.hasBlockingSignals("run-1")).thenReturn(false);
            // An async agent is pending somewhere in the run → run-wide check defers the reset.
            when(pendingAgentRegistry.hasAnyPendingForRun("run-1")).thenReturn(true);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.WEBHOOK, null, false, -1);

            verify(pendingAgentRegistry).hasAnyPendingForRun("run-1");
            verify(pendingAgentRegistry, never()).hasPendingFor(anyString(), anyString(), anyInt());
            verify(runRepository, never()).save(any());
        }
    }
}
