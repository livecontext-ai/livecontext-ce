package com.apimarketplace.orchestrator.controllers.agent;

import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.ToolsRegistrationService;
import com.apimarketplace.common.classification.DataSensitivity;
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
 * Regression review 2026-09-29 (CASA LC-066): both request paths of this controller rebuild the
 * credentials key by key, and the restricted-data tag forwarded by agent-service was not one of
 * them, so an orchestrator tool relayed from a chat or agent holding Gmail content saw an ordinary
 * caller. This endpoint is gateway-routed and its body is caller-controlled: only RESTRICTED is
 * honoured, so a body can tighten what a tool does, never relax it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Orchestrator AgentToolsController restores the forwarded restricted-data tag")
class AgentToolsControllerRestrictedTagTest {

    @Mock private AgentToolRegistry registry;
    @Mock private ToolsRegistrationService registrationService;
    private AgentToolsController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentToolsController(registry, registrationService);
    }

    private Map<String, Object> body(Object dataSensitivity) {
        Map<String, Object> body = new HashMap<>();
        body.put("tool", "workflow");
        body.put("parameters", Map.of("action", "list"));
        if (dataSensitivity != null) {
            body.put("dataSensitivity", dataSensitivity);
        }
        return body;
    }

    private MockHttpServletRequest http() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.addHeader("X-User-ID", "tenant-1");
        return http;
    }

    private Map<String, Object> syncCredentials(Object dataSensitivity) {
        when(registry.hasTool("workflow")).thenReturn(true);
        when(registrationService.executeTool(eq("workflow"), any(), any()))
            .thenReturn(ToolExecutionResult.success(Map.of()));
        controller.executeTool(http(), body(dataSensitivity));
        ArgumentCaptor<ToolExecutionContext> ctx = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(registrationService).executeTool(eq("workflow"), any(), ctx.capture());
        return ctx.getValue().credentials();
    }

    private Map<String, Object> asyncCredentials(Object dataSensitivity) {
        when(registry.hasTool("workflow")).thenReturn(true);
        when(registrationService.executeToolAsync(eq("workflow"), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(ToolExecutionResult.success(Map.of())));
        controller.executeToolAsync(http(), body(dataSensitivity));
        ArgumentCaptor<ToolExecutionContext> ctx = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(registrationService).executeToolAsync(eq("workflow"), any(), ctx.capture());
        return ctx.getValue().credentials();
    }

    @Test
    @DisplayName("regression: /execute restores a forwarded RESTRICTED tag")
    void syncPathRestoresTheTag() {
        assertThat(DataSensitivity.fromCredentials(syncCredentials("RESTRICTED")).isRestricted()).isTrue();
    }

    @Test
    @DisplayName("regression: /execute-async restores a forwarded RESTRICTED tag")
    void asyncPathRestoresTheTag() {
        assertThat(DataSensitivity.fromCredentials(asyncCredentials("RESTRICTED")).isRestricted()).isTrue();
    }

    @Test
    @DisplayName("a body saying NORMAL writes nothing: it cannot relax a restriction, only its absence means NORMAL")
    void normalWritesNothing() {
        assertThat(syncCredentials("NORMAL")).doesNotContainKey(DataSensitivity.CREDENTIAL_KEY);
    }

    @Test
    @DisplayName("no field: the call stays untagged")
    void absentStaysUntagged() {
        assertThat(asyncCredentials(null)).doesNotContainKey(DataSensitivity.CREDENTIAL_KEY);
    }
}
