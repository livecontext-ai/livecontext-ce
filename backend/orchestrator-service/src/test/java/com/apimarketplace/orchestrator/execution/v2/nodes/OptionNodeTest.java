package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OptionNode.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OptionNode")
class OptionNodeTest {

    @Mock private WorkflowPlan mockPlan;
    @Mock private TemplateEngine mockTemplateEngine;
    @Mock private ExecutionNode targetNode1;
    @Mock private ExecutionNode targetNode2;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-0", 0, Map.of(), mockPlan);
    }

    @Nested
    @DisplayName("Constructor and properties")
    class ConstructorTests {

        @Test
        @DisplayName("should have OPTION node type")
        void shouldHaveOptionType() {
            OptionNode node = new OptionNode("core:option", List.of(), mockTemplateEngine);
            assertEquals(NodeType.OPTION, node.getType());
        }

        @Test
        @DisplayName("should handle null choices list")
        void shouldHandleNullChoices() {
            OptionNode node = new OptionNode("core:option", null, mockTemplateEngine);
            assertNotNull(node.getChoices());
            assertTrue(node.getChoices().isEmpty());
        }

        @Test
        @DisplayName("isOptionNode should return true")
        void shouldBeOptionNode() {
            OptionNode node = new OptionNode("core:option", List.of(), null);
            assertTrue(node.isOptionNode());
        }

        @Test
        @DisplayName("isBranchingNode should return true")
        void shouldBeBranchingNode() {
            OptionNode node = new OptionNode("core:option", List.of(), null);
            assertTrue(node.isBranchingNode());
        }
    }

    @Nested
    @DisplayName("getNextNodes")
    class GetNextNodes {

        @Test
        @DisplayName("should return nodes for selected choice")
        void shouldReturnSelectedChoiceNodes() {
            List<ExecutionNode> choiceNodes = new ArrayList<>(List.of(targetNode1));
            OptionNode.OptionBranch choice = new OptionNode.OptionBranch("c1", "Choice 1", "true", choiceNodes);
            OptionNode node = new OptionNode("core:option", List.of(choice), mockTemplateEngine);

            Map<String, Object> output = Map.of("selected_choice_index", 0);
            NodeExecutionResult result = NodeExecutionResult.success("core:option", output);

            List<ExecutionNode> next = node.getNextNodes(result);
            assertEquals(1, next.size());
            assertSame(targetNode1, next.get(0));
        }

        @Test
        @DisplayName("should return empty list when no choice matched")
        void shouldReturnEmptyWhenNoMatch() {
            OptionNode node = new OptionNode("core:option", List.of(), mockTemplateEngine);

            Map<String, Object> output = Map.of("selected_choice_index", -1);
            NodeExecutionResult result = NodeExecutionResult.success("core:option", output);

            assertTrue(node.getNextNodes(result).isEmpty());
        }
    }

    @Nested
    @DisplayName("getSkippedChildNodes")
    class GetSkippedChildNodes {

        @Test
        @DisplayName("should return nodes from non-selected choices")
        void shouldReturnSkippedChoiceNodes() {
            List<ExecutionNode> choice1Nodes = new ArrayList<>(List.of(targetNode1));
            List<ExecutionNode> choice2Nodes = new ArrayList<>(List.of(targetNode2));

            OptionNode.OptionBranch branch1 = new OptionNode.OptionBranch("c1", "C1", "true", choice1Nodes);
            OptionNode.OptionBranch branch2 = new OptionNode.OptionBranch("c2", "C2", "false", choice2Nodes);
            OptionNode node = new OptionNode("core:option", List.of(branch1, branch2), mockTemplateEngine);

            // Index 0 is selected, so choice 2 nodes are skipped
            Map<String, Object> output = Map.of("selected_choice_index", 0);
            NodeExecutionResult result = NodeExecutionResult.success("core:option", output);

            List<ExecutionNode> skipped = node.getSkippedChildNodes(result);
            assertEquals(1, skipped.size());
            assertSame(targetNode2, skipped.get(0));
        }

        @Test
        @DisplayName("should return all choice nodes when index is invalid")
        void shouldReturnAllNodesForInvalidIndex() {
            List<ExecutionNode> choice1Nodes = new ArrayList<>(List.of(targetNode1));
            OptionNode.OptionBranch branch1 = new OptionNode.OptionBranch("c1", "C1", "true", choice1Nodes);
            OptionNode node = new OptionNode("core:option", List.of(branch1), mockTemplateEngine);

            Map<String, Object> output = Map.of("selected_choice_index", -1);
            NodeExecutionResult result = NodeExecutionResult.success("core:option", output);

            List<ExecutionNode> skipped = node.getSkippedChildNodes(result);
            assertEquals(1, skipped.size());
        }
    }

    @Nested
    @DisplayName("getAllChoiceNodes")
    class GetAllChoiceNodes {

        @Test
        @DisplayName("should return all nodes from all choices")
        void shouldReturnAll() {
            List<ExecutionNode> c1Nodes = new ArrayList<>(List.of(targetNode1));
            List<ExecutionNode> c2Nodes = new ArrayList<>(List.of(targetNode2));
            OptionNode.OptionBranch b1 = new OptionNode.OptionBranch("c1", "C1", "true", c1Nodes);
            OptionNode.OptionBranch b2 = new OptionNode.OptionBranch("c2", "C2", "false", c2Nodes);
            OptionNode node = new OptionNode("core:option", List.of(b1, b2), mockTemplateEngine);

            assertEquals(2, node.getAllChoiceNodes().size());
        }
    }

    @Nested
    @DisplayName("addBranchTarget / addTargetToChoice")
    class AddBranchTarget {

        @Test
        @DisplayName("should add target to choice at index")
        void shouldAddTargetToChoice() {
            when(targetNode1.getNodeId()).thenReturn("target-1");
            List<ExecutionNode> c1Nodes = new ArrayList<>();
            OptionNode.OptionBranch b1 = new OptionNode.OptionBranch("c1", "C1", "true", c1Nodes);
            OptionNode node = new OptionNode("core:option", List.of(b1), mockTemplateEngine);

            node.addBranchTarget(0, targetNode1);

            assertEquals(1, b1.nodes().size());
        }

        @Test
        @DisplayName("should not throw for invalid index")
        void shouldNotThrowForInvalidIndex() {
            OptionNode node = new OptionNode("core:option", List.of(), mockTemplateEngine);
            assertDoesNotThrow(() -> node.addBranchTarget(99, targetNode1));
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build OptionNode with builder")
        void shouldBuild() {
            OptionNode node = OptionNode.builder()
                .nodeId("core:my_option")
                .templateEngine(mockTemplateEngine)
                .addChoice("c1", "Choice 1", "expr", List.of())
                .build();

            assertEquals("core:my_option", node.getNodeId());
            assertEquals(1, node.getChoices().size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute()")
    class ExecuteTests {

        @Test
        @DisplayName("execute with matching choice returns selected_choice and condition_result=true")
        void execute_matchingChoice_selectedChoiceAndConditionTrue() {
            List<ExecutionNode> choice1Nodes = new ArrayList<>(List.of(targetNode1));
            List<ExecutionNode> choice2Nodes = new ArrayList<>(List.of(targetNode2));

            OptionNode.OptionBranch branch1 = new OptionNode.OptionBranch("c1", "Yes", "{{value == 1}}", choice1Nodes);
            OptionNode.OptionBranch branch2 = new OptionNode.OptionBranch("c2", "No", "{{value == 2}}", choice2Nodes);
            OptionNode node = new OptionNode("core:option", List.of(branch1, branch2), mockTemplateEngine);

            // First choice matches
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(eq("{{value == 1}}"), anyMap()))
                    .thenReturn(new TemplateEngine.ConditionEvaluationResult("{{value == 1}}", "1 == 1", true, null));
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(eq("{{value == 2}}"), anyMap()))
                    .thenReturn(new TemplateEngine.ConditionEvaluationResult("{{value == 2}}", "1 == 2", false, null));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            Map<String, Object> output = result.output();
            assertEquals("c1", output.get("selected_choice"));
            assertEquals("Yes", output.get("selected_label"));
            assertEquals(0, output.get("selected_choice_index"));
            assertEquals("OPTION", output.get("node_type"));
            assertEquals("core:option", output.get("option_node"));
            assertEquals(2, output.get("choices_evaluated"));

            // selected_branches contains the selected label
            @SuppressWarnings("unchecked")
            List<String> selectedBranches = (List<String>) output.get("selected_branches");
            assertEquals(List.of("Yes"), selectedBranches);

            // skipped_branches contains non-matching labels (not IDs)
            @SuppressWarnings("unchecked")
            List<String> skippedBranches = (List<String>) output.get("skipped_branches");
            assertTrue(skippedBranches.contains("No"));
            assertFalse(skippedBranches.contains("Yes"));
        }

        @Test
        @DisplayName("execute with no matching choice returns condition_result=false and null selected_choice")
        void execute_noMatchingChoice_conditionFalse() {
            List<ExecutionNode> choice1Nodes = new ArrayList<>(List.of(targetNode1));
            OptionNode.OptionBranch branch1 = new OptionNode.OptionBranch("c1", "Check", "{{x > 10}}", choice1Nodes);
            OptionNode node = new OptionNode("core:option", List.of(branch1), mockTemplateEngine);

            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(eq("{{x > 10}}"), anyMap()))
                    .thenReturn(new TemplateEngine.ConditionEvaluationResult("{{x > 10}}", "5 > 10", false, null));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            Map<String, Object> output = result.output();
            assertNull(output.get("selected_choice"));
            assertNull(output.get("selected_label"));
            assertEquals(-1, output.get("selected_choice_index"));

            // No choice matched - selected_branches is empty
            @SuppressWarnings("unchecked")
            List<String> selectedBranches = (List<String>) output.get("selected_branches");
            assertNotNull(selectedBranches);
            assertTrue(selectedBranches.isEmpty());
        }

        @Test
        @DisplayName("execute with multiple matching choices selects first match only")
        void execute_multipleMatches_firstMatchWins() {
            List<ExecutionNode> choice1Nodes = new ArrayList<>(List.of(targetNode1));
            List<ExecutionNode> choice2Nodes = new ArrayList<>(List.of(targetNode2));

            OptionNode.OptionBranch branch1 = new OptionNode.OptionBranch("c1", "First", "{{x > 0}}", choice1Nodes);
            OptionNode.OptionBranch branch2 = new OptionNode.OptionBranch("c2", "Second", "{{x > 0}}", choice2Nodes);
            OptionNode node = new OptionNode("core:option", List.of(branch1, branch2), mockTemplateEngine);

            // Both choices match
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(eq("{{x > 0}}"), anyMap()))
                    .thenReturn(new TemplateEngine.ConditionEvaluationResult("{{x > 0}}", "5 > 0", true, null));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            Map<String, Object> output = result.output();
            assertEquals("c1", output.get("selected_choice"));
            assertEquals("First", output.get("selected_label"));
            assertEquals(0, output.get("selected_choice_index"));

            // Second choice should be skipped even though it matches
            @SuppressWarnings("unchecked")
            List<String> skippedBranches = (List<String>) output.get("skipped_branches");
            assertTrue(skippedBranches.contains("Second"));
        }

        @Test
        @DisplayName("execute with expression evaluation error captures error in evaluations")
        void execute_expressionError_capturedInEvaluations() {
            List<ExecutionNode> choice1Nodes = new ArrayList<>(List.of(targetNode1));
            OptionNode.OptionBranch branch1 = new OptionNode.OptionBranch("c1", "Bad", "{{bad.expr}}", choice1Nodes);
            OptionNode node = new OptionNode("core:option", List.of(branch1), mockTemplateEngine);

            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(eq("{{bad.expr}}"), anyMap()))
                    .thenThrow(new RuntimeException("SpEL parse error"));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            Map<String, Object> output = result.output();

            // When expression throws, the OptionBranch.evaluateExpressionWithDetails catches it
            // and returns result=false with error message
            assertNull(output.get("selected_choice"));

            // Evaluations should contain the error detail
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> evaluations = (List<Map<String, Object>>) output.get("evaluations");
            assertNotNull(evaluations);
            assertEquals(1, evaluations.size());
            Map<String, Object> evalDetail = evaluations.get(0);
            assertEquals(false, evalDetail.get("result"));
            assertNotNull(evalDetail.get("error"));
        }

        @Test
        @DisplayName("execute with null/empty expression choice evaluates false with 'No expression defined' error and does not call template engine")
        void execute_emptyExpressionChoice_falseWithErrorAndNoEngineCall() {
            // First choice has a null expression (the empty/blank-expression branch);
            // second choice is a normal matching expression.
            List<ExecutionNode> choice1Nodes = new ArrayList<>(List.of(targetNode1));
            List<ExecutionNode> choice2Nodes = new ArrayList<>(List.of(targetNode2));

            OptionNode.OptionBranch branch1 = new OptionNode.OptionBranch("c1", "Empty", null, choice1Nodes);
            OptionNode.OptionBranch branch2 = new OptionNode.OptionBranch("c2", "Valid", "{{x == 1}}", choice2Nodes);
            OptionNode node = new OptionNode("core:option", List.of(branch1, branch2), mockTemplateEngine);

            // Only the second (valid) choice should reach the template engine.
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(eq("{{x == 1}}"), anyMap()))
                    .thenReturn(new TemplateEngine.ConditionEvaluationResult("{{x == 1}}", "1 == 1", true, null));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            Map<String, Object> output = result.output();

            // The empty-expression choice must NOT be selected; the second valid one wins.
            assertEquals("c2", output.get("selected_choice"));
            assertEquals("Valid", output.get("selected_label"));
            assertEquals(1, output.get("selected_choice_index"));

            // The empty-expression choice is recorded as evaluated to false with the specific error.
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> evaluations = (List<Map<String, Object>>) output.get("evaluations");
            assertNotNull(evaluations);
            assertEquals(2, evaluations.size());
            Map<String, Object> emptyEval = evaluations.get(0);
            assertEquals("c1", emptyEval.get("choice_id"));
            assertEquals(false, emptyEval.get("result"));
            assertEquals("No expression defined", emptyEval.get("error"));
            assertEquals("(no expression)", emptyEval.get("resolved_expression"));
            // A null expression is normalized to an empty string in the persisted evaluation detail.
            assertEquals("", emptyEval.get("expression"));

            // The template engine must never be invoked for the null-expression choice.
            verify(mockTemplateEngine, never())
                    .evaluateConditionWithDetailsWithMap(eq("(no expression)"), anyMap());
            verify(mockTemplateEngine, never())
                    .evaluateConditionWithDetailsWithMap(isNull(), anyMap());
        }

        @Test
        @DisplayName("execute with empty choices list selects nothing: null selected_choice, index -1, empty selected/skipped branches and evaluations")
        void execute_emptyChoicesList_selectsNothing() {
            OptionNode node = new OptionNode("core:option", List.of(), mockTemplateEngine);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            Map<String, Object> output = result.output();

            // No choices means nothing is selected.
            assertNull(output.get("selected_choice"));
            assertNull(output.get("selected_label"));
            assertEquals(-1, output.get("selected_choice_index"));
            assertEquals(0, output.get("choices_evaluated"));

            @SuppressWarnings("unchecked")
            List<String> selectedBranches = (List<String>) output.get("selected_branches");
            assertNotNull(selectedBranches);
            assertTrue(selectedBranches.isEmpty());

            @SuppressWarnings("unchecked")
            List<String> skippedBranches = (List<String>) output.get("skipped_branches");
            assertNotNull(skippedBranches);
            assertTrue(skippedBranches.isEmpty());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> evaluations = (List<Map<String, Object>>) output.get("evaluations");
            assertNotNull(evaluations);
            assertTrue(evaluations.isEmpty());

            // With no choices evaluated, the template engine is never touched.
            verifyNoInteractions(mockTemplateEngine);
        }

        @Test
        @DisplayName("executeOutputMatchesPersistedShape: execute() keyset and customTransform() keyset are aligned - no legacy fields, no dropped fields")
        void executeOutputMatchesPersistedShape() {
            // Two choices; first matches
            List<ExecutionNode> choice1Nodes = new ArrayList<>(List.of(targetNode1));
            List<ExecutionNode> choice2Nodes = new ArrayList<>(List.of(targetNode2));

            OptionNode.OptionBranch branch1 = new OptionNode.OptionBranch("c1", "Alpha", "{{x == 1}}", choice1Nodes);
            OptionNode.OptionBranch branch2 = new OptionNode.OptionBranch("c2", "Beta",  "{{x == 2}}", choice2Nodes);
            OptionNode node = new OptionNode("core:option", List.of(branch1, branch2), mockTemplateEngine);

            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(eq("{{x == 1}}"), anyMap()))
                    .thenReturn(new TemplateEngine.ConditionEvaluationResult("{{x == 1}}", "1==1", true, null));
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(eq("{{x == 2}}"), anyMap()))
                    .thenReturn(new TemplateEngine.ConditionEvaluationResult("{{x == 2}}", "1==2", false, null));

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isSuccess());
            Map<String, Object> executeOutput = result.output();

            // customTransform must produce an equivalent keyset (identity transform for Option)
            OptionNodeSpec spec = new OptionNodeSpec();
            Map<String, Object> persistedOutput = spec.customTransform(executeOutput);

            // The persisted shape must contain the canonical B3 keys
            assertTrue(persistedOutput.containsKey("selected_branches"),     "selected_branches must be in persisted shape");
            assertTrue(persistedOutput.containsKey("skipped_branches"),       "skipped_branches must be in persisted shape");
            assertTrue(persistedOutput.containsKey("selected_choice"),        "selected_choice must be in persisted shape");
            assertTrue(persistedOutput.containsKey("selected_choice_index"),  "selected_choice_index must be in persisted shape");
            assertTrue(persistedOutput.containsKey("evaluations"),            "evaluations must be in persisted shape");

            // Legacy keys dropped in B3 must NOT appear in either the execute output or the persisted output
            assertFalse(executeOutput.containsKey("skipped_choices"),       "skipped_choices must not be in execute output (shape drift)");
            assertFalse(executeOutput.containsKey("skipped_choice_labels"), "skipped_choice_labels must not be in execute output (shape drift)");
            assertFalse(persistedOutput.containsKey("skipped_choices"),     "skipped_choices must not be in persisted output (shape drift)");
            assertFalse(persistedOutput.containsKey("skipped_choice_labels"), "skipped_choice_labels must not be in persisted output (shape drift)");

            // customTransform must strip engine-envelope and legacy Option keys from persisted output
            assertFalse(persistedOutput.containsKey("node_type"),       "persisted output must NOT contain engine key node_type");
            assertFalse(persistedOutput.containsKey("item_index"),      "persisted output must NOT contain engine key item_index");
            assertFalse(persistedOutput.containsKey("itemIndex"),       "persisted output must NOT contain engine key itemIndex");
            assertFalse(persistedOutput.containsKey("item_id"),         "persisted output must NOT contain engine key item_id");
            assertFalse(persistedOutput.containsKey("resolved_params"), "persisted output must NOT contain engine key resolved_params");
            assertFalse(persistedOutput.containsKey("option_node"),     "persisted output must NOT contain legacy key option_node");
            assertFalse(persistedOutput.containsKey("choices_evaluated"), "persisted output must NOT contain legacy key choices_evaluated");

            // Verify correct values
            @SuppressWarnings("unchecked")
            List<String> selectedBranches = (List<String>) persistedOutput.get("selected_branches");
            assertEquals(List.of("Alpha"), selectedBranches);
            @SuppressWarnings("unchecked")
            List<String> skippedBranches = (List<String>) persistedOutput.get("skipped_branches");
            assertEquals(List.of("Beta"), skippedBranches);

            // evaluations must not contain the internal 'index' field
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> evaluations = (List<Map<String, Object>>) persistedOutput.get("evaluations");
            assertNotNull(evaluations);
            assertEquals(2, evaluations.size());
            for (Map<String, Object> eval : evaluations) {
                assertFalse(eval.containsKey("index"), "evaluations must not expose internal 'index' field");
            }
        }
    }
}
