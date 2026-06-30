package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.services.interfaces.ToolsGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BaseNode abstract class.
 * Tests common functionality shared by all node types.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BaseNode")
class BaseNodeTest {

    @Mock
    private ToolsGateway mockToolsGateway;

    @Mock
    private V2TemplateAdapter mockTemplateAdapter;

    @Mock
    private WorkflowPlan mockPlan;

    private TestableBaseNode node;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        node = new TestableBaseNode("test:node1", NodeType.MCP);
        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            Map.of("key", "value"),
            mockPlan
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor and basic getters
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Constructor and Getters")
    class ConstructorAndGettersTests {

        @Test
        @DisplayName("Should initialize with nodeId and type")
        void shouldInitializeWithNodeIdAndType() {
            assertEquals("test:node1", node.getNodeId());
            assertEquals(NodeType.MCP, node.getType());
        }

        @Test
        @DisplayName("Should initialize with empty successors list")
        void shouldInitializeWithEmptySuccessorsList() {
            assertTrue(node.getSuccessors().isEmpty());
        }

        @Test
        @DisplayName("Should initialize with empty predecessorIds list")
        void shouldInitializeWithEmptyPredecessorIdsList() {
            assertTrue(node.getPredecessorIds().isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Service injection
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Service Injection")
    class ServiceInjectionTests {

        @Test
        @DisplayName("Should set ToolsGateway")
        void shouldSetToolsGateway() {
            node.setToolsGateway(mockToolsGateway);
            assertNotNull(node.toolsGateway);
            assertEquals(mockToolsGateway, node.toolsGateway);
        }

        @Test
        @DisplayName("Should set TemplateAdapter")
        void shouldSetTemplateAdapter() {
            node.setTemplateAdapter(mockTemplateAdapter);
            assertNotNull(node.templateAdapter);
            assertEquals(mockTemplateAdapter, node.templateAdapter);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Predecessor management
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Predecessor Management")
    class PredecessorManagementTests {

        @Test
        @DisplayName("Should add predecessor")
        void shouldAddPredecessor() {
            node.addPredecessor("mcp:step1");

            assertEquals(1, node.getPredecessorIds().size());
            assertTrue(node.getPredecessorIds().contains("mcp:step1"));
        }

        @Test
        @DisplayName("Should not add duplicate predecessor")
        void shouldNotAddDuplicatePredecessor() {
            node.addPredecessor("mcp:step1");
            node.addPredecessor("mcp:step1");

            assertEquals(1, node.getPredecessorIds().size());
        }

        @Test
        @DisplayName("Should add multiple predecessors")
        void shouldAddMultiplePredecessors() {
            node.addPredecessor("mcp:step1");
            node.addPredecessor("mcp:step2");
            node.addPredecessor("mcp:step3");

            assertEquals(3, node.getPredecessorIds().size());
        }

        @Test
        @DisplayName("Should set all predecessors at once")
        void shouldSetAllPredecessorsAtOnce() {
            node.addPredecessor("mcp:old");
            node.setPredecessors(List.of("mcp:new1", "mcp:new2"));

            assertEquals(2, node.getPredecessorIds().size());
            assertTrue(node.getPredecessorIds().contains("mcp:new1"));
            assertTrue(node.getPredecessorIds().contains("mcp:new2"));
            assertFalse(node.getPredecessorIds().contains("mcp:old"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Implicit merge detection
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Implicit Merge Detection")
    class ImplicitMergeDetectionTests {

        @Test
        @DisplayName("isImplicitMerge() should return false with no predecessors")
        void isImplicitMergeShouldReturnFalseWithNoPredecessors() {
            assertFalse(node.isImplicitMerge());
        }

        @Test
        @DisplayName("isImplicitMerge() should return false with one predecessor")
        void isImplicitMergeShouldReturnFalseWithOnePredecessor() {
            node.addPredecessor("mcp:step1");
            assertFalse(node.isImplicitMerge());
        }

        @Test
        @DisplayName("isImplicitMerge() should return true with multiple predecessors")
        void isImplicitMergeShouldReturnTrueWithMultiplePredecessors() {
            node.addPredecessor("mcp:step1");
            node.addPredecessor("mcp:step2");
            assertTrue(node.isImplicitMerge());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Successor management
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Successor Management")
    class SuccessorManagementTests {

        @Test
        @DisplayName("Should add successor")
        void shouldAddSuccessor() {
            ExecutionNode successor = new TestableBaseNode("mcp:next", NodeType.MCP);
            node.addSuccessor(successor);

            assertEquals(1, node.getSuccessors().size());
            assertEquals("mcp:next", node.getSuccessors().get(0).getNodeId());
        }

        @Test
        @DisplayName("Should add multiple successors")
        void shouldAddMultipleSuccessors() {
            node.addSuccessor(new TestableBaseNode("mcp:step1", NodeType.MCP));
            node.addSuccessor(new TestableBaseNode("mcp:step2", NodeType.MCP));

            assertEquals(2, node.getSuccessors().size());
        }

        @Test
        @DisplayName("Should set all successors at once")
        void shouldSetAllSuccessorsAtOnce() {
            node.addSuccessor(new TestableBaseNode("mcp:old", NodeType.MCP));

            List<ExecutionNode> newSuccessors = List.of(
                new TestableBaseNode("mcp:new1", NodeType.MCP),
                new TestableBaseNode("mcp:new2", NodeType.MCP)
            );
            node.setSuccessors(newSuccessors);

            assertEquals(2, node.getSuccessors().size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // canExecute() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("canExecute()")
    class CanExecuteTests {

        @Test
        @DisplayName("Should return true when no dependencies")
        void shouldReturnTrueWhenNoDependencies() {
            assertTrue(node.canExecute(context));
        }

        @Test
        @DisplayName("Should return false when dependencies not completed")
        void shouldReturnFalseWhenDependenciesNotCompleted() {
            node.addPredecessor("mcp:step1");
            // step1 is not completed in context
            assertFalse(node.canExecute(context));
        }

        @Test
        @DisplayName("Should return true when all dependencies completed")
        void shouldReturnTrueWhenAllDependenciesCompleted() {
            node.addPredecessor("mcp:step1");

            // Mark step1 as completed
            NodeExecutionResult result = NodeExecutionResult.success("mcp:step1", Map.of("data", "value"));
            ExecutionContext updatedContext = context.withResult("mcp:step1", result);

            assertTrue(node.canExecute(updatedContext));
        }

        @Test
        @DisplayName("Should return true when all multiple dependencies completed")
        void shouldReturnTrueWhenAllMultipleDependenciesCompleted() {
            node.addPredecessor("mcp:step1");
            node.addPredecessor("mcp:step2");

            // Mark both steps as completed
            NodeExecutionResult result1 = NodeExecutionResult.success("mcp:step1", Map.of());
            NodeExecutionResult result2 = NodeExecutionResult.success("mcp:step2", Map.of());

            ExecutionContext updatedContext = context
                .withResult("mcp:step1", result1)
                .withResult("mcp:step2", result2);

            assertTrue(node.canExecute(updatedContext));
        }

        @Test
        @DisplayName("Should return false when one of multiple dependencies not completed")
        void shouldReturnFalseWhenOneOfMultipleDependenciesNotCompleted() {
            node.addPredecessor("mcp:step1");
            node.addPredecessor("mcp:step2");

            // Only mark step1 as completed
            NodeExecutionResult result1 = NodeExecutionResult.success("mcp:step1", Map.of());
            ExecutionContext updatedContext = context.withResult("mcp:step1", result1);

            assertFalse(node.canExecute(updatedContext));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getNextNodes() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return all successors on success")
        void shouldReturnAllSuccessorsOnSuccess() {
            ExecutionNode successor1 = new TestableBaseNode("mcp:next1", NodeType.MCP);
            ExecutionNode successor2 = new TestableBaseNode("mcp:next2", NodeType.MCP);
            node.addSuccessor(successor1);
            node.addSuccessor(successor2);

            NodeExecutionResult successResult = NodeExecutionResult.success(node.getNodeId(), Map.of());
            List<ExecutionNode> nextNodes = node.getNextNodes(successResult);

            assertEquals(2, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty list on failure")
        void shouldReturnEmptyListOnFailure() {
            ExecutionNode successor = new TestableBaseNode("mcp:next", NodeType.MCP);
            node.addSuccessor(successor);

            NodeExecutionResult failureResult = NodeExecutionResult.failure(node.getNodeId(), "Error");
            List<ExecutionNode> nextNodes = node.getNextNodes(failureResult);

            assertTrue(nextNodes.isEmpty());
        }

        @Test
        @DisplayName("Should return successors when result is null")
        void shouldReturnSuccessorsWhenResultIsNull() {
            ExecutionNode successor = new TestableBaseNode("mcp:next", NodeType.MCP);
            node.addSuccessor(successor);

            List<ExecutionNode> nextNodes = node.getNextNodes(null);

            assertEquals(1, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty when no successors")
        void shouldReturnEmptyWhenNoSuccessors() {
            NodeExecutionResult successResult = NodeExecutionResult.success(node.getNodeId(), Map.of());
            List<ExecutionNode> nextNodes = node.getNextNodes(successResult);

            assertTrue(nextNodes.isEmpty());
        }

        @Test
        @DisplayName("Should pass through a null successor on success without filtering or NPE")
        void shouldPassThroughNullSuccessorOnSuccess() {
            // ArrayList-backed successors accept null; getNextNodes does not filter on success.
            node.addSuccessor(new TestableBaseNode("mcp:real", NodeType.MCP));
            node.addSuccessor(null);

            NodeExecutionResult successResult = NodeExecutionResult.success(node.getNodeId(), Map.of());

            List<ExecutionNode> nextNodes = assertDoesNotThrow(() -> node.getNextNodes(successResult));

            // The null is NOT filtered out: both entries are returned, one of them null.
            assertEquals(2, nextNodes.size());
            assertNull(nextNodes.get(1));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getMetadata() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMetadata()")
    class GetMetadataTests {

        @Test
        @DisplayName("Should return metadata with nodeId, type, and successorCount")
        void shouldReturnMetadata() {
            Map<String, Object> metadata = node.getMetadata();

            assertEquals("test:node1", metadata.get("nodeId"));
            assertEquals("MCP", metadata.get("type"));
            assertEquals(0, metadata.get("successorCount"));
        }

        @Test
        @DisplayName("Should reflect correct successor count in metadata")
        void shouldReflectCorrectSuccessorCountInMetadata() {
            node.addSuccessor(new TestableBaseNode("mcp:next1", NodeType.MCP));
            node.addSuccessor(new TestableBaseNode("mcp:next2", NodeType.MCP));

            Map<String, Object> metadata = node.getMetadata();
            assertEquals(2, metadata.get("successorCount"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // onComplete() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("onComplete()")
    class OnCompleteTests {

        @Test
        @DisplayName("Should not throw on success result")
        void shouldNotThrowOnSuccessResult() {
            NodeExecutionResult result = NodeExecutionResult.success(node.getNodeId(), Map.of());
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }

        @Test
        @DisplayName("Should not throw on failure result")
        void shouldNotThrowOnFailureResult() {
            NodeExecutionResult result = NodeExecutionResult.failure(node.getNodeId(), "Error");
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Test implementation of BaseNode
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Concrete implementation of BaseNode for testing purposes.
     */
    private static class TestableBaseNode extends BaseNode {

        public TestableBaseNode(String nodeId, NodeType type) {
            super(nodeId, type);
        }

        @Override
        public NodeExecutionResult execute(ExecutionContext context) {
            return NodeExecutionResult.success(nodeId, Map.of("test", "result"));
        }
    }
}
