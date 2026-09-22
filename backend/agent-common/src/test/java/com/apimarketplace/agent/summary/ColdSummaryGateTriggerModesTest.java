package com.apimarketplace.agent.summary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the mode-aware half of the gate decision.
 *
 * <p>The invariants under test, in order of how expensive they are to get
 * wrong: the credit floor is never bypassed by any mode; a mode only ever
 * decides WHEN, above that floor; an invalidation keyword wins everywhere
 * because it is a correctness signal and not pacing; and the pre-feature
 * five-argument entry point still behaves exactly as cadence-only.
 */
@DisplayName("ColdSummaryGate - trigger modes")
class ColdSummaryGateTriggerModesTest {

    /** Claude-like cap: thresholdFor(4000) = 2000, so 2001+ COLD tokens clear the floor. */
    private static final int COLD_CAP = 4000;
    private static final int ABOVE_FLOOR = 2001;
    private static final int BELOW_FLOOR = 1999;
    private static final int SIZE_TRIGGER = 32_000;

    @Nested
    @DisplayName("The size GATE is a floor no mode may bypass")
    class FloorIsAbsolute {

        @ParameterizedTest
        @EnumSource(CompactionTrigger.class)
        @DisplayName("COLD below the model floor never regenerates, whatever the mode or the growth")
        void floorBeatsEveryMode(CompactionTrigger mode) {
            // Growth is enormous and the cadence is long past: only the floor
            // can be refusing here. It keeps the summariser off a zone too small
            // for a summary to be worth its own tokens.
            boolean run = ColdSummaryGate.shouldRegenerate(
                    BELOW_FLOOR, COLD_CAP, /*turnsSince*/ 999, /*cadence*/ 5,
                    /*keyword*/ false, mode, /*newColdTokens*/ 10_000_000, SIZE_TRIGGER);

            assertThat(run).isFalse();
        }

        @ParameterizedTest
        @EnumSource(CompactionTrigger.class)
        @DisplayName("Not even an invalidation keyword summarises a COLD zone below the floor")
        void floorBeatsKeyword(CompactionTrigger mode) {
            boolean run = ColdSummaryGate.shouldRegenerate(
                    BELOW_FLOOR, COLD_CAP, 0, 5, /*keyword*/ true, mode, 0, SIZE_TRIGGER);

            assertThat(run).isFalse();
        }
    }

    @Nested
    @DisplayName("TURNS - cadence only")
    class TurnsMode {

        @Test
        @DisplayName("Fires on the cadence")
        void firesOnCadence() {
            assertThat(ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP, 5, 5,
                    false, CompactionTrigger.TURNS, 0, SIZE_TRIGGER)).isTrue();
        }

        @Test
        @DisplayName("Ignores growth entirely: a huge tool result alone does not fire it")
        void ignoresGrowth() {
            // This is the production blind spot the feature exists to close:
            // one turn carrying 400k tokens cannot satisfy a cadence of 5.
            assertThat(ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP, /*turnsSince*/ 1, 5,
                    false, CompactionTrigger.TURNS, /*newColdTokens*/ 400_000, SIZE_TRIGGER)).isFalse();
        }
    }

    @Nested
    @DisplayName("SIZE - growth only")
    class SizeMode {

        @Test
        @DisplayName("Fires as soon as growth reaches the threshold, without waiting for the cadence")
        void firesOnGrowth() {
            assertThat(ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP, /*turnsSince*/ 1, 5,
                    false, CompactionTrigger.SIZE, SIZE_TRIGGER, SIZE_TRIGGER)).isTrue();
        }

        @Test
        @DisplayName("One token short of the threshold does not fire")
        void boundaryBelow() {
            assertThat(ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP, 1, 5,
                    false, CompactionTrigger.SIZE, SIZE_TRIGGER - 1, SIZE_TRIGGER)).isFalse();
        }

        @Test
        @DisplayName("Ignores the cadence: this is the documented recall trade-off of the mode")
        void ignoresCadence() {
            // A long conversation of small turns never reaches the threshold and
            // therefore never gets an envelope. Pinned so the trade-off cannot be
            // turned into SIZE_OR_TURNS by accident.
            assertThat(ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP, /*turnsSince*/ 999, 5,
                    false, CompactionTrigger.SIZE, /*newColdTokens*/ 10, SIZE_TRIGGER)).isFalse();
        }
    }

    @Nested
    @DisplayName("SIZE_OR_TURNS - whichever comes first")
    class SizeOrTurnsMode {

        @Test
        @DisplayName("Fires on growth while the cadence is still far away")
        void firesOnGrowthAlone() {
            assertThat(ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP, /*turnsSince*/ 1, 5,
                    false, CompactionTrigger.SIZE_OR_TURNS, SIZE_TRIGGER, SIZE_TRIGGER)).isTrue();
        }

        @Test
        @DisplayName("Fires on the cadence while growth is negligible")
        void firesOnCadenceAlone() {
            assertThat(ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP, /*turnsSince*/ 5, 5,
                    false, CompactionTrigger.SIZE_OR_TURNS, /*newColdTokens*/ 1, SIZE_TRIGGER)).isTrue();
        }

        @Test
        @DisplayName("Neither condition met - no regeneration")
        void firesOnNeither() {
            assertThat(ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP, 1, 5,
                    false, CompactionTrigger.SIZE_OR_TURNS, 10, SIZE_TRIGGER)).isFalse();
        }
    }

    @Nested
    @DisplayName("An untrusted envelope substitutes for the SIZE condition, and only for it")
    class UntrustedEnvelope {

        @Test
        @DisplayName("REGRESSION: TURNS is unaffected, so a default deployment keeps refusing below cadence")
        void turnsIsUnaffected() {
            // The first attempt routed this signal through keywordTriggered, which
            // returns true before either condition is consulted. That made a
            // shrunk COLD zone fire the summariser in TURNS mode where the cadence
            // had always refused, in deployments that never opted in, and it
            // repeated on every assistant message until a write landed.
            boolean run = ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP,
                    /*turnsSince*/ 3, /*cadence*/ 5, /*keyword*/ false,
                    CompactionTrigger.TURNS, /*newColdTokens*/ 0, SIZE_TRIGGER,
                    /*envelopeUntrusted*/ true);

            assertThat(run).isFalse();
        }

        @Test
        @DisplayName("TURNS still fires once its own cadence is reached, untrusted or not")
        void turnsStillFiresOnItsOwnTerms() {
            assertThat(ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP, 5, 5, false,
                    CompactionTrigger.TURNS, 0, SIZE_TRIGGER, true)).isTrue();
        }

        @Test
        @DisplayName("SIZE regenerates once the cadence has elapsed, although growth is below the threshold")
        void sizeIsUnblockedOnCadence() {
            // Without this, SIZE marks an envelope stale and then refuses to
            // replace it on every later turn whenever COLD sits between the
            // credit floor and the growth threshold.
            assertThat(ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP, /*turnsSince*/ 5,
                    /*cadence*/ 5, false, CompactionTrigger.SIZE,
                    /*newColdTokens*/ 0, SIZE_TRIGGER, true)).isTrue();
        }

        @Test
        @DisplayName("REPEAT-FIRE: below the cadence an untrusted envelope does NOT fire, so a failing provider is not retried per message")
        void untrustedIsPacedByTheCadence() {
            // The stale status is stored on the row, so the condition persists
            // across turns. An unpaced substitution therefore retried on every
            // assistant message until a write landed: with a provider outage
            // that is one paid call per message, and nothing else caps
            // summariser spend here.
            assertThat(ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP, /*turnsSince*/ 1,
                    /*cadence*/ 5, false, CompactionTrigger.SIZE,
                    /*newColdTokens*/ 0, SIZE_TRIGGER, /*envelopeUntrusted*/ true)).isFalse();
            assertThat(ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP, 4, 5, false,
                    CompactionTrigger.SIZE, 0, SIZE_TRIGGER, true)).isFalse();
        }

        @Test
        @DisplayName("Real growth still fires immediately: pacing applies to the substitution, not to the condition")
        void genuineGrowthIsNotPaced() {
            assertThat(ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP, /*turnsSince*/ 1,
                    /*cadence*/ 5, false, CompactionTrigger.SIZE,
                    /*newColdTokens*/ SIZE_TRIGGER, SIZE_TRIGGER, true)).isTrue();
        }

        @Test
        @DisplayName("In SIZE_OR_TURNS the substitution is INERT: pacing it made the cadence branch subsume it")
        void substitutionIsInertInSizeOrTurns() {
            // Documented rather than asserted as a feature. Once the substitution
            // requires the cadence to have elapsed, SIZE_OR_TURNS fires on that
            // same condition through usesTurns() regardless, so the flag cannot
            // change any outcome in the recommended mode. A test that merely
            // showed "it regenerates" would pass with the mechanism deleted.
            for (int turnsSince = 0; turnsSince <= 10; turnsSince++) {
                for (int growth : new int[] {0, SIZE_TRIGGER - 1, SIZE_TRIGGER, 100_000}) {
                    boolean withFlag = ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP,
                            turnsSince, 5, false, CompactionTrigger.SIZE_OR_TURNS,
                            growth, SIZE_TRIGGER, true);
                    boolean withoutFlag = ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP,
                            turnsSince, 5, false, CompactionTrigger.SIZE_OR_TURNS,
                            growth, SIZE_TRIGGER, false);
                    assertThat(withFlag)
                            .as("turnsSince=%d growth=%d: the flag must not change SIZE_OR_TURNS",
                                    turnsSince, growth)
                            .isEqualTo(withoutFlag);
                }
            }
        }

        @Test
        @DisplayName("In SIZE the substitution is what makes the difference, at the cadence and only there")
        void substitutionIsLoadBearingInSizeOnly() {
            // The mirror of the test above: in SIZE the flag changes the result,
            // so the mechanism is proven to be doing something somewhere.
            assertThat(ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP, 5, 5, false,
                    CompactionTrigger.SIZE, 0, SIZE_TRIGGER, false)).isFalse();
            assertThat(ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP, 5, 5, false,
                    CompactionTrigger.SIZE, 0, SIZE_TRIGGER, true)).isTrue();
        }

        @ParameterizedTest
        @EnumSource(CompactionTrigger.class)
        @DisplayName("The credit floor still refuses in every mode, untrusted or not")
        void floorStillWins(CompactionTrigger mode) {
            assertThat(ColdSummaryGate.shouldRegenerate(BELOW_FLOOR, COLD_CAP, 999, 5, false,
                    mode, 0, SIZE_TRIGGER, /*envelopeUntrusted*/ true)).isFalse();
        }

        @Test
        @DisplayName("The eight-argument overload defaults it to false, so existing callers are unchanged")
        void overloadDefaultsToFalse() {
            assertThat(ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP, 3, 5, false,
                    CompactionTrigger.SIZE, 0, SIZE_TRIGGER)).isFalse();
            // At the cadence the flag is what makes the difference, so the
            // overload's default is observable rather than coincidental.
            assertThat(ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP, 5, 5, false,
                    CompactionTrigger.SIZE, 0, SIZE_TRIGGER)).isFalse();
            assertThat(ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP, 5, 5, false,
                    CompactionTrigger.SIZE, 0, SIZE_TRIGGER, true)).isTrue();
        }
    }

    @Nested
    @DisplayName("Cross-cutting behaviour")
    class CrossCutting {

        @ParameterizedTest
        @EnumSource(CompactionTrigger.class)
        @DisplayName("An invalidation keyword fires above the floor in every mode")
        void keywordWinsInEveryMode(CompactionTrigger mode) {
            // The stored envelope is known to be wrong; that is correctness,
            // not pacing, so no mode may hold it back.
            assertThat(ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP, /*turnsSince*/ 0,
                    /*cadence*/ 999, /*keyword*/ true, mode, /*newColdTokens*/ 0, SIZE_TRIGGER)).isTrue();
        }

        @Test
        @DisplayName("A null mode is read as TURNS so an un-updated caller keeps the old behaviour")
        void nullModeIsTurns() {
            assertThat(ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP, 5, 5,
                    false, /*trigger*/ null, 0, SIZE_TRIGGER)).isTrue();
            assertThat(ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP, 1, 5,
                    false, /*trigger*/ null, 400_000, SIZE_TRIGGER)).isFalse();
        }

        @Test
        @DisplayName("The five-argument overload is still exactly cadence-only")
        void legacyOverloadUnchanged() {
            assertThat(ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP, 5, 5, false)).isTrue();
            assertThat(ColdSummaryGate.shouldRegenerate(ABOVE_FLOOR, COLD_CAP, 4, 5, false)).isFalse();
            assertThat(ColdSummaryGate.shouldRegenerate(BELOW_FLOOR, COLD_CAP, 999, 5, false)).isFalse();
        }

        @Test
        @DisplayName("A non-positive size threshold falls back to the documented default, like the cadence does")
        void nonPositiveThresholdFallsBack() {
            int dflt = ColdSummaryGate.DEFAULT_SIZE_TRIGGER_COLD_TOKENS;
            assertThat(ColdSummaryGate.passesSizeTrigger(dflt, 0)).isTrue();
            assertThat(ColdSummaryGate.passesSizeTrigger(dflt - 1, 0)).isFalse();
            assertThat(ColdSummaryGate.passesSizeTrigger(dflt, -5)).isTrue();
        }

        @Test
        @DisplayName("The growth default sits well above the credit floor, so the two never collapse into one")
        void defaultsStayDistinct() {
            // If someone lowered the growth default to the floor, SIZE mode would
            // degenerate into "fire whenever the floor is cleared", i.e. every turn.
            assertThat(ColdSummaryGate.DEFAULT_SIZE_TRIGGER_COLD_TOKENS)
                    .isGreaterThan(ColdSummaryGate.MIN_COLD_TOKENS_FLOOR * 4);
        }
    }
}
