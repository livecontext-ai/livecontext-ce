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
import static org.mockito.Mockito.verify;

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

    /**
     * The node as the engine builds it: WITH a template adapter.
     *
     * <p>Without one, `BaseNode.resolveTemplateString` returns the template unchanged, so
     * the pre-fix reporting path produced the same string as the fixed one and every test
     * named for the change passed on the code it was meant to catch. Wiring the adapter is
     * what makes `{{trigger:start.items}}` actually resolve to the three rows, and therefore
     * what makes "the expression, not a stringified rendering of its value" a real assertion.
     */
    private FindNode createFindNode(String listExpression, int maxItems) {
        Step stepConfig = new Step("tool-1", "crud-find", "Find Users", null, Map.of(), 123L, null, null);
        FindNode node = new FindNode("table:find_users", stepConfig, listExpression, maxItems, mockTemplateEngine);
        node.setTemplateAdapter(new V2TemplateAdapter(mockTemplateEngine));
        return node;
    }

    private FindNode createFindNodeWithToolsGateway(String listExpression, int maxItems) {
        Step stepConfig = new Step("tool-1", "crud-find", "Find Users", null, Map.of(), 123L, null, null);
        FindNode node = new FindNode("table:find_users", stepConfig, listExpression, maxItems, mockTemplateEngine);
        node.setToolsGateway(mockToolsGateway);
        node.setTemplateAdapter(new V2TemplateAdapter(mockTemplateEngine));
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
    @DisplayName("CRUD params resolution failure")
    class CrudParamsResolutionFailureTests {

        @Test
        @DisplayName("fails instead of querying the table with the raw params and the whole trigger payload")
        void failsInsteadOfQueryingWithRawParams() {
            Step stepConfig = new Step(null, "crud-find", "Find Users", null,
                Map.of("status", "{{core:missing.output.status}}"), 123L, null, null);
            FindNode node = new FindNode("table:find_users", stepConfig, null, 100, mockTemplateEngine);
            node.setToolsGateway(mockToolsGateway);
            V2TemplateAdapter failingAdapter = org.mockito.Mockito.mock(V2TemplateAdapter.class);
            org.mockito.Mockito.when(failingAdapter.resolveTemplates(any(), any()))
                .thenThrow(new RuntimeException("Template error"));
            node.setTemplateAdapter(failingAdapter);

            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> node.execute(context));

            assertTrue(failure.getMessage().contains("Template error"));
            org.mockito.Mockito.verify(mockToolsGateway, org.mockito.Mockito.never())
                .executeTool(any(), any(), any(), any());
        }
    }

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
        @DisplayName("the table read is marked as a step OUTPUT, so the catalog keeps its text whole")
        @SuppressWarnings("unchecked")
        void marksTheCallAsStepOutput() {
            Step stepConfig = new Step(null, "crud-find", "Find Users", null, Map.of(), 123L, null, null);
            FindNode node = new FindNode("table:find_users", stepConfig, null, 100, mockTemplateEngine);
            node.setToolsGateway(mockToolsGateway);
            lenient().when(mockToolsGateway.executeTool(any(ToolRef.class), any(), anyString(), any()))
                .thenReturn(new ExecutionResult(true, Map.of("rows", List.of(Map.of("name", "Alice"))),
                    List.of(), List.of()));

            node.execute(context);

            org.mockito.ArgumentCaptor<Map<String, Object>> ids = org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(mockToolsGateway).executeTool(any(ToolRef.class), any(), anyString(), ids.capture());
            assertEquals(Boolean.TRUE, ids.getValue().get(
                com.apimarketplace.orchestrator.services.impl.CatalogToolsGateway.STEP_OUTPUT_MARKER));
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

    /**
     * What a find says about the `list` expression it was configured with.
     *
     * <p>It used to report {@code resolveTemplateString(listExpression, context)}: a second
     * resolution of the same expression the node evaluates for real, through the resolver
     * that coerces every value to a String. So the one parameter a find returning nothing
     * is diagnosed from was reported as text that matched neither the node's own items[]
     * nor the expression the author wrote.
     */
    @Nested
    @DisplayName("Reported list expression")
    class ReportedListExpressionTests {

        @SuppressWarnings("unchecked")
        private Map<String, Object> paramsOf(NodeExecutionResult result) {
            return (Map<String, Object>) result.output().get("resolved_params");
        }

        @Test
        @DisplayName("`list` is the expression the author wrote, never a stringified copy of the rows it produced")
        void reportsTheExpressionNotItsStringifiedValue() {
            FindNode node = createFindNode("{{trigger:start.items}}", 100);

            Map<String, Object> params = paramsOf(node.execute(context));

            assertEquals("{{trigger:start.items}}", params.get("list"));
            // The pre-fix value: the three rows flattened into one String by
            // resolveTemplateString, which is neither the configuration nor the data.
            assertFalse(String.valueOf(params.get("list")).contains("Alice"),
                "the expression must not be replaced by a coerced rendering of its value");
        }

        @Test
        @DisplayName("`listResolved` describes what the evaluation returned, from that evaluation and not a second one")
        void reportsWhatTheEvaluationReturned() {
            FindNode node = createFindNode("{{trigger:start.items}}", 100);

            Map<String, Object> params = paramsOf(node.execute(context));

            assertEquals("List(size=3)", params.get("listResolved"));
        }

        @Test
        @DisplayName("an expression that resolved to nothing reads as null, not as an empty string")
        void reportsNullRatherThanAnEmptyString() {
            // resolveTemplateString rendered an absent value as "", which reads exactly
            // like an expression that resolved to an empty string. The evaluation says null.
            lenient().when(mockTemplateEngine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
                .thenReturn(null);
            FindNode node = createFindNode("{{trigger:start.missing}}", 100);

            Map<String, Object> params = paramsOf(node.execute(context));

            assertEquals("{{trigger:start.missing}}", params.get("list"));
            assertEquals("null", params.get("listResolved"));
        }

        @Test
        @DisplayName("when the table served the rows, the expression is reported as NOT evaluated rather than credited with them")
        void saysWhenTheExpressionWasNotEvaluated() {
            Step stepConfig = new Step(null, "crud-read-row", "Get Data", null, Map.of(), 1L, null, null);
            FindNode node = new FindNode("table:get_data", stepConfig, "{{trigger:start.items}}", 100, mockTemplateEngine);
            node.setToolsGateway(mockToolsGateway);
            lenient().when(mockToolsGateway.executeTool(any(ToolRef.class), any(), anyString(), any()))
                .thenReturn(new ExecutionResult(true,
                    Map.of("rows", List.of(Map.of("x", 1))), List.of(), List.of()));

            NodeExecutionResult result = node.execute(context);
            Map<String, Object> params = paramsOf(result);

            assertEquals(1, result.output().get("item_count"));
            assertEquals("(not evaluated: the table returned rows)", params.get("listResolved"));
        }

        @Test
        @DisplayName("a fallback that threw says so, where the reader is asking why nothing came back")
        void reportsAFailedEvaluation() {
            lenient().when(mockTemplateEngine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
                .thenThrow(new IllegalStateException("bad expression"));
            FindNode node = createFindNode("{{trigger:start.items}}", 100);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertEquals("(evaluation failed: bad expression)", paramsOf(result).get("listResolved"));
        }

        @Test
        @DisplayName("a table that returned NO rows hands over to the expression, and the report follows the strategy that ran")
        void reportsTheFallbackWhenTheTableReturnedNothing() {
            // The sentinel is seeded before the strategies run, so this is the branch where
            // it has to be OVERWRITTEN: the table came back empty, the expression ran, and
            // reporting "the table returned rows" here would name the wrong strategy AND
            // contradict the rows the node returns.
            Step stepConfig = new Step(null, "crud-read-row", "Get Data", null, Map.of(), 1L, null, null);
            FindNode node = new FindNode("table:get_data", stepConfig, "{{trigger:start.items}}", 100, mockTemplateEngine);
            node.setToolsGateway(mockToolsGateway);
            lenient().when(mockToolsGateway.executeTool(any(ToolRef.class), any(), anyString(), any()))
                .thenReturn(new ExecutionResult(true, Map.of("rows", List.of()), List.of(), List.of()));

            NodeExecutionResult result = node.execute(context);

            assertEquals(3, result.output().get("item_count"), "the fallback's rows are the ones returned");
            assertEquals("List(size=3)", paramsOf(result).get("listResolved"));
        }

        @Test
        @DisplayName("a table read that FAILED hands over to the expression, and the report says what that expression gave")
        void reportsTheFallbackWhenTheTableReadFailed() {
            Step stepConfig = new Step(null, "crud-read-row", "Get Data", null, Map.of(), 1L, null, null);
            FindNode node = new FindNode("table:get_data", stepConfig, "{{trigger:start.items}}", 100, mockTemplateEngine);
            node.setToolsGateway(mockToolsGateway);
            lenient().when(mockToolsGateway.executeTool(any(ToolRef.class), any(), anyString(), any()))
                .thenReturn(new ExecutionResult(false, Map.of("error", "Connection failed"),
                    List.of(Map.of("message", "fail")), List.of()));

            NodeExecutionResult result = node.execute(context);

            assertEquals(3, result.output().get("item_count"));
            assertEquals("List(size=3)", paramsOf(result).get("listResolved"));
        }

        @Test
        @DisplayName("the CRUD echo does not overwrite `list` with a resolved copy of itself (the shape the factory actually builds)")
        void survivesTheCrudParamsEcho() {
            // The production wiring, which every other fixture here skips: `list` LIVES in
            // the step's params (ExecutionNodeFactory reads listExpression from params.list),
            // and prepareCrudInput echoes that whole params map, resolved, into the same
            // report. With an empty params map and no template adapter - the shape of the
            // other fixtures - the collision cannot happen, so the panel shipped the
            // resolved rows under `list` beside "(not evaluated)" under `listResolved` and
            // nothing was red.
            Step stepConfig = new Step(null, "crud-find", "Find Users", null,
                Map.of("list", "{{trigger:start.items}}"), 1L, null, null);
            FindNode node = new FindNode("table:find_users", stepConfig,
                "{{trigger:start.items}}", 100, mockTemplateEngine);
            node.setToolsGateway(mockToolsGateway);
            node.setTemplateAdapter(new V2TemplateAdapter(mockTemplateEngine));
            lenient().when(mockToolsGateway.executeTool(any(ToolRef.class), any(), anyString(), any()))
                .thenReturn(new ExecutionResult(true, Map.of("rows", List.of(Map.of("x", 1))), List.of(), List.of()));

            Map<String, Object> params = paramsOf(node.execute(context));

            assertEquals("{{trigger:start.items}}", params.get("list"),
                "`list` must stay the expression on the path that ships");
            assertEquals("(not evaluated: the table returned rows)", params.get("listResolved"));
            // And the resolved collection is no longer copied onto the persisted row under
            // a key that claims to hold configuration.
            assertFalse(String.valueOf(params.get("list")).contains("Alice"));
        }

        @Test
        @DisplayName("the resolved value sits beside the expression, not below every CRUD key")
        void keepsTheTwoListKeysTogether() {
            // One question, two keys: "Items" and "Items (resolved)" are read as a pair, and
            // the map's order is the panel's order.
            Step stepConfig = new Step(null, "crud-read-row", "Get Data", null, Map.of(), 1L, null, null);
            FindNode node = new FindNode("table:get_data", stepConfig, "{{trigger:start.items}}", 100, mockTemplateEngine);
            node.setToolsGateway(mockToolsGateway);
            lenient().when(mockToolsGateway.executeTool(any(ToolRef.class), any(), anyString(), any()))
                .thenReturn(new ExecutionResult(true, Map.of("rows", List.of(Map.of("x", 1))), List.of(), List.of()));

            List<String> keys = new ArrayList<>(paramsOf(node.execute(context)).keySet());

            assertEquals("list", keys.get(0));
            assertEquals("listResolved", keys.get(1));
        }

        @Test
        @DisplayName("a failure message the size of a stack trace is shortened before it is persisted")
        void shortensTheFailureReason() {
            lenient().when(mockTemplateEngine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
                .thenThrow(new IllegalStateException("q".repeat(5000)));
            FindNode node = createFindNode("{{trigger:start.items}}", 100);

            String reported = String.valueOf(paramsOf(node.execute(context)).get("listResolved"));

            assertTrue(reported.length() < 300, "resolved_params is persisted on every step row");
            assertTrue(reported.contains("5000 chars"), "and the reader must be told what was cut");
        }

        @Test
        @DisplayName("no expression configured, no key invented")
        void reportsNothingWhenNoExpressionIsConfigured() {
            FindNode node = createFindNode(null, 100);

            Map<String, Object> params = paramsOf(node.execute(context));

            assertFalse(params.containsKey("list"));
            assertFalse(params.containsKey("listResolved"));
            assertEquals(100, params.get("maxItems"));
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
            // Product-analytics attribution rides beside billing but never through
            // the billing step key (__nodeId__), which shapes what is charged.
            assertEquals(context.plan() != null ? context.plan().getId() : null, ids.get("__workflowId__"),
                "__workflowId__ attributes api_call_completed to the workflow");
            assertEquals("table:get_data", ids.get("__analyticsNodeId__"),
                "__analyticsNodeId__ attributes api_call_completed to the node");
            assertEquals(null, ids.get("__nodeId__"),
                "__nodeId__ is the billing step key and must stay unset");
        }
    }

    /**
     * The last relay site of this change without a call-site test, kept honest by a mutation:
     * forcing this branch back to ERROR left 62 tests green before these existed.
     */
    @Nested
    @DisplayName("a relayed CRUD refusal is not re-raised to ERROR")
    class RelayedRefusalLogLevel {

        private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender;
        private ch.qos.logback.classic.Logger nodeLogger;

        @BeforeEach
        void attach() {
            nodeLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(FindNode.class);
            appender = new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            nodeLogger.addAppender(appender);
        }

        @org.junit.jupiter.api.AfterEach
        void detach() {
            nodeLogger.detachAppender(appender);
        }

        private void readWithError(String message) {
            Step stepConfig = new Step(null, "crud-find", "Find Users", null, Map.of(), 123L, null, null);
            FindNode node = new FindNode("table:find_users", stepConfig, null, 100, mockTemplateEngine);
            node.setToolsGateway(mockToolsGateway);
            lenient().when(mockToolsGateway.executeTool(any(ToolRef.class), any(), anyString(), any()))
                .thenReturn(new ExecutionResult(false, Map.of(),
                    List.of(Map.of("message", message)), List.of()));
            node.execute(context);
        }

        @Test
        @DisplayName("out of credits is a WARN, with no ERROR line")
        void refusalIsWarn() {
            readWithError(com.apimarketplace.orchestrator.services.credit.CreditExhaustion.MESSAGE);

            org.assertj.core.api.Assertions.assertThat(appender.list)
                .noneMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR);
            org.assertj.core.api.Assertions.assertThat(appender.list)
                .anySatisfy(e -> org.assertj.core.api.Assertions.assertThat(e.getLevel())
                    .isEqualTo(ch.qos.logback.classic.Level.WARN));
        }

        @Test
        @DisplayName("a real fault still logs ERROR")
        void faultStaysError() {
            readWithError("Connection refused: connect");

            org.assertj.core.api.Assertions.assertThat(appender.list)
                .anySatisfy(e -> org.assertj.core.api.Assertions.assertThat(e.getLevel())
                    .isEqualTo(ch.qos.logback.classic.Level.ERROR));
        }
    }
}
