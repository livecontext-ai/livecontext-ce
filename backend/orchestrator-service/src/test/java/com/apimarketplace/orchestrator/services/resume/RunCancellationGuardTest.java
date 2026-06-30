package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RunCancellationGuard - WAITING_TRIGGER vs stopWorkflow disambiguation (regression: Gmail Auto-Labeler run da7994c7, 2026-05-06)")
class RunCancellationGuardTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowRedisPublisher workflowRedisPublisher;
    @Mock private WorkflowRunEntity run;

    @Nested
    @DisplayName("Terminal statuses always drop late work")
    class TerminalStatuses {

        @Test
        @DisplayName("CANCELLED → stopped/terminal (true)")
        void cancelledIsTerminal() {
            RunCancellationGuard guard = new RunCancellationGuard(runRepository, workflowRedisPublisher);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));
            when(run.getStatus()).thenReturn(RunStatus.CANCELLED);

            assertThat(guard.isRunStoppedOrTerminal("run-1")).isTrue();
            // Cancel-signal lookup MUST NOT happen - already terminal
            verify(workflowRedisPublisher, never()).isAgentCancelSignalSet(anyString());
        }

        @Test
        @DisplayName("FAILED → stopped/terminal (true)")
        void failedIsTerminal() {
            RunCancellationGuard guard = new RunCancellationGuard(runRepository, workflowRedisPublisher);
            when(runRepository.findByRunIdPublic("run-2")).thenReturn(Optional.of(run));
            when(run.getStatus()).thenReturn(RunStatus.FAILED);

            assertThat(guard.isRunStoppedOrTerminal("run-2")).isTrue();
        }

        @Test
        @DisplayName("Run not found → treated as terminal (defensive)")
        void runNotFoundIsTerminal() {
            RunCancellationGuard guard = new RunCancellationGuard(runRepository, workflowRedisPublisher);
            when(runRepository.findByRunIdPublic("run-missing")).thenReturn(Optional.empty());

            assertThat(guard.isRunStoppedOrTerminal("run-missing")).isTrue();
        }
    }

    @Nested
    @DisplayName("WAITING_TRIGGER disambiguation via agent cancel signal")
    class WaitingTriggerDisambiguation {

        @Test
        @DisplayName("WAITING_TRIGGER + cancel signal SET → stopWorkflow path → drop (true)")
        void waitingTriggerWithCancelSignalIsStopped() {
            RunCancellationGuard guard = new RunCancellationGuard(runRepository, workflowRedisPublisher);
            when(runRepository.findByRunIdPublic("run-stop")).thenReturn(Optional.of(run));
            when(run.getStatus()).thenReturn(RunStatus.WAITING_TRIGGER);
            when(workflowRedisPublisher.isAgentCancelSignalSet("run-stop")).thenReturn(true);

            assertThat(guard.isRunStoppedOrTerminal("run-stop")).isTrue();
        }

        @Test
        @DisplayName("WAITING_TRIGGER + cancel signal NOT SET → reusable trigger between fires → allow (false)")
        void waitingTriggerWithoutCancelSignalIsAlive() {
            // The exact regression case: Gmail Auto-Labeler reusable cron whose epoch closed
            // cleanly via resetForNextCycle. Late async classify result must drive successors.
            RunCancellationGuard guard = new RunCancellationGuard(runRepository, workflowRedisPublisher);
            when(runRepository.findByRunIdPublic("run-alive")).thenReturn(Optional.of(run));
            when(run.getStatus()).thenReturn(RunStatus.WAITING_TRIGGER);
            when(workflowRedisPublisher.isAgentCancelSignalSet("run-alive")).thenReturn(false);

            assertThat(guard.isRunStoppedOrTerminal("run-alive")).isFalse();
        }

        @Test
        @DisplayName("WAITING_TRIGGER + Redis publisher unavailable → fail-open (false / allow)")
        void waitingTriggerWithoutPublisherFailsOpen() {
            // Without a publisher we can't read the cancel signal. We allow traversal
            // (fail-open) because dropping late work on a reusable run is a hard data-loss
            // bug, while letting it through is a recoverable no-op when the run is alive.
            RunCancellationGuard guard = new RunCancellationGuard(runRepository, /* publisher = */ null);
            when(runRepository.findByRunIdPublic("run-no-redis")).thenReturn(Optional.of(run));
            when(run.getStatus()).thenReturn(RunStatus.WAITING_TRIGGER);

            assertThat(guard.isRunStoppedOrTerminal("run-no-redis")).isFalse();
        }
    }

    @Nested
    @DisplayName("Live statuses pass through")
    class LiveStatuses {

        @Test
        @DisplayName("RUNNING → not stopped (false)")
        void runningIsAlive() {
            RunCancellationGuard guard = new RunCancellationGuard(runRepository, workflowRedisPublisher);
            when(runRepository.findByRunIdPublic("run-running")).thenReturn(Optional.of(run));
            when(run.getStatus()).thenReturn(RunStatus.RUNNING);

            assertThat(guard.isRunStoppedOrTerminal("run-running")).isFalse();
            // Cancel signal MUST NOT be queried for non-WAITING_TRIGGER live statuses
            verify(workflowRedisPublisher, never()).isAgentCancelSignalSet(anyString());
        }

        @Test
        @DisplayName("PAUSED → not stopped (false)")
        void pausedIsAlive() {
            RunCancellationGuard guard = new RunCancellationGuard(runRepository, workflowRedisPublisher);
            when(runRepository.findByRunIdPublic("run-paused")).thenReturn(Optional.of(run));
            when(run.getStatus()).thenReturn(RunStatus.PAUSED);

            assertThat(guard.isRunStoppedOrTerminal("run-paused")).isFalse();
        }
    }

    @Nested
    @DisplayName("Resilience to underlying failures")
    class FailureModes {

        @Test
        @DisplayName("Repository throws → fail-open (allow traversal, log warning)")
        void repositoryExceptionFailsOpen() {
            RunCancellationGuard guard = new RunCancellationGuard(runRepository, workflowRedisPublisher);
            when(runRepository.findByRunIdPublic("run-db-down"))
                .thenThrow(new RuntimeException("DB unavailable"));

            assertThat(guard.isRunStoppedOrTerminal("run-db-down")).isFalse();
        }
    }
}
