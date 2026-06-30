package com.apimarketplace.orchestrator.tools.websearch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pin-test for {@link BrowserAgentStopReasonMapper}.
 *
 * <p>The mapper is the single source of truth shared between
 * {@code BrowserAgentNode.mapStopReason} (workflow path) and
 * {@code BrowserAgentModule.recordObservabilityFromResult} (chat-tool path).
 * If either path were to fork the mapping the dashboard would split per-stop-reason
 * histograms across two buckets - invisible drift. This test locks every
 * runner stop_reason → canonical agent stopReason mapping in place.
 */
@DisplayName("BrowserAgentStopReasonMapper")
class BrowserAgentStopReasonMapperTest {

    @ParameterizedTest(name = "[{index}] runnerReason={0}, success={1} → {2}")
    @CsvSource({
        // Direct vocabulary matches
        "COMPLETED,        true,  COMPLETED",
        "MAX_STEPS,        false, MAX_ITERATIONS",
        "USER_TAKEOVER,    false, STOPPED_BY_USER",
        "TIMEOUT,          false, TIMEOUT",
        "CANCELLED,        false, CANCELLED",
        "BUDGET_EXHAUSTED, false, BUDGET_EXHAUSTED",
        // Error-class collapses (multiple runner reasons → single canonical ERROR)
        "LLM_FAILED,       false, ERROR",
        "SCHEMA_MISMATCH,  false, ERROR",
        "DOMAIN_BLOCKED,   false, ERROR",
        // Case-insensitive (runner may emit lowercase under some conditions)
        "completed,        true,  COMPLETED",
        "max_steps,        false, MAX_ITERATIONS",
        // Unknown runner reason → ERROR (defensive default)
        "SOMETHING_UNKNOWN, false, ERROR",
    })
    @DisplayName("known runner stop_reasons map to canonical AgentObservabilityRequest.stopReason")
    void knownReasonsMap(String runnerReason, boolean success, String expected) {
        assertThat(BrowserAgentStopReasonMapper.map(runnerReason, success))
            .isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] null/blank runnerReason, success={0} → {1}")
    @CsvSource({
        "true,  COMPLETED",
        "false, ERROR",
    })
    @DisplayName("null/blank runner reason falls back to success flag")
    void nullOrBlankFallsBackToSuccessFlag(boolean success, String expected) {
        assertThat(BrowserAgentStopReasonMapper.map(null, success)).isEqualTo(expected);
        assertThat(BrowserAgentStopReasonMapper.map("", success)).isEqualTo(expected);
        assertThat(BrowserAgentStopReasonMapper.map("   ", success)).isEqualTo(expected);
    }
}
