package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.TemplateEngine.ConditionEvaluationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DecisionNode.
 * DecisionNode provides conditional branching in workflows.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DecisionNode")
class DecisionNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private TemplateEngine mockTemplateEngine;

    @Mock
    private V2TemplateAdapter mockTemplateAdapter;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("value", 15);
        triggerData.put("status", "active");
        triggerData.put("user_id", 42);

        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            triggerData,
            mockPlan
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create DecisionNode with branches")
        void shouldCreateDecisionNodeWithBranches() {
            List<DecisionNode.ConditionalBranch> branches = createTwoBranches();
            DecisionNode node = new DecisionNode("core:check", branches, mockTemplateEngine);

            assertEquals("core:check", node.getNodeId());
            assertEquals(NodeType.DECISION, node.getType());
        }

        @Test
        @DisplayName("Should handle null branches")
        void shouldHandleNullBranches() {
            DecisionNode node = new DecisionNode("core:check", null, mockTemplateEngine);

            assertNotNull(node);
        }

        @Test
        @DisplayName("Should handle empty branches")
        void shouldHandleEmptyBranches() {
            DecisionNode node = new DecisionNode("core:check", List.of(), mockTemplateEngine);

            assertNotNull(node);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Builder tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should build DecisionNode using builder pattern")
        void shouldBuildDecisionNodeUsingBuilderPattern() {
            DecisionNode node = DecisionNode.builder()
                .nodeId("core:check")
                .templateEngine(mockTemplateEngine)
                .ifBranch("{{value}} > 10", List.of())
                .elseBranch(List.of())
                .build();

            assertEquals("core:check", node.getNodeId());
            assertEquals(NodeType.DECISION, node.getType());
        }

        @Test
        @DisplayName("Should add elsif branches")
        void shouldAddElsifBranches() {
            DecisionNode node = DecisionNode.builder()
                .nodeId("core:check")
                .templateEngine(mockTemplateEngine)
                .ifBranch("{{value}} > 20", List.of())
                .elsifBranch("{{value}} > 10", List.of())
                .elseBranch(List.of())
                .build();

            assertNotNull(node);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Branch selection tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Branch Selection")
    class ExecuteBranchSelectionTests {

        @Test
        @DisplayName("Should select 'if' branch when condition is true")
        void shouldSelectIfBranchWhenConditionIsTrue() {
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(eq("{{value}} > 10"), any()))
                .thenReturn(new ConditionEvaluationResult("condition", "resolved = true", true, null));

            DecisionNode node = DecisionNode.builder()
                .nodeId("core:check")
                .templateEngine(mockTemplateEngine)
                .ifBranch("{{value}} > 10", List.of())
                .elseBranch(List.of())
                .build();

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("if", result.output().get("selected_branch"));
        }

        @Test
        @DisplayName("Should select 'else' branch when if condition is false")
        void shouldSelectElseBranchWhenIfConditionIsFalse() {
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(eq("{{value}} > 100"), any()))
                .thenReturn(new ConditionEvaluationResult("{{value}} > 100", "15 > 100 = false", false, null));

            DecisionNode node = DecisionNode.builder()
                .nodeId("core:check")
                .templateEngine(mockTemplateEngine)
                .ifBranch("{{value}} > 100", List.of())
                .elseBranch(List.of())
                .build();

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("else", result.output().get("selected_branch"));
        }

        @Test
        @DisplayName("Should select first matching elsif branch")
        void shouldSelectFirstMatchingElsifBranch() {
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(eq("{{value}} > 20"), any()))
                .thenReturn(new ConditionEvaluationResult("{{value}} > 20", "15 > 20 = false", false, null));
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(eq("{{value}} > 10"), any()))
                .thenReturn(new ConditionEvaluationResult("{{value}} > 10", "15 > 10 = true", true, null));

            DecisionNode node = DecisionNode.builder()
                .nodeId("core:check")
                .templateEngine(mockTemplateEngine)
                .ifBranch("{{value}} > 20", List.of())
                .elsifBranch("{{value}} > 10", List.of())
                .elseBranch(List.of())
                .build();

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("elsif", result.output().get("selected_branch"));
        }

        @Test
        @DisplayName("Should evaluate conditions in order")
        void shouldEvaluateConditionsInOrder() {
            // First branch matches, second should not be evaluated
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(eq("{{value}} > 5"), any()))
                .thenReturn(new ConditionEvaluationResult("condition", "resolved = true", true, null));

            DecisionNode node = DecisionNode.builder()
                .nodeId("core:check")
                .templateEngine(mockTemplateEngine)
                .ifBranch("{{value}} > 5", List.of())
                .elsifBranch("{{value}} > 10", List.of())
                .elseBranch(List.of())
                .build();

            node.execute(context);

            verify(mockTemplateEngine, times(1)).evaluateConditionWithDetailsWithMap(eq("{{value}} > 5"), any());
            // Should still evaluate all for detailed output, but first match wins
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Output structure tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Output Structure")
    class ExecuteOutputStructureTests {

        @Test
        @DisplayName("Should include node_type in output")
        void shouldIncludeNodeTypeInOutput() {
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(any(), any())).thenReturn(new ConditionEvaluationResult("condition", "resolved = true", true, null));

            DecisionNode node = createSimpleDecisionNode();
            NodeExecutionResult result = node.execute(context);

            assertEquals("DECISION", result.output().get("node_type"));
        }

        @Test
        @DisplayName("Should include decision_node in output")
        void shouldIncludeDecisionNodeInOutput() {
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(any(), any())).thenReturn(new ConditionEvaluationResult("condition", "resolved = true", true, null));

            DecisionNode node = createSimpleDecisionNode();
            NodeExecutionResult result = node.execute(context);

            assertEquals("core:check", result.output().get("decision_node"));
        }

        @Test
        @DisplayName("Should include branches_evaluated count in output")
        void shouldIncludeBranchesEvaluatedInOutput() {
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(any(), any())).thenReturn(new ConditionEvaluationResult("condition", "resolved = true", true, null));

            DecisionNode node = createSimpleDecisionNode();
            NodeExecutionResult result = node.execute(context);

            assertEquals(2, result.output().get("branches_evaluated"));
        }

        @Test
        @DisplayName("Should include selected_branch_index in output")
        void shouldIncludeSelectedBranchIndexInOutput() {
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(any(), any())).thenReturn(new ConditionEvaluationResult("condition", "resolved = true", true, null));

            DecisionNode node = createSimpleDecisionNode();
            NodeExecutionResult result = node.execute(context);

            assertEquals(0, result.output().get("selected_branch_index"));
        }

        @Test
        @DisplayName("Should include skipped_branches in output")
        void shouldIncludeSkippedBranchesInOutput() {
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(any(), any())).thenReturn(new ConditionEvaluationResult("condition", "resolved = true", true, null));

            DecisionNode node = createSimpleDecisionNode();
            NodeExecutionResult result = node.execute(context);

            @SuppressWarnings("unchecked")
            List<String> skipped = (List<String>) result.output().get("skipped_branches");
            assertNotNull(skipped);
            assertTrue(skipped.contains("else"));
        }

        @Test
        @DisplayName("Should include evaluations in output")
        void shouldIncludeEvaluationsInOutput() {
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(any(), any())).thenReturn(new ConditionEvaluationResult("condition", "resolved = true", true, null));

            DecisionNode node = createSimpleDecisionNode();
            NodeExecutionResult result = node.execute(context);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> evaluations = (List<Map<String, Object>>) result.output().get("evaluations");
            assertNotNull(evaluations);
            assertFalse(evaluations.isEmpty());
        }

        @Test
        @DisplayName("Should include condition_expression for selected branch")
        void shouldIncludeConditionExpressionForSelectedBranch() {
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(eq("{{value}} > 10"), any()))
                .thenReturn(new ConditionEvaluationResult("condition", "resolved = true", true, null));

            DecisionNode node = DecisionNode.builder()
                .nodeId("core:check")
                .templateEngine(mockTemplateEngine)
                .ifBranch("{{value}} > 10", List.of())
                .elseBranch(List.of())
                .build();

            NodeExecutionResult result = node.execute(context);

            assertEquals("{{value}} > 10", result.output().get("condition_expression"));
        }

        @Test
        @DisplayName("Should include item context in output")
        void shouldIncludeItemContextInOutput() {
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(any(), any())).thenReturn(new ConditionEvaluationResult("condition", "resolved = true", true, null));

            DecisionNode node = createSimpleDecisionNode();
            NodeExecutionResult result = node.execute(context);

            assertEquals(0, result.output().get("item_index"));
            assertEquals("item-1", result.output().get("item_id"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Condition evaluation exception handling
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Condition Evaluation Exception")
    class ExecuteConditionEvaluationExceptionTests {

        @Test
        @DisplayName("Should handle template engine exception gracefully and still succeed selecting else")
        void shouldHandleTemplateEngineExceptionGracefully() {
            // Arrange: the 'if' condition evaluation throws, the 'else' branch should still win.
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(eq("{{value}} > 10"), any()))
                .thenThrow(new RuntimeException("Boom: SpEL parse failure"));

            DecisionNode node = createSimpleDecisionNode();

            // Act: must not propagate the exception out of execute().
            NodeExecutionResult result = assertDoesNotThrow(() -> node.execute(context));

            // Assert: node degrades gracefully - the throwing 'if' is treated as false, 'else' is selected.
            assertTrue(result.isSuccess());
            assertEquals("else", result.output().get("selected_branch"));
        }

        @Test
        @DisplayName("Should record exception message in evaluations array error field for the throwing branch")
        void shouldRecordExceptionMessageInEvaluationsArrayErrorField() {
            // Arrange: the 'if' condition throws during evaluation.
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(eq("{{value}} > 10"), any()))
                .thenThrow(new RuntimeException("Boom: SpEL parse failure"));

            DecisionNode node = createSimpleDecisionNode();

            // Act
            NodeExecutionResult result = node.execute(context);

            // Assert: the exception is captured (not silently swallowed) in the evaluations detail
            // for the 'if' branch under the "error" key, with result=false for that branch.
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> evaluations =
                (List<Map<String, Object>>) result.output().get("evaluations");
            assertNotNull(evaluations);

            Map<String, Object> ifEval = evaluations.stream()
                .filter(e -> "if".equals(e.get("branch_type")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No 'if' branch evaluation recorded"));

            assertEquals("Boom: SpEL parse failure", ifEval.get("error"),
                "Exception message must surface in the evaluations 'error' field");
            assertEquals(false, ifEval.get("result"),
                "A throwing condition must evaluate to false");
        }

        @Test
        @DisplayName("Should not select a branch that threw during condition evaluation")
        void shouldNotSelectBranchThatThrewDuringEvaluation() {
            // Arrange: only an 'if' branch (no else), and it throws.
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(eq("{{value}} > 10"), any()))
                .thenThrow(new RuntimeException("evaluation error"));

            DecisionNode node = DecisionNode.builder()
                .nodeId("core:check")
                .templateEngine(mockTemplateEngine)
                .ifBranch("{{value}} > 10", new ArrayList<>())
                .build();

            // Act
            NodeExecutionResult result = node.execute(context);

            // Assert: no branch matched, so selected index is -1, no selected_branch, condition_result false.
            assertTrue(result.isSuccess());
            assertEquals(-1, result.output().get("selected_branch_index"));
            assertNull(result.output().get("selected_branch"));
            assertEquals(false, result.output().get("condition_result"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getNextNodes() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return selected branch nodes")
        void shouldReturnSelectedBranchNodes() {
            ExecutionNode ifTarget = createMockNode("mcp:if_target");
            ExecutionNode elseTarget = createMockNode("mcp:else_target");

            List<DecisionNode.ConditionalBranch> branches = List.of(
                new DecisionNode.ConditionalBranch("if", "{{value}} > 10", new ArrayList<>(List.of(ifTarget))),
                new DecisionNode.ConditionalBranch("else", null, new ArrayList<>(List.of(elseTarget)))
            );

            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(eq("{{value}} > 10"), any()))
                .thenReturn(new ConditionEvaluationResult("condition", "resolved = true", true, null));

            DecisionNode node = new DecisionNode("core:check", branches, mockTemplateEngine);
            NodeExecutionResult result = node.execute(context);

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(1, nextNodes.size());
            assertEquals("mcp:if_target", nextNodes.get(0).getNodeId());
        }

        @Test
        @DisplayName("Should return else branch nodes when if is false")
        void shouldReturnElseBranchNodesWhenIfIsFalse() {
            ExecutionNode ifTarget = createMockNode("mcp:if_target");
            ExecutionNode elseTarget = createMockNode("mcp:else_target");

            List<DecisionNode.ConditionalBranch> branches = List.of(
                new DecisionNode.ConditionalBranch("if", "{{value}} > 100", new ArrayList<>(List.of(ifTarget))),
                new DecisionNode.ConditionalBranch("else", null, new ArrayList<>(List.of(elseTarget)))
            );

            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(eq("{{value}} > 100"), any()))
                .thenReturn(new ConditionEvaluationResult("{{value}} > 100", "15 > 100 = false", false, null));

            DecisionNode node = new DecisionNode("core:check", branches, mockTemplateEngine);
            NodeExecutionResult result = node.execute(context);

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(1, nextNodes.size());
            assertEquals("mcp:else_target", nextNodes.get(0).getNodeId());
        }

        @Test
        @DisplayName("Should return empty when selected_branch_index is invalid")
        void shouldReturnEmptyWhenSelectedBranchIndexIsInvalid() {
            DecisionNode node = createSimpleDecisionNode();

            // Create a result with invalid index
            NodeExecutionResult result = NodeExecutionResult.success("core:check",
                Map.of("selected_branch_index", 999));

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertTrue(nextNodes.isEmpty());
        }

        @Test
        @DisplayName("Should return empty when no branch selected (index = -1)")
        void shouldReturnEmptyWhenNoBranchSelected() {
            DecisionNode node = createSimpleDecisionNode();

            NodeExecutionResult result = NodeExecutionResult.success("core:check",
                Map.of("selected_branch_index", -1));

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertTrue(nextNodes.isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getSkippedChildNodes() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getSkippedChildNodes()")
    class GetSkippedChildNodesTests {

        @Test
        @DisplayName("Should return nodes from non-selected branches")
        void shouldReturnNodesFromNonSelectedBranches() {
            ExecutionNode ifTarget = createMockNode("mcp:if_target");
            ExecutionNode elseTarget = createMockNode("mcp:else_target");

            List<DecisionNode.ConditionalBranch> branches = List.of(
                new DecisionNode.ConditionalBranch("if", "{{value}} > 10", new ArrayList<>(List.of(ifTarget))),
                new DecisionNode.ConditionalBranch("else", null, new ArrayList<>(List.of(elseTarget)))
            );

            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(any(), any())).thenReturn(new ConditionEvaluationResult("condition", "resolved = true", true, null));

            DecisionNode node = new DecisionNode("core:check", branches, mockTemplateEngine);
            NodeExecutionResult result = node.execute(context);

            List<ExecutionNode> skippedNodes = node.getSkippedChildNodes(result);

            assertEquals(1, skippedNodes.size());
            assertEquals("mcp:else_target", skippedNodes.get(0).getNodeId());
        }

        @Test
        @DisplayName("Should return all branch nodes when no branch selected")
        void shouldReturnAllBranchNodesWhenNoBranchSelected() {
            ExecutionNode ifTarget = createMockNode("mcp:if_target");
            ExecutionNode elseTarget = createMockNode("mcp:else_target");

            List<DecisionNode.ConditionalBranch> branches = List.of(
                new DecisionNode.ConditionalBranch("if", "{{value}} > 10", new ArrayList<>(List.of(ifTarget))),
                new DecisionNode.ConditionalBranch("else", null, new ArrayList<>(List.of(elseTarget)))
            );

            DecisionNode node = new DecisionNode("core:check", branches, mockTemplateEngine);

            NodeExecutionResult result = NodeExecutionResult.success("core:check",
                Map.of("selected_branch_index", -1));

            List<ExecutionNode> skippedNodes = node.getSkippedChildNodes(result);

            assertEquals(2, skippedNodes.size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getAllBranchNodes() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getAllBranchNodes()")
    class GetAllBranchNodesTests {

        @Test
        @DisplayName("Should return all nodes from all branches")
        void shouldReturnAllNodesFromAllBranches() {
            ExecutionNode ifTarget1 = createMockNode("mcp:if_target1");
            ExecutionNode ifTarget2 = createMockNode("mcp:if_target2");
            ExecutionNode elseTarget = createMockNode("mcp:else_target");

            List<DecisionNode.ConditionalBranch> branches = List.of(
                new DecisionNode.ConditionalBranch("if", "condition", new ArrayList<>(List.of(ifTarget1, ifTarget2))),
                new DecisionNode.ConditionalBranch("else", null, new ArrayList<>(List.of(elseTarget)))
            );

            DecisionNode node = new DecisionNode("core:check", branches, mockTemplateEngine);

            List<ExecutionNode> allNodes = node.getAllBranchNodes();

            assertEquals(3, allNodes.size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // addTargetToBranch() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("addTargetToBranch()")
    class AddTargetToBranchTests {

        @Test
        @DisplayName("Should add target to specified branch")
        void shouldAddTargetToSpecifiedBranch() {
            List<DecisionNode.ConditionalBranch> branches = new ArrayList<>();
            branches.add(new DecisionNode.ConditionalBranch("if", "condition", new ArrayList<>()));
            branches.add(new DecisionNode.ConditionalBranch("else", null, new ArrayList<>()));

            DecisionNode node = new DecisionNode("core:check", branches, mockTemplateEngine);
            ExecutionNode target = createMockNode("mcp:new_target");

            node.addTargetToBranch(0, target);

            assertEquals(1, node.getAllBranchNodes().size());
        }

        @Test
        @DisplayName("Should not throw for invalid branch index")
        void shouldNotThrowForInvalidBranchIndex() {
            List<DecisionNode.ConditionalBranch> branches = new ArrayList<>();
            branches.add(new DecisionNode.ConditionalBranch("if", "condition", new ArrayList<>()));

            DecisionNode node = new DecisionNode("core:check", branches, mockTemplateEngine);
            ExecutionNode target = createMockNode("mcp:target");

            assertDoesNotThrow(() -> node.addTargetToBranch(99, target));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ConditionalBranch tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ConditionalBranch")
    class ConditionalBranchTests {

        @Test
        @DisplayName("else branch should always evaluate to true")
        void elseBranchShouldAlwaysEvaluateToTrue() {
            DecisionNode.ConditionalBranch elseBranch =
                new DecisionNode.ConditionalBranch("else", null, new ArrayList<>());

            boolean result = elseBranch.evaluateCondition(Map.of(), null, null);

            assertTrue(result);
        }

        @Test
        @DisplayName("Branch with blank condition should evaluate to true")
        void branchWithBlankConditionShouldEvaluateToTrue() {
            DecisionNode.ConditionalBranch branch =
                new DecisionNode.ConditionalBranch("if", "  ", new ArrayList<>());

            boolean result = branch.evaluateCondition(Map.of(), null, null);

            assertTrue(result);
        }

        @Test
        @DisplayName("Should return false when TemplateEngine is null and condition exists")
        void shouldReturnFalseWhenTemplateEngineIsNullAndConditionExists() {
            DecisionNode.ConditionalBranch branch =
                new DecisionNode.ConditionalBranch("if", "{{value}} > 10", new ArrayList<>());

            boolean result = branch.evaluateCondition(Map.of(), null, null);

            assertFalse(result);
        }

        @Test
        @DisplayName("Should return false when condition evaluation throws exception")
        void shouldReturnFalseWhenConditionEvaluationThrowsException() {
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(any(), any()))
                .thenThrow(new RuntimeException("Evaluation error"));

            DecisionNode.ConditionalBranch branch =
                new DecisionNode.ConditionalBranch("if", "{{invalid}}", new ArrayList<>());

            boolean result = branch.evaluateCondition(Map.of(), mockTemplateEngine, null);

            assertFalse(result);
        }

        @Test
        @DisplayName("Should expose branch type")
        void shouldExposeBranchType() {
            DecisionNode.ConditionalBranch branch =
                new DecisionNode.ConditionalBranch("elsif", "condition", new ArrayList<>());

            assertEquals("elsif", branch.type());
        }

        @Test
        @DisplayName("Should expose branch condition")
        void shouldExposeBranchCondition() {
            DecisionNode.ConditionalBranch branch =
                new DecisionNode.ConditionalBranch("if", "{{value}} > 10", new ArrayList<>());

            assertEquals("{{value}} > 10", branch.condition());
        }

        @Test
        @DisplayName("Should expose branch nodes")
        void shouldExposeBranchNodes() {
            ExecutionNode node = createMockNode("mcp:target");
            DecisionNode.ConditionalBranch branch =
                new DecisionNode.ConditionalBranch("if", "condition", new ArrayList<>(List.of(node)));

            assertEquals(1, branch.nodes().size());
        }

        @Test
        @DisplayName("Should add node to branch")
        void shouldAddNodeToBranch() {
            DecisionNode.ConditionalBranch branch =
                new DecisionNode.ConditionalBranch("if", "condition", new ArrayList<>());
            ExecutionNode node = createMockNode("mcp:target");

            branch.addNode(node);

            assertEquals(1, branch.nodes().size());
        }

        @Test
        @DisplayName("Should not add null node")
        void shouldNotAddNullNode() {
            DecisionNode.ConditionalBranch branch =
                new DecisionNode.ConditionalBranch("if", "condition", new ArrayList<>());

            branch.addNode(null);

            assertTrue(branch.nodes().isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Evaluation context building tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Evaluation Context Building")
    class EvaluationContextBuildingTests {

        @Test
        @DisplayName("Should include trigger data in evaluation context")
        void shouldIncludeTriggerDataInEvaluationContext() {
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(any(), argThat(ctx ->
                ctx.containsKey("value") && Integer.valueOf(15).equals(ctx.get("value"))
            ))).thenReturn(new ConditionEvaluationResult("condition", "resolved = true", true, null));

            DecisionNode node = createSimpleDecisionNode();
            node.execute(context);

            verify(mockTemplateEngine).evaluateConditionWithDetailsWithMap(any(), argThat(ctx ->
                ctx.containsKey("trigger") && ctx.containsKey("value")
            ));
        }

        @Test
        @DisplayName("Should include step outputs in evaluation context")
        void shouldIncludeStepOutputsInEvaluationContext() {
            NodeExecutionResult stepResult = NodeExecutionResult.success("mcp:step1",
                Map.of("data", "step_output"));
            ExecutionContext contextWithSteps = context.withResult("mcp:step1", stepResult);

            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(any(), any())).thenReturn(new ConditionEvaluationResult("condition", "resolved = true", true, null));

            DecisionNode node = createSimpleDecisionNode();
            node.execute(contextWithSteps);

            verify(mockTemplateEngine).evaluateConditionWithDetailsWithMap(any(), argThat(ctx ->
                ctx.containsKey("mcp:step1")
            ));
        }

        @Test
        @DisplayName("Should include item context in evaluation context")
        void shouldIncludeItemContextInEvaluationContext() {
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(any(), argThat(ctx ->
                "item-1".equals(ctx.get("item_id")) && Integer.valueOf(0).equals(ctx.get("item_index"))
            ))).thenReturn(new ConditionEvaluationResult("condition", "resolved = true", true, null));

            DecisionNode node = createSimpleDecisionNode();
            node.execute(context);

            verify(mockTemplateEngine).evaluateConditionWithDetailsWithMap(any(), argThat(ctx ->
                ctx.containsKey("item_id") && ctx.containsKey("item_index")
            ));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // onComplete() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("onComplete()")
    class OnCompleteTests {

        @Test
        @DisplayName("Should not throw exception")
        void shouldNotThrowException() {
            DecisionNode node = createSimpleDecisionNode();
            NodeExecutionResult result = NodeExecutionResult.success(node.getNodeId(), Map.of());

            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper methods
    // ═══════════════════════════════════════════════════════════════════════════

    private DecisionNode createSimpleDecisionNode() {
        return DecisionNode.builder()
            .nodeId("core:check")
            .templateEngine(mockTemplateEngine)
            .ifBranch("{{value}} > 10", new ArrayList<>())
            .elseBranch(new ArrayList<>())
            .build();
    }

    private List<DecisionNode.ConditionalBranch> createTwoBranches() {
        return List.of(
            new DecisionNode.ConditionalBranch("if", "{{value}} > 10", new ArrayList<>()),
            new DecisionNode.ConditionalBranch("else", null, new ArrayList<>())
        );
    }

    private ExecutionNode createMockNode(String nodeId) {
        return new BaseNode(nodeId, NodeType.MCP) {
            @Override
            public NodeExecutionResult execute(ExecutionContext context) {
                return NodeExecutionResult.success(nodeId, Map.of());
            }
        };
    }
}
