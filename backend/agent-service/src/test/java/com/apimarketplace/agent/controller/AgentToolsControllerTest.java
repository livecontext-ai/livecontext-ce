package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.agent.tools.ToolsRegistrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@DisplayName("AgentToolsController")
@ExtendWith(MockitoExtension.class)
class AgentToolsControllerTest {

    @Mock private AgentToolRegistry registry;
    @Mock private ToolsRegistrationService registrationService;

    @Test
    @DisplayName("Forwards per-resource access modes into tool execution credentials")
    void forwardsAccessModesIntoExecutionCredentials() {
        AgentToolsController controller = new AgentToolsController(registry, registrationService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", "tenant-1");
        request.addHeader("X-Organization-ID", "org-1");
        request.addHeader("X-Organization-Role", "MEMBER");

        when(registry.hasTool("agent")).thenReturn(true);
        ArgumentCaptor<ToolsProvider.ToolExecutionContext> contextCaptor =
            ArgumentCaptor.forClass(ToolsProvider.ToolExecutionContext.class);
        when(registrationService.executeTool(eq("agent"), any(), contextCaptor.capture()))
            .thenReturn(ToolsProvider.ToolExecutionResult.success(Map.of("ok", true)));

        controller.executeTool(request, Map.of(
            "tool", "agent",
            "parameters", Map.of("action", "update"),
            "allowedAgentIds", List.of("agent-1"),
            "agentAccessMode", "read",
            "tableAccessMode", "write",
            "workflowAccessMode", "read"
        ));

        ToolsProvider.ToolExecutionContext context = contextCaptor.getValue();
        assertThat(context.tenantId()).isEqualTo("tenant-1");
        assertThat(context.orgId()).isEqualTo("org-1");
        assertThat(context.orgRole()).isEqualTo("MEMBER");
        assertThat(context.credentials()).containsEntry("allowedAgentIds", List.of("agent-1"));
        assertThat(context.credentials()).containsEntry("agentAccessMode", "read");
        assertThat(context.credentials()).containsEntry("tableAccessMode", "write");
        assertThat(context.credentials()).containsEntry("workflowAccessMode", "read");
    }

    @Test
    @DisplayName("Forwards the X-User-Roles header into __userRoles__ credentials (bridge-model gating)")
    void forwardsUserRolesHeaderIntoExecutionCredentials() {
        AgentToolsController controller = new AgentToolsController(registry, registrationService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", "tenant-1");
        request.addHeader("X-User-Roles", "ADMIN,USER");

        when(registry.hasTool("agent")).thenReturn(true);
        ArgumentCaptor<ToolsProvider.ToolExecutionContext> contextCaptor =
            ArgumentCaptor.forClass(ToolsProvider.ToolExecutionContext.class);
        when(registrationService.executeTool(eq("agent"), any(), contextCaptor.capture()))
            .thenReturn(ToolsProvider.ToolExecutionResult.success(Map.of("ok", true)));

        controller.executeTool(request, Map.of(
            "tool", "agent",
            "parameters", Map.of("action", "help_models")
        ));

        assertThat(contextCaptor.getValue().credentials())
            .containsEntry("__userRoles__", "ADMIN,USER");
    }

    @Test
    @DisplayName("Falls back to the request-body userRoles when the X-User-Roles header is absent")
    void fallsBackToBodyUserRolesWhenHeaderAbsent() {
        AgentToolsController controller = new AgentToolsController(registry, registrationService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", "tenant-1");

        when(registry.hasTool("agent")).thenReturn(true);
        ArgumentCaptor<ToolsProvider.ToolExecutionContext> contextCaptor =
            ArgumentCaptor.forClass(ToolsProvider.ToolExecutionContext.class);
        when(registrationService.executeTool(eq("agent"), any(), contextCaptor.capture()))
            .thenReturn(ToolsProvider.ToolExecutionResult.success(Map.of("ok", true)));

        controller.executeTool(request, Map.of(
            "tool", "agent",
            "parameters", Map.of("action", "help_models"),
            "userRoles", "USER"
        ));

        assertThat(contextCaptor.getValue().credentials())
            .containsEntry("__userRoles__", "USER");
    }

    @Test
    @DisplayName("Forwards credentials and workflow context into async tool execution")
    void forwardsCredentialsIntoAsyncExecution() {
        AgentToolsController controller = new AgentToolsController(registry, registrationService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", "tenant-1");
        // The role travels as a HEADER, which is how every internal caller already sends it
        // (RemoteToolExecutionService.applyOrgHeaders). This test used to put it in the body and
        // assert it arrived, which pinned the body fallback that let a gateway-routed caller
        // assert its own role. The test's subject is that the context is forwarded, not which
        // channel the role uses, so it keeps its meaning on the honest channel.
        request.addHeader("X-Organization-Role", "MEMBER");

        when(registry.hasTool("agent")).thenReturn(true);
        ArgumentCaptor<ToolsProvider.ToolExecutionContext> contextCaptor =
            ArgumentCaptor.forClass(ToolsProvider.ToolExecutionContext.class);
        when(registrationService.executeToolAsync(eq("agent"), any(), contextCaptor.capture()))
            .thenReturn(CompletableFuture.completedFuture(
                ToolsProvider.ToolExecutionResult.success(Map.of("ok", true))));

        controller.executeToolAsync(request, Map.ofEntries(
            Map.entry("tool", "agent"),
            Map.entry("parameters", Map.of("action", "backlog")),
            Map.entry("conversationId", "conversation-1"),
            Map.entry("turnId", "turn-1"),
            Map.entry("agentId", "agent-1"),
            Map.entry("allowedAgentIds", List.of("child-1")),
            Map.entry("agentAccessMode", "read"),
            Map.entry("approvedServices", List.of("deepseek")),
            Map.entry("viewingWorkflowId", "workflow-1"),
            Map.entry("viewingWorkflowName", "Workflow One"),
            Map.entry("orgId", "org-1"),
            Map.entry("requireToolAuthorization", true),
            Map.entry("unattendedRun", true)
        )).join();

        ToolsProvider.ToolExecutionContext context = contextCaptor.getValue();
        assertThat(context.tenantId()).isEqualTo("tenant-1");
        assertThat(context.orgId()).isEqualTo("org-1");
        assertThat(context.orgRole()).isEqualTo("MEMBER");
        assertThat(context.viewingWorkflowId()).isEqualTo("workflow-1");
        assertThat(context.viewingWorkflowName()).isEqualTo("Workflow One");
        assertThat(context.approvedServices()).containsExactly("deepseek");
        assertThat(context.credentials()).containsEntry("conversationId", "conversation-1");
        assertThat(context.credentials()).containsEntry("turnId", "turn-1");
        assertThat(context.credentials()).containsEntry("__agentId__", "agent-1");
        assertThat(context.credentials()).containsEntry("allowedAgentIds", List.of("child-1"));
        assertThat(context.credentials()).containsEntry("agentAccessMode", "read");
        // The arming and the "nobody is watching" fact travel WITH the call. A key not
        // copied in this relay is simply gone by the time the gate reads it, and the gate
        // then treats an armed agent as unarmed and an unattended run as a person typing:
        // the sensitive action runs unasked, and nothing is ever delivered anywhere.
        assertThat(context.credentials()).containsEntry("__requireToolAuthorization__", true);
        assertThat(context.credentials()).containsEntry("__unattendedRun__", true);
    }

    @Test
    @DisplayName("an orgRole in the request BODY is ignored - the role comes from the header or not at all")
    void bodyOrgRoleIsIgnored() {
        // Regression. This endpoint is gateway-routed and the gateway strips the caller's own
        // identity headers, but NOT the body. When the gateway resolved no active org it sent no
        // X-Organization-Role, and the controller then read "orgRole" from the body: a user could
        // name a workspace AND assert OWNER in it in the same request. Every legitimate internal
        // caller already sends the header from the same source it filled the body with, so the
        // body read was a duplicate, not a channel.
        AgentToolsController controller = new AgentToolsController(registry, registrationService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", "tenant-1");
        // deliberately NO X-Organization-Role header: this is the reachable state

        when(registry.hasTool("agent")).thenReturn(true);
        ArgumentCaptor<ToolsProvider.ToolExecutionContext> contextCaptor =
            ArgumentCaptor.forClass(ToolsProvider.ToolExecutionContext.class);
        when(registrationService.executeTool(eq("agent"), any(), contextCaptor.capture()))
            .thenReturn(ToolsProvider.ToolExecutionResult.success(Map.of("ok", true)));

        controller.executeTool(request, Map.of(
            "tool", "agent",
            "parameters", Map.of("action", "update"),
            "orgId", "victim-org",
            "orgRole", "OWNER"
        ));

        ToolsProvider.ToolExecutionContext context = contextCaptor.getValue();
        assertThat(context.orgRole())
            .as("a body-supplied role must never reach the execution context")
            .isNull();
        // orgId still comes from the body on purpose: it is how an internal caller names the
        // workspace, and it is not the privilege axis. Pinned so the two are not conflated.
        assertThat(context.orgId()).isEqualTo("victim-org");
    }

    @Test
    @DisplayName("the HEADER role is still honoured, and wins over a body value")
    void headerOrgRoleIsStillHonoured() {
        AgentToolsController controller = new AgentToolsController(registry, registrationService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", "tenant-1");
        request.addHeader("X-Organization-Role", "MEMBER");

        when(registry.hasTool("agent")).thenReturn(true);
        ArgumentCaptor<ToolsProvider.ToolExecutionContext> contextCaptor =
            ArgumentCaptor.forClass(ToolsProvider.ToolExecutionContext.class);
        when(registrationService.executeTool(eq("agent"), any(), contextCaptor.capture()))
            .thenReturn(ToolsProvider.ToolExecutionResult.success(Map.of("ok", true)));

        controller.executeTool(request, Map.of(
            "tool", "agent",
            "parameters", Map.of("action", "update"),
            "orgRole", "OWNER"
        ));

        assertThat(contextCaptor.getValue().orgRole()).isEqualTo("MEMBER");
    }

    @Test
    @DisplayName("execute-async ignores a body orgRole too - both entry points read it, both are closed")
    void bodyOrgRoleIsIgnoredOnAsyncToo() {
        AgentToolsController controller = new AgentToolsController(registry, registrationService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", "tenant-1");

        when(registry.hasTool("agent")).thenReturn(true);
        ArgumentCaptor<ToolsProvider.ToolExecutionContext> contextCaptor =
            ArgumentCaptor.forClass(ToolsProvider.ToolExecutionContext.class);
        when(registrationService.executeToolAsync(eq("agent"), any(), contextCaptor.capture()))
            .thenReturn(CompletableFuture.completedFuture(
                ToolsProvider.ToolExecutionResult.success(Map.of("ok", true))));

        controller.executeToolAsync(request, Map.of(
            "tool", "agent",
            "parameters", Map.of("action", "backlog"),
            "orgId", "victim-org",
            "orgRole", "OWNER"
        )).join();

        assertThat(contextCaptor.getValue().orgRole()).isNull();
        assertThat(contextCaptor.getValue().orgId()).isEqualTo("victim-org");
    }
}
