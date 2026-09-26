package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.AgentEntity;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V523 - the REST wiring of an agent's chat destination ({@code chatChannelLinkId}), the field
 * the agent modal's destination picker writes. Patch semantics like its siblings: present sets it
 * (null or blank = back to the workspace default), absent leaves it alone, and anything that is not
 * an id is refused BEFORE persistence, so a typo is never stored and no agent is half-created.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentController - chat destination REST wiring (V523)")
class AgentControllerChatChannelTest {

    private static final UUID AGENT_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID LINK_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final String TENANT_ID = "tenant-1";
    private static final String ORG_ID = "66666666-6666-4666-8666-666666666666";

    @Mock private AgentService agentService;
    @Mock private AgentWebhookTokenService webhookTokenService;
    @Mock private AgentWidgetConfigService widgetConfigService;
    @Mock private TenantResolver tenantResolver;
    @Mock private HttpServletRequest request;

    private AgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentController(
            agentService, webhookTokenService, widgetConfigService, tenantResolver,
            new RequestParameterExtractor(), "", "http://widget.test", "http://trigger.test");
    }

    private AgentEntity agent() {
        AgentEntity e = new AgentEntity();
        e.setId(AGENT_ID);
        e.setName("Finance");
        return e;
    }

    private AgentEntity stubCreate() {
        AgentEntity created = agent();
        when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(created);
        return created;
    }

    private AgentEntity stubUpdate() {
        AgentEntity updated = agent();
        when(agentService.updateAgent(any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            anyBoolean()))
            .thenReturn(updated);
        return updated;
    }

    private static Map<String, Object> body(Object chatChannelLinkId) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Finance");
        body.put("chatChannelLinkId", chatChannelLinkId);
        return body;
    }

    @Test
    @DisplayName("create with a destination id stores it on the new agent")
    void createStoresDestination() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        AgentEntity created = stubCreate();
        when(agentService.setChatChannelLinkId(AGENT_ID, TENANT_ID, ORG_ID, LINK_ID)).thenReturn(created);

        var response = controller.createAgent(request, body(LINK_ID.toString()));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(agentService).setChatChannelLinkId(AGENT_ID, TENANT_ID, ORG_ID, LINK_ID);
    }

    @Test
    @DisplayName("create with something that is not an id -> 400, and no agent is created")
    void createRejectsMalformed() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);

        var response = controller.createAgent(request, body("finance chat"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(agentService, never()).createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(agentService, never()).setChatChannelLinkId(any(), any(), any(), any());
    }

    @Test
    @DisplayName("update with null goes back to the workspace default")
    void updateNullIsDefault() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        AgentEntity updated = stubUpdate();
        when(agentService.setChatChannelLinkId(eq(AGENT_ID), eq(TENANT_ID), eq(ORG_ID), isNull())).thenReturn(updated);

        var response = controller.updateAgent(AGENT_ID, request, ORG_ID, body(null));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(agentService).setChatChannelLinkId(eq(AGENT_ID), eq(TENANT_ID), eq(ORG_ID), isNull());
    }

    @Test
    @DisplayName("update with a blank value also means the workspace default, not an error")
    void updateBlankIsDefault() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        AgentEntity updated = stubUpdate();
        when(agentService.setChatChannelLinkId(eq(AGENT_ID), eq(TENANT_ID), eq(ORG_ID), isNull())).thenReturn(updated);

        assertThat(controller.updateAgent(AGENT_ID, request, ORG_ID, body("  ")).getStatusCode().is2xxSuccessful()).isTrue();
        verify(agentService).setChatChannelLinkId(eq(AGENT_ID), eq(TENANT_ID), eq(ORG_ID), isNull());
    }

    @Test
    @DisplayName("update without the key leaves the destination alone")
    void updateAbsentIsUnchanged() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        stubUpdate();
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Finance");

        controller.updateAgent(AGENT_ID, request, ORG_ID, body);

        verify(agentService, never()).setChatChannelLinkId(any(), any(), any(), any());
    }

    @Test
    @DisplayName("V524: update with chatChannelEnabled=false switches the channel off")
    void updateSwitchesOff() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        AgentEntity updated = stubUpdate();
        when(agentService.setChatChannelEnabled(AGENT_ID, TENANT_ID, ORG_ID, false)).thenReturn(updated);
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Finance");
        body.put("chatChannelEnabled", false);

        assertThat(controller.updateAgent(AGENT_ID, request, ORG_ID, body).getStatusCode().is2xxSuccessful()).isTrue();
        verify(agentService).setChatChannelEnabled(AGENT_ID, TENANT_ID, ORG_ID, false);
    }

    @Test
    @DisplayName("V524: create with chatChannelEnabled=false creates the agent switched off")
    void createSwitchedOff() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        AgentEntity created = stubCreate();
        when(agentService.setChatChannelEnabled(AGENT_ID, TENANT_ID, ORG_ID, false)).thenReturn(created);
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Finance");
        body.put("chatChannelEnabled", "false");

        assertThat(controller.createAgent(request, body).getStatusCode().is2xxSuccessful()).isTrue();
        verify(agentService).setChatChannelEnabled(AGENT_ID, TENANT_ID, ORG_ID, false);
    }

    @Test
    @DisplayName("V524: chatChannelEnabled that is not a boolean -> 400 before any write (never read as false)")
    void switchMustBeBoolean() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Finance");
        body.put("chatChannelEnabled", "maybe");

        assertThat(controller.updateAgent(AGENT_ID, request, ORG_ID, body).getStatusCode().value()).isEqualTo(400);
        verify(agentService, never()).setChatChannelEnabled(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("update with something that is not an id -> 400, nothing updated")
    void updateRejectsMalformed() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);

        var response = controller.updateAgent(AGENT_ID, request, ORG_ID, body(42));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(agentService, never()).setChatChannelLinkId(any(), any(), any(), any());
    }
}
