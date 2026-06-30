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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SwitchNode.
 * SwitchNode evaluates an expression and matches it against case values.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SwitchNode")
class SwitchNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private TemplateEngine mockTemplateEngine;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("status", "active");
        triggerData.put("priority", 1);
        triggerData.put("type", "order");

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
        @DisplayName("Should create SwitchNode with all parameters")
        void shouldCreateSwitchNodeWithAllParameters() {
            List<SwitchNode.SwitchCase> cases = new ArrayList<>();
            cases.add(new SwitchNode.SwitchCase("case", "active", "Active Case"));
            cases.add(new SwitchNode.SwitchCase("default", null, "Default"));

            SwitchNode node = new SwitchNode(
                "core:switch",
                "{{trigger:start.status}}",
                cases,
                mockTemplateEngine
            );

            assertEquals("core:switch", node.getNodeId());
            assertEquals(NodeType.SWITCH, node.getType());
            assertEquals("{{trigger:start.status}}", node.getSwitchExpression());
            assertEquals(2, node.getCases().size());
        }

        @Test
        @DisplayName("Should handle null cases")
        void shouldHandleNullCases() {
            SwitchNode node = new SwitchNode(
                "core:switch",
                "{{status}}",
                null,
                mockTemplateEngine
            );

            assertNotNull(node.getCases());
            assertTrue(node.getCases().isEmpty());
        }

        @Test
        @DisplayName("Should create SwitchNode using builder")
        void shouldCreateSwitchNodeUsingBuilder() {
            SwitchNode node = SwitchNode.builder()
                .nodeId("core:my_switch")
                .switchExpression("{{trigger:start.type}}")
                .addCase("order", "Order Case")
                .addCase("refund", "Refund Case")
                .addDefault("Default Case")
                .templateEngine(mockTemplateEngine)
                .build();

            assertEquals("core:my_switch", node.getNodeId());
            assertEquals("{{trigger:start.type}}", node.getSwitchExpression());
            assertEquals(3, node.getCases().size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Basic execution tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Basic Execution")
    class ExecuteBasicTests {

        @Test
        @DisplayName("Should return success")
        void shouldReturnSuccess() {
            when(mockTemplateEngine.resolveWithMap(any(), any()))
                .thenReturn("active");

            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{status}}")
                .addCase("active", "Active")
                .addDefault("Default")
                .templateEngine(mockTemplateEngine)
                .build();

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Should include node_type SWITCH in output")
        void shouldIncludeNodeTypeSWITCHInOutput() {
            when(mockTemplateEngine.resolveWithMap(any(), any()))
                .thenReturn("value");

            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{status}}")
                .addCase("value", "Value Case")
                .templateEngine(mockTemplateEngine)
                .build();

            NodeExecutionResult result = node.execute(context);

            assertEquals("SWITCH", result.output().get("node_type"));
        }

        @Test
        @DisplayName("Should include switch_node in output")
        void shouldIncludeSwitchNodeInOutput() {
            when(mockTemplateEngine.resolveWithMap(any(), any()))
                .thenReturn("value");

            SwitchNode node = SwitchNode.builder()
                .nodeId("core:my_switch")
                .switchExpression("{{status}}")
                .addCase("value", "Value Case")
                .templateEngine(mockTemplateEngine)
                .build();

            NodeExecutionResult result = node.execute(context);

            assertEquals("core:my_switch", result.output().get("switch_node"));
        }

        @Test
        @DisplayName("Should include switch_value in output")
        void shouldIncludeSwitchValueInOutput() {
            when(mockTemplateEngine.resolveWithMap(any(), any()))
                .thenReturn("active");

            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{status}}")
                .addCase("active", "Active")
                .templateEngine(mockTemplateEngine)
                .build();

            NodeExecutionResult result = node.execute(context);

            assertEquals("active", result.output().get("switch_value"));
        }

        @Test
        @DisplayName("Should include item context in output")
        void shouldIncludeItemContextInOutput() {
            when(mockTemplateEngine.resolveWithMap(any(), any()))
                .thenReturn("value");

            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{status}}")
                .addCase("value", "Value")
                .templateEngine(mockTemplateEngine)
                .build();

            NodeExecutionResult result = node.execute(context);

            assertEquals(0, result.output().get("item_index"));
            assertEquals("item-1", result.output().get("item_id"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Case matching tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Case Matching")
    class ExecuteCaseMatchingTests {

        @Test
        @DisplayName("Should match first matching case")
        void shouldMatchFirstMatchingCase() {
            when(mockTemplateEngine.resolveWithMap(any(), any()))
                .thenReturn("active");

            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{status}}")
                .addCase("pending", "Pending")
                .addCase("active", "Active")
                .addCase("completed", "Completed")
                .addDefault("Default")
                .templateEngine(mockTemplateEngine)
                .build();

            NodeExecutionResult result = node.execute(context);

            assertEquals("case_1", result.output().get("selected_case"));
            assertEquals(1, result.output().get("selected_case_index"));
        }

        @Test
        @DisplayName("Should use default when no case matches")
        void shouldUseDefaultWhenNoCaseMatches() {
            when(mockTemplateEngine.resolveWithMap(any(), any()))
                .thenReturn("unknown_status");

            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{status}}")
                .addCase("pending", "Pending")
                .addCase("active", "Active")
                .addDefault("Default")
                .templateEngine(mockTemplateEngine)
                .build();

            NodeExecutionResult result = node.execute(context);

            assertEquals("default", result.output().get("selected_case"));
        }

        @Test
        @DisplayName("Should include skipped_cases in output")
        void shouldIncludeSkippedCasesInOutput() {
            when(mockTemplateEngine.resolveWithMap(any(), any()))
                .thenReturn("active");

            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{status}}")
                .addCase("pending", "Pending")
                .addCase("active", "Active")
                .addCase("completed", "Completed")
                .addDefault("Default")
                .templateEngine(mockTemplateEngine)
                .build();

            NodeExecutionResult result = node.execute(context);

            @SuppressWarnings("unchecked")
            List<String> skipped = (List<String>) result.output().get("skipped_cases");
            assertTrue(skipped.contains("case_0"));
            assertTrue(skipped.contains("case_2"));
            assertTrue(skipped.contains("default"));
        }

        @Test
        @DisplayName("Should handle numeric value matching")
        void shouldHandleNumericValueMatching() {
            when(mockTemplateEngine.resolveWithMap(any(), any()))
                .thenReturn("1");

            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{priority}}")
                .addCase(0, "Low")
                .addCase(1, "Medium")
                .addCase(2, "High")
                .templateEngine(mockTemplateEngine)
                .build();

            NodeExecutionResult result = node.execute(context);

            assertEquals("case_1", result.output().get("selected_case"));
        }

        @Test
        @DisplayName("Should handle string-to-number comparison")
        void shouldHandleStringToNumberComparison() {
            when(mockTemplateEngine.resolveWithMap(any(), any()))
                .thenReturn("1");

            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{priority}}")
                .addCase(1, "Match")
                .addDefault("No Match")
                .templateEngine(mockTemplateEngine)
                .build();

            NodeExecutionResult result = node.execute(context);

            assertEquals("case_0", result.output().get("selected_case"));
        }

        @Test
        @DisplayName("Should handle case-insensitive string matching")
        void shouldHandleCaseInsensitiveStringMatching() {
            when(mockTemplateEngine.resolveWithMap(any(), any()))
                .thenReturn("ACTIVE");

            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{status}}")
                .addCase("active", "Active")
                .addDefault("Default")
                .templateEngine(mockTemplateEngine)
                .build();

            NodeExecutionResult result = node.execute(context);

            assertEquals("case_0", result.output().get("selected_case"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Expression evaluation tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Expression Evaluation")
    class ExecuteExpressionEvaluationTests {

        @Test
        @DisplayName("Should handle null expression")
        void shouldHandleNullExpression() {
            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression(null)
                .addCase(null, "Null Case")
                .addDefault("Default")
                .templateEngine(mockTemplateEngine)
                .build();

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertNull(result.output().get("switch_value"));
        }

        @Test
        @DisplayName("Should handle blank expression")
        void shouldHandleBlankExpression() {
            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("   ")
                .addDefault("Default")
                .templateEngine(mockTemplateEngine)
                .build();

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Should handle expression evaluation error")
        void shouldHandleExpressionEvaluationError() {
            when(mockTemplateEngine.resolveWithMap(any(), any()))
                .thenThrow(new RuntimeException("Evaluation error"));

            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{invalid}}")
                .addCase("value", "Value")
                .addDefault("Default")
                .templateEngine(mockTemplateEngine)
                .build();

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            // Should fall back to default when expression fails
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Null value matching contract (valuesMatch null handling)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Null Value Matching")
    class ExecuteNullValueMatchingTests {

        @Test
        @DisplayName("Null switch value should match a case whose value is also null (both-null match)")
        void nullSwitchValueShouldMatchNullCaseValue() {
            // Arrange: switchExpression is null so evaluateSwitchExpression returns null
            // without invoking the template engine. The first case has a null value, so
            // valuesMatch(null, null) -> true and case_0 must be selected, not the default.
            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression(null)
                .addCase(null, "Null Case")
                .addDefault("Default")
                .templateEngine(mockTemplateEngine)
                .build();

            // Act
            NodeExecutionResult result = node.execute(context);

            // Assert: the null-valued case wins over the default branch
            assertTrue(result.isSuccess());
            assertNull(result.output().get("switch_value"));
            assertEquals("case_0", result.output().get("selected_case"));
            assertEquals(0, result.output().get("selected_case_index"));
            assertEquals(true, result.output().get("match_result"));
        }

        @Test
        @DisplayName("Null switch value should NOT match a non-null case value and fall through to default")
        void nullSwitchValueShouldNotMatchNonNullCaseValue() {
            // Arrange: null switch value, single non-null case, plus a default.
            // valuesMatch(null, "active") must return false (one-null branch), so the
            // default branch is selected.
            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression(null)
                .addCase("active", "Active")
                .addDefault("Default")
                .templateEngine(mockTemplateEngine)
                .build();

            // Act
            NodeExecutionResult result = node.execute(context);

            // Assert: non-null case did not match, default selected
            assertTrue(result.isSuccess());
            assertNull(result.output().get("switch_value"));
            assertEquals("default", result.output().get("selected_case"));
        }

        @Test
        @DisplayName("Null switch value picks the null case even when a string case precedes the default")
        void nullSwitchValuePicksNullCaseAmongMixedCases() {
            // Arrange: a string case, then a null case, then a default. With a null
            // switch value the string case must NOT match (one-null -> false) but the
            // null case MUST match (both-null -> true) before the default is reached.
            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression(null)
                .addCase("active", "Active")
                .addCase(null, "Null Case")
                .addDefault("Default")
                .templateEngine(mockTemplateEngine)
                .build();

            // Act
            NodeExecutionResult result = node.execute(context);

            // Assert: case_1 (the null case) wins, not case_0 and not default
            assertTrue(result.isSuccess());
            assertEquals("case_1", result.output().get("selected_case"));
            assertEquals(1, result.output().get("selected_case_index"));
        }

        @Test
        @DisplayName("Non-null switch value should NOT match a null case value (symmetric one-null)")
        void nonNullSwitchValueShouldNotMatchNullCaseValue() {
            // Arrange: switch resolves to a real string, but the only matchable case has
            // a null value. valuesMatch("active", null) must return false (one-null
            // branch), so the default branch is selected.
            when(mockTemplateEngine.resolveWithMap(any(), any()))
                .thenReturn("active");

            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{status}}")
                .addCase(null, "Null Case")
                .addDefault("Default")
                .templateEngine(mockTemplateEngine)
                .build();

            // Act
            NodeExecutionResult result = node.execute(context);

            // Assert: null case did not match the non-null value, default selected
            assertTrue(result.isSuccess());
            assertEquals("active", result.output().get("switch_value"));
            assertEquals("default", result.output().get("selected_case"));
        }

        @Test
        @DisplayName("Expression evaluation exception yields null switch value and selects the default case")
        void expressionEvaluationExceptionSelectsDefaultCase() {
            // Arrange: template engine throws, so evaluateSwitchExpression catches and
            // returns null. The single non-null case must not match the null value, so
            // the switch must fall back to the default branch.
            when(mockTemplateEngine.resolveWithMap(any(), any()))
                .thenThrow(new RuntimeException("Evaluation error"));

            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{invalid}}")
                .addCase("value", "Value")
                .addDefault("Default")
                .templateEngine(mockTemplateEngine)
                .build();

            // Act
            NodeExecutionResult result = node.execute(context);

            // Assert: failure swallowed -> null switch value -> default branch taken
            assertTrue(result.isSuccess());
            assertNull(result.output().get("switch_value"));
            assertEquals("default", result.output().get("selected_case"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getNextNodes() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return nodes for selected case")
        void shouldReturnNodesForSelectedCase() {
            ExecutionNode targetNode = createMockNode("mcp:active_handler");

            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{status}}")
                .addCase("active", "Active", new ArrayList<>(List.of(targetNode)))
                .addDefault("Default")
                .templateEngine(mockTemplateEngine)
                .build();

            NodeExecutionResult result = NodeExecutionResult.success("core:switch",
                Map.of("selected_case_index", 0));

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(1, nextNodes.size());
            assertEquals("mcp:active_handler", nextNodes.get(0).getNodeId());
        }

        @Test
        @DisplayName("Should return default nodes when selected")
        void shouldReturnDefaultNodesWhenSelected() {
            ExecutionNode defaultTarget = createMockNode("mcp:default_handler");

            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{status}}")
                .addCase("pending", "Pending")
                .addDefault("Default", new ArrayList<>(List.of(defaultTarget)))
                .templateEngine(mockTemplateEngine)
                .build();

            NodeExecutionResult result = NodeExecutionResult.success("core:switch",
                Map.of("selected_case_index", 1));

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(1, nextNodes.size());
            assertEquals("mcp:default_handler", nextNodes.get(0).getNodeId());
        }

        @Test
        @DisplayName("Should return empty list when no case selected")
        void shouldReturnEmptyListWhenNoCaseSelected() {
            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{status}}")
                .addCase("pending", "Pending")
                .templateEngine(mockTemplateEngine)
                .build();

            NodeExecutionResult result = NodeExecutionResult.success("core:switch",
                Map.of("selected_case_index", -1));

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertTrue(nextNodes.isEmpty());
        }

        @Test
        @DisplayName("Should return empty list when index out of bounds")
        void shouldReturnEmptyListWhenIndexOutOfBounds() {
            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{status}}")
                .addCase("pending", "Pending")
                .templateEngine(mockTemplateEngine)
                .build();

            NodeExecutionResult result = NodeExecutionResult.success("core:switch",
                Map.of("selected_case_index", 999));

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertTrue(nextNodes.isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getSkippedCaseNodes() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getSkippedCaseNodes()")
    class GetSkippedCaseNodesTests {

        @Test
        @DisplayName("Should return all other case nodes")
        void shouldReturnAllOtherCaseNodes() {
            ExecutionNode target1 = createMockNode("mcp:pending");
            ExecutionNode target2 = createMockNode("mcp:active");
            ExecutionNode target3 = createMockNode("mcp:default");

            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{status}}")
                .addCase("pending", "Pending", new ArrayList<>(List.of(target1)))
                .addCase("active", "Active", new ArrayList<>(List.of(target2)))
                .addDefault("Default", new ArrayList<>(List.of(target3)))
                .templateEngine(mockTemplateEngine)
                .build();

            NodeExecutionResult result = NodeExecutionResult.success("core:switch",
                Map.of("selected_case_index", 1)); // Active selected

            List<ExecutionNode> skippedNodes = node.getSkippedCaseNodes(result);

            assertEquals(2, skippedNodes.size());
            assertTrue(skippedNodes.stream().anyMatch(n -> n.getNodeId().equals("mcp:pending")));
            assertTrue(skippedNodes.stream().anyMatch(n -> n.getNodeId().equals("mcp:default")));
        }

        @Test
        @DisplayName("Should return all case nodes when no selection")
        void shouldReturnAllCaseNodesWhenNoSelection() {
            ExecutionNode target1 = createMockNode("mcp:case1");
            ExecutionNode target2 = createMockNode("mcp:case2");

            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{status}}")
                .addCase("value1", "Case1", new ArrayList<>(List.of(target1)))
                .addCase("value2", "Case2", new ArrayList<>(List.of(target2)))
                .templateEngine(mockTemplateEngine)
                .build();

            NodeExecutionResult result = NodeExecutionResult.success("core:switch",
                Map.of("selected_case_index", -1));

            List<ExecutionNode> skippedNodes = node.getSkippedCaseNodes(result);

            assertEquals(2, skippedNodes.size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getAllCaseNodes() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getAllCaseNodes()")
    class GetAllCaseNodesTests {

        @Test
        @DisplayName("Should return all case nodes")
        void shouldReturnAllCaseNodes() {
            ExecutionNode target1 = createMockNode("mcp:case1");
            ExecutionNode target2 = createMockNode("mcp:case2");
            ExecutionNode target3 = createMockNode("mcp:default");

            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{status}}")
                .addCase("value1", "Case1", new ArrayList<>(List.of(target1)))
                .addCase("value2", "Case2", new ArrayList<>(List.of(target2)))
                .addDefault("Default", new ArrayList<>(List.of(target3)))
                .templateEngine(mockTemplateEngine)
                .build();

            List<ExecutionNode> allNodes = node.getAllCaseNodes();

            assertEquals(3, allNodes.size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // addTargetToCase() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("addTargetToCase()")
    class AddTargetToCaseTests {

        @Test
        @DisplayName("Should add target to specific case")
        void shouldAddTargetToSpecificCase() {
            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{status}}")
                .addCase("active", "Active")
                .addDefault("Default")
                .templateEngine(mockTemplateEngine)
                .build();

            ExecutionNode target = createMockNode("mcp:new_target");
            node.addTargetToCase(0, target);

            assertEquals(1, node.getCases().get(0).nodes().size());
            assertEquals("mcp:new_target", node.getCases().get(0).nodes().get(0).getNodeId());
        }

        @Test
        @DisplayName("Should handle invalid case index")
        void shouldHandleInvalidCaseIndex() {
            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{status}}")
                .addCase("active", "Active")
                .templateEngine(mockTemplateEngine)
                .build();

            ExecutionNode target = createMockNode("mcp:target");

            // Should not throw
            assertDoesNotThrow(() -> node.addTargetToCase(999, target));
            assertDoesNotThrow(() -> node.addTargetToCase(-1, target));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SwitchCase tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SwitchCase")
    class SwitchCaseTests {

        @Test
        @DisplayName("Should create case with all properties")
        void shouldCreateCaseWithAllProperties() {
            SwitchNode.SwitchCase switchCase = new SwitchNode.SwitchCase(
                "case", "active", "Active Status"
            );

            assertEquals("case", switchCase.type());
            assertEquals("active", switchCase.value());
            assertEquals("Active Status", switchCase.label());
            assertFalse(switchCase.isDefault());
        }

        @Test
        @DisplayName("Should create default case")
        void shouldCreateDefaultCase() {
            SwitchNode.SwitchCase switchCase = new SwitchNode.SwitchCase(
                "default", null, "Default"
            );

            assertEquals("default", switchCase.type());
            assertNull(switchCase.value());
            assertTrue(switchCase.isDefault());
        }

        @Test
        @DisplayName("Should use default type when null")
        void shouldUseDefaultTypeWhenNull() {
            SwitchNode.SwitchCase switchCase = new SwitchNode.SwitchCase(
                null, "value", "Label"
            );

            assertEquals("case", switchCase.type());
        }

        @Test
        @DisplayName("Should add node to case")
        void shouldAddNodeToCase() {
            SwitchNode.SwitchCase switchCase = new SwitchNode.SwitchCase(
                "case", "value", "Label"
            );

            ExecutionNode node = createMockNode("mcp:target");
            switchCase.addNode(node);

            assertEquals(1, switchCase.nodes().size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Getters tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Getters")
    class GettersTests {

        @Test
        @DisplayName("getSwitchExpression() should return expression")
        void getSwitchExpressionShouldReturnExpression() {
            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{trigger:start.status}}")
                .templateEngine(mockTemplateEngine)
                .build();

            assertEquals("{{trigger:start.status}}", node.getSwitchExpression());
        }

        @Test
        @DisplayName("getCases() should return cases")
        void getCasesShouldReturnCases() {
            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{status}}")
                .addCase("a", "A")
                .addCase("b", "B")
                .addDefault("Default")
                .templateEngine(mockTemplateEngine)
                .build();

            assertEquals(3, node.getCases().size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // onComplete() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("onComplete()")
    class OnCompleteTests {

        @Test
        @DisplayName("Should not throw exception on success result")
        void shouldNotThrowExceptionOnSuccessResult() {
            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{status}}")
                .addCase("active", "Active")
                .templateEngine(mockTemplateEngine)
                .build();

            NodeExecutionResult result = NodeExecutionResult.success("core:switch", Map.of());

            assertDoesNotThrow(() -> node.onComplete(context, result));
        }

        @Test
        @DisplayName("Should not throw exception on failure result")
        void shouldNotThrowExceptionOnFailureResult() {
            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{status}}")
                .addCase("active", "Active")
                .templateEngine(mockTemplateEngine)
                .build();

            NodeExecutionResult result = NodeExecutionResult.failure("core:switch", "Error");

            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Switch vs Decision comparison tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Switch vs Decision")
    class SwitchVsDecisionTests {

        @Test
        @DisplayName("Switch matches value, Decision evaluates condition")
        void switchMatchesValueDecisionEvaluatesCondition() {
            when(mockTemplateEngine.resolveWithMap(any(), any()))
                .thenReturn("completed");

            // Switch matches "completed" against case values
            SwitchNode switchNode = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{status}}")
                .addCase("pending", "Pending")
                .addCase("active", "Active")
                .addCase("completed", "Completed")
                .templateEngine(mockTemplateEngine)
                .build();

            NodeExecutionResult result = switchNode.execute(context);

            assertEquals("case_2", result.output().get("selected_case"));
            assertEquals("completed", result.output().get("switch_value"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // buildEvalContext() - Metadata exclusion tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("buildEvalContext() - Metadata Exclusion")
    class BuildEvalContextMetadataExclusionTests {

        @Test
        @DisplayName("Should exclude currentIteration from eval context")
        void shouldExcludeCurrentIterationFromEvalContext() {
            // Given: A trigger output that contains currentIteration metadata
            Map<String, Object> triggerOutput = new HashMap<>();
            triggerOutput.put("status", "active");
            triggerOutput.put("currentIteration", 3);
            triggerOutput.put("iteration", 3);

            Map<String, Object> wrappedTriggerOutput = new HashMap<>();
            wrappedTriggerOutput.put("output", triggerOutput);

            // Build a context that includes trigger step outputs with loop metadata
            NodeExecutionResult triggerResult = NodeExecutionResult.success("trigger:start", triggerOutput);
            ExecutionContext contextWithTrigger = context.withResult("trigger:start", triggerResult);

            when(mockTemplateEngine.resolveWithMap(any(), argThat(ctx -> {
                // Verify that currentIteration and iteration are NOT in the eval context at top level
                return !ctx.containsKey("currentIteration") && !ctx.containsKey("iteration");
            }))).thenReturn("active");

            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{status}}")
                .addCase("active", "Active")
                .addDefault("Default")
                .templateEngine(mockTemplateEngine)
                .build();

            // When
            NodeExecutionResult result = node.execute(contextWithTrigger);

            // Then: The switch should still work correctly
            assertTrue(result.isSuccess());
            // Verify the template engine was called with a context that excludes metadata
            verify(mockTemplateEngine).resolveWithMap(any(), argThat(ctx ->
                !ctx.containsKey("currentIteration") && !ctx.containsKey("iteration")
            ));
        }

        @Test
        @DisplayName("Should exclude all metadata keys from eval context like DecisionNode")
        void shouldExcludeAllMetadataKeysLikeDecisionNode() {
            // Given: A trigger output with all metadata keys
            Map<String, Object> triggerOutput = new HashMap<>();
            triggerOutput.put("user_data", "value");
            triggerOutput.put("trigger_id", "should-be-excluded");
            triggerOutput.put("trigger_data", "should-be-excluded");
            triggerOutput.put("item_id", "should-be-excluded");
            triggerOutput.put("item_index", 0);
            triggerOutput.put("httpstatus", Map.of("code", 200));
            triggerOutput.put("itemIndex", 0);
            triggerOutput.put("currentIteration", 5);
            triggerOutput.put("iteration", 5);

            NodeExecutionResult triggerResult = NodeExecutionResult.success("trigger:webhook", triggerOutput);
            ExecutionContext contextWithTrigger = context.withResult("trigger:webhook", triggerResult);

            when(mockTemplateEngine.resolveWithMap(any(), argThat(ctx -> {
                // user_data should be present, all metadata keys should be excluded
                return ctx.containsKey("user_data")
                    && !ctx.containsKey("trigger_id")
                    && !ctx.containsKey("trigger_data")
                    && !ctx.containsKey("currentIteration")
                    && !ctx.containsKey("iteration");
            }))).thenReturn("some_value");

            SwitchNode node = SwitchNode.builder()
                .nodeId("core:switch")
                .switchExpression("{{user_data}}")
                .addCase("value", "Match")
                .addDefault("Default")
                .templateEngine(mockTemplateEngine)
                .build();

            // When
            NodeExecutionResult result = node.execute(contextWithTrigger);

            // Then
            assertTrue(result.isSuccess());
            verify(mockTemplateEngine).resolveWithMap(any(), argThat(ctx ->
                ctx.containsKey("user_data")
                    && !ctx.containsKey("trigger_id")
                    && !ctx.containsKey("trigger_data")
                    && !ctx.containsKey("currentIteration")
                    && !ctx.containsKey("iteration")
            ));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper methods
    // ═══════════════════════════════════════════════════════════════════════════

    private ExecutionNode createMockNode(String nodeId) {
        return new BaseNode(nodeId, NodeType.MCP) {
            @Override
            public NodeExecutionResult execute(ExecutionContext context) {
                return NodeExecutionResult.success(nodeId, Map.of());
            }
        };
    }
}
