package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ExecutionMode")
class ExecutionModeTest {

    @Nested
    @DisplayName("getValue()")
    class GetValueTests {

        @Test
        @DisplayName("AUTOMATIC should have value 'automatic'")
        void automaticShouldHaveCorrectValue() {
            assertEquals("automatic", ExecutionMode.AUTOMATIC.getValue());
        }

        @Test
        @DisplayName("STEP_BY_STEP should have value 'step_by_step'")
        void stepByStepShouldHaveCorrectValue() {
            assertEquals("step_by_step", ExecutionMode.STEP_BY_STEP.getValue());
        }
    }

    @Nested
    @DisplayName("isStepByStep()")
    class IsStepByStepTests {

        @Test
        @DisplayName("STEP_BY_STEP should return true")
        void stepByStepShouldReturnTrue() {
            assertTrue(ExecutionMode.STEP_BY_STEP.isStepByStep());
        }

        @Test
        @DisplayName("AUTOMATIC should return false")
        void automaticShouldReturnFalse() {
            assertFalse(ExecutionMode.AUTOMATIC.isStepByStep());
        }
    }

    @Nested
    @DisplayName("isAutomatic()")
    class IsAutomaticTests {

        @Test
        @DisplayName("AUTOMATIC should return true")
        void automaticShouldReturnTrue() {
            assertTrue(ExecutionMode.AUTOMATIC.isAutomatic());
        }

        @Test
        @DisplayName("STEP_BY_STEP should return false")
        void stepByStepShouldReturnFalse() {
            assertFalse(ExecutionMode.STEP_BY_STEP.isAutomatic());
        }
    }

    @Nested
    @DisplayName("fromString()")
    class FromStringTests {

        @Test
        @DisplayName("Should parse 'automatic' value")
        void shouldParseAutomatic() {
            assertEquals(ExecutionMode.AUTOMATIC, ExecutionMode.fromString("automatic"));
        }

        @Test
        @DisplayName("Should parse 'step_by_step' value")
        void shouldParseStepByStep() {
            assertEquals(ExecutionMode.STEP_BY_STEP, ExecutionMode.fromString("step_by_step"));
        }

        @Test
        @DisplayName("Should parse enum name case-insensitively")
        void shouldParseEnumNameCaseInsensitive() {
            assertEquals(ExecutionMode.AUTOMATIC, ExecutionMode.fromString("AUTOMATIC"));
            assertEquals(ExecutionMode.STEP_BY_STEP, ExecutionMode.fromString("step_by_step"));
        }

        @Test
        @DisplayName("Should return AUTOMATIC for null")
        void shouldReturnAutomaticForNull() {
            assertEquals(ExecutionMode.AUTOMATIC, ExecutionMode.fromString(null));
        }

        @Test
        @DisplayName("Should return AUTOMATIC for unknown value")
        void shouldReturnAutomaticForUnknown() {
            assertEquals(ExecutionMode.AUTOMATIC, ExecutionMode.fromString("unknown"));
        }
    }
}
