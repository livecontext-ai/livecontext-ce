package com.apimarketplace.orchestrator.integration.execution;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTreeBuilder;
import com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.engine.TriggerItem;
import com.apimarketplace.orchestrator.execution.v2.engine.UnifiedExecutionEngine;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;

/**
 * Integration tests for workflow resume (step-by-step execution mode).
 *
 * <p>Tests the ability to execute a workflow node-by-node, simulating
 * resume-from-step scenarios. The step-by-step mode executes one node
 * at a time and returns the set of next ready nodes.
 *
 * <p>This tests the real Spring-wired execution engine in step-by-step
 * mode, verifying correct node transitions and context propagation.
 */
@IntegrationTest
@DisplayName("Resume Service Integration Tests")
class ResumeServiceIntegrationTest {

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
    // STEP-BY-STEP RESUME TESTS
    // =========================================================================

    @Nested
    @DisplayName("Resume from specific step")
    class ResumeFromStepTests {

        @Test
        @DisplayName("Should execute trigger node and get next ready nodes")
        void shouldExecuteTriggerAndGetReadyNodes() {
            // Given: A linear workflow
            WorkflowPlan plan = buildLinearPlan(List.of("Step One", "Step Two"));
            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            String triggerNodeId = tree.getRootNode().getNodeId();
            TriggerItem item = new TriggerItem("item-1", 0, Map.of("key", "value"));
            ExecutionContext context = ExecutionContext.create(
                runId, "wfr-test", "tenant-test",
                "item-1", 0, Map.of("key", "value"), plan);

            // When: Execute the trigger node
            StepByStepExecutionResult result = executionEngine.executeSingleNode(
                triggerNodeId, tree, context, workflowExecution, eventService, item);

            // Then: Trigger should complete and next nodes should include step_one
            assertNotNull(result);
            assertNotNull(result.context());
            assertTrue(result.context().isCompleted(triggerNodeId),
                "Trigger should be completed");
            assertNotNull(result.readyNodes());
            assertFalse(result.readyNodes().isEmpty(),
                "Should have ready nodes after trigger completion");
            assertTrue(result.readyNodes().contains("mcp:step_one"),
                "Step One should be ready after trigger");
        }

        @Test
        @DisplayName("Should chain step-by-step execution through linear workflow")
        void shouldChainStepByStepExecution() {
            // Given: Trigger -> Step1 -> Step2
            WorkflowPlan plan = buildLinearPlan(List.of("Step One", "Step Two"));
            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            TriggerItem item = new TriggerItem("item-1", 0, Map.of());
            ExecutionContext context = ExecutionContext.create(
                runId, "wfr-test", "tenant-test",
                "item-1", 0, Map.of(), plan);

            // Step 1: Execute trigger
            String triggerNodeId = tree.getRootNode().getNodeId();
            StepByStepExecutionResult step1 = executionEngine.executeSingleNode(
                triggerNodeId, tree, context, workflowExecution, eventService, item);

            assertTrue(step1.context().isCompleted(triggerNodeId));
            assertTrue(step1.readyNodes().contains("mcp:step_one"));

            // Step 2: Execute Step One using updated context
            StepByStepExecutionResult step2 = executionEngine.executeSingleNode(
                "mcp:step_one", tree, step1.context(), workflowExecution, eventService, item);

            assertTrue(step2.context().isCompleted("mcp:step_one"));
            assertTrue(step2.readyNodes().contains("mcp:step_two"),
                "Step Two should be ready after Step One completion");

            // Step 3: Execute Step Two
            StepByStepExecutionResult step3 = executionEngine.executeSingleNode(
                "mcp:step_two", tree, step2.context(), workflowExecution, eventService, item);

            assertTrue(step3.context().isCompleted("mcp:step_two"));
            assertTrue(step3.readyNodes().isEmpty(),
                "No more nodes should be ready after final step");
        }

        @Test
        @DisplayName("Should propagate context between steps")
        void shouldPropagateContextBetweenSteps() {
            // Given
            WorkflowPlan plan = buildLinearPlan(List.of("Process"));
            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            Map<String, Object> triggerData = Map.of("name", "John", "value", 42);
            TriggerItem item = new TriggerItem("item-1", 0, triggerData);
            ExecutionContext context = ExecutionContext.create(
                runId, "wfr-test", "tenant-test",
                "item-1", 0, triggerData, plan);

            // When: Execute trigger
            StepByStepExecutionResult triggerResult = executionEngine.executeSingleNode(
                tree.getRootNode().getNodeId(), tree, context, workflowExecution, eventService, item);

            // Then: Trigger output should be in context for next step
            ExecutionContext updatedContext = triggerResult.context();
            assertTrue(updatedContext.getStepOutput(tree.getRootNode().getNodeId()).isPresent(),
                "Trigger output should be available in context");
        }
    }

    // =========================================================================
    // RESUME AFTER FAILURE TESTS
    // =========================================================================

    @Nested
    @DisplayName("Resume after failure")
    class ResumeAfterFailureTests {

        @Test
        @DisplayName("Should allow re-execution after building fresh context from persisted state")
        void shouldAllowReExecutionAfterFailure() {
            // Given: A workflow where we simulate recovery
            WorkflowPlan plan = buildLinearPlan(List.of("Step One", "Step Two"));
            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            TriggerItem item = new TriggerItem("item-1", 0, Map.of());

            // Simulate: Trigger already completed (recovered from persistence)
            ExecutionContext recoveredContext = ExecutionContext.create(
                runId, "wfr-test", "tenant-test",
                "item-1", 0, Map.of(), plan);
            recoveredContext = recoveredContext.withResult(
                tree.getRootNode().getNodeId(),
                NodeExecutionResult.success(tree.getRootNode().getNodeId(),
                    Map.of("trigger_id", "t1")));

            // When: Resume from Step One (as if trigger was already done)
            StepByStepExecutionResult result = executionEngine.executeSingleNode(
                "mcp:step_one", tree, recoveredContext, workflowExecution, eventService, item);

            // Then: Step One should execute successfully
            assertNotNull(result);
            assertTrue(result.context().isCompleted("mcp:step_one"));
        }

        @Test
        @DisplayName("Should handle non-existent node ID gracefully")
        void shouldHandleNonExistentNodeId() {
            // Given
            WorkflowPlan plan = buildLinearPlan(List.of("Process"));
            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            TriggerItem item = new TriggerItem("item-1", 0, Map.of());
            ExecutionContext context = ExecutionContext.create(
                runId, "wfr-test", "tenant-test",
                "item-1", 0, Map.of(), plan);

            // When/Then: Should throw for non-existent node
            assertThrows(IllegalArgumentException.class, () ->
                executionEngine.executeSingleNode(
                    "mcp:non_existent", tree, context, workflowExecution, eventService, item));
        }
    }

    // =========================================================================
    // STEP RERUN TESTS
    // =========================================================================

    @Nested
    @DisplayName("Step rerun")
    class StepRerunTests {

        @Test
        @DisplayName("Should allow re-executing a previously completed step")
        void shouldAllowReExecution() {
            // Given: A workflow where trigger and step1 already completed
            WorkflowPlan plan = buildLinearPlan(List.of("Step One", "Step Two"));
            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            TriggerItem item = new TriggerItem("item-1", 0, Map.of());

            // Execute trigger first
            ExecutionContext context = ExecutionContext.create(
                runId, "wfr-test", "tenant-test",
                "item-1", 0, Map.of(), plan);

            StepByStepExecutionResult triggerResult = executionEngine.executeSingleNode(
                tree.getRootNode().getNodeId(), tree, context, workflowExecution, eventService, item);

            // Execute Step One
            StepByStepExecutionResult step1Result = executionEngine.executeSingleNode(
                "mcp:step_one", tree, triggerResult.context(), workflowExecution, eventService, item);

            assertTrue(step1Result.context().isCompleted("mcp:step_one"));

            // When: Re-execute Step One (simulating rerun)
            // Note: In a real rerun, the context would be rebuilt without step1's result
            ExecutionContext contextWithoutStep1 = triggerResult.context();

            StepByStepExecutionResult rerunResult = executionEngine.executeSingleNode(
                "mcp:step_one", tree, contextWithoutStep1, workflowExecution, eventService, item);

            // Then: Step should execute again successfully
            assertNotNull(rerunResult);
            assertTrue(rerunResult.context().isCompleted("mcp:step_one"));
        }
    }

    // =========================================================================
    // DECISION STEP-BY-STEP TESTS
    // =========================================================================

    @Nested
    @DisplayName("Decision workflow in step-by-step mode")
    class DecisionStepByStepTests {

        @Test
        @DisplayName("Should execute decision node and return selected branch as ready")
        void shouldExecuteDecisionStepByStep() {
            // Given
            WorkflowPlan plan = buildDecisionPlan();
            String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
            ExecutionTree tree = treeBuilder.build(runId, "wfr-test", "tenant-test", plan);

            Map<String, Object> triggerData = Map.of("value", 15);
            TriggerItem item = new TriggerItem("item-1", 0, triggerData);
            ExecutionContext context = ExecutionContext.create(
                runId, "wfr-test", "tenant-test",
                "item-1", 0, triggerData, plan);

            // Step 1: Execute trigger
            String triggerNodeId = tree.getRootNode().getNodeId();
            StepByStepExecutionResult triggerResult = executionEngine.executeSingleNode(
                triggerNodeId, tree, context, workflowExecution, eventService, item);

            assertTrue(triggerResult.context().isCompleted(triggerNodeId));

            // Step 2: Execute decision node
            Set<String> readyAfterTrigger = triggerResult.readyNodes();
            assertTrue(readyAfterTrigger.contains("core:check"),
                "Decision node should be ready after trigger");

            StepByStepExecutionResult decisionResult = executionEngine.executeSingleNode(
                "core:check", tree, triggerResult.context(), workflowExecution, eventService, item);

            // Then: Decision should complete and one branch should be ready
            assertTrue(decisionResult.context().isCompleted("core:check"));
            Set<String> readyAfterDecision = decisionResult.readyNodes();
            assertNotNull(readyAfterDecision);
            // At least one branch step should be ready
            assertTrue(readyAfterDecision.contains("mcp:high_path") ||
                       readyAfterDecision.contains("mcp:low_path"),
                "At least one branch should be ready after decision. Ready nodes: " + readyAfterDecision);
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
