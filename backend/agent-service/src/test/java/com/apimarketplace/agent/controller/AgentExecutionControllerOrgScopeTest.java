package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.agent.client.dto.execution.ClassifyRequestDto;
import com.apimarketplace.agent.client.dto.execution.ClassifyResponseDto;
import com.apimarketplace.agent.client.dto.execution.GuardrailRequestDto;
import com.apimarketplace.agent.client.dto.execution.GuardrailResponseDto;
import com.apimarketplace.agent.service.execution.AgentRemoteExecutionService;
import com.apimarketplace.agent.service.execution.ClassifyService;
import com.apimarketplace.agent.service.execution.GuardrailService;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DisplayName("AgentExecutionController org scope")
@ExtendWith(MockitoExtension.class)
class AgentExecutionControllerOrgScopeTest {

    @Mock private AgentRemoteExecutionService executionService;
    @Mock private ClassifyService classifyService;
    @Mock private GuardrailService guardrailService;
    @Mock private HttpServletRequest httpRequest;

    private AgentExecutionController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentExecutionController(executionService, classifyService, guardrailService, null);
    }

    @Test
    @DisplayName("Binds X-Organization-ID before executing remote agent request")
    void bindsHeaderOrgIdBeforeExecutingRemoteAgentRequest() {
        AtomicReference<String> observedOrg = new AtomicReference<>();
        when(httpRequest.getHeader("X-Organization-ID")).thenReturn("org-header");
        when(executionService.executeAgent(any(), any())).thenAnswer(invocation -> {
            observedOrg.set(TenantResolver.currentRequestOrganizationId());
            return okResponse();
        });

        controller.executeAgent(httpRequest, requestWithCredentials(Map.<String, Object>of("__orgId__", "org-credential")));

        assertThat(observedOrg).hasValue("org-header");
    }

    @Test
    @DisplayName("Forwards X-User-Roles header to executeAgent so admin_only bridge policies recognise ADMIN callers (regression for 2026-05-21 'bridge_disabled' prod symptom)")
    void forwardsUserRolesHeaderToExecutionService() {
        // The bug: AgentExecutionController previously dropped X-User-Roles
        // and AgentRemoteExecutionService.executeAgent took no roles arg →
        // BridgeLoopDispatcher.dispatchRaw forwarded null roles → HttpBridgeAccessClient
        // defaulted to "USER" → V270 admin_only policy denied every dispatch
        // with reason=admin_only_requires_admin_role even for admins.
        // The fix: header → controller param → service → dispatcher → guard.
        // This test pins the controller→service hop.
        AtomicReference<String> observedRoles = new AtomicReference<>();
        when(httpRequest.getHeader("X-Organization-ID")).thenReturn(null);
        when(httpRequest.getHeader("X-User-Roles")).thenReturn("ADMIN,USER");
        when(executionService.executeAgent(any(), any())).thenAnswer(invocation -> {
            observedRoles.set(invocation.getArgument(1));
            return okResponse();
        });

        controller.executeAgent(httpRequest, requestWithCredentials(Map.<String, Object>of()));

        assertThat(observedRoles).hasValue("ADMIN,USER");
    }

    @Test
    @DisplayName("Forwards X-User-Roles header to ClassifyService - sibling regression for /classify so admin_only bridge policies recognise ADMIN callers")
    void forwardsUserRolesHeaderToClassifyService() {
        AtomicReference<String> observedRoles = new AtomicReference<>();
        when(httpRequest.getHeader("X-Organization-ID")).thenReturn(null);
        when(httpRequest.getHeader("X-User-Roles")).thenReturn("ADMIN,USER");
        when(classifyService.execute(any(), any())).thenAnswer(invocation -> {
            observedRoles.set(invocation.getArgument(1));
            return classifyOkResponse();
        });

        controller.executeClassify(httpRequest, classifyRequest());

        assertThat(observedRoles).hasValue("ADMIN,USER");
    }

    @Test
    @DisplayName("Forwards X-User-Roles header to GuardrailService - sibling regression for /guardrail so admin_only bridge policies recognise ADMIN callers")
    void forwardsUserRolesHeaderToGuardrailService() {
        AtomicReference<String> observedRoles = new AtomicReference<>();
        when(httpRequest.getHeader("X-Organization-ID")).thenReturn(null);
        when(httpRequest.getHeader("X-User-Roles")).thenReturn("ADMIN,USER");
        when(guardrailService.execute(any(), any())).thenAnswer(invocation -> {
            observedRoles.set(invocation.getArgument(1));
            return guardrailOkResponse();
        });

        controller.executeGuardrail(httpRequest, guardrailRequest());

        assertThat(observedRoles).hasValue("ADMIN,USER");
    }

    @Test
    @DisplayName("Falls back to credentials __orgId__ when the remote execution header is absent")
    void bindsCredentialsOrgIdWhenHeaderIsAbsent() {
        AtomicReference<String> observedOrg = new AtomicReference<>();
        when(httpRequest.getHeader("X-Organization-ID")).thenReturn(null);
        when(executionService.executeAgent(any(), any())).thenAnswer(invocation -> {
            observedOrg.set(TenantResolver.currentRequestOrganizationId());
            return okResponse();
        });

        controller.executeAgent(httpRequest, requestWithCredentials(Map.<String, Object>of("__orgId__", "org-credential")));

        assertThat(observedOrg).hasValue("org-credential");
    }

    private static AgentExecutionRequestDto requestWithCredentials(Map<String, Object> credentials) {
        return new AgentExecutionRequestDto(
                "prompt",
                "system",
                "openai",
                "gpt-5-mini",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "tenant-1",
                "run-1",
                "agent:test",
                null,
                credentials,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "agent-1",
                null,
                null,
                null,
                null,
                null,
                null,  // executionId
                null,  // source
                null,  // reasoningEffort
                null   // enabledModules
        );
    }

    private static ClassifyRequestDto classifyRequest() {
        return new ClassifyRequestDto(
                "content to classify",
                "classify this",
                List.of(new ClassifyRequestDto.CategoryDto("billing", "billing issues")),
                "claude-code",
                "claude-sonnet-4-6",
                null,
                null,
                "tenant-1",
                "agent-1"
        );
    }

    private static ClassifyResponseDto classifyOkResponse() {
        return new ClassifyResponseDto(
                true, "billing", 0.9, "matched", null,
                1L, "claude-code", "claude-sonnet-4-6",
                0, 0, 0, null, null, null
        );
    }

    private static GuardrailRequestDto guardrailRequest() {
        return new GuardrailRequestDto(
                "content to validate",
                "validate this",
                List.of(new GuardrailRequestDto.RuleDto("no-pii", "no personal info")),
                "block",
                "claude-code",
                "claude-sonnet-4-6",
                null,
                null,
                "tenant-1",
                "agent-1"
        );
    }

    private static GuardrailResponseDto guardrailOkResponse() {
        return new GuardrailResponseDto(
                true, true, List.of(), Map.of(), null, null,
                1L, "claude-code", "claude-sonnet-4-6",
                0, 0, 0, null, null, null
        );
    }

    private static AgentExecutionResponseDto okResponse() {
        return new AgentExecutionResponseDto(
                true,
                "ok",
                "ok",
                null,
                1,
                null,
                null,
                1L,
                "openai",
                "gpt-5-mini",
                null,
                "COMPLETED",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
