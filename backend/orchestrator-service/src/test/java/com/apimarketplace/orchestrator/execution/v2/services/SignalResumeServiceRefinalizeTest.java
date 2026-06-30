package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.async.PendingAgentRegistry;
import com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the missing async-agent gate in
 * {@link SignalResumeService#refinalizeAfterInterfaceResume(String, RunStatus)}.
 *
 * <p>Pre-fix: when an interface signal resumed a previously-terminal run, the
 * re-finalization path checked only {@code signalService.hasBlockingSignals(runId)}.
 * Async agents are tracked in {@link PendingAgentRegistry}, not in
 * {@code workflow_signal_waits} - so a worker still computing during the resume would
 * see its result dropped on a re-COMPLETED/FAILED run when it eventually arrived. This
 * is the same source-of-truth gap that
 * {@link com.apimarketplace.orchestrator.config.OrchestrationRecoveryService} suffered
 * from until the watchdog was patched to consult {@link PendingAgentRegistry#hasAnyPendingForRun}.
 *
 * <p>Post-fix: the refinalize path mirrors the watchdog's two-source check; if the
 * registry reports a pending agent, the run stays RUNNING and the next async-completion
 * callback drives finalization.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SignalResumeService.refinalizeAfterInterfaceResume - async agent gate (regression)")
class SignalResumeServiceRefinalizeTest {

    @Mock private StringRedisTemplate redis;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private SplitContextManager splitContextManager;
    @Mock private StorageService storageService;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private WorkflowStepDataRepository stepDataRepository;
    @Mock private ExecutionCacheManager executionCacheManager;
    @Mock private NodeSearchService nodeSearchService;
    @Mock private RunningNodeTracker runningNodeTracker;
    @Mock private WorkflowEpochService workflowEpochService;
    @Mock private UnifiedSignalService signalService;
    @Mock private PendingAgentRegistry pendingAgentRegistry;

    private SignalResumeService service;
    private static final String RUN_ID = "run-refinalize-x";

    @BeforeEach
    void setUp() throws Exception {
        service = new SignalResumeService(
                redis, runRepository, splitContextManager, storageService,
                stateSnapshotService, stepDataRepository, executionCacheManager,
                nodeSearchService, runningNodeTracker, workflowEpochService);

        setField("signalService", signalService);
        setField("pendingAgentRegistry", pendingAgentRegistry);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = SignalResumeService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private void invokeRefinalize(RunStatus previousStatus) throws Exception {
        Method m = SignalResumeService.class.getDeclaredMethod(
                "refinalizeAfterInterfaceResume", String.class, RunStatus.class);
        m.setAccessible(true);
        m.invoke(service, RUN_ID, previousStatus);
    }

    @Test
    @DisplayName("Skips re-finalization when an async agent is in flight (PendingAgentRegistry)")
    void asyncAgentInFlightKeepsRunRunning() throws Exception {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setStatus(RunStatus.RUNNING);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        when(signalService.hasBlockingSignals(RUN_ID)).thenReturn(false);
        when(pendingAgentRegistry.hasAnyPendingForRun(RUN_ID)).thenReturn(true);

        invokeRefinalize(RunStatus.COMPLETED);

        // Status MUST stay RUNNING; the registry-driven completion will refinalize.
        verify(runRepository, never()).save(any());
    }

    @Test
    @DisplayName("Skips re-finalization when registry throws (fail-safe - never re-finalize on uncertain state)")
    void registryErrorKeepsRunRunning() throws Exception {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setStatus(RunStatus.RUNNING);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        when(signalService.hasBlockingSignals(RUN_ID)).thenReturn(false);
        when(pendingAgentRegistry.hasAnyPendingForRun(RUN_ID))
                .thenThrow(new RuntimeException("registry transient failure"));

        invokeRefinalize(RunStatus.FAILED);

        verify(runRepository, never()).save(any());
    }

    @Test
    @DisplayName("Re-finalizes normally when no signals AND no async agents - preserves the happy path")
    void noBlockersRestoresTerminalStatus() throws Exception {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setStatus(RunStatus.RUNNING);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        when(signalService.hasBlockingSignals(RUN_ID)).thenReturn(false);
        when(pendingAgentRegistry.hasAnyPendingForRun(RUN_ID)).thenReturn(false);

        invokeRefinalize(RunStatus.COMPLETED);

        verify(runRepository).save(run);
    }

    @Test
    @DisplayName("Blocking-signal check still short-circuits before the registry check (preserves existing behavior)")
    void blockingSignalShortCircuitsBeforeRegistry() throws Exception {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setStatus(RunStatus.RUNNING);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        when(signalService.hasBlockingSignals(RUN_ID)).thenReturn(true);

        invokeRefinalize(RunStatus.COMPLETED);

        verify(runRepository, never()).save(any());
        verify(pendingAgentRegistry, never()).hasAnyPendingForRun(any());
    }

    @Test
    @DisplayName("Re-finalizes when PendingAgentRegistry bean is not wired (queue-disabled deployments)")
    void noRegistryWiredFallsBackToSignalsOnlyAndRefinalizes() throws Exception {
        // Simulate scaling.agent.queue.enabled=false: registry field is null.
        setField("pendingAgentRegistry", null);

        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setStatus(RunStatus.RUNNING);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        when(signalService.hasBlockingSignals(RUN_ID)).thenReturn(false);

        invokeRefinalize(RunStatus.COMPLETED);

        verify(runRepository).save(run);
    }
}
