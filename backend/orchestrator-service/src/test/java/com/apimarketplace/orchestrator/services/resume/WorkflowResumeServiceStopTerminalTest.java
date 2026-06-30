package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.async.PendingAgentRegistry;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowPersistenceService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.cache.RunCacheRegistry;
import com.apimarketplace.orchestrator.services.resume.cache.WorkflowCacheManager;
import com.apimarketplace.orchestrator.services.resume.state.StateReconstructor;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import com.apimarketplace.orchestrator.services.streaming.state.RunStateStore;
import com.apimarketplace.orchestrator.trigger.EpochConcurrencyLimiter;
import com.apimarketplace.orchestrator.trigger.TriggerEpochManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression tests for WorkflowResumeService.stopWorkflow() idempotency on terminal runs.
 *
 * <p>Bug context: when the OrchestrationRecoveryService watchdog force-FAILs a run without
 * broadcasting an SSE update, the user clicking "Stop" hits a silent IllegalStateException
 * that bubbles up as an opaque 500. The fix makes stopWorkflow idempotent for terminal runs:
 * best-effort cleanup, no status change, no SSE lie.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowResumeService - stopWorkflow on terminal runs (regression)")
class WorkflowResumeServiceStopTerminalTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowExecutionService executionService;
    @Mock private WorkflowPersistenceService persistenceService;
    @Mock private WorkflowStreamingService streamingService;
    @Mock private RunStateStore runStateStore;
    @Mock private WorkflowCacheManager cacheManager;
    @Mock private StateReconstructor stateReconstructor;
    @Mock private RunCacheRegistry cacheRegistry;
    @Mock private ExecutionContextManager contextManager;
    @Mock private StepByStepExecutor stepByStepExecutor;
    @Mock private TriggerEpochManager epochManager;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private UnifiedSignalService unifiedSignalService;
    @Mock private WorkflowRedisPublisher workflowRedisPublisher;
    @Mock private PendingAgentRegistry pendingAgentRegistry;
    @Mock private EpochConcurrencyLimiter epochConcurrencyLimiter;

    private WorkflowResumeService service;

    private static final String RUN_ID = "run_<id>";

    @BeforeEach
    void setUp() throws Exception {
        service = new WorkflowResumeService(
                runRepository, executionService, persistenceService,
                streamingService, runStateStore,
                cacheManager, stateReconstructor, cacheRegistry,
                contextManager, stepByStepExecutor,
                epochManager, stateSnapshotService
        );

        injectField("unifiedSignalService", unifiedSignalService);
        injectField("workflowRedisPublisher", workflowRedisPublisher);
        injectField("pendingAgentRegistry", pendingAgentRegistry);
        injectField("epochConcurrencyLimiter", epochConcurrencyLimiter);
    }

    private void injectField(String name, Object value) throws Exception {
        Field f = WorkflowResumeService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private WorkflowRunEntity createRunEntity(RunStatus status) {
        WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
        lenient().when(entity.getStatus()).thenReturn(status);
        lenient().when(entity.getMetadata()).thenReturn(new HashMap<>());
        return entity;
    }

    // ============================================================
    // Regression: stop on already-terminal runs is idempotent
    // ============================================================

    @Nested
    @DisplayName("Idempotent on terminal runs (regression)")
    class IdempotentTerminal {

        @Test
        @DisplayName("Stop on already-FAILED run cleans up pending agents without throwing")
        void stopOnFailedRunPerformsBestEffortCleanupAndDoesNotThrow() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.FAILED);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));

            assertDoesNotThrow(() -> service.stopWorkflow(RUN_ID));

            // Cleanup hooks must run even on terminal runs
            verify(unifiedSignalService).cancelByRun(RUN_ID);
            verify(workflowRedisPublisher).setAgentCancelSignal(RUN_ID);
            verify(pendingAgentRegistry).removeByRunId(RUN_ID);
            verify(epochConcurrencyLimiter).cleanup(RUN_ID);

            // Status MUST NOT change - terminal stays terminal
            verify(runEntity, never()).setStatus(any());
            verify(runRepository, never()).save(any());

            // No SSE lie - we must NOT broadcast a fake transition
            verifyNoInteractions(streamingService);
        }

        @Test
        @DisplayName("Stop on COMPLETED run does best-effort cleanup, never changes status")
        void stopOnCompletedRunIsNoOpForStatusButRunsCleanup() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));

            assertDoesNotThrow(() -> service.stopWorkflow(RUN_ID));

            verify(unifiedSignalService).cancelByRun(RUN_ID);
            verify(runEntity, never()).setStatus(any());
            verifyNoInteractions(streamingService);
        }

        @Test
        @DisplayName("Stop on CANCELLED run is idempotent - no exception, no status change")
        void stopOnCancelledRunIsIdempotent() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.CANCELLED);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));

            assertDoesNotThrow(() -> service.stopWorkflow(RUN_ID));

            verify(epochConcurrencyLimiter).cleanup(RUN_ID);
            verify(runEntity, never()).setStatus(any());
        }

        @Test
        @DisplayName("Stop on PARTIAL_SUCCESS run is idempotent - no exception, no status change")
        void stopOnPartialSuccessRunIsIdempotent() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.PARTIAL_SUCCESS);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));

            assertDoesNotThrow(() -> service.stopWorkflow(RUN_ID));

            verify(runEntity, never()).setStatus(any());
        }

        @Test
        @DisplayName("Stop on TIMEOUT run is idempotent - no exception, no status change")
        void stopOnTimeoutRunIsIdempotent() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.TIMEOUT);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));

            assertDoesNotThrow(() -> service.stopWorkflow(RUN_ID));

            verify(runEntity, never()).setStatus(any());
        }

        @Test
        @DisplayName("Cleanup step failure (Redis down) is isolated - siblings still run")
        void redisDownDuringStopDoesNotBlockOtherCleanupSteps() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.FAILED);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));
            // Simulate Redis-down for one cleanup step.
            doThrow(new RuntimeException("Redis unreachable"))
                    .when(workflowRedisPublisher).setAgentCancelSignal(RUN_ID);

            assertDoesNotThrow(() -> service.stopWorkflow(RUN_ID));

            // Sibling cleanup steps must still run despite the Redis failure.
            verify(unifiedSignalService).cancelByRun(RUN_ID);
            verify(workflowRedisPublisher).setAgentCancelSignal(RUN_ID);
            verify(pendingAgentRegistry).removeByRunId(RUN_ID);
            verify(epochConcurrencyLimiter).cleanup(RUN_ID);
        }

        @Test
        @DisplayName("Concurrent stop on terminal run still runs cleanup safely (no exception)")
        void concurrentStopOnTerminalRunIsSafe() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.FAILED);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));

            // Two stop calls in sequence - both must succeed and run cleanup
            assertDoesNotThrow(() -> service.stopWorkflow(RUN_ID));
            assertDoesNotThrow(() -> service.stopWorkflow(RUN_ID));

            verify(unifiedSignalService, times(2)).cancelByRun(RUN_ID);
            verify(epochConcurrencyLimiter, times(2)).cleanup(RUN_ID);
            verify(runEntity, never()).setStatus(any());
        }
    }

    // ============================================================
    // Happy path unchanged
    // ============================================================

    @Nested
    @DisplayName("Happy path unchanged (regression guard)")
    class HappyPathUnchanged {

        @Test
        @DisplayName("Stop on RUNNING run still transitions to WAITING_TRIGGER")
        void stopOnRunningRunStillTransitionsToWaitingTrigger() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));

            service.stopWorkflow(RUN_ID);

            verify(runEntity).setStatus(RunStatus.WAITING_TRIGGER);
            verify(runRepository).save(runEntity);
            verify(unifiedSignalService).cancelByRun(RUN_ID);
        }

        @Test
        @DisplayName("Stop on PAUSED run still transitions to WAITING_TRIGGER")
        void stopOnPausedRunStillTransitionsToWaitingTrigger() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.PAUSED);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));

            service.stopWorkflow(RUN_ID);

            verify(runEntity).setStatus(RunStatus.WAITING_TRIGGER);
            verify(runRepository).save(runEntity);
        }

        @Test
        @DisplayName("Stop on a run with active epochs (blocking signal pending) does NOT cancel signals per-epoch in the outer tx - avoids the REQUIRES_NEW self-deadlock that hung Stop for 5 min (regression: prod run run_<id>, wait node, 2026-06-05)")
        void stopWithActiveEpochsDoesNotCancelBlockingSignalsInOuterTransaction() {
            // Run blocked at a WAIT_TIMER (or INTERFACE_SIGNAL / USER_APPROVAL /
            // WEBHOOK_WAIT) signal: status is RUNNING and the snapshot still has an
            // active epoch to close.
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));

            StateSnapshot snapshot = mock(StateSnapshot.class);
            String triggerId = "trigger:post_request";
            when(snapshot.getDags()).thenReturn(Map.of(triggerId, mock(DagState.class)));
            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);
            when(stateSnapshotService.closeAllActiveEpochs(RUN_ID, triggerId))
                    .thenReturn(Set.of(1));

            service.stopWorkflow(RUN_ID);

            // THE REGRESSION GUARD: cancelBlockingByDagAndEpoch() is @Transactional
            // (REQUIRED) so it runs inside stopWorkflow's outer @Transactional and
            // row-locks the blocking-signal rows uncommitted. performStopCleanup()'s
            // cancelByRun() is @Transactional(REQUIRES_NEW) - an independent tx that
            // then tries to re-cancel the same rows and blocks on the outer's locks
            // until idle_in_transaction_session_timeout (prod: 5 min). It MUST NOT be
            // called here. cancelByRun() (run-wide) is a strict superset.
            verify(unifiedSignalService, never())
                    .cancelBlockingByDagAndEpoch(any(), any(), org.mockito.ArgumentMatchers.anyInt());

            // Epochs are still closed (snapshot-only) and ALL signals are cancelled
            // run-wide via the REQUIRES_NEW path, then the run goes to WAITING_TRIGGER.
            verify(stateSnapshotService).closeAllActiveEpochs(RUN_ID, triggerId);
            verify(unifiedSignalService).cancelByRun(RUN_ID);
            verify(runEntity).setStatus(RunStatus.WAITING_TRIGGER);
            verify(runRepository).save(runEntity);
        }

        @Test
        @DisplayName("performStopCleanup runs setAgentCancelSignal BEFORE cancelByRun (regression: race window let late async results bypass RunCancellationGuard, audit P0 #1, 2026-05-06)")
        void cancelSignalSetBeforeCancelByRun() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));

            service.stopWorkflow(RUN_ID);

            // Race that this guards against: a late async result arriving between
            // cancelByRun and setAgentCancelSignal would slip past
            // RunCancellationGuard.isAgentCancelSignalSet (false until set) and
            // drive successors on a stopped run. Setting the signal FIRST turns
            // the guard into an immediate reject for any in-flight or late result.
            org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(
                workflowRedisPublisher, unifiedSignalService, pendingAgentRegistry);
            inOrder.verify(workflowRedisPublisher).setAgentCancelSignal(RUN_ID);
            inOrder.verify(unifiedSignalService).cancelByRun(RUN_ID);
            inOrder.verify(pendingAgentRegistry).removeByRunId(RUN_ID);
        }
    }

    // ============================================================
    // Non-terminal non-stoppable states still rejected
    // ============================================================

    @Nested
    @DisplayName("Non-terminal non-stoppable states still rejected")
    class NonStoppableStillRejected {

        @Test
        @DisplayName("Stop on PENDING run still throws IllegalStateException")
        void stopOnPendingRunStillThrows() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.PENDING);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.stopWorkflow(RUN_ID));
            assertTrue(ex.getMessage().contains("Cannot stop workflow"));
            assertTrue(ex.getMessage().contains("PENDING"));

            // No cleanup, no status change, no SSE
            verify(unifiedSignalService, never()).cancelByRun(any());
            verify(runEntity, never()).setStatus(any());
        }

        @Test
        @DisplayName("Stop on WAITING_TRIGGER run still throws IllegalStateException")
        void stopOnWaitingTriggerRunStillThrows() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.WAITING_TRIGGER);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));

            assertThrows(IllegalStateException.class, () -> service.stopWorkflow(RUN_ID));
            verify(runEntity, never()).setStatus(any());
        }

        @Test
        @DisplayName("Stop on AWAITING_SIGNAL run still throws IllegalStateException")
        void stopOnAwaitingSignalRunStillThrows() {
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.AWAITING_SIGNAL);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                    .thenReturn(Optional.of(runEntity));

            assertThrows(IllegalStateException.class, () -> service.stopWorkflow(RUN_ID));
            verify(runEntity, never()).setStatus(any());
        }
    }

    // ============================================================
    // Run-not-found
    // ============================================================

    @Test
    @DisplayName("Stop on unknown run throws IllegalArgumentException (404 path)")
    void stopOnUnknownRunThrowsIllegalArgumentException() {
        when(runRepository.findByRunIdPublicForUpdate(RUN_ID))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.stopWorkflow(RUN_ID));
    }
}
