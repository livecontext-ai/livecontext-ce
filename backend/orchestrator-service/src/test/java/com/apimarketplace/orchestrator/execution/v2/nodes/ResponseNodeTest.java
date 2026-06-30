package com.apimarketplace.orchestrator.execution.v2.nodes;

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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ResponseNode.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResponseNode")
class ResponseNodeTest {

    @Mock private WorkflowPlan mockPlan;
    @Mock private V2TemplateAdapter mockTemplateAdapter;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-0", 0, Map.of(), mockPlan);
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("should set message template")
        void shouldSetMessageTemplate() {
            ResponseNode node = new ResponseNode("core:response", "Hello {{name}}");
            assertEquals("Hello {{name}}", node.getMessageTemplate());
        }

        @Test
        @DisplayName("should default to empty string for null template")
        void shouldDefaultToEmpty() {
            ResponseNode node = new ResponseNode("core:response", null);
            assertEquals("", node.getMessageTemplate());
        }

        @Test
        @DisplayName("should have RESPONSE node type")
        void shouldHaveResponseType() {
            ResponseNode node = new ResponseNode("core:response", "message");
            assertEquals(NodeType.RESPONSE, node.getType());
        }
    }

    @Nested
    @DisplayName("execute")
    class Execute {

        @Test
        @DisplayName("should return success with resolved message when template adapter available")
        void shouldResolveMessage() {
            ResponseNode node = new ResponseNode("core:response", "Hello World");
            node.setTemplateAdapter(mockTemplateAdapter);

            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenReturn(Map.of("__message__", "Hello World Resolved"));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("RESPONSE", result.output().get("node_type"));
            assertEquals("Hello World Resolved", result.output().get("message"));
            assertNotNull(result.output().get("sent_at"));
        }

        @Test
        @DisplayName("should return raw message when no template adapter")
        void shouldReturnRawMessage() {
            ResponseNode node = new ResponseNode("core:response", "Raw message");

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("Raw message", result.output().get("message"));
        }

        @Test
        @DisplayName("should handle blank message template")
        void shouldHandleBlankMessage() {
            ResponseNode node = new ResponseNode("core:response", "  ");

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("  ", result.output().get("message"));
        }

        @Test
        @DisplayName("should return failure when template resolution throws")
        void shouldReturnFailureOnException() {
            ResponseNode node = new ResponseNode("core:response", "{{invalid}}");
            node.setTemplateAdapter(mockTemplateAdapter);

            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenThrow(new RuntimeException("Template error"));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build ResponseNode with builder")
        void shouldBuild() {
            ResponseNode node = ResponseNode.builder()
                .nodeId("core:my_response")
                .messageTemplate("Hello!")
                .build();

            assertEquals("core:my_response", node.getNodeId());
            assertEquals("Hello!", node.getMessageTemplate());
        }
    }
}
