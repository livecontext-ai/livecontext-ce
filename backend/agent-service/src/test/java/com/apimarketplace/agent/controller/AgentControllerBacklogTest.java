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
 * V340 - proves the REST create/update wiring of the per-agent backlog opt-in flag:
 * when the request body carries {@code backlogEnabled}, the controller routes it to
 * {@link AgentService#setBacklogEnabled} (with the resolved tenant + org scope);
 * when the key is absent the setter is never called (patch semantics - flag unchanged).
 * This is the layer the UI toggle (CreateAgentModal) talks to.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentController - backlogEnabled REST wiring (V340)")
class AgentControllerBacklogTest {

    private static final UUID AGENT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String TENANT_ID = "tenant-1";
    private static final String ORG_ID = "44444444-4444-4444-8444-444444444444";

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
    @DisplayName("create with backlogEnabled=true routes to setBacklogEnabled(agentId, tenant, org, true)")
    void createForwardsBacklogEnabled() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        AgentEntity created = stubCreate();
        when(agentService.setBacklogEnabled(eq(AGENT_ID), eq(TENANT_ID), eq(ORG_ID), eq(true)))
            .thenReturn(created);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");
        body.put("backlogEnabled", true);

        var response = controller.createAgent(request, body);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(agentService).setBacklogEnabled(eq(AGENT_ID), eq(TENANT_ID), eq(ORG_ID), eq(true));
    }

    @Test
    @DisplayName("create without backlogEnabled → setBacklogEnabled never called (default false)")
    void createOmitsBacklogEnabled() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        stubCreate();

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");

        controller.createAgent(request, body);

        verify(agentService, never()).setBacklogEnabled(any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("update with backlogEnabled=false routes to setBacklogEnabled(agentId, tenant, callerOrg, false) - proves the toggle persists on edit")
    void updateForwardsBacklogEnabled() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        AgentEntity updated = stubUpdate();
        when(agentService.setBacklogEnabled(eq(AGENT_ID), eq(TENANT_ID), eq(ORG_ID), eq(false)))
            .thenReturn(updated);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");
        body.put("backlogEnabled", false);

        var response = controller.updateAgent(AGENT_ID, request, ORG_ID, body);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(agentService).setBacklogEnabled(eq(AGENT_ID), eq(TENANT_ID), eq(ORG_ID), eq(false));
    }

    @Test
    @DisplayName("update without backlogEnabled → setBacklogEnabled never called (flag unchanged)")
    void updateOmitsBacklogEnabled() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        stubUpdate();

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");

        controller.updateAgent(AGENT_ID, request, ORG_ID, body);

        verify(agentService, never()).setBacklogEnabled(any(), any(), any(), anyBoolean());
    }
}
