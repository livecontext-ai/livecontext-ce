package com.apimarketplace.orchestrator.config;

import com.apimarketplace.orchestrator.lifecycle.OrchestratorLifecycleGate;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regression for the 2026-05-22 21:06:08 UTC false-positive. Pre-fix:
 * OrchestrationRecoveryService.recoverZombieRuns fired 5 min after a JVM crash, found a
 * legitimate in-flight run with no recent activity (the in-flight ack records had been
 * GETDEL'd pre-crash), and marked it FAILED. Post-fix: while the LifecycleGate is WARMING,
 * the scan returns early so AgentRecoveryService.replayInFlightEntries has time to land.
 */
@ExtendWith(MockitoExtension.class)
class OrchestrationRecoveryServiceWarmingGateTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private SignalWaitRepository signalWaitRepository;
    @Mock private OrchestratorLifecycleGate gate;

    @Test
    @DisplayName("recoverZombieRunsSkipsScanWhenLifecycleGateIsWarming: prevents the 21:06:08 false-positive failure of an in-flight run after JVM restart")
    void recoverZombieRunsSkipsScanWhenWarming() {
        when(gate.isWarming()).thenReturn(true);

        OrchestrationRecoveryService service = new OrchestrationRecoveryService(
            runRepository, signalWaitRepository, Clock.fixed(Instant.parse("2026-05-23T00:00:00Z"), java.time.ZoneId.of("UTC")));
        ReflectionTestUtils.setField(service, "lifecycleGate", gate);

        service.recoverZombieRuns();

        // Critical: the scan MUST NOT have queried for zombie candidates while WARMING.
        verifyNoInteractions(runRepository);
        verifyNoInteractions(signalWaitRepository);
    }

    @Test
    @DisplayName("recoverZombieRunsRunsNormallyWhenLifecycleGateIsReady: warming gate has no effect outside the post-boot window")
    void recoverZombieRunsRunsWhenReady() {
        when(gate.isWarming()).thenReturn(false);
        when(runRepository.findByStatusAndUpdatedAtBefore(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(java.util.List.of());

        OrchestrationRecoveryService service = new OrchestrationRecoveryService(
            runRepository, signalWaitRepository, Clock.fixed(Instant.parse("2026-05-23T00:00:00Z"), java.time.ZoneId.of("UTC")));
        ReflectionTestUtils.setField(service, "lifecycleGate", gate);

        service.recoverZombieRuns();

        verify(runRepository).findByStatusAndUpdatedAtBefore(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("recoverZombieRunsRunsWhenLifecycleGateNotWired: backward-compat path when bean is absent")
    void recoverZombieRunsRunsWhenGateAbsent() {
        when(runRepository.findByStatusAndUpdatedAtBefore(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(java.util.List.of());

        OrchestrationRecoveryService service = new OrchestrationRecoveryService(
            runRepository, signalWaitRepository, Clock.fixed(Instant.parse("2026-05-23T00:00:00Z"), java.time.ZoneId.of("UTC")));
        // lifecycleGate intentionally NOT set
        service.recoverZombieRuns();

        verify(runRepository).findByStatusAndUpdatedAtBefore(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
