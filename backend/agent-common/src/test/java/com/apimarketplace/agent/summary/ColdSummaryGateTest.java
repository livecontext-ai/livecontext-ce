package com.apimarketplace.agent.summary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 5.3 - pin the per-model threshold formula + the two-gate
 * AND-combine behaviour. These tests fence off R49 (Gemini COLD cap
 * contradiction) and the size-gate-vs-cadence separation.
 */
@DisplayName("ColdSummaryGate - per-model threshold + two-gate AND (Stage 5.3)")
class ColdSummaryGateTest {

    // ---- thresholdFor() ----

    @ParameterizedTest(name = "thresholdFor(coldCap={0}) = {1}")
    @CsvSource({
            // Claude COLD cap 4k → 15% × 4k = 600, floor 2k wins.
            "4000, 2000",
            // Gemini COLD cap 2.5k → 15% × 2.5k = 375, floor wins.
            "2500, 2000",
            // Weak model 0.8k → 15% × 0.8k = 120, floor wins.
            "800,  2000",
            // Very large hypothetical cap 50k → 15% × 50k = 7500, fraction wins.
            "50000, 7500",
            // Exactly at floor crossover: 15% × 13334 = 2000.1 → ceil 2001
            "13334, 2001",
            // Below crossover at 13333: 15% × 13333 = 1999.95 → ceil 2000,
            // Math.max(2000, 2000) = 2000 (floor wins at equality)
            "13333, 2000"
    })
    @DisplayName("thresholdFor: max(floor=2000, ceil(cap × 0.15))")
    void thresholdFormula(int coldCap, int expected) {
        assertThat(ColdSummaryGate.thresholdFor(coldCap)).isEqualTo(expected);
    }

    @Test
    @DisplayName("thresholdFor: non-positive cap defaults to floor (defensive)")
    void thresholdNonPositiveCap() {
        assertThat(ColdSummaryGate.thresholdFor(0)).isEqualTo(ColdSummaryGate.MIN_COLD_TOKENS_FLOOR);
        assertThat(ColdSummaryGate.thresholdFor(-1)).isEqualTo(ColdSummaryGate.MIN_COLD_TOKENS_FLOOR);
        assertThat(ColdSummaryGate.thresholdFor(Integer.MIN_VALUE)).isEqualTo(ColdSummaryGate.MIN_COLD_TOKENS_FLOOR);
    }

    // ---- passesSizeGate ----

    @Test
    @DisplayName("passesSizeGate: strict '>' semantics - exactly at threshold is NOT passing")
    void sizeGateStrictlyGreater() {
        // Pin the '>' vs '>=' choice so a refactor to '>=' can't
        // silently lower the bar.
        assertThat(ColdSummaryGate.passesSizeGate(2000, 4000))
                .as("2000 == threshold(4000) → NOT passing").isFalse();
        assertThat(ColdSummaryGate.passesSizeGate(2001, 4000)).isTrue();
    }

    @Test
    @DisplayName("passesSizeGate: Gemini (2.5k cap) fires at 2001 tokens (R49 fixed)")
    void sizeGateGeminiFires() {
        // R49 regression guard: in the v3 plan's >5k rule, Gemini
        // COLD (capped at 2.5k) could never exceed 5k → summariser
        // never fired. Pin the fix: threshold is 2000 here, so 2001
        // COLD tokens triggers.
        assertThat(ColdSummaryGate.passesSizeGate(2001, 2500))
                .as("Gemini with 2001 COLD tokens must be summarisable").isTrue();
    }

    // ---- passesCadenceOrKeywordGate ----

    @Test
    @DisplayName("cadenceOrKeyword: keyword trigger short-circuits regardless of turn count")
    void cadenceKeywordShortCircuits() {
        assertThat(ColdSummaryGate.passesCadenceOrKeywordGate(0, 5, true)).isTrue();
        assertThat(ColdSummaryGate.passesCadenceOrKeywordGate(0, 1000, true)).isTrue();
    }

    @Test
    @DisplayName("cadenceOrKeyword: turn count must be >= cadence (not strictly greater)")
    void cadenceGreaterOrEqual() {
        assertThat(ColdSummaryGate.passesCadenceOrKeywordGate(4, 5, false)).isFalse();
        assertThat(ColdSummaryGate.passesCadenceOrKeywordGate(5, 5, false)).isTrue();
        assertThat(ColdSummaryGate.passesCadenceOrKeywordGate(6, 5, false)).isTrue();
    }

    @Test
    @DisplayName("cadenceOrKeyword: non-positive cadence falls back to DEFAULT_CADENCE_TURNS=5")
    void cadenceDefaultFallback() {
        // Absent config (cadence=0 or negative) → 5-turn default
        // kicks in. Pin so a caller that forgot to populate config
        // doesn't accidentally enable "summarise every turn".
        assertThat(ColdSummaryGate.passesCadenceOrKeywordGate(4, 0, false)).isFalse();
        assertThat(ColdSummaryGate.passesCadenceOrKeywordGate(5, 0, false)).isTrue();
        assertThat(ColdSummaryGate.passesCadenceOrKeywordGate(5, -1, false)).isTrue();
    }

    // ---- shouldRegenerate (combined) ----

    @Test
    @DisplayName("shouldRegenerate: requires BOTH gates - size gate alone is not enough")
    void shouldRegenerateSizeAlone() {
        // Size passes (5000 > 2000) but cadence is 0 turns and no
        // keyword → must not fire. Pin the AND-combine.
        assertThat(ColdSummaryGate.shouldRegenerate(5000, 4000, 0, 5, false)).isFalse();
    }

    @Test
    @DisplayName("shouldRegenerate: cadence alone is not enough if size-gate fails")
    void shouldRegenerateCadenceAlone() {
        // 5 turns elapsed BUT only 100 COLD tokens → summariser
        // would spend credits on nearly-empty input. Pin the refusal.
        assertThat(ColdSummaryGate.shouldRegenerate(100, 4000, 5, 5, false)).isFalse();
    }

    @Test
    @DisplayName("shouldRegenerate: both gates pass → true")
    void shouldRegenerateBothPass() {
        assertThat(ColdSummaryGate.shouldRegenerate(5000, 4000, 5, 5, false)).isTrue();
        assertThat(ColdSummaryGate.shouldRegenerate(5000, 4000, 0, 5, true))
                .as("keyword short-circuits cadence; size still must pass").isTrue();
    }

    @Test
    @DisplayName("shouldRegenerate: keyword without size does NOT fire - prevents FP keyword explosion")
    void shouldRegenerateKeywordWithoutSize() {
        // Critical for the compaction_summary_pct alert. Without
        // this guard, a conversation with a tight COLD zone that
        // happens to have "instead of" in the last turn would
        // re-summarise for ~2k tokens of output over ~500 tokens of
        // input. Pin the refusal.
        assertThat(ColdSummaryGate.shouldRegenerate(500, 4000, 0, 5, true))
                .as("keyword fires only when summary is size-justified").isFalse();
    }

    // ---- Constants pinned ----

    @Test
    @DisplayName("constants pinned - 2000 floor, 0.15 fraction, 5-turn cadence")
    void constantsPinned() {
        // Matches Claude Code autoCompact telemetry signoff. A
        // refactor to 0.05 or 10k floor would change the entire
        // rollout economics - trip a review.
        assertThat(ColdSummaryGate.MIN_COLD_TOKENS_FLOOR).isEqualTo(2_000);
        assertThat(ColdSummaryGate.MIN_COLD_FRACTION_OF_CAP).isEqualTo(0.15);
        assertThat(ColdSummaryGate.DEFAULT_CADENCE_TURNS).isEqualTo(5);
    }
}
