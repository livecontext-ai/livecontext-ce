package com.apimarketplace.orchestrator.lifecycle;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.async.PendingAgentRegistry;
import com.apimarketplace.orchestrator.execution.v2.async.RedisInFlightStore;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.trigger.queue.ExecutionQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentDrainCoordinatorTest {

    private static final Instant T0 = Instant.parse("2026-05-23T00:00:00Z");

    private static class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant start) { this.now = start; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** Every read advances the clock, so a drain that keeps waiting reaches its deadline in a few real sleeps. */
    private static class SteppingClock extends MutableClock {
        private final Duration step;
        SteppingClock(Instant start, Duration step) { super(start); this.step = step; }
        @Override public Instant instant() {
            Instant before = now;
            now = now.plus(step);
            return before;
        }
    }

    @Mock private PendingAgentRegistry registry;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private RedisInFlightStore inFlightStore;
    @Mock private ExecutionQueue executionQueue;
    @Mock private LocalRunExecutionTracker runTracker;

    private ch.qos.logback.classic.Logger drainLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureLogs() {
        drainLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AgentDrainCoordinator.class);
        previousLevel = drainLogger.getLevel();
        drainLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        drainLogger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        drainLogger.detachAppender(appender);
        drainLogger.setLevel(previousLevel);
        appender.stop();
        Thread.interrupted(); // never leak an interrupt flag into the next test
    }

    private OrchestratorLifecycleGate gate(Clock clock) {
        OrchestratorLifecycleGate gate = new OrchestratorLifecycleGate(null, "test", Duration.ofSeconds(60), clock);
        ReflectionTestUtils.invokeMethod(gate, "enterWarming");
        return gate;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ExecutionQueue> provider(ExecutionQueue queue) {
        ObjectProvider<ExecutionQueue> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(queue);
        return provider;
    }

    private AgentDrainCoordinator coordinator(OrchestratorLifecycleGate gate, Duration timeout, Clock clock) {
        return new AgentDrainCoordinator(gate, registry, runRepository, inFlightStore, provider(executionQueue), runTracker, timeout, clock);
    }

    private List<ILoggingEvent> events(Level level) {
        return appender.list.stream().filter(e -> e.getLevel() == level).toList();
    }

    private boolean logged(Level level, String fragment) {
        return events(level).stream().anyMatch(e -> e.getFormattedMessage().contains(fragment));
    }

    @Test
    @DisplayName("drainAndAwaitReturnsPromptlyWhenRegistryIsEmptyAndNoRunningRunsExistAtPreDestroyTime: clean shutdown exits in <1s")
    void drainAndAwaitReturnsPromptlyWhenIdle() {
        MutableClock clock = new MutableClock(T0);
        OrchestratorLifecycleGate gate = gate(clock);

        AgentDrainCoordinator coordinator = coordinator(gate, Duration.ofSeconds(60), clock);

        long start = System.currentTimeMillis();
        ReflectionTestUtils.invokeMethod(coordinator, "drainAndAwait");
        long elapsed = System.currentTimeMillis() - start;

        // 3 idle observations x >=200 ms sleep = >=600 ms. Should never exceed 5 s in practice.
        assertThat(elapsed).isBetween(500L, 5_000L);
        assertThat(gate.isDraining()).isTrue();
        verify(registry, atLeast(3)).size();
        assertThat(events(Level.ERROR)).isEmpty();
    }

    @Test
    @DisplayName("drainAndAwaitFlipsLifecycleGateToDrainingBeforeWaitingSoNewSchedulesAreRefused: state transition is the first action")
    void drainAndAwaitFlipsGateFirst() {
        MutableClock clock = new MutableClock(T0);
        OrchestratorLifecycleGate gate = gate(clock);
        assertThat(gate.isDraining()).isFalse();

        ReflectionTestUtils.invokeMethod(coordinator(gate, Duration.ofSeconds(60), clock), "drainAndAwait");

        assertThat(gate.isDraining()).isTrue();
    }

    @Test
    @DisplayName("Rolling restart: cluster-wide RUNNING runs and another replica's in-flight entries do not hold the drain nor log a TIMEOUT ERROR")
    void rollingRestartDoesNotWaitForClusterWideWorkAndLogsNoTimeoutError() {
        // Production shape of the bug: this pod owns nothing (in_flight_agents=0), while the
        // other replica is running runs and has staged deliveries in the shared Redis. Pre-fix
        // the drain waited on those cluster counts until its deadline and logged
        // "[Drain] TIMEOUT ... in_flight_agents=0" at ERROR on every rolling restart.
        SteppingClock clock = new SteppingClock(T0, Duration.ofSeconds(5));
        OrchestratorLifecycleGate gate = gate(clock);
        when(registry.size()).thenReturn(0);
        when(inFlightStore.localSize()).thenReturn(0);
        when(executionQueue.getLocalActiveExecutions()).thenReturn(0);
        when(runRepository.countByStatus(RunStatus.RUNNING)).thenReturn(7L);
        when(inFlightStore.size()).thenReturn(3);

        ReflectionTestUtils.invokeMethod(coordinator(gate, Duration.ofSeconds(60), clock), "drainAndAwait");

        assertThat(events(Level.ERROR)).as("no TIMEOUT ERROR when this pod owns no work").isEmpty();
        assertThat(logged(Level.INFO, "Steady state reached")).isTrue();
        // The cluster counts are never read while deciding whether to wait.
        verify(runRepository, never()).countByStatus(RunStatus.RUNNING);
        verify(inFlightStore, never()).size();
    }

    @Test
    @DisplayName("Local pending agents hold the drain until they complete, then it exits cleanly")
    void waitsForLocalPendingAgentsThenExits() {
        MutableClock clock = new MutableClock(T0);
        OrchestratorLifecycleGate gate = gate(clock);
        when(registry.size()).thenReturn(2, 1, 0);

        ReflectionTestUtils.invokeMethod(coordinator(gate, Duration.ofSeconds(60), clock), "drainAndAwait");

        verify(registry, times(5)).size(); // 2 busy observations + 3 idle ones
        assertThat(logged(Level.INFO, "in_flight_agents=2")).isTrue();
        assertThat(logged(Level.INFO, "Steady state reached")).isTrue();
        assertThat(events(Level.ERROR)).isEmpty();
    }

    @Test
    @DisplayName("A delivery this pod staged holds the drain until it is cleared")
    void waitsForLocallyStagedDelivery() {
        MutableClock clock = new MutableClock(T0);
        OrchestratorLifecycleGate gate = gate(clock);
        when(inFlightStore.localSize()).thenReturn(1, 0);

        ReflectionTestUtils.invokeMethod(coordinator(gate, Duration.ofSeconds(60), clock), "drainAndAwait");

        verify(inFlightStore, times(4)).localSize(); // 1 busy + 3 idle
        assertThat(logged(Level.INFO, "in_flight_staged=1")).isTrue();
        assertThat(events(Level.ERROR)).isEmpty();
    }

    @Test
    @DisplayName("A trigger execution running on this pod's queue workers holds the drain until it finishes")
    void waitsForLocalQueueExecution() {
        MutableClock clock = new MutableClock(T0);
        OrchestratorLifecycleGate gate = gate(clock);
        when(executionQueue.getLocalActiveExecutions()).thenReturn(3, 0);

        ReflectionTestUtils.invokeMethod(coordinator(gate, Duration.ofSeconds(60), clock), "drainAndAwait");

        verify(executionQueue, times(4)).getLocalActiveExecutions();
        assertThat(logged(Level.INFO, "local_executions=3")).isTrue();
        assertThat(events(Level.ERROR)).isEmpty();
    }

    @Test
    @DisplayName("A run executing on this pod outside the queue and the registry (POST /execute on the common pool) holds the drain until it ends")
    void waitsForInProcessRunOutsideQueueAndRegistry() {
        // Regression: the old cluster-wide RUNNING count held the drain for this run by
        // accident; once the drain waited on local counts only, nothing counted it and a
        // deploy cut it mid-node.
        MutableClock clock = new MutableClock(T0);
        OrchestratorLifecycleGate gate = gate(clock);
        when(runTracker.activeCount()).thenReturn(1, 1, 0);

        ReflectionTestUtils.invokeMethod(coordinator(gate, Duration.ofSeconds(60), clock), "drainAndAwait");

        verify(runTracker, times(5)).activeCount(); // 2 busy + 3 idle
        assertThat(logged(Level.INFO, "in_process_runs=1")).isTrue();
        assertThat(logged(Level.INFO, "Steady state reached")).isTrue();
        assertThat(events(Level.ERROR)).isEmpty();
    }

    @Test
    @DisplayName("drainAndAwaitLogsUnfinishedWorkAtErrorWhenTimeoutElapsedWithInFlightAgentsStillPresent: the ERROR carries local work and cluster context")
    void drainAndAwaitLogsUnfinishedWorkOnTimeout() {
        SteppingClock clock = new SteppingClock(T0, Duration.ofMillis(250));
        OrchestratorLifecycleGate gate = gate(clock);
        // Registry never empties - 5 in-flight agents stuck for the full drain window.
        when(registry.size()).thenReturn(5);
        when(runRepository.countByStatus(RunStatus.RUNNING)).thenReturn(2L);
        when(inFlightStore.size()).thenReturn(4);

        long start = System.currentTimeMillis();
        ReflectionTestUtils.invokeMethod(coordinator(gate, Duration.ofMillis(500), clock), "drainAndAwait");

        assertThat(System.currentTimeMillis() - start).isLessThan(10_000L);
        List<ILoggingEvent> errors = events(Level.ERROR);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getFormattedMessage())
            .contains("[Drain] TIMEOUT")
            .contains("in_flight_agents=5")
            .contains("cluster_RUNNING_runs=2")
            .contains("cluster_in_flight_store_size=4");
        // Cluster figures are read once, for the ERROR line only.
        verify(runRepository, times(1)).countByStatus(RunStatus.RUNNING);
        verify(inFlightStore, times(1)).size();
    }

    @Test
    @DisplayName("Local work that finishes right at the deadline is logged at INFO, never as a TIMEOUT ERROR")
    void localWorkFinishingAtDeadlineIsNotAnError() {
        SteppingClock clock = new SteppingClock(T0, Duration.ofMillis(250));
        OrchestratorLifecycleGate gate = gate(clock);
        Instant deadline = T0.plus(Duration.ofMillis(500));
        // Busy for every observation inside the window, idle once the deadline has passed.
        when(registry.size()).thenAnswer(inv -> clock.now.isAfter(deadline) ? 0 : 1);

        ReflectionTestUtils.invokeMethod(coordinator(gate, Duration.ofMillis(500), clock), "drainAndAwait");

        assertThat(events(Level.ERROR)).isEmpty();
        assertThat(logged(Level.INFO, "Local work finished at the deadline")).isTrue();
        verify(runRepository, never()).countByStatus(RunStatus.RUNNING);
    }

    @Test
    @DisplayName("Interrupted while local work is pending: WARN, then the unfinished-work ERROR, and the interrupt flag is preserved")
    void interruptedDrainEmitsSnapshot() {
        MutableClock clock = new MutableClock(T0);
        OrchestratorLifecycleGate gate = gate(clock);
        when(registry.size()).thenReturn(1);

        Thread.currentThread().interrupt();
        ReflectionTestUtils.invokeMethod(coordinator(gate, Duration.ofSeconds(60), clock), "drainAndAwait");

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(logged(Level.WARN, "Interrupted")).isTrue();
        assertThat(logged(Level.ERROR, "in_flight_agents=1")).isTrue();
    }

    @Test
    @DisplayName("No in-flight store and no execution queue bean: both count as zero, the drain exits cleanly")
    void absentOptionalBeansCountAsZero() {
        MutableClock clock = new MutableClock(T0);
        OrchestratorLifecycleGate gate = gate(clock);
        AgentDrainCoordinator coordinator = new AgentDrainCoordinator(
            gate, registry, runRepository, null, provider(null), null, Duration.ofSeconds(60), clock);

        AgentDrainCoordinator.LocalWork work = coordinator.observeLocalWork();
        ReflectionTestUtils.invokeMethod(coordinator, "drainAndAwait");

        assertThat(work.isIdle()).isTrue();
        assertThat(events(Level.ERROR)).isEmpty();
    }

    @Test
    @DisplayName("drainAndAwait runs once: a second call (the @PreDestroy fallback after stop()) is a no-op")
    void drainRunsOnce() {
        MutableClock clock = new MutableClock(T0);
        OrchestratorLifecycleGate gate = gate(clock);
        AtomicInteger reads = new AtomicInteger();
        when(registry.size()).thenAnswer(inv -> { reads.incrementAndGet(); return 0; });
        AgentDrainCoordinator coordinator = coordinator(gate, Duration.ofSeconds(60), clock);

        coordinator.stop();
        int afterStop = reads.get();
        ReflectionTestUtils.invokeMethod(coordinator, "preDestroyFallback");

        assertThat(reads.get()).isEqualTo(afterStop);
        assertThat(coordinator.isRunning()).isFalse();
    }
}
