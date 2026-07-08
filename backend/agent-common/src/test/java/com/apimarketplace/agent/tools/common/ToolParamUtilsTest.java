package com.apimarketplace.agent.tools.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the shared list-search helpers {@link ToolParamUtils#matchesQuery}
 * and {@link ToolParamUtils#hasQuery} used by every list action's {@code query}
 * filter (workflow.list, interface.list, skill.list, agent.list, application.my,
 * table.list). Pins the exact matching semantics all six tools now share.
 */
class ToolParamUtilsTest {

    @Nested
    @DisplayName("matchesQuery")
    class MatchesQuery {

        @Test
        @DisplayName("null query matches everything (no filter applied)")
        void nullQueryMatches() {
            assertThat(ToolParamUtils.matchesQuery(null, "anything")).isTrue();
        }

        @Test
        @DisplayName("blank/whitespace-only query matches everything")
        void blankQueryMatches() {
            assertThat(ToolParamUtils.matchesQuery("", "anything")).isTrue();
            assertThat(ToolParamUtils.matchesQuery("   ", "anything")).isTrue();
        }

        @Test
        @DisplayName("match is case-insensitive")
        void caseInsensitive() {
            assertThat(ToolParamUtils.matchesQuery("INVOICE", "Monthly invoice sync")).isTrue();
            assertThat(ToolParamUtils.matchesQuery("invoice", "MONTHLY INVOICE SYNC")).isTrue();
        }

        @Test
        @DisplayName("match is a substring, not just a prefix or whole-word")
        void substring() {
            assertThat(ToolParamUtils.matchesQuery("voic", "Invoices")).isTrue();
        }

        @Test
        @DisplayName("the query is trimmed before matching")
        void trimsQuery() {
            assertThat(ToolParamUtils.matchesQuery("  invoice  ", "invoice tool")).isTrue();
        }

        @Test
        @DisplayName("matches when ANY field contains the query (e.g. description but not name)")
        void matchesAnyField() {
            assertThat(ToolParamUtils.matchesQuery("invoice", "Order Export", "handles invoices too")).isTrue();
        }

        @Test
        @DisplayName("null fields are skipped, not NPE")
        void nullFieldsSkipped() {
            assertThat(ToolParamUtils.matchesQuery("invoice", null, "invoice tool")).isTrue();
            assertThat(ToolParamUtils.matchesQuery("invoice", (String) null)).isFalse();
        }

        @Test
        @DisplayName("returns false when no field contains the query")
        void noMatch() {
            assertThat(ToolParamUtils.matchesQuery("weather", "Invoice Sync", "billing")).isFalse();
        }

        @Test
        @DisplayName("returns false when there are no fields to match a non-blank query")
        void noFields() {
            assertThat(ToolParamUtils.matchesQuery("invoice")).isFalse();
        }
    }

    @Nested
    @DisplayName("hasQuery")
    class HasQuery {

        @Test
        @DisplayName("true only for a non-blank query")
        void nonBlank() {
            assertThat(ToolParamUtils.hasQuery("invoice")).isTrue();
            assertThat(ToolParamUtils.hasQuery("  invoice  ")).isTrue();
        }

        @Test
        @DisplayName("false for null / empty / whitespace-only")
        void blank() {
            assertThat(ToolParamUtils.hasQuery(null)).isFalse();
            assertThat(ToolParamUtils.hasQuery("")).isFalse();
            assertThat(ToolParamUtils.hasQuery("   ")).isFalse();
        }

        @Test
        @DisplayName("hasQuery agrees with matchesQuery's no-filter branch")
        void agreesWithMatches() {
            // When hasQuery is false, matchesQuery must treat it as 'match everything'.
            assertThat(ToolParamUtils.hasQuery("  ")).isFalse();
            assertThat(ToolParamUtils.matchesQuery("  ", "x")).isTrue();
        }
    }
}
