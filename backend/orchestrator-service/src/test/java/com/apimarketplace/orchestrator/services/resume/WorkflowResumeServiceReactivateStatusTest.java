package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowPersistenceService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.cache.RunCacheRegistry;
import com.apimarketplace.orchestrator.services.resume.cache.WorkflowCacheManager;
import com.apimarketplace.orchestrator.services.resume.state.StateReconstructor;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.state.RunStateStore;
import com.apimarketplace.orchestrator.trigger.TriggerEpochManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Status-guard tests for {@link WorkflowResumeService#reactivateWorkflow(String)}.
 *
 * <p>The original guard accepted only {@link RunStatus#CANCELLED}, which became a
 * usability gap once commit a04f13449 made every dispatcher reject terminal runs:
 * a FAILED run left behind by a JVM crash mid-cycle (prod 2026-05-07 12:40 UTC,
 * run_<id>) was rejected by triggers AND non-reactivatable from
 * the UI, leaving it zombie irrecoverable. The guard now accepts every terminal
 * status (COMPLETED / FAILED / PARTIAL_SUCCESS / CANCELLED / TIMEOUT / SKIPPED)
 * and rejects everything else.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowResumeService.reactivateWorkflow - terminal-status guard")
class WorkflowResumeServiceReactivateStatusTest {

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

    private WorkflowResumeService service;

    private static final String RUN_ID = "test-run-123";

    @BeforeEach
    void setUp() throws Exception {
        service = new WorkflowResumeService(
            runRepository, executionService, persistenceService,
            streamingService, runStateStore,
            cacheManager, stateReconstructor, cacheRegistry,
            contextManager, stepByStepExecutor,
            epochManager, stateSnapshotService
        );
        Field signalField = WorkflowResumeService.class.getDeclaredField("unifiedSignalService");
        signalField.setAccessible(true);
        signalField.set(service, unifiedSignalService);
    }

    private WorkflowRunEntity runWith(RunStatus status) {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class, withSettings().lenient());
        when(run.getStatus()).thenReturn(status);
        when(run.getMetadata()).thenReturn(new HashMap<>());
        when(run.getRunIdPublic()).thenReturn(RUN_ID);
        return run;
    }

    @Test
    @DisplayName("FAILED is reactivatable - fixes the prod-OOM zombie scenario introduced by a04f13449")
    void failedIsReactivatable() {
        // Direct regression for run_<id>: post-OOM the run was FAILED,
        // every dispatcher refused to fire it (good), but the UI's only path back was
        // closed because reactivateWorkflow accepted only CANCELLED. Without this guard
        // extension the user is stuck - the fix would have shifted the bug rather than
        // resolved it.
        WorkflowRunEntity run = runWith(RunStatus.FAILED);
        when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(run));
        when(stateSnapshotService.getSnapshot(anyString())).thenReturn(null);

        service.reactivateWorkflow(RUN_ID);

        verify(run).setStatus(RunStatus.WAITING_TRIGGER);
        verify(run).setEndedAt(null);
    }

    @Test
    @DisplayName("Every terminal status is reactivatable - COMPLETED, FAILED, PARTIAL_SUCCESS, CANCELLED, TIMEOUT, SKIPPED")
    void everyTerminalStatusIsReactivatable() {
        for (RunStatus terminal : new RunStatus[]{
                RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.PARTIAL_SUCCESS,
                RunStatus.CANCELLED, RunStatus.TIMEOUT, RunStatus.SKIPPED}) {
            WorkflowRunEntity run = runWith(terminal);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(run));
            when(stateSnapshotService.getSnapshot(anyString())).thenReturn(null);

            service.reactivateWorkflow(RUN_ID);

            verify(run).setStatus(RunStatus.WAITING_TRIGGER);
            reset(runRepository, stateSnapshotService);
        }
    }

    @Test
    @DisplayName("RUNNING is rejected - only terminal statuses can be reactivated")
    void runningIsRejected() {
        WorkflowRunEntity run = runWith(RunStatus.RUNNING);
        when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.reactivateWorkflow(RUN_ID))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("RUNNING")
            .hasMessageContaining("Must be terminal");
        verify(run, never()).setStatus(RunStatus.WAITING_TRIGGER);
    }

    @Test
    @DisplayName("WAITING_TRIGGER is rejected - already in the correct state, nothing to reactivate")
    void waitingTriggerIsRejected() {
        WorkflowRunEntity run = runWith(RunStatus.WAITING_TRIGGER);
        when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.reactivateWorkflow(RUN_ID))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("WAITING_TRIGGER");
        verify(run, never()).setStatus(RunStatus.WAITING_TRIGGER);
    }

    @Test
    @DisplayName("PAUSED, PENDING, AWAITING_SIGNAL all rejected - non-terminal in-flight states")
    void inFlightStatesRejected() {
        for (RunStatus inFlight : new RunStatus[]{
                RunStatus.PAUSED, RunStatus.PENDING, RunStatus.AWAITING_SIGNAL}) {
            WorkflowRunEntity run = runWith(inFlight);
            when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(run));

            assertThatThrownBy(() -> service.reactivateWorkflow(RUN_ID))
                .as("status %s must be rejected", inFlight)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Must be terminal");
            reset(runRepository);
        }
    }

    @Test
    @DisplayName("Null status is rejected with a clear message - defensive against legacy data")
    void nullStatusIsRejected() {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class, withSettings().lenient());
        when(run.getStatus()).thenReturn(null);
        when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.reactivateWorkflow(RUN_ID))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("null")
            .hasMessageContaining("Must be terminal");
    }

    @Test
    @DisplayName("Run not found surfaces IllegalArgumentException - pre-existing contract preserved")
    void runNotFound() {
        when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reactivateWorkflow(RUN_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Run not found");
    }
}
