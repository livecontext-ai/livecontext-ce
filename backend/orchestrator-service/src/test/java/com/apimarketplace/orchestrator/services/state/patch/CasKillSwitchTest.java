package com.apimarketplace.orchestrator.services.state.patch;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Plan v4 §1.11 - CasKillSwitch")
class CasKillSwitchTest {

    private SimpleMeterRegistry meterRegistry;
    private CasKillSwitch killSwitch;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        killSwitch = new CasKillSwitch(meterRegistry);
        // listenerContainer null - narrow test context; pub/sub disabled but
        // direct __testForcePubsubKill simulates the activation.
    }

    @Nested
    @DisplayName("Default state")
    class DefaultState {

        @Test
        @DisplayName("Fresh kill-switch reports CAS NOT disabled (both layers inactive)")
        void freshSwitchIsInactive() {
            assertThat(killSwitch.isCasDisabled()).isFalse();
            assertThat(killSwitch.isPubsubKilled()).isFalse();
            assertThat(killSwitch.isCircuitOpen()).isFalse();
        }
    }

    @Nested
    @DisplayName("Pub/sub kill-switch")
    class PubsubKill {

        @Test
        @DisplayName("Force pub/sub kill → isCasDisabled true")
        void pubsubKillDisablesCas() {
            killSwitch.__testForcePubsubKill(true);

            assertThat(killSwitch.isPubsubKilled()).isTrue();
            assertThat(killSwitch.isCasDisabled()).isTrue();
        }

        @Test
        @DisplayName("Pub/sub kill auto-resets after PUBSUB_DISABLE_DURATION via reflection on field")
        void pubsubKillAutoResets() throws Exception {
            killSwitch.__testForcePubsubKill(true);
            assertThat(killSwitch.isPubsubKilled()).isTrue();

            // Manually advance the killed-at timestamp beyond the auto-reset window
            java.lang.reflect.Field f = CasKillSwitch.class.getDeclaredField("pubsubKilledAt");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.atomic.AtomicReference<java.time.Instant> killedAt =
                    (java.util.concurrent.atomic.AtomicReference<java.time.Instant>) f.get(killSwitch);
            killedAt.set(java.time.Instant.now().minus(java.time.Duration.ofMinutes(20)));

            // Reading isPubsubKilled now triggers the CAS auto-reset
            assertThat(killSwitch.isPubsubKilled()).isFalse();
        }
    }

    @Nested
    @DisplayName("Local circuit breaker - tumbling 10s window conflict-rate trigger")
    class CircuitBreaker {

        @Test
        @DisplayName("All-successes window → circuit stays closed")
        void allSuccessesKeepCircuitClosed() {
            for (int i = 0; i < 50; i++) {
                killSwitch.recordCasSuccess();
            }
            killSwitch.checkCircuitBreaker();

            assertThat(killSwitch.isCircuitOpen()).isFalse();
        }

        @Test
        @DisplayName("60% conflict rate over 25 samples → circuit trips")
        void highConflictRateTripsCircuit() {
            for (int i = 0; i < 10; i++) killSwitch.recordCasSuccess();
            for (int i = 0; i < 15; i++) killSwitch.recordCasConflict();
            killSwitch.checkCircuitBreaker();

            assertThat(killSwitch.isCircuitOpen()).isTrue();
            assertThat(killSwitch.isCasDisabled()).isTrue();
            assertThat(meterRegistry.counter("orchestrator.cas_kill_switch.circuit_trip_count").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("Below MIN_SAMPLES (20) → circuit stays closed even at 100% conflict rate")
        void belowMinSamplesDoesNotTrip() {
            // 10 samples all conflicts - below MIN_SAMPLES=20
            for (int i = 0; i < 10; i++) killSwitch.recordCasConflict();
            killSwitch.checkCircuitBreaker();

            assertThat(killSwitch.isCircuitOpen())
                    .as("MIN_SAMPLES not met - circuit must stay closed to avoid false-positive trip on early test traffic")
                    .isFalse();
        }

        @Test
        @DisplayName("50% conflict rate is at the threshold - stays closed (must be strictly greater than 50%)")
        void exactlyHalfDoesNotTrip() {
            for (int i = 0; i < 15; i++) killSwitch.recordCasSuccess();
            for (int i = 0; i < 15; i++) killSwitch.recordCasConflict();
            killSwitch.checkCircuitBreaker();

            assertThat(killSwitch.isCircuitOpen())
                    .as("50% exactly = threshold; strict > 50% is the trip condition")
                    .isFalse();
        }

        @Test
        @DisplayName("Circuit auto-resets after CIRCUIT_COOLDOWN (60s)")
        void circuitAutoResetsAfterCooldown() throws Exception {
            killSwitch.__testForceCircuitOpen(true);
            assertThat(killSwitch.isCircuitOpen()).isTrue();

            // Advance opened-at beyond cooldown
            java.lang.reflect.Field f = CasKillSwitch.class.getDeclaredField("circuitOpenedAt");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.atomic.AtomicReference<java.time.Instant> openedAt =
                    (java.util.concurrent.atomic.AtomicReference<java.time.Instant>) f.get(killSwitch);
            openedAt.set(java.time.Instant.now().minus(java.time.Duration.ofSeconds(90)));

            assertThat(killSwitch.isCircuitOpen()).isFalse();
        }

        @Test
        @DisplayName("Window counters reset after every checkCircuitBreaker tick")
        void windowResetsEachTick() {
            // 14 successes + 16 conflicts = 30 samples, 53.3% conflict rate → trips
            for (int i = 0; i < 14; i++) killSwitch.recordCasSuccess();
            for (int i = 0; i < 16; i++) killSwitch.recordCasConflict();
            killSwitch.checkCircuitBreaker();

            // Counters reset - feed in just 5 successes
            for (int i = 0; i < 5; i++) killSwitch.recordCasSuccess();
            killSwitch.checkCircuitBreaker();

            // 5 samples is below MIN_SAMPLES; no second trip
            assertThat(meterRegistry.counter("orchestrator.cas_kill_switch.circuit_trip_count").count())
                    .isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("isCasDisabled - combined gate (pub/sub OR circuit)")
    class CombinedGate {

        @Test
        @DisplayName("Both layers active → isCasDisabled true")
        void bothLayersActive() {
            killSwitch.__testForcePubsubKill(true);
            killSwitch.__testForceCircuitOpen(true);

            assertThat(killSwitch.isCasDisabled()).isTrue();
        }

        @Test
        @DisplayName("Only pub/sub active → isCasDisabled true")
        void onlyPubsubActive() {
            killSwitch.__testForcePubsubKill(true);

            assertThat(killSwitch.isCasDisabled()).isTrue();
        }

        @Test
        @DisplayName("Only circuit active → isCasDisabled true")
        void onlyCircuitActive() {
            killSwitch.__testForceCircuitOpen(true);

            assertThat(killSwitch.isCasDisabled()).isTrue();
        }
    }
}
