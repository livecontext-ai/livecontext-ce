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
 * V350 - proves the REST create/update wiring of the per-agent compaction override:
 * when the body carries {@code compactionEnabled} / {@code compactionAfterTurns}, the
 * controller routes them to {@link AgentService#setCompactionOverrides} with the right
 * per-field "present" flags; absent keys leave the setter uncalled (patch semantics);
 * and an out-of-range cadence is rejected with 400 BEFORE any persistence. This is the
 * layer the agent-config UI (CreateAgentModal) and the agent CRUD tool talk to.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentController - compaction override REST wiring (V350)")
class AgentControllerCompactionTest {

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
    @DisplayName("create with compactionEnabled + compactionAfterTurns routes both (present flags true)")
    void createForwardsCompaction() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        AgentEntity created = stubCreate();
        when(agentService.setCompactionOverrides(eq(AGENT_ID), eq(TENANT_ID), eq(ORG_ID),
            eq(true), eq(true), eq(true), eq(8))).thenReturn(created);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");
        body.put("compactionEnabled", true);
        body.put("compactionAfterTurns", 8);

        var response = controller.createAgent(request, body);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(agentService).setCompactionOverrides(eq(AGENT_ID), eq(TENANT_ID), eq(ORG_ID),
            eq(true), eq(true), eq(true), eq(8));
    }

    @Test
    @DisplayName("create without compaction keys → setCompactionOverrides never called (inherit)")
    void createOmitsCompaction() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        stubCreate();

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");

        controller.createAgent(request, body);

        verify(agentService, never()).setCompactionOverrides(any(), any(), any(),
            anyBoolean(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("create with compactionAfterTurns < 1 → 400, agent never created")
    void createRejectsInvalidCadence() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");
        body.put("compactionAfterTurns", 0);

        var response = controller.createAgent(request, body);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(agentService, never()).createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(agentService, never()).setCompactionOverrides(any(), any(), any(),
            anyBoolean(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("update with only compactionEnabled=false routes (enabled present, cadence absent)")
    void updateForwardsCompactionEnableOnly() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        AgentEntity updated = stubUpdate();
        when(agentService.setCompactionOverrides(eq(AGENT_ID), eq(TENANT_ID), eq(ORG_ID),
            eq(true), eq(false), eq(false), isNull())).thenReturn(updated);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");
        body.put("compactionEnabled", false);

        var response = controller.updateAgent(AGENT_ID, request, ORG_ID, body);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(agentService).setCompactionOverrides(eq(AGENT_ID), eq(TENANT_ID), eq(ORG_ID),
            eq(true), eq(false), eq(false), isNull());
    }

    @Test
    @DisplayName("update without compaction keys → setCompactionOverrides never called (unchanged)")
    void updateOmitsCompaction() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        stubUpdate();

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");

        controller.updateAgent(AGENT_ID, request, ORG_ID, body);

        verify(agentService, never()).setCompactionOverrides(any(), any(), any(),
            anyBoolean(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("update with compactionAfterTurns < 1 → 400, agent never updated")
    void updateRejectsInvalidCadence() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");
        body.put("compactionAfterTurns", -1);

        var response = controller.updateAgent(AGENT_ID, request, ORG_ID, body);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(agentService, never()).updateAgent(any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            anyBoolean());
        verify(agentService, never()).setCompactionOverrides(any(), any(), any(),
            anyBoolean(), any(), anyBoolean(), any());
    }

    // -----------------------------------------------------------------
    // compaction SUMMARISER model pair (compactionModelProvider/Name)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("create with a full model pair routes to setCompactionModel with both values")
    void createForwardsCompactionModel() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        AgentEntity created = stubCreate();
        when(agentService.setCompactionModel(AGENT_ID, TENANT_ID, ORG_ID, "openai", "gpt-5-mini"))
            .thenReturn(created);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");
        body.put("compactionModelProvider", "openai");
        body.put("compactionModelName", "gpt-5-mini");

        var response = controller.createAgent(request, body);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(agentService).setCompactionModel(AGENT_ID, TENANT_ID, ORG_ID, "openai", "gpt-5-mini");
    }

    @Test
    @DisplayName("create with a partial model pair → 400 invalid_compaction_model, agent never created")
    void createRejectsPartialModelPair() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");
        body.put("compactionModelProvider", "openai"); // name missing → partial pair

        var response = controller.createAgent(request, body);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(((Map<?, ?>) response.getBody()).get("error")).isEqualTo("invalid_compaction_model");
        verify(agentService, never()).createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(agentService, never()).setCompactionModel(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("create without model keys → setCompactionModel never called (inherit)")
    void createOmitsCompactionModel() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        stubCreate();

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");

        controller.createAgent(request, body);

        verify(agentService, never()).setCompactionModel(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("create with a NUMERIC model value → 400 invalid_compaction_model, never toString-coerced into a model id (the 106735 corruption class)")
    void createRejectsNumericModelValue() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");
        body.put("compactionModelProvider", 42);
        body.put("compactionModelName", 106735);

        var response = controller.createAgent(request, body);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(((Map<?, ?>) response.getBody()).get("error")).isEqualTo("invalid_compaction_model");
        verify(agentService, never()).setCompactionModel(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("update with a full model pair routes to setCompactionModel with both values")
    void updateForwardsCompactionModel() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        AgentEntity updated = stubUpdate();
        when(agentService.setCompactionModel(AGENT_ID, TENANT_ID, ORG_ID, "google", "gemini-3-flash"))
            .thenReturn(updated);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");
        body.put("compactionModelProvider", "google");
        body.put("compactionModelName", "gemini-3-flash");

        var response = controller.updateAgent(AGENT_ID, request, ORG_ID, body);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(agentService).setCompactionModel(AGENT_ID, TENANT_ID, ORG_ID, "google", "gemini-3-flash");
    }

    @Test
    @DisplayName("update with a partial model pair → 400 invalid_compaction_model, agent never updated")
    void updateRejectsPartialModelPair() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");
        body.put("compactionModelName", "gpt-5-mini"); // provider missing → partial pair

        var response = controller.updateAgent(AGENT_ID, request, ORG_ID, body);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(((Map<?, ?>) response.getBody()).get("error")).isEqualTo("invalid_compaction_model");
        verify(agentService, never()).updateAgent(any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            anyBoolean());
        verify(agentService, never()).setCompactionModel(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("update without model keys → setCompactionModel never called (unchanged)")
    void updateOmitsCompactionModel() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        stubUpdate();

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Worker");

        controller.updateAgent(AGENT_ID, request, ORG_ID, body);

        verify(agentService, never()).setCompactionModel(any(), any(), any(), any(), any());
    }
}
