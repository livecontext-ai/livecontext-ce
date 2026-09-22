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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Orchestrator AgentToolsController async org scope")
class AgentToolsControllerAsyncOrgScopeTest {

    @Mock private AgentToolRegistry registry;
    @Mock private ToolsRegistrationService registrationService;
    private AgentToolsController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentToolsController(registry, registrationService);
    }

    @Test
    @DisplayName("execute-async forwards X-Organization-ID into context.orgId()")
    void asyncForwardsOrganizationIdIntoContext() {
        when(registry.hasTool("workflow")).thenReturn(true);
        when(registrationService.executeToolAsync(eq("workflow"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ToolExecutionResult.success(Map.of())));

        MockHttpServletRequest httpReq = new MockHttpServletRequest();
        httpReq.addHeader("X-User-ID", "tenant-1");
        httpReq.addHeader("X-Organization-ID", "org-orchestrator-tools-async");
        httpReq.addHeader("X-Organization-Role", "member");

        Map<String, Object> body = new HashMap<>();
        body.put("tool", "workflow");
        body.put("parameters", Map.of("action", "list"));
        body.put("conversationId", "conversation-1");
        body.put("turnId", "turn-1");
        body.put("agentId", "agent-1");
        body.put("allowedWorkflowIds", List.of("workflow-1"));
        body.put("workflowAccessMode", "read");
        body.put("workflowRunId", "run-async-1");
        body.put("approvedServices", List.of("deepseek"));
        body.put("viewingWorkflowId", "viewing-workflow-1");
        body.put("viewingWorkflowName", "Viewing Workflow");

        controller.executeToolAsync(httpReq, body);

        ArgumentCaptor<ToolExecutionContext> ctx = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(registrationService).executeToolAsync(eq("workflow"), any(), ctx.capture());
        assertThat(ctx.getValue().orgId()).isEqualTo("org-orchestrator-tools-async");
        assertThat(ctx.getValue().orgRole()).isEqualTo("member");
        assertThat(ctx.getValue().approvedServices()).containsExactly("deepseek");
        assertThat(ctx.getValue().viewingWorkflowId()).isEqualTo("viewing-workflow-1");
        assertThat(ctx.getValue().viewingWorkflowName()).isEqualTo("Viewing Workflow");
        assertThat(ctx.getValue().credentials()).containsEntry("conversationId", "conversation-1");
        assertThat(ctx.getValue().credentials()).containsEntry("turnId", "turn-1");
        assertThat(ctx.getValue().credentials()).containsEntry("__agentId__", "agent-1");
        assertThat(ctx.getValue().credentials()).containsEntry("allowedWorkflowIds", List.of("workflow-1"));
        assertThat(ctx.getValue().credentials()).containsEntry("workflowAccessMode", "read");
        assertThat(ctx.getValue().credentials()).containsEntry("__workflowRunId__", "run-async-1");
    }

    @Test
    @DisplayName("execute-async forwards allowedFileIds into context credentials (agent file scoping)")
    void asyncForwardsAllowedFileIdsIntoCredentials() {
        // Regression: the chat path threads toolsConfig.files as request 'allowedFileIds';
        // the controller must copy it into the tool's credentials so FilesToolsProvider
        // can scope list/get/view to those ids. Before the fix the inbound loop omitted
        // files, so a scoped agent saw EVERY org file (the live e2e caught this).
        when(registry.hasTool("files")).thenReturn(true);
        when(registrationService.executeToolAsync(eq("files"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ToolExecutionResult.success(Map.of())));

        MockHttpServletRequest httpReq = new MockHttpServletRequest();
        httpReq.addHeader("X-User-ID", "tenant-1");
        httpReq.addHeader("X-Organization-ID", "org-file-scope");
        httpReq.addHeader("X-Organization-Role", "member");

        Map<String, Object> body = new HashMap<>();
        body.put("tool", "files");
        body.put("parameters", Map.of("action", "list"));
        body.put("allowedFileIds", List.of("file-1", "file-2"));

        controller.executeToolAsync(httpReq, body);

        ArgumentCaptor<ToolExecutionContext> ctx = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(registrationService).executeToolAsync(eq("files"), any(), ctx.capture());
        assertThat(ctx.getValue().credentials())
                .containsEntry("allowedFileIds", List.of("file-1", "file-2"));
    }

    @Test
    @DisplayName("a body-supplied orgRole never reaches the context, on BOTH the sync and async entry points")
    void bodyOrgRoleIsIgnoredOnBothEntryPoints() {
        // The body was a second channel for the privilege axis. Both entry points here read it,
        // and both are reachable through the gateway, which strips the caller's own identity
        // HEADERS but not the body: a user whose gateway resolved no active org could name a
        // workspace and assert OWNER in it in one request. Every internal caller sends the role
        // as a header from the same source it filled the body with, so nothing legitimate needs
        // the body read. orgId is deliberately still accepted from the body and asserted here so
        // the two are not conflated by a later reader.
        when(registry.hasTool("workflow")).thenReturn(true);
        when(registrationService.executeToolAsync(eq("workflow"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ToolExecutionResult.success(Map.of())));
        when(registrationService.executeTool(eq("workflow"), any(), any()))
                .thenReturn(ToolExecutionResult.success(Map.of()));

        MockHttpServletRequest httpReq = new MockHttpServletRequest();
        httpReq.addHeader("X-User-ID", "tenant-1");
        // deliberately NO X-Organization-Role header: this is the reachable state

        Map<String, Object> body = new HashMap<>();
        body.put("tool", "workflow");
        body.put("parameters", Map.of("action", "list"));
        body.put("orgId", "victim-org");
        body.put("orgRole", "OWNER");

        controller.executeToolAsync(httpReq, body);
        ArgumentCaptor<ToolExecutionContext> asyncCtx = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(registrationService).executeToolAsync(eq("workflow"), any(), asyncCtx.capture());
        assertThat(asyncCtx.getValue().orgRole())
                .as("execute-async must not take the role from the body")
                .isNull();
        assertThat(asyncCtx.getValue().orgId()).isEqualTo("victim-org");

        controller.executeTool(httpReq, body);
        ArgumentCaptor<ToolExecutionContext> syncCtx = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(registrationService).executeTool(eq("workflow"), any(), syncCtx.capture());
        assertThat(syncCtx.getValue().orgRole())
                .as("execute must not take the role from the body either")
                .isNull();
    }
}
