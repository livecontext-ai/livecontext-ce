package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import com.apimarketplace.orchestrator.services.interfaces.ToolsGateway;
import com.apimarketplace.orchestrator.domain.WorkflowExecutionContext;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for FindNode.
 * FindNode combines CRUD read with split-like parallel execution per row.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FindNode")
class FindNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private TemplateEngine mockTemplateEngine;

    @Mock
    private ToolsGateway mockToolsGateway;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("items", List.of(
            Map.of("name", "Alice", "status", "active"),
            Map.of("name", "Bob", "status", "active"),
            Map.of("name", "Charlie", "status", "active")
        ));

        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            triggerData,
            mockPlan
        );

        // Default stub: evaluateTemplate returns 3 items
        lenient().when(mockTemplateEngine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
            .thenReturn(List.of(
                Map.of("name", "Alice", "status", "active"),
                Map.of("name", "Bob", "status", "active"),
                Map.of("name", "Charlie", "status", "active")
            ));
    }

    private FindNode createFindNode(String listExpression, int maxItems) {
        Step stepConfig = new Step("tool-1", "crud-find", "Find Users", null, Map.of(), 123L, null, null);
        return new FindNode("table:find_users", stepConfig, listExpression, maxItems, mockTemplateEngine);
    }

    private FindNode createFindNodeWithToolsGateway(String listExpression, int maxItems) {
        Step stepConfig = new Step("tool-1", "crud-find", "Find Users", null, Map.of(), 123L, null, null);
        FindNode node = new FindNode("table:find_users", stepConfig, listExpression, maxItems, mockTemplateEngine);
        node.setToolsGateway(mockToolsGateway);
        return node;
    }

    // ===== Constructor =====

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create FindNode with correct type")
        void shouldCreateWithCorrectType() {
            FindNode node = createFindNode("{{trigger:start.items}}", 100);
            assertEquals(NodeType.FIND, node.getType());
            assertEquals("table:find_users", node.getNodeId());
        }

        @Test
        @DisplayName("Should default maxItems to 100 when 0 or negative")
        void shouldDefaultMaxItems() {
            FindNode node = createFindNode("{{trigger:start.items}}", 0);
            assertEquals(100, node.getMaxItems());

            FindNode node2 = createFindNode("{{trigger:start.items}}", -1);
            assertEquals(100, node2.getMaxItems());
        }

    }

    // ===== Node Identity =====

    @Nested
    @DisplayName("Node Identity")
    class NodeIdentityTests {

        @Test
        @DisplayName("Should report as find node but NOT split node")
        void shouldBeFindNodeNotSplit() {
            FindNode node = createFindNode("{{trigger:start.items}}", 100);
            assertTrue(node.isFindNode());
            assertFalse(node.isSplitNode());
        }

        @Test
        @DisplayName("Should return maxItems")
        void shouldReturnMaxItems() {
            FindNode node = createFindNode("{{trigger:start.items}}", 50);
            assertEquals(50, node.getMaxItems());
        }
    }

    // ===== Execute with List Fallback =====

    @Nested
    @DisplayName("Execute with List Fallback")
    class ExecuteWithListFallbackTests {

        @Test
        @DisplayName("Should execute and return items from list expression")
        void shouldExecuteWithListExpression() {
            FindNode node = createFindNode("{{trigger:start.items}}", 100);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("FIND", result.output().get("node_type"));
            assertEquals(3, result.output().get("item_count"));
            assertNotNull(result.output().get("items"));
            assertEquals("items_found", result.output().get("exit_reason"));
        }

        @Test
        @DisplayName("Should return empty result when no items")
        void shouldReturnEmptyWhenNoItems() {
            lenient().when(mockTemplateEngine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
                .thenReturn(List.of());

            FindNode node = createFindNode("{{trigger:start.empty}}", 100);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(0, result.output().get("item_count"));
            assertEquals("empty_result", result.output().get("exit_reason"));
        }

        @Test
        @DisplayName("Should return empty list when no list expression and no gateway")
        void shouldReturnEmptyWhenNoExpressionNoGateway() {
            FindNode node = createFindNode(null, 100);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(0, result.output().get("item_count"));
        }
    }

    // ===== Pagination / MaxItems =====

    @Nested
    @DisplayName("Pagination / MaxItems")
    class PaginationTests {

        @Test
        @DisplayName("Should limit items to maxItems")
        void shouldLimitItems() {
            // Return 10 items but maxItems is 3
            lenient().when(mockTemplateEngine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
                .thenReturn(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"));

            FindNode node = createFindNode("{{trigger:start.items}}", 3);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(3, result.output().get("item_count"));
            assertEquals(10, result.output().get("total_before_limit"));
            assertEquals(true, result.output().get("has_more"));
        }

        @Test
        @DisplayName("Should not limit when items count is within maxItems")
        void shouldNotLimitWhenWithinRange() {
            FindNode node = createFindNode("{{trigger:start.items}}", 100);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(3, result.output().get("item_count"));
            assertEquals(3, result.output().get("total_before_limit"));
            assertEquals(false, result.output().get("has_more"));
        }

        @Test
        @DisplayName("Should handle large item counts with cap")
        void shouldHandleLargeItemCountsWithCap() {
            // Simulate 5000 items
            List<Object> largeList = new ArrayList<>();
            for (int i = 0; i < 5000; i++) {
                largeList.add(Map.of("id", i, "name", "item_" + i));
            }
            lenient().when(mockTemplateEngine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
                .thenReturn(largeList);

            FindNode node = createFindNode("{{trigger:start.items}}", 100);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(100, result.output().get("item_count"));
            assertEquals(5000, result.output().get("total_before_limit"));
            assertEquals(true, result.output().get("has_more"));
        }
    }

    // ===== getNextNodes =====

    @Nested
    @DisplayName("getNextNodes")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return successors when items found")
        void shouldReturnSuccessorsWhenItemsFound() {
            FindNode node = createFindNode("{{trigger:start.items}}", 100);
            StepNode successor = new StepNode("mcp:process_user",
                new Step("s1", "mcp", "Process User", null, Map.of(), null, null, null));
            node.addSuccessor(successor);

            NodeExecutionResult result = node.execute(context);

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertEquals(1, nextNodes.size());
            assertEquals("mcp:process_user", nextNodes.get(0).getNodeId());
        }

        @Test
        @DisplayName("Should return empty list when result is failure")
        void shouldReturnEmptyWhenResultIsFailure() {
            // Simulate failure: template engine throws so evaluateListFallback returns null
            lenient().when(mockTemplateEngine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
                .thenThrow(new RuntimeException("Template evaluation error"));

            FindNode node = createFindNode("{{trigger:start.missing}}", 100);
            StepNode successor = new StepNode("mcp:should_not_run",
                new Step("s1", "mcp", "Should Not Run", null, Map.of(), null, null, null));
            node.addSuccessor(successor);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure(), "FindNode should fail when items cannot be retrieved");
            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertTrue(nextNodes.isEmpty(), "Failed FindNode should return no successors");
        }

        @Test
        @DisplayName("Should return successors for skip propagation when empty")
        void shouldReturnSuccessorsForSkipWhenEmpty() {
            lenient().when(mockTemplateEngine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
                .thenReturn(List.of());

            FindNode node = createFindNode("{{trigger:start.empty}}", 100);
            StepNode successor = new StepNode("mcp:never_called",
                new Step("s1", "mcp", "Never Called", null, Map.of(), null, null, null));
            node.addSuccessor(successor);

            NodeExecutionResult result = node.execute(context);

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertEquals(1, nextNodes.size());
        }
    }

    // ===== Step Config =====

    @Nested
    @DisplayName("Step Config")
    class StepConfigTests {

        @Test
        @DisplayName("Should expose step config")
        void shouldExposeStepConfig() {
            FindNode node = createFindNode("{{trigger:start.items}}", 100);
            assertNotNull(node.getStepConfig());
            assertEquals("crud-find", node.getStepConfig().type());
            assertEquals("Find Users", node.getStepConfig().label());
        }

        @Test
        @DisplayName("Should report as find step")
        void shouldReportAsFindStep() {
            FindNode node = createFindNode("{{trigger:start.items}}", 100);
            assertTrue(node.getStepConfig().isFindStep());
            assertTrue(node.getStepConfig().isCrudStep());
        }
    }

    // ===== CRUD toolId resolution =====

    @Nested
    @DisplayName("CRUD toolId resolution")
    class CrudToolIdTests {

        @Test
        @DisplayName("Should derive toolId from CRUD type when step id is null (crud-find)")
        void shouldDeriveToolIdFromCrudFindType() {
            // Step with null id (typical for table nodes in plans)
            Step stepConfig = new Step(null, "crud-find", "Find Users", null, Map.of(), 123L, null, null);
            FindNode node = new FindNode("table:find_users", stepConfig, null, 100, mockTemplateEngine);
            node.setToolsGateway(mockToolsGateway);

            // Mock successful CRUD read returning rows
            ExecutionResult crudResult = new ExecutionResult(true,
                Map.of("rows", List.of(Map.of("name", "Alice"), Map.of("name", "Bob"))),
                List.of(), List.of());
            lenient().when(mockToolsGateway.executeTool(any(ToolRef.class), any(), anyString(), any()))
                .thenReturn(crudResult);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(2, result.output().get("item_count"));
        }

        @Test
        @DisplayName("Should derive toolId from CRUD type when step id is null (crud-read-row)")
        void shouldDeriveToolIdFromCrudReadRowType() {
            Step stepConfig = new Step(null, "crud-read-row", "Get Users", null, Map.of(), 456L, null, null);
            FindNode node = new FindNode("table:get_users", stepConfig, null, 50, mockTemplateEngine);
            node.setToolsGateway(mockToolsGateway);

            ExecutionResult crudResult = new ExecutionResult(true,
                Map.of("rows", List.of(Map.of("name", "Charlie"))),
                List.of(), List.of());
            lenient().when(mockToolsGateway.executeTool(any(ToolRef.class), any(), anyString(), any()))
                .thenReturn(crudResult);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(1, result.output().get("item_count"));
        }

        @Test
        @DisplayName("Should use explicit step id for non-CRUD steps")
        void shouldUseExplicitIdForNonCrudSteps() {
            Step stepConfig = new Step("custom-tool-id", "mcp", "Custom Tool", null, Map.of(), null, null, null);
            FindNode node = new FindNode("mcp:custom_tool", stepConfig, "{{trigger:start.items}}", 100, mockTemplateEngine);
            // No toolsGateway set, so it falls back to list expression
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(3, result.output().get("item_count"));
        }

        @Test
        @DisplayName("Should extract rows from result using 'rows' key")
        void shouldExtractRowsFromResult() {
            Step stepConfig = new Step(null, "crud-read-row", "Get Data", null, Map.of(), 1L, null, null);
            FindNode node = new FindNode("table:get_data", stepConfig, null, 100, mockTemplateEngine);
            node.setToolsGateway(mockToolsGateway);

            List<Map<String, Object>> rows = List.of(
                Map.of("id", 1, "name", "Item A", "price", 10.5),
                Map.of("id", 2, "name", "Item B", "price", 20.0),
                Map.of("id", 3, "name", "Item C", "price", 30.0)
            );
            ExecutionResult crudResult = new ExecutionResult(true,
                Map.of("rows", rows, "rowCount", 3),
                List.of(), List.of());
            lenient().when(mockToolsGateway.executeTool(any(ToolRef.class), any(), anyString(), any()))
                .thenReturn(crudResult);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(3, result.output().get("item_count"));
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) result.output().get("items");
            assertNotNull(items);
            assertEquals(3, items.size());
        }

        @Test
        @DisplayName("Should extract rows from result using 'data' key as fallback")
        void shouldExtractRowsFromDataKey() {
            Step stepConfig = new Step(null, "crud-find", "Find Items", null, Map.of(), 1L, null, null);
            FindNode node = new FindNode("table:find_items", stepConfig, null, 100, mockTemplateEngine);
            node.setToolsGateway(mockToolsGateway);

            ExecutionResult crudResult = new ExecutionResult(true,
                Map.of("data", List.of(Map.of("x", 1), Map.of("x", 2))),
                List.of(), List.of());
            lenient().when(mockToolsGateway.executeTool(any(ToolRef.class), any(), anyString(), any()))
                .thenReturn(crudResult);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(2, result.output().get("item_count"));
        }

        @Test
        @DisplayName("Should fallback to list expression when CRUD read fails")
        void shouldFallbackToListWhenCrudFails() {
            Step stepConfig = new Step(null, "crud-read-row", "Get Data", null, Map.of(), 1L, null, null);
            FindNode node = new FindNode("table:get_data", stepConfig, "{{trigger:start.items}}", 100, mockTemplateEngine);
            node.setToolsGateway(mockToolsGateway);

            ExecutionResult crudResult = new ExecutionResult(false,
                Map.of("error", "Connection failed"),
                List.of(Map.of("message", "fail")), List.of());
            lenient().when(mockToolsGateway.executeTool(any(ToolRef.class), any(), anyString(), any()))
                .thenReturn(crudResult);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            // Falls back to list expression which returns 3 items from setUp
            assertEquals(3, result.output().get("item_count"));
        }
    }

    // ===== Billing identifier propagation - regression for centralized dispatcher =====

    @Nested
    @DisplayName("Billing Identifiers")
    class BillingIdentifiersTests {

        @Test
        @DisplayName("Should set __credentialSource__=user in billingIdentifiers when Step authored as user (default)")
        void shouldEmitUserCredentialSourceMarker() {
            Step stepConfig = new Step(null, "crud-read-row", "Get Data",
                null, Map.of(), 1L, null, null,
                88L,
                com.apimarketplace.orchestrator.domain.workflow.CredentialSource.USER,
                null);
            FindNode node = new FindNode("table:get_data", stepConfig,
                "{{trigger:start.items}}", 100, mockTemplateEngine);
            node.setToolsGateway(mockToolsGateway);

            ExecutionResult crudResult = new ExecutionResult(true,
                Map.of("data", List.of(Map.of("x", 1))), List.of(), List.of());
            lenient().when(mockToolsGateway.executeTool(any(ToolRef.class), any(), anyString(), any()))
                .thenReturn(crudResult);

            node.execute(context);

            org.mockito.ArgumentCaptor<Map<String, Object>> idsCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
            org.mockito.Mockito.verify(mockToolsGateway).executeTool(
                any(ToolRef.class), any(), anyString(), idsCaptor.capture());

            Map<String, Object> ids = idsCaptor.getValue();
            assertEquals("user", ids.get("__credentialSource__"),
                "FindNode must propagate the workflow toggle so the catalog resolves credentials strictly per author intent");
            assertEquals(88L, ids.get("__selectedCredentialId__"),
                "FindNode must preserve the selected user credential id for catalog resolution");
            assertEquals("run-1", ids.get("__workflowRunId__"),
                "__workflowRunId__ must propagate so the catalog billing scope is built with RUN priority");
        }
    }
}
