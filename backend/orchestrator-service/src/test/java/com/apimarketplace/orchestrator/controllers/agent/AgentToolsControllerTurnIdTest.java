package com.apimarketplace.orchestrator.controllers.agent;

import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.ToolsRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for the turnId wiring between conversation-service (which generates turnId
 * per agent turn and forwards it in the tool-execution HTTP body) and the per-turn workflow
 * create cap in {@code WorkflowBuilderProvider.executeFinish} (which reads
 * {@code credentials.get("turnId")}).
 *
 * <p>Before the fix this controller dropped {@code turnId} from the request body, so every
 * workflow create saw {@code turnId == null} and the create limiter was silently bypassed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentToolsController (orchestrator) turnId passthrough")
class AgentToolsControllerTurnIdTest {

    @Mock private AgentToolRegistry registry;
    @Mock private ToolsRegistrationService registrationService;
    private AgentToolsController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentToolsController(registry, registrationService);
    }

    @Test
    @DisplayName("Forwards turnId from request body into context.credentials() - enables workflow per-turn cap")
    void forwardsTurnIdIntoCredentials() {
        when(registry.hasTool("workflow")).thenReturn(true);
        when(registrationService.executeTool(eq("workflow"), any(), any()))
            .thenReturn(ToolExecutionResult.success(Map.of()));

        MockHttpServletRequest httpReq = new MockHttpServletRequest();
        httpReq.addHeader("X-User-ID", "tenant-1");

        Map<String, Object> body = new HashMap<>();
        body.put("tool", "workflow");
        body.put("parameters", Map.of("action", "list"));
        body.put("turnId", "turn-abc");

        controller.executeTool(httpReq, body);

        ArgumentCaptor<ToolExecutionContext> ctx = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(registrationService).executeTool(eq("workflow"), any(), ctx.capture());
        assertThat(ctx.getValue().credentials())
            .as("turnId MUST land in credentials - WorkflowBuilderProvider keys the per-turn cap on it")
            .containsEntry("turnId", "turn-abc");
    }

    @Test
    @DisplayName("Absent turnId leaves credentials.turnId null - legacy callers stay permissive")
    void absentTurnIdLeavesNullCredentials() {
        when(registry.hasTool("workflow")).thenReturn(true);
        when(registrationService.executeTool(eq("workflow"), any(), any()))
            .thenReturn(ToolExecutionResult.success(Map.of()));

        MockHttpServletRequest httpReq = new MockHttpServletRequest();
        httpReq.addHeader("X-User-ID", "tenant-1");

        Map<String, Object> body = new HashMap<>();
        body.put("tool", "workflow");
        body.put("parameters", Map.of("action", "list"));

        controller.executeTool(httpReq, body);

        ArgumentCaptor<ToolExecutionContext> ctx = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(registrationService).executeTool(eq("workflow"), any(), ctx.capture());
        assertThat(ctx.getValue().credentials()).doesNotContainKey("turnId");
    }

    @Test
    @DisplayName("Forwards workflowRunId from request body into context credentials")
    void forwardsWorkflowRunIdIntoCredentials() {
        when(registry.hasTool("web_search")).thenReturn(true);
        when(registrationService.executeTool(eq("web_search"), any(), any()))
            .thenReturn(ToolExecutionResult.success(Map.of()));

        MockHttpServletRequest httpReq = new MockHttpServletRequest();
        httpReq.addHeader("X-User-ID", "tenant-1");

        Map<String, Object> body = new HashMap<>();
        body.put("tool", "web_search");
        body.put("parameters", Map.of("action", "search", "query", "java"));
        body.put("toolCallId", "call-1");
        body.put("workflowRunId", "run-abc");

        controller.executeTool(httpReq, body);

        ArgumentCaptor<ToolExecutionContext> ctx = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(registrationService).executeTool(eq("web_search"), any(), ctx.capture());
        assertThat(ctx.getValue().credentials())
            .containsEntry("__toolCallId__", "call-1")
            .containsEntry("__workflowRunId__", "run-abc");
    }
}
