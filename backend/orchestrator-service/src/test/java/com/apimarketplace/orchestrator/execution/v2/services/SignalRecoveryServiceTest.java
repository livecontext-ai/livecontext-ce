package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity.SignalWaitStatus;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SignalRecoveryService.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SignalRecoveryService")
class SignalRecoveryServiceTest {

    @Mock private SignalWaitRepository mockRepository;
    @Mock private com.apimarketplace.orchestrator.repository.WorkflowRunRepository mockRunRepository;
    @Mock private SignalResumeService mockResumeService;
    @Mock private SignalTimerScheduler mockTimerScheduler;

    private Clock fixedClock;
    private SignalRecoveryService recoveryService;

    @BeforeEach
    void setUp() throws Exception {
        fixedClock = Clock.fixed(Instant.parse("2025-01-15T12:00:00Z"), ZoneId.of("UTC"));
        recoveryService = new SignalRecoveryService(mockRepository, mockRunRepository, fixedClock);

        // Inject the lazy signalResumeService via reflection
        Field field = SignalRecoveryService.class.getDeclaredField("signalResumeService");
        field.setAccessible(true);
        field.set(recoveryService, mockResumeService);

        // Inject the lazy timerScheduler via reflection
        Field timerField = SignalRecoveryService.class.getDeclaredField("timerScheduler");
        timerField.setAccessible(true);
        timerField.set(recoveryService, mockTimerScheduler);
    }

    @Nested
    @DisplayName("recoverSignalsOnStartup")
    class RecoverSignalsOnStartup {

        @Test
        @DisplayName("should reset stale claims and resume orphaned signals")
        void shouldResetAndResume() {
            // Stale claims reset
            when(mockRepository.resetStaleClaims(any(Instant.class))).thenReturn(2);

            // Orphaned resolved signals
            SignalWaitEntity orphanSignal = mock(SignalWaitEntity.class);
            when(orphanSignal.getId()).thenReturn(1L);
            when(orphanSignal.getRunId()).thenReturn("run-1");
            when(orphanSignal.getNodeId()).thenReturn("core:wait");
            when(mockRepository.findResolvedSignalsForRunningWorkflows(any(Instant.class)))
                .thenReturn(List.of(orphanSignal));

            // Pending signal summary
            when(mockRepository.findAll()).thenReturn(List.of());

            recoveryService.recoverSignalsOnStartup();

            verify(mockRepository).resetStaleClaims(any(Instant.class));
            verify(mockRepository).findResolvedSignalsForRunningWorkflows(any(Instant.class));
            verify(mockResumeService).resumeAfterSignal(orphanSignal);
        }

        @Test
        @DisplayName("should handle no stale claims and no orphans")
        void shouldHandleNoRecoveryNeeded() {
            when(mockRepository.resetStaleClaims(any(Instant.class))).thenReturn(0);
            when(mockRepository.findResolvedSignalsForRunningWorkflows(any(Instant.class)))
                .thenReturn(List.of());
            when(mockRepository.findAll()).thenReturn(List.of());

            recoveryService.recoverSignalsOnStartup();

            verify(mockRepository).resetStaleClaims(any(Instant.class));
            verify(mockResumeService, never()).resumeAfterSignal(any());
        }

        @Test
        @DisplayName("should handle exception during recovery gracefully")
        void shouldHandleExceptionGracefully() {
            when(mockRepository.resetStaleClaims(any(Instant.class)))
                .thenThrow(new RuntimeException("DB error"));

            // Should not throw
            recoveryService.recoverSignalsOnStartup();
        }

        @Test
        @DisplayName("should continue processing remaining signals when one fails")
        void shouldContinueOnIndividualFailure() {
            when(mockRepository.resetStaleClaims(any(Instant.class))).thenReturn(0);

            SignalWaitEntity signal1 = mock(SignalWaitEntity.class);
            when(signal1.getId()).thenReturn(1L);
            when(signal1.getRunId()).thenReturn("run-1");
            when(signal1.getNodeId()).thenReturn("core:wait1");

            SignalWaitEntity signal2 = mock(SignalWaitEntity.class);
            when(signal2.getId()).thenReturn(2L);
            when(signal2.getRunId()).thenReturn("run-2");
            when(signal2.getNodeId()).thenReturn("core:wait2");

            when(mockRepository.findResolvedSignalsForRunningWorkflows(any(Instant.class)))
                .thenReturn(List.of(signal1, signal2));

            doThrow(new RuntimeException("Resume failed")).when(mockResumeService).resumeAfterSignal(signal1);
            doNothing().when(mockResumeService).resumeAfterSignal(signal2);

            when(mockRepository.findAll()).thenReturn(List.of());

            recoveryService.recoverSignalsOnStartup();

            // Both should have been attempted
            verify(mockResumeService).resumeAfterSignal(signal1);
            verify(mockResumeService).resumeAfterSignal(signal2);
        }

        @Test
        @DisplayName("should log pending signal summary by type")
        void shouldLogPendingSignalSummary() {
            when(mockRepository.resetStaleClaims(any(Instant.class))).thenReturn(0);
            when(mockRepository.findResolvedSignalsForRunningWorkflows(any(Instant.class)))
                .thenReturn(List.of());

            SignalWaitEntity timerSignal = mock(SignalWaitEntity.class);
            when(timerSignal.getStatus()).thenReturn(SignalWaitStatus.PENDING);
            when(timerSignal.getSignalType()).thenReturn(SignalType.WAIT_TIMER);

            SignalWaitEntity approvalSignal = mock(SignalWaitEntity.class);
            when(approvalSignal.getStatus()).thenReturn(SignalWaitStatus.PENDING);
            when(approvalSignal.getSignalType()).thenReturn(SignalType.USER_APPROVAL);

            SignalWaitEntity resolvedSignal = mock(SignalWaitEntity.class);
            when(resolvedSignal.getStatus()).thenReturn(SignalWaitStatus.RESOLVED);

            when(mockRepository.findAll()).thenReturn(List.of(timerSignal, approvalSignal, resolvedSignal));

            // Should not throw - this just tests the logging branch
            recoveryService.recoverSignalsOnStartup();
        }

        @Test
        @DisplayName("should use correct stale threshold of 30 seconds")
        void shouldUseCorrectStaleThreshold() {
            when(mockRepository.resetStaleClaims(any(Instant.class))).thenReturn(0);
            when(mockRepository.findResolvedSignalsForRunningWorkflows(any(Instant.class)))
                .thenReturn(List.of());
            when(mockRepository.findAll()).thenReturn(List.of());

            recoveryService.recoverSignalsOnStartup();

            Instant expectedThreshold = fixedClock.instant().minus(Duration.ofSeconds(30));
            verify(mockRepository).resetStaleClaims(eq(expectedThreshold));
        }

        @Test
        @DisplayName("should use correct cutoff of 1 hour for orphaned signals")
        void shouldUseCorrectCutoff() {
            when(mockRepository.resetStaleClaims(any(Instant.class))).thenReturn(0);
            when(mockRepository.findResolvedSignalsForRunningWorkflows(any(Instant.class)))
                .thenReturn(List.of());
            when(mockRepository.findAll()).thenReturn(List.of());

            recoveryService.recoverSignalsOnStartup();

            Instant expectedCutoff = fixedClock.instant().minus(Duration.ofHours(1));
            verify(mockRepository).findResolvedSignalsForRunningWorkflows(eq(expectedCutoff));
        }

        @Test
        @DisplayName("binds run.organizationId on the ApplicationReadyEvent thread during resumeAfterSignal (prod fire 2026-05-20 follow-up)")
        void bindsOrganizationIdDuringOrphanResume() {
            String runId = "run-orphan-1";
            String orgId = "00000000-0000-0000-0000-000000000000";

            SignalWaitEntity orphanSignal = new SignalWaitEntity();
            orphanSignal.setId(101L);
            orphanSignal.setRunId(runId);
            orphanSignal.setNodeId("core:gate");

            when(mockRepository.resetStaleClaims(any(Instant.class))).thenReturn(0);
            when(mockRepository.findResolvedSignalsForRunningWorkflows(any(Instant.class)))
                .thenReturn(List.of(orphanSignal));
            when(mockRepository.findAll()).thenReturn(List.of());

            com.apimarketplace.orchestrator.domain.WorkflowRunEntity run =
                new com.apimarketplace.orchestrator.domain.WorkflowRunEntity();
            run.setRunIdPublic(runId);
            run.setOrganizationId(orgId);
            when(mockRunRepository.findByRunIdPublic(runId)).thenReturn(java.util.Optional.of(run));

            java.util.concurrent.atomic.AtomicReference<String> orgInsideResume =
                new java.util.concurrent.atomic.AtomicReference<>();
            doAnswer(inv -> {
                orgInsideResume.set(
                    com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId());
                return null;
            }).when(mockResumeService).resumeAfterSignal(orphanSignal);

            // Pre-condition: startup recovery thread has no prior binding.
            org.assertj.core.api.Assertions.assertThat(
                com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId()).isNull();

            recoveryService.recoverSignalsOnStartup();

            org.assertj.core.api.Assertions.assertThat(orgInsideResume.get())
                .as("orgId must be bound on the recovery thread before resumeAfterSignal")
                .isEqualTo(orgId);
            org.assertj.core.api.Assertions.assertThat(
                com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId())
                .as("ThreadLocal restored after sweep")
                .isNull();
        }
    }
}
