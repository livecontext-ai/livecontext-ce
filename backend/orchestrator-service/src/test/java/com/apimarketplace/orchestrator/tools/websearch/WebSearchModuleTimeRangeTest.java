package com.apimarketplace.orchestrator.tools.websearch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for {@link WebSearchModule#normalizeTimeRange(String)} -
 * the defensive alias normaliser that maps whatever the LLM decided to
 * hallucinate onto the four values SearXNG actually accepts.
 */
@DisplayName("WebSearchModule.normalizeTimeRange")
class WebSearchModuleTimeRangeTest {

    @ParameterizedTest
    @ValueSource(strings = {"day", "week", "month", "year"})
    @DisplayName("canonical values pass through unchanged")
    void canonicalValues(String value) {
        assertThat(WebSearchModule.normalizeTimeRange(value)).isEqualTo(value);
    }

    @ParameterizedTest
    @CsvSource({
            // SerpAPI style
            "past_day, day",
            "past_week, week",
            "past_month, month",
            "past_year, year",
            // "last_*" form
            "last_day, day",
            "last_week, week",
            "last_month, month",
            "last_year, year",
            // Adjective form
            "daily, day",
            "weekly, week",
            "monthly, month",
            "yearly, year",
            "annual, year",
            "annually, year",
            // Single-letter
            "d, day",
            "w, week",
            "m, month",
            "y, year",
            // Relative durations / today
            "today, day",
            "24h, day",
            "24hours, day",
            "24_hours, day",
            "1d, day",
            "1day, day",
            "7d, week",
            "7days, week",
            "1week, week",
            "30d, month",
            "30days, month",
            "1month, month",
            "365d, year",
            "365days, year",
            "1year, year"
    })
    @DisplayName("known aliases map to the canonical value")
    void knownAliases(String input, String expected) {
        assertThat(WebSearchModule.normalizeTimeRange(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "DAY, day",
            "Week, week",
            "PAST_MONTH, month",
            "Past-Week, week",   // hyphenated → underscore
            "  month  , month",  // surrounding whitespace
            "Annually, year"
    })
    @DisplayName("case / hyphen / whitespace are normalised before lookup")
    void caseAndWhitespaceInsensitive(String input, String expected) {
        assertThat(WebSearchModule.normalizeTimeRange(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "    ", "hour", "forever", "random-garbage", "past_hour", "bi-weekly", "fortnight"})
    @DisplayName("unrecognised values return null → caller drops the param")
    void unknownReturnsNull(String input) {
        assertThat(WebSearchModule.normalizeTimeRange(input)).isNull();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("null input returns null (no NPE, no default)")
    void nullIsNull() {
        assertThat(WebSearchModule.normalizeTimeRange(null)).isNull();
    }
}
