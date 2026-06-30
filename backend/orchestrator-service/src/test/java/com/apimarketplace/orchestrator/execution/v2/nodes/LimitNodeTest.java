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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

/**
 * Unit tests for LimitNode.
 * LimitNode passes through only the first or last N items with optional offset.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LimitNode")
class LimitNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private V2TemplateAdapter mockTemplateAdapter;

    private ExecutionContext context;

    private static final List<Object> TEN_ITEMS = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j");

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("items", TEN_ITEMS);

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

    /**
     * Helper: build a LimitNode with inputExpression and a wired templateAdapter
     * that resolves to the given items.
     */
    private LimitNode buildNode(int count, String from, int offset, List<Object> resolvedItems) {
        LimitNode node = LimitNode.builder()
            .nodeId("core:limit")
            .count(count)
            .from(from)
            .offset(offset)
            .inputExpression("{{items}}")
            .build();
        node.setTemplateAdapter(mockTemplateAdapter);

        when(mockTemplateAdapter.resolveTemplates(anyMap(), any()))
            .thenReturn(Map.of("__input__", resolvedItems));

        return node;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create LimitNode with explicit parameters")
        void shouldCreateLimitNodeWithExplicitParameters() {
            LimitNode node = new LimitNode("core:limit", 5, "first", 2);

            assertEquals("core:limit", node.getNodeId());
            assertEquals(NodeType.LIMIT, node.getType());
            assertEquals(5, node.getCount());
            assertEquals("first", node.getFrom());
            assertEquals(2, node.getOffset());
        }

        @Test
        @DisplayName("Should create LimitNode from LimitConfig")
        void shouldCreateLimitNodeFromLimitConfig() {
            Core.LimitConfig config = new Core.LimitConfig(3, "last", 1, null);
            LimitNode node = new LimitNode("core:limit", config);

            assertEquals(3, node.getCount());
            assertEquals("last", node.getFrom());
            assertEquals(1, node.getOffset());
        }

        @Test
        @DisplayName("Should use defaults for null values")
        void shouldUseDefaultsForNullValues() {
            LimitNode node = new LimitNode("core:limit", 5, null, 0);

            assertEquals("first", node.getFrom());
        }

        @Test
        @DisplayName("Should enforce minimum count of 1")
        void shouldEnforceMinimumCountOfOne() {
            LimitNode node = new LimitNode("core:limit", 0, "first", 0);

            assertEquals(1, node.getCount());
        }

        @Test
        @DisplayName("Should enforce minimum offset of 0")
        void shouldEnforceMinimumOffsetOfZero() {
            LimitNode node = new LimitNode("core:limit", 5, "first", -3);

            assertEquals(0, node.getOffset());
        }

        @Test
        @DisplayName("Should create LimitNode using builder")
        void shouldCreateLimitNodeUsingBuilder() {
            LimitNode node = LimitNode.builder()
                .nodeId("core:my_limit")
                .count(7)
                .from("last")
                .offset(3)
                .inputExpression("{{data}}")
                .build();

            assertEquals("core:my_limit", node.getNodeId());
            assertEquals(7, node.getCount());
            assertEquals("last", node.getFrom());
            assertEquals(3, node.getOffset());
            assertEquals("{{data}}", node.getInputExpression());
        }

        @Test
        @DisplayName("Should create LimitNode with builder defaults")
        void shouldCreateLimitNodeWithBuilderDefaults() {
            LimitNode node = LimitNode.builder()
                .nodeId("core:limit")
                .build();

            assertEquals(10, node.getCount());
            assertEquals("first", node.getFrom());
            assertEquals(0, node.getOffset());
        }

        @Test
        @DisplayName("Should handle null LimitConfig")
        void shouldHandleNullLimitConfig() {
            LimitNode node = new LimitNode("core:limit", (Core.LimitConfig) null);

            assertEquals(10, node.getCount());
            assertEquals("first", node.getFrom());
            assertEquals(0, node.getOffset());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Input expression validation
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Input Expression Validation")
    class InputExpressionValidationTests {

        @Test
        @DisplayName("Should fail when inputExpression is null")
        void shouldFailWhenInputExpressionIsNull() {
            LimitNode node = LimitNode.builder()
                .nodeId("core:limit")
                .count(5)
                .build(); // inputExpression defaults to null

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("Input expression is required"));
        }

        @Test
        @DisplayName("Should fail when inputExpression is blank")
        void shouldFailWhenInputExpressionIsBlank() {
            LimitNode node = LimitNode.builder()
                .nodeId("core:limit")
                .count(5)
                .inputExpression("   ")
                .build();

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("Input expression is required"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Limit first N items
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Limit First N Items")
    class LimitFirstTests {

        @Test
        @DisplayName("Should limit first 3 items")
        void shouldLimitFirstThreeItems() {
            LimitNode node = buildNode(3, "first", 0, TEN_ITEMS);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) result.output().get("items");
            assertEquals(3, items.size());
            assertEquals(List.of("a", "b", "c"), items);
            assertEquals(3, result.output().get("count"));
            assertEquals(10, result.output().get("original_count"));
        }

        @Test
        @DisplayName("Should return all items when count exceeds available")
        void shouldReturnAllItemsWhenCountExceedsAvailable() {
            LimitNode node = buildNode(100, "first", 0, TEN_ITEMS);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) result.output().get("items");
            assertEquals(10, items.size());
        }

        @Test
        @DisplayName("Should return single item when count is 1")
        void shouldReturnSingleItemWhenCountIsOne() {
            LimitNode node = buildNode(1, "first", 0, TEN_ITEMS);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) result.output().get("items");
            assertEquals(1, items.size());
            assertEquals("a", items.get(0));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Limit last N items
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Limit Last N Items")
    class LimitLastTests {

        @Test
        @DisplayName("Should limit last 3 items")
        void shouldLimitLastThreeItems() {
            LimitNode node = buildNode(3, "last", 0, TEN_ITEMS);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) result.output().get("items");
            assertEquals(3, items.size());
            assertEquals(List.of("h", "i", "j"), items);
        }

        @Test
        @DisplayName("Should return all items when last count exceeds available")
        void shouldReturnAllItemsWhenLastCountExceedsAvailable() {
            LimitNode node = buildNode(50, "last", 0, TEN_ITEMS);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) result.output().get("items");
            assertEquals(10, items.size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Offset + Limit
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Offset + Limit")
    class OffsetLimitTests {

        @Test
        @DisplayName("Should apply offset then limit first items")
        void shouldApplyOffsetThenLimitFirstItems() {
            LimitNode node = buildNode(3, "first", 2, TEN_ITEMS);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) result.output().get("items");
            assertEquals(3, items.size());
            assertEquals(List.of("c", "d", "e"), items);
        }

        @Test
        @DisplayName("Should apply offset then limit last items")
        void shouldApplyOffsetThenLimitLastItems() {
            LimitNode node = buildNode(3, "last", 2, TEN_ITEMS);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) result.output().get("items");
            assertEquals(3, items.size());
            // After offset 2: [c,d,e,f,g,h,i,j] -> last 3: [h,i,j]
            assertEquals(List.of("h", "i", "j"), items);
        }

        @Test
        @DisplayName("Should return empty list when offset exceeds items")
        void shouldReturnEmptyListWhenOffsetExceedsItems() {
            LimitNode node = buildNode(5, "first", 100, TEN_ITEMS);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) result.output().get("items");
            assertTrue(items.isEmpty());
            assertEquals(0, result.output().get("count"));
        }

        @Test
        @DisplayName("Should handle zero offset correctly")
        void shouldHandleZeroOffsetCorrectly() {
            LimitNode node = buildNode(5, "first", 0, TEN_ITEMS);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) result.output().get("items");
            assertEquals(5, items.size());
            assertEquals(List.of("a", "b", "c", "d", "e"), items);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Metadata fields
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Metadata Fields")
    class MetadataTests {

        @Test
        @DisplayName("Should include mandatory metadata fields")
        void shouldIncludeMandatoryMetadataFields() {
            LimitNode node = buildNode(5, "first", 0, TEN_ITEMS);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("LIMIT", result.output().get("node_type"));
            assertEquals(0, result.output().get("item_index"));
            assertEquals(0, result.output().get("itemIndex"));
            assertEquals("item-1", result.output().get("item_id"));
        }

        @Test
        @DisplayName("Should include resolved_params with configuration")
        void shouldIncludeInputDataWithConfiguration() {
            LimitNode node = buildNode(5, "first", 2, TEN_ITEMS);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> inputData = (Map<String, Object>) result.output().get("resolved_params");
            assertNotNull(inputData);
            assertEquals(5, inputData.get("count"));
            assertEquals("first", inputData.get("from"));
            assertEquals(2, inputData.get("offset"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Empty input
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Empty Input")
    class EmptyInputTests {

        @Test
        @DisplayName("Should return empty list when resolved input is empty")
        void shouldReturnEmptyListWhenResolvedInputIsEmpty() {
            LimitNode node = buildNode(5, "first", 0, List.of());

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) result.output().get("items");
            assertTrue(items.isEmpty());
            assertEquals(0, result.output().get("count"));
            assertEquals(0, result.output().get("original_count"));
        }

        @Test
        @DisplayName("Should succeed with empty items when resolved input is null (not List nor Map)")
        void shouldReturnEmptyListWhenResolvedInputIsNull() {
            // Wire the adapter to resolve __input__ to a null value (e.g. an
            // unresolvable reference). null is neither a List nor a Map, so the
            // else fall-through must coerce it to an empty list, not throw.
            LimitNode node = LimitNode.builder()
                .nodeId("core:limit")
                .count(5)
                .from("first")
                .offset(0)
                .inputExpression("{{items}}")
                .build();
            node.setTemplateAdapter(mockTemplateAdapter);

            // Map.of rejects null values, so use a HashMap to hold null __input__.
            Map<String, Object> resolvedWithNull = new HashMap<>();
            resolvedWithNull.put("__input__", null);
            when(mockTemplateAdapter.resolveTemplates(anyMap(), any()))
                .thenReturn(resolvedWithNull);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess(), "null resolved input must be handled gracefully, not fail");
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) result.output().get("items");
            assertTrue(items.isEmpty(), "null input must coerce to an empty items list");
            assertEquals(0, result.output().get("count"));
            assertEquals(0, result.output().get("original_count"),
                "original_count must be 0 when input resolved to null");
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
            LimitNode node = new LimitNode("core:limit", 5, "first", 0);

            ExecutionNode successor1 = createMockNode("mcp:next1");
            ExecutionNode successor2 = createMockNode("mcp:next2");
            node.addSuccessor(successor1);
            node.addSuccessor(successor2);

            NodeExecutionResult result = NodeExecutionResult.success("core:limit", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertEquals(2, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty list on failure")
        void shouldReturnEmptyListOnFailure() {
            LimitNode node = new LimitNode("core:limit", 5, "first", 0);

            ExecutionNode successor = createMockNode("mcp:next");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.failure("core:limit", "Error");

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertTrue(nextNodes.isEmpty());
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
            LimitNode node = new LimitNode("core:limit", 5, "first", 0);
            NodeExecutionResult result = NodeExecutionResult.success("core:limit", Map.of());

            assertDoesNotThrow(() -> node.onComplete(context, result));
        }

        @Test
        @DisplayName("Should not throw exception on failure result")
        void shouldNotThrowExceptionOnFailureResult() {
            LimitNode node = new LimitNode("core:limit", 5, "first", 0);
            NodeExecutionResult result = NodeExecutionResult.failure("core:limit", "Error");

            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Regression: execute() output shape matches persisted shape
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("executeOutputIncludesConfig: runtime shape == persisted shape")
    class ExecuteOutputShapeTests {

        @Test
        @DisplayName("executeOutputIncludesConfig: execute() keyset and LimitNodeSpec.customTransform() keyset are aligned - 'config' key present and no dropped fields")
        void executeOutputIncludesConfig() {
            LimitNode node = buildNode(5, "first", 2, TEN_ITEMS);

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isSuccess());
            Map<String, Object> executeOutput = result.output();

            // Invoke the spec's customTransform so we verify the persisted shape
            LimitNodeSpec spec = new LimitNodeSpec();
            Map<String, Object> persistedOutput = spec.customTransform(executeOutput);

            // The canonical B3 keys must be present in the persisted shape
            assertNotNull(persistedOutput.get("items"),          "items must be in persisted shape");
            assertNotNull(persistedOutput.get("count"),          "count must be in persisted shape");
            assertNotNull(persistedOutput.get("original_count"), "original_count must be in persisted shape");
            @SuppressWarnings("unchecked")
            Map<String, Object> config = (Map<String, Object>) persistedOutput.get("config");
            assertNotNull(config, "execute() must emit 'config' so runtime shape equals persisted shape");

            // config must carry the limit parameters
            assertEquals(5, config.get("count"));
            assertEquals("first", config.get("from"));
            assertEquals(2, config.get("offset"));

            // customTransform must strip engine-envelope keys from execute() output
            assertFalse(persistedOutput.containsKey("node_type"),    "persisted output must NOT contain engine key node_type");
            assertFalse(persistedOutput.containsKey("item_index"),   "persisted output must NOT contain engine key item_index");
            assertFalse(persistedOutput.containsKey("itemIndex"),    "persisted output must NOT contain engine key itemIndex");
            assertFalse(persistedOutput.containsKey("item_id"),      "persisted output must NOT contain engine key item_id");
            assertFalse(persistedOutput.containsKey("resolved_params"), "persisted output must NOT contain engine key resolved_params");
        }

        @Test
        @DisplayName("failureOutputContainsCanonicalKeys: persisted output on failure must contain items/count/original_count/config with defaults, not empty JSONB")
        void failureOutputContainsCanonicalKeys() {
            // Trigger the early-return failure path: no inputExpression configured
            LimitNode node = LimitNode.builder()
                .nodeId("core:limit")
                .count(5)
                .build(); // inputExpression is null → early failure

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess(), "Expected failure when inputExpression is null");

            LimitNodeSpec spec = new LimitNodeSpec();
            Map<String, Object> persistedOutput = spec.customTransform(result.output());

            // Canonical keys must be present with safe defaults (not empty JSONB)
            assertTrue(persistedOutput.containsKey("items"),          "failure output must contain 'items'");
            assertTrue(persistedOutput.containsKey("count"),          "failure output must contain 'count'");
            assertTrue(persistedOutput.containsKey("original_count"), "failure output must contain 'original_count'");
            assertTrue(persistedOutput.containsKey("config"),         "failure output must contain 'config'");

            // Default values
            @SuppressWarnings("unchecked")
            java.util.List<?> items = (java.util.List<?>) persistedOutput.get("items");
            assertTrue(items.isEmpty(), "items must be empty list on failure");
            assertEquals(0, persistedOutput.get("count"),          "count must be 0 on failure");
            assertEquals(0, persistedOutput.get("original_count"), "original_count must be 0 on failure");
            assertNotNull(persistedOutput.get("config"),           "config must not be null on failure");

            // Engine-envelope key stripped
            assertFalse(persistedOutput.containsKey("resolved_params"),
                "resolved_params is an engine-envelope key and must NOT appear in persisted output");
        }

        @Test
        @DisplayName("configCarriesConfigurationData: config key (persisted via LimitNodeSpec) carries the limit configuration; resolved_params is stripped as an engine-envelope key")
        void configCarriesConfigurationData() {
            LimitNode node = buildNode(3, "last", 1, TEN_ITEMS);

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isSuccess());

            // Use LimitNodeSpec to produce the persisted shape
            LimitNodeSpec spec = new LimitNodeSpec();
            Map<String, Object> persistedOutput = spec.customTransform(result.output());

            @SuppressWarnings("unchecked")
            Map<String, Object> config = (Map<String, Object>) persistedOutput.get("config");

            assertNotNull(config, "config key must be present in persisted output");
            // resolved_params is an engine-envelope key and must be stripped from persisted output
            assertFalse(persistedOutput.containsKey("resolved_params"),
                    "resolved_params is an engine-envelope key and must NOT appear in persisted output");
            // config carries the limit parameters
            assertEquals(3, config.get("count"),  "config.count must match the configured limit");
            assertEquals("last", config.get("from"), "config.from must match the configured direction");
            assertEquals(1, config.get("offset"), "config.offset must match the configured offset");
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
