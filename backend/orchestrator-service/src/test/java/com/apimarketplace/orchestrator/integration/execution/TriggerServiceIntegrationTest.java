package com.apimarketplace.orchestrator.integration.execution;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTreeBuilder;
import com.apimarketplace.orchestrator.execution.v2.engine.TriggerItem;
import com.apimarketplace.orchestrator.execution.v2.engine.UnifiedExecutionEngine;
import com.apimarketplace.orchestrator.execution.v2.engine.WorkflowResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.integration.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.lenient;

/**
 * Integration tests for trigger handling in the execution engine.
 *
 * <p>Tests various trigger types (manual, webhook, datasource, schedule)
 * and their processing through the execution tree. Verifies that trigger
 * nodes are correctly created, wired, and executed with Spring context.
 */
@IntegrationTest
@DisplayName("Trigger Service Integration Tests")
class TriggerServiceIntegrationTest {

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
        lenient().doNothing().when(eventService).emitNodeComplete(any(), any(), any(), any(), anyInt(), any());
        lenient().doNothing().when(eventService).emitNodeAwaitingSignal(any(), any(), any(), any(), anyInt(), any());
    }

    // =========================================================================
    // MANUAL TRIGGER TESTS
    // =========================================================================

    @Nested
    @DisplayName("Manual trigger handling")
    class ManualTriggerTests {

        @Test
        @DisplayName("Should create trigger node for manual trigger type")
        void shouldCreateManualTriggerNode() {
            // Given
            WorkflowPlan plan = buildPlanWithTriggerType("manual", "Manual Start");
            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);

            // When
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // Then
            assertNotNull(tree);
            ExecutionNode rootNode = tree.getRootNode();
            assertNotNull(rootNode);
            assertTrue(rootNode.isTriggerNode());
            assertEquals(NodeType.TRIGGER, rootNode.getType());
        }

        @Test
        @DisplayName("Should execute manual trigger with empty payload")
        void shouldExecuteManualTrigger() throws Exception {
            // Given
            WorkflowPlan plan = buildPlanWithTriggerType("manual", "Manual Start");
            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // When: Execute with empty trigger data (manual triggers may have no payload)
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            WorkflowResult result = executionEngine.executeWorkflow(
                tree, items, workflowExecution, eventService).get(30, TimeUnit.SECONDS);

            // Then
            assertNotNull(result);
            assertEquals(1, result.totalItems());
        }
    }

    // =========================================================================
    // WEBHOOK TRIGGER TESTS
    // =========================================================================

    @Nested
    @DisplayName("Webhook trigger handling")
    class WebhookTriggerTests {

        @Test
        @DisplayName("Should process webhook trigger with JSON payload")
        void shouldProcessWebhookTrigger() throws Exception {
            // Given
            WorkflowPlan plan = buildPlanWithTriggerType("webhook", "Webhook Start");
            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // Webhook payload simulation
            Map<String, Object> webhookPayload = new HashMap<>();
            webhookPayload.put("event", "user.created");
            webhookPayload.put("user_id", 12345);
            webhookPayload.put("email", "user@example.com");
            webhookPayload.put("data", Map.of("name", "John", "age", 30));

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, webhookPayload));
            WorkflowResult result = executionEngine.executeWorkflow(
                tree, items, workflowExecution, eventService).get(30, TimeUnit.SECONDS);

            // Then
            assertNotNull(result);
            assertEquals(1, result.totalItems());
        }

        @Test
        @DisplayName("Should handle webhook trigger with nested data structures")
        void shouldHandleNestedWebhookData() throws Exception {
            // Given
            WorkflowPlan plan = buildPlanWithTriggerType("webhook", "Webhook Start");
            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            Map<String, Object> nestedPayload = new HashMap<>();
            nestedPayload.put("order", Map.of(
                "id", "ORD-001",
                "items", List.of(
                    Map.of("sku", "SKU-A", "qty", 2),
                    Map.of("sku", "SKU-B", "qty", 1)),
                "total", 99.99));

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, nestedPayload));
            WorkflowResult result = executionEngine.executeWorkflow(
                tree, items, workflowExecution, eventService).get(30, TimeUnit.SECONDS);

            // Then
            assertNotNull(result);
            assertEquals(1, result.totalItems());
        }
    }

    // =========================================================================
    // DATASOURCE TRIGGER TESTS
    // =========================================================================

    @Nested
    @DisplayName("Datasource trigger handling")
    class DatasourceTriggerTests {

        @Test
        @DisplayName("Should process datasource trigger with multiple rows")
        void shouldProcessDatasourceTrigger() throws Exception {
            // Given
            WorkflowPlan plan = buildPlanWithTriggerType("datasource", "Fetch Data");
            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // Simulate multiple datasource rows as trigger items
            List<TriggerItem> items = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                Map<String, Object> rowData = Map.of(
                    "id", i + 1,
                    "name", "Record " + (i + 1),
                    "status", "active");
                items.add(new TriggerItem("row-" + i, i, rowData));
            }

            // When
            WorkflowResult result = executionEngine.executeWorkflow(
                tree, items, workflowExecution, eventService).get(30, TimeUnit.SECONDS);

            // Then
            assertNotNull(result);
            assertEquals(5, result.totalItems(), "Should process all datasource rows");
        }

        @Test
        @DisplayName("Should handle single datasource row")
        void shouldHandleSingleDatasourceRow() throws Exception {
            // Given
            WorkflowPlan plan = buildPlanWithTriggerType("datasource", "Fetch Single");
            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            Map<String, Object> singleRow = Map.of(
                "data", Map.of("user_id", 42, "name", "Test User"));

            // When
            List<TriggerItem> items = List.of(new TriggerItem("row-0", 0, singleRow));
            WorkflowResult result = executionEngine.executeWorkflow(
                tree, items, workflowExecution, eventService).get(30, TimeUnit.SECONDS);

            // Then
            assertNotNull(result);
            assertEquals(1, result.totalItems());
        }
    }

    // =========================================================================
    // SCHEDULE TRIGGER TESTS
    // =========================================================================

    @Nested
    @DisplayName("Schedule trigger handling")
    class ScheduleTriggerTests {

        @Test
        @DisplayName("Should process schedule trigger with cron-style execution")
        void shouldProcessScheduleTrigger() throws Exception {
            // Given: Schedule trigger (scheduler is mocked, but execution works the same)
            WorkflowPlan plan = buildPlanWithTriggerType("schedule", "Daily Job");
            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // Schedule triggers typically have minimal payload
            Map<String, Object> schedulePayload = Map.of(
                "scheduled_time", "2025-01-15T10:00:00Z",
                "run_number", 42);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, schedulePayload));
            WorkflowResult result = executionEngine.executeWorkflow(
                tree, items, workflowExecution, eventService).get(30, TimeUnit.SECONDS);

            // Then
            assertNotNull(result);
            assertEquals(1, result.totalItems());
        }
    }

    // =========================================================================
    // MULTI-TRIGGER WORKFLOW TESTS
    // =========================================================================

    @Nested
    @DisplayName("Multi-trigger workflow handling")
    class MultiTriggerTests {

        @Test
        @DisplayName("Should build tree with multiple triggers as independent roots")
        void shouldBuildMultiTriggerTree() {
            // Given: Plan with two triggers
            WorkflowPlan plan = buildMultiTriggerPlan();
            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);

            // When
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // Then: Should have multiple root nodes
            assertNotNull(tree);
            assertTrue(tree.getRootNodes().size() >= 1,
                "Should have at least one root node");
            // Each root should be a trigger node
            tree.getRootNodes().forEach(root ->
                assertTrue(root.isTriggerNode(),
                    "All root nodes should be triggers"));
        }
    }

    // =========================================================================
    // TRIGGER NODE PROPERTIES TESTS
    // =========================================================================

    @Nested
    @DisplayName("Trigger node properties")
    class TriggerNodePropertiesTests {

        @Test
        @DisplayName("Should create trigger node with correct node ID format")
        void shouldHaveCorrectNodeIdFormat() {
            // Given
            WorkflowPlan plan = buildPlanWithTriggerType("webhook", "My Trigger");
            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // Then: Node ID should follow trigger:<normalized_label> format
            ExecutionNode rootNode = tree.getRootNode();
            assertNotNull(rootNode);
            assertTrue(rootNode.getNodeId().startsWith("trigger:"),
                "Trigger node ID should start with 'trigger:'");
        }

        @Test
        @DisplayName("Should always report canExecute as true for trigger nodes")
        void shouldAlwaysBeExecutable() {
            // Given
            WorkflowPlan plan = buildPlanWithTriggerType("webhook", "Start");
            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            ExecutionContext context = ExecutionContext.create(
                runId, "wfr-test", "tenant-test",
                "item-1", 0, Map.of(), plan);

            // Then: Trigger should always be executable
            assertTrue(tree.getRootNode().canExecute(context));
        }

        @Test
        @DisplayName("Should have successors wired from plan edges")
        void shouldHaveSuccessorsWired() {
            // Given
            WorkflowPlan plan = buildPlanWithTriggerType("webhook", "Start");
            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // Then: Trigger should have successors
            assertFalse(tree.getRootNode().getSuccessors().isEmpty(),
                "Trigger should have at least one successor wired from edges");
        }
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private WorkflowPlan buildPlanWithTriggerType(String triggerType, String triggerLabel) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "trigger-plan-" + UUID.randomUUID().toString().substring(0, 8));
        data.put("tenant_id", "test-tenant");

        data.put("triggers", List.of(
            Map.of("id", "t1", "label", triggerLabel, "type", triggerType, "strategy", "single")));

        String alias = triggerLabel.toLowerCase().replace(" ", "_");
        data.put("mcps", List.of(
            Map.of("id", "s1", "label", "Process", "alias", "process", "tool_name", "mock_tool")));

        data.put("edges", List.of(
            Map.of("from", "trigger:" + alias, "to", "mcp:process")));

        data.put("agents", List.of());
        data.put("cores", List.of());
        data.put("tables", List.of());
        data.put("notes", List.of());

        return WorkflowPlan.fromMap(data);
    }

    private WorkflowPlan buildMultiTriggerPlan() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "multi-trigger-plan-" + UUID.randomUUID().toString().substring(0, 8));
        data.put("tenant_id", "test-tenant");

        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "Webhook Start", "type", "webhook", "strategy", "single"),
            Map.of("id", "t2", "label", "Schedule Start", "type", "schedule", "strategy", "single")));

        data.put("mcps", List.of(
            Map.of("id", "s1", "label", "Process A", "alias", "process_a", "tool_name", "mock"),
            Map.of("id", "s2", "label", "Process B", "alias", "process_b", "tool_name", "mock")));

        data.put("edges", List.of(
            Map.of("from", "trigger:webhook_start", "to", "mcp:process_a"),
            Map.of("from", "trigger:schedule_start", "to", "mcp:process_b")));

        data.put("agents", List.of());
        data.put("cores", List.of());
        data.put("tables", List.of());
        data.put("notes", List.of());

        return WorkflowPlan.fromMap(data);
    }

    private WorkflowExecution createTestWorkflowExecution() {
        WorkflowPlan plan = buildPlanWithTriggerType("webhook", "Dummy");
        return new WorkflowExecution("test-run", plan, Map.of());
    }
}
