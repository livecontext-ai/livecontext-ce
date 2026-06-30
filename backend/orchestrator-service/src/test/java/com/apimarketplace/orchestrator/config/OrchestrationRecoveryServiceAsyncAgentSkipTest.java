package com.apimarketplace.orchestrator.config;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.async.PendingAgentRegistry;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for the async-agent zombie-kill bug observed on app-host at
 * 2026-05-03 18:10:05 UTC.
 *
 * <p>{@code OrchestrationRecoveryService.recoverZombieRuns()} force-FAILED a live workflow
 * run whose {@code agent:analyze_emails} (deepseek-chat) had been offloaded to the Redis
 * agent queue and was still executing when the watchdog fired at the 5-minute mark. The
 * watchdog skipped runs with blocking entries in {@code workflow_signal_waits} but did
 * not consult {@link PendingAgentRegistry}, which is where async agent executions live
 * after the horizontal-scaling refactor (commits 730389011 / d0a24209d / 7d9aadeb1).
 *
 * <p>The fix wires the registry into the recovery service and adds it as a second skip
 * gate, mirroring the dual lookup already used by
 * {@code ReusableTriggerService.hasActiveSignalsForTrigger}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrchestrationRecoveryService - async agent skip (zombie regression)")
class OrchestrationRecoveryServiceAsyncAgentSkipTest {

    private static final Instant NOW = Instant.parse("2026-05-03T18:10:05Z");

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private SignalWaitRepository signalWaitRepository;

    @Mock
    private PendingAgentRegistry pendingAgentRegistry;

    private OrchestrationRecoveryService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(NOW, ZoneId.of("UTC"));
        service = new OrchestrationRecoveryService(runRepository, signalWaitRepository, fixedClock);
        service.setPendingAgentRegistry(pendingAgentRegistry);
    }

    @Test
    @DisplayName("Run with in-flight async agent is not killed by zombie watchdog")
    void runWithInflightAsyncAgentIsNotKilled() {
        // Arrange - a run past the 5-minute threshold, no blocking signal,
        // but an async agent is still in flight in the registry.
        WorkflowRunEntity run = createRunningRun("run_<id>",
                NOW.minus(Duration.ofMinutes(6)));
        when(runRepository.findByStatusAndUpdatedAtBefore(any(), any()))
                .thenReturn(List.of(run));
        when(signalWaitRepository.hasBlockingSignals("run_<id>"))
                .thenReturn(false);
        when(pendingAgentRegistry.hasAnyPendingForRun("run_<id>"))
                .thenReturn(true);

        // Act
        service.recoverZombieRuns();

        // Assert - the run must remain RUNNING; nothing is persisted.
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.getEndedAt()).isNull();
        verify(runRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("Run is failed when no blocking signal and no pending async agent")
    void failsRunWhenNoBlockingSignalAndNoPendingAgent() {
        // Arrange - a true zombie: past threshold, no blocking signal, registry empty.
        WorkflowRunEntity run = createRunningRun("run-true-zombie",
                NOW.minus(Duration.ofMinutes(10)));
        when(runRepository.findByStatusAndUpdatedAtBefore(any(), any()))
                .thenReturn(List.of(run));
        when(signalWaitRepository.hasBlockingSignals("run-true-zombie")).thenReturn(false);
        when(pendingAgentRegistry.hasAnyPendingForRun("run-true-zombie")).thenReturn(false);

        // Act
        service.recoverZombieRuns();

        // Assert - original zombie-kill behavior still works.
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getEndedAt()).isEqualTo(NOW);
        verify(runRepository).saveAll(any());
    }

    @Test
    @DisplayName("Run is skipped when pending-agent check throws (fail-safe like signal check)")
    void skipsRunWhenPendingAgentCheckThrows() {
        // Arrange - registry blows up (e.g. transient bean state during shutdown);
        // we must NOT misinterpret that as "no pending agent" and kill the run.
        WorkflowRunEntity run = createRunningRun("run-registry-error",
                NOW.minus(Duration.ofMinutes(7)));
        when(runRepository.findByStatusAndUpdatedAtBefore(any(), any()))
                .thenReturn(List.of(run));
        when(signalWaitRepository.hasBlockingSignals("run-registry-error")).thenReturn(false);
        when(pendingAgentRegistry.hasAnyPendingForRun("run-registry-error"))
                .thenThrow(new RuntimeException("registry transient failure"));

        // Act
        service.recoverZombieRuns();

        // Assert - run preserved, no save invoked.
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        verify(runRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("Recovery still works when PendingAgentRegistry is not wired (queue disabled)")
    void recoversZombieWhenRegistryNotWired() {
        // Arrange - simulate scaling.agent.queue.enabled=false: no registry injected.
        Clock fixedClock = Clock.fixed(NOW, ZoneId.of("UTC"));
        OrchestrationRecoveryService noRegistryService =
                new OrchestrationRecoveryService(runRepository, signalWaitRepository, fixedClock);
        // setPendingAgentRegistry is NOT called.

        WorkflowRunEntity run = createRunningRun("run-no-registry",
                NOW.minus(Duration.ofMinutes(8)));
        when(runRepository.findByStatusAndUpdatedAtBefore(any(), any()))
                .thenReturn(List.of(run));
        when(signalWaitRepository.hasBlockingSignals("run-no-registry")).thenReturn(false);

        // Act
        noRegistryService.recoverZombieRuns();

        // Assert - without the registry, only the signal check applies; the run is failed
        // exactly as it was before the fix (preserves backward compatibility).
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        verify(runRepository).saveAll(any());
    }

    private WorkflowRunEntity createRunningRun(String runIdPublic, Instant lastUpdated) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(runIdPublic);
        run.setTenantId("test-tenant");
        run.setStatus(RunStatus.RUNNING);
        run.setUpdatedAt(lastUpdated);
        return run;
    }
}
