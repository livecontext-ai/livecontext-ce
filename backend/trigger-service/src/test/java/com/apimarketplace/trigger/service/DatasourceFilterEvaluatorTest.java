package com.apimarketplace.trigger.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DatasourceFilterEvaluator")
class DatasourceFilterEvaluatorTest {

    private final DatasourceFilterEvaluator evaluator = new DatasourceFilterEvaluator();

    // ==================== null / empty filter = always match ====================

    @Nested
    @DisplayName("when filter is null or empty")
    class NoFilter {

        @Test
        @DisplayName("null filter matches any row (trigger always fires)")
        void nullFilterMatches() {
            assertThat(evaluator.matches(null, Map.of("status", "paid"))).isTrue();
        }

        @Test
        @DisplayName("empty filter map matches any row")
        void emptyFilterMatches() {
            assertThat(evaluator.matches(Map.of(), Map.of("status", "paid"))).isTrue();
        }

        @Test
        @DisplayName("null row with non-null filter does not match")
        void nullRowDoesNotMatch() {
            Map<String, Object> filter = Map.of("column", "status", "operator", "eq", "value", "paid");
            assertThat(evaluator.matches(filter, null)).isFalse();
        }
    }

    // ==================== eq / neq ====================

    @Nested
    @DisplayName("eq / neq operators")
    class Equality {

        @Test
        @DisplayName("eq matches when row value equals expected")
        void eqMatches() {
            Map<String, Object> filter = Map.of("column", "status", "operator", "eq", "value", "paid");
            assertThat(evaluator.matches(filter, Map.of("status", "paid"))).isTrue();
        }

        @Test
        @DisplayName("eq coerces numeric types (Integer 42 matches String \"42\")")
        void eqCoercesNumericTypes() {
            Map<String, Object> filter = Map.of("column", "n", "operator", "eq", "value", 42);
            assertThat(evaluator.matches(filter, Map.of("n", "42"))).isTrue();
        }

        @Test
        @DisplayName("eq matches null on both sides (is_null-like behavior)")
        void eqNullMatchesNull() {
            Map<String, Object> filter = new HashMap<>();
            filter.put("column", "status");
            filter.put("operator", "eq");
            filter.put("value", null);
            Map<String, Object> row = new HashMap<>();
            row.put("status", null);
            assertThat(evaluator.matches(filter, row)).isTrue();
        }

        @Test
        @DisplayName("neq is the logical inverse of eq")
        void neqInverse() {
            Map<String, Object> filter = Map.of("column", "status", "operator", "neq", "value", "paid");
            assertThat(evaluator.matches(filter, Map.of("status", "pending"))).isTrue();
            assertThat(evaluator.matches(filter, Map.of("status", "paid"))).isFalse();
        }
    }

    // ==================== Numeric comparisons ====================

    @Nested
    @DisplayName("numeric operators (gt/gte/lt/lte)")
    class Numeric {

        @Test
        @DisplayName("gt matches strictly greater numbers")
        void gtStrict() {
            Map<String, Object> filter = Map.of("column", "amount", "operator", "gt", "value", 100);
            assertThat(evaluator.matches(filter, Map.of("amount", 150))).isTrue();
            assertThat(evaluator.matches(filter, Map.of("amount", 100))).isFalse();
            assertThat(evaluator.matches(filter, Map.of("amount", 50))).isFalse();
        }

        @Test
        @DisplayName("gte includes equality")
        void gteInclusive() {
            Map<String, Object> filter = Map.of("column", "amount", "operator", "gte", "value", 100);
            assertThat(evaluator.matches(filter, Map.of("amount", 100))).isTrue();
            assertThat(evaluator.matches(filter, Map.of("amount", 99))).isFalse();
        }

        @Test
        @DisplayName("non-numeric values never match numeric operators (no false positives)")
        void nonNumericValuesFail() {
            Map<String, Object> filter = Map.of("column", "amount", "operator", "lt", "value", 100);
            assertThat(evaluator.matches(filter, Map.of("amount", "abc"))).isFalse();
            assertThat(evaluator.matches(filter, Map.of("amount", "xyz"))).isFalse();
        }

        @Test
        @DisplayName("null row value fails numeric comparison (never fires)")
        void nullValueFailsNumeric() {
            Map<String, Object> filter = Map.of("column", "amount", "operator", "gt", "value", 100);
            Map<String, Object> row = new HashMap<>();
            row.put("amount", null);
            assertThat(evaluator.matches(filter, row)).isFalse();
        }
    }

    // ==================== String operators ====================

    @Nested
    @DisplayName("string operators (contains / starts_with / ends_with)")
    class Strings {

        @Test
        @DisplayName("contains finds substring")
        void contains() {
            Map<String, Object> filter = Map.of("column", "email", "operator", "contains", "value", "@example.com");
            assertThat(evaluator.matches(filter, Map.of("email", "foo@example.com"))).isTrue();
            assertThat(evaluator.matches(filter, Map.of("email", "foo@other.com"))).isFalse();
        }

        @Test
        @DisplayName("starts_with is anchored to the beginning")
        void startsWithAnchored() {
            Map<String, Object> filter = Map.of("column", "name", "operator", "starts_with", "value", "Mr.");
            assertThat(evaluator.matches(filter, Map.of("name", "Mr. Smith"))).isTrue();
            assertThat(evaluator.matches(filter, Map.of("name", "Dr. Mr. Jones"))).isFalse();
        }

        @Test
        @DisplayName("null row value fails string operators (no NPE, no match)")
        void nullValueFailsString() {
            Map<String, Object> filter = Map.of("column", "email", "operator", "contains", "value", "@");
            Map<String, Object> row = new HashMap<>();
            row.put("email", null);
            assertThat(evaluator.matches(filter, row)).isFalse();
        }
    }

    // ==================== Null checks ====================

    @Nested
    @DisplayName("is_null / is_not_null")
    class NullChecks {

        @Test
        @DisplayName("is_null matches when row column is null OR missing")
        void isNullMatches() {
            Map<String, Object> filter = Map.of("column", "deleted_at", "operator", "is_null");
            Map<String, Object> row = new HashMap<>();
            row.put("deleted_at", null);
            assertThat(evaluator.matches(filter, row)).isTrue();
            assertThat(evaluator.matches(filter, Map.of("other", "x"))).isTrue(); // missing column = null
        }

        @Test
        @DisplayName("is_not_null is the logical inverse of is_null")
        void isNotNullInverse() {
            Map<String, Object> filter = Map.of("column", "deleted_at", "operator", "is_not_null");
            Map<String, Object> row = new HashMap<>();
            row.put("deleted_at", null);
            assertThat(evaluator.matches(filter, row)).isFalse();
            assertThat(evaluator.matches(filter, Map.of("deleted_at", "2026-01-01"))).isTrue();
        }
    }

    // ==================== Unknown operators (fail-safe) ====================

    @Test
    @DisplayName("unknown operator returns false (fail-safe: does not fire)")
    void unknownOperatorDoesNotFire() {
        Map<String, Object> filter = Map.of("column", "status", "operator", "regex", "value", "^paid$");
        assertThat(evaluator.matches(filter, Map.of("status", "paid"))).isFalse();
    }

    // ==================== Symbolic operator aliases ====================

    @Nested
    @DisplayName("symbolic operator vocabulary (=, !=, >, >=, <, <=) aligned with frontend + TriggerCreator")
    class SymbolicOperators {

        @Test
        @DisplayName("'=' behaves like 'eq'")
        void eqSymbol() {
            Map<String, Object> filter = Map.of("column", "status", "operator", "=", "value", "paid");
            assertThat(evaluator.matches(filter, Map.of("status", "paid"))).isTrue();
            assertThat(evaluator.matches(filter, Map.of("status", "pending"))).isFalse();
        }

        @Test
        @DisplayName("'!=' behaves like 'neq'")
        void neqSymbol() {
            Map<String, Object> filter = Map.of("column", "status", "operator", "!=", "value", "paid");
            assertThat(evaluator.matches(filter, Map.of("status", "pending"))).isTrue();
            assertThat(evaluator.matches(filter, Map.of("status", "paid"))).isFalse();
        }

        @Test
        @DisplayName("'>' behaves like 'gt'")
        void gtSymbol() {
            Map<String, Object> filter = Map.of("column", "amount", "operator", ">", "value", 100);
            assertThat(evaluator.matches(filter, Map.of("amount", 150))).isTrue();
            assertThat(evaluator.matches(filter, Map.of("amount", 100))).isFalse();
        }

        @Test
        @DisplayName("'>=' behaves like 'gte'")
        void gteSymbol() {
            Map<String, Object> filter = Map.of("column", "amount", "operator", ">=", "value", 100);
            assertThat(evaluator.matches(filter, Map.of("amount", 100))).isTrue();
            assertThat(evaluator.matches(filter, Map.of("amount", 99))).isFalse();
        }

        @Test
        @DisplayName("'<' behaves like 'lt'")
        void ltSymbol() {
            Map<String, Object> filter = Map.of("column", "amount", "operator", "<", "value", 100);
            assertThat(evaluator.matches(filter, Map.of("amount", 50))).isTrue();
            assertThat(evaluator.matches(filter, Map.of("amount", 100))).isFalse();
        }

        @Test
        @DisplayName("'<=' behaves like 'lte'")
        void lteSymbol() {
            Map<String, Object> filter = Map.of("column", "amount", "operator", "<=", "value", 100);
            assertThat(evaluator.matches(filter, Map.of("amount", 100))).isTrue();
            assertThat(evaluator.matches(filter, Map.of("amount", 101))).isFalse();
        }

        @Test
        @DisplayName("operator is trim+lowercased before matching")
        void operatorIsNormalized() {
            Map<String, Object> filter = Map.of("column", "status", "operator", "  =  ", "value", "paid");
            assertThat(evaluator.matches(filter, Map.of("status", "paid"))).isTrue();
        }

        @Test
        @DisplayName("'==' is accepted as an alias for '=' (frontend surfaces '==' in its operator dropdown because '=' reads as an assignment)")
        void doubleEqualsIsEquivalentToSingle() {
            Map<String, Object> filter = Map.of("column", "status", "operator", "==", "value", "paid");
            assertThat(evaluator.matches(filter, Map.of("status", "paid"))).isTrue();
            assertThat(evaluator.matches(filter, Map.of("status", "pending"))).isFalse();
        }
    }

    // ==================== IN / NOT_IN ====================

    @Nested
    @DisplayName("in / not_in operators")
    class InMembership {

        @Test
        @DisplayName("in matches when row value is in the list")
        void inListMatches() {
            Map<String, Object> filter = Map.of(
                    "column", "status",
                    "operator", "in",
                    "value", List.of("paid", "refunded", "cancelled"));
            assertThat(evaluator.matches(filter, Map.of("status", "refunded"))).isTrue();
            assertThat(evaluator.matches(filter, Map.of("status", "pending"))).isFalse();
        }

        @Test
        @DisplayName("in accepts comma-separated string as convenience")
        void inCommaSeparatedString() {
            Map<String, Object> filter = Map.of(
                    "column", "status",
                    "operator", "in",
                    "value", "paid, refunded, cancelled");
            assertThat(evaluator.matches(filter, Map.of("status", "refunded"))).isTrue();
            assertThat(evaluator.matches(filter, Map.of("status", "pending"))).isFalse();
        }

        @Test
        @DisplayName("in coerces types - Integer 1 matches \"1\" in list")
        void inCoercesTypes() {
            Map<String, Object> filter = Map.of(
                    "column", "priority",
                    "operator", "in",
                    "value", List.of("1", "2", "3"));
            assertThat(evaluator.matches(filter, Map.of("priority", 2))).isTrue();
        }

        @Test
        @DisplayName("not_in is the logical inverse of in")
        void notInInverse() {
            Map<String, Object> filter = Map.of(
                    "column", "status",
                    "operator", "not_in",
                    "value", List.of("paid", "refunded"));
            assertThat(evaluator.matches(filter, Map.of("status", "pending"))).isTrue();
            assertThat(evaluator.matches(filter, Map.of("status", "paid"))).isFalse();
        }

        @Test
        @DisplayName("in with null row value never matches")
        void inNullRowValue() {
            Map<String, Object> filter = Map.of(
                    "column", "status",
                    "operator", "in",
                    "value", List.of("paid"));
            Map<String, Object> row = new HashMap<>();
            row.put("status", null);
            assertThat(evaluator.matches(filter, row)).isFalse();
        }
    }
}
