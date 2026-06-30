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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ExitNode.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExitNode")
class ExitNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-0", 0, Map.of(), mockPlan);
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("should set reason from parameter")
        void shouldSetReason() {
            ExitNode node = new ExitNode("core:exit", "User cancelled");
            assertEquals("User cancelled", node.getReason());
        }

        @Test
        @DisplayName("should default reason to 'Branch exited' when null")
        void shouldDefaultReason() {
            ExitNode node = new ExitNode("core:exit", null);
            assertEquals("Branch exited", node.getReason());
        }

        @Test
        @DisplayName("should have EXIT node type")
        void shouldHaveExitType() {
            ExitNode node = new ExitNode("core:exit", "reason");
            assertEquals(NodeType.EXIT, node.getType());
        }
    }

    @Nested
    @DisplayName("execute")
    class Execute {

        @Test
        @DisplayName("should return success with exit metadata")
        void shouldReturnSuccessWithExitMetadata() {
            ExitNode node = new ExitNode("core:exit", "Done processing");
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("EXIT", result.output().get("node_type"));
            assertTrue((Boolean) result.output().get("exited"));
            assertEquals("Done processing", result.output().get("reason"));
            assertEquals(0, result.output().get("item_index"));
            assertEquals("item-0", result.output().get("item_id"));
        }
    }

    @Nested
    @DisplayName("getNextNodes")
    class GetNextNodes {

        @Test
        @DisplayName("should return empty list (branch exits)")
        void shouldReturnEmptyList() {
            ExitNode node = new ExitNode("core:exit", "reason");
            node.addSuccessor(new ExitNode("next", "next reason")); // should still be empty

            NodeExecutionResult result = NodeExecutionResult.success("core:exit", Map.of());
            List<ExecutionNode> next = node.getNextNodes(result);

            assertTrue(next.isEmpty());
        }
    }

    @Nested
    @DisplayName("isExitNode")
    class IsExitNode {

        @Test
        @DisplayName("should return true")
        void shouldReturnTrue() {
            ExitNode node = new ExitNode("core:exit", "reason");
            assertTrue(node.isExitNode());
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build ExitNode with builder")
        void shouldBuild() {
            ExitNode node = ExitNode.builder()
                .nodeId("core:my_exit")
                .reason("Error detected")
                .build();

            assertEquals("core:my_exit", node.getNodeId());
            assertEquals("Error detected", node.getReason());
        }
    }
}
