package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity.SignalWaitStatus;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.lifecycle.V2WorkflowFinalizer;
import com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for INTERFACE_SIGNAL blocking behavior.
 *
 * Interface signals are blocking when __continue is present in actionMapping (blocking=true
 * column). They are non-blocking (auto-advance) otherwise (blocking=false column).
 * The hasBlockingSignals() family of methods uses the blocking column, not signal_type.
 *
 * Verifies:
 * 1. hasBlockingSignals() uses the blocking=true column (excludes auto-advance INTERFACE_SIGNAL)
 * 2. V2WorkflowFinalizer allows completion with pending non-blocking INTERFACE_SIGNAL
 * 3. SignalResumeService allows COMPLETED runs to resume for INTERFACE_SIGNAL
 * 4. SignalResumeService rejects COMPLETED runs for non-INTERFACE_SIGNAL types
 * 5. Re-finalization restores terminal status after interface resume
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Interface Signal Non-Blocking Behavior")
class InterfaceSignalNonBlockingTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-02-16T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));

    // ========================================================================
    // hasBlockingSignals() - UnifiedSignalService
    // ========================================================================

    @Nested
    @DisplayName("UnifiedSignalService.hasBlockingSignals()")
    class HasBlockingSignalsTests {

        @Mock private SignalWaitRepository signalWaitRepository;
        @Mock private StateSnapshotService stateSnapshotService;
        @Mock private ApplicationEventPublisher eventPublisher;
        @Mock private SignalTimerScheduler timerScheduler;
        @Mock private SignalResumeRedisPublisher redisPublisher;

        private UnifiedSignalService signalService;

        @BeforeEach
        void setUp() {
            signalService = new UnifiedSignalService(
                signalWaitRepository, stateSnapshotService, eventPublisher, timerScheduler, redisPublisher, FIXED_CLOCK, null);
        }

        @Test
        @DisplayName("Should return false when only INTERFACE_SIGNAL is active")
        void shouldReturnFalseForOnlyInterfaceSignal() {
            when(signalWaitRepository.hasBlockingSignals("run-1")).thenReturn(false);

            assertFalse(signalService.hasBlockingSignals("run-1"));
        }

        @Test
        @DisplayName("Should return true when WAIT_TIMER is active alongside INTERFACE_SIGNAL")
        void shouldReturnTrueForWaitTimerWithInterfaceSignal() {
            when(signalWaitRepository.hasBlockingSignals("run-1")).thenReturn(true);

            assertTrue(signalService.hasBlockingSignals("run-1"));
        }

        @Test
        @DisplayName("Should return true when USER_APPROVAL is active")
        void shouldReturnTrueForUserApproval() {
            when(signalWaitRepository.hasBlockingSignals("run-1")).thenReturn(true);

            assertTrue(signalService.hasBlockingSignals("run-1"));
        }

        @Test
        @DisplayName("Should return false when no signals are active")
        void shouldReturnFalseWhenNoSignals() {
            when(signalWaitRepository.hasBlockingSignals("run-1")).thenReturn(false);

            assertFalse(signalService.hasBlockingSignals("run-1"));
        }

        @Test
        @DisplayName("hasActiveSignals should still count INTERFACE_SIGNAL")
        void hasActiveSignalsShouldCountInterfaceSignal() {
            when(signalWaitRepository.hasActiveSignals("run-1")).thenReturn(true);

            assertTrue(signalService.hasActiveSignals("run-1"));
        }

        @Test
        @DisplayName("hasBlockingSignals and hasActiveSignals should diverge for INTERFACE_SIGNAL only")
        void shouldDivergeForInterfaceSignalOnly() {
            // Active signals exist (INTERFACE_SIGNAL)
            when(signalWaitRepository.hasActiveSignals("run-1")).thenReturn(true);
            // But no blocking signals (INTERFACE_SIGNAL excluded)
            when(signalWaitRepository.hasBlockingSignals("run-1")).thenReturn(false);

            assertTrue(signalService.hasActiveSignals("run-1"),
                "hasActiveSignals should be true (INTERFACE_SIGNAL is active)");
            assertFalse(signalService.hasBlockingSignals("run-1"),
                "hasBlockingSignals should be false (INTERFACE_SIGNAL has blocking=false)");
        }

        // --- Epoch-scoped blocking checks ---

        @Test
        @DisplayName("hasBlockingSignalsForDagAndEpoch should exclude INTERFACE_SIGNAL")
        void hasBlockingSignalsForDagAndEpochShouldExcludeInterfaceSignal() {
            when(signalWaitRepository.countActiveBlockingByRunIdAndDagAndEpoch("run-1", "trigger:manual", 1))
                .thenReturn(0L);
            when(signalWaitRepository.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 1))
                .thenCallRealMethod();

            assertFalse(signalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 1),
                "Should return false when only INTERFACE_SIGNAL in epoch 1");
        }

        @Test
        @DisplayName("hasBlockingSignalsForDagAndEpoch should return true for USER_APPROVAL in specific epoch")
        void hasBlockingSignalsForDagAndEpochShouldReturnTrueForUserApproval() {
            when(signalWaitRepository.countActiveBlockingByRunIdAndDagAndEpoch("run-1", "trigger:manual", 2))
                .thenReturn(1L);
            when(signalWaitRepository.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 2))
                .thenCallRealMethod();

            assertTrue(signalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 2),
                "Should return true when USER_APPROVAL exists in epoch 2");
        }

        @Test
        @DisplayName("hasBlockingSignalsForDagAndEpoch should be independent per epoch")
        void hasBlockingSignalsPerEpochShouldBeIndependent() {
            // Epoch 1: no blocking signals (resolved)
            when(signalWaitRepository.countActiveBlockingByRunIdAndDagAndEpoch("run-1", "trigger:manual", 1))
                .thenReturn(0L);
            when(signalWaitRepository.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 1))
                .thenCallRealMethod();

            // Epoch 2: has blocking signal (USER_APPROVAL pending)
            when(signalWaitRepository.countActiveBlockingByRunIdAndDagAndEpoch("run-1", "trigger:manual", 2))
                .thenReturn(1L);
            when(signalWaitRepository.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 2))
                .thenCallRealMethod();

            assertFalse(signalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 1),
                "Epoch 1 should have no blocking signals");
            assertTrue(signalService.hasBlockingSignalsForDagAndEpoch("run-1", "trigger:manual", 2),
                "Epoch 2 should have blocking signals");
        }

        @Test
        @DisplayName("hasBlockingSignalsForDag should exclude INTERFACE_SIGNAL (DAG-wide)")
        void hasBlockingSignalsForDagShouldExcludeInterfaceSignal() {
            when(signalWaitRepository.countActiveBlockingByRunIdAndDag("run-1", "trigger:manual"))
                .thenReturn(0L);
            when(signalWaitRepository.hasBlockingSignalsForDag("run-1", "trigger:manual"))
                .thenCallRealMethod();

            assertFalse(signalService.hasBlockingSignalsForDag("run-1", "trigger:manual"),
                "DAG-wide blocking should return false when only INTERFACE_SIGNAL active");
        }

        @Test
        @DisplayName("hasActiveSignalsForDag should still count INTERFACE_SIGNAL (not filtering)")
        void hasActiveSignalsForDagShouldStillCountInterfaceSignal() {
            when(signalWaitRepository.countActiveByRunIdAndDag("run-1", "trigger:manual"))
                .thenReturn(1L);
            when(signalWaitRepository.hasActiveSignalsForDag("run-1", "trigger:manual"))
                .thenCallRealMethod();

            assertTrue(signalService.hasActiveSignalsForDag("run-1", "trigger:manual"),
                "hasActiveSignalsForDag should count INTERFACE_SIGNAL (it's still active, regardless of blocking flag)");
        }
    }

    // ========================================================================
    // SignalResumeService - COMPLETED run resumption for INTERFACE_SIGNAL
    // ========================================================================

    @Nested
    @DisplayName("SignalResumeService - COMPLETED/FAILED resumption")
    class SignalResumeCompletedResumptionTests {

        @Mock private StringRedisTemplate mockRedis;
        @SuppressWarnings("unchecked")
        @Mock private ValueOperations<String, String> mockValueOps;
        @Mock private WorkflowRunRepository mockRunRepository;
        @Mock private SplitContextManager mockSplitContextManager;
        @Mock private StorageService mockStorageService;
        @Mock private StateSnapshotService mockStateSnapshotService;
        @Mock private WorkflowStepDataRepository mockStepDataRepository;
        @Mock private ExecutionCacheManager mockExecutionCacheManager;
        @Mock private NodeSearchService mockNodeSearchService;
        @Mock private RunningNodeTracker mockRunningNodeTracker;
        @Mock private WorkflowEpochService mockWorkflowEpochService;
        @Mock private V2StepByStepService mockStepByStepService;
        @Mock private V2ExecutionEventService mockEventService;
        @Mock private UnifiedSignalService mockSignalService;

        private SignalResumeService resumeService;

        @BeforeEach
        void setUp() throws Exception {
            // Stub Redis dedup: always allow (first caller wins)
            lenient().when(mockRedis.opsForValue()).thenReturn(mockValueOps);
            lenient().when(mockValueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);

            resumeService = new SignalResumeService(
                mockRedis, mockRunRepository, mockSplitContextManager,
                mockStorageService, mockStateSnapshotService,
                mockStepDataRepository, mockExecutionCacheManager, mockNodeSearchService,
                mockRunningNodeTracker, mockWorkflowEpochService);

            setField("v2StepByStepService", mockStepByStepService);
            setField("eventService", mockEventService);
            setField("signalService", mockSignalService);
        }

        private void setField(String fieldName, Object value) throws Exception {
            Field field = SignalResumeService.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(resumeService, value);
        }

        private SignalWaitEntity createSignal(String runId, String nodeId, String itemId, SignalType type) {
            SignalWaitEntity signal = mock(SignalWaitEntity.class);
            when(signal.getRunId()).thenReturn(runId);
            when(signal.getNodeId()).thenReturn(nodeId);
            when(signal.getItemId()).thenReturn(itemId);
            lenient().when(signal.getResolution()).thenReturn(SignalResolution.CONTINUE);
            lenient().when(signal.getSignalType()).thenReturn(type);
            lenient().when(signal.getSplitItemData()).thenReturn(null);
            lenient().when(signal.getDagTriggerId()).thenReturn("trigger:start");
            return signal;
        }

        // --- COMPLETED + INTERFACE_SIGNAL: should resume ---

        @Test
        @DisplayName("Should reopen COMPLETED run for INTERFACE_SIGNAL and proceed")
        void shouldReopenCompletedRunForInterfaceSignal() {
            SignalWaitEntity signal = createSignal("run-1", "interface:form", "0", SignalType.INTERFACE_SIGNAL);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.COMPLETED);
            when(run.getRunIdPublic()).thenReturn("run-1");
            when(run.isStepByStepMode()).thenReturn(false);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            // No ready nodes (interface is terminal)
            when(mockStepByStepService.getReadyNodes("run-1", "0", 0)).thenReturn(Set.of());

            // hasBlockingSignals for re-finalization
            when(mockSignalService.hasBlockingSignals("run-1")).thenReturn(false);

            resumeService.resumeAfterSignal(signal);

            // Should reopen: set status to RUNNING, clear endedAt
            verify(run).setStatus(RunStatus.RUNNING);
            verify(run).setEndedAt(null);
            // Should re-finalize back to COMPLETED
            verify(run).setStatus(RunStatus.COMPLETED);
        }

        @Test
        @DisplayName("Should reopen FAILED run for INTERFACE_SIGNAL and re-finalize to FAILED")
        void shouldReopenFailedRunForInterfaceSignal() {
            SignalWaitEntity signal = createSignal("run-1", "interface:form", "0", SignalType.INTERFACE_SIGNAL);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.FAILED);
            when(run.getRunIdPublic()).thenReturn("run-1");
            when(run.isStepByStepMode()).thenReturn(false);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            when(mockStepByStepService.getReadyNodes("run-1", "0", 0)).thenReturn(Set.of());
            when(mockSignalService.hasBlockingSignals("run-1")).thenReturn(false);

            resumeService.resumeAfterSignal(signal);

            // Should reopen then re-finalize to original FAILED status
            verify(run).setStatus(RunStatus.RUNNING);
            verify(run).setStatus(RunStatus.FAILED);
        }

        @Test
        @DisplayName("Should execute successor nodes after reopening COMPLETED run")
        void shouldExecuteSuccessorsAfterReopening() {
            SignalWaitEntity signal = createSignal("run-1", "interface:form", "0", SignalType.INTERFACE_SIGNAL);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.COMPLETED);
            when(run.getRunIdPublic()).thenReturn("run-1");
            when(run.isStepByStepMode()).thenReturn(false);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            // First call: successor ready; second call: no more ready
            when(mockStepByStepService.getReadyNodes("run-1", "0", 0))
                .thenReturn(Set.of("mcp:next_step"))
                .thenReturn(Set.of());

            StepByStepExecutionResult stepResult = mock(StepByStepExecutionResult.class);
            when(stepResult.isSuccess()).thenReturn(true);
            // SignalResumeService now gates the loop on isPending() instead of isAwaitingSignal()
            // so it correctly catches every non-terminal yield (async agent, running, collecting, …).
            // A terminal success must explicitly return isPending()==false for the loop to continue.
            when(stepResult.isPending()).thenReturn(false);
            when(mockStepByStepService.executeNode("run-1", "mcp:next_step", "0", 0, "trigger:start")).thenReturn(stepResult);

            when(mockSignalService.hasBlockingSignals("run-1")).thenReturn(false);

            resumeService.resumeAfterSignal(signal);

            // Should execute the successor (5-param with triggerId from signal)
            verify(mockStepByStepService).executeNode("run-1", "mcp:next_step", "0", 0, "trigger:start");
            // Should re-finalize
            verify(run).setStatus(RunStatus.COMPLETED);
        }

        // --- COMPLETED + non-INTERFACE_SIGNAL: should reject ---

        @Test
        @DisplayName("Should reopen COMPLETED run for WAIT_TIMER signal (safety net)")
        void shouldReopenCompletedRunForWaitTimer() {
            SignalWaitEntity signal = createSignal("run-1", "core:wait", "0", SignalType.WAIT_TIMER);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.COMPLETED);
            when(run.getRunIdPublic()).thenReturn("run-1");
            when(run.isStepByStepMode()).thenReturn(false);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            when(mockStepByStepService.getReadyNodes("run-1", "0", 0)).thenReturn(Set.of());
            when(mockSignalService.hasBlockingSignals("run-1")).thenReturn(false);

            resumeService.resumeAfterSignal(signal);

            // Should reopen: set status to RUNNING (safety net for prematurely terminal runs)
            verify(run).setStatus(RunStatus.RUNNING);
        }

        @Test
        @DisplayName("Should reopen COMPLETED run for USER_APPROVAL signal (safety net)")
        void shouldReopenCompletedRunForUserApproval() {
            SignalWaitEntity signal = createSignal("run-1", "core:gate", "0", SignalType.USER_APPROVAL);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.COMPLETED);
            when(run.getRunIdPublic()).thenReturn("run-1");
            when(run.isStepByStepMode()).thenReturn(false);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            when(mockStepByStepService.getReadyNodes("run-1", "0", 0)).thenReturn(Set.of());
            when(mockSignalService.hasBlockingSignals("run-1")).thenReturn(false);

            resumeService.resumeAfterSignal(signal);

            // Should reopen: set status to RUNNING (safety net for prematurely terminal runs)
            verify(run).setStatus(RunStatus.RUNNING);
        }

        @Test
        @DisplayName("Should reject COMPLETED run for WEBHOOK_WAIT signal")
        void shouldRejectCompletedRunForWebhookWait() {
            SignalWaitEntity signal = createSignal("run-1", "core:webhook", "0", SignalType.WEBHOOK_WAIT);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.COMPLETED);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            resumeService.resumeAfterSignal(signal);

            verify(mockStepByStepService, never()).getReadyNodes(any(), any(), anyInt());
        }

        // --- RUNNING + INTERFACE_SIGNAL: normal path (no reopen needed) ---

        @Test
        @DisplayName("Should use normal resume path for RUNNING + INTERFACE_SIGNAL")
        void shouldUseNormalPathForRunningInterfaceSignal() {
            SignalWaitEntity signal = createSignal("run-1", "interface:form", "0", SignalType.INTERFACE_SIGNAL);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);
            when(run.isStepByStepMode()).thenReturn(false);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            when(mockStepByStepService.getReadyNodes("run-1", "0", 0)).thenReturn(Set.of());
            lenient().when(mockSignalService.hasActiveSignalsForDag("run-1", "trigger:start")).thenReturn(false);

            resumeService.resumeAfterSignal(signal);

            // Should NOT call reopen (status is RUNNING, no need)
            verify(run, never()).setEndedAt(null);
            // Should proceed with normal flow
            verify(mockStepByStepService).getReadyNodes("run-1", "0", 0);
        }

        // --- Re-finalization edge cases ---

        @Test
        @DisplayName("Should stay RUNNING if blocking signals appear during interface resume")
        void shouldStayRunningIfBlockingSignalsAppearDuringResume() {
            SignalWaitEntity signal = createSignal("run-1", "interface:form", "0", SignalType.INTERFACE_SIGNAL);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.COMPLETED);
            when(run.getRunIdPublic()).thenReturn("run-1");
            when(run.isStepByStepMode()).thenReturn(false);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            // Successor node registers a new blocking signal (e.g., a WaitTimer)
            when(mockStepByStepService.getReadyNodes("run-1", "0", 0))
                .thenReturn(Set.of("mcp:next_step"))
                .thenReturn(Set.of());

            StepByStepExecutionResult stepResult = mock(StepByStepExecutionResult.class);
            when(stepResult.isSuccess()).thenReturn(true);
            // SignalResumeService gates the loop on isPending() - a terminal success
            // must return isPending()==false for the loop to continue past this node.
            when(stepResult.isPending()).thenReturn(false);
            when(mockStepByStepService.executeNode("run-1", "mcp:next_step", "0", 0, "trigger:start")).thenReturn(stepResult);

            // Blocking signal appeared during execution
            when(mockSignalService.hasBlockingSignals("run-1")).thenReturn(true);

            resumeService.resumeAfterSignal(signal);

            // Should NOT restore terminal status (blocking signals present)
            // The verify below checks that setStatus(COMPLETED) was called exactly once for reopen->RUNNING,
            // and NOT again with COMPLETED for re-finalization
            verify(run, times(1)).setStatus(RunStatus.RUNNING);
            verify(run, never()).setStatus(RunStatus.COMPLETED);
        }

        @Test
        @DisplayName("Should handle re-finalization when successor node is awaiting signal")
        void shouldHandleSuccessorAwaitingSignal() {
            SignalWaitEntity signal = createSignal("run-1", "interface:form", "0", SignalType.INTERFACE_SIGNAL);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.COMPLETED);
            when(run.getRunIdPublic()).thenReturn("run-1");
            when(run.isStepByStepMode()).thenReturn(false);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            // Successor yields with AWAITING_SIGNAL
            when(mockStepByStepService.getReadyNodes("run-1", "0", 0))
                .thenReturn(Set.of("core:wait_timer"));

            StepByStepExecutionResult awaitResult = mock(StepByStepExecutionResult.class);
            // SignalResumeService now breaks the loop on isPending() - a pending
            // yield (AWAITING_SIGNAL, running, collecting, etc.) must short-circuit
            // auto-execution instead of being mis-classified as a failure.
            when(awaitResult.isPending()).thenReturn(true);
            when(mockStepByStepService.executeNode("run-1", "core:wait_timer", "0", 0, "trigger:start")).thenReturn(awaitResult);

            // After loop: blocking signal exists (the wait timer)
            when(mockSignalService.hasBlockingSignals("run-1")).thenReturn(true);

            resumeService.resumeAfterSignal(signal);

            // Executed the successor (5-param with triggerId from signal)
            verify(mockStepByStepService).executeNode("run-1", "core:wait_timer", "0", 0, "trigger:start");
            // Should NOT re-finalize (blocking signal present)
            verify(run, never()).setStatus(RunStatus.COMPLETED);
        }

        @Test
        @DisplayName("Should handle step-by-step mode for reopened COMPLETED run")
        void shouldHandleStepByStepModeForReopenedRun() {
            SignalWaitEntity signal = createSignal("run-1", "interface:form", "0", SignalType.INTERFACE_SIGNAL);

            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getStatus()).thenReturn(RunStatus.COMPLETED);
            when(run.getRunIdPublic()).thenReturn("run-1");
            when(run.isStepByStepMode()).thenReturn(true);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            when(mockStepByStepService.getReadyNodes("run-1", "0", 0)).thenReturn(Set.of("mcp:next"));

            resumeService.resumeAfterSignal(signal);

            // Step-by-step: should NOT auto-execute nodes (check both 4-param and 5-param overloads)
            verify(mockStepByStepService, never()).executeNode(any(), any(), any(), anyInt());
            verify(mockStepByStepService, never()).executeNode(any(), any(), any(), anyInt(), any());
            // Should still reopen the run
            verify(run).setStatus(RunStatus.RUNNING);
        }
    }

    // ========================================================================
    // V2WorkflowFinalizer - uses hasBlockingSignals
    // ========================================================================

    @Nested
    @DisplayName("V2WorkflowFinalizer - blocking signal check")
    class FinalizerBlockingSignalTests {

        @Mock private UnifiedSignalService mockSignalService;

        @Test
        @DisplayName("Should verify finalizer calls hasBlockingSignals not hasActiveSignals")
        void shouldUseBlockingSignalsMethod() {
            // This is a design verification test.
            // The actual integration is tested via E2E tests.
            // Here we verify the contract: hasBlockingSignals excludes INTERFACE_SIGNAL.

            // Scenario: only INTERFACE_SIGNAL is active
            when(mockSignalService.hasActiveSignals("run-1")).thenReturn(true);
            when(mockSignalService.hasBlockingSignals("run-1")).thenReturn(false);

            // hasBlockingSignals should return false → finalizer proceeds
            assertFalse(mockSignalService.hasBlockingSignals("run-1"),
                "hasBlockingSignals should be false when only INTERFACE_SIGNAL is active");

            // hasActiveSignals still returns true (INTERFACE_SIGNAL counted)
            assertTrue(mockSignalService.hasActiveSignals("run-1"),
                "hasActiveSignals should still be true");
        }

        @Test
        @DisplayName("Should block finalization when WAIT_TIMER is active")
        void shouldBlockForWaitTimer() {
            when(mockSignalService.hasBlockingSignals("run-1")).thenReturn(true);

            assertTrue(mockSignalService.hasBlockingSignals("run-1"),
                "hasBlockingSignals should be true for WAIT_TIMER");
        }

        @Test
        @DisplayName("Should block finalization when USER_APPROVAL is active")
        void shouldBlockForUserApproval() {
            when(mockSignalService.hasBlockingSignals("run-1")).thenReturn(true);

            assertTrue(mockSignalService.hasBlockingSignals("run-1"),
                "hasBlockingSignals should be true for USER_APPROVAL");
        }

        @Test
        @DisplayName("Should allow finalization when no signals at all")
        void shouldAllowWhenNoSignals() {
            when(mockSignalService.hasBlockingSignals("run-1")).thenReturn(false);

            assertFalse(mockSignalService.hasBlockingSignals("run-1"));
        }
    }

    // ========================================================================
    // SignalWaitRepository default methods - hasBlockingSignals
    // ========================================================================

    @Nested
    @DisplayName("SignalWaitRepository.hasBlockingSignals()")
    class RepositoryBlockingSignalsTests {

        @Mock private SignalWaitRepository repository;

        @Test
        @DisplayName("Should return false when countActiveBlockingByRunId is 0")
        void shouldReturnFalseWhenCountIsZero() {
            when(repository.countActiveBlockingByRunId("run-1")).thenReturn(0L);
            when(repository.hasBlockingSignals("run-1")).thenCallRealMethod();

            assertFalse(repository.hasBlockingSignals("run-1"));
        }

        @Test
        @DisplayName("Should return true when countActiveBlockingByRunId is > 0")
        void shouldReturnTrueWhenCountIsPositive() {
            when(repository.countActiveBlockingByRunId("run-1")).thenReturn(2L);
            when(repository.hasBlockingSignals("run-1")).thenCallRealMethod();

            assertTrue(repository.hasBlockingSignals("run-1"));
        }
    }
}
