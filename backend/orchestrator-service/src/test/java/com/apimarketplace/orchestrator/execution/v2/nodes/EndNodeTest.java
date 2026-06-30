package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
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

/**
 * Unit tests for EndNode.
 * EndNode is the terminal node that marks workflow completion.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EndNode")
class EndNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("data", "value");

        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            triggerData,
            mockPlan
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create EndNode with nodeId")
        void shouldCreateEndNodeWithNodeId() {
            EndNode node = new EndNode("end:workflow");

            assertEquals("end:workflow", node.getNodeId());
            assertEquals(NodeType.END, node.getType());
        }

        @Test
        @DisplayName("Should create EndNode with different nodeId")
        void shouldCreateEndNodeWithDifferentNodeId() {
            EndNode node = new EndNode("core:end");

            assertEquals("core:end", node.getNodeId());
            assertEquals(NodeType.END, node.getType());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute()")
    class ExecuteTests {

        @Test
        @DisplayName("Should return success")
        void shouldReturnSuccess() {
            EndNode node = new EndNode("end:workflow");

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Should include status completed in output")
        void shouldIncludeStatusCompletedInOutput() {
            EndNode node = new EndNode("end:workflow");

            NodeExecutionResult result = node.execute(context);

            assertEquals("completed", result.output().get("status"));
        }

        @Test
        @DisplayName("Should include completion message in output")
        void shouldIncludeCompletionMessageInOutput() {
            EndNode node = new EndNode("end:workflow");

            NodeExecutionResult result = node.execute(context);

            assertEquals("Workflow execution completed", result.output().get("message"));
        }

        @Test
        @DisplayName("Should return correct nodeId in result")
        void shouldReturnCorrectNodeIdInResult() {
            EndNode node = new EndNode("end:my_workflow");

            NodeExecutionResult result = node.execute(context);

            assertEquals("end:my_workflow", result.nodeId());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getNextNodes() tests - Terminal node
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getNextNodes() - Terminal Node")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should always return empty list (terminal node)")
        void shouldAlwaysReturnEmptyListTerminalNode() {
            EndNode node = new EndNode("end:workflow");

            NodeExecutionResult result = NodeExecutionResult.success("end:workflow", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertTrue(nextNodes.isEmpty());
        }

        @Test
        @DisplayName("Should return empty list even if successors were added")
        void shouldReturnEmptyListEvenIfSuccessorsWereAdded() {
            EndNode node = new EndNode("end:workflow");

            // Try to add a successor (shouldn't affect result)
            node.addSuccessor(new EndNode("should:not:appear"));

            NodeExecutionResult result = NodeExecutionResult.success("end:workflow", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            // EndNode overrides getNextNodes to always return empty list
            assertTrue(nextNodes.isEmpty());
        }

        @Test
        @DisplayName("Should return empty list on failure result")
        void shouldReturnEmptyListOnFailureResult() {
            EndNode node = new EndNode("end:workflow");

            NodeExecutionResult result = NodeExecutionResult.failure("end:workflow", "Error");

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertTrue(nextNodes.isEmpty());
        }

        @Test
        @DisplayName("Should return empty list on null result")
        void shouldReturnEmptyListOnNullResult() {
            EndNode node = new EndNode("end:workflow");

            List<ExecutionNode> nextNodes = node.getNextNodes(null);

            assertTrue(nextNodes.isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // onComplete() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("onComplete()")
    class OnCompleteTests {

        @Test
        @DisplayName("Should not throw exception on success result")
        void shouldNotThrowExceptionOnSuccessResult() {
            EndNode node = new EndNode("end:workflow");

            NodeExecutionResult result = NodeExecutionResult.success("end:workflow", Map.of());

            assertDoesNotThrow(() -> node.onComplete(context, result));
        }

        @Test
        @DisplayName("Should not throw exception on failure result")
        void shouldNotThrowExceptionOnFailureResult() {
            EndNode node = new EndNode("end:workflow");

            NodeExecutionResult result = NodeExecutionResult.failure("end:workflow", "Error");

            assertDoesNotThrow(() -> node.onComplete(context, result));
        }

        @Test
        @DisplayName("Should not throw exception on null context")
        void shouldNotThrowExceptionOnNullContext() {
            EndNode node = new EndNode("end:workflow");

            NodeExecutionResult result = NodeExecutionResult.success("end:workflow", Map.of());

            assertDoesNotThrow(() -> node.onComplete(null, result));
        }

        @Test
        @DisplayName("Should not throw exception on null result")
        void shouldNotThrowExceptionOnNullResult() {
            EndNode node = new EndNode("end:workflow");

            assertDoesNotThrow(() -> node.onComplete(context, null));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // canExecute() tests (inherited from BaseNode)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("canExecute()")
    class CanExecuteTests {

        @Test
        @DisplayName("Should return true when no dependencies")
        void shouldReturnTrueWhenNoDependencies() {
            EndNode node = new EndNode("end:workflow");

            assertTrue(node.canExecute(context));
        }

        @Test
        @DisplayName("Should return false when dependencies not completed")
        void shouldReturnFalseWhenDependenciesNotCompleted() {
            EndNode node = new EndNode("end:workflow");
            node.addPredecessor("mcp:final_step");

            assertFalse(node.canExecute(context));
        }

        @Test
        @DisplayName("Should return true when all dependencies completed")
        void shouldReturnTrueWhenAllDependenciesCompleted() {
            EndNode node = new EndNode("end:workflow");
            node.addPredecessor("mcp:final_step");

            ExecutionContext updatedContext = context.withResult("mcp:final_step",
                NodeExecutionResult.success("mcp:final_step", Map.of()));

            assertTrue(node.canExecute(updatedContext));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getMetadata() tests (inherited from BaseNode)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMetadata()")
    class GetMetadataTests {

        @Test
        @DisplayName("Should return metadata with nodeId and type")
        void shouldReturnMetadataWithNodeIdAndType() {
            EndNode node = new EndNode("end:workflow");

            Map<String, Object> metadata = node.getMetadata();

            assertEquals("end:workflow", metadata.get("nodeId"));
            assertEquals("END", metadata.get("type"));
        }

        @Test
        @DisplayName("Should show successorCount as 0")
        void shouldShowSuccessorCountAsZero() {
            EndNode node = new EndNode("end:workflow");

            Map<String, Object> metadata = node.getMetadata();

            assertEquals(0, metadata.get("successorCount"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Workflow termination behavior tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Workflow Termination Behavior")
    class WorkflowTerminationBehaviorTests {

        @Test
        @DisplayName("EndNode should mark workflow as complete")
        void endNodeShouldMarkWorkflowAsComplete() {
            EndNode node = new EndNode("end:workflow");

            NodeExecutionResult result = node.execute(context);

            // The 'status: completed' and 'message' indicate workflow completion
            assertTrue(result.isSuccess());
            assertEquals("completed", result.output().get("status"));
            assertNotNull(result.output().get("message"));
        }

        @Test
        @DisplayName("EndNode should be final in execution path")
        void endNodeShouldBeFinalInExecutionPath() {
            EndNode node = new EndNode("end:workflow");

            // Execute the node
            NodeExecutionResult result = node.execute(context);

            // Get next nodes - should be empty
            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertTrue(result.isSuccess());
            assertTrue(nextNodes.isEmpty());
        }
    }
}
