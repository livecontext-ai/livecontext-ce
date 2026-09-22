package com.apimarketplace.orchestrator.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.async.PendingAgentRegistry;
import com.apimarketplace.orchestrator.execution.v2.async.RedisInFlightStore;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Regression test for the zombie-scan log flood observed in production on 2026-09-19.
 *
 * <p>The scanner logged its CANDIDATE count at WARN before applying the two skip gates.
 * A run parked on a long blocking signal (a {@code WAIT_TIMER}, or a sub-workflow waiting
 * on its child epoch) stays past the threshold for as long as it waits, so it re-qualified
 * as a candidate on every 30-second pass and was correctly skipped every time. One such run
 * produced roughly 2 880 WARN lines a day saying nothing had gone wrong, which is enough to
 * bury the WARNs that matter in the same log - the reason this is a defect and not a
 * cosmetic preference.
 *
 * <p>The contract these tests pin: a pass that recovers nothing is silent at WARN, and a
 * pass that actually fails a run is not.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrchestrationRecoveryService - zombie scan log level")
class OrchestrationRecoveryServiceScanLogLevelTest {

    private static final Instant NOW = Instant.parse("2026-09-19T07:00:00Z");

    @Mock private WorkflowRunRepository runRepository;
    @Mock private SignalWaitRepository signalWaitRepository;
    @Mock private PendingAgentRegistry pendingAgentRegistry;
    @Mock private RedisInFlightStore agentInFlightStore;

    private OrchestrationRecoveryService service;
    private ch.qos.logback.classic.Logger recoveryLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(NOW, ZoneId.of("UTC"));
        service = new OrchestrationRecoveryService(runRepository, signalWaitRepository, fixedClock);
        service.setPendingAgentRegistry(pendingAgentRegistry);
        service.setAgentInFlightStore(agentInFlightStore);

        recoveryLogger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(OrchestrationRecoveryService.class);
        previousLevel = recoveryLogger.getLevel();
        // DEBUG so the demoted candidate line is still CAPTURED here. Without this the
        // "no WARN" assertion would also pass if the line had simply been deleted, and
        // deleting it is the wrong fix: the count must stay reachable when debugging.
        recoveryLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        recoveryLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        recoveryLogger.detachAppender(appender);
        recoveryLogger.setLevel(previousLevel);
        appender.stop();
    }

    @Test
    @DisplayName("A pass that skips every candidate emits no WARN")
    void skippedCandidatesProduceNoWarning() {
        // Exactly the production shape: one run past the threshold, held by a blocking
        // signal, so the scanner deliberately leaves it alone. Pre-fix this pass logged
        // "Found 1 candidate zombie RUNNING run(s)" at WARN - every 30 seconds, forever.
        WorkflowRunEntity parked = createRunningRun("run-parked-on-signal",
                NOW.minus(Duration.ofHours(2)));
        when(runRepository.findByStatusAndUpdatedAtBefore(any(), any())).thenReturn(List.of(parked));
        when(signalWaitRepository.hasBlockingSignals("run-parked-on-signal")).thenReturn(true);

        service.recoverZombieRuns();

        assertThat(parked.getStatus())
                .as("the run must still be left alone - this test is about the log, not the behaviour")
                .isEqualTo(RunStatus.RUNNING);
        assertThat(appender.list)
                .as("a pass that recovers nothing has nothing to warn about")
                .noneMatch(event -> event.getLevel() == Level.WARN);
        assertThat(appender.list)
                .as("demoted, not deleted - the candidate count must stay reachable at DEBUG, "
                        + "or nobody can tell a quiet scanner from a stalled one")
                .anyMatch(event -> event.getLevel() == Level.DEBUG
                        && event.getFormattedMessage().contains("1 candidate zombie"));
    }

    @Test
    @DisplayName("A pass that actually fails a run still warns")
    void recoveredRunStillProducesWarning() {
        // The other half of the contract: quieting the candidate line must not quiet the
        // event that matters. Forcing a run to FAILED is a real state change and has to
        // stay visible at WARN.
        WorkflowRunEntity zombie = createRunningRun("run-true-zombie",
                NOW.minus(Duration.ofMinutes(30)));
        when(runRepository.findByStatusAndUpdatedAtBefore(any(), any())).thenReturn(List.of(zombie));
        when(signalWaitRepository.hasBlockingSignals("run-true-zombie")).thenReturn(false);
        when(pendingAgentRegistry.hasAnyPendingForRun("run-true-zombie")).thenReturn(false);
        when(agentInFlightStore.hasAnyInFlightForRun("run-true-zombie")).thenReturn(false);

        service.recoverZombieRuns();

        assertThat(zombie.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(appender.list)
                .as("a run transitioned to FAILED must be visible at WARN")
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("run-true-zombie"));
        assertThat(appender.list)
                .as("the summary must carry BOTH numbers: silencing the candidate line is only "
                        + "safe if the ratio survives where it still matters")
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("Recovered 1 zombie run(s)")
                        && event.getFormattedMessage().contains("out of 1 candidate(s)"));
    }

    private WorkflowRunEntity createRunningRun(String runId, Instant updatedAt) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(runId);
        run.setStatus(RunStatus.RUNNING);
        run.setUpdatedAt(updatedAt);
        return run;
    }
}
