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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V372 - proves the REST create/update wiring of the per-agent inactivity-timeout override:
 * when the body carries {@code inactivityTimeout}, the controller routes it to
 * {@link AgentService#setInactivityTimeout} verbatim (including {@code 0} = disabled);
 * an absent key leaves the setter uncalled (patch semantics); and an out-of-range value
 * is rejected with 400 BEFORE any persistence, so a bad value can never leave an orphaned
 * agent. This is the REST layer the agent-config UI (CreateAgentModal) talks to - distinct
 * from the MCP AgentCrudModule path - so without this wiring the modal field saves nothing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentController - inactivity-timeout REST wiring (V372)")
class AgentControllerInactivityTest {

    private static final UUID AGENT_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
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
        e.setName("Worker");
        return e;
    }

    @SuppressWarnings("unchecked")
    private AgentEntity stubCreate() {
        AgentEntity created = agent();
        when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(created);
        return created;
    }

    @SuppressWarnings("unchecked")
    private AgentEntity stubUpdate() {
        AgentEntity updated = agent();
        when(agentService.updateAgent(any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            anyBoolean()))
            .thenReturn(updated);
        return updated;
    }

    @Test
    @DisplayName("create with inactivityTimeout=120 routes it verbatim to setInactivityTimeout")
    void createForwardsInactivity() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        AgentEntity created = stubCreate();
        when(agentService.setInactivityTimeout(eq(AGENT_ID), eq(TENANT_ID), eq(ORG_ID), eq(120)))
            .thenReturn(created);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");
        body.put("inactivityTimeout", 120);

        var response = controller.createAgent(request, body);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(agentService).setInactivityTimeout(eq(AGENT_ID), eq(TENANT_ID), eq(ORG_ID), eq(120));
    }

    @Test
    @DisplayName("create with inactivityTimeout=0 (disabled) is carried verbatim, not rejected")
    void createForwardsDisabledZero() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        AgentEntity created = stubCreate();
        when(agentService.setInactivityTimeout(eq(AGENT_ID), eq(TENANT_ID), eq(ORG_ID), eq(0)))
            .thenReturn(created);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");
        body.put("inactivityTimeout", 0);

        var response = controller.createAgent(request, body);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(agentService).setInactivityTimeout(eq(AGENT_ID), eq(TENANT_ID), eq(ORG_ID), eq(0));
    }

    @Test
    @DisplayName("create without inactivityTimeout key -> setInactivityTimeout never called (default 5 min)")
    void createOmitsInactivity() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        stubCreate();

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");

        controller.createAgent(request, body);

        verify(agentService, never()).setInactivityTimeout(any(), any(), any(), any());
    }

    @Test
    @DisplayName("create with inactivityTimeout below range (5) -> 400, agent never created")
    void createRejectsBelowRange() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");
        body.put("inactivityTimeout", 5);

        var response = controller.createAgent(request, body);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(agentService, never()).createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(agentService, never()).setInactivityTimeout(any(), any(), any(), any());
    }

    @Test
    @DisplayName("create with inactivityTimeout above range (7201) -> 400, agent never created")
    void createRejectsAboveRange() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");
        body.put("inactivityTimeout", 7201);

        var response = controller.createAgent(request, body);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(agentService, never()).setInactivityTimeout(any(), any(), any(), any());
    }

    @Test
    @DisplayName("create accepts the range boundaries 10 and 7200")
    void createAcceptsBoundaries() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        AgentEntity created = stubCreate();
        when(agentService.setInactivityTimeout(eq(AGENT_ID), eq(TENANT_ID), eq(ORG_ID), eq(10)))
            .thenReturn(created);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");
        body.put("inactivityTimeout", 10);

        assertThat(controller.createAgent(request, body).getStatusCode().value()).isEqualTo(200);

        body.put("inactivityTimeout", 7200);
        when(agentService.setInactivityTimeout(eq(AGENT_ID), eq(TENANT_ID), eq(ORG_ID), eq(7200)))
            .thenReturn(created);
        assertThat(controller.createAgent(request, body).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("update with inactivityTimeout=300 routes it to setInactivityTimeout")
    void updateForwardsInactivity() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        AgentEntity updated = stubUpdate();
        when(agentService.setInactivityTimeout(eq(AGENT_ID), eq(TENANT_ID), eq(ORG_ID), eq(300)))
            .thenReturn(updated);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");
        body.put("inactivityTimeout", 300);

        var response = controller.updateAgent(AGENT_ID, request, ORG_ID, body);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(agentService).setInactivityTimeout(eq(AGENT_ID), eq(TENANT_ID), eq(ORG_ID), eq(300));
    }

    @Test
    @DisplayName("update without inactivityTimeout key -> setInactivityTimeout never called (unchanged)")
    void updateOmitsInactivity() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        stubUpdate();

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");

        controller.updateAgent(AGENT_ID, request, ORG_ID, body);

        verify(agentService, never()).setInactivityTimeout(any(), any(), any(), any());
    }

    @Test
    @DisplayName("update with inactivityTimeout below range (9) -> 400, agent never updated")
    void updateRejectsBelowRange() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");
        body.put("inactivityTimeout", 9);

        var response = controller.updateAgent(AGENT_ID, request, ORG_ID, body);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(agentService, never()).updateAgent(any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            anyBoolean());
        verify(agentService, never()).setInactivityTimeout(any(), any(), any(), any());
    }
}
