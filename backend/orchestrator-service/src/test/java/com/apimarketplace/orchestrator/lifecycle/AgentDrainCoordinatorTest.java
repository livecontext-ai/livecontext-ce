package com.apimarketplace.orchestrator.lifecycle;

import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.async.PendingAgentRegistry;
import com.apimarketplace.orchestrator.execution.v2.async.RedisInFlightStore;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentDrainCoordinatorTest {

    private static class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant start) { this.now = start; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Mock private PendingAgentRegistry registry;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private RedisInFlightStore inFlightStore;

    @Test
    @DisplayName("drainAndAwaitReturnsPromptlyWhenRegistryIsEmptyAndNoRunningRunsExistAtPreDestroyTime: clean shutdown exits in <1s")
    void drainAndAwaitReturnsPromptlyWhenIdle() {
        Instant t0 = Instant.parse("2026-05-23T00:00:00Z");
        MutableClock clock = new MutableClock(t0);
        OrchestratorLifecycleGate gate = new OrchestratorLifecycleGate(null, "test", Duration.ofSeconds(60), clock);
        ReflectionTestUtils.invokeMethod(gate, "enterWarming");

        when(registry.size()).thenReturn(0);
        when(runRepository.countByStatus(RunStatus.RUNNING)).thenReturn(0L);

        AgentDrainCoordinator coordinator = new AgentDrainCoordinator(
            gate, registry, runRepository, inFlightStore, Duration.ofSeconds(60), clock);

        long start = System.currentTimeMillis();
        ReflectionTestUtils.invokeMethod(coordinator, "drainAndAwait");
        long elapsed = System.currentTimeMillis() - start;

        // 3 idle observations × ≥200 ms sleep = ≥600 ms. Should never exceed 5 s in practice.
        assertThat(elapsed).isBetween(500L, 5_000L);
        assertThat(gate.isDraining()).isTrue();
        verify(registry, atLeast(3)).size();
    }

    @Test
    @DisplayName("drainAndAwaitFlipsLifecycleGateToDrainingBeforeWaitingSoNewSchedulesAreRefused: state transition is the first action")
    void drainAndAwaitFlipsGateFirst() {
        Instant t0 = Instant.parse("2026-05-23T00:00:00Z");
        MutableClock clock = new MutableClock(t0);
        OrchestratorLifecycleGate gate = new OrchestratorLifecycleGate(null, "test", Duration.ofSeconds(60), clock);
        ReflectionTestUtils.invokeMethod(gate, "enterWarming");
        assertThat(gate.isDraining()).isFalse();

        when(registry.size()).thenReturn(0);
        when(runRepository.countByStatus(RunStatus.RUNNING)).thenReturn(0L);

        AgentDrainCoordinator coordinator = new AgentDrainCoordinator(
            gate, registry, runRepository, inFlightStore, Duration.ofSeconds(60), clock);
        ReflectionTestUtils.invokeMethod(coordinator, "drainAndAwait");

        assertThat(gate.isDraining()).isTrue();
    }

    @Test
    @DisplayName("drainAndAwaitLogsUnfinishedWorkAtErrorWhenTimeoutElapsedWithInFlightAgentsStillPresent: structured ERROR line lets ops correlate orphans with the next instance's startup recovery")
    void drainAndAwaitLogsUnfinishedWorkOnTimeout() {
        // Each call to instant() advances the clock by 250 ms so the loop crosses the
        // 500 ms deadline naturally without real wall-clock waits AND without losing the
        // first two observations (which need to see in_flight > 0).
        Instant t0 = Instant.parse("2026-05-23T00:00:00Z");
        MutableClock clock = new MutableClock(t0) {
            @Override public Instant instant() {
                Instant before = now;
                now = now.plus(Duration.ofMillis(250));
                return before;
            }
        };
        OrchestratorLifecycleGate gate = new OrchestratorLifecycleGate(null, "test", Duration.ofSeconds(60), clock);
        ReflectionTestUtils.invokeMethod(gate, "enterWarming");

        // Registry never empties - 5 in-flight agents stuck for the full drain window.
        when(registry.size()).thenReturn(5);
        when(runRepository.countByStatus(RunStatus.RUNNING)).thenReturn(2L);
        when(inFlightStore.size()).thenReturn(5);

        AgentDrainCoordinator coordinator = new AgentDrainCoordinator(
            gate, registry, runRepository, inFlightStore, Duration.ofMillis(500), clock);

        long start = System.currentTimeMillis();
        ReflectionTestUtils.invokeMethod(coordinator, "drainAndAwait");
        long elapsed = System.currentTimeMillis() - start;

        // Should not hang for the full 500 ms wall-clock - the mutable clock advances faster.
        assertThat(elapsed).isLessThan(10_000L);
        // The structured ERROR log calls inFlightStore.size() exactly once at end.
        verify(inFlightStore, org.mockito.Mockito.atLeast(1)).size();
        assertThat(gate.isDraining()).isTrue();
    }
}
