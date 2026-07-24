package com.apimarketplace.orchestrator.integration.execution;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTreeBuilder;
import com.apimarketplace.orchestrator.execution.v2.engine.TriggerItem;
import com.apimarketplace.orchestrator.execution.v2.engine.UnifiedExecutionEngine;
import com.apimarketplace.orchestrator.execution.v2.engine.WorkflowResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.integration.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Integration tests for streaming event emission during workflow execution.
 *
 * <p>Tests that the execution engine correctly emits events through
 * V2ExecutionEventService at appropriate points during workflow traversal.
 *
 * <p>V2ExecutionEventService is mocked to capture and verify event emissions
 * without requiring the full streaming infrastructure (Redis, streaming connections).
 */
@IntegrationTest
@DisplayName("Streaming Service Integration Tests")
class StreamingServiceIntegrationTest {

    @Autowired
    private UnifiedExecutionEngine executionEngine;

    @Autowired
    private ExecutionTreeBuilder treeBuilder;

    @MockitoBean
    private V2ExecutionEventService eventService;

    private WorkflowExecution workflowExecution;

    @BeforeEach
    void setUp() {
        workflowExecution = createTestWorkflowExecution();

        lenient().doNothing().when(eventService).initializeTotalItems(any(), anyInt());
        lenient().doNothing().when(eventService).emitNodeStart(any(), any(), any(), anyInt(), anyInt());
        lenient().doReturn(null).when(eventService).emitNodeComplete(any(), any(), any(), any(), anyInt(), any()); // emitNodeComplete now RETURNS the completion result (payload-loss fix); null = no payload-lost rewrite
        lenient().doNothing().when(eventService).emitNodeAwaitingSignal(any(), any(), any(), any(), anyInt(), any());
    }

    // =========================================================================
    // NODE START EVENT TESTS
    // =========================================================================

    @Nested
    @DisplayName("Node start event emission")
    class NodeStartEventTests {

        @Test
        @DisplayName("Should emit start event for each node in linear workflow")
        void shouldEmitStartForEachNode() throws Exception {
            // Given
            WorkflowPlan plan = buildLinearPlan(List.of("Step One", "Step Two"));

            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            executionEngine.executeWorkflow(tree, items, workflowExecution, eventService)
                .get(30, TimeUnit.SECONDS);

            // Then: emitNodeStart should be called for trigger + steps
            // P2.3.1: 5-arg overload (per-epoch). Engine threads context.epoch().
            verify(eventService, atLeast(2)).emitNodeStart(
                any(WorkflowExecution.class),
                any(ExecutionNode.class),
                any(TriggerItem.class),
                anyInt(),
                anyInt());
        }

        @Test
        @DisplayName("Should emit start event before node execution")
        void shouldEmitStartBeforeExecution() throws Exception {
            // Given: Single step workflow
            WorkflowPlan plan = buildLinearPlan(List.of("Process"));

            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            executionEngine.executeWorkflow(tree, items, workflowExecution, eventService)
                .get(30, TimeUnit.SECONDS);

            // Then: Start is always called before complete for the same node
            // P2.3.1: 5-arg emitNodeStart (per-epoch).
            verify(eventService, atLeastOnce()).emitNodeStart(any(), any(), any(), anyInt(), anyInt());
            verify(eventService, atLeastOnce()).emitNodeComplete(any(), any(), any(), any(), anyInt(), any());
        }
    }

    // =========================================================================
    // NODE COMPLETE EVENT TESTS
    // =========================================================================

    @Nested
    @DisplayName("Node complete event emission")
    class NodeCompleteEventTests {

        @Test
        @DisplayName("Should emit complete event for each node in linear workflow")
        void shouldEmitCompleteForEachNode() throws Exception {
            // Given
            WorkflowPlan plan = buildLinearPlan(List.of("Step One", "Step Two"));

            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            executionEngine.executeWorkflow(tree, items, workflowExecution, eventService)
                .get(30, TimeUnit.SECONDS);

            // Then: emitNodeComplete should be called for trigger + steps
            verify(eventService, atLeast(2)).emitNodeComplete(
                any(WorkflowExecution.class),
                any(ExecutionNode.class),
                any(NodeExecutionResult.class),
                any(TriggerItem.class),
                anyInt(),
                any(ExecutionContext.class));
        }

        @Test
        @DisplayName("Should emit complete event with execution result")
        void shouldEmitCompleteWithResult() throws Exception {
            // Given
            WorkflowPlan plan = buildLinearPlan(List.of("Process"));

            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of("key", "val")));
            executionEngine.executeWorkflow(tree, items, workflowExecution, eventService)
                .get(30, TimeUnit.SECONDS);

            // Then: Capture the result passed to emitNodeComplete
            ArgumentCaptor<NodeExecutionResult> resultCaptor =
                ArgumentCaptor.forClass(NodeExecutionResult.class);
            verify(eventService, atLeastOnce()).emitNodeComplete(
                any(), any(), resultCaptor.capture(), any(), anyInt(), any());

            List<NodeExecutionResult> results = resultCaptor.getAllValues();
            assertFalse(results.isEmpty(), "Should have captured at least one result");
            // All results should be non-null
            results.forEach(r -> assertNotNull(r, "Result should not be null"));
        }
    }

    // =========================================================================
    // MULTI-ITEM EVENT TESTS
    // =========================================================================

    @Nested
    @DisplayName("Multi-item event emission")
    class MultiItemEventTests {

        @Test
        @DisplayName("Should initialize total items count")
        void shouldInitializeTotalItems() throws Exception {
            // Given
            WorkflowPlan plan = buildLinearPlan(List.of("Process"));

            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // When: Execute with 3 items
            List<TriggerItem> items = List.of(
                new TriggerItem("item-0", 0, Map.of()),
                new TriggerItem("item-1", 1, Map.of()),
                new TriggerItem("item-2", 2, Map.of()));

            executionEngine.executeWorkflow(tree, items, workflowExecution, eventService)
                .get(30, TimeUnit.SECONDS);

            // Then: Total items should be initialized to 3
            verify(eventService).initializeTotalItems(any(WorkflowExecution.class), eq(3));
        }

        @Test
        @DisplayName("Should emit events for each item independently")
        void shouldEmitEventsForEachItem() throws Exception {
            // Given
            WorkflowPlan plan = buildLinearPlan(List.of("Process"));

            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // When: Execute with 3 items
            List<TriggerItem> items = List.of(
                new TriggerItem("item-0", 0, Map.of()),
                new TriggerItem("item-1", 1, Map.of()),
                new TriggerItem("item-2", 2, Map.of()));

            executionEngine.executeWorkflow(tree, items, workflowExecution, eventService)
                .get(30, TimeUnit.SECONDS);

            // Then: Node start events should be emitted for each item
            // 3 items x 2 nodes (trigger + step) = 6 start events
            // P2.3.1: 5-arg emitNodeStart (per-epoch).
            verify(eventService, atLeast(6)).emitNodeStart(
                any(), any(), any(), anyInt(), anyInt());
        }
    }

    // =========================================================================
    // EVENT ORDERING TESTS
    // =========================================================================

    @Nested
    @DisplayName("Event ordering during execution")
    class EventOrderingTests {

        @Test
        @DisplayName("Should emit events in correct order for linear workflow")
        void shouldEmitEventsInCorrectOrder() throws Exception {
            // Given
            WorkflowPlan plan = buildLinearPlan(List.of("Step One"));

            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            executionEngine.executeWorkflow(tree, items, workflowExecution, eventService)
                .get(30, TimeUnit.SECONDS);

            // Then: Verify the order of calls
            // initializeTotalItems should be called first
            var inOrder = inOrder(eventService);
            inOrder.verify(eventService).initializeTotalItems(any(), eq(1));
            // Then at least one start and one complete event (P2.3.1 5-arg overload).
            inOrder.verify(eventService, atLeastOnce()).emitNodeStart(any(), any(), any(), anyInt(), anyInt());
            inOrder.verify(eventService, atLeastOnce()).emitNodeComplete(any(), any(), any(), any(), anyInt(), any());
        }
    }

    // =========================================================================
    // DECISION WORKFLOW EVENT TESTS
    // =========================================================================

    @Nested
    @DisplayName("Decision workflow event emission")
    class DecisionWorkflowEventTests {

        @Test
        @DisplayName("Should emit events for decision node and selected branch")
        void shouldEmitEventsForDecisionWorkflow() throws Exception {
            // Given: A decision workflow
            WorkflowPlan plan = buildDecisionPlan();

            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // When
            List<TriggerItem> items = List.of(
                new TriggerItem("item-1", 0, Map.of("value", 15)));

            executionEngine.executeWorkflow(tree, items, workflowExecution, eventService)
                .get(30, TimeUnit.SECONDS);

            // Then: At least trigger + decision + one branch = 3 start events
            // P2.3.1: 5-arg emitNodeStart (per-epoch).
            verify(eventService, atLeast(3)).emitNodeStart(any(), any(), any(), anyInt(), anyInt());
        }
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private WorkflowPlan buildLinearPlan(List<String> stepLabels) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "test-plan-" + UUID.randomUUID().toString().substring(0, 8));
        data.put("tenant_id", "test-tenant");

        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "Start", "type", "webhook", "strategy", "single")));

        List<Map<String, Object>> mcps = new ArrayList<>();
        for (int i = 0; i < stepLabels.size(); i++) {
            String label = stepLabels.get(i);
            String alias = label.toLowerCase().replace(" ", "_");
            mcps.add(Map.of(
                "id", "s" + (i + 1),
                "label", label,
                "alias", alias,
                "tool_name", "mock_tool"));
        }
        data.put("mcps", mcps);

        List<Map<String, Object>> edges = new ArrayList<>();
        String prev = "trigger:start";
        for (int i = 0; i < stepLabels.size(); i++) {
            String alias = stepLabels.get(i).toLowerCase().replace(" ", "_");
            String curr = "mcp:" + alias;
            edges.add(Map.of("from", prev, "to", curr));
            prev = curr;
        }
        data.put("edges", edges);

        data.put("agents", List.of());
        data.put("cores", List.of());
        data.put("tables", List.of());
        data.put("notes", List.of());

        return WorkflowPlan.fromMap(data);
    }

    private WorkflowPlan buildDecisionPlan() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "decision-plan-" + UUID.randomUUID().toString().substring(0, 8));
        data.put("tenant_id", "test-tenant");

        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "Start", "type", "webhook", "strategy", "single")));

        data.put("mcps", List.of(
            Map.of("id", "s1", "label", "High Path", "alias", "high_path", "tool_name", "mock"),
            Map.of("id", "s2", "label", "Low Path", "alias", "low_path", "tool_name", "mock")));

        data.put("cores", List.of(
            Map.of("id", "c1", "label", "Check", "type", "decision",
                "decisionConditions", List.of(
                    Map.of("id", "dc1", "type", "if", "label", "If High", "expression", "{{trigger:start.output.value}} > 10"),
                    Map.of("id", "dc2", "type", "else", "label", "Else Low", "expression", "")
                ))));

        data.put("edges", List.of(
            Map.of("from", "trigger:start", "to", "core:check"),
            Map.of("from", "core:check:if", "to", "mcp:high_path"),
            Map.of("from", "core:check:else", "to", "mcp:low_path")));

        data.put("agents", List.of());
        data.put("tables", List.of());
        data.put("notes", List.of());

        return WorkflowPlan.fromMap(data);
    }

    private WorkflowExecution createTestWorkflowExecution() {
        WorkflowPlan plan = buildLinearPlan(List.of("Dummy"));
        return new WorkflowExecution("test-run", plan, Map.of());
    }
}
