package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.execution.v2.nodes.*;
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
import static org.mockito.Mockito.*;

/**
 * Unit tests for EdgeWiringOrchestrator.
 *
 * EdgeWiringOrchestrator implements the two-pass edge wiring algorithm:
 * 1. Collect port edges and create nodes (PASS 1)
 * 2. Wire targets after all nodes exist (PASS 2)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EdgeWiringOrchestrator")
class EdgeWiringOrchestratorTest {

    @Mock
    private CoreNodeBuilder coreNodeBuilder;

    @Mock
    private DecisionNodeWirer decisionNodeWirer;

    @Mock
    private SwitchNodeWirer switchNodeWirer;

    @Mock
    private ForkNodeWirer forkNodeWirer;

    @Mock
    private OptionNodeWirer optionNodeWirer;

    @Mock
    private ApprovalNodeWirer approvalNodeWirer;

    @Mock
    private LoopNodeWirer loopNodeWirer;

    private EdgeWiringOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new EdgeWiringOrchestrator(coreNodeBuilder, decisionNodeWirer, switchNodeWirer, forkNodeWirer, optionNodeWirer, approvalNodeWirer, loopNodeWirer);
    }

    @Nested
    @DisplayName("collectPortEdges()")
    class CollectPortEdgesTests {

        @Test
        @DisplayName("Should collect decision port edges")
        void shouldCollectDecisionPortEdges() {
            WorkflowPlan plan = createPlanWithEdges(List.of(
                createEdge("core:check:if", "mcp:success"),
                createEdge("core:check:else", "mcp:failure")
            ));

            EdgeWiringOrchestrator.EdgeCollector collector = orchestrator.collectPortEdges(plan);

            assertTrue(collector.decisionPortTargets.containsKey("core:check"));
            assertEquals("mcp:success", collector.decisionPortTargets.get("core:check").get("if"));
            assertEquals("mcp:failure", collector.decisionPortTargets.get("core:check").get("else"));
        }

        @Test
        @DisplayName("Should collect fork branch edges")
        void shouldCollectForkBranchEdges() {
            WorkflowPlan plan = createPlanWithEdges(List.of(
                createEdge("core:parallel:branch_0", "mcp:task_a"),
                createEdge("core:parallel:branch_1", "mcp:task_b")
            ));

            EdgeWiringOrchestrator.EdgeCollector collector = orchestrator.collectPortEdges(plan);

            assertTrue(collector.forkBranchTargets.containsKey("core:parallel"));
            assertEquals("mcp:task_a", collector.forkBranchTargets.get("core:parallel").get("branch_0"));
            assertEquals("mcp:task_b", collector.forkBranchTargets.get("core:parallel").get("branch_1"));
        }

        @Test
        @DisplayName("Should collect merge source nodes")
        void shouldCollectMergeSourceNodes() {
            WorkflowPlan plan = createPlanWithEdges(List.of(
                createEdge("mcp:task_a", "core:wait_all"),
                createEdge("mcp:task_b", "core:wait_all")
            ));

            EdgeWiringOrchestrator.EdgeCollector collector = orchestrator.collectPortEdges(plan);

            assertTrue(collector.mergeSourceNodes.containsKey("core:wait_all"));
            assertTrue(collector.mergeSourceNodes.get("core:wait_all").contains("mcp:task_a"));
            assertTrue(collector.mergeSourceNodes.get("core:wait_all").contains("mcp:task_b"));
        }

        @Test
        @DisplayName("Should handle elseif port edges")
        void shouldHandleElseifPortEdges() {
            WorkflowPlan plan = createPlanWithEdges(List.of(
                createEdge("core:check:if", "mcp:case_a"),
                createEdge("core:check:elseif_0", "mcp:case_b"),
                createEdge("core:check:elseif_1", "mcp:case_c"),
                createEdge("core:check:else", "mcp:default")
            ));

            EdgeWiringOrchestrator.EdgeCollector collector = orchestrator.collectPortEdges(plan);

            Map<String, String> ports = collector.decisionPortTargets.get("core:check");
            assertEquals(4, ports.size());
            assertEquals("mcp:case_a", ports.get("if"));
            assertEquals("mcp:case_b", ports.get("elseif_0"));
            assertEquals("mcp:case_c", ports.get("elseif_1"));
            assertEquals("mcp:default", ports.get("else"));
        }

        @Test
        @DisplayName("Should handle switch case port edges")
        void shouldHandleSwitchCasePortEdges() {
            WorkflowPlan plan = createPlanWithEdges(List.of(
                createEdge("core:router:case_0", "mcp:handler_a"),
                createEdge("core:router:case_1", "mcp:handler_b"),
                createEdge("core:router:default", "mcp:fallback")
            ));

            EdgeWiringOrchestrator.EdgeCollector collector = orchestrator.collectPortEdges(plan);

            Map<String, String> ports = collector.switchPortTargets.get("core:router");
            assertEquals(3, ports.size());
            assertEquals("mcp:handler_a", ports.get("case_0"));
            assertEquals("mcp:handler_b", ports.get("case_1"));
            assertEquals("mcp:fallback", ports.get("default"));
        }

        @Test
        @DisplayName("Should skip edges with null from")
        void shouldSkipEdgesWithNullFrom() {
            WorkflowPlan plan = createPlanWithEdges(List.of(
                new Edge(null, "mcp:step", null)
            ));

            EdgeWiringOrchestrator.EdgeCollector collector = orchestrator.collectPortEdges(plan);

            assertTrue(collector.decisionPortTargets.isEmpty());
            assertTrue(collector.forkBranchTargets.isEmpty());
        }

        @Test
        @DisplayName("Should handle empty edges list")
        void shouldHandleEmptyEdgesList() {
            WorkflowPlan plan = createPlanWithEdges(List.of());

            EdgeWiringOrchestrator.EdgeCollector collector = orchestrator.collectPortEdges(plan);

            assertTrue(collector.decisionPortTargets.isEmpty());
            assertTrue(collector.forkBranchTargets.isEmpty());
            assertTrue(collector.mergeSourceNodes.isEmpty());
        }

        @Test
        @DisplayName("Should skip iterate-port edges in collection")
        void shouldSkipIteratePortEdgesInCollection() {
            WorkflowPlan plan = createPlanWithEdges(List.of(
                createEdge("mcp:a", "mcp:b"),
                createEdge("mcp:b", "core:my_loop:iterate")
            ));

            EdgeWiringOrchestrator.EdgeCollector collector = orchestrator.collectPortEdges(plan);

            // Iterate edges should be skipped - not collected into any port targets
            assertTrue(collector.decisionPortTargets.isEmpty());
            assertTrue(collector.forkBranchTargets.isEmpty());
            // mcp:a should appear in mergeSourceNodes for mcp:b
            assertTrue(collector.mergeSourceNodes.containsKey("mcp:b"));
        }
    }

    @Nested
    @DisplayName("wireSuccessorsFromEdges()")
    class WireSuccessorsFromEdgesTests {

        @Test
        @DisplayName("Should call coreNodeBuilder.createCoreNodes()")
        void shouldCallCoreNodeBuilderCreateCoreNodes() {
            WorkflowPlan plan = createPlanWithEdges(List.of());
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            orchestrator.wireSuccessorsFromEdges(nodeMap, plan);

            verify(coreNodeBuilder).createCoreNodes(eq(nodeMap), eq(plan), any());
        }

        @Test
        @DisplayName("Should call decisionNodeWirer.wireDecisionBranchTargets()")
        void shouldCallDecisionNodeWirerWireDecisionBranchTargets() {
            WorkflowPlan plan = createPlanWithEdges(List.of());
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            orchestrator.wireSuccessorsFromEdges(nodeMap, plan);

            verify(decisionNodeWirer).wireDecisionBranchTargets(eq(nodeMap), eq(plan), any());
        }

        @Test
        @DisplayName("Should call forkNodeWirer.wireForkBranchTargets()")
        void shouldCallForkNodeWirerWireForkBranchTargets() {
            WorkflowPlan plan = createPlanWithEdges(List.of());
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            orchestrator.wireSuccessorsFromEdges(nodeMap, plan);

            verify(forkNodeWirer).wireForkBranchTargets(eq(nodeMap), eq(plan), any());
        }

        @Test
        @DisplayName("Should wire simple edge between nodes")
        void shouldWireSimpleEdgeBetweenNodes() {
            WorkflowPlan plan = createPlanWithEdges(List.of(
                createEdge("trigger:start", "mcp:step_1")
            ));

            BaseNode triggerNode = mock(BaseNode.class);
            // wireV2Edge() does NOT call getNodeId() on nodes
            BaseNode stepNode = mock(BaseNode.class);
            // wireV2Edge() does NOT call getNodeId() on nodes

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("trigger:start", triggerNode);
            nodeMap.put("mcp:step_1", stepNode);

            orchestrator.wireSuccessorsFromEdges(nodeMap, plan);

            verify(triggerNode).addSuccessor(stepNode);
            verify(stepNode).addPredecessor("trigger:start");
        }

        @Test
        @DisplayName("Should skip decision port edges (handled elsewhere)")
        void shouldSkipDecisionPortEdges() {
            WorkflowPlan plan = createPlanWithEdges(List.of(
                createEdge("core:check:if", "mcp:success")
            ));

            BaseNode decisionNode = mock(BaseNode.class);
            BaseNode successNode = mock(BaseNode.class);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:check", decisionNode);
            nodeMap.put("mcp:success", successNode);

            orchestrator.wireSuccessorsFromEdges(nodeMap, plan);

            // Should NOT directly wire - handled by DecisionNodeWirer
            verify(decisionNode, never()).addSuccessor(any());
        }

        @Test
        @DisplayName("Should skip fork port edges (handled elsewhere)")
        void shouldSkipForkPortEdges() {
            WorkflowPlan plan = createPlanWithEdges(List.of(
                createEdge("core:parallel:branch_0", "mcp:task_a")
            ));

            BaseNode forkNode = mock(BaseNode.class);
            BaseNode taskNode = mock(BaseNode.class);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:parallel", forkNode);
            nodeMap.put("mcp:task_a", taskNode);

            orchestrator.wireSuccessorsFromEdges(nodeMap, plan);

            // Should NOT directly wire - handled by ForkNodeWirer
            verify(forkNode, never()).addSuccessor(any());
        }

        @Test
        @DisplayName("Should collect loop exit edges in loopPortTargets (not as direct successors)")
        void shouldCollectLoopExitEdgesInLoopPortTargets() {
            WorkflowPlan plan = createPlanWithEdges(List.of(
                createEdge("core:loop:exit", "mcp:after_loop")
            ));

            EdgeWiringOrchestrator.EdgeCollector collector = orchestrator.collectPortEdges(plan);

            // Loop exit edges should be collected as loop port targets
            assertTrue(collector.loopPortTargets.containsKey("core:loop"));
            assertEquals("mcp:after_loop", collector.loopPortTargets.get("core:loop").get("exit"));
        }
    }

    @Nested
    @DisplayName("EdgeCollector")
    class EdgeCollectorTests {

        @Test
        @DisplayName("Should initialize with empty collections")
        void shouldInitializeWithEmptyCollections() {
            EdgeWiringOrchestrator.EdgeCollector collector = new EdgeWiringOrchestrator.EdgeCollector();

            assertTrue(collector.decisionPortTargets.isEmpty());
            assertTrue(collector.forkBranchTargets.isEmpty());
            assertTrue(collector.mergeSourceNodes.isEmpty());
        }
    }

    // ===== Helper methods =====

    private Edge createEdge(String from, String to) {
        return new Edge(from, to, null);
    }

    private WorkflowPlan createPlanWithEdges(List<Edge> edges) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "test-plan");
        data.put("tenant_id", "test-tenant");
        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "Start", "type", "webhook", "strategy", "single")
        ));
        data.put("mcps", List.of());

        // Convert Edge objects to maps
        List<Map<String, Object>> edgeMaps = edges.stream()
            .map(e -> {
                Map<String, Object> map = new HashMap<>();
                map.put("from", e.from());
                map.put("to", e.to());
                if (e.params() != null) map.put("params", e.params());
                return map;
            })
            .toList();
        data.put("edges", edgeMaps);

        return WorkflowPlan.fromMap(data);
    }
}
