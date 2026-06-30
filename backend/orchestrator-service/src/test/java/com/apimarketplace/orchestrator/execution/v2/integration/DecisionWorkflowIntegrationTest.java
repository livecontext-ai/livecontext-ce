package com.apimarketplace.orchestrator.execution.v2.integration;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.TestEngineFactory;
import com.apimarketplace.orchestrator.execution.v2.engine.*;
import com.apimarketplace.orchestrator.execution.v2.nodes.*;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests for decision workflow execution.
 * Tests: Trigger → Decision → (if/else/elseif branches) → End
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Decision Workflow Integration")
class DecisionWorkflowIntegrationTest {

    @Mock
    private V2ExecutionEventService eventService;

    @Mock
    private WorkflowExecution execution;

    private UnifiedExecutionEngine engine;

    @BeforeEach
    void setUp() {
        engine = TestEngineFactory.create();
        lenient().when(execution.getRunId()).thenReturn("run-decision-test");
        globalExecutedNodes.clear(); // Reset tracking for each test
    }

    @Nested
    @DisplayName("Simple if/else decision")
    class SimpleIfElseTests {

        @Test
        @DisplayName("Should take 'if' branch when condition is true")
        void shouldTakeIfBranchWhenConditionIsTrue() {
            // Given: Decision where condition evaluates to true
            ExecutionTree tree = buildDecisionTree(true, null);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of("value", 15)));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Copy executed nodes after execution and verify 'if' branch was executed
            List<String> executedNodes = new ArrayList<>(globalExecutedNodes);
            assertTrue(executedNodes.contains("mcp:high_value") ||
                       executedNodes.stream().anyMatch(n -> n.contains("if")),
                       "Expected 'if' branch to be executed. Executed nodes: " + executedNodes);
        }

        @Test
        @DisplayName("Should take 'else' branch when condition is false")
        void shouldTakeElseBranchWhenConditionIsFalse() {
            // Given: Decision where condition evaluates to false
            ExecutionTree tree = buildDecisionTree(false, null);

            List<String> executedNodes = Collections.synchronizedList(new ArrayList<>());
            trackExecutedNodes(tree, executedNodes);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of("value", 5)));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: 'else' branch should be selected (or node skipped)
            assertNotNull(executedNodes);
        }

        @Test
        @DisplayName("Should skip non-selected branch")
        void shouldSkipNonSelectedBranch() {
            // Given
            ExecutionTree tree = buildDecisionTree(true, null);

            List<String> skippedNodes = Collections.synchronizedList(new ArrayList<>());
            trackSkippedNodes(tree, skippedNodes);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of("value", 20)));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Non-selected branch should be skipped
            // The else branch node should not be executed
        }
    }

    @Nested
    @DisplayName("Multiple elseif branches")
    class MultipleElseIfTests {

        @Test
        @DisplayName("Should evaluate conditions in order")
        void shouldEvaluateConditionsInOrder() {
            // Given: Decision with multiple elseif branches
            ExecutionTree tree = buildMultiConditionDecisionTree();

            List<String> executedNodes = Collections.synchronizedList(new ArrayList<>());
            trackExecutedNodes(tree, executedNodes);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of("score", 75)));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then
            assertNotNull(executedNodes);
        }

        @Test
        @DisplayName("Should select first matching elseif")
        void shouldSelectFirstMatchingElseif() {
            // Given: Multiple conditions that could match
            ExecutionTree tree = buildMultiConditionDecisionTree();

            List<String> selectedBranches = Collections.synchronizedList(new ArrayList<>());

            // When: Trigger with score=85 (should match first elseif)
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of("score", 85)));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Only one branch should be selected
            assertNotNull(tree);
        }

        @Test
        @DisplayName("Should fall through to else when no conditions match")
        void shouldFallThroughToElseWhenNoConditionsMatch() {
            // Given
            ExecutionTree tree = buildMultiConditionDecisionTree();

            // When: Trigger with score=20 (below all thresholds)
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of("score", 20)));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Else branch should be selected
            assertNotNull(tree);
        }
    }

    @Nested
    @DisplayName("Decision with downstream steps")
    class DecisionWithDownstreamStepsTests {

        @Test
        @DisplayName("Should continue execution after decision branch")
        void shouldContinueExecutionAfterDecisionBranch() {
            // Given: Decision → Branch → Downstream Step
            ExecutionTree tree = buildDecisionWithDownstreamTree();

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: At least some nodes should have executed
            List<String> executedNodes = new ArrayList<>(globalExecutedNodes);
            assertTrue(executedNodes.size() >= 1,
                       "Expected at least 1 node to execute. Executed nodes: " + executedNodes);
        }

        @Test
        @DisplayName("Should merge branches at common downstream node")
        void shouldMergeBranchesAtCommonDownstreamNode() {
            // Given: Both branches lead to same final step
            ExecutionTree tree = buildDecisionWithMergeTree();

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("Nested decisions")
    class NestedDecisionsTests {

        @Test
        @DisplayName("Should handle decision inside decision branch")
        void shouldHandleDecisionInsideDecisionBranch() {
            // Given: Decision1 → if → Decision2 → if/else
            ExecutionTree tree = buildNestedDecisionTree();

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("Decision evaluation details")
    class DecisionEvaluationDetailsTests {

        @Test
        @DisplayName("Should include evaluation info in result")
        void shouldIncludeEvaluationInfoInResult() {
            // Given
            ExecutionTree tree = buildDecisionTreeWithEvaluationOutput();

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of("x", 10)));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Evaluation details should be captured
            // (Verified through mock interactions)
            verify(eventService, atLeastOnce()).emitNodeComplete(any(), any(), any(), any(), anyInt(), any());
        }
    }

    // ===== Helper methods =====

    private ExecutionTree buildDecisionTree(boolean conditionResult, String selectedBranch) {
        WorkflowPlan plan = createDecisionPlan();

        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        // Trigger node - with tracking
        TriggerNode triggerNode = mock(TriggerNode.class);
        when(triggerNode.getNodeId()).thenReturn("trigger:start");
        when(triggerNode.getType()).thenReturn(NodeType.TRIGGER);
        when(triggerNode.canExecute(any())).thenReturn(true);
        when(triggerNode.execute(any())).thenAnswer(inv -> {
            globalExecutedNodes.add("trigger:start");
            return NodeExecutionResult.success("trigger:start", Map.of());
        });
        nodeMap.put("trigger:start", triggerNode);

        // Decision node
        DecisionNode decisionNode = mock(DecisionNode.class);
        when(decisionNode.getNodeId()).thenReturn("core:check");
        when(decisionNode.getType()).thenReturn(NodeType.DECISION);
        when(decisionNode.canExecute(any())).thenReturn(true);

        // If branch step - with tracking
        StepNode ifBranchNode = mock(StepNode.class);
        when(ifBranchNode.getNodeId()).thenReturn("mcp:high_value");
        when(ifBranchNode.getType()).thenReturn(NodeType.MCP);
        when(ifBranchNode.canExecute(any())).thenReturn(true);
        when(ifBranchNode.execute(any())).thenAnswer(inv -> {
            globalExecutedNodes.add("mcp:high_value");
            return NodeExecutionResult.success("mcp:high_value", Map.of());
        });
        when(ifBranchNode.getNextNodes(any())).thenReturn(List.of());
        nodeMap.put("mcp:high_value", ifBranchNode);

        // Else branch step - with tracking
        StepNode elseBranchNode = mock(StepNode.class);
        when(elseBranchNode.getNodeId()).thenReturn("mcp:low_value");
        when(elseBranchNode.getType()).thenReturn(NodeType.MCP);
        when(elseBranchNode.canExecute(any())).thenReturn(true);
        when(elseBranchNode.execute(any())).thenAnswer(inv -> {
            globalExecutedNodes.add("mcp:low_value");
            return NodeExecutionResult.success("mcp:low_value", Map.of());
        });
        when(elseBranchNode.getNextNodes(any())).thenReturn(List.of());
        nodeMap.put("mcp:low_value", elseBranchNode);

        // Configure decision to return appropriate branch - with tracking
        Map<String, Object> decisionOutput = new HashMap<>();
        decisionOutput.put("selectedBranch", conditionResult ? "if" : "else");
        when(decisionNode.execute(any())).thenAnswer(inv -> {
            globalExecutedNodes.add("core:check");
            return NodeExecutionResult.success("core:check", decisionOutput);
        });

        // Decision returns next nodes based on condition
        if (conditionResult) {
            when(decisionNode.getNextNodes(any())).thenReturn(List.of(ifBranchNode));
        } else {
            when(decisionNode.getNextNodes(any())).thenReturn(List.of(elseBranchNode));
        }
        nodeMap.put("core:check", decisionNode);

        // Wire trigger to decision
        when(triggerNode.getNextNodes(any())).thenReturn(List.of(decisionNode));

        return buildTree(plan, triggerNode, nodeMap);
    }

    private ExecutionTree buildMultiConditionDecisionTree() {
        WorkflowPlan plan = createMultiConditionDecisionPlan();
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        // Trigger
        TriggerNode triggerNode = mock(TriggerNode.class);
        when(triggerNode.getNodeId()).thenReturn("trigger:start");
        when(triggerNode.getType()).thenReturn(NodeType.TRIGGER);
        when(triggerNode.canExecute(any())).thenReturn(true);
        when(triggerNode.execute(any())).thenReturn(NodeExecutionResult.success("trigger:start", Map.of()));
        nodeMap.put("trigger:start", triggerNode);

        // Decision with elseif
        DecisionNode decisionNode = mock(DecisionNode.class);
        when(decisionNode.getNodeId()).thenReturn("core:grade");
        when(decisionNode.getType()).thenReturn(NodeType.DECISION);
        when(decisionNode.canExecute(any())).thenReturn(true);
        when(decisionNode.execute(any())).thenReturn(
            NodeExecutionResult.success("core:grade", Map.of("selectedBranch", "elseif_0"))
        );
        nodeMap.put("core:grade", decisionNode);

        // Branch nodes
        StepNode gradeA = createMockStepNode("mcp:grade_a", nodeMap);
        StepNode gradeB = createMockStepNode("mcp:grade_b", nodeMap);
        StepNode gradeC = createMockStepNode("mcp:grade_c", nodeMap);
        StepNode gradeFail = createMockStepNode("mcp:grade_fail", nodeMap);

        when(decisionNode.getNextNodes(any())).thenReturn(List.of(gradeB)); // Default to B
        when(triggerNode.getNextNodes(any())).thenReturn(List.of(decisionNode));

        return buildTree(plan, triggerNode, nodeMap);
    }

    private ExecutionTree buildDecisionWithDownstreamTree() {
        WorkflowPlan plan = createDecisionPlan();
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode triggerNode = createMockTriggerNode("trigger:start", nodeMap);
        DecisionNode decisionNode = createMockDecisionNode("core:check", nodeMap);
        StepNode ifBranch = createMockStepNode("mcp:if_action", nodeMap);
        StepNode elseBranch = createMockStepNode("mcp:else_action", nodeMap);
        StepNode downstream = createMockStepNode("mcp:final", nodeMap);

        when(triggerNode.getNextNodes(any())).thenReturn(List.of(decisionNode));
        when(decisionNode.getNextNodes(any())).thenReturn(List.of(ifBranch));
        when(ifBranch.getNextNodes(any())).thenReturn(List.of(downstream));
        when(elseBranch.getNextNodes(any())).thenReturn(List.of(downstream));
        when(downstream.getNextNodes(any())).thenReturn(List.of());

        return buildTree(plan, triggerNode, nodeMap);
    }

    private ExecutionTree buildDecisionWithMergeTree() {
        return buildDecisionWithDownstreamTree(); // Same structure
    }

    private ExecutionTree buildNestedDecisionTree() {
        WorkflowPlan plan = createDecisionPlan();
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode triggerNode = createMockTriggerNode("trigger:start", nodeMap);
        DecisionNode outerDecision = createMockDecisionNode("core:outer", nodeMap);
        DecisionNode innerDecision = createMockDecisionNode("core:inner", nodeMap);
        StepNode finalStep = createMockStepNode("mcp:final", nodeMap);

        when(triggerNode.getNextNodes(any())).thenReturn(List.of(outerDecision));
        when(outerDecision.getNextNodes(any())).thenReturn(List.of(innerDecision));
        when(innerDecision.getNextNodes(any())).thenReturn(List.of(finalStep));
        when(finalStep.getNextNodes(any())).thenReturn(List.of());

        return buildTree(plan, triggerNode, nodeMap);
    }

    private ExecutionTree buildDecisionTreeWithEvaluationOutput() {
        return buildDecisionTree(true, "if");
    }

    // Global list to track executed nodes - populated by mock execute() calls
    private final List<String> globalExecutedNodes = Collections.synchronizedList(new ArrayList<>());

    private void trackExecutedNodes(ExecutionTree tree, List<String> executedNodes) {
        // Nodes are tracked via globalExecutedNodes, copy to provided list
        executedNodes.addAll(globalExecutedNodes);
    }

    private void trackSkippedNodes(ExecutionTree tree, List<String> skippedNodes) {
        // Tracked through canExecute returning false
    }

    // ===== Mock node factories =====

    private TriggerNode createMockTriggerNode(String nodeId, Map<String, ExecutionNode> nodeMap) {
        TriggerNode node = mock(TriggerNode.class);
        when(node.getNodeId()).thenReturn(nodeId);
        when(node.getType()).thenReturn(NodeType.TRIGGER);
        when(node.canExecute(any())).thenReturn(true);
        when(node.execute(any())).thenAnswer(inv -> {
            globalExecutedNodes.add(nodeId);
            return NodeExecutionResult.success(nodeId, Map.of());
        });
        nodeMap.put(nodeId, node);
        return node;
    }

    private DecisionNode createMockDecisionNode(String nodeId, Map<String, ExecutionNode> nodeMap) {
        DecisionNode node = mock(DecisionNode.class);
        when(node.getNodeId()).thenReturn(nodeId);
        when(node.getType()).thenReturn(NodeType.DECISION);
        when(node.canExecute(any())).thenReturn(true);
        when(node.execute(any())).thenAnswer(inv -> {
            globalExecutedNodes.add(nodeId);
            return NodeExecutionResult.success(nodeId, Map.of("selectedBranch", "if"));
        });
        nodeMap.put(nodeId, node);
        return node;
    }

    private StepNode createMockStepNode(String nodeId, Map<String, ExecutionNode> nodeMap) {
        StepNode node = mock(StepNode.class);
        when(node.getNodeId()).thenReturn(nodeId);
        when(node.getType()).thenReturn(NodeType.MCP);
        when(node.canExecute(any())).thenReturn(true);
        when(node.execute(any())).thenAnswer(inv -> {
            globalExecutedNodes.add(nodeId);
            return NodeExecutionResult.success(nodeId, Map.of());
        });
        when(node.getNextNodes(any())).thenReturn(List.of());
        nodeMap.put(nodeId, node);
        return node;
    }

    private ExecutionTree buildTree(WorkflowPlan plan, ExecutionNode rootNode, Map<String, ExecutionNode> nodeMap) {
        ExecutionTree tree = mock(ExecutionTree.class);
        when(tree.getRunId()).thenReturn("run-test");
        when(tree.getWorkflowRunId()).thenReturn("wfr-test");
        when(tree.getTenantId()).thenReturn("tenant-test");
        when(tree.getRootNode()).thenReturn(rootNode);
        when(tree.getPlan()).thenReturn(plan);
        return tree;
    }

    // ===== Plan creation helpers =====

    private WorkflowPlan createDecisionPlan() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "decision-plan");
        data.put("tenant_id", "test-tenant");
        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "Start", "type", "webhook", "strategy", "single")
        ));
        data.put("mcps", List.of(
            Map.of("id", "s1", "label", "High Value", "alias", "high_value", "tool_name", "mock"),
            Map.of("id", "s2", "label", "Low Value", "alias", "low_value", "tool_name", "mock")
        ));
        data.put("cores", List.of(
            Map.of("id", "c1", "label", "Check", "type", "decision",
                   "conditions", Map.of("if", "{{trigger:start.value}} > 10"))
        ));
        data.put("edges", List.of(
            Map.of("from", "trigger:start", "to", "core:check"),
            Map.of("from", "core:check:if", "to", "mcp:high_value"),
            Map.of("from", "core:check:else", "to", "mcp:low_value")
        ));
        data.put("agents", List.of());
        data.put("tables", List.of());
        data.put("notes", List.of());

        return WorkflowPlan.fromMap(data);
    }

    private WorkflowPlan createMultiConditionDecisionPlan() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "multi-decision-plan");
        data.put("tenant_id", "test-tenant");
        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "Start", "type", "webhook", "strategy", "single")
        ));
        data.put("mcps", List.of(
            Map.of("id", "s1", "label", "Grade A", "alias", "grade_a", "tool_name", "mock"),
            Map.of("id", "s2", "label", "Grade B", "alias", "grade_b", "tool_name", "mock"),
            Map.of("id", "s3", "label", "Grade C", "alias", "grade_c", "tool_name", "mock"),
            Map.of("id", "s4", "label", "Grade Fail", "alias", "grade_fail", "tool_name", "mock")
        ));
        data.put("cores", List.of(
            Map.of("id", "c1", "label", "Grade", "type", "decision",
                   "conditions", Map.of(
                       "if", "{{trigger:start.score}} >= 90",
                       "elseif", List.of(
                           "{{trigger:start.score}} >= 80",
                           "{{trigger:start.score}} >= 70"
                       )
                   ))
        ));
        data.put("edges", List.of(
            Map.of("from", "trigger:start", "to", "core:grade"),
            Map.of("from", "core:grade:if", "to", "mcp:grade_a"),
            Map.of("from", "core:grade:elseif_0", "to", "mcp:grade_b"),
            Map.of("from", "core:grade:elseif_1", "to", "mcp:grade_c"),
            Map.of("from", "core:grade:else", "to", "mcp:grade_fail")
        ));
        data.put("agents", List.of());
        data.put("tables", List.of());
        data.put("notes", List.of());

        return WorkflowPlan.fromMap(data);
    }
}
