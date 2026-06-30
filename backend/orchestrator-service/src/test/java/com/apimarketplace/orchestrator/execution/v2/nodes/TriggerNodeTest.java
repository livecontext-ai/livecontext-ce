package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TriggerNode.
 * TriggerNode is the entry point of workflow execution.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TriggerNode")
class TriggerNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private V2TemplateAdapter mockTemplateAdapter;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("user_id", 123);
        triggerData.put("data", Map.of("name", "Test User"));

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
        @DisplayName("Should create TriggerNode with Trigger object")
        void shouldCreateTriggerNodeWithTriggerObject() {
            Trigger trigger = new Trigger("trig-1", "Webhook", "single", "webhook");
            TriggerNode node = new TriggerNode("trigger:webhook", trigger);

            assertEquals("trigger:webhook", node.getNodeId());
            assertEquals(NodeType.TRIGGER, node.getType());
            assertEquals("trig-1", node.getTriggerId());
        }

        @Test
        @DisplayName("Should create TriggerNode with triggerId only (legacy)")
        void shouldCreateTriggerNodeWithTriggerIdOnly() {
            TriggerNode node = new TriggerNode("trigger:webhook", "trig-123");

            assertEquals("trigger:webhook", node.getNodeId());
            assertEquals(NodeType.TRIGGER, node.getType());
            assertEquals("trig-123", node.getTriggerId());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // canExecute() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("canExecute()")
    class CanExecuteTests {

        @Test
        @DisplayName("Should always return true (trigger is entry point)")
        void shouldAlwaysReturnTrue() {
            Trigger trigger = new Trigger("trig-1", "Webhook", "single", "webhook");
            TriggerNode node = new TriggerNode("trigger:webhook", trigger);

            assertTrue(node.canExecute(context));
        }

        @Test
        @DisplayName("Should return true even with predecessors set")
        void shouldReturnTrueEvenWithPredecessorsSet() {
            Trigger trigger = new Trigger("trig-1", "Webhook", "single", "webhook");
            TriggerNode node = new TriggerNode("trigger:webhook", trigger);
            node.addPredecessor("some:predecessor");

            assertTrue(node.canExecute(context));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute()")
    class ExecuteTests {

        @Test
        @DisplayName("Should return success result")
        void shouldReturnSuccessResult() {
            Trigger trigger = new Trigger("trig-1", "Webhook", "single", "webhook");
            TriggerNode node = new TriggerNode("trigger:webhook", trigger);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("trigger:webhook", result.nodeId());
        }

        @Test
        @DisplayName("Should include trigger_id in output")
        void shouldIncludeTriggerIdInOutput() {
            Trigger trigger = new Trigger("trig-1", "Webhook", "single", "webhook");
            TriggerNode node = new TriggerNode("trigger:webhook", trigger);

            NodeExecutionResult result = node.execute(context);

            assertEquals("trig-1", result.output().get("trigger_id"));
        }

        @Test
        @DisplayName("Should include data from trigger_data in output")
        void shouldIncludeTriggerDataInOutput() {
            Trigger trigger = new Trigger("trig-1", "Webhook", "single", "webhook");
            TriggerNode node = new TriggerNode("trigger:webhook", trigger);

            NodeExecutionResult result = node.execute(context);

            // TriggerNode copies specific fields from triggerData to output
            // The 'data' field from triggerData is included at output.data
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.output().get("data");
            assertNotNull(data);
            assertEquals("Test User", data.get("name"));
        }

        @Test
        @DisplayName("Should include item_id in output")
        void shouldIncludeItemIdInOutput() {
            Trigger trigger = new Trigger("trig-1", "Webhook", "single", "webhook");
            TriggerNode node = new TriggerNode("trigger:webhook", trigger);

            NodeExecutionResult result = node.execute(context);

            assertEquals("item-1", result.output().get("item_id"));
        }

        @Test
        @DisplayName("Should include item_index in output")
        void shouldIncludeItemIndexInOutput() {
            Trigger trigger = new Trigger("trig-1", "Webhook", "single", "webhook");
            TriggerNode node = new TriggerNode("trigger:webhook", trigger);

            NodeExecutionResult result = node.execute(context);

            assertEquals(0, result.output().get("item_index"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Trigger input resolution tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Trigger Input Resolution")
    class TriggerInputResolutionTests {

        @Test
        @DisplayName("Should resolve trigger.input templates when templateAdapter available")
        void shouldResolveTriggerInputTemplates() {
            Map<String, Object> triggerInput = new HashMap<>();
            triggerInput.put("user_id", "${int(current_item.data.user_id)}");

            Trigger trigger = new Trigger("trig-1", "Webhook", "single", "webhook", triggerInput);
            TriggerNode node = new TriggerNode("trigger:webhook", trigger);
            node.setTemplateAdapter(mockTemplateAdapter);

            // Mock the template resolution
            Map<String, Object> resolvedInput = Map.of("user_id", 42);
            when(mockTemplateAdapter.resolveTemplates(eq(triggerInput), any(ExecutionContext.class)))
                .thenReturn(resolvedInput);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(42, result.output().get("user_id"));
            verify(mockTemplateAdapter).resolveTemplates(eq(triggerInput), any());
        }

        @Test
        @DisplayName("Should not fail when templateAdapter is null")
        void shouldNotFailWhenTemplateAdapterIsNull() {
            Map<String, Object> triggerInput = Map.of("resolved_field", "${current_item.data.user_id}");
            Trigger trigger = new Trigger("trig-1", "Webhook", "single", "webhook", triggerInput);
            TriggerNode node = new TriggerNode("trigger:webhook", trigger);
            // No templateAdapter set

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            // No resolved inputs should be added (resolved_field is only in params, not in triggerData)
            assertFalse(result.output().containsKey("resolved_field"));
        }

        @Test
        @DisplayName("Should not fail when trigger.input is null")
        void shouldNotFailWhenTriggerInputIsNull() {
            Trigger trigger = new Trigger("trig-1", "Webhook", "single", "webhook");
            TriggerNode node = new TriggerNode("trigger:webhook", trigger);
            node.setTemplateAdapter(mockTemplateAdapter);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            verify(mockTemplateAdapter, never()).resolveTemplates(any(), any());
        }

        @Test
        @DisplayName("Should not fail when trigger.input is empty")
        void shouldNotFailWhenTriggerInputIsEmpty() {
            Trigger trigger = new Trigger("trig-1", "Webhook", "single", "webhook", Map.of());
            TriggerNode node = new TriggerNode("trigger:webhook", trigger);
            node.setTemplateAdapter(mockTemplateAdapter);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            verify(mockTemplateAdapter, never()).resolveTemplates(any(), any());
        }

        @Test
        @DisplayName("Should handle template resolution exception gracefully")
        void shouldHandleTemplateResolutionExceptionGracefully() {
            Map<String, Object> triggerInput = Map.of("bad_expr", "${invalid}");
            Trigger trigger = new Trigger("trig-1", "Webhook", "single", "webhook", triggerInput);
            TriggerNode node = new TriggerNode("trigger:webhook", trigger);
            node.setTemplateAdapter(mockTemplateAdapter);

            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenThrow(new RuntimeException("Template error"));

            NodeExecutionResult result = node.execute(context);

            // Should still succeed - template resolution error is logged but not fatal
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Should work with legacy constructor (no Trigger object)")
        void shouldWorkWithLegacyConstructor() {
            TriggerNode node = new TriggerNode("trigger:webhook", "trig-123");

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("trig-123", result.output().get("trigger_id"));
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
            Trigger trigger = new Trigger("trig-1", "Webhook", "single", "webhook");
            TriggerNode node = new TriggerNode("trigger:webhook", trigger);

            BaseNode successor1 = createSuccessorNode("mcp:step1");
            BaseNode successor2 = createSuccessorNode("mcp:step2");
            node.addSuccessor(successor1);
            node.addSuccessor(successor2);

            NodeExecutionResult result = NodeExecutionResult.success(node.getNodeId(), Map.of());
            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(2, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty list on failure")
        void shouldReturnEmptyListOnFailure() {
            Trigger trigger = new Trigger("trig-1", "Webhook", "single", "webhook");
            TriggerNode node = new TriggerNode("trigger:webhook", trigger);
            node.addSuccessor(createSuccessorNode("mcp:step1"));

            NodeExecutionResult result = NodeExecutionResult.failure(node.getNodeId(), "Error");
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
        @DisplayName("Should not throw exception")
        void shouldNotThrowException() {
            Trigger trigger = new Trigger("trig-1", "Webhook", "single", "webhook");
            TriggerNode node = new TriggerNode("trigger:webhook", trigger);
            NodeExecutionResult result = NodeExecutionResult.success(node.getNodeId(), Map.of());

            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getTriggerId() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getTriggerId()")
    class GetTriggerIdTests {

        @Test
        @DisplayName("Should return triggerId from Trigger object")
        void shouldReturnTriggerIdFromTriggerObject() {
            Trigger trigger = new Trigger("my-trigger-id", "Webhook", "single", "webhook");
            TriggerNode node = new TriggerNode("trigger:webhook", trigger);

            assertEquals("my-trigger-id", node.getTriggerId());
        }

        @Test
        @DisplayName("Should return triggerId from legacy constructor")
        void shouldReturnTriggerIdFromLegacyConstructor() {
            TriggerNode node = new TriggerNode("trigger:webhook", "legacy-id");

            assertEquals("legacy-id", node.getTriggerId());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper methods
    // ═══════════════════════════════════════════════════════════════════════════

    private BaseNode createSuccessorNode(String nodeId) {
        return new BaseNode(nodeId, NodeType.MCP) {
            @Override
            public NodeExecutionResult execute(ExecutionContext context) {
                return NodeExecutionResult.success(nodeId, Map.of());
            }
        };
    }
}
