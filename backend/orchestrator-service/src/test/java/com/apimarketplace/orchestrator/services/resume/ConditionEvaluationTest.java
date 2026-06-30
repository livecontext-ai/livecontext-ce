package com.apimarketplace.orchestrator.services.resume;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConditionEvaluation")
class ConditionEvaluationTest {

    @Nested
    @DisplayName("Record construction")
    class ConstructionTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() {
            ConditionEvaluation eval = new ConditionEvaluation(
                "if", "{{status}} == 'active'", "'active' == 'active'", true, "mcp:process"
            );

            assertEquals("if", eval.type());
            assertEquals("{{status}} == 'active'", eval.expression());
            assertEquals("'active' == 'active'", eval.resolvedExpression());
            assertTrue(eval.result());
            assertEquals("mcp:process", eval.destination());
        }

        @Test
        @DisplayName("Should create with false result")
        void shouldCreateWithFalseResult() {
            ConditionEvaluation eval = new ConditionEvaluation(
                "elseif", "{{count}} > 10", "5 > 10", false, "mcp:skip"
            );

            assertFalse(eval.result());
        }

        @Test
        @DisplayName("Should allow null expression for else type")
        void shouldAllowNullExpressionForElse() {
            ConditionEvaluation eval = new ConditionEvaluation(
                "else", null, null, true, "mcp:fallback"
            );

            assertNull(eval.expression());
            assertNull(eval.resolvedExpression());
            assertEquals("else", eval.type());
            assertTrue(eval.result());
        }

        @Test
        @DisplayName("Should allow null destination")
        void shouldAllowNullDestination() {
            ConditionEvaluation eval = new ConditionEvaluation(
                "if", "{{x}} > 0", "5 > 0", false, null
            );

            assertNull(eval.destination());
        }
    }

    @Nested
    @DisplayName("Record equality")
    class EqualityTests {

        @Test
        @DisplayName("Should be equal for same values")
        void shouldBeEqualForSameValues() {
            ConditionEvaluation a = new ConditionEvaluation(
                "if", "expr", "resolved", true, "dest"
            );
            ConditionEvaluation b = new ConditionEvaluation(
                "if", "expr", "resolved", true, "dest"
            );

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("Should not be equal for different results")
        void shouldNotBeEqualForDifferentResults() {
            ConditionEvaluation a = new ConditionEvaluation(
                "if", "expr", "resolved", true, "dest"
            );
            ConditionEvaluation b = new ConditionEvaluation(
                "if", "expr", "resolved", false, "dest"
            );

            assertNotEquals(a, b);
        }
    }

    @Nested
    @DisplayName("Condition types")
    class ConditionTypeTests {

        @ParameterizedTest(name = "Type ''{0}'' should be valid")
        @CsvSource({"if", "elseif", "else"})
        @DisplayName("Should accept standard condition types")
        void shouldAcceptStandardTypes(String type) {
            ConditionEvaluation eval = new ConditionEvaluation(
                type, "expr", "resolved", true, "dest"
            );
            assertEquals(type, eval.type());
        }
    }
}
