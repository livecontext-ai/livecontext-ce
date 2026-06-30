package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.AgentWidgetConfigEntity;
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

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the org-scope contract on the widget routes of {@code AgentController}:
 * every widget endpoint resolves {@code orgId}/{@code orgRole} from the request and
 * threads them into the 4-arg {@code getAgent(id, tenantId, orgId, orgRole)} overload,
 * so an out-of-scope caller can never reach a teammate's widget config. The prod fix
 * that wires the 4-arg overload has landed (createOrUpdate / get / patch / delete widget
 * endpoints), so these tests are active.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentController widget scope")
class AgentControllerWidgetScopeTest {

    private static final UUID AGENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String TENANT_ID = "tenant-1";
    private static final String ORG_ID = "22222222-2222-4222-8222-222222222222";
    private static final String ORG_ROLE = "MEMBER";

    @Mock private AgentService agentService;
    @Mock private AgentWebhookTokenService webhookTokenService;
    @Mock private AgentWidgetConfigService widgetConfigService;
    @Mock private TenantResolver tenantResolver;
    @Mock private HttpServletRequest request;

    private AgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentController(
            agentService,
            webhookTokenService,
            widgetConfigService,
            tenantResolver,
            new RequestParameterExtractor(),
            "",
            "http://widgets.test",
            "http://localhost:8091"
        );
    }

    @Test
    @DisplayName("POST /{id}/widget resolves agent in active organization scope")
    void createWidgetUsesOrganizationScope() {
        AgentEntity agent = agent();
        AgentWidgetConfigEntity widget = widget();

        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        when(tenantResolver.resolveOrgRole(request)).thenReturn(ORG_ROLE);
        when(agentService.getAgent(AGENT_ID, TENANT_ID, ORG_ID, ORG_ROLE)).thenReturn(Optional.of(agent));
        when(widgetConfigService.isValidPosition("bottom-left")).thenReturn(true);
        when(widgetConfigService.isValidTheme("dark")).thenReturn(true);
        when(widgetConfigService.isValidColor("#123456")).thenReturn(true);
        when(widgetConfigService.createOrUpdateWidgetConfig(
            eq(AGENT_ID),
            eq("bottom-left"),
            eq("dark"),
            eq("#123456"),
            eq("Welcome"),
            eq("Chat"),
            eq(true),
            eq(0),
            eq("https://example.test")
        )).thenReturn(widget);
        when(widgetConfigService.getWidgetScriptUrl("http://widgets.test"))
            .thenReturn("http://widgets.test/widget.js");

        ResponseEntity<Map<String, Object>> response = controller.createOrUpdateWidgetConfig(
            AGENT_ID,
            request,
            Map.of(
                "position", "bottom-left",
                "theme", "dark",
                "primaryColor", "#123456",
                "welcomeMessage", "Welcome",
                "bubbleText", "Chat",
                "showAvatar", true,
                "autoOpenDelay", 0,
                "allowedOrigins", "https://example.test"
            ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("agentId", AGENT_ID.toString());
        assertThat(response.getBody()).containsEntry("agentName", "Widget Scope Agent");
        verify(agentService).getAgent(AGENT_ID, TENANT_ID, ORG_ID, ORG_ROLE);
    }

    @Test
    @DisplayName("GET /{id}/widget returns 404 before reading widget config when org access is denied")
    void getWidgetStopsWhenOrganizationScopeDeniesAgent() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        when(tenantResolver.resolveOrgRole(request)).thenReturn(ORG_ROLE);
        when(agentService.getAgent(AGENT_ID, TENANT_ID, ORG_ID, ORG_ROLE)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.getWidgetConfig(AGENT_ID, request, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(widgetConfigService, never()).getWidgetConfig(any());
    }

    private static AgentEntity agent() {
        AgentEntity agent = new AgentEntity();
        agent.setId(AGENT_ID);
        agent.setTenantId(TENANT_ID);
        agent.setOrganizationId(ORG_ID);
        agent.setName("Widget Scope Agent");
        agent.setAvatarUrl("https://cdn.test/avatar.png");
        return agent;
    }

    private static AgentWidgetConfigEntity widget() {
        AgentWidgetConfigEntity widget = new AgentWidgetConfigEntity(AGENT_ID);
        widget.setWidgetToken("wid_1234567890abcdef1234567890abcdef");
        widget.setPosition("bottom-left");
        widget.setTheme("dark");
        widget.setPrimaryColor("#123456");
        widget.setWelcomeMessage("Welcome");
        widget.setBubbleText("Chat");
        widget.setShowAvatar(true);
        widget.setAutoOpenDelay(0);
        widget.setAllowedOrigins("https://example.test");
        return widget;
    }
}
