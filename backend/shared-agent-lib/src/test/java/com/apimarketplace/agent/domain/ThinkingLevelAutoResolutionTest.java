package com.apimarketplace.agent.domain;

import com.apimarketplace.agent.loop.CallPurpose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 1b - pin the {@link ThinkingLevel#auto(CallPurpose, int, int)}
 * resolution rule. Callers that opt in must get the exact same tier across
 * pods and restarts for a given (purpose, toolCount, userMsgChars) triple -
 * cost attribution and cache-stability both depend on this being deterministic.
 *
 * <p>Rule locked here:
 * <ul>
 *   <li>{@code null purpose} → treated as {@link CallPurpose#MAIN} (matches
 *   {@link CallPurpose#orDefault(CallPurpose)} so adaptive-budget behavior is
 *   the fallback for omitted values, not a separate MEDIUM track).</li>
 *   <li>{@code CLASSIFY} / {@code GUARDRAIL} → {@link ThinkingLevel#MEDIUM} (any shape)</li>
 *   <li>{@code MAIN} with {@code tools ≤ 2} AND {@code chars < 50} → {@link ThinkingLevel#LOW}</li>
 *   <li>{@code MAIN} otherwise → {@link ThinkingLevel#HIGH}</li>
 * </ul>
 */
@DisplayName("ThinkingLevel.auto - adaptive resolution (Stage 1b)")
class ThinkingLevelAutoResolutionTest {

    @Test
    @DisplayName("null purpose → treated as MAIN (CallPurpose.orDefault contract); tiny turn → LOW")
    void nullPurposeTreatedAsMainShortTurnIsLow() {
        // null → MAIN → tools=0, chars=0 are both under the thresholds → LOW.
        assertThat(ThinkingLevel.auto(null, 0, 0)).isEqualTo(ThinkingLevel.LOW);
    }

    @Test
    @DisplayName("null purpose → treated as MAIN; long turn → HIGH (consistent with CallPurpose.orDefault=MAIN)")
    void nullPurposeTreatedAsMainLongTurnIsHigh() {
        assertThat(ThinkingLevel.auto(null, 20, 5000)).isEqualTo(ThinkingLevel.HIGH);
    }

    @Test
    @DisplayName("CLASSIFY → MEDIUM regardless of toolCount / userMsgChars")
    void classifyAlwaysMedium() {
        assertThat(ThinkingLevel.auto(CallPurpose.CLASSIFY, 0, 0)).isEqualTo(ThinkingLevel.MEDIUM);
        assertThat(ThinkingLevel.auto(CallPurpose.CLASSIFY, 50, 2000)).isEqualTo(ThinkingLevel.MEDIUM);
    }

    @Test
    @DisplayName("GUARDRAIL → MEDIUM regardless of toolCount / userMsgChars")
    void guardrailAlwaysMedium() {
        assertThat(ThinkingLevel.auto(CallPurpose.GUARDRAIL, 0, 0)).isEqualTo(ThinkingLevel.MEDIUM);
        assertThat(ThinkingLevel.auto(CallPurpose.GUARDRAIL, 50, 2000)).isEqualTo(ThinkingLevel.MEDIUM);
    }

    @Test
    @DisplayName("MAIN short turn (≤2 tools, <50 chars) → LOW - fast-path routing")
    void mainShortTurnIsLow() {
        assertThat(ThinkingLevel.auto(CallPurpose.MAIN, 0, 10)).isEqualTo(ThinkingLevel.LOW);
        assertThat(ThinkingLevel.auto(CallPurpose.MAIN, 2, 49)).isEqualTo(ThinkingLevel.LOW);
        assertThat(ThinkingLevel.auto(CallPurpose.MAIN, 1, 0)).isEqualTo(ThinkingLevel.LOW);
    }

    @Test
    @DisplayName("MAIN with >2 tools → HIGH (tool surface implies planning)")
    void mainManyToolsIsHigh() {
        assertThat(ThinkingLevel.auto(CallPurpose.MAIN, 3, 10)).isEqualTo(ThinkingLevel.HIGH);
        assertThat(ThinkingLevel.auto(CallPurpose.MAIN, 20, 5)).isEqualTo(ThinkingLevel.HIGH);
    }

    @Test
    @DisplayName("MAIN with ≥50 chars → HIGH (long message implies substance)")
    void mainLongMessageIsHigh() {
        assertThat(ThinkingLevel.auto(CallPurpose.MAIN, 0, 50)).isEqualTo(ThinkingLevel.HIGH);
        assertThat(ThinkingLevel.auto(CallPurpose.MAIN, 2, 5000)).isEqualTo(ThinkingLevel.HIGH);
    }

    @Test
    @DisplayName("MAIN boundary: tools=2 AND chars=49 → LOW; tools=3 OR chars=50 → HIGH")
    void boundaryConditions() {
        // Just inside both thresholds → LOW
        assertThat(ThinkingLevel.auto(CallPurpose.MAIN, 2, 49)).isEqualTo(ThinkingLevel.LOW);
        // Tool threshold exceeded by one → HIGH
        assertThat(ThinkingLevel.auto(CallPurpose.MAIN, 3, 49)).isEqualTo(ThinkingLevel.HIGH);
        // Char threshold exceeded by one → HIGH
        assertThat(ThinkingLevel.auto(CallPurpose.MAIN, 2, 50)).isEqualTo(ThinkingLevel.HIGH);
    }

    @Test
    @DisplayName("never returns null - every (purpose, ints) triple resolves to a tier")
    void neverReturnsNull() {
        for (CallPurpose p : CallPurpose.values()) {
            assertThat(ThinkingLevel.auto(p, 0, 0)).isNotNull();
            assertThat(ThinkingLevel.auto(p, 100, 100)).isNotNull();
        }
        assertThat(ThinkingLevel.auto(null, 0, 0)).isNotNull();
    }
}
