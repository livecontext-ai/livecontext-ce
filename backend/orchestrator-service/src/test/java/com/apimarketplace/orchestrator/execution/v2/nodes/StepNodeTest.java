package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import com.apimarketplace.orchestrator.services.interfaces.ToolsGateway;
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
 * Unit tests for StepNode.
 * StepNode executes API calls via ToolsGateway.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepNode")
class StepNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private ToolsGateway mockToolsGateway;

    @Mock
    private V2TemplateAdapter mockTemplateAdapter;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("user_id", 123);
        triggerData.put("name", "Test User");

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
        @DisplayName("Should create StepNode with stepConfig and dependencies")
        void shouldCreateStepNodeWithStepConfigAndDependencies() {
            Step step = new Step("step-1", "mcp", "API Call", null, Map.of(), null, null, null);
            StepNode node = new StepNode("mcp:api_call", step, List.of("trigger:webhook"));

            assertEquals("mcp:api_call", node.getNodeId());
            assertEquals(NodeType.MCP, node.getType());
            assertEquals(step, node.getStepConfig());
        }

        @Test
        @DisplayName("Should create StepNode with stepConfig only")
        void shouldCreateStepNodeWithStepConfigOnly() {
            Step step = new Step("step-1", "mcp", "API Call", null, Map.of(), null, null, null);
            StepNode node = new StepNode("mcp:api_call", step);

            assertEquals("mcp:api_call", node.getNodeId());
            assertEquals(NodeType.MCP, node.getType());
        }

        @Test
        @DisplayName("Should handle null dependencies")
        void shouldHandleNullDependencies() {
            Step step = new Step("step-1", "mcp", "API Call", null, Map.of(), null, null, null);
            StepNode node = new StepNode("mcp:api_call", step, null);

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
        @DisplayName("Should build StepNode using builder pattern")
        void shouldBuildStepNodeUsingBuilderPattern() {
            Step step = new Step("step-1", "mcp", "API Call", null, Map.of(), null, null, null);

            StepNode node = StepNode.builder()
                .nodeId("mcp:api_call")
                .stepConfig(step)
                .dependencies(List.of("trigger:webhook"))
                .build();

            assertEquals("mcp:api_call", node.getNodeId());
            assertEquals(step, node.getStepConfig());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // canExecute() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("canExecute()")
    class CanExecuteTests {

        @Test
        @DisplayName("Should return true when no dependencies")
        void shouldReturnTrueWhenNoDependencies() {
            Step step = new Step("step-1", "mcp", "API Call", null, Map.of(), null, null, null);
            StepNode node = new StepNode("mcp:api_call", step, List.of());

            assertTrue(node.canExecute(context));
        }

        @Test
        @DisplayName("Should return false when dependencies not completed")
        void shouldReturnFalseWhenDependenciesNotCompleted() {
            Step step = new Step("step-1", "mcp", "API Call", null, Map.of(), null, null, null);
            StepNode node = new StepNode("mcp:api_call", step, List.of("trigger:webhook"));

            assertFalse(node.canExecute(context));
        }

        @Test
        @DisplayName("Should return true when dependencies completed")
        void shouldReturnTrueWhenDependenciesCompleted() {
            Step step = new Step("step-1", "mcp", "API Call", null, Map.of(), null, null, null);
            StepNode node = new StepNode("mcp:api_call", step, List.of("trigger:webhook"));

            NodeExecutionResult triggerResult = NodeExecutionResult.success("trigger:webhook", Map.of());
            ExecutionContext updatedContext = context.withResult("trigger:webhook", triggerResult);

            assertTrue(node.canExecute(updatedContext));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Passthrough mode tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Passthrough Mode")
    class ExecutePassthroughModeTests {

        @Test
        @DisplayName("Should return passthrough result when ToolsGateway is null")
        void shouldReturnPassthroughResultWhenToolsGatewayIsNull() {
            Step step = new Step("step-1", "mcp", "API Call", null, Map.of(), null, null, null);
            StepNode node = new StepNode("mcp:api_call", step);
            // No toolsGateway set

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(true, result.output().get("passthrough"));
            assertEquals("API Call", result.output().get("label"));
        }

        @Test
        @DisplayName("Should include step_id in passthrough output")
        void shouldIncludeStepIdInPassthroughOutput() {
            Step step = new Step("step-1", "mcp", "API Call", null, Map.of(), null, null, null);
            StepNode node = new StepNode("mcp:api_call", step);

            NodeExecutionResult result = node.execute(context);

            assertEquals("mcp:api_call", result.output().get("step_id"));
        }

        @Test
        @DisplayName("Should include warning in passthrough output")
        void shouldIncludeWarningInPassthroughOutput() {
            Step step = new Step("step-1", "mcp", "API Call", null, Map.of(), null, null, null);
            StepNode node = new StepNode("mcp:api_call", step);

            NodeExecutionResult result = node.execute(context);

            assertNotNull(result.output().get("warning"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - ToolsGateway integration tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - ToolsGateway Integration")
    class ExecuteToolsGatewayTests {

        @Test
        @DisplayName("Should return success when tool execution succeeds")
        void shouldReturnSuccessWhenToolExecutionSucceeds() {
            Step step = new Step("tool-123", "mcp", "API Call", null, Map.of(), null, null, null);
            StepNode node = new StepNode("mcp:api_call", step);
            node.setToolsGateway(mockToolsGateway);

            ExecutionResult toolResult = new ExecutionResult(true, Map.of("data", "response"), List.of(), List.of());
            when(mockToolsGateway.executeTool(any(ToolRef.class), any(), eq("tenant-1"), any()))
                .thenReturn(toolResult);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("response", result.output().get("data"));
        }

        @Test
        @DisplayName("Should return failure when tool execution fails")
        void shouldReturnFailureWhenToolExecutionFails() {
            Step step = new Step("tool-123", "mcp", "API Call", null, Map.of(), null, null, null);
            StepNode node = new StepNode("mcp:api_call", step);
            node.setToolsGateway(mockToolsGateway);

            ExecutionResult toolResult = new ExecutionResult(false, Map.of(), List.of(Map.of("message", "Tool error")), List.of());
            when(mockToolsGateway.executeTool(any(ToolRef.class), any(), eq("tenant-1"), any()))
                .thenReturn(toolResult);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().isPresent());
            assertEquals("Tool error", result.errorMessage().get());
        }

        @Test
        @DisplayName("Should return failure when step has no tool ID")
        void shouldReturnFailureWhenStepHasNoToolId() {
            Step step = new Step(null, "mcp", "API Call", null, Map.of(), null, null, null);
            StepNode node = new StepNode("mcp:api_call", step);
            node.setToolsGateway(mockToolsGateway);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().get().contains("no tool ID"));
        }

        @Test
        @DisplayName("Should return failure when step has blank tool ID")
        void shouldReturnFailureWhenStepHasBlankToolId() {
            Step step = new Step("  ", "mcp", "API Call", null, Map.of(), null, null, null);
            StepNode node = new StepNode("mcp:api_call", step);
            node.setToolsGateway(mockToolsGateway);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }

        @Test
        @DisplayName("Should handle ToolsGateway exception gracefully")
        void shouldHandleToolsGatewayExceptionGracefully() {
            Step step = new Step("tool-123", "mcp", "API Call", null, Map.of(), null, null, null);
            StepNode node = new StepNode("mcp:api_call", step);
            node.setToolsGateway(mockToolsGateway);

            when(mockToolsGateway.executeTool(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Connection failed"));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().get().contains("Connection failed"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Input preparation tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Input Preparation")
    class InputPreparationTests {

        @Test
        @DisplayName("Should include step.input in prepared input")
        void shouldIncludeStepInputInPreparedInput() {
            Map<String, Object> stepInput = Map.of("param1", "value1", "param2", 42);
            Step step = new Step("tool-123", "mcp", "API Call", null, stepInput, null, null, null);
            StepNode node = new StepNode("mcp:api_call", step);
            node.setToolsGateway(mockToolsGateway);

            ExecutionResult toolResult = new ExecutionResult(true, Map.of("result", "ok"), List.of(), List.of());
            when(mockToolsGateway.executeTool(any(), any(), any(), any())).thenReturn(toolResult);

            node.execute(context);

            verify(mockToolsGateway).executeTool(any(), argThat(input ->
                "value1".equals(input.get("param1")) && Integer.valueOf(42).equals(input.get("param2"))
            ), any(), any());
        }

        @Test
        @DisplayName("Should resolve templates when templateAdapter available")
        void shouldResolveTemplatesWhenTemplateAdapterAvailable() {
            Map<String, Object> stepInput = Map.of("user", "{{trigger:webhook.user_id}}");
            Step step = new Step("tool-123", "mcp", "API Call", null, stepInput, null, null, null);
            StepNode node = new StepNode("mcp:api_call", step);
            node.setToolsGateway(mockToolsGateway);
            node.setTemplateAdapter(mockTemplateAdapter);

            Map<String, Object> resolvedInput = Map.of("user", 123);
            when(mockTemplateAdapter.resolveTemplates(eq(stepInput), any())).thenReturn(resolvedInput);
            when(mockTemplateAdapter.hasUnresolvedTemplates(any(), any())).thenReturn(false);

            ExecutionResult toolResult = new ExecutionResult(true, Map.of("result", "ok"), List.of(), List.of());
            when(mockToolsGateway.executeTool(any(), any(), any(), any())).thenReturn(toolResult);

            node.execute(context);

            verify(mockTemplateAdapter).resolveTemplates(eq(stepInput), any());
        }

        @Test
        @DisplayName("Should fallback when template resolution fails")
        void shouldFallbackWhenTemplateResolutionFails() {
            Map<String, Object> stepInput = Map.of("bad", "{{invalid}}");
            Step step = new Step("tool-123", "mcp", "API Call", null, stepInput, null, null, null);
            StepNode node = new StepNode("mcp:api_call", step);
            node.setToolsGateway(mockToolsGateway);
            node.setTemplateAdapter(mockTemplateAdapter);

            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenThrow(new RuntimeException("Template error"));

            ExecutionResult toolResult = new ExecutionResult(true, Map.of("result", "ok"), List.of(), List.of());
            when(mockToolsGateway.executeTool(any(), any(), any(), any())).thenReturn(toolResult);

            NodeExecutionResult result = node.execute(context);

            // Should still succeed, using fallback input
            assertTrue(result.isSuccess());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CRUD step tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("CRUD Steps")
    class CrudStepTests {

        @Test
        @DisplayName("Should include dataSourceId for CRUD step")
        void shouldIncludeDataSourceIdForCrudStep() {
            Step step = new Step("tool-123", "crud-read-row", "Read Users", null, Map.of(), 456L, null, null);
            StepNode node = new StepNode("mcp:read_users", step);
            node.setToolsGateway(mockToolsGateway);

            ExecutionResult toolResult = new ExecutionResult(true, Map.of("rows", List.of()), List.of(), List.of());
            when(mockToolsGateway.executeTool(any(), any(), any(), any())).thenReturn(toolResult);

            node.execute(context);

            verify(mockToolsGateway).executeTool(any(), argThat(input ->
                Long.valueOf(456L).equals(input.get("dataSourceId"))
            ), any(), any());
        }

        @Test
        @DisplayName("Should include crud config for CRUD step")
        void shouldIncludeCrudConfigForCrudStep() {
            Step.CrudConfig.WhereCondition where = new Step.CrudConfig.WhereCondition("id", "=", 1);
            Step.CrudConfig crud = new Step.CrudConfig(where, null, Map.of(), List.of(), List.of(), 10, null);
            Step step = new Step("tool-123", "crud-read-row", "Read Users", null, Map.of(), 456L, crud, null);
            StepNode node = new StepNode("mcp:read_users", step);
            node.setToolsGateway(mockToolsGateway);

            ExecutionResult toolResult = new ExecutionResult(true, Map.of("rows", List.of()), List.of(), List.of());
            when(mockToolsGateway.executeTool(any(), any(), any(), any())).thenReturn(toolResult);

            node.execute(context);

            verify(mockToolsGateway).executeTool(any(), argThat(input ->
                input.containsKey("crud")
            ), any(), any());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // node_type resolution tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Node Type Resolution")
    class NodeTypeResolutionTests {

        @Test
        @DisplayName("Should resolve node_type to MCP for regular step")
        void shouldResolveNodeTypeToMcpForRegularStep() {
            Step step = new Step("tool-123", "mcp", "API Call", null, Map.of(), null, null, null);
            StepNode node = new StepNode("mcp:api_call", step);
            node.setToolsGateway(mockToolsGateway);

            ExecutionResult toolResult = new ExecutionResult(true, Map.of("data", "ok"), List.of(), List.of());
            when(mockToolsGateway.executeTool(any(), any(), any(), any())).thenReturn(toolResult);

            NodeExecutionResult result = node.execute(context);
            assertEquals("MCP", result.output().get("node_type"));
        }

        @Test
        @DisplayName("Should resolve node_type to GET_ROWS for crud-read-row")
        void shouldResolveNodeTypeToGetRowsForCrudReadRow() {
            Step step = new Step("tool-123", "crud-read-row", "Read Users", null, Map.of(), 456L, null, null);
            StepNode node = new StepNode("mcp:read_users", step);
            node.setToolsGateway(mockToolsGateway);

            ExecutionResult toolResult = new ExecutionResult(true, Map.of("rows", List.of()), List.of(), List.of());
            when(mockToolsGateway.executeTool(any(), any(), any(), any())).thenReturn(toolResult);

            NodeExecutionResult result = node.execute(context);
            assertEquals("GET_ROWS", result.output().get("node_type"));
        }

        @Test
        @DisplayName("Should resolve node_type to INSERT_ROW for crud-create-row")
        void shouldResolveNodeTypeToInsertRowForCrudCreateRow() {
            Step step = new Step("tool-123", "crud-create-row", "Create User", null, Map.of(), 456L, null, null);
            StepNode node = new StepNode("mcp:create_user", step);
            node.setToolsGateway(mockToolsGateway);

            ExecutionResult toolResult = new ExecutionResult(true, Map.of("insertedIds", List.of(1L)), List.of(), List.of());
            when(mockToolsGateway.executeTool(any(), any(), any(), any())).thenReturn(toolResult);

            NodeExecutionResult result = node.execute(context);
            assertEquals("INSERT_ROW", result.output().get("node_type"));
        }

        @Test
        @DisplayName("Should resolve node_type to UPDATE_ROW for crud-update-row")
        void shouldResolveNodeTypeToUpdateRowForCrudUpdateRow() {
            Step step = new Step("tool-123", "crud-update-row", "Update User", null, Map.of(), 456L, null, null);
            StepNode node = new StepNode("mcp:update_user", step);
            node.setToolsGateway(mockToolsGateway);

            ExecutionResult toolResult = new ExecutionResult(true, Map.of("rowsAffected", 1L), List.of(), List.of());
            when(mockToolsGateway.executeTool(any(), any(), any(), any())).thenReturn(toolResult);

            NodeExecutionResult result = node.execute(context);
            assertEquals("UPDATE_ROW", result.output().get("node_type"));
        }

        @Test
        @DisplayName("Should resolve node_type to DELETE_ROW for crud-delete-row")
        void shouldResolveNodeTypeToDeleteRowForCrudDeleteRow() {
            Step step = new Step("tool-123", "crud-delete-row", "Delete User", null, Map.of(), 456L, null, null);
            StepNode node = new StepNode("mcp:delete_user", step);
            node.setToolsGateway(mockToolsGateway);

            ExecutionResult toolResult = new ExecutionResult(true, Map.of("rowsAffected", 1L), List.of(), List.of());
            when(mockToolsGateway.executeTool(any(), any(), any(), any())).thenReturn(toolResult);

            NodeExecutionResult result = node.execute(context);
            assertEquals("DELETE_ROW", result.output().get("node_type"));
        }

        @Test
        @DisplayName("Should resolve node_type to CREATE_COLUMN for crud-create-column")
        void shouldResolveNodeTypeToCreateColumnForCrudCreateColumn() {
            Step step = new Step("tool-123", "crud-create-column", "Add Column", null, Map.of(), 456L, null, null);
            StepNode node = new StepNode("mcp:add_column", step);
            node.setToolsGateway(mockToolsGateway);

            ExecutionResult toolResult = new ExecutionResult(true, Map.of("created", true), List.of(), List.of());
            when(mockToolsGateway.executeTool(any(), any(), any(), any())).thenReturn(toolResult);

            NodeExecutionResult result = node.execute(context);
            assertEquals("CREATE_COLUMN", result.output().get("node_type"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Duration tracking tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Duration Tracking")
    class DurationTrackingTests {

        @Test
        @DisplayName("Should track execution duration on success")
        void shouldTrackExecutionDurationOnSuccess() {
            Step step = new Step("tool-123", "mcp", "API Call", null, Map.of(), null, null, null);
            StepNode node = new StepNode("mcp:api_call", step);
            node.setToolsGateway(mockToolsGateway);

            ExecutionResult toolResult = new ExecutionResult(true, Map.of("data", "ok"), List.of(), List.of());
            when(mockToolsGateway.executeTool(any(), any(), any(), any())).thenReturn(toolResult);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.durationMs() >= 0);
        }

        @Test
        @DisplayName("Should track execution duration on failure")
        void shouldTrackExecutionDurationOnFailure() {
            Step step = new Step("tool-123", "mcp", "API Call", null, Map.of(), null, null, null);
            StepNode node = new StepNode("mcp:api_call", step);
            node.setToolsGateway(mockToolsGateway);

            ExecutionResult toolResult = new ExecutionResult(false, Map.of(), List.of(Map.of("message", "Error")), List.of());
            when(mockToolsGateway.executeTool(any(), any(), any(), any())).thenReturn(toolResult);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.durationMs() >= 0);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getNextNodes() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return successors on success")
        void shouldReturnSuccessorsOnSuccess() {
            Step step = new Step("step-1", "mcp", "API Call", null, Map.of(), null, null, null);
            StepNode node = new StepNode("mcp:api_call", step);
            node.addSuccessor(createSuccessorNode("mcp:next"));

            NodeExecutionResult result = NodeExecutionResult.success(node.getNodeId(), Map.of());
            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(1, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty on failure")
        void shouldReturnEmptyOnFailure() {
            Step step = new Step("step-1", "mcp", "API Call", null, Map.of(), null, null, null);
            StepNode node = new StepNode("mcp:api_call", step);
            node.addSuccessor(createSuccessorNode("mcp:next"));

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
            Step step = new Step("step-1", "mcp", "API Call", null, Map.of(), null, null, null);
            StepNode node = new StepNode("mcp:api_call", step);
            NodeExecutionResult result = NodeExecutionResult.success(node.getNodeId(), Map.of());

            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getStepConfig() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getStepConfig()")
    class GetStepConfigTests {

        @Test
        @DisplayName("Should return the step configuration")
        void shouldReturnTheStepConfiguration() {
            Step step = new Step("step-1", "mcp", "API Call", null, Map.of("key", "value"), null, null, null);
            StepNode node = new StepNode("mcp:api_call", step);

            assertEquals(step, node.getStepConfig());
            assertEquals("API Call", node.getStepConfig().label());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Billing identifier propagation - regression for centralized dispatcher
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Billing Identifiers")
    class BillingIdentifiersTests {

        @Test
        @DisplayName("Should set __credentialSource__=user in billingIdentifiers when Step authored as user (default)")
        void shouldEmitUserCredentialSourceMarker() {
            Step step = new Step("openai/openai-create-image", "mcp", "API Call",
                null, Map.of("prompt", "x"), null, null, null,
                99L,
                com.apimarketplace.orchestrator.domain.workflow.CredentialSource.USER,
                null);
            StepNode node = new StepNode("mcp:api_call", step);
            node.setToolsGateway(mockToolsGateway);

            ExecutionResult toolResult = new ExecutionResult(true, Map.of("data", "ok"), List.of(), List.of());
            when(mockToolsGateway.executeTool(any(ToolRef.class), any(), eq("tenant-1"), any()))
                .thenReturn(toolResult);

            node.execute(context);

            org.mockito.ArgumentCaptor<Map<String, Object>> idsCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(mockToolsGateway).executeTool(any(ToolRef.class), any(), eq("tenant-1"), idsCaptor.capture());

            Map<String, Object> ids = idsCaptor.getValue();
            assertEquals("user", ids.get("__credentialSource__"),
                "StepNode must propagate the workflow toggle ('user' default) so the catalog resolves credentials strictly per author intent");
            assertEquals(99L, ids.get("__selectedCredentialId__"),
                "StepNode must propagate the exact workflow-selected user credential id instead of letting catalog pick the default");
            assertNull(ids.get("__platformCredentialId__"),
                "platformCredentialId must NOT be set when source='user'");
        }

        @Test
        @DisplayName("Should set __credentialSource__=platform + __platformCredentialId__ when Step authored as platform (regression: workflow toggle propagation)")
        void shouldEmitPlatformCredentialSourceMarker() {
            Step step = new Step("openai/openai-create-image", "mcp", "API Call",
                null, Map.of("prompt", "x"), null, null, null,
                com.apimarketplace.orchestrator.domain.workflow.CredentialSource.PLATFORM,
                42L);
            StepNode node = new StepNode("mcp:api_call", step);
            node.setToolsGateway(mockToolsGateway);

            ExecutionResult toolResult = new ExecutionResult(true, Map.of("data", "ok"), List.of(), List.of());
            when(mockToolsGateway.executeTool(any(ToolRef.class), any(), eq("tenant-1"), any()))
                .thenReturn(toolResult);

            node.execute(context);

            org.mockito.ArgumentCaptor<Map<String, Object>> idsCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(mockToolsGateway).executeTool(any(ToolRef.class), any(), eq("tenant-1"), idsCaptor.capture());

            Map<String, Object> ids = idsCaptor.getValue();
            assertEquals("platform", ids.get("__credentialSource__"),
                "StepNode must propagate the workflow toggle 'platform' choice so the catalog resolves the platform pool durci (no fallback)");
            assertEquals(42L, ids.get("__platformCredentialId__"),
                "platformCredentialId must be the exact id pinned by PlatformMarkupPinService - used by ToolExecutionManager to bypass name lookup for billing");
            assertNull(ids.get("__selectedCredentialId__"),
                "selected user credential id must not be forwarded when source='platform'");
        }

        @Test
        @DisplayName("Should also set __workflowRunId__ when context has runId (catalog billing skip marker)")
        void shouldEmitWorkflowRunIdAlongsideMarker() {
            Step step = new Step("openai/openai-create-image", "mcp", "API Call",
                null, Map.of("prompt", "x"), null, null, null);
            StepNode node = new StepNode("mcp:api_call", step);
            node.setToolsGateway(mockToolsGateway);

            ExecutionResult toolResult = new ExecutionResult(true, Map.of("data", "ok"), List.of(), List.of());
            when(mockToolsGateway.executeTool(any(ToolRef.class), any(), eq("tenant-1"), any()))
                .thenReturn(toolResult);

            node.execute(context);

            org.mockito.ArgumentCaptor<Map<String, Object>> idsCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(mockToolsGateway).executeTool(any(ToolRef.class), any(), eq("tenant-1"), idsCaptor.capture());

            Map<String, Object> ids = idsCaptor.getValue();
            // ExecutionContext.create("run-1", ...) sets runId="run-1"
            assertEquals("run-1", ids.get("__workflowRunId__"),
                "__workflowRunId__ must propagate so the catalog billing scope is built with RUN priority");
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
