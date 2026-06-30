package com.apimarketplace.orchestrator.config;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.async.PendingAgentRegistry;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import com.apimarketplace.orchestrator.trigger.EpochConcurrencyLimiter;
import com.apimarketplace.orchestrator.trigger.ErrorTriggerDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the post-fail cleanup that {@link OrchestrationRecoveryService}
 * performs after force-FAILing a zombie run.
 *
 * <p>Bug: a zombie run was force-FAILed in DB, but the frontend never saw the status
 * change (no SSE), and in-memory + Redis state was leaked (pending async agents,
 * blocking signals, epoch concurrency permits, agent cancel signal). When the user
 * clicked Stop on the still-RUNNING UI, {@code stopWorkflow} threw silently
 * because the run was already FAILED.
 *
 * <p>Fix: mirror the cleanup pattern from {@code WorkflowResumeService.stopWorkflow}
 * for every run we transition to FAILED.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrchestrationRecoveryService - post-fail cleanup")
class OrchestrationRecoveryServiceCleanupTest {

    private static final Instant NOW = Instant.parse("2026-05-03T18:10:05Z");

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private SignalWaitRepository signalWaitRepository;

    @Mock
    private UnifiedSignalService unifiedSignalService;

    @Mock
    private WorkflowRedisPublisher workflowRedisPublisher;

    @Mock
    private PendingAgentRegistry pendingAgentRegistry;

    @Mock
    private EpochConcurrencyLimiter epochConcurrencyLimiter;

    @Mock
    private WorkflowStreamingService streamingService;

    @Mock
    private ErrorTriggerDispatchService errorTriggerDispatchService;

    private OrchestrationRecoveryService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(NOW, ZoneId.of("UTC"));
        service = new OrchestrationRecoveryService(runRepository, signalWaitRepository, fixedClock);
        service.setUnifiedSignalService(unifiedSignalService);
        service.setWorkflowRedisPublisher(workflowRedisPublisher);
        service.setPendingAgentRegistry(pendingAgentRegistry);
        service.setEpochConcurrencyLimiter(epochConcurrencyLimiter);
        service.setStreamingService(streamingService);
        service.setErrorTriggerDispatchService(errorTriggerDispatchService);
    }

    @Test
    @DisplayName("Broadcasts a FAILED workflowStatus SSE event with a clear reason for every recovered run")
    void failsZombieRunAndBroadcastsSseEvent() {
        // Arrange
        WorkflowRunEntity zombie = createZombieRun("run-zombie-1", NOW.minus(Duration.ofMinutes(10)));
        when(runRepository.findByStatusAndUpdatedAtBefore(any(), any())).thenReturn(List.of(zombie));

        // Act
        service.recoverZombieRuns();

        // Assert - SSE event went out with FAILED status and a reason mentioning the threshold
        ArgumentCaptor<WorkflowExecution> executionCaptor = ArgumentCaptor.forClass(WorkflowExecution.class);
        verify(streamingService).sendWorkflowStatusEvent(
                executionCaptor.capture(), eq(RunStatus.FAILED), contains("recovery service"));
        assertThat(executionCaptor.getValue().getRunId()).isEqualTo("run-zombie-1");
    }

    @Test
    @DisplayName("Removes pending async agent entries so late-arriving results don't drive successors")
    void failsZombieRunAndCleansUpPendingAgents() {
        // Arrange
        WorkflowRunEntity zombie = createZombieRun("run-zombie-2", NOW.minus(Duration.ofMinutes(10)));
        when(runRepository.findByStatusAndUpdatedAtBefore(any(), any())).thenReturn(List.of(zombie));

        // Act
        service.recoverZombieRuns();

        // Assert
        verify(pendingAgentRegistry).removeByRunId("run-zombie-2");
    }

    @Test
    @DisplayName("Cancels active signals (timers, approvals, awaiting-signal nodes)")
    void failsZombieRunAndCancelsSignals() {
        // Arrange
        WorkflowRunEntity zombie = createZombieRun("run-zombie-3", NOW.minus(Duration.ofMinutes(10)));
        when(runRepository.findByStatusAndUpdatedAtBefore(any(), any())).thenReturn(List.of(zombie));

        // Act
        service.recoverZombieRuns();

        // Assert
        verify(unifiedSignalService).cancelByRun("run-zombie-3");
    }

    @Test
    @DisplayName("Sets the Redis agent-cancel flag so mid-stream LLM calls and sub-agents stop")
    void failsZombieRunAndSetsRedisCancelSignal() {
        // Arrange
        WorkflowRunEntity zombie = createZombieRun("run-zombie-4", NOW.minus(Duration.ofMinutes(10)));
        when(runRepository.findByStatusAndUpdatedAtBefore(any(), any())).thenReturn(List.of(zombie));

        // Act
        service.recoverZombieRuns();

        // Assert
        verify(workflowRedisPublisher).setAgentCancelSignal("run-zombie-4");
    }

    @Test
    @DisplayName("setAgentCancelSignal runs BEFORE cancelByRun and removeByRunId (regression: race let late async results bypass RunCancellationGuard, audit P0 #1, 2026-05-06)")
    void cancelSignalSetBeforeOtherCleanupSteps() {
        // Race that this guards against: a late async result arriving between
        // cancelByRun and setAgentCancelSignal would slip past
        // RunCancellationGuard.isAgentCancelSignalSet (false until set) and drive
        // successors on a force-FAILed run. Setting the signal FIRST closes the window.
        WorkflowRunEntity zombie = createZombieRun("run-order-1", NOW.minus(Duration.ofMinutes(10)));
        when(runRepository.findByStatusAndUpdatedAtBefore(any(), any())).thenReturn(List.of(zombie));

        service.recoverZombieRuns();

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(
            workflowRedisPublisher, unifiedSignalService, pendingAgentRegistry);
        inOrder.verify(workflowRedisPublisher).setAgentCancelSignal("run-order-1");
        inOrder.verify(unifiedSignalService).cancelByRun("run-order-1");
        inOrder.verify(pendingAgentRegistry).removeByRunId("run-order-1");
    }

    @Test
    @DisplayName("Releases epoch concurrency permits for every recovered run")
    void failsZombieRunAndReleasesEpochPermits() {
        // Arrange
        WorkflowRunEntity zombie = createZombieRun("run-zombie-5", NOW.minus(Duration.ofMinutes(10)));
        when(runRepository.findByStatusAndUpdatedAtBefore(any(), any())).thenReturn(List.of(zombie));

        // Act
        service.recoverZombieRuns();

        // Assert
        verify(epochConcurrencyLimiter).cleanup("run-zombie-5");
    }

    @Test
    @DisplayName("If SSE broadcast throws on run #1, run #2 still gets full cleanup + broadcast")
    void cleanupFailureDoesNotBlockSubsequentRuns() {
        // Arrange - two zombies; SSE for the first throws Redis-down.
        WorkflowRunEntity zombieA = createZombieRun("run-A", NOW.minus(Duration.ofMinutes(10)));
        WorkflowRunEntity zombieB = createZombieRun("run-B", NOW.minus(Duration.ofMinutes(10)));
        when(runRepository.findByStatusAndUpdatedAtBefore(any(), any()))
                .thenReturn(List.of(zombieA, zombieB));
        doThrow(new RuntimeException("Redis pub/sub down"))
                .when(streamingService).sendWorkflowStatusEvent(
                        any(WorkflowExecution.class), eq(RunStatus.FAILED), any());

        // Act
        service.recoverZombieRuns();

        // Assert - both runs got every cleanup call, despite the SSE failure on run-A.
        verify(unifiedSignalService).cancelByRun("run-A");
        verify(unifiedSignalService).cancelByRun("run-B");
        verify(pendingAgentRegistry).removeByRunId("run-A");
        verify(pendingAgentRegistry).removeByRunId("run-B");
        verify(epochConcurrencyLimiter).cleanup("run-A");
        verify(epochConcurrencyLimiter).cleanup("run-B");
        verify(workflowRedisPublisher).setAgentCancelSignal("run-A");
        verify(workflowRedisPublisher).setAgentCancelSignal("run-B");
        // SSE attempted twice (once per run) - neither call short-circuited the loop.
        verify(streamingService, times(2)).sendWorkflowStatusEvent(
                any(WorkflowExecution.class), eq(RunStatus.FAILED), any());
    }

    @Test
    @DisplayName("A single Redis-down on setAgentCancelSignal does not block signal cancel, registry cleanup, permit release, or SSE")
    void redisFailureIsIsolatedFromOtherCleanupSteps() {
        // Arrange
        WorkflowRunEntity zombie = createZombieRun("run-redis-down", NOW.minus(Duration.ofMinutes(10)));
        when(runRepository.findByStatusAndUpdatedAtBefore(any(), any())).thenReturn(List.of(zombie));
        doThrow(new RuntimeException("Redis connection refused"))
                .when(workflowRedisPublisher).setAgentCancelSignal("run-redis-down");

        // Act
        service.recoverZombieRuns();

        // Assert - Redis publisher threw, but every other cleanup step still ran.
        verify(unifiedSignalService).cancelByRun("run-redis-down");
        verify(pendingAgentRegistry).removeByRunId("run-redis-down");
        verify(epochConcurrencyLimiter).cleanup("run-redis-down");
        verify(streamingService).sendWorkflowStatusEvent(
                any(WorkflowExecution.class), eq(RunStatus.FAILED), any());
    }

    @Test
    @DisplayName("Skips runs that have active blocking signals - no DB save, no SSE, no cleanup")
    void doesNotCleanUpRunsThatWereSkipped() {
        // Arrange - the only zombie has blocking signals, so it must NOT be touched.
        WorkflowRunEntity zombie = createZombieRun("run-blocked", NOW.minus(Duration.ofMinutes(10)));
        when(runRepository.findByStatusAndUpdatedAtBefore(any(), any())).thenReturn(List.of(zombie));
        when(signalWaitRepository.hasBlockingSignals("run-blocked")).thenReturn(true);

        // Act
        service.recoverZombieRuns();

        // Assert - no save, no SSE, no cleanup of any kind for the blocked run.
        verify(runRepository, never()).saveAll(any());
        verify(streamingService, never()).sendWorkflowStatusEvent(any(), any(RunStatus.class), any());
        verify(unifiedSignalService, never()).cancelByRun(any());
        verify(workflowRedisPublisher, never()).setAgentCancelSignal(any());
        verify(pendingAgentRegistry, never()).removeByRunId(any());
        verify(epochConcurrencyLimiter, never()).cleanup(any());
        // And the zombie is still RUNNING in memory (would have been mutated otherwise).
        assertThat(zombie.getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    @DisplayName("Dispatches user-configured error-trigger workflows with execution.status=FAILED")
    void failsZombieRunAndDispatchesErrorTriggerWorkflows() {
        // Arrange - a zombie kill that should fan out to any workflow wired to the
        // failed workflow's "error" trigger. Pre-fix: the watchdog flipped status via
        // saveAll() and never invoked ErrorTriggerDispatchService - Slack notifications
        // and retry-on-failure pipelines were silently dead for zombie kills.
        WorkflowRunEntity zombie = createZombieRun("run-zombie-err", NOW.minus(Duration.ofMinutes(10)));
        when(runRepository.findByStatusAndUpdatedAtBefore(any(), any())).thenReturn(List.of(zombie));

        // Act
        service.recoverZombieRuns();

        // Assert - error-trigger dispatcher was called with an execution carrying the
        // FAILED status (the dispatcher gates on FAILED/PARTIAL_SUCCESS, so a missing
        // status set would silently no-op).
        ArgumentCaptor<WorkflowExecution> captor = ArgumentCaptor.forClass(WorkflowExecution.class);
        verify(errorTriggerDispatchService).dispatchWorkflowFailure(captor.capture());
        WorkflowExecution dispatched = captor.getValue();
        assertThat(dispatched.getRunId()).isEqualTo("run-zombie-err");
        assertThat(dispatched.getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    @DisplayName("Error-trigger dispatch failure does not block SSE, signals, registry, permits, or other runs")
    void errorTriggerDispatchFailureIsIsolated() {
        WorkflowRunEntity zombie = createZombieRun("run-err-throws", NOW.minus(Duration.ofMinutes(10)));
        when(runRepository.findByStatusAndUpdatedAtBefore(any(), any())).thenReturn(List.of(zombie));
        doThrow(new RuntimeException("error-trigger dispatcher boom"))
                .when(errorTriggerDispatchService).dispatchWorkflowFailure(any(WorkflowExecution.class));

        service.recoverZombieRuns();

        // Every sibling cleanup step still ran.
        verify(unifiedSignalService).cancelByRun("run-err-throws");
        verify(workflowRedisPublisher).setAgentCancelSignal("run-err-throws");
        verify(pendingAgentRegistry).removeByRunId("run-err-throws");
        verify(epochConcurrencyLimiter).cleanup("run-err-throws");
        verify(streamingService).sendWorkflowStatusEvent(
                any(WorkflowExecution.class), eq(RunStatus.FAILED), any());
    }

    @Test
    @DisplayName("No error-trigger dispatch when ErrorTriggerDispatchService bean is not wired (queue-disabled deployments)")
    void noErrorTriggerWhenDispatcherUnwired() {
        // Reset the service WITHOUT the dispatcher to simulate a minimal deployment.
        Clock fixedClock = Clock.fixed(NOW, ZoneId.of("UTC"));
        OrchestrationRecoveryService minimal = new OrchestrationRecoveryService(
                runRepository, signalWaitRepository, fixedClock);
        minimal.setUnifiedSignalService(unifiedSignalService);
        minimal.setStreamingService(streamingService);

        WorkflowRunEntity zombie = createZombieRun("run-no-dispatcher", NOW.minus(Duration.ofMinutes(10)));
        when(runRepository.findByStatusAndUpdatedAtBefore(any(), any())).thenReturn(List.of(zombie));

        minimal.recoverZombieRuns();

        // No interactions with the optional dispatcher (it's not wired).
        verify(errorTriggerDispatchService, never()).dispatchWorkflowFailure(any());
        // But the rest of the cleanup did happen.
        verify(unifiedSignalService).cancelByRun("run-no-dispatcher");
        verify(streamingService).sendWorkflowStatusEvent(
                any(WorkflowExecution.class), eq(RunStatus.FAILED), any());
    }

    // --- helpers ---

    /**
     * Builds a zombie run with the minimum plan required for SSE event construction.
     * The plan only needs to be parseable by {@code WorkflowPlan.fromMap}; an empty
     * map with an id field is enough.
     */
    private WorkflowRunEntity createZombieRun(String runIdPublic, Instant lastUpdated) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(runIdPublic);
        run.setTenantId("test-tenant");
        run.setStatus(RunStatus.RUNNING);
        run.setUpdatedAt(lastUpdated);
        Map<String, Object> plan = new HashMap<>();
        plan.put("id", "wf-" + runIdPublic);
        plan.put("triggers", List.of());
        plan.put("mcps", List.of());
        plan.put("agents", List.of());
        plan.put("edges", List.of());
        plan.put("cores", List.of());
        run.setPlan(plan);
        return run;
    }
}
