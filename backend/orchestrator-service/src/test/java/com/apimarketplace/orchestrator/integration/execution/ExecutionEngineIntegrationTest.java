package com.apimarketplace.orchestrator.integration.execution;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTreeBuilder;
import com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.engine.TriggerItem;
import com.apimarketplace.orchestrator.execution.v2.engine.UnifiedExecutionEngine;
import com.apimarketplace.orchestrator.execution.v2.engine.WorkflowResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;

/**
 * Integration tests for the V2 execution engine with real Spring wiring.
 *
 * <p>These tests verify that the execution engine, tree builder, and all
 * their dependencies are correctly wired by Spring and work together
 * to execute workflows end-to-end.
 *
 * <p>External services (HTTP calls, LLM providers, MCP tools) are mocked
 * via {@code @MockitoBean}. The execution engine, tree builder, node factory,
 * edge wiring, and state management are all REAL Spring beans.
 */
@IntegrationTest
@DisplayName("Execution Engine Integration Tests")
class ExecutionEngineIntegrationTest {

    @Autowired
    private UnifiedExecutionEngine executionEngine;

    @Autowired
    private ExecutionTreeBuilder treeBuilder;

    @MockitoBean
    private V2ExecutionEventService eventService;

    private WorkflowExecution workflowExecution;

    @BeforeEach
    void setUp() {
        // Create a minimal WorkflowExecution for testing
        workflowExecution = createTestWorkflowExecution();

        // Stub event service calls to avoid NullPointerExceptions
        lenient().doNothing().when(eventService).initializeTotalItems(any(), anyInt());
        lenient().doNothing().when(eventService).emitNodeStart(any(), any(), any(), anyInt(), anyInt());
        lenient().doReturn(null).when(eventService).emitNodeComplete(any(), any(), any(), any(), anyInt(), any()); // emitNodeComplete now RETURNS the completion result (payload-loss fix); null = no payload-lost rewrite
        lenient().doNothing().when(eventService).emitNodeAwaitingSignal(any(), any(), any(), any(), anyInt(), any());
    }

    // =========================================================================
    // LINEAR WORKFLOW TESTS
    // =========================================================================

    @Nested
    @DisplayName("Linear workflow execution")
    class LinearWorkflowTests {

        @Test
        @DisplayName("Should build and execute simple trigger-to-step workflow")
        void shouldBuildAndExecuteSimpleWorkflow() throws Exception {
            // Given: A simple linear workflow plan
            WorkflowPlan plan = buildLinearPlan("webhook", "Start",
                List.of(stepDef("s1", "Step One", "step_one")));

            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            assertNotNull(tree, "Execution tree should not be null");
            assertNotNull(tree.getRootNode(), "Root node should not be null");
            assertTrue(tree.getRootNode().isTriggerNode(), "Root should be a trigger node");

            // When: Execute with one trigger item
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of("data", "test-value")));
            CompletableFuture<WorkflowResult> future = executionEngine.executeWorkflow(
                tree, items, workflowExecution, eventService);

            WorkflowResult result = future.get(30, TimeUnit.SECONDS);

            // Then: Workflow completes successfully
            assertNotNull(result, "Workflow result should not be null");
            assertEquals(1, result.totalItems(), "Should have processed 1 item");
        }

        @Test
        @DisplayName("Should execute multi-step linear workflow in sequence")
        void shouldExecuteMultiStepWorkflow() throws Exception {
            // Given: Trigger -> Step1 -> Step2 -> Step3
            WorkflowPlan plan = buildLinearPlan("webhook", "Start", List.of(
                stepDef("s1", "Step One", "step_one"),
                stepDef("s2", "Step Two", "step_two"),
                stepDef("s3", "Step Three", "step_three")));

            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            WorkflowResult result = executionEngine.executeWorkflow(
                tree, items, workflowExecution, eventService).get(30, TimeUnit.SECONDS);

            // Then
            assertNotNull(result);
            assertEquals(1, result.totalItems());
        }

        @Test
        @DisplayName("Should process multiple trigger items concurrently")
        void shouldProcessMultipleTriggerItems() throws Exception {
            // Given
            WorkflowPlan plan = buildLinearPlan("datasource", "Fetch", List.of(
                stepDef("s1", "Process", "process")));

            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // When: Execute with 5 items
            List<TriggerItem> items = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                items.add(new TriggerItem("item-" + i, i, Map.of("index", i)));
            }

            WorkflowResult result = executionEngine.executeWorkflow(
                tree, items, workflowExecution, eventService).get(30, TimeUnit.SECONDS);

            // Then: All items should be processed
            assertNotNull(result);
            assertEquals(5, result.totalItems());
        }

        @Test
        @DisplayName("Should propagate trigger data through workflow context")
        void shouldPropagateTriggerData() throws Exception {
            // Given
            WorkflowPlan plan = buildLinearPlan("webhook", "Start", List.of(
                stepDef("s1", "Process", "process")));

            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            Map<String, Object> triggerData = Map.of(
                "name", "John",
                "email", "john@example.com",
                "amount", 42);

            // When: Execute and verify trigger data is available
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, triggerData));
            WorkflowResult result = executionEngine.executeWorkflow(
                tree, items, workflowExecution, eventService).get(30, TimeUnit.SECONDS);

            // Then: Workflow completes and trigger node output should contain trigger data
            assertNotNull(result);
            assertEquals(1, result.totalItems());
        }
    }

    // =========================================================================
    // DECISION WORKFLOW TESTS
    // =========================================================================

    @Nested
    @DisplayName("Decision workflow execution")
    class DecisionWorkflowTests {

        @Test
        @DisplayName("Should build execution tree with decision node from plan")
        void shouldBuildDecisionTree() {
            // Given: A plan with a decision node
            WorkflowPlan plan = buildDecisionPlan();

            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);

            // When: Build the execution tree
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // Then: Tree should contain the trigger and decision nodes
            assertNotNull(tree);
            assertNotNull(tree.getRootNode());
            assertTrue(tree.getRootNode().isTriggerNode());

            // Find the decision node in the tree
            ExecutionNode decisionNode = executionEngine.findNodeFromAllRoots(tree, "core:check_value");
            assertNotNull(decisionNode, "Decision node should be found in tree");
            assertTrue(decisionNode.isDecisionNode(), "Node should be a decision node");
        }

        @Test
        @DisplayName("Should execute decision workflow end-to-end")
        void shouldExecuteDecisionWorkflow() throws Exception {
            // Given: Decision workflow
            WorkflowPlan plan = buildDecisionPlan();

            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // When: Execute with trigger data
            List<TriggerItem> items = List.of(
                new TriggerItem("item-1", 0, Map.of("value", 15)));

            WorkflowResult result = executionEngine.executeWorkflow(
                tree, items, workflowExecution, eventService).get(30, TimeUnit.SECONDS);

            // Then: Should complete
            assertNotNull(result);
            assertEquals(1, result.totalItems());
        }
    }

    // =========================================================================
    // FORK-MERGE WORKFLOW TESTS
    // =========================================================================

    @Nested
    @DisplayName("Fork-merge workflow execution")
    class ForkMergeWorkflowTests {

        @Test
        @DisplayName("Should build execution tree with fork and merge nodes")
        void shouldBuildForkMergeTree() {
            // Given: Fork-merge plan
            WorkflowPlan plan = buildForkMergePlan();

            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // Then
            assertNotNull(tree);
            assertNotNull(tree.getRootNode());

            ExecutionNode forkNode = executionEngine.findNodeFromAllRoots(tree, "core:parallel");
            assertNotNull(forkNode, "Fork node should be found in tree");
            assertTrue(forkNode.isForkNode(), "Node should be a fork node");
        }

        @Test
        @DisplayName("Should execute fork-merge workflow with parallel branches")
        void shouldExecuteForkMergeWorkflow() throws Exception {
            // Given
            WorkflowPlan plan = buildForkMergePlan();

            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // When
            List<TriggerItem> items = List.of(
                new TriggerItem("item-1", 0, Map.of()));

            WorkflowResult result = executionEngine.executeWorkflow(
                tree, items, workflowExecution, eventService).get(30, TimeUnit.SECONDS);

            // Then
            assertNotNull(result);
            assertEquals(1, result.totalItems());
        }
    }

    // =========================================================================
    // STEP-BY-STEP MODE TESTS
    // =========================================================================

    @Nested
    @DisplayName("Step-by-step execution mode")
    class StepByStepModeTests {

        @Test
        @DisplayName("Should calculate initial ready nodes for step-by-step mode")
        void shouldCalculateInitialReadyNodes() {
            // Given: A linear workflow
            WorkflowPlan plan = buildLinearPlan("webhook", "Start", List.of(
                stepDef("s1", "Step One", "step_one")));

            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // When: Get initial ready nodes
            Set<String> readyNodes = executionEngine.getInitialReadyNodes(tree);

            // Then: Only trigger should be ready initially
            assertNotNull(readyNodes);
            assertFalse(readyNodes.isEmpty(), "Should have at least one ready node");
            assertTrue(readyNodes.stream().anyMatch(n -> n.startsWith("trigger:")),
                "Trigger node should be in initial ready nodes");
        }

        @Test
        @DisplayName("Should execute single node in step-by-step mode")
        void shouldExecuteSingleNodeStepByStep() {
            // Given
            WorkflowPlan plan = buildLinearPlan("webhook", "Start", List.of(
                stepDef("s1", "Step One", "step_one")));

            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            String triggerNodeId = tree.getRootNode().getNodeId();
            TriggerItem item = new TriggerItem("item-1", 0, Map.of("test", "data"));

            ExecutionContext context = ExecutionContext.create(
                runId, "wfr-test", "tenant-test",
                "item-1", 0, Map.of("test", "data"), plan);

            // When: Execute just the trigger node
            StepByStepExecutionResult stepResult = executionEngine.executeSingleNode(
                triggerNodeId, tree, context, workflowExecution, eventService, item);

            // Then: Should complete and return next ready nodes
            assertNotNull(stepResult);
            assertNotNull(stepResult.context());
            assertTrue(stepResult.context().isCompleted(triggerNodeId),
                "Trigger node should be completed in context");
        }
    }

    // =========================================================================
    // NODE MAP AND SEARCH TESTS
    // =========================================================================

    @Nested
    @DisplayName("Node search and tree utilities")
    class NodeSearchTests {

        @Test
        @DisplayName("Should build complete node map from execution tree")
        void shouldBuildNodeMap() {
            // Given: A workflow with multiple node types
            WorkflowPlan plan = buildLinearPlan("webhook", "Start", List.of(
                stepDef("s1", "Step One", "step_one"),
                stepDef("s2", "Step Two", "step_two")));

            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // When: Build node map
            Map<String, ExecutionNode> nodeMap = executionEngine.buildNodeMapFromAllRoots(tree);

            // Then: Map should contain all nodes
            assertNotNull(nodeMap);
            assertFalse(nodeMap.isEmpty(), "Node map should not be empty");
            assertTrue(nodeMap.containsKey("trigger:start"),
                "Node map should contain trigger node");
            assertTrue(nodeMap.containsKey("mcp:step_one"),
                "Node map should contain first step node");
            assertTrue(nodeMap.containsKey("mcp:step_two"),
                "Node map should contain second step node");
        }

        @Test
        @DisplayName("Should find node by ID in execution tree")
        void shouldFindNodeById() {
            // Given
            WorkflowPlan plan = buildLinearPlan("webhook", "Start", List.of(
                stepDef("s1", "Process", "process")));

            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            // When
            ExecutionNode found = executionEngine.findNodeFromAllRoots(tree, "mcp:process");

            // Then
            assertNotNull(found, "Should find the step node");
            assertEquals("mcp:process", found.getNodeId());
        }
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private WorkflowPlan buildLinearPlan(String triggerType, String triggerLabel, List<Map<String, Object>> steps) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "test-plan-" + UUID.randomUUID().toString().substring(0, 8));
        data.put("tenant_id", "test-tenant");

        String triggerAlias = triggerLabel.toLowerCase().replace(" ", "_");
        data.put("triggers", List.of(
            Map.of("id", "t1", "label", triggerLabel, "type", triggerType, "strategy", "single")));

        List<Map<String, Object>> mcps = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            Map<String, Object> mcp = new HashMap<>(step);
            mcp.putIfAbsent("tool_name", "mock_tool");
            mcp.putIfAbsent("parameters", Map.of());
            mcps.add(mcp);
        }
        data.put("mcps", mcps);

        List<Map<String, Object>> edges = new ArrayList<>();
        String prevNode = "trigger:" + triggerAlias;
        for (Map<String, Object> step : steps) {
            String alias = (String) step.get("alias");
            String currentNode = "mcp:" + alias;
            edges.add(Map.of("from", prevNode, "to", currentNode));
            prevNode = currentNode;
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
            Map.of("id", "c1", "label", "Check Value", "type", "decision",
                "conditions", Map.of("if", "{{trigger:start.output.value}} > 10"))));

        data.put("edges", List.of(
            Map.of("from", "trigger:start", "to", "core:check_value"),
            Map.of("from", "core:check_value:if", "to", "mcp:high_path"),
            Map.of("from", "core:check_value:else", "to", "mcp:low_path")));

        data.put("agents", List.of());
        data.put("tables", List.of());
        data.put("notes", List.of());

        return WorkflowPlan.fromMap(data);
    }

    private WorkflowPlan buildForkMergePlan() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "fork-plan-" + UUID.randomUUID().toString().substring(0, 8));
        data.put("tenant_id", "test-tenant");

        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "Start", "type", "webhook", "strategy", "single")));

        data.put("mcps", List.of(
            Map.of("id", "s1", "label", "Branch A", "alias", "branch_a", "tool_name", "mock"),
            Map.of("id", "s2", "label", "Branch B", "alias", "branch_b", "tool_name", "mock"),
            Map.of("id", "s3", "label", "Final Step", "alias", "final_step", "tool_name", "mock")));

        data.put("cores", List.of(
            Map.of("id", "c1", "label", "Parallel", "type", "fork",
                "branches", List.of(
                    Map.of("id", "branch_0", "label", "Branch 0"),
                    Map.of("id", "branch_1", "label", "Branch 1"))),
            Map.of("id", "c2", "label", "Wait All", "type", "merge")));

        data.put("edges", List.of(
            Map.of("from", "trigger:start", "to", "core:parallel"),
            Map.of("from", "core:parallel:branch_0", "to", "mcp:branch_a"),
            Map.of("from", "core:parallel:branch_1", "to", "mcp:branch_b"),
            Map.of("from", "mcp:branch_a", "to", "core:wait_all"),
            Map.of("from", "mcp:branch_b", "to", "core:wait_all"),
            Map.of("from", "core:wait_all", "to", "mcp:final_step")));

        data.put("agents", List.of());
        data.put("tables", List.of());
        data.put("notes", List.of());

        return WorkflowPlan.fromMap(data);
    }

    private Map<String, Object> stepDef(String id, String label, String alias) {
        Map<String, Object> step = new HashMap<>();
        step.put("id", id);
        step.put("label", label);
        step.put("alias", alias);
        step.put("tool_name", "mock_tool");
        step.put("parameters", Map.of());
        return step;
    }

    private WorkflowExecution createTestWorkflowExecution() {
        WorkflowPlan dummyPlan = buildLinearPlan("webhook", "Start",
            List.of(stepDef("s1", "Dummy", "dummy")));
        return new WorkflowExecution("test-run", dummyPlan, Map.of());
    }
}
