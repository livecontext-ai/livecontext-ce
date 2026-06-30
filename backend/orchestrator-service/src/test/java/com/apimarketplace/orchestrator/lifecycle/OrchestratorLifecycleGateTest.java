package com.apimarketplace.orchestrator.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for {@link OrchestratorLifecycleGate}. Post-2026-05-22 21:01 UTC OOM:
 * orchestrator restarted and {@code OrchestrationRecoveryService.recoverZombieRuns}
 * marked an in-flight run FAILED 5 min later because no warming window existed.
 */
@ExtendWith(MockitoExtension.class)
class OrchestratorLifecycleGateTest {

    private static class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant start) { this.now = start; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    @Test
    @DisplayName("startsInWarmingForConfiguredDurationAndTransitionsToReadyAfterWarmUntilPasses: post-boot grace window expires on schedule")
    void startsInWarmingForConfiguredDuration() {
        Instant t0 = Instant.parse("2026-05-23T00:00:00Z");
        MutableClock clock = new MutableClock(t0);
        OrchestratorLifecycleGate gate = new OrchestratorLifecycleGate(null, "test-instance", Duration.ofSeconds(60), clock);
        ReflectionTestUtils.invokeMethod(gate, "enterWarming");

        assertThat(gate.currentState()).isEqualTo(OrchestratorLifecycleGate.State.WARMING);
        assertThat(gate.isWarming()).isTrue();
        assertThat(gate.isReady()).isFalse();
        assertThat(gate.warmingUntil()).contains(t0.plusSeconds(60));

        // Advance past warm_until - the scheduled tick flips to READY
        clock.advance(Duration.ofSeconds(61));
        ReflectionTestUtils.invokeMethod(gate, "tickExitWarmingIfExpired");

        assertThat(gate.currentState()).isEqualTo(OrchestratorLifecycleGate.State.READY);
        assertThat(gate.isWarming()).isFalse();
        assertThat(gate.isReady()).isTrue();
    }

    @Test
    @DisplayName("isWarmingIsFalseAfterEnterDrainingEvenIfWarmingWindowNotElapsed: drain wins over warming")
    void enterDrainingFlipsStateImmediately() {
        Instant t0 = Instant.parse("2026-05-23T00:00:00Z");
        MutableClock clock = new MutableClock(t0);
        OrchestratorLifecycleGate gate = new OrchestratorLifecycleGate(null, "test", Duration.ofSeconds(60), clock);
        ReflectionTestUtils.invokeMethod(gate, "enterWarming");
        assertThat(gate.isWarming()).isTrue();

        gate.enterDraining();

        assertThat(gate.currentState()).isEqualTo(OrchestratorLifecycleGate.State.DRAINING);
        assertThat(gate.isWarming()).isFalse();
        assertThat(gate.isDraining()).isTrue();
        assertThat(gate.isReady()).isFalse();
        assertThat(gate.drainStartedAt()).contains(t0);
    }

    @Test
    @DisplayName("enterDrainingIsIdempotentAndDoesNotOverwriteDrainStartedAtOnSecondCall: first drain timestamp wins")
    void enterDrainingIsIdempotent() {
        Instant t0 = Instant.parse("2026-05-23T00:00:00Z");
        MutableClock clock = new MutableClock(t0);
        OrchestratorLifecycleGate gate = new OrchestratorLifecycleGate(null, "test", Duration.ofSeconds(60), clock);
        ReflectionTestUtils.invokeMethod(gate, "enterWarming");
        gate.enterDraining();
        Instant firstDrainStart = gate.drainStartedAt().orElseThrow();

        clock.advance(Duration.ofSeconds(30));
        gate.enterDraining();

        assertThat(gate.drainStartedAt()).contains(firstDrainStart);
    }
}
