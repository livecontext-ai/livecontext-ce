package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.nodes.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.mockito.Mockito;

/**
 * Unit tests for NodeSearchService.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NodeSearchService")
class NodeSearchServiceTest {

    private NodeSearchService service;

    @BeforeEach
    void setUp() {
        service = new NodeSearchService();
    }

    private ExitNode createNode(String id) {
        return new ExitNode(id, "reason");
    }

    /**
     * Creates a mock ExecutionNode that properly returns successors from getNextNodes().
     * EndNode cannot be used here because it overrides getNextNodes() to always return empty,
     * which prevents NodeSearchService from traversing successors.
     * Uses lenient() stubs since not all stubs are used in every test path.
     */
    private ExecutionNode createTraversableNode(String id) {
        ExecutionNode node = mock(ExecutionNode.class);
        lenient().when(node.getNodeId()).thenReturn(id);
        lenient().when(node.getNextNodes(any())).thenReturn(List.of());
        lenient().when(node.getAllChildNodes()).thenReturn(List.of());
        return node;
    }

    /**
     * Helper to make one mock node return another as a successor via getNextNodes().
     */
    private void addMockSuccessor(ExecutionNode parent, ExecutionNode child) {
        // Get current next nodes and add the new child
        List<ExecutionNode> currentNext = parent.getNextNodes(null);
        List<ExecutionNode> newNext = new java.util.ArrayList<>(currentNext);
        newNext.add(child);
        lenient().when(parent.getNextNodes(any())).thenReturn(newNext);
    }

    @Nested
    @DisplayName("findNodeById")
    class FindNodeById {

        @Test
        @DisplayName("should return null for null root")
        void shouldReturnNullForNullRoot() {
            assertNull(service.findNodeById(null, "any-id"));
        }

        @Test
        @DisplayName("should find root node itself")
        void shouldFindRootNode() {
            ExitNode root = createNode("root");
            ExecutionNode found = service.findNodeById(root, "root");
            assertNotNull(found);
            assertEquals("root", found.getNodeId());
        }

        @Test
        @DisplayName("should find successor node")
        void shouldFindSuccessor() {
            // Use traversable mock nodes because EndNode overrides getNextNodes() to return empty
            ExecutionNode root = createTraversableNode("root");
            ExecutionNode child = createTraversableNode("child-1");
            addMockSuccessor(root, child);

            ExecutionNode found = service.findNodeById(root, "child-1");
            assertNotNull(found);
            assertEquals("child-1", found.getNodeId());
        }

        @Test
        @DisplayName("should return null when node not found")
        void shouldReturnNullWhenNotFound() {
            ExitNode root = createNode("root");
            assertNull(service.findNodeById(root, "nonexistent"));
        }

        @Test
        @DisplayName("should handle cycles without infinite loop")
        void shouldHandleCycles() {
            ExecutionNode node1 = createTraversableNode("node-1");
            ExecutionNode node2 = createTraversableNode("node-2");
            addMockSuccessor(node1, node2);
            addMockSuccessor(node2, node1); // Cycle

            ExecutionNode found = service.findNodeById(node1, "node-2");
            assertNotNull(found);
            assertEquals("node-2", found.getNodeId());
        }
    }

    @Nested
    @DisplayName("findNodeFromAllRoots")
    class FindNodeFromAllRoots {

        @Test
        @DisplayName("should find node across multiple roots")
        void shouldFindAcrossRoots() {
            ExecutionNode root1 = createTraversableNode("root-1");
            ExecutionNode root2 = createTraversableNode("root-2");
            ExecutionNode target = createTraversableNode("target");
            addMockSuccessor(root2, target);

            ExecutionTree tree = ExecutionTree.builder()
                .runId("run-1")
                .rootNodes(List.of(root1, root2))
                .build();

            ExecutionNode found = service.findNodeFromAllRoots(tree, "target");
            assertNotNull(found);
            assertEquals("target", found.getNodeId());
        }

        @Test
        @DisplayName("should return null when not found in any root")
        void shouldReturnNullWhenNotInAnyRoot() {
            ExecutionNode root1 = createTraversableNode("root-1");
            ExecutionTree tree = ExecutionTree.builder()
                .runId("run-1")
                .rootNodes(List.of(root1))
                .build();

            assertNull(service.findNodeFromAllRoots(tree, "nonexistent"));
        }
    }

    @Nested
    @DisplayName("buildNodeMap")
    class BuildNodeMap {

        @Test
        @DisplayName("should build map with all reachable nodes")
        void shouldBuildMapWithAllNodes() {
            ExecutionNode root = createTraversableNode("root");
            ExecutionNode child1 = createTraversableNode("child-1");
            ExecutionNode child2 = createTraversableNode("child-2");
            addMockSuccessor(root, child1);
            addMockSuccessor(root, child2);

            Map<String, ExecutionNode> nodeMap = service.buildNodeMap(root);

            assertEquals(3, nodeMap.size());
            assertTrue(nodeMap.containsKey("root"));
            assertTrue(nodeMap.containsKey("child-1"));
            assertTrue(nodeMap.containsKey("child-2"));
        }

        @Test
        @DisplayName("should handle cycles without infinite loop")
        void shouldHandleCycles() {
            ExecutionNode node1 = createTraversableNode("node-1");
            ExecutionNode node2 = createTraversableNode("node-2");
            addMockSuccessor(node1, node2);
            addMockSuccessor(node2, node1);

            Map<String, ExecutionNode> nodeMap = service.buildNodeMap(node1);
            assertEquals(2, nodeMap.size());
        }
    }

    @Nested
    @DisplayName("buildNodeMapFromAllRoots")
    class BuildNodeMapFromAllRoots {

        @Test
        @DisplayName("should build map from multiple roots")
        void shouldBuildFromMultipleRoots() {
            ExecutionNode root1 = createTraversableNode("root-1");
            ExecutionNode child1 = createTraversableNode("child-1");
            addMockSuccessor(root1, child1);

            ExecutionNode root2 = createTraversableNode("root-2");
            ExecutionNode child2 = createTraversableNode("child-2");
            addMockSuccessor(root2, child2);

            ExecutionTree tree = ExecutionTree.builder()
                .runId("run-1")
                .rootNodes(List.of(root1, root2))
                .build();

            Map<String, ExecutionNode> nodeMap = service.buildNodeMapFromAllRoots(tree);
            assertEquals(4, nodeMap.size());
            assertTrue(nodeMap.containsKey("root-1"));
            assertTrue(nodeMap.containsKey("child-1"));
            assertTrue(nodeMap.containsKey("root-2"));
            assertTrue(nodeMap.containsKey("child-2"));
        }
    }
}
