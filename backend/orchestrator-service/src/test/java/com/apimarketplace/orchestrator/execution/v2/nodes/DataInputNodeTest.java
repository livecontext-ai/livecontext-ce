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
import static org.mockito.Mockito.when;

/**
 * Unit tests for DataInputNode (multi-item version).
 * DataInputNode provides multiple labeled text/file inputs to downstream nodes.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DataInputNode")
class DataInputNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private V2TemplateAdapter mockTemplateAdapter;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("name", "John");
        triggerData.put("query", "Analyse this document");

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
        @DisplayName("Should have DATA_INPUT node type")
        void shouldHaveDataInputNodeType() {
            DataInputNode node = new DataInputNode("core:my_prompt", List.of(
                new Core.DataInputItem("item_1", "prompt", "text", "Hello", null)
            ));

            assertEquals(NodeType.DATA_INPUT, node.getType());
        }

        @Test
        @DisplayName("Should store items list")
        void shouldStoreItemsList() {
            List<Core.DataInputItem> items = List.of(
                new Core.DataInputItem("item_1", "prompt", "text", "Hello", null),
                new Core.DataInputItem("item_2", "document", "file", "", Map.of("path", "s3://bucket/file.pdf", "name", "file.pdf"))
            );

            DataInputNode node = new DataInputNode("core:my_prompt", items);

            assertEquals(2, node.getItems().size());
            assertEquals("prompt", node.getItems().get(0).label());
            assertEquals("document", node.getItems().get(1).label());
        }

        @Test
        @DisplayName("Should handle null items gracefully")
        void shouldHandleNullItemsGracefully() {
            DataInputNode node = new DataInputNode("core:my_prompt", null);

            assertNotNull(node.getItems());
            assertTrue(node.getItems().isEmpty());
        }

        @Test
        @DisplayName("Should create using builder")
        void shouldCreateUsingBuilder() {
            DataInputNode node = DataInputNode.builder()
                .nodeId("core:my_input")
                .items(List.of(
                    new Core.DataInputItem("item_1", "prompt", "text", "Hello world", null)
                ))
                .build();

            assertEquals("core:my_input", node.getNodeId());
            assertEquals(1, node.getItems().size());
            assertEquals("Hello world", node.getItems().get(0).text());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Multi-item
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Multi-item")
    class ExecuteMultiItemTests {

        @Test
        @DisplayName("Should return label-keyed output for 2 text + 1 file")
        void shouldReturnLabelKeyedOutput() {
            Map<String, Object> file = Map.of("path", "s3://bucket/file.pdf", "name", "file.pdf", "mimeType", "application/pdf");

            DataInputNode node = new DataInputNode("core:my_input", List.of(
                new Core.DataInputItem("item_1", "prompt", "text", "Analyse this", null),
                new Core.DataInputItem("item_2", "context", "text", "More context here", null),
                new Core.DataInputItem("item_3", "document", "file", "", file)
            ));
            node.setTemplateAdapter(mockTemplateAdapter);

            // Mock template resolution: return the text as-is for simplicity
            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> toResolve = (Map<String, Object>) inv.getArgument(0);
                    return Map.of("__expr__", toResolve.get("__expr__"));
                });

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("Analyse this", result.output().get("prompt"));
            assertEquals("More context here", result.output().get("context"));
            @SuppressWarnings("unchecked")
            Map<String, Object> outputFile = (Map<String, Object>) result.output().get("document");
            assertNotNull(outputFile);
            assertEquals("file.pdf", outputFile.get("name"));
        }

        @Test
        @DisplayName("Should return only metadata for empty items")
        void shouldReturnOnlyMetadataForEmptyItems() {
            DataInputNode node = new DataInputNode("core:my_input", List.of());
            node.setTemplateAdapter(mockTemplateAdapter);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("DATA_INPUT", result.output().get("node_type"));
            // No item keys expected
            assertFalse(result.output().containsKey("prompt"));
        }

        @Test
        @DisplayName("Should handle null file in file item")
        void shouldHandleNullFileInFileItem() {
            DataInputNode node = new DataInputNode("core:my_input", List.of(
                new Core.DataInputItem("item_1", "attachment", "file", "", null)
            ));
            node.setTemplateAdapter(mockTemplateAdapter);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> outputFile = (Map<String, Object>) result.output().get("attachment");
            assertNotNull(outputFile);
            assertTrue(outputFile.isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - SpEL Resolution
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - SpEL Resolution per text item")
    class ExecuteSpelResolutionTests {

        @Test
        @DisplayName("Should resolve SpEL expression in each text item")
        void shouldResolveSpelExpressionPerItem() {
            DataInputNode node = new DataInputNode("core:my_input", List.of(
                new Core.DataInputItem("item_1", "greeting", "text", "Hello {{trigger:start.output.name}}", null),
                new Core.DataInputItem("item_2", "query", "text", "{{trigger:start.output.query}}", null)
            ));
            node.setTemplateAdapter(mockTemplateAdapter);

            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenReturn(Map.of("__expr__", "Hello John"))
                .thenReturn(Map.of("__expr__", "Analyse this document"));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("Hello John", result.output().get("greeting"));
            assertEquals("Analyse this document", result.output().get("query"));
        }

        @Test
        @DisplayName("Should return raw expression when no templateAdapter")
        void shouldReturnRawExpressionWhenNoTemplateAdapter() {
            DataInputNode node = new DataInputNode("core:my_input", List.of(
                new Core.DataInputItem("item_1", "prompt", "text", "{{trigger:start.output.name}}", null)
            ));
            // Not setting templateAdapter

            NodeExecutionResult result = node.execute(context);

            assertEquals("{{trigger:start.output.name}}", result.output().get("prompt"));
        }

        @Test
        @DisplayName("Should handle resolution failure gracefully")
        void shouldHandleResolutionFailureGracefully() {
            DataInputNode node = new DataInputNode("core:my_input", List.of(
                new Core.DataInputItem("item_1", "prompt", "text", "{{invalid.expression}}", null)
            ));
            node.setTemplateAdapter(mockTemplateAdapter);

            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenThrow(new RuntimeException("Resolution error"));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("{{invalid.expression}}", result.output().get("prompt"));
        }

        @Test
        @DisplayName("Should handle blank text as empty string")
        void shouldHandleBlankText() {
            DataInputNode node = new DataInputNode("core:my_input", List.of(
                new Core.DataInputItem("item_1", "prompt", "text", "   ", null)
            ));
            node.setTemplateAdapter(mockTemplateAdapter);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("", result.output().get("prompt"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Metadata
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Metadata")
    class ExecuteMetadataTests {

        @Test
        @DisplayName("Should include node_type=DATA_INPUT")
        void shouldIncludeNodeType() {
            DataInputNode node = new DataInputNode("core:my_input", List.of(
                new Core.DataInputItem("item_1", "prompt", "text", "Hello", null)
            ));
            node.setTemplateAdapter(mockTemplateAdapter);

            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenReturn(Map.of("__expr__", "Hello"));

            NodeExecutionResult result = node.execute(context);

            assertEquals("DATA_INPUT", result.output().get("node_type"));
        }

        @Test
        @DisplayName("Should include item_index and item_id")
        void shouldIncludeItemIndexAndItemId() {
            DataInputNode node = new DataInputNode("core:my_input", List.of(
                new Core.DataInputItem("item_1", "prompt", "text", "Hello", null)
            ));
            node.setTemplateAdapter(mockTemplateAdapter);

            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenReturn(Map.of("__expr__", "Hello"));

            NodeExecutionResult result = node.execute(context);

            assertEquals(0, result.output().get("item_index"));
            assertEquals(0, result.output().get("itemIndex"));
            assertEquals("item-1", result.output().get("item_id"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getNextNodes()
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return all successors on success")
        void shouldReturnAllSuccessorsOnSuccess() {
            DataInputNode node = new DataInputNode("core:my_input", List.of());

            ExecutionNode successor1 = createMockNode("mcp:next1");
            ExecutionNode successor2 = createMockNode("mcp:next2");
            node.addSuccessor(successor1);
            node.addSuccessor(successor2);

            NodeExecutionResult result = NodeExecutionResult.success("core:my_input", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(2, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty list on failure")
        void shouldReturnEmptyListOnFailure() {
            DataInputNode node = new DataInputNode("core:my_input", List.of());

            ExecutionNode successor = createMockNode("mcp:next");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.failure("core:my_input", "Error");

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertTrue(nextNodes.isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // onComplete()
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("onComplete()")
    class OnCompleteTests {

        @Test
        @DisplayName("Should not throw on success")
        void shouldNotThrowOnSuccess() {
            DataInputNode node = new DataInputNode("core:my_input", List.of());

            NodeExecutionResult result = NodeExecutionResult.success("core:my_input", Map.of());

            assertDoesNotThrow(() -> node.onComplete(context, result));
        }

        @Test
        @DisplayName("Should not throw on failure")
        void shouldNotThrowOnFailure() {
            DataInputNode node = new DataInputNode("core:my_input", List.of());

            NodeExecutionResult result = NodeExecutionResult.failure("core:my_input", "Error");

            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Builder
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should build with items")
        void shouldBuildWithItems() {
            Map<String, Object> file = Map.of("path", "s3://bucket/file.pdf", "name", "file.pdf");

            DataInputNode node = DataInputNode.builder()
                .nodeId("core:my_input")
                .items(List.of(
                    new Core.DataInputItem("item_1", "prompt", "text", "Analyse {{trigger:start.output.name}}", null),
                    new Core.DataInputItem("item_2", "document", "file", "", file)
                ))
                .build();

            assertEquals("core:my_input", node.getNodeId());
            assertEquals(2, node.getItems().size());
            assertEquals("prompt", node.getItems().get(0).label());
            assertEquals("document", node.getItems().get(1).label());
        }

        @Test
        @DisplayName("Should build with defaults (null items)")
        void shouldBuildWithDefaults() {
            DataInputNode node = DataInputNode.builder()
                .nodeId("core:empty_input")
                .build();

            assertEquals("core:empty_input", node.getNodeId());
            assertNotNull(node.getItems());
            assertTrue(node.getItems().isEmpty());
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
