package com.apimarketplace.datasource.crud.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WhereCondition")
class WhereConditionTest {

    @Nested
    @DisplayName("normalizeOperator - alias resolution")
    class NormalizeOperatorTests {

        @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
        @CsvSource({
            // Equality aliases
            "=,      =",
            "==,     =",
            "eq,     =",
            "EQ,     =",
            "Eq,     =",
            "equals, =",
            "EQUALS, =",

            // Not-equal aliases
            "!=,         !=",
            "<>,         !=",
            "ne,         !=",
            "NE,         !=",
            "neq,        !=",
            "NEQ,        !=",
            "not_equal,  !=",
            "NOT_EQUALS, !=",

            // Greater-than aliases
            ">,  >",
            "gt, >",
            "GT, >",
            "greater_than, >",

            // Less-than aliases
            "<,  <",
            "lt, <",
            "LT, <",
            "less_than, <",

            // Greater-than-or-equal aliases
            ">=,  >=",
            "gte, >=",
            "GTE, >=",
            "ge,  >=",
            "GE,  >=",
            "greater_than_or_equal, >=",

            // Less-than-or-equal aliases
            "<=,  <=",
            "lte, <=",
            "LTE, <=",
            "le,  <=",
            "LE,  <=",
            "less_than_or_equal, <=",

            // LIKE aliases
            "LIKE,     LIKE",
            "like,     LIKE",
            "contains, LIKE",
            "CONTAINS, LIKE",

            // IS NULL aliases
            "IS NULL,  IS NULL",
            "null,     IS NULL",
            "isnull,   IS NULL",
            "is_null,  IS NULL",

            // IS NOT NULL aliases
            "IS NOT NULL, IS NOT NULL",
            "not_null,    IS NOT NULL",
            "notnull,     IS NOT NULL",
            "is_not_null, IS NOT NULL",
            "isnotnull,   IS NOT NULL",

            // IN stays as-is
            "IN, IN",
            "in, IN",
        })
        @DisplayName("should normalize operator alias")
        void shouldNormalizeAlias(String input, String expected) {
            assertThat(WhereCondition.normalizeOperator(input)).isEqualTo(expected);
        }

        @Test
        @DisplayName("should return null for null input")
        void shouldReturnNullForNull() {
            assertThat(WhereCondition.normalizeOperator(null)).isNull();
        }

        @Test
        @DisplayName("should trim whitespace")
        void shouldTrimWhitespace() {
            assertThat(WhereCondition.normalizeOperator("  eq  ")).isEqualTo("=");
            assertThat(WhereCondition.normalizeOperator(" >= ")).isEqualTo(">=");
        }
    }

    @Nested
    @DisplayName("validate - with aliases")
    class ValidateWithAliasesTests {

        @Test
        @DisplayName("should accept 'eq' alias without throwing")
        void shouldAcceptEqAlias() {
            WhereCondition condition = new WhereCondition("name", "eq", "Alice");
            condition.validate(); // should not throw
        }

        @Test
        @DisplayName("should accept 'gte' alias without throwing")
        void shouldAcceptGteAlias() {
            WhereCondition condition = new WhereCondition("age", "gte", 18);
            condition.validate();
        }

        @Test
        @DisplayName("should accept 'contains' alias without throwing")
        void shouldAcceptContainsAlias() {
            WhereCondition condition = new WhereCondition("name", "contains", "%Al%");
            condition.validate();
        }

        @Test
        @DisplayName("should accept 'is_null' alias without value")
        void shouldAcceptIsNullAlias() {
            WhereCondition condition = new WhereCondition("deleted_at", "is_null", null);
            condition.validate();
        }

        @Test
        @DisplayName("should reject truly invalid operators")
        void shouldRejectInvalidOperator() {
            WhereCondition condition = new WhereCondition("name", "BETWEEN", "a");
            assertThatThrownBy(condition::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid WHERE operator");
        }
    }

    @Nested
    @DisplayName("validate - actionable error messages")
    class ValidateErrorMessages {
        // Agents see these messages verbatim. Each must contain enough guidance that the
        // next call succeeds without another help round-trip.

        @Test
        @DisplayName("Invalid operator lists valid operators + aliases")
        void invalidOperatorListsValidOnes() {
            WhereCondition cond = new WhereCondition("status", "~~", "open");
            assertThatThrownBy(cond::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid WHERE operator")
                .hasMessageContaining("~~")
                .hasMessageContaining("Valid operators:")
                .hasMessageContaining("LIKE")
                .hasMessageContaining("IS NULL")
                .hasMessageContaining("Aliases also accepted");
        }

        @Test
        @DisplayName("Empty operator lists valid operators + example")
        void emptyOperatorListsValidOnes() {
            WhereCondition cond = new WhereCondition("status", "", "open");
            assertThatThrownBy(cond::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WHERE operator cannot be empty")
                .hasMessageContaining("Valid operators:")
                .hasMessageContaining("Example:");
        }

        @Test
        @DisplayName("Empty column includes example")
        void emptyColumnHasExample() {
            WhereCondition cond = new WhereCondition("", "=", "open");
            assertThatThrownBy(cond::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WHERE column cannot be empty")
                .hasMessageContaining("Example:")
                .hasMessageContaining("column:");
        }

        @Test
        @DisplayName("Missing value references the offending operator + example + IS NULL escape hatch")
        void missingValueShowsExampleAndNullHint() {
            WhereCondition cond = new WhereCondition("status", "=", null);
            assertThatThrownBy(cond::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WHERE value is required")
                .hasMessageContaining("'='")
                .hasMessageContaining("Example:")
                .hasMessageContaining("IS NULL");
        }
    }
}
