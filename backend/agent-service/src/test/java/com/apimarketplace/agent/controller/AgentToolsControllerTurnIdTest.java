package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.tools.ToolsProvider;
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
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for the turnId wiring between conversation-service (which generates turnId
 * per agent turn and forwards it in the tool-execution HTTP body) and the per-turn create
 * cap in {@code AgentCrudModule}/{@code SkillCrudModule}/{@code SubAgentExecutionHandler}
 * (which read {@code credentials.get("turnId")}).
 *
 * <p>Before the fix this controller dropped {@code turnId} from the request body, so every
 * resource create saw {@code turnId == null} and the limiter was silently bypassed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentToolsController turnId passthrough")
class AgentToolsControllerTurnIdTest {

    @Mock private AgentToolRegistry registry;
    @Mock private ToolsRegistrationService registrationService;
    private AgentToolsController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentToolsController(registry, registrationService);
    }

    @Test
    @DisplayName("Forwards turnId from request body into context.credentials() - enables per-turn cap")
    void forwardsTurnIdIntoCredentials() {
        when(registry.hasTool("agent")).thenReturn(true);
        when(registrationService.executeTool(eq("agent"), any(), any()))
            .thenReturn(ToolExecutionResult.success(Map.of()));

        MockHttpServletRequest httpReq = new MockHttpServletRequest();
        httpReq.addHeader("X-User-ID", "tenant-1");

        Map<String, Object> body = new HashMap<>();
        body.put("tool", "agent");
        body.put("parameters", Map.of("action", "list"));
        body.put("turnId", "turn-abc");

        controller.executeTool(httpReq, body);

        ArgumentCaptor<ToolExecutionContext> ctx = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(registrationService).executeTool(eq("agent"), any(), ctx.capture());
        assertThat(ctx.getValue().credentials())
            .as("turnId MUST land in credentials - resolvers key the per-turn cap on it")
            .containsEntry("turnId", "turn-abc");
    }

    @Test
    @DisplayName("Absent turnId leaves credentials.turnId null - legacy callers stay permissive")
    void absentTurnIdLeavesNullCredentials() {
        when(registry.hasTool("agent")).thenReturn(true);
        when(registrationService.executeTool(eq("agent"), any(), any()))
            .thenReturn(ToolExecutionResult.success(Map.of()));

        MockHttpServletRequest httpReq = new MockHttpServletRequest();
        httpReq.addHeader("X-User-ID", "tenant-1");

        Map<String, Object> body = new HashMap<>();
        body.put("tool", "agent");
        body.put("parameters", Map.of("action", "list"));

        controller.executeTool(httpReq, body);

        ArgumentCaptor<ToolExecutionContext> ctx = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(registrationService).executeTool(eq("agent"), any(), ctx.capture());
        assertThat(ctx.getValue().credentials()).doesNotContainKey("turnId");
    }

    @Test
    @DisplayName("execute-async forwards X-Organization-ID into context.orgId()")
    void asyncForwardsOrganizationIdIntoContext() {
        when(registry.hasTool("agent")).thenReturn(true);
        when(registrationService.executeToolAsync(eq("agent"), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(ToolExecutionResult.success(Map.of())));

        MockHttpServletRequest httpReq = new MockHttpServletRequest();
        httpReq.addHeader("X-User-ID", "tenant-1");
        httpReq.addHeader("X-Organization-ID", "org-tools-async");
        httpReq.addHeader("X-Organization-Role", "admin");

        Map<String, Object> body = new HashMap<>();
        body.put("tool", "agent");
        body.put("parameters", Map.of("action", "list"));

        controller.executeToolAsync(httpReq, body);

        ArgumentCaptor<ToolExecutionContext> ctx = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(registrationService).executeToolAsync(eq("agent"), any(), ctx.capture());
        assertThat(ctx.getValue().orgId()).isEqualTo("org-tools-async");
        assertThat(ctx.getValue().orgRole()).isEqualTo("admin");
    }
}
