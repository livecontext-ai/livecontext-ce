package com.apimarketplace.orchestrator.services.state;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WorkflowGraph.
 * Tests immutable graph representation of workflows.
 */
class WorkflowGraphTest {

    private WorkflowGraph graph;
    private NodeId triggerId;
    private NodeId step1Id;
    private NodeId step2Id;

    @BeforeEach
    void setUp() {
        triggerId = NodeId.trigger("start");
        step1Id = NodeId.step("step1");
        step2Id = NodeId.step("step2");

        Map<NodeId, WorkflowNode> nodes = new HashMap<>();
        nodes.put(triggerId, WorkflowNode.builder(triggerId, WorkflowNode.NodeType.TRIGGER)
                .addSuccessor(step1Id)
                .build());
        nodes.put(step1Id, WorkflowNode.builder(step1Id, WorkflowNode.NodeType.MCP)
                .addPredecessor(triggerId)
                .addSuccessor(step2Id)
                .build());
        nodes.put(step2Id, WorkflowNode.builder(step2Id, WorkflowNode.NodeType.MCP)
                .addPredecessor(step1Id)
                .build());

        List<WorkflowGraph.Edge> edges = List.of(
                new WorkflowGraph.Edge(triggerId, step1Id),
                new WorkflowGraph.Edge(step1Id, step2Id)
        );

        graph = new WorkflowGraph(nodes, triggerId, edges);
    }

    // ========================================================================
    // Constructor Tests
    // ========================================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create graph with valid parameters")
        void shouldCreateGraphWithValidParameters() {
            assertNotNull(graph);
            assertEquals(3, graph.getNodeCount());
            assertEquals(2, graph.getEdgeCount());
        }

        @Test
        @DisplayName("Should throw when trigger ID is null")
        void shouldThrowWhenTriggerIdIsNull() {
            Map<NodeId, WorkflowNode> nodes = new HashMap<>();
            nodes.put(step1Id, WorkflowNode.builder(step1Id, WorkflowNode.NodeType.MCP).build());

            assertThrows(NullPointerException.class, () ->
                    new WorkflowGraph(nodes, (NodeId) null, List.of())
            );
        }

        @Test
        @DisplayName("Should throw when trigger not in nodes")
        void shouldThrowWhenTriggerNotInNodes() {
            Map<NodeId, WorkflowNode> nodes = new HashMap<>();
            nodes.put(step1Id, WorkflowNode.builder(step1Id, WorkflowNode.NodeType.MCP).build());

            assertThrows(IllegalArgumentException.class, () ->
                    new WorkflowGraph(nodes, triggerId, List.of())
            );
        }

        @Test
        @DisplayName("Should create immutable nodes map")
        void shouldCreateImmutableNodesMap() {
            assertThrows(UnsupportedOperationException.class, () ->
                    graph.getAllNodeIds().add(NodeId.step("new_node"))
            );
        }
    }

    // ========================================================================
    // getNode() Tests
    // ========================================================================

    @Nested
    @DisplayName("getNode()")
    class GetNodeTests {

        @Test
        @DisplayName("Should return node for valid ID")
        void shouldReturnNodeForValidId() {
            WorkflowNode node = graph.getNode(step1Id);

            assertNotNull(node);
            assertEquals(step1Id, node.id());
        }

        @Test
        @DisplayName("Should throw for unknown node ID")
        void shouldThrowForUnknownNodeId() {
            NodeId unknownId = NodeId.step("unknown");

            assertThrows(IllegalArgumentException.class, () ->
                    graph.getNode(unknownId)
            );
        }
    }

    // ========================================================================
    // getNodeOrNull() Tests
    // ========================================================================

    @Nested
    @DisplayName("getNodeOrNull()")
    class GetNodeOrNullTests {

        @Test
        @DisplayName("Should return node for valid ID")
        void shouldReturnNodeForValidId() {
            WorkflowNode node = graph.getNodeOrNull(step1Id);

            assertNotNull(node);
        }

        @Test
        @DisplayName("Should return null for unknown ID")
        void shouldReturnNullForUnknownId() {
            NodeId unknownId = NodeId.step("unknown");

            WorkflowNode node = graph.getNodeOrNull(unknownId);

            assertNull(node);
        }
    }

    // ========================================================================
    // containsNode() Tests
    // ========================================================================

    @Nested
    @DisplayName("containsNode()")
    class ContainsNodeTests {

        @Test
        @DisplayName("Should return true for existing node")
        void shouldReturnTrueForExistingNode() {
            assertTrue(graph.containsNode(step1Id));
        }

        @Test
        @DisplayName("Should return false for non-existing node")
        void shouldReturnFalseForNonExistingNode() {
            assertFalse(graph.containsNode(NodeId.step("unknown")));
        }
    }

    // ========================================================================
    // getTrigger() / getTriggerId() Tests
    // ========================================================================

    @Nested
    @DisplayName("Trigger Access")
    class TriggerAccessTests {

        @Test
        @DisplayName("Should return trigger node")
        void shouldReturnTriggerNode() {
            WorkflowNode trigger = graph.getTrigger();

            assertNotNull(trigger);
            assertEquals(triggerId, trigger.id());
            assertTrue(trigger.isTrigger());
        }

        @Test
        @DisplayName("Should return trigger ID")
        void shouldReturnTriggerId() {
            assertEquals(triggerId, graph.getTriggerId());
        }
    }

    // ========================================================================
    // getAllNodes() / getAllNodeIds() Tests
    // ========================================================================

    @Nested
    @DisplayName("All Nodes Access")
    class AllNodesAccessTests {

        @Test
        @DisplayName("Should return all nodes")
        void shouldReturnAllNodes() {
            var nodes = graph.getAllNodes();

            assertEquals(3, nodes.size());
        }

        @Test
        @DisplayName("Should return all node IDs")
        void shouldReturnAllNodeIds() {
            var nodeIds = graph.getAllNodeIds();

            assertEquals(3, nodeIds.size());
            assertTrue(nodeIds.contains(triggerId));
            assertTrue(nodeIds.contains(step1Id));
            assertTrue(nodeIds.contains(step2Id));
        }
    }

    // ========================================================================
    // Edge Access Tests
    // ========================================================================

    @Nested
    @DisplayName("Edge Access")
    class EdgeAccessTests {

        @Test
        @DisplayName("Should return all edges")
        void shouldReturnAllEdges() {
            var edges = graph.getEdges();

            assertEquals(2, edges.size());
        }

        @Test
        @DisplayName("Should check if edge exists")
        void shouldCheckIfEdgeExists() {
            assertTrue(graph.hasEdge(triggerId, step1Id));
            assertTrue(graph.hasEdge(step1Id, step2Id));
            assertFalse(graph.hasEdge(triggerId, step2Id));
        }
    }

    // ========================================================================
    // getNodesByType() Tests
    // ========================================================================

    @Nested
    @DisplayName("getNodesByType()")
    class GetNodesByTypeTests {

        @Test
        @DisplayName("Should return nodes of specified type")
        void shouldReturnNodesOfSpecifiedType() {
            var triggerNodes = graph.getNodesByType(WorkflowNode.NodeType.TRIGGER);
            var mcpNodes = graph.getNodesByType(WorkflowNode.NodeType.MCP);

            assertEquals(1, triggerNodes.size());
            assertEquals(2, mcpNodes.size());
        }

        @Test
        @DisplayName("Should return empty list for non-existing type")
        void shouldReturnEmptyListForNonExistingType() {
            var agentNodes = graph.getNodesByType(WorkflowNode.NodeType.AGENT);

            assertTrue(agentNodes.isEmpty());
        }
    }

    // ========================================================================
    // getExecutableNodes() Tests
    // ========================================================================

    @Nested
    @DisplayName("getExecutableNodes()")
    class GetExecutableNodesTests {

        @Test
        @DisplayName("Should return executable nodes (trigger, mcp, agent)")
        void shouldReturnExecutableNodes() {
            var executableNodes = graph.getExecutableNodes();

            assertEquals(3, executableNodes.size());
        }
    }

    // ========================================================================
    // getMergeNodes() Tests
    // ========================================================================

    @Nested
    @DisplayName("getMergeNodes()")
    class GetMergeNodesTests {

        @Test
        @DisplayName("Should return nodes with multiple predecessors")
        void shouldReturnNodesWithMultiplePredecessors() {
            // Current setup has no merge nodes
            var mergeNodes = graph.getMergeNodes();

            assertTrue(mergeNodes.isEmpty());
        }

        @Test
        @DisplayName("Should detect merge node")
        void shouldDetectMergeNode() {
            // Create graph with merge node
            NodeId branch1 = NodeId.step("branch1");
            NodeId branch2 = NodeId.step("branch2");
            NodeId mergeId = NodeId.step("merge");

            Map<NodeId, WorkflowNode> nodes = new HashMap<>();
            nodes.put(triggerId, WorkflowNode.builder(triggerId, WorkflowNode.NodeType.TRIGGER)
                    .addSuccessor(branch1).addSuccessor(branch2).build());
            nodes.put(branch1, WorkflowNode.builder(branch1, WorkflowNode.NodeType.MCP)
                    .addPredecessor(triggerId).addSuccessor(mergeId).build());
            nodes.put(branch2, WorkflowNode.builder(branch2, WorkflowNode.NodeType.MCP)
                    .addPredecessor(triggerId).addSuccessor(mergeId).build());
            nodes.put(mergeId, WorkflowNode.builder(mergeId, WorkflowNode.NodeType.MCP)
                    .addPredecessor(branch1).addPredecessor(branch2).build());

            WorkflowGraph graphWithMerge = new WorkflowGraph(nodes, triggerId, List.of());

            var mergeNodes = graphWithMerge.getMergeNodes();

            assertEquals(1, mergeNodes.size());
            assertEquals(mergeId, mergeNodes.get(0).id());
        }
    }

    // ========================================================================
    // getExitPoints() Tests
    // ========================================================================

    @Nested
    @DisplayName("getExitPoints()")
    class GetExitPointsTests {

        @Test
        @DisplayName("Should return nodes with no successors")
        void shouldReturnNodesWithNoSuccessors() {
            var exitPoints = graph.getExitPoints();

            assertEquals(1, exitPoints.size());
            assertEquals(step2Id, exitPoints.get(0).id());
        }
    }

    // ========================================================================
    // getPredecessors() / getSuccessors() Tests
    // ========================================================================

    @Nested
    @DisplayName("Predecessors and Successors")
    class PredecessorsAndSuccessorsTests {

        @Test
        @DisplayName("Should return predecessors")
        void shouldReturnPredecessors() {
            var predecessors = graph.getPredecessors(step1Id);

            assertEquals(1, predecessors.size());
            assertEquals(triggerId, predecessors.get(0).id());
        }

        @Test
        @DisplayName("Should return successors")
        void shouldReturnSuccessors() {
            var successors = graph.getSuccessors(triggerId);

            assertEquals(1, successors.size());
            assertEquals(step1Id, successors.get(0).id());
        }

        @Test
        @DisplayName("Should return empty list for trigger predecessors")
        void shouldReturnEmptyListForTriggerPredecessors() {
            var predecessors = graph.getPredecessors(triggerId);

            assertTrue(predecessors.isEmpty());
        }
    }

    // ========================================================================
    // topologicalSort() Tests
    // ========================================================================

    @Nested
    @DisplayName("topologicalSort()")
    class TopologicalSortTests {

        @Test
        @DisplayName("Should return nodes in topological order")
        void shouldReturnNodesInTopologicalOrder() {
            List<NodeId> sorted = graph.topologicalSort();

            assertEquals(3, sorted.size());

            // Trigger should come before its successors
            int triggerIndex = sorted.indexOf(triggerId);
            int step1Index = sorted.indexOf(step1Id);
            int step2Index = sorted.indexOf(step2Id);

            assertTrue(triggerIndex < step1Index);
            assertTrue(step1Index < step2Index);
        }

        @Test
        @DisplayName("Should throw on cyclic graph")
        void shouldThrowOnCyclicGraph() {
            // Create graph with cycle
            NodeId nodeA = NodeId.step("a");
            NodeId nodeB = NodeId.step("b");

            Map<NodeId, WorkflowNode> nodes = new HashMap<>();
            nodes.put(triggerId, WorkflowNode.builder(triggerId, WorkflowNode.NodeType.TRIGGER)
                    .addSuccessor(nodeA).build());
            nodes.put(nodeA, WorkflowNode.builder(nodeA, WorkflowNode.NodeType.MCP)
                    .addPredecessor(triggerId).addPredecessor(nodeB).addSuccessor(nodeB).build());
            nodes.put(nodeB, WorkflowNode.builder(nodeB, WorkflowNode.NodeType.MCP)
                    .addPredecessor(nodeA).addSuccessor(nodeA).build()); // Cycle: A -> B -> A

            WorkflowGraph cyclicGraph = new WorkflowGraph(nodes, triggerId, List.of());

            assertThrows(IllegalStateException.class, cyclicGraph::topologicalSort);
        }
    }

    // ========================================================================
    // findPathsToNode() Tests
    // ========================================================================

    @Nested
    @DisplayName("findPathsToNode()")
    class FindPathsToNodeTests {

        @Test
        @DisplayName("Should find single path")
        void shouldFindSinglePath() {
            List<List<NodeId>> paths = graph.findPathsToNode(step2Id);

            assertEquals(1, paths.size());
            assertEquals(3, paths.get(0).size());
            assertEquals(triggerId, paths.get(0).get(0));
            assertEquals(step1Id, paths.get(0).get(1));
            assertEquals(step2Id, paths.get(0).get(2));
        }

        @Test
        @DisplayName("Should find multiple paths")
        void shouldFindMultiplePaths() {
            // Create graph with multiple paths
            NodeId branch1 = NodeId.step("branch1");
            NodeId branch2 = NodeId.step("branch2");
            NodeId target = NodeId.step("target");

            Map<NodeId, WorkflowNode> nodes = new HashMap<>();
            nodes.put(triggerId, WorkflowNode.builder(triggerId, WorkflowNode.NodeType.TRIGGER)
                    .addSuccessor(branch1).addSuccessor(branch2).build());
            nodes.put(branch1, WorkflowNode.builder(branch1, WorkflowNode.NodeType.MCP)
                    .addPredecessor(triggerId).addSuccessor(target).build());
            nodes.put(branch2, WorkflowNode.builder(branch2, WorkflowNode.NodeType.MCP)
                    .addPredecessor(triggerId).addSuccessor(target).build());
            nodes.put(target, WorkflowNode.builder(target, WorkflowNode.NodeType.MCP)
                    .addPredecessor(branch1).addPredecessor(branch2).build());

            WorkflowGraph multiPathGraph = new WorkflowGraph(nodes, triggerId, List.of());

            List<List<NodeId>> paths = multiPathGraph.findPathsToNode(target);

            assertEquals(2, paths.size());
        }

        @Test
        @DisplayName("Should return empty for unreachable node")
        void shouldReturnEmptyForUnreachableNode() {
            // Node not connected to trigger
            NodeId isolated = NodeId.step("isolated");
            Map<NodeId, WorkflowNode> nodes = new HashMap<>();
            nodes.put(triggerId, WorkflowNode.builder(triggerId, WorkflowNode.NodeType.TRIGGER).build());
            nodes.put(isolated, WorkflowNode.builder(isolated, WorkflowNode.NodeType.MCP).build());

            WorkflowGraph graphWithIsolated = new WorkflowGraph(nodes, triggerId, List.of());

            List<List<NodeId>> paths = graphWithIsolated.findPathsToNode(isolated);

            assertTrue(paths.isEmpty());
        }
    }

    // ========================================================================
    // Edge Record Tests
    // ========================================================================

    @Nested
    @DisplayName("Edge Record")
    class EdgeRecordTests {

        @Test
        @DisplayName("Should create edge with valid parameters")
        void shouldCreateEdgeWithValidParameters() {
            WorkflowGraph.Edge edge = new WorkflowGraph.Edge(triggerId, step1Id);

            assertEquals(triggerId, edge.from());
            assertEquals(step1Id, edge.to());
        }

        @Test
        @DisplayName("Should throw on null from")
        void shouldThrowOnNullFrom() {
            assertThrows(NullPointerException.class, () ->
                    new WorkflowGraph.Edge(null, step1Id)
            );
        }

        @Test
        @DisplayName("Should throw on null to")
        void shouldThrowOnNullTo() {
            assertThrows(NullPointerException.class, () ->
                    new WorkflowGraph.Edge(triggerId, null)
            );
        }

        @Test
        @DisplayName("Should have meaningful toString")
        void shouldHaveMeaningfulToString() {
            WorkflowGraph.Edge edge = new WorkflowGraph.Edge(triggerId, step1Id);

            String str = edge.toString();

            assertTrue(str.contains("->"));
        }
    }

    // ========================================================================
    // toString() Tests
    // ========================================================================

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("Should return meaningful string representation")
        void shouldReturnMeaningfulString() {
            String str = graph.toString();

            assertTrue(str.contains("WorkflowGraph"));
            assertTrue(str.contains("nodes=3"));
            assertTrue(str.contains("edges=2"));
        }
    }
}
