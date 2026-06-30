package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

/**
 * Unit tests for StopOnErrorNode.
 * StopOnErrorNode immediately fails the workflow with an error message and optional error code.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StopOnErrorNode")
class StopOnErrorNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private V2TemplateAdapter mockTemplateAdapter;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-0",
            0,
            Map.of(),
            mockPlan
        );
    }

    @Nested
    @DisplayName("execute")
    class Execute {

        @Test
        @DisplayName("should return failure result with error_message, error_code, stopped_at, and status=failed")
        void execute_returnsFailureWithOutput() {
            Core.StopOnErrorConfig config = new Core.StopOnErrorConfig("Critical failure", "ERR_001");
            StopOnErrorNode node = new StopOnErrorNode("core:stop_on_error", config);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertEquals("STOP_ON_ERROR", result.output().get("node_type"));
            assertEquals("Critical failure", result.output().get("error_message"));
            assertEquals("ERR_001", result.output().get("error_code"));
            assertNotNull(result.output().get("stopped_at"));
            assertEquals("failed", result.output().get("status"));
            assertEquals(0, result.output().get("item_index"));
            assertEquals("item-0", result.output().get("item_id"));
            assertTrue(result.errorMessage().isPresent());
            assertEquals("Critical failure", result.errorMessage().get());
        }

        @Test
        @DisplayName("should use default error message when config is null")
        void execute_withNullConfig_usesDefaultMessage() {
            StopOnErrorNode node = new StopOnErrorNode("core:stop_on_error", null);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertEquals("Workflow stopped due to error", result.output().get("error_message"));
            assertEquals("failed", result.output().get("status"));
            assertFalse(result.output().containsKey("error_code"));
        }

        @Test
        @DisplayName("should resolve templates in error message and error code")
        void execute_resolveTemplates() {
            Core.StopOnErrorConfig config = new Core.StopOnErrorConfig(
                "{{trigger:start.output.reason}}", "{{trigger:start.output.code}}"
            );
            StopOnErrorNode node = new StopOnErrorNode("core:stop_on_error", config);
            node.setTemplateAdapter(mockTemplateAdapter);

            // Mock template resolution for error message
            when(mockTemplateAdapter.resolveTemplates(
                Map.of("__v__", "{{trigger:start.output.reason}}"), context))
                .thenReturn(Map.of("__v__", "Resolved error reason"));

            when(mockTemplateAdapter.resolveTemplates(
                Map.of("__v__", "{{trigger:start.output.code}}"), context))
                .thenReturn(Map.of("__v__", "E_RESOLVED"));

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertEquals("Resolved error reason", result.output().get("error_message"));
            assertEquals("E_RESOLVED", result.output().get("error_code"));
        }

        @Test
        @DisplayName("should omit error_code from output when it is blank")
        void execute_withBlankErrorCode_omitsErrorCode() {
            Core.StopOnErrorConfig config = new Core.StopOnErrorConfig("Some error", "   ");
            StopOnErrorNode node = new StopOnErrorNode("core:stop_on_error", config);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertEquals("Some error", result.output().get("error_message"));
            assertFalse(result.output().containsKey("error_code"));
        }
    }

    @Nested
    @DisplayName("node type detection")
    class NodeTypeDetection {

        @Test
        @DisplayName("isStopOnErrorNode should return true")
        void isStopOnErrorNode_returnsTrue() {
            StopOnErrorNode node = new StopOnErrorNode("core:stop_on_error", null);
            assertTrue(node.isStopOnErrorNode());
        }

        @Test
        @DisplayName("isExitNode should return false (StopOnError is not Exit)")
        void isExitNode_returnsFalse() {
            StopOnErrorNode node = new StopOnErrorNode("core:stop_on_error", null);
            assertFalse(node.isExitNode());
        }
    }

    @Nested
    @DisplayName("getNextNodes")
    class GetNextNodes {

        @Test
        @DisplayName("should return empty list (workflow terminates)")
        void getNextNodes_returnsEmptyList() {
            StopOnErrorNode node = new StopOnErrorNode("core:stop_on_error", null);
            // Add a successor to prove it is ignored
            node.addSuccessor(new BaseNode("mcp:next", NodeType.MCP) {
                @Override
                public NodeExecutionResult execute(ExecutionContext ctx) {
                    return NodeExecutionResult.success("mcp:next", Map.of());
                }
            });

            NodeExecutionResult result = NodeExecutionResult.failure("core:stop_on_error", "error");
            List<ExecutionNode> next = node.getNextNodes(result);

            assertTrue(next.isEmpty());
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build StopOnErrorNode with builder pattern")
        void shouldBuild() {
            Core.StopOnErrorConfig config = new Core.StopOnErrorConfig("Build error", "BUILD_ERR");
            StopOnErrorNode node = StopOnErrorNode.builder()
                .nodeId("core:my_stop_error")
                .stopOnErrorConfig(config)
                .build();

            assertEquals("core:my_stop_error", node.getNodeId());
            assertEquals(NodeType.STOP_ON_ERROR, node.getType());
            assertNotNull(node.getConfig());
            assertEquals("Build error", node.getConfig().errorMessage());
            assertEquals("BUILD_ERR", node.getConfig().errorCode());
        }
    }
}
