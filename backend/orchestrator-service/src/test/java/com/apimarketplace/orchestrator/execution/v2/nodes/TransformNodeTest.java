package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
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
import static org.mockito.Mockito.when;

/**
 * Unit tests for TransformNode.
 *
 * <p>TransformNode produces the canonical shape {@code {transformed: {...}, evaluations: [...]}}
 * directly at runtime (no post-mapper reshape). All assertions navigate through the
 * {@code transformed} sub-map - that is the contract downstream templates rely on.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransformNode")
class TransformNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private V2TemplateAdapter mockTemplateAdapter;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("name", "John");
        triggerData.put("age", 30);
        triggerData.put("items", List.of("a", "b", "c"));

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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> transformedOf(NodeExecutionResult result) {
        Object t = result.output().get("transformed");
        assertNotNull(t, "Output must contain 'transformed' map at top level");
        assertInstanceOf(Map.class, t, "'transformed' must be a Map");
        return (Map<String, Object>) t;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> evaluationsOf(NodeExecutionResult result) {
        Object e = result.output().get("evaluations");
        assertNotNull(e, "Output must contain 'evaluations' array at top level");
        assertInstanceOf(List.class, e, "'evaluations' must be a List");
        return (List<Map<String, Object>>) e;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create TransformNode with nodeId and mappings")
        void shouldCreateTransformNodeWithNodeIdAndMappings() {
            List<Core.TransformMapping> mappings = List.of(
                new Core.TransformMapping("output_name", "{{trigger:start.name}}"),
                new Core.TransformMapping("output_age", "{{trigger:start.age}}")
            );

            TransformNode node = new TransformNode("core:transform", mappings);

            assertEquals("core:transform", node.getNodeId());
            assertEquals(NodeType.TRANSFORM, node.getType());
            assertEquals(2, node.getMappings().size());
        }

        @Test
        @DisplayName("Should handle null mappings")
        void shouldHandleNullMappings() {
            TransformNode node = new TransformNode("core:transform", null);

            assertNotNull(node.getMappings());
            assertTrue(node.getMappings().isEmpty());
        }

        @Test
        @DisplayName("Should create TransformNode using builder")
        void shouldCreateTransformNodeUsingBuilder() {
            List<Core.TransformMapping> mappings = List.of(
                new Core.TransformMapping("result", "{{mcp:step.output}}")
            );

            TransformNode node = TransformNode.builder()
                .nodeId("core:my_transform")
                .mappings(mappings)
                .build();

            assertEquals("core:my_transform", node.getNodeId());
            assertEquals(1, node.getMappings().size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Canonical shape (REGRESSION)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Canonical shape (regression)")
    class CanonicalShapeRegressionTests {

        @Test
        @DisplayName("Output exposes 'transformed' map at top level so {{output.transformed.field}} resolves downstream")
        void runtimeOutputContainsTransformedKeyAtTopLevel() {
            // Regression for the drift fixed alongside be1c9e59e: TransformNode used to
            // write mappings at the top level, but TransformNodeSpec wraps them under
            // 'transformed' for DB/doc - breaking AUTOMATIC-mode templates that follow
            // the documented shape {{...output.transformed.field}}. Now runtime ==
            // persisted == doc.
            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenReturn(Map.of("__expr__", "noreply@statuspage.io"));

            List<Core.TransformMapping> mappings = List.of(
                new Core.TransformMapping("from", "{{mcp:get_content.output.payload.headers.?[name == 'From'][0].value}}")
            );

            TransformNode node = new TransformNode("core:parse_headers", mappings);
            node.setTemplateAdapter(mockTemplateAdapter);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            // The contract: top-level keys are the canonical/documented ones.
            assertEquals("noreply@statuspage.io", transformedOf(result).get("from"),
                "Mapping value must live under .transformed.<label> at runtime");
            // Mapping label must NOT appear at top level - that was the legacy drift.
            assertFalse(result.output().containsKey("from"),
                "Mapping label must NOT be exposed at top level; only inside 'transformed'");
        }

        @Test
        @DisplayName("Evaluations array carries field/expression/resolved_expression/value per mapping")
        void evaluationsArrayDocumentsEachMapping() {
            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> input = invocation.getArgument(0);
                    return Map.of("__expr__", "value_for_" + input.get("__expr__"));
                });

            List<Core.TransformMapping> mappings = List.of(
                new Core.TransformMapping("a", "expr_a"),
                new Core.TransformMapping("b", "expr_b")
            );

            TransformNode node = new TransformNode("core:t", mappings);
            node.setTemplateAdapter(mockTemplateAdapter);

            NodeExecutionResult result = node.execute(context);

            List<Map<String, Object>> evals = evaluationsOf(result);
            assertEquals(2, evals.size());
            Map<String, Object> first = evals.get(0);
            assertEquals("a", first.get("field"));
            assertEquals("expr_a", first.get("expression"));
            assertEquals("expr_a", first.get("resolved_expression"));
            assertEquals("value_for_expr_a", first.get("value"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Success cases
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Success Cases")
    class ExecuteSuccessCasesTests {

        @Test
        @DisplayName("Should return success when mappings are applied")
        void shouldReturnSuccessWhenMappingsAreApplied() {
            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenReturn(Map.of("__expr__", "resolved_value"));

            List<Core.TransformMapping> mappings = List.of(
                new Core.TransformMapping("output", "{{trigger:start.name}}")
            );

            TransformNode node = new TransformNode("core:transform", mappings);
            node.setTemplateAdapter(mockTemplateAdapter);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Should include transformed values under 'transformed'")
        void shouldIncludeTransformedValuesInOutput() {
            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> input = invocation.getArgument(0);
                    String expr = (String) input.get("__expr__");
                    if (expr.contains("name")) {
                        return Map.of("__expr__", "John");
                    } else if (expr.contains("age")) {
                        return Map.of("__expr__", 30);
                    }
                    return Map.of("__expr__", "unknown");
                });

            List<Core.TransformMapping> mappings = List.of(
                new Core.TransformMapping("user_name", "{{trigger:start.name}}"),
                new Core.TransformMapping("user_age", "{{trigger:start.age}}")
            );

            TransformNode node = new TransformNode("core:transform", mappings);
            node.setTemplateAdapter(mockTemplateAdapter);

            NodeExecutionResult result = node.execute(context);

            Map<String, Object> transformed = transformedOf(result);
            assertEquals("John", transformed.get("user_name"));
            assertEquals(30, transformed.get("user_age"));
        }

        @Test
        @DisplayName("Should handle empty mappings - transformed is empty map, evaluations is empty list")
        void shouldHandleEmptyMappings() {
            TransformNode node = new TransformNode("core:transform", List.of());
            node.setTemplateAdapter(mockTemplateAdapter);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertTrue(transformedOf(result).isEmpty());
            assertTrue(evaluationsOf(result).isEmpty());
            assertEquals("TRANSFORM", result.output().get("node_type"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Mapping validation
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Mapping Validation")
    class ExecuteMappingValidationTests {

        @Test
        @DisplayName("Should skip mapping with null label")
        void shouldSkipMappingWithNullLabel() {
            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenReturn(Map.of("__expr__", "value"));

            List<Core.TransformMapping> mappings = new ArrayList<>();
            mappings.add(new Core.TransformMapping(null, "{{trigger:start.name}}"));
            mappings.add(new Core.TransformMapping("valid_label", "{{trigger:start.age}}"));

            TransformNode node = new TransformNode("core:transform", mappings);
            node.setTemplateAdapter(mockTemplateAdapter);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            Map<String, Object> transformed = transformedOf(result);
            assertFalse(transformed.containsKey(null));
            assertTrue(transformed.containsKey("valid_label"));
            // Skipped mapping does not appear in evaluations either
            assertEquals(1, evaluationsOf(result).size());
        }

        @Test
        @DisplayName("Should skip mapping with blank label")
        void shouldSkipMappingWithBlankLabel() {
            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenReturn(Map.of("__expr__", "value"));

            List<Core.TransformMapping> mappings = new ArrayList<>();
            mappings.add(new Core.TransformMapping("   ", "{{trigger:start.name}}"));
            mappings.add(new Core.TransformMapping("valid_label", "{{trigger:start.age}}"));

            TransformNode node = new TransformNode("core:transform", mappings);
            node.setTemplateAdapter(mockTemplateAdapter);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertFalse(transformedOf(result).containsKey("   "));
        }

        @Test
        @DisplayName("Should set null for mapping with null expression")
        void shouldSetNullForMappingWithNullExpression() {
            List<Core.TransformMapping> mappings = List.of(
                new Core.TransformMapping("output", null)
            );

            TransformNode node = new TransformNode("core:transform", mappings);
            node.setTemplateAdapter(mockTemplateAdapter);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            Map<String, Object> transformed = transformedOf(result);
            assertTrue(transformed.containsKey("output"));
            assertNull(transformed.get("output"));
        }

        @Test
        @DisplayName("Should set null for mapping with blank expression")
        void shouldSetNullForMappingWithBlankExpression() {
            List<Core.TransformMapping> mappings = List.of(
                new Core.TransformMapping("output", "   ")
            );

            TransformNode node = new TransformNode("core:transform", mappings);
            node.setTemplateAdapter(mockTemplateAdapter);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            Map<String, Object> transformed = transformedOf(result);
            assertTrue(transformed.containsKey("output"));
            assertNull(transformed.get("output"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Error handling
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Error Handling")
    class ExecuteErrorHandlingTests {

        @Test
        @DisplayName("Should set null when mapping evaluation fails (per-mapping isolation)")
        void shouldSetNullWhenMappingEvaluationFails() {
            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenThrow(new RuntimeException("Evaluation error"));

            List<Core.TransformMapping> mappings = List.of(
                new Core.TransformMapping("output", "{{invalid.expression}}")
            );

            TransformNode node = new TransformNode("core:transform", mappings);
            node.setTemplateAdapter(mockTemplateAdapter);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            Map<String, Object> transformed = transformedOf(result);
            assertTrue(transformed.containsKey("output"));
            assertNull(transformed.get("output"));
            // Evaluation entry carries the error for inspector visibility
            Map<String, Object> evaluation = evaluationsOf(result).get(0);
            assertEquals("Evaluation error", evaluation.get("error"));
        }

        @Test
        @DisplayName("Should use expression as-is when no templateAdapter")
        void shouldUseExpressionAsIsWhenNoTemplateAdapter() {
            List<Core.TransformMapping> mappings = List.of(
                new Core.TransformMapping("output", "{{trigger:start.name}}")
            );

            TransformNode node = new TransformNode("core:transform", mappings);
            // Note: not setting templateAdapter

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            // Without adapter, expression is stored as-is under 'transformed'
            assertEquals("{{trigger:start.name}}", transformedOf(result).get("output"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getNextNodes() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return all successors on success")
        void shouldReturnAllSuccessorsOnSuccess() {
            TransformNode node = new TransformNode("core:transform", List.of());

            ExecutionNode successor1 = createMockNode("mcp:next1");
            ExecutionNode successor2 = createMockNode("mcp:next2");
            node.addSuccessor(successor1);
            node.addSuccessor(successor2);

            NodeExecutionResult result = NodeExecutionResult.success("core:transform", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(2, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty list on failure")
        void shouldReturnEmptyListOnFailure() {
            TransformNode node = new TransformNode("core:transform", List.of());

            ExecutionNode successor = createMockNode("mcp:next");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.failure("core:transform", "Error");

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertTrue(nextNodes.isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Getters tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Getters")
    class GettersTests {

        @Test
        @DisplayName("getMappings() should return mappings")
        void getMappingsShouldReturnMappings() {
            List<Core.TransformMapping> mappings = List.of(
                new Core.TransformMapping("a", "{{x}}"),
                new Core.TransformMapping("b", "{{y}}"),
                new Core.TransformMapping("c", "{{z}}")
            );

            TransformNode node = new TransformNode("core:transform", mappings);

            assertEquals(3, node.getMappings().size());
            assertEquals("a", node.getMappings().get(0).label());
            assertEquals("{{x}}", node.getMappings().get(0).expression());
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
            TransformNode node = new TransformNode("core:transform", List.of());

            NodeExecutionResult result = NodeExecutionResult.success("core:transform", Map.of());

            assertDoesNotThrow(() -> node.onComplete(context, result));
        }

        @Test
        @DisplayName("Should not throw exception on failure result")
        void shouldNotThrowExceptionOnFailureResult() {
            TransformNode node = new TransformNode("core:transform", List.of());

            NodeExecutionResult result = NodeExecutionResult.failure("core:transform", "Error");

            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Multiple mappings tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Multiple Mappings")
    class MultipleMappingsTests {

        @Test
        @DisplayName("Should apply all mappings under 'transformed'")
        void shouldApplyAllMappings() {
            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> input = invocation.getArgument(0);
                    String expr = (String) input.get("__expr__");
                    return Map.of("__expr__", "resolved_" + expr);
                });

            List<Core.TransformMapping> mappings = List.of(
                new Core.TransformMapping("field1", "expr1"),
                new Core.TransformMapping("field2", "expr2"),
                new Core.TransformMapping("field3", "expr3")
            );

            TransformNode node = new TransformNode("core:transform", mappings);
            node.setTemplateAdapter(mockTemplateAdapter);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            Map<String, Object> transformed = transformedOf(result);
            assertEquals(3, transformed.size());
            assertEquals("resolved_expr1", transformed.get("field1"));
            assertEquals("resolved_expr2", transformed.get("field2"));
            assertEquals("resolved_expr3", transformed.get("field3"));
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
