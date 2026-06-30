package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.dto.AgentAvatarResponse;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.AgentWidgetConfigService;
import com.apimarketplace.agent.util.RequestParameterExtractor;
import com.apimarketplace.agent.webhook.AgentWebhookTokenService;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Contract tests for {@code GET /api/agents/avatars} - the lightweight
 * (id, avatarUrl) projection consumed by the conversation sidebar.
 *
 * <p>Regression guard for the "load entire agent fleet for avatars" anti-pattern:
 * the sidebar previously called {@code GET /api/agents}, which serializes the
 * full {@code AgentEntity} (system_prompt LOB, config blobs, …). The lightweight
 * endpoint must return ONLY the (id, avatarUrl) pair.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentController - GET /agents/avatars")
class AgentControllerAvatarsTest {

    @Mock private AgentService agentService;
    @Mock private AgentWebhookTokenService webhookTokenService;
    @Mock private AgentWidgetConfigService widgetConfigService;
    @Mock private TenantResolver tenantResolver;
    @Mock private HttpServletRequest request;

    private AgentController controller;

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-1";
    private static final String ROLE = "MEMBER";

    @BeforeEach
    void setUp() {
        RequestParameterExtractor extractor = new RequestParameterExtractor();
        controller = new AgentController(
            agentService,
            webhookTokenService,
            widgetConfigService,
            tenantResolver,
            extractor,
            "",
            "",
            "http://localhost:8091"
        );
    }

    @Test
    @DisplayName("Returns the lightweight (id, avatarUrl) list straight from the service - no transformation")
    void returnsLightweightProjection() {
        UUID a1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID a2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        List<AgentAvatarResponse> projection = List.of(
            new AgentAvatarResponse(a1, "https://cdn/a1.png"),
            new AgentAvatarResponse(a2, null)
        );

        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG);
        when(tenantResolver.resolveOrgRole(request)).thenReturn(ROLE);
        when(agentService.listAgentAvatars(TENANT, ORG, ROLE)).thenReturn(projection);

        ResponseEntity<List<AgentAvatarResponse>> response =
            controller.listAgentAvatars(request, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactlyElementsOf(projection);
    }

    @Test
    @DisplayName("Forwards an empty list when the tenant has no agents - no body magic")
    void emptyListPassesThrough() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT);
        when(tenantResolver.resolveOrgId(request)).thenReturn(null);
        when(tenantResolver.resolveOrgRole(request)).thenReturn(null);
        when(agentService.listAgentAvatars(TENANT, null, null)).thenReturn(List.of());

        ResponseEntity<List<AgentAvatarResponse>> response =
            controller.listAgentAvatars(request, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    @DisplayName("Honors the tenantId query parameter as a fallback for the X-User-ID header")
    void tenantIdParamForwardedToResolver() {
        String paramTenant = "tenant-from-query";
        when(tenantResolver.resolveOrNull(request)).thenReturn(paramTenant);
        when(tenantResolver.resolveOrgId(request)).thenReturn(null);
        when(tenantResolver.resolveOrgRole(request)).thenReturn(null);
        when(agentService.listAgentAvatars(paramTenant, null, null)).thenReturn(List.of());

        ResponseEntity<List<AgentAvatarResponse>> response =
            controller.listAgentAvatars(request, paramTenant);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
