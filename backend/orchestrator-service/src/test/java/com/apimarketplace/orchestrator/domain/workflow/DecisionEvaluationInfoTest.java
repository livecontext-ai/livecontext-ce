package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DecisionEvaluationInfo")
class DecisionEvaluationInfoTest {

    @Nested
    @DisplayName("Record construction")
    class ConstructionTests {
        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() {
            List<DecisionEvaluationInfo.ConditionEvaluation> conditions = List.of(
                new DecisionEvaluationInfo.ConditionEvaluation("if", "{{x > 5}}", "10 > 5", true, true, "step_a", null)
            );
            Map<String, Object> context = Map.of("x", 10);

            DecisionEvaluationInfo info = new DecisionEvaluationInfo(
                "core:check", "Check Value", "mcp:prev", "if", conditions, context
            );

            assertEquals("core:check", info.decisionNodeId());
            assertEquals("Check Value", info.decisionNodeLabel());
            assertEquals("mcp:prev", info.sourceStepId());
            assertEquals("if", info.selectedBranch());
            assertEquals(1, info.conditions().size());
            assertEquals(10, info.contextSnapshot().get("x"));
        }

        @Test
        @DisplayName("Should default null conditions to empty list")
        void shouldDefaultNullConditionsToEmptyList() {
            DecisionEvaluationInfo info = new DecisionEvaluationInfo(
                "core:check", "Check", "mcp:prev", "if", null, Map.of()
            );
            assertNotNull(info.conditions());
            assertTrue(info.conditions().isEmpty());
        }

        @Test
        @DisplayName("Should default null contextSnapshot to empty map")
        void shouldDefaultNullContextSnapshotToEmptyMap() {
            DecisionEvaluationInfo info = new DecisionEvaluationInfo(
                "core:check", "Check", "mcp:prev", "if", List.of(), null
            );
            assertNotNull(info.contextSnapshot());
            assertTrue(info.contextSnapshot().isEmpty());
        }
    }

    @Nested
    @DisplayName("ConditionEvaluation")
    class ConditionEvaluationTests {
        @Test
        @DisplayName("Should create IF condition")
        void shouldCreateIfCondition() {
            var condition = new DecisionEvaluationInfo.ConditionEvaluation(
                "if", "{{value > 10}}", "15 > 10", true, true, "step_a", null
            );
            assertEquals("if", condition.type());
            assertEquals("{{value > 10}}", condition.originalExpression());
            assertEquals("15 > 10", condition.resolvedExpression());
            assertTrue(condition.result());
            assertTrue(condition.selected());
            assertEquals("step_a", condition.targetBranch());
            assertNull(condition.errorMessage());
        }

        @Test
        @DisplayName("Should create ELSEIF condition")
        void shouldCreateElseIfCondition() {
            var condition = new DecisionEvaluationInfo.ConditionEvaluation(
                "elseif", "{{value > 5}}", "3 > 5", false, false, "step_b", null
            );
            assertEquals("elseif", condition.type());
            assertFalse(condition.result());
            assertFalse(condition.selected());
        }

        @Test
        @DisplayName("Should create ELSE condition")
        void shouldCreateElseCondition() {
            var condition = new DecisionEvaluationInfo.ConditionEvaluation(
                "else", null, null, null, true, "step_c", null
            );
            assertEquals("else", condition.type());
            assertNull(condition.originalExpression());
            assertTrue(condition.selected());
        }

        @Test
        @DisplayName("Should include error message on failure")
        void shouldIncludeErrorMessageOnFailure() {
            var condition = new DecisionEvaluationInfo.ConditionEvaluation(
                "if", "{{invalid}}", null, null, false, "step_a", "Variable 'invalid' not found"
            );
            assertEquals("Variable 'invalid' not found", condition.errorMessage());
            assertFalse(condition.selected());
        }
    }

    @Nested
    @DisplayName("Multiple conditions")
    class MultipleConditionsTests {
        @Test
        @DisplayName("Should track multiple condition evaluations")
        void shouldTrackMultipleConditionEvaluations() {
            List<DecisionEvaluationInfo.ConditionEvaluation> conditions = new ArrayList<>();
            conditions.add(new DecisionEvaluationInfo.ConditionEvaluation(
                "if", "{{x > 10}}", "5 > 10", false, false, "step_a", null));
            conditions.add(new DecisionEvaluationInfo.ConditionEvaluation(
                "elseif", "{{x > 3}}", "5 > 3", true, true, "step_b", null));
            conditions.add(new DecisionEvaluationInfo.ConditionEvaluation(
                "else", null, null, null, false, "step_c", null));

            DecisionEvaluationInfo info = new DecisionEvaluationInfo(
                "core:check", "Multi Check", "mcp:prev", "elseif_0", conditions, Map.of("x", 5)
            );

            assertEquals(3, info.conditions().size());
            assertEquals("elseif_0", info.selectedBranch());
            assertFalse(info.conditions().get(0).selected());
            assertTrue(info.conditions().get(1).selected());
            assertFalse(info.conditions().get(2).selected());
        }
    }
}
