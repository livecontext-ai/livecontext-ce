package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.execution.v2.state.ExecutionState;
import com.apimarketplace.orchestrator.services.resume.MergeNodeAnalyzer;
import com.apimarketplace.orchestrator.validation.DAGIndependenceValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive tests for ReadyNodeCalculator with multi-trigger DAG support.
 *
 * Covers:
 * - Merge detection with multi-trigger predecessors
 * - Foreign trigger predecessor filtering
 * - Ready node calculation with various trigger configurations
 * - Stress tests with many triggers and deep chains
 * - Regression tests for standard merge behavior
 * - Mixed shared-DAG + independent trigger scenarios
 *
 * DAG sharing is auto-detected from edge topology: two triggers share a DAG
 * if their BFS descendants (computed from plan edges) overlap.
 */
@DisplayName("ReadyNodeCalculator - Multi-Trigger DAG")
class MultiTriggerReadyNodeCalculatorTest {

    private ReadyNodeCalculator calculator;
    private MergeNodeAnalyzer mergeNodeAnalyzer;
    private DAGIndependenceValidator dagValidator;

    @BeforeEach
    void setUp() {
        mergeNodeAnalyzer = new MergeNodeAnalyzer();
        dagValidator = new DAGIndependenceValidator();
        calculator = new ReadyNodeCalculator(mergeNodeAnalyzer, null, dagValidator);
    }

    // ===== Helper methods =====

    private Trigger createTrigger(String label, String type) {
        return new Trigger(label, label, "single", type);
    }

    private Edge createEdge(String from, String to) {
        return new Edge(from, to, null);
    }

    private WorkflowPlan createPlan(List<Trigger> triggers, List<Edge> edges) {
        return new WorkflowPlan("test-plan", "tenant1", triggers, List.of(),
            List.of(), edges, List.of(), List.of(), List.of(), List.of(), Map.of());
    }

    /**
     * Build an ExecutionContext with given trigger completed and step outputs populated.
     */
    private ExecutionContext buildContext(String triggerId, int epoch, Map<String, Object> stepOutputs, WorkflowPlan plan) {
        ExecutionState state = ExecutionState.create();
        // Mark all nodes in stepOutputs as completed
        for (Map.Entry<String, Object> entry : stepOutputs.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> output = entry.getValue() instanceof Map ?
                (Map<String, Object>) entry.getValue() : Map.of();
            state = state.recordResult(entry.getKey(), NodeExecutionResult.success(entry.getKey(), output));
        }
        return new ExecutionContext(
            "run1", "wfRun1", "tenant1",
            null, 0,
            triggerId, epoch, 0,
            Map.of("data", "test"),
            new HashMap<>(stepOutputs),
            state, plan
        );
    }

    /**
     * Build execution tree from root nodes and a plan.
     */
    private ExecutionTree buildTree(WorkflowPlan plan, List<TestExecutionNode> roots) {
        return new ExecutionTree(
            "run1", "wfRun1", "tenant1", plan,
            new ArrayList<>(roots),
            com.apimarketplace.orchestrator.domain.workflow.ExecutionMode.STEP_BY_STEP
        );
    }

    /**
     * Minimal ExecutionNode implementation for testing.
     */
    static class TestExecutionNode implements ExecutionNode {
        private final String nodeId;
        private final NodeType type;
        private final List<ExecutionNode> children = new ArrayList<>();
        private final List<String> predecessorIds = new ArrayList<>();

        TestExecutionNode(String nodeId, String typeStr) {
            this.nodeId = nodeId;
            this.type = parseNodeType(typeStr);
        }

        private static NodeType parseNodeType(String typeStr) {
            if (typeStr == null) return NodeType.MCP;
            return switch (typeStr.toLowerCase()) {
                case "trigger" -> NodeType.TRIGGER;
                case "mcp" -> NodeType.MCP;
                case "decision" -> NodeType.DECISION;
                case "switch" -> NodeType.SWITCH;
                case "merge" -> NodeType.MERGE;
                case "fork" -> NodeType.FORK;
                case "agent" -> NodeType.AGENT;
                default -> NodeType.MCP;
            };
        }

        @Override public String getNodeId() { return nodeId; }
        @Override public NodeType getType() { return type; }
        @Override public boolean isTriggerNode() { return type == NodeType.TRIGGER; }
        @Override public boolean isAgentNode() { return type == NodeType.AGENT; }
        @Override public boolean isDecisionNode() { return type == NodeType.DECISION; }
        @Override public boolean isSwitchNode() { return type == NodeType.SWITCH; }
        @Override public boolean isBranchingNode() { return isDecisionNode() || isSwitchNode(); }
        @Override public List<ExecutionNode> getAllChildNodes() { return children; }
        @Override public List<String> getPredecessorIds() { return predecessorIds; }

        @Override
        public boolean canExecute(ExecutionContext context) {
            return predecessorIds.stream()
                .allMatch(pred -> context.isCompleted(pred));
        }

        @Override
        public NodeExecutionResult execute(ExecutionContext context) {
            return NodeExecutionResult.success(nodeId, Map.of());
        }

        @Override
        public void onComplete(ExecutionContext context, NodeExecutionResult result) {
            // no-op for test
        }

        @Override
        public List<ExecutionNode> getNextNodes(NodeExecutionResult result) {
            return children;
        }

        @Override
        public Collection<ExecutionNode> getAllCategoryTargetNodes() {
            return List.of();
        }

        void addChild(ExecutionNode child) {
            children.add(child);
        }

        public void addPredecessor(String predId) {
            predecessorIds.add(predId);
        }
    }

    // =========================================================================
    // Merge Detection Tests
    // =========================================================================

    @Nested
    @DisplayName("Merge node detection with multi-trigger predecessors")
    class MergeDetectionTests {

        @Test
        @DisplayName("Node with 2 trigger predecessors sharing a DAG is detected as merge node")
        void multiTriggerNodeIsMergeNode() {
            // Both triggers connect to the same node -> they share a DAG (auto-detected from edges)
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1")
                )
            );

            assertThat(mergeNodeAnalyzer.isMergeNode(plan, "mcp:step1")).isTrue();
        }

        @Test
        @DisplayName("Node with 3 trigger predecessors is also a merge node")
        void threeTriggersStillMergeNode() {
            WorkflowPlan plan = createPlan(
                List.of(
                    createTrigger("webhook", "webhook"),
                    createTrigger("manual", "manual"),
                    createTrigger("chat", "chat")
                ),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1"),
                    createEdge("trigger:chat", "mcp:step1")
                )
            );

            assertThat(mergeNodeAnalyzer.isMergeNode(plan, "mcp:step1")).isTrue();
            assertThat(mergeNodeAnalyzer.findPredecessorsFromEdges(plan, "mcp:step1"))
                .containsExactlyInAnyOrder("trigger:webhook", "trigger:manual", "trigger:chat");
        }

        @Test
        @DisplayName("Predecessors of multi-trigger node include both triggers")
        void predecessorsIncludeBothTriggers() {
            WorkflowPlan plan = createPlan(
                List.of(
                    createTrigger("webhook", "webhook"),
                    createTrigger("manual", "manual")
                ),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1")
                )
            );

            List<String> predecessors = mergeNodeAnalyzer.findPredecessorsFromEdges(plan, "mcp:step1");
            assertThat(predecessors).containsExactlyInAnyOrder("trigger:webhook", "trigger:manual");
        }

        @Test
        @DisplayName("Node with single trigger predecessor is NOT a merge node")
        void singleTriggerNotMergeNode() {
            WorkflowPlan plan = createPlan(
                List.of(createTrigger("webhook", "webhook")),
                List.of(createEdge("trigger:webhook", "mcp:step1"))
            );

            assertThat(mergeNodeAnalyzer.isMergeNode(plan, "mcp:step1")).isFalse();
        }

        @Test
        @DisplayName("Mixed predecessors: trigger + non-trigger is a merge node")
        void mixedPredecessorsIsMergeNode() {
            WorkflowPlan plan = createPlan(
                List.of(
                    createTrigger("webhook", "webhook"),
                    createTrigger("manual", "manual")
                ),
                List.of(
                    createEdge("trigger:webhook", "mcp:enrich"),
                    createEdge("trigger:manual", "mcp:enrich"),
                    createEdge("mcp:enrich", "core:merge_point"),
                    createEdge("mcp:side_task", "core:merge_point")
                )
            );

            assertThat(mergeNodeAnalyzer.isMergeNode(plan, "core:merge_point")).isTrue();
            assertThat(mergeNodeAnalyzer.findPredecessorsFromEdges(plan, "core:merge_point"))
                .containsExactlyInAnyOrder("mcp:enrich", "mcp:side_task");
        }
    }

    // =========================================================================
    // Core Ready Node Calculation
    // =========================================================================

    @Nested
    @DisplayName("calculateReadyNodes - core multi-trigger scenarios")
    class CalculateReadyNodesTests {

        @Test
        @DisplayName("Multi-trigger node becomes ready when ONE trigger fires (not ALL)")
        void nodeReadyWhenOneTriggerFires() {
            // Both triggers share a DAG (edges overlap at mcp:step1)
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1"),
                    createEdge("mcp:step1", "mcp:step2")
                )
            );

            TestExecutionNode triggerWebhook = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode triggerManual = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode step1 = new TestExecutionNode("mcp:step1", "mcp");
            TestExecutionNode step2 = new TestExecutionNode("mcp:step2", "mcp");

            triggerWebhook.addChild(step1);
            triggerManual.addChild(step1);
            step1.addChild(step2);
            step1.addPredecessor("trigger:webhook");
            step1.addPredecessor("trigger:manual");
            step2.addPredecessor("mcp:step1");

            ExecutionTree tree = buildTree(plan, List.of(triggerWebhook, triggerManual));

            // Only trigger:webhook completed
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of("data", "test"));
            ExecutionContext context = buildContext("trigger:webhook", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            assertThat(readyNodes).contains("mcp:step1");
            assertThat(readyNodes).doesNotContain("mcp:step2"); // step1 hasn't completed yet
        }

        @Test
        @DisplayName("Second trigger fires instead of first - node still becomes ready")
        void secondTriggerFiresNodeStillReady() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1")
                )
            );

            TestExecutionNode triggerWebhook = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode triggerManual = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode step1 = new TestExecutionNode("mcp:step1", "mcp");

            triggerWebhook.addChild(step1);
            triggerManual.addChild(step1);
            step1.addPredecessor("trigger:webhook");
            step1.addPredecessor("trigger:manual");

            ExecutionTree tree = buildTree(plan, List.of(triggerWebhook, triggerManual));

            // Only trigger:manual completed (NOT webhook)
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:manual", Map.of("source", "manual"));
            ExecutionContext context = buildContext("trigger:manual", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            assertThat(readyNodes).contains("mcp:step1");
        }

        @Test
        @DisplayName("3 triggers sharing a DAG - any single trigger fires -> node ready")
        void threeTriggersAnyOneFires() {
            WorkflowPlan plan = createPlan(
                List.of(
                    createTrigger("webhook", "webhook"),
                    createTrigger("manual", "manual"),
                    createTrigger("chat", "chat")
                ),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1"),
                    createEdge("trigger:chat", "mcp:step1")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode tc = new TestExecutionNode("trigger:chat", "trigger");
            TestExecutionNode step1 = new TestExecutionNode("mcp:step1", "mcp");

            tw.addChild(step1);
            tm.addChild(step1);
            tc.addChild(step1);
            step1.addPredecessor("trigger:webhook");
            step1.addPredecessor("trigger:manual");
            step1.addPredecessor("trigger:chat");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm, tc));

            // Only chat trigger fires
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:chat", Map.of("message", "hello"));
            ExecutionContext context = buildContext("trigger:chat", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            assertThat(readyNodes).contains("mcp:step1");
        }

        @Test
        @DisplayName("Deep chain: after first node completes, second node becomes ready")
        void deepChainSecondNodeReady() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1"),
                    createEdge("mcp:step1", "mcp:step2"),
                    createEdge("mcp:step2", "mcp:step3")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode step1 = new TestExecutionNode("mcp:step1", "mcp");
            TestExecutionNode step2 = new TestExecutionNode("mcp:step2", "mcp");
            TestExecutionNode step3 = new TestExecutionNode("mcp:step3", "mcp");

            tw.addChild(step1);
            tm.addChild(step1);
            step1.addChild(step2);
            step2.addChild(step3);
            step1.addPredecessor("trigger:webhook");
            step1.addPredecessor("trigger:manual");
            step2.addPredecessor("mcp:step1");
            step3.addPredecessor("mcp:step2");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm));

            // trigger:webhook AND mcp:step1 completed
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of("data", "test"));
            outputs.put("mcp:step1", Map.of("result", "enriched"));
            ExecutionContext context = buildContext("trigger:webhook", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            assertThat(readyNodes).contains("mcp:step2");
            assertThat(readyNodes).doesNotContain("mcp:step3");
        }

        @Test
        @DisplayName("Full chain completion: trigger -> step1 -> step2 all done -> step3 ready")
        void fullChainCompletionLastNodeReady() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1"),
                    createEdge("mcp:step1", "mcp:step2"),
                    createEdge("mcp:step2", "mcp:step3")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode step1 = new TestExecutionNode("mcp:step1", "mcp");
            TestExecutionNode step2 = new TestExecutionNode("mcp:step2", "mcp");
            TestExecutionNode step3 = new TestExecutionNode("mcp:step3", "mcp");

            tw.addChild(step1);
            tm.addChild(step1);
            step1.addChild(step2);
            step2.addChild(step3);
            step1.addPredecessor("trigger:webhook");
            step1.addPredecessor("trigger:manual");
            step2.addPredecessor("mcp:step1");
            step3.addPredecessor("mcp:step2");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm));

            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of("data", "test"));
            outputs.put("mcp:step1", Map.of("result", "ok"));
            outputs.put("mcp:step2", Map.of("result", "done"));
            ExecutionContext context = buildContext("trigger:webhook", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            assertThat(readyNodes).contains("mcp:step3");
        }
    }

    // =========================================================================
    // Regression: Standard merge behavior preserved
    // =========================================================================

    @Nested
    @DisplayName("Regression: standard merge behavior preserved (separate DAG graphs)")
    class RegressionMergeBehaviorTests {

        @Test
        @DisplayName("Separate DAG graphs: merge node still waits for ALL predecessors")
        void separateGraphsMergeWaitsForAll() {
            // Triggers have separate graphs (no shared descendants) BUT merge node
            // has non-trigger predecessors from both graphs - standard merge behavior
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    // Separate trigger graphs converging at a merge via non-trigger predecessors
                    createEdge("trigger:webhook", "mcp:task_a"),
                    createEdge("trigger:manual", "mcp:task_b"),
                    createEdge("mcp:task_a", "core:merge_all"),
                    createEdge("mcp:task_b", "core:merge_all")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode taskA = new TestExecutionNode("mcp:task_a", "mcp");
            TestExecutionNode taskB = new TestExecutionNode("mcp:task_b", "mcp");
            TestExecutionNode mergeAll = new TestExecutionNode("core:merge_all", "merge");

            tw.addChild(taskA);
            tm.addChild(taskB);
            taskA.addChild(mergeAll);
            taskB.addChild(mergeAll);
            taskA.addPredecessor("trigger:webhook");
            taskB.addPredecessor("trigger:manual");
            mergeAll.addPredecessor("mcp:task_a");
            mergeAll.addPredecessor("mcp:task_b");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm));

            // Only task_a completed (task_b hasn't)
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of());
            outputs.put("mcp:task_a", Map.of("result", "a"));
            ExecutionContext context = buildContext("trigger:webhook", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // Merge should NOT be ready - task_b hasn't completed
            assertThat(readyNodes).doesNotContain("core:merge_all");
        }

        @Test
        @DisplayName("Non-trigger merge node after multi-trigger still requires ALL non-trigger predecessors")
        void nonTriggerMergeStillRequiresAll() {
            // Both triggers share a DAG (edges overlap at mcp:entry)
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            // Two parallel paths after trigger, converging at a merge
            //   trigger:webhook -+
            //   trigger:manual  -+-> mcp:entry -> mcp:path_a -+
            //                                  -> mcp:path_b -+-> mcp:merge_point
            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:entry"),
                    createEdge("trigger:manual", "mcp:entry"),
                    createEdge("mcp:entry", "mcp:path_a"),
                    createEdge("mcp:entry", "mcp:path_b"),
                    createEdge("mcp:path_a", "mcp:merge_point"),
                    createEdge("mcp:path_b", "mcp:merge_point")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode entry = new TestExecutionNode("mcp:entry", "mcp");
            TestExecutionNode pathA = new TestExecutionNode("mcp:path_a", "mcp");
            TestExecutionNode pathB = new TestExecutionNode("mcp:path_b", "mcp");
            TestExecutionNode mergePoint = new TestExecutionNode("mcp:merge_point", "mcp");

            tw.addChild(entry);
            tm.addChild(entry);
            entry.addChild(pathA);
            entry.addChild(pathB);
            pathA.addChild(mergePoint);
            pathB.addChild(mergePoint);
            entry.addPredecessor("trigger:webhook");
            entry.addPredecessor("trigger:manual");
            pathA.addPredecessor("mcp:entry");
            pathB.addPredecessor("mcp:entry");
            mergePoint.addPredecessor("mcp:path_a");
            mergePoint.addPredecessor("mcp:path_b");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm));

            // trigger + entry + path_a completed, but path_b NOT
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of());
            outputs.put("mcp:entry", Map.of());
            outputs.put("mcp:path_a", Map.of("result", "a"));
            ExecutionContext context = buildContext("trigger:webhook", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // merge_point should NOT be ready (waiting for path_b)
            assertThat(readyNodes).doesNotContain("mcp:merge_point");
        }

        @Test
        @DisplayName("Non-trigger merge node after multi-trigger: both paths done -> merge ready")
        void nonTriggerMergeAllDone() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:entry"),
                    createEdge("trigger:manual", "mcp:entry"),
                    createEdge("mcp:entry", "mcp:path_a"),
                    createEdge("mcp:entry", "mcp:path_b"),
                    createEdge("mcp:path_a", "mcp:merge_point"),
                    createEdge("mcp:path_b", "mcp:merge_point")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode entry = new TestExecutionNode("mcp:entry", "mcp");
            TestExecutionNode pathA = new TestExecutionNode("mcp:path_a", "mcp");
            TestExecutionNode pathB = new TestExecutionNode("mcp:path_b", "mcp");
            TestExecutionNode mergePoint = new TestExecutionNode("mcp:merge_point", "mcp");

            tw.addChild(entry);
            tm.addChild(entry);
            entry.addChild(pathA);
            entry.addChild(pathB);
            pathA.addChild(mergePoint);
            pathB.addChild(mergePoint);
            entry.addPredecessor("trigger:webhook");
            entry.addPredecessor("trigger:manual");
            pathA.addPredecessor("mcp:entry");
            pathB.addPredecessor("mcp:entry");
            mergePoint.addPredecessor("mcp:path_a");
            mergePoint.addPredecessor("mcp:path_b");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm));

            // ALL done
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of());
            outputs.put("mcp:entry", Map.of());
            outputs.put("mcp:path_a", Map.of("result", "a"));
            outputs.put("mcp:path_b", Map.of("result", "b"));
            ExecutionContext context = buildContext("trigger:webhook", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            assertThat(readyNodes).contains("mcp:merge_point");
        }
    }

    // =========================================================================
    // Mixed shared-DAG + independent triggers
    // =========================================================================

    @Nested
    @DisplayName("Mixed shared-DAG + independent triggers")
    class MixedDagGroupTests {

        @Test
        @DisplayName("Shared-DAG triggers share graph, independent trigger has own graph - both work")
        void mixedGroupsIndependentExecution() {
            // webhook and manual share a DAG (both connect to mcp:api_entry)
            // schedule is independent (connects to mcp:cron_task only)
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");
            Trigger t3 = createTrigger("schedule", "schedule");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2, t3),
                List.of(
                    createEdge("trigger:webhook", "mcp:api_entry"),
                    createEdge("trigger:manual", "mcp:api_entry"),
                    createEdge("mcp:api_entry", "mcp:api_process"),
                    createEdge("trigger:schedule", "mcp:cron_task")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode ts = new TestExecutionNode("trigger:schedule", "trigger");
            TestExecutionNode apiEntry = new TestExecutionNode("mcp:api_entry", "mcp");
            TestExecutionNode apiProcess = new TestExecutionNode("mcp:api_process", "mcp");
            TestExecutionNode cronTask = new TestExecutionNode("mcp:cron_task", "mcp");

            tw.addChild(apiEntry);
            tm.addChild(apiEntry);
            apiEntry.addChild(apiProcess);
            ts.addChild(cronTask);
            apiEntry.addPredecessor("trigger:webhook");
            apiEntry.addPredecessor("trigger:manual");
            apiProcess.addPredecessor("mcp:api_entry");
            cronTask.addPredecessor("trigger:schedule");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm, ts));

            // Only webhook fires
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of("payload", "data"));
            ExecutionContext context = buildContext("trigger:webhook", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // api_entry should be ready (foreign trigger:manual filtered out - same DAG detected from edges)
            assertThat(readyNodes).contains("mcp:api_entry");
            // schedule trigger should be available as initial ready node
            assertThat(readyNodes).contains("trigger:schedule");
        }

        @Test
        @DisplayName("Two shared-DAG groups: each fires independently without blocking the other")
        void twoSharedDagGroupsFireIndependently() {
            // Group 1: webhook + form share mcp:api_call
            // Group 2: schedule + datasource share mcp:cron_job
            // The two groups have completely separate descendant graphs
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("form", "form");
            Trigger t3 = createTrigger("schedule", "schedule");
            Trigger t4 = createTrigger("datasource", "datasource");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2, t3, t4),
                List.of(
                    createEdge("trigger:webhook", "mcp:api_call"),
                    createEdge("trigger:form", "mcp:api_call"),
                    createEdge("trigger:schedule", "mcp:cron_job"),
                    createEdge("trigger:datasource", "mcp:cron_job")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tf = new TestExecutionNode("trigger:form", "trigger");
            TestExecutionNode ts = new TestExecutionNode("trigger:schedule", "trigger");
            TestExecutionNode td = new TestExecutionNode("trigger:datasource", "trigger");
            TestExecutionNode apiCall = new TestExecutionNode("mcp:api_call", "mcp");
            TestExecutionNode cronJob = new TestExecutionNode("mcp:cron_job", "mcp");

            tw.addChild(apiCall);
            tf.addChild(apiCall);
            ts.addChild(cronJob);
            td.addChild(cronJob);
            apiCall.addPredecessor("trigger:webhook");
            apiCall.addPredecessor("trigger:form");
            cronJob.addPredecessor("trigger:schedule");
            cronJob.addPredecessor("trigger:datasource");

            ExecutionTree tree = buildTree(plan, List.of(tw, tf, ts, td));

            // Webhook fires in api group
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of());
            ExecutionContext context = buildContext("trigger:webhook", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // api_call should be ready (form filtered out as foreign in same shared DAG)
            assertThat(readyNodes).contains("mcp:api_call");
            // cron triggers should still be available
            assertThat(readyNodes).contains("trigger:schedule");
            assertThat(readyNodes).contains("trigger:datasource");
        }
    }

    // =========================================================================
    // Complex graph patterns
    // =========================================================================

    @Nested
    @DisplayName("Complex graph patterns with multi-trigger")
    class ComplexGraphPatterns {

        @Test
        @DisplayName("Multi-trigger -> fork -> two branches -> merge: merge waits for both branches")
        void multiTriggerForkMerge() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:entry"),
                    createEdge("trigger:manual", "mcp:entry"),
                    createEdge("mcp:entry", "mcp:branch_a"),
                    createEdge("mcp:entry", "mcp:branch_b"),
                    createEdge("mcp:branch_a", "mcp:final"),
                    createEdge("mcp:branch_b", "mcp:final")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode entry = new TestExecutionNode("mcp:entry", "mcp");
            TestExecutionNode branchA = new TestExecutionNode("mcp:branch_a", "mcp");
            TestExecutionNode branchB = new TestExecutionNode("mcp:branch_b", "mcp");
            TestExecutionNode finalNode = new TestExecutionNode("mcp:final", "mcp");

            tw.addChild(entry);
            tm.addChild(entry);
            entry.addChild(branchA);
            entry.addChild(branchB);
            branchA.addChild(finalNode);
            branchB.addChild(finalNode);
            entry.addPredecessor("trigger:webhook");
            entry.addPredecessor("trigger:manual");
            branchA.addPredecessor("mcp:entry");
            branchB.addPredecessor("mcp:entry");
            finalNode.addPredecessor("mcp:branch_a");
            finalNode.addPredecessor("mcp:branch_b");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm));

            // Only branch_a completed, branch_b not
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of());
            outputs.put("mcp:entry", Map.of());
            outputs.put("mcp:branch_a", Map.of("result", "a"));
            ExecutionContext context = buildContext("trigger:webhook", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // final should NOT be ready (branch_b hasn't completed)
            assertThat(readyNodes).doesNotContain("mcp:final");
            // branch_b should be ready (entry completed)
            assertThat(readyNodes).contains("mcp:branch_b");
        }

        @Test
        @DisplayName("Multi-trigger -> fork -> merge: both branches done -> merge ready")
        void multiTriggerForkMergeBothDone() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:entry"),
                    createEdge("trigger:manual", "mcp:entry"),
                    createEdge("mcp:entry", "mcp:branch_a"),
                    createEdge("mcp:entry", "mcp:branch_b"),
                    createEdge("mcp:branch_a", "mcp:final"),
                    createEdge("mcp:branch_b", "mcp:final")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode entry = new TestExecutionNode("mcp:entry", "mcp");
            TestExecutionNode branchA = new TestExecutionNode("mcp:branch_a", "mcp");
            TestExecutionNode branchB = new TestExecutionNode("mcp:branch_b", "mcp");
            TestExecutionNode finalNode = new TestExecutionNode("mcp:final", "mcp");

            tw.addChild(entry);
            tm.addChild(entry);
            entry.addChild(branchA);
            entry.addChild(branchB);
            branchA.addChild(finalNode);
            branchB.addChild(finalNode);
            entry.addPredecessor("trigger:webhook");
            entry.addPredecessor("trigger:manual");
            branchA.addPredecessor("mcp:entry");
            branchB.addPredecessor("mcp:entry");
            finalNode.addPredecessor("mcp:branch_a");
            finalNode.addPredecessor("mcp:branch_b");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm));

            // ALL done
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of());
            outputs.put("mcp:entry", Map.of());
            outputs.put("mcp:branch_a", Map.of());
            outputs.put("mcp:branch_b", Map.of());
            ExecutionContext context = buildContext("trigger:webhook", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            assertThat(readyNodes).contains("mcp:final");
        }

        @Test
        @DisplayName("Diamond: 2 triggers -> nodeA -> nodeB -> merge, trigger filtering only at entry")
        void diamondPatternFilteringOnlyAtEntry() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            // Diamond: triggers -> entry -> (left, right) -> converge
            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:entry"),
                    createEdge("trigger:manual", "mcp:entry"),
                    createEdge("mcp:entry", "mcp:left"),
                    createEdge("mcp:entry", "mcp:right"),
                    createEdge("mcp:left", "mcp:converge"),
                    createEdge("mcp:right", "mcp:converge"),
                    createEdge("mcp:converge", "agent:notify")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode entry = new TestExecutionNode("mcp:entry", "mcp");
            TestExecutionNode left = new TestExecutionNode("mcp:left", "mcp");
            TestExecutionNode right = new TestExecutionNode("mcp:right", "mcp");
            TestExecutionNode converge = new TestExecutionNode("mcp:converge", "mcp");
            TestExecutionNode notify = new TestExecutionNode("agent:notify", "agent");

            tw.addChild(entry);
            tm.addChild(entry);
            entry.addChild(left);
            entry.addChild(right);
            left.addChild(converge);
            right.addChild(converge);
            converge.addChild(notify);
            entry.addPredecessor("trigger:webhook");
            entry.addPredecessor("trigger:manual");
            left.addPredecessor("mcp:entry");
            right.addPredecessor("mcp:entry");
            converge.addPredecessor("mcp:left");
            converge.addPredecessor("mcp:right");
            notify.addPredecessor("mcp:converge");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm));

            // trigger fires, entry done, left+right done, converge done
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of());
            outputs.put("mcp:entry", Map.of());
            outputs.put("mcp:left", Map.of());
            outputs.put("mcp:right", Map.of());
            outputs.put("mcp:converge", Map.of());
            ExecutionContext context = buildContext("trigger:webhook", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            assertThat(readyNodes).contains("agent:notify");
        }
    }

    // =========================================================================
    // Stress Tests
    // =========================================================================

    @Nested
    @DisplayName("Stress tests - many triggers, deep chains")
    class StressTests {

        @Test
        @DisplayName("STRESS: 5 triggers sharing a DAG -> shared entry -> 10-node chain")
        void stress5TriggersDeepChain() {
            int numTriggers = 5;
            int chainLength = 10;

            List<Trigger> triggers = new ArrayList<>();
            List<Edge> edges = new ArrayList<>();
            List<TestExecutionNode> triggerNodes = new ArrayList<>();

            // Create triggers - all connect to mcp:shared_entry so they share a DAG
            for (int i = 0; i < numTriggers; i++) {
                triggers.add(createTrigger("t" + i, "webhook"));
                edges.add(createEdge("trigger:t" + i, "mcp:shared_entry"));
            }

            // Create chain after entry
            String prev = "mcp:shared_entry";
            for (int i = 0; i < chainLength; i++) {
                String next = "mcp:chain_" + i;
                edges.add(createEdge(prev, next));
                prev = next;
            }

            WorkflowPlan plan = createPlan(triggers, edges);

            // Build execution tree
            TestExecutionNode sharedEntry = new TestExecutionNode("mcp:shared_entry", "mcp");
            for (int i = 0; i < numTriggers; i++) {
                TestExecutionNode tn = new TestExecutionNode("trigger:t" + i, "trigger");
                tn.addChild(sharedEntry);
                sharedEntry.addPredecessor("trigger:t" + i);
                triggerNodes.add(tn);
            }

            // Build chain nodes
            TestExecutionNode prevNode = sharedEntry;
            List<TestExecutionNode> chainNodes = new ArrayList<>();
            for (int i = 0; i < chainLength; i++) {
                TestExecutionNode chainNode = new TestExecutionNode("mcp:chain_" + i, "mcp");
                chainNode.addPredecessor(prevNode.getNodeId());
                prevNode.addChild(chainNode);
                chainNodes.add(chainNode);
                prevNode = chainNode;
            }

            ExecutionTree tree = buildTree(plan, triggerNodes);

            // Only trigger 3 fires (middle one)
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:t3", Map.of("source", "trigger3"));
            ExecutionContext context = buildContext("trigger:t3", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // Shared entry should be ready (other 4 triggers filtered out - same DAG detected from edges)
            assertThat(readyNodes).contains("mcp:shared_entry");
        }

        @Test
        @DisplayName("STRESS: 5 triggers, trigger fires -> entire chain completes -> last node ready")
        void stress5TriggersEntireChainComplete() {
            int numTriggers = 5;
            int chainLength = 5;

            List<Trigger> triggers = new ArrayList<>();
            List<Edge> edges = new ArrayList<>();
            List<TestExecutionNode> triggerNodes = new ArrayList<>();

            for (int i = 0; i < numTriggers; i++) {
                triggers.add(createTrigger("t" + i, "webhook"));
                edges.add(createEdge("trigger:t" + i, "mcp:entry"));
            }

            String prev = "mcp:entry";
            for (int i = 0; i < chainLength; i++) {
                String next = "mcp:step_" + i;
                edges.add(createEdge(prev, next));
                prev = next;
            }

            WorkflowPlan plan = createPlan(triggers, edges);

            // Build tree
            TestExecutionNode entry = new TestExecutionNode("mcp:entry", "mcp");
            for (int i = 0; i < numTriggers; i++) {
                TestExecutionNode tn = new TestExecutionNode("trigger:t" + i, "trigger");
                tn.addChild(entry);
                entry.addPredecessor("trigger:t" + i);
                triggerNodes.add(tn);
            }

            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:t0", Map.of());
            outputs.put("mcp:entry", Map.of());

            TestExecutionNode prevNode = entry;
            for (int i = 0; i < chainLength; i++) {
                TestExecutionNode stepNode = new TestExecutionNode("mcp:step_" + i, "mcp");
                stepNode.addPredecessor(prevNode.getNodeId());
                prevNode.addChild(stepNode);
                if (i < chainLength - 1) {
                    outputs.put("mcp:step_" + i, Map.of());
                }
                prevNode = stepNode;
            }

            ExecutionTree tree = buildTree(plan, triggerNodes);
            ExecutionContext context = buildContext("trigger:t0", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // Last node in chain should be ready
            assertThat(readyNodes).contains("mcp:step_" + (chainLength - 1));
        }

        @Test
        @DisplayName("STRESS: 10 triggers sharing a DAG - trigger 7 fires -> entry ready")
        void stress10Triggers() {
            int numTriggers = 10;

            List<Trigger> triggers = new ArrayList<>();
            List<Edge> edges = new ArrayList<>();
            List<TestExecutionNode> triggerNodes = new ArrayList<>();

            for (int i = 0; i < numTriggers; i++) {
                triggers.add(createTrigger("t" + i, "webhook"));
                edges.add(createEdge("trigger:t" + i, "mcp:mega_entry"));
            }
            edges.add(createEdge("mcp:mega_entry", "mcp:next"));

            WorkflowPlan plan = createPlan(triggers, edges);

            TestExecutionNode megaEntry = new TestExecutionNode("mcp:mega_entry", "mcp");
            TestExecutionNode next = new TestExecutionNode("mcp:next", "mcp");
            megaEntry.addChild(next);
            next.addPredecessor("mcp:mega_entry");

            for (int i = 0; i < numTriggers; i++) {
                TestExecutionNode tn = new TestExecutionNode("trigger:t" + i, "trigger");
                tn.addChild(megaEntry);
                megaEntry.addPredecessor("trigger:t" + i);
                triggerNodes.add(tn);
            }

            ExecutionTree tree = buildTree(plan, triggerNodes);

            // Trigger 7 fires
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:t7", Map.of("fired_by", "trigger7"));
            ExecutionContext context = buildContext("trigger:t7", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            assertThat(readyNodes).contains("mcp:mega_entry");
        }

        @Test
        @DisplayName("STRESS: 3 shared-DAG groups x 3 triggers each, all independent, one fires per group")
        void stressThreeGroupsThreeTriggersEach() {
            // Each group has 3 triggers sharing a common entry node.
            // Groups are independent (no shared descendants between groups).
            List<Trigger> triggers = new ArrayList<>();
            List<Edge> edges = new ArrayList<>();
            List<TestExecutionNode> triggerNodes = new ArrayList<>();
            Map<String, TestExecutionNode> entryNodes = new HashMap<>();

            String[] groups = {"alpha", "beta", "gamma"};
            for (String group : groups) {
                TestExecutionNode entryNode = new TestExecutionNode("mcp:" + group + "_entry", "mcp");
                entryNodes.put(group, entryNode);

                for (int i = 0; i < 3; i++) {
                    String label = group + "_t" + i;
                    triggers.add(createTrigger(label, "webhook"));
                    edges.add(createEdge("trigger:" + label, "mcp:" + group + "_entry"));

                    TestExecutionNode tn = new TestExecutionNode("trigger:" + label, "trigger");
                    tn.addChild(entryNode);
                    entryNode.addPredecessor("trigger:" + label);
                    triggerNodes.add(tn);
                }
            }

            WorkflowPlan plan = createPlan(triggers, edges);
            ExecutionTree tree = buildTree(plan, triggerNodes);

            // Fire alpha_t1
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:alpha_t1", Map.of());
            ExecutionContext context = buildContext("trigger:alpha_t1", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // alpha entry should be ready
            assertThat(readyNodes).contains("mcp:alpha_entry");
            // beta and gamma triggers should be available (not yet fired)
            assertThat(readyNodes).containsAnyOf("trigger:beta_t0", "trigger:beta_t1", "trigger:beta_t2");
            assertThat(readyNodes).containsAnyOf("trigger:gamma_t0", "trigger:gamma_t1", "trigger:gamma_t2");
        }
    }

    // =========================================================================
    // Edge Cases
    // =========================================================================

    @Nested
    @DisplayName("Edge cases and boundary conditions")
    class EdgeCases {

        @Test
        @DisplayName("Single trigger behaves like normal (no filtering)")
        void singleTriggerNoFiltering() {
            Trigger t1 = createTrigger("webhook", "webhook");

            WorkflowPlan plan = createPlan(
                List.of(t1),
                List.of(createEdge("trigger:webhook", "mcp:step1"))
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode step1 = new TestExecutionNode("mcp:step1", "mcp");

            tw.addChild(step1);
            step1.addPredecessor("trigger:webhook");

            ExecutionTree tree = buildTree(plan, List.of(tw));

            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of());
            ExecutionContext context = buildContext("trigger:webhook", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            assertThat(readyNodes).contains("mcp:step1");
        }

        @Test
        @DisplayName("Node with no predecessors is always ready (root-like)")
        void nodeWithNoPredecessorsAlwaysReady() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode step1 = new TestExecutionNode("mcp:step1", "mcp");
            // Deliberately NOT adding predecessors to step1

            tw.addChild(step1);
            tm.addChild(step1);

            ExecutionTree tree = buildTree(plan, List.of(tw, tm));

            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of());
            ExecutionContext context = buildContext("trigger:webhook", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // step1 has no predecessors set on the node -> canExecute=true -> ready
            assertThat(readyNodes).contains("mcp:step1");
        }

        @Test
        @DisplayName("Trigger node that already completed is not returned as ready")
        void completedTriggerNotReadyAgain() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode step1 = new TestExecutionNode("mcp:step1", "mcp");

            tw.addChild(step1);
            tm.addChild(step1);
            step1.addPredecessor("trigger:webhook");
            step1.addPredecessor("trigger:manual");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm));

            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of());
            ExecutionContext context = buildContext("trigger:webhook", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // trigger:webhook should NOT be in ready nodes (it's already completed)
            assertThat(readyNodes).doesNotContain("trigger:webhook");
        }

        @Test
        @DisplayName("Multi-trigger with different epoch numbers - epoch doesn't affect filtering")
        void differentEpochsNoEffect() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode step1 = new TestExecutionNode("mcp:step1", "mcp");

            tw.addChild(step1);
            tm.addChild(step1);
            step1.addPredecessor("trigger:webhook");
            step1.addPredecessor("trigger:manual");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm));

            // Webhook fires at epoch=5
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of("data", "epoch5"));
            ExecutionContext context = buildContext("trigger:webhook", 5, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            assertThat(readyNodes).contains("mcp:step1");
        }

        @Test
        @DisplayName("No plan in tree -> no filtering happens (graceful degradation)")
        void noPlanGracefulDegradation() {
            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode step1 = new TestExecutionNode("mcp:step1", "mcp");

            tw.addChild(step1);
            step1.addPredecessor("trigger:webhook");

            // Create tree with null plan
            ExecutionTree tree = new ExecutionTree(
                "run1", "wfRun1", "tenant1", null,
                List.of(tw),
                com.apimarketplace.orchestrator.domain.workflow.ExecutionMode.STEP_BY_STEP
            );

            ExecutionState state = ExecutionState.create()
                .recordResult("trigger:webhook", NodeExecutionResult.success("trigger:webhook", Map.of()));
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of());
            ExecutionContext context = new ExecutionContext(
                "run1", "wfRun1", "tenant1",
                null, 0, "trigger:webhook", 1, 0,
                Map.of(), outputs, state, null
            );

            // Should not crash
            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);
            assertThat(readyNodes).contains("mcp:step1");
        }
    }

    // =========================================================================
    // filterForeignTriggerPredecessors - dedicated tests via calculateReadyNodes
    // =========================================================================

    @Nested
    @DisplayName("Foreign trigger predecessor filtering behavior")
    class FilteringBehaviorTests {

        @Test
        @DisplayName("0 trigger predecessors: no filtering (non-trigger merge works normally)")
        void zeroTriggerPredecessorsNoFiltering() {
            Trigger t1 = createTrigger("webhook", "webhook");

            WorkflowPlan plan = createPlan(
                List.of(t1),
                List.of(
                    createEdge("trigger:webhook", "mcp:a"),
                    createEdge("trigger:webhook", "mcp:b"),
                    createEdge("mcp:a", "mcp:merge_point"),
                    createEdge("mcp:b", "mcp:merge_point")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode a = new TestExecutionNode("mcp:a", "mcp");
            TestExecutionNode b = new TestExecutionNode("mcp:b", "mcp");
            TestExecutionNode merge = new TestExecutionNode("mcp:merge_point", "mcp");

            tw.addChild(a);
            tw.addChild(b);
            a.addChild(merge);
            b.addChild(merge);
            a.addPredecessor("trigger:webhook");
            b.addPredecessor("trigger:webhook");
            merge.addPredecessor("mcp:a");
            merge.addPredecessor("mcp:b");

            ExecutionTree tree = buildTree(plan, List.of(tw));

            // Only mcp:a completed (not mcp:b)
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of());
            outputs.put("mcp:a", Map.of());
            ExecutionContext context = buildContext("trigger:webhook", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // merge_point should NOT be ready (b not done)
            assertThat(readyNodes).doesNotContain("mcp:merge_point");
            assertThat(readyNodes).contains("mcp:b");
        }

        @Test
        @DisplayName("1 trigger predecessor: no filtering even when triggers share a DAG")
        void oneTriggerPredecessorNoFiltering() {
            // Both triggers share a DAG (their descendants overlap at mcp:shared_downstream)
            // but step1 only has ONE trigger predecessor, so no filtering applies
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            // Only webhook connects to step1 (manual connects to other)
            // Both share descendants via mcp:shared_downstream
            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:other"),
                    createEdge("mcp:step1", "mcp:shared_downstream"),
                    createEdge("mcp:other", "mcp:shared_downstream")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode step1 = new TestExecutionNode("mcp:step1", "mcp");
            TestExecutionNode other = new TestExecutionNode("mcp:other", "mcp");
            TestExecutionNode shared = new TestExecutionNode("mcp:shared_downstream", "mcp");

            tw.addChild(step1);
            tm.addChild(other);
            step1.addChild(shared);
            other.addChild(shared);
            step1.addPredecessor("trigger:webhook");
            other.addPredecessor("trigger:manual");
            shared.addPredecessor("mcp:step1");
            shared.addPredecessor("mcp:other");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm));

            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of());
            ExecutionContext context = buildContext("trigger:webhook", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            assertThat(readyNodes).contains("mcp:step1");
        }

        @Test
        @DisplayName("Triggers with separate graphs: NO filtering (standard merge wait)")
        void separateGraphsNoFiltering() {
            // Each trigger connects to its OWN exclusive node, no shared descendants
            // -> areTriggersInSameDagGroup returns false -> standard merge behavior
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:webhook_step"),
                    createEdge("trigger:manual", "mcp:manual_step"),
                    createEdge("mcp:webhook_step", "mcp:shared"),
                    createEdge("mcp:manual_step", "mcp:shared")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode webhookStep = new TestExecutionNode("mcp:webhook_step", "mcp");
            TestExecutionNode manualStep = new TestExecutionNode("mcp:manual_step", "mcp");
            TestExecutionNode shared = new TestExecutionNode("mcp:shared", "mcp");

            tw.addChild(webhookStep);
            tm.addChild(manualStep);
            webhookStep.addChild(shared);
            manualStep.addChild(shared);
            webhookStep.addPredecessor("trigger:webhook");
            manualStep.addPredecessor("trigger:manual");
            shared.addPredecessor("mcp:webhook_step");
            shared.addPredecessor("mcp:manual_step");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm));

            // Only webhook fires - webhook_step done, manual_step NOT done
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of());
            outputs.put("mcp:webhook_step", Map.of());
            ExecutionContext context = buildContext("trigger:webhook", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // shared should NOT be ready - triggers share descendants (via mcp:shared) so they ARE
            // in the same DAG group, but the merge predecessors are non-trigger nodes (webhook_step, manual_step)
            // and manual_step hasn't completed. This is standard non-trigger merge behavior.
            assertThat(readyNodes).doesNotContain("mcp:shared");
        }

        @Test
        @DisplayName("Mixed: trigger predecessors from same shared DAG + non-trigger predecessor")
        void mixedTriggerAndNonTriggerPredecessors() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            // mcp:merge has 3 predecessors: 2 triggers (same shared DAG) + 1 non-trigger
            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:merge"),
                    createEdge("trigger:manual", "mcp:merge"),
                    createEdge("mcp:side_task", "mcp:merge")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode sideTask = new TestExecutionNode("mcp:side_task", "mcp");
            TestExecutionNode mergeNode = new TestExecutionNode("mcp:merge", "mcp");

            tw.addChild(mergeNode);
            tm.addChild(mergeNode);
            sideTask.addChild(mergeNode);
            mergeNode.addPredecessor("trigger:webhook");
            mergeNode.addPredecessor("trigger:manual");
            mergeNode.addPredecessor("mcp:side_task");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm));

            // Only webhook fires (manual filtered), but side_task NOT done
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of());
            ExecutionContext context = buildContext("trigger:webhook", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // merge should NOT be ready - side_task is still required
            assertThat(readyNodes).doesNotContain("mcp:merge");
        }

        @Test
        @DisplayName("Mixed: trigger predecessors from same shared DAG + non-trigger predecessor ALL done -> ready")
        void mixedTriggerAndNonTriggerAllDone() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:merge"),
                    createEdge("trigger:manual", "mcp:merge"),
                    createEdge("mcp:side_task", "mcp:merge")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode sideTask = new TestExecutionNode("mcp:side_task", "mcp");
            TestExecutionNode mergeNode = new TestExecutionNode("mcp:merge", "mcp");

            tw.addChild(mergeNode);
            tm.addChild(mergeNode);
            sideTask.addChild(mergeNode);
            mergeNode.addPredecessor("trigger:webhook");
            mergeNode.addPredecessor("trigger:manual");
            mergeNode.addPredecessor("mcp:side_task");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm));

            // webhook + side_task done (manual filtered)
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of());
            outputs.put("mcp:side_task", Map.of());
            ExecutionContext context = buildContext("trigger:webhook", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(context, tree);

            // merge should be ready - webhook is the only trigger needed + side_task done
            assertThat(readyNodes).contains("mcp:merge");
        }
    }

    // =========================================================================
    // Advanced Topology: Nested Diamonds
    // =========================================================================

    @Nested
    @DisplayName("Advanced topology: nested diamonds with multi-trigger")
    class NestedDiamondTests {

        @Test
        @DisplayName("Nested diamonds: 2 triggers -> diamond1 -> diamond2 -> end")
        void nestedDiamondsFullExecution() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:entry"),
                    createEdge("trigger:manual", "mcp:entry"),
                    // Diamond 1
                    createEdge("mcp:entry", "mcp:d1_left"),
                    createEdge("mcp:entry", "mcp:d1_right"),
                    createEdge("mcp:d1_left", "mcp:d1_merge"),
                    createEdge("mcp:d1_right", "mcp:d1_merge"),
                    // Diamond 2
                    createEdge("mcp:d1_merge", "mcp:d2_left"),
                    createEdge("mcp:d1_merge", "mcp:d2_right"),
                    createEdge("mcp:d2_left", "mcp:d2_merge"),
                    createEdge("mcp:d2_right", "mcp:d2_merge"),
                    // End
                    createEdge("mcp:d2_merge", "agent:end")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode entry = new TestExecutionNode("mcp:entry", "mcp");
            TestExecutionNode d1L = new TestExecutionNode("mcp:d1_left", "mcp");
            TestExecutionNode d1R = new TestExecutionNode("mcp:d1_right", "mcp");
            TestExecutionNode d1M = new TestExecutionNode("mcp:d1_merge", "mcp");
            TestExecutionNode d2L = new TestExecutionNode("mcp:d2_left", "mcp");
            TestExecutionNode d2R = new TestExecutionNode("mcp:d2_right", "mcp");
            TestExecutionNode d2M = new TestExecutionNode("mcp:d2_merge", "mcp");
            TestExecutionNode end = new TestExecutionNode("agent:end", "agent");

            tw.addChild(entry); tm.addChild(entry);
            entry.addChild(d1L); entry.addChild(d1R);
            d1L.addChild(d1M); d1R.addChild(d1M);
            d1M.addChild(d2L); d1M.addChild(d2R);
            d2L.addChild(d2M); d2R.addChild(d2M);
            d2M.addChild(end);

            entry.addPredecessor("trigger:webhook"); entry.addPredecessor("trigger:manual");
            d1L.addPredecessor("mcp:entry"); d1R.addPredecessor("mcp:entry");
            d1M.addPredecessor("mcp:d1_left"); d1M.addPredecessor("mcp:d1_right");
            d2L.addPredecessor("mcp:d1_merge"); d2R.addPredecessor("mcp:d1_merge");
            d2M.addPredecessor("mcp:d2_left"); d2M.addPredecessor("mcp:d2_right");
            end.addPredecessor("mcp:d2_merge");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm));

            // Phase 1: trigger fires -> entry ready
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of());
            ExecutionContext ctx1 = buildContext("trigger:webhook", 1, outputs, plan);
            assertThat(calculator.calculateReadyNodes(ctx1, tree)).contains("mcp:entry");

            // Phase 2: entry done -> both d1 branches ready
            outputs.put("mcp:entry", Map.of());
            ExecutionContext ctx2 = buildContext("trigger:webhook", 1, outputs, plan);
            Set<String> ready2 = calculator.calculateReadyNodes(ctx2, tree);
            assertThat(ready2).contains("mcp:d1_left", "mcp:d1_right");

            // Phase 3: d1_left done, d1_right NOT -> d1_merge NOT ready
            outputs.put("mcp:d1_left", Map.of());
            ExecutionContext ctx3 = buildContext("trigger:webhook", 1, outputs, plan);
            assertThat(calculator.calculateReadyNodes(ctx3, tree)).doesNotContain("mcp:d1_merge");

            // Phase 4: both d1 branches done -> d1_merge ready
            outputs.put("mcp:d1_right", Map.of());
            ExecutionContext ctx4 = buildContext("trigger:webhook", 1, outputs, plan);
            assertThat(calculator.calculateReadyNodes(ctx4, tree)).contains("mcp:d1_merge");

            // Phase 5: d1_merge done -> d2 branches ready
            outputs.put("mcp:d1_merge", Map.of());
            ExecutionContext ctx5 = buildContext("trigger:webhook", 1, outputs, plan);
            Set<String> ready5 = calculator.calculateReadyNodes(ctx5, tree);
            assertThat(ready5).contains("mcp:d2_left", "mcp:d2_right");

            // Phase 6: all done -> agent:end ready
            outputs.put("mcp:d2_left", Map.of());
            outputs.put("mcp:d2_right", Map.of());
            outputs.put("mcp:d2_merge", Map.of());
            ExecutionContext ctx6 = buildContext("trigger:webhook", 1, outputs, plan);
            assertThat(calculator.calculateReadyNodes(ctx6, tree)).contains("agent:end");
        }

        @Test
        @DisplayName("Diamond with one trigger - no filtering, standard merge behavior")
        void diamondSingleTrigger() {
            Trigger t1 = createTrigger("webhook", "webhook");

            WorkflowPlan plan = createPlan(
                List.of(t1),
                List.of(
                    createEdge("trigger:webhook", "mcp:entry"),
                    createEdge("mcp:entry", "mcp:left"),
                    createEdge("mcp:entry", "mcp:right"),
                    createEdge("mcp:left", "mcp:merge"),
                    createEdge("mcp:right", "mcp:merge")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode entry = new TestExecutionNode("mcp:entry", "mcp");
            TestExecutionNode left = new TestExecutionNode("mcp:left", "mcp");
            TestExecutionNode right = new TestExecutionNode("mcp:right", "mcp");
            TestExecutionNode merge = new TestExecutionNode("mcp:merge", "mcp");

            tw.addChild(entry); entry.addChild(left); entry.addChild(right);
            left.addChild(merge); right.addChild(merge);
            entry.addPredecessor("trigger:webhook");
            left.addPredecessor("mcp:entry"); right.addPredecessor("mcp:entry");
            merge.addPredecessor("mcp:left"); merge.addPredecessor("mcp:right");

            ExecutionTree tree = buildTree(plan, List.of(tw));

            // Only left done -> merge NOT ready
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of());
            outputs.put("mcp:entry", Map.of());
            outputs.put("mcp:left", Map.of());
            ExecutionContext ctx = buildContext("trigger:webhook", 1, outputs, plan);
            assertThat(calculator.calculateReadyNodes(ctx, tree)).doesNotContain("mcp:merge");

            // Both done -> merge ready
            outputs.put("mcp:right", Map.of());
            ExecutionContext ctx2 = buildContext("trigger:webhook", 1, outputs, plan);
            assertThat(calculator.calculateReadyNodes(ctx2, tree)).contains("mcp:merge");
        }
    }

    // =========================================================================
    // Concurrent Trigger Execution Simulation
    // =========================================================================

    @Nested
    @DisplayName("Concurrent trigger execution simulation")
    class ConcurrentTriggerTests {

        @Test
        @DisplayName("Shared DAG: webhook fires epoch 1, then manual fires epoch 2 - independent")
        void sequentialTriggerFiresDifferentEpochs() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1"),
                    createEdge("mcp:step1", "mcp:step2")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode step1 = new TestExecutionNode("mcp:step1", "mcp");
            TestExecutionNode step2 = new TestExecutionNode("mcp:step2", "mcp");

            tw.addChild(step1); tm.addChild(step1); step1.addChild(step2);
            step1.addPredecessor("trigger:webhook"); step1.addPredecessor("trigger:manual");
            step2.addPredecessor("mcp:step1");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm));

            // Epoch 1: webhook fires
            Map<String, Object> epoch1Out = new HashMap<>();
            epoch1Out.put("trigger:webhook", Map.of("data", "from_webhook"));
            ExecutionContext ctx1 = buildContext("trigger:webhook", 1, epoch1Out, plan);
            assertThat(calculator.calculateReadyNodes(ctx1, tree)).contains("mcp:step1");

            // Epoch 2: manual fires (independent epoch, own state)
            Map<String, Object> epoch2Out = new HashMap<>();
            epoch2Out.put("trigger:manual", Map.of("data", "from_manual"));
            ExecutionContext ctx2 = buildContext("trigger:manual", 2, epoch2Out, plan);
            assertThat(calculator.calculateReadyNodes(ctx2, tree)).contains("mcp:step1");
        }

        @Test
        @DisplayName("Epoch 1 fully completes, epoch 2 starts fresh - no leakage")
        void noStateLeakageBetweenEpochs() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1"),
                    createEdge("mcp:step1", "mcp:step2")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode step1 = new TestExecutionNode("mcp:step1", "mcp");
            TestExecutionNode step2 = new TestExecutionNode("mcp:step2", "mcp");

            tw.addChild(step1); tm.addChild(step1); step1.addChild(step2);
            step1.addPredecessor("trigger:webhook"); step1.addPredecessor("trigger:manual");
            step2.addPredecessor("mcp:step1");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm));

            // Epoch 2: fresh state, manual fires, only trigger:manual output
            Map<String, Object> freshOutputs = new HashMap<>();
            freshOutputs.put("trigger:manual", Map.of("fresh", true));
            ExecutionContext fresh = buildContext("trigger:manual", 2, freshOutputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(fresh, tree);
            // step1 should be ready (only trigger:manual needed)
            assertThat(readyNodes).contains("mcp:step1");
            // step2 should NOT be ready (step1 hasn't completed in this epoch)
            assertThat(readyNodes).doesNotContain("mcp:step2");
        }

        @Test
        @DisplayName("3 triggers: each fires in sequence - each epoch independently resolves")
        void threeTriggersSequentialFires() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");
            Trigger t3 = createTrigger("chat", "chat");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2, t3),
                List.of(
                    createEdge("trigger:webhook", "mcp:process"),
                    createEdge("trigger:manual", "mcp:process"),
                    createEdge("trigger:chat", "mcp:process"),
                    createEdge("mcp:process", "agent:respond")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode tc = new TestExecutionNode("trigger:chat", "trigger");
            TestExecutionNode process = new TestExecutionNode("mcp:process", "mcp");
            TestExecutionNode respond = new TestExecutionNode("agent:respond", "agent");

            tw.addChild(process); tm.addChild(process); tc.addChild(process);
            process.addChild(respond);
            process.addPredecessor("trigger:webhook");
            process.addPredecessor("trigger:manual");
            process.addPredecessor("trigger:chat");
            respond.addPredecessor("mcp:process");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm, tc));

            // Each trigger fires independently -> process is always ready
            for (int epoch = 1; epoch <= 3; epoch++) {
                String[] triggerIds = {"trigger:webhook", "trigger:manual", "trigger:chat"};
                String triggerId = triggerIds[epoch - 1];

                Map<String, Object> outputs = new HashMap<>();
                outputs.put(triggerId, Map.of("epoch", epoch));
                ExecutionContext ctx = buildContext(triggerId, epoch, outputs, plan);

                Set<String> readyNodes = calculator.calculateReadyNodes(ctx, tree);
                assertThat(readyNodes)
                    .as("Epoch %d: trigger %s fires -> process should be ready", epoch, triggerId)
                    .contains("mcp:process");
            }
        }
    }

    // =========================================================================
    // Stress: Wide Fan-Out with Multi-Trigger
    // =========================================================================

    @Nested
    @DisplayName("Stress: wide fan-out patterns")
    class WideFanOutStressTests {

        @Test
        @DisplayName("STRESS: 3 triggers -> entry -> 10 parallel branches (no merge)")
        void wideFanOutNoMerge() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");
            Trigger t3 = createTrigger("chat", "chat");

            List<Edge> edges = new ArrayList<>();
            edges.add(createEdge("trigger:webhook", "mcp:entry"));
            edges.add(createEdge("trigger:manual", "mcp:entry"));
            edges.add(createEdge("trigger:chat", "mcp:entry"));

            List<TestExecutionNode> branchNodes = new ArrayList<>();
            TestExecutionNode entryNode = new TestExecutionNode("mcp:entry", "mcp");
            entryNode.addPredecessor("trigger:webhook");
            entryNode.addPredecessor("trigger:manual");
            entryNode.addPredecessor("trigger:chat");

            for (int i = 0; i < 10; i++) {
                String branchId = "mcp:branch_" + i;
                edges.add(createEdge("mcp:entry", branchId));
                TestExecutionNode branchNode = new TestExecutionNode(branchId, "mcp");
                branchNode.addPredecessor("mcp:entry");
                entryNode.addChild(branchNode);
                branchNodes.add(branchNode);
            }

            WorkflowPlan plan = createPlan(List.of(t1, t2, t3), edges);

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode tc = new TestExecutionNode("trigger:chat", "trigger");
            tw.addChild(entryNode); tm.addChild(entryNode); tc.addChild(entryNode);

            ExecutionTree tree = buildTree(plan, List.of(tw, tm, tc));

            // Trigger + entry done -> all 10 branches ready
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of());
            outputs.put("mcp:entry", Map.of());
            ExecutionContext ctx = buildContext("trigger:webhook", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(ctx, tree);

            for (int i = 0; i < 10; i++) {
                assertThat(readyNodes).contains("mcp:branch_" + i);
            }
        }

        @Test
        @DisplayName("STRESS: 5 triggers -> entry -> 5 branches -> merge -> exit")
        void wideFanOutWithMerge() {
            List<Trigger> triggers = new ArrayList<>();
            List<Edge> edges = new ArrayList<>();

            for (int i = 0; i < 5; i++) {
                triggers.add(createTrigger("t" + i, "webhook"));
                edges.add(createEdge("trigger:t" + i, "mcp:entry"));
            }

            TestExecutionNode entryNode = new TestExecutionNode("mcp:entry", "mcp");
            for (int i = 0; i < 5; i++) {
                entryNode.addPredecessor("trigger:t" + i);
            }

            TestExecutionNode mergeNode = new TestExecutionNode("mcp:merge_all", "mcp");
            List<TestExecutionNode> branches = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                String branchId = "mcp:b" + i;
                edges.add(createEdge("mcp:entry", branchId));
                edges.add(createEdge(branchId, "mcp:merge_all"));

                TestExecutionNode b = new TestExecutionNode(branchId, "mcp");
                b.addPredecessor("mcp:entry");
                entryNode.addChild(b);
                b.addChild(mergeNode);
                mergeNode.addPredecessor(branchId);
                branches.add(b);
            }
            edges.add(createEdge("mcp:merge_all", "agent:exit"));
            TestExecutionNode exit = new TestExecutionNode("agent:exit", "agent");
            exit.addPredecessor("mcp:merge_all");
            mergeNode.addChild(exit);

            WorkflowPlan plan = createPlan(triggers, edges);

            List<TestExecutionNode> triggerNodes = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                TestExecutionNode tn = new TestExecutionNode("trigger:t" + i, "trigger");
                tn.addChild(entryNode);
                triggerNodes.add(tn);
            }

            ExecutionTree tree = buildTree(plan, triggerNodes);

            // Phase 1: trigger fires -> entry ready
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:t2", Map.of());
            ExecutionContext ctx1 = buildContext("trigger:t2", 1, outputs, plan);
            assertThat(calculator.calculateReadyNodes(ctx1, tree)).contains("mcp:entry");

            // Phase 2: entry done -> all 5 branches ready
            outputs.put("mcp:entry", Map.of());
            ExecutionContext ctx2 = buildContext("trigger:t2", 1, outputs, plan);
            Set<String> ready2 = calculator.calculateReadyNodes(ctx2, tree);
            for (int i = 0; i < 5; i++) {
                assertThat(ready2).contains("mcp:b" + i);
            }

            // Phase 3: 4 of 5 branches done -> merge NOT ready
            for (int i = 0; i < 4; i++) {
                outputs.put("mcp:b" + i, Map.of());
            }
            ExecutionContext ctx3 = buildContext("trigger:t2", 1, outputs, plan);
            assertThat(calculator.calculateReadyNodes(ctx3, tree)).doesNotContain("mcp:merge_all");

            // Phase 4: last branch done -> merge ready
            outputs.put("mcp:b4", Map.of());
            ExecutionContext ctx4 = buildContext("trigger:t2", 1, outputs, plan);
            assertThat(calculator.calculateReadyNodes(ctx4, tree)).contains("mcp:merge_all");
        }
    }

    // =========================================================================
    // Stress: Many Triggers Large Scale
    // =========================================================================

    @Nested
    @DisplayName("Stress: large-scale trigger configurations")
    class LargeScaleStressTests {

        @Test
        @DisplayName("STRESS: 20 triggers sharing a DAG -> shared entry -> single chain of 3")
        void stress20Triggers() {
            int numTriggers = 20;
            List<Trigger> triggers = new ArrayList<>();
            List<Edge> edges = new ArrayList<>();
            List<TestExecutionNode> triggerNodes = new ArrayList<>();

            TestExecutionNode entry = new TestExecutionNode("mcp:entry", "mcp");
            for (int i = 0; i < numTriggers; i++) {
                triggers.add(createTrigger("t" + i, "webhook"));
                edges.add(createEdge("trigger:t" + i, "mcp:entry"));
                TestExecutionNode tn = new TestExecutionNode("trigger:t" + i, "trigger");
                tn.addChild(entry);
                entry.addPredecessor("trigger:t" + i);
                triggerNodes.add(tn);
            }

            edges.add(createEdge("mcp:entry", "mcp:process"));
            edges.add(createEdge("mcp:process", "agent:end"));

            TestExecutionNode process = new TestExecutionNode("mcp:process", "mcp");
            TestExecutionNode end = new TestExecutionNode("agent:end", "agent");
            entry.addChild(process); process.addChild(end);
            process.addPredecessor("mcp:entry"); end.addPredecessor("mcp:process");

            WorkflowPlan plan = createPlan(triggers, edges);
            ExecutionTree tree = buildTree(plan, triggerNodes);

            // Any trigger fires -> entry ready (19 others filtered)
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:t15", Map.of());
            ExecutionContext ctx = buildContext("trigger:t15", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(ctx, tree);
            assertThat(readyNodes).contains("mcp:entry");
            // All other triggers should be available (not completed)
            for (int i = 0; i < numTriggers; i++) {
                if (i != 15) {
                    assertThat(readyNodes).contains("trigger:t" + i);
                }
            }
        }

        @Test
        @DisplayName("STRESS: 3 shared-DAG groups x 5 triggers - verify cross-group isolation")
        void stressThreeGroupsCrossIsolation() {
            // Each group has 5 triggers sharing a common entry node.
            // Groups are completely independent (separate descendant graphs).
            List<Trigger> triggers = new ArrayList<>();
            List<Edge> edges = new ArrayList<>();
            List<TestExecutionNode> triggerNodes = new ArrayList<>();
            Map<String, TestExecutionNode> entryMap = new HashMap<>();

            String[] groups = {"api", "cron", "user"};
            for (String group : groups) {
                TestExecutionNode entry = new TestExecutionNode("mcp:" + group + "_entry", "mcp");
                entryMap.put(group, entry);

                for (int i = 0; i < 5; i++) {
                    String label = group + "_t" + i;
                    triggers.add(createTrigger(label, "webhook"));
                    edges.add(createEdge("trigger:" + label, "mcp:" + group + "_entry"));

                    TestExecutionNode tn = new TestExecutionNode("trigger:" + label, "trigger");
                    tn.addChild(entry);
                    entry.addPredecessor("trigger:" + label);
                    triggerNodes.add(tn);
                }
            }

            WorkflowPlan plan = createPlan(triggers, edges);
            ExecutionTree tree = buildTree(plan, triggerNodes);

            // Fire api_t2 -> only api_entry should be ready, cron/user entries not
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:api_t2", Map.of());
            ExecutionContext ctx = buildContext("trigger:api_t2", 1, outputs, plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(ctx, tree);

            assertThat(readyNodes).contains("mcp:api_entry");
            // cron and user triggers should be available as initial nodes (not fired)
            for (int i = 0; i < 5; i++) {
                assertThat(readyNodes).contains("trigger:cron_t" + i);
                assertThat(readyNodes).contains("trigger:user_t" + i);
            }
        }
    }

    // =========================================================================
    // Robustness: Null/Empty/Edge Conditions
    // =========================================================================

    @Nested
    @DisplayName("Robustness: null/empty/edge conditions")
    class RobustnessTests {

        @Test
        @DisplayName("No dagValidator injected -> filtering still works via plan edge topology")
        void noDagValidatorFilteringViaPlan() {
            // Create calculator without dagValidator - filtering uses plan.areTriggersInSameDagGroup() directly
            ReadyNodeCalculator noDagCalc = new ReadyNodeCalculator(mergeNodeAnalyzer, null, null);

            // Both triggers connect to the same node -> they share a DAG (auto-detected from edges)
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode step1 = new TestExecutionNode("mcp:step1", "mcp");

            tw.addChild(step1); tm.addChild(step1);
            step1.addPredecessor("trigger:webhook"); step1.addPredecessor("trigger:manual");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm));

            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of());
            ExecutionContext ctx = buildContext("trigger:webhook", 1, outputs, plan);

            // Filtering is driven by plan edge topology, not dagValidator -> step1 IS ready
            Set<String> readyNodes = noDagCalc.calculateReadyNodes(ctx, tree);
            assertThat(readyNodes).contains("mcp:step1");
        }

        @Test
        @DisplayName("Triggers with separate graphs + no dagValidator -> standard merge (waits for all)")
        void separateGraphsNoDagValidatorStandardMerge() {
            ReadyNodeCalculator noDagCalc = new ReadyNodeCalculator(mergeNodeAnalyzer, null, null);

            // Each trigger has its own exclusive path - NO shared descendants
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:webhook_only"),
                    createEdge("trigger:manual", "mcp:manual_only")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode webhookOnly = new TestExecutionNode("mcp:webhook_only", "mcp");
            TestExecutionNode manualOnly = new TestExecutionNode("mcp:manual_only", "mcp");

            tw.addChild(webhookOnly);
            tm.addChild(manualOnly);
            webhookOnly.addPredecessor("trigger:webhook");
            manualOnly.addPredecessor("trigger:manual");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm));

            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:webhook", Map.of());
            ExecutionContext ctx = buildContext("trigger:webhook", 1, outputs, plan);

            // Separate graphs -> no filtering -> each trigger's own path works independently
            Set<String> readyNodes = noDagCalc.calculateReadyNodes(ctx, tree);
            assertThat(readyNodes).contains("mcp:webhook_only");
        }

        @Test
        @DisplayName("Empty step outputs -> only trigger nodes as initial ready")
        void emptyStepOutputsOnlyTriggersReady() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode step1 = new TestExecutionNode("mcp:step1", "mcp");

            tw.addChild(step1); tm.addChild(step1);
            step1.addPredecessor("trigger:webhook"); step1.addPredecessor("trigger:manual");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm));

            // No outputs -> nothing completed
            ExecutionContext ctx = buildContext("trigger:webhook", 0, new HashMap<>(), plan);

            Set<String> readyNodes = calculator.calculateReadyNodes(ctx, tree);

            // Both triggers should be initial ready nodes
            assertThat(readyNodes).contains("trigger:webhook", "trigger:manual");
            assertThat(readyNodes).doesNotContain("mcp:step1");
        }

        @Test
        @DisplayName("Context triggerId doesn't match any trigger in plan -> no filtering crash")
        void unknownTriggerIdNoFilteringCrash() {
            Trigger t1 = createTrigger("webhook", "webhook");
            Trigger t2 = createTrigger("manual", "manual");

            WorkflowPlan plan = createPlan(
                List.of(t1, t2),
                List.of(
                    createEdge("trigger:webhook", "mcp:step1"),
                    createEdge("trigger:manual", "mcp:step1")
                )
            );

            TestExecutionNode tw = new TestExecutionNode("trigger:webhook", "trigger");
            TestExecutionNode tm = new TestExecutionNode("trigger:manual", "trigger");
            TestExecutionNode step1 = new TestExecutionNode("mcp:step1", "mcp");

            tw.addChild(step1); tm.addChild(step1);
            step1.addPredecessor("trigger:webhook"); step1.addPredecessor("trigger:manual");

            ExecutionTree tree = buildTree(plan, List.of(tw, tm));

            // Context uses unknown trigger ID
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:unknown", Map.of());
            ExecutionContext ctx = buildContext("trigger:unknown", 1, outputs, plan);

            // Should not crash
            Set<String> readyNodes = calculator.calculateReadyNodes(ctx, tree);
            assertThat(readyNodes).isNotNull();
        }
    }
}
