package com.apimarketplace.orchestrator.config;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.events.WorkflowRunTerminatedEvent;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link OrchestrationRecoveryService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrchestrationRecoveryService")
class OrchestrationRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-10T12:00:00Z");

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private SignalWaitRepository signalWaitRepository;

    @Captor
    private ArgumentCaptor<List<WorkflowRunEntity>> savedRunsCaptor;

    private Clock fixedClock;
    private OrchestrationRecoveryService service;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(NOW, ZoneId.of("UTC"));
        service = new OrchestrationRecoveryService(runRepository, signalWaitRepository, fixedClock);
    }

    @Nested
    @DisplayName("recoverZombieRuns")
    class RecoverZombieRunsTests {

        @Test
        @DisplayName("Should query for RUNNING runs older than zombie threshold (default 10 min)")
        void shouldQueryWithCorrectCutoff() {
            when(runRepository.findByStatusAndUpdatedAtBefore(any(), any()))
                    .thenReturn(Collections.emptyList());

            service.recoverZombieRuns();

            Instant expectedCutoff = NOW.minus(service.getZombieThreshold());
            verify(runRepository).findByStatusAndUpdatedAtBefore(
                    eq(RunStatus.RUNNING), eq(expectedCutoff));
        }

        @Test
        @DisplayName("Should do nothing when no zombies found")
        void shouldDoNothingWhenNoZombies() {
            when(runRepository.findByStatusAndUpdatedAtBefore(any(), any()))
                    .thenReturn(Collections.emptyList());

            service.recoverZombieRuns();

            verify(runRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("Should mark zombie runs as FAILED")
        void shouldMarkZombiesAsFailed() {
            WorkflowRunEntity zombie = createZombieRun("run-001",
                    NOW.minus(Duration.ofMinutes(10)));

            when(runRepository.findByStatusAndUpdatedAtBefore(any(), any()))
                    .thenReturn(List.of(zombie));

            service.recoverZombieRuns();

            verify(runRepository).saveAll(savedRunsCaptor.capture());
            List<WorkflowRunEntity> saved = savedRunsCaptor.getValue();
            assertThat(saved).hasSize(1);
            assertThat(saved.get(0).getStatus()).isEqualTo(RunStatus.FAILED);
        }

        @Test
        @DisplayName("Should set endedAt to current time on recovered runs")
        void shouldSetEndedAt() {
            WorkflowRunEntity zombie = createZombieRun("run-002",
                    NOW.minus(Duration.ofMinutes(15)));

            when(runRepository.findByStatusAndUpdatedAtBefore(any(), any()))
                    .thenReturn(List.of(zombie));

            service.recoverZombieRuns();

            verify(runRepository).saveAll(savedRunsCaptor.capture());
            WorkflowRunEntity recovered = savedRunsCaptor.getValue().get(0);
            assertThat(recovered.getEndedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("Should set updatedAt to current time on recovered runs")
        void shouldSetUpdatedAt() {
            WorkflowRunEntity zombie = createZombieRun("run-003",
                    NOW.minus(Duration.ofMinutes(8)));

            when(runRepository.findByStatusAndUpdatedAtBefore(any(), any()))
                    .thenReturn(List.of(zombie));

            service.recoverZombieRuns();

            verify(runRepository).saveAll(savedRunsCaptor.capture());
            WorkflowRunEntity recovered = savedRunsCaptor.getValue().get(0);
            assertThat(recovered.getUpdatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("Should skip runs with active blocking signals")
        void shouldSkipRunsWithBlockingSignals() {
            WorkflowRunEntity waiting = createZombieRun("run-waiting",
                    NOW.minus(Duration.ofMinutes(10)));
            WorkflowRunEntity zombie = createZombieRun("run-zombie",
                    NOW.minus(Duration.ofMinutes(10)));

            when(runRepository.findByStatusAndUpdatedAtBefore(any(), any()))
                    .thenReturn(List.of(waiting, zombie));
            // run-waiting has blocking signals (e.g., AGENT_EXECUTION)
            when(signalWaitRepository.hasBlockingSignals("run-waiting")).thenReturn(true);
            when(signalWaitRepository.hasBlockingSignals("run-zombie")).thenReturn(false);

            service.recoverZombieRuns();

            verify(runRepository).saveAll(savedRunsCaptor.capture());
            List<WorkflowRunEntity> saved = savedRunsCaptor.getValue();
            assertThat(saved).hasSize(1);
            assertThat(saved.get(0).getRunIdPublic()).isEqualTo("run-zombie");
            // waiting run should still be RUNNING
            assertThat(waiting.getStatus()).isEqualTo(RunStatus.RUNNING);
        }

        @Test
        @DisplayName("Should recover multiple zombie runs in one scan")
        void shouldRecoverMultipleZombies() {
            WorkflowRunEntity z1 = createZombieRun("run-a",
                    NOW.minus(Duration.ofMinutes(6)));
            WorkflowRunEntity z2 = createZombieRun("run-b",
                    NOW.minus(Duration.ofMinutes(30)));
            WorkflowRunEntity z3 = createZombieRun("run-c",
                    NOW.minus(Duration.ofMinutes(120)));

            when(runRepository.findByStatusAndUpdatedAtBefore(any(), any()))
                    .thenReturn(List.of(z1, z2, z3));

            service.recoverZombieRuns();

            verify(runRepository).saveAll(savedRunsCaptor.capture());
            List<WorkflowRunEntity> saved = savedRunsCaptor.getValue();
            assertThat(saved).hasSize(3);
            assertThat(saved).allSatisfy(run -> {
                assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
                assertThat(run.getEndedAt()).isEqualTo(NOW);
                assertThat(run.getUpdatedAt()).isEqualTo(NOW);
            });
        }
    }

    @Nested
    @DisplayName("zombie threshold derivation from WorkflowExecutionConfig")
    class ZombieThresholdTests {

        @Test
        @DisplayName("Default threshold is 127 minutes (125 max-execution + 2 grace) - must exceed the 7200s agent contract so healthy long runs are never zombie-failed")
        void defaultThresholdCoversAgentContract() {
            assertThat(service.getZombieThreshold())
                    .isEqualTo(Duration.ofMinutes(127));
            assertThat(service.getZombieThreshold())
                    .isGreaterThan(Duration.ofSeconds(7200));
        }

        @Test
        @DisplayName("Should derive threshold from max-execution-minutes + 2 min grace")
        void shouldDeriveFromMaxExecutionMinutes() {
            WorkflowExecutionConfig config = new WorkflowExecutionConfig();
            config.setMaxExecutionMinutes(15);
            service.setExecutionConfig(config);

            service.initZombieThreshold();

            assertThat(service.getZombieThreshold())
                    .isEqualTo(Duration.ofMinutes(17));
        }

        @Test
        @DisplayName("Should use default (127 min) when maxExecutionMinutes is 0")
        void shouldUseDefaultWhenMaxExecutionMinutesIsZero() {
            WorkflowExecutionConfig config = new WorkflowExecutionConfig();
            config.setMaxExecutionMinutes(0);
            service.setExecutionConfig(config);

            service.initZombieThreshold();

            assertThat(service.getZombieThreshold())
                    .isEqualTo(Duration.ofMinutes(127));
        }
    }

    @Nested
    @DisplayName("signal check failure handling")
    class SignalCheckFailureTests {

        @Test
        @DisplayName("Should skip run when signal check throws exception (fail-safe)")
        void shouldSkipRunWhenSignalCheckFails() {
            WorkflowRunEntity zombie = createZombieRun("run-error",
                    NOW.minus(Duration.ofMinutes(10)));

            when(runRepository.findByStatusAndUpdatedAtBefore(any(), any()))
                    .thenReturn(List.of(zombie));
            when(signalWaitRepository.hasBlockingSignals("run-error"))
                    .thenThrow(new RuntimeException("DB connection lost"));

            service.recoverZombieRuns();

            // Run should NOT be marked as FAILED - skipped due to signal check failure
            verify(runRepository, never()).saveAll(any());
            assertThat(zombie.getStatus()).isEqualTo(RunStatus.RUNNING);
        }
    }

    @Nested
    @DisplayName("epoch closure on zombie recovery")
    class EpochClosureTests {

        @Mock
        private StateSnapshotService stateSnapshotService;

        @Test
        @DisplayName("Should close active epochs for all DAGs when zombie is recovered")
        void shouldCloseActiveEpochsForAllDagsOnZombieRecovery() {
            WorkflowRunEntity zombie = createZombieRun("run-epoch-test",
                    NOW.minus(Duration.ofMinutes(10)));
            when(runRepository.findByStatusAndUpdatedAtBefore(any(), any()))
                    .thenReturn(List.of(zombie));

            StateSnapshot snapshot = mock(StateSnapshot.class);
            when(snapshot.getDags()).thenReturn(Map.of(
                    "trigger:start", mock(com.apimarketplace.orchestrator.domain.execution.DagState.class),
                    "trigger:cron", mock(com.apimarketplace.orchestrator.domain.execution.DagState.class)));
            when(stateSnapshotService.getSnapshot("run-epoch-test")).thenReturn(snapshot);

            service.setStateSnapshotService(stateSnapshotService);
            service.recoverZombieRuns();

            verify(stateSnapshotService).closeAllActiveEpochs("run-epoch-test", "trigger:start");
            verify(stateSnapshotService).closeAllActiveEpochs("run-epoch-test", "trigger:cron");
        }

        @Test
        @DisplayName("Should handle null snapshot gracefully without throwing")
        void shouldSkipEpochClosureWhenSnapshotIsNull() {
            WorkflowRunEntity zombie = createZombieRun("run-null-snap",
                    NOW.minus(Duration.ofMinutes(10)));
            when(runRepository.findByStatusAndUpdatedAtBefore(any(), any()))
                    .thenReturn(List.of(zombie));
            when(stateSnapshotService.getSnapshot("run-null-snap")).thenReturn(null);

            service.setStateSnapshotService(stateSnapshotService);
            service.recoverZombieRuns();

            verify(stateSnapshotService, never()).closeAllActiveEpochs(any(), any());
            // Run should still be marked FAILED
            verify(runRepository).saveAll(any());
        }

        @Test
        @DisplayName("Should continue event publishing when epoch closure throws exception")
        void shouldContinueWhenEpochClosureThrows() {
            ApplicationEventPublisher mockPublisher = mock(ApplicationEventPublisher.class);
            WorkflowRunEntity zombie = createZombieRun("run-epoch-err",
                    NOW.minus(Duration.ofMinutes(10)));
            when(runRepository.findByStatusAndUpdatedAtBefore(any(), any()))
                    .thenReturn(List.of(zombie));
            when(stateSnapshotService.getSnapshot("run-epoch-err"))
                    .thenThrow(new RuntimeException("DB connection lost"));

            service.setStateSnapshotService(stateSnapshotService);
            service.setEventPublisher(mockPublisher);
            service.recoverZombieRuns();

            // Event should still be published despite epoch closure failure
            verify(mockPublisher).publishEvent(any(WorkflowRunTerminatedEvent.class));
        }
    }

    @Nested
    @DisplayName("notification event on zombie recovery")
    class NotificationEventTests {

        @Test
        @DisplayName("Should publish WorkflowRunTerminatedEvent with correct fields on zombie recovery")
        void shouldPublishTerminatedEventOnZombieRecovery() {
            ApplicationEventPublisher mockPublisher = mock(ApplicationEventPublisher.class);
            WorkflowRunEntity zombie = createZombieRun("run-notify",
                    NOW.minus(Duration.ofMinutes(10)));
            UUID runUuid = UUID.randomUUID();
            ReflectionTestUtils.setField(zombie, "id", runUuid);
            zombie.setPlanVersion(7);

            when(runRepository.findByStatusAndUpdatedAtBefore(any(), any()))
                    .thenReturn(List.of(zombie));

            service.setEventPublisher(mockPublisher);
            service.recoverZombieRuns();

            ArgumentCaptor<WorkflowRunTerminatedEvent> eventCaptor =
                    ArgumentCaptor.forClass(WorkflowRunTerminatedEvent.class);
            verify(mockPublisher).publishEvent(eventCaptor.capture());

            WorkflowRunTerminatedEvent event = eventCaptor.getValue();
            assertThat(event.runId()).isEqualTo(runUuid);
            assertThat(event.status()).isEqualTo(RunStatus.FAILED);
            assertThat(event.planVersion()).isEqualTo(7);
        }

        @Test
        @DisplayName("Should swallow event publishing failure without propagating exception")
        void shouldSwallowEventPublishingFailure() {
            ApplicationEventPublisher mockPublisher = mock(ApplicationEventPublisher.class);
            doThrow(new RuntimeException("Redis down")).when(mockPublisher).publishEvent(any());

            WorkflowRunEntity zombie = createZombieRun("run-pub-err",
                    NOW.minus(Duration.ofMinutes(10)));
            when(runRepository.findByStatusAndUpdatedAtBefore(any(), any()))
                    .thenReturn(List.of(zombie));

            service.setEventPublisher(mockPublisher);

            // Should NOT throw
            service.recoverZombieRuns();

            // Run should still be saved as FAILED
            verify(runRepository).saveAll(any());
        }
    }

    // --- helpers ---

    private WorkflowRunEntity createZombieRun(String runIdPublic, Instant lastUpdated) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(runIdPublic);
        run.setTenantId("test-tenant");
        run.setStatus(RunStatus.RUNNING);
        run.setUpdatedAt(lastUpdated);
        return run;
    }
}
