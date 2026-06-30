package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.AgentWebhookTokenEntity;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.AgentWidgetConfigService;
import com.apimarketplace.agent.webhook.AgentWebhookTokenService;
import com.apimarketplace.agent.util.RequestParameterExtractor;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit guard for {@link AgentController#getFleetTriggers} - the Agent Fleet batch
 * trigger lookup that replaces the per-agent getWebhook + getSchedule fan-out.
 * Verifies: webhook + schedule merge, only-active/only-enabled rows surface,
 * workflow schedules (null agentEntityId) and disabled/malformed rows are skipped,
 * and a trigger-service failure still returns the webhook rows.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentController.getFleetTriggers - fleet batch triggers")
class AgentControllerFleetTriggersTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String ORG_ID = "22222222-2222-4222-8222-222222222222";
    private static final String ORG_ROLE = "MEMBER";
    private static final String TRIGGER_URL = "http://trigger-service.test";

    private static final UUID AGENT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"); // webhook only
    private static final UUID AGENT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002"); // schedule only
    private static final UUID AGENT_C = UUID.fromString("cccccccc-0000-0000-0000-000000000003"); // both
    private static final UUID AGENT_D = UUID.fromString("dddddddd-0000-0000-0000-000000000004"); // disabled schedule

    @Mock private AgentService agentService;
    @Mock private AgentWebhookTokenService webhookTokenService;
    @Mock private AgentWidgetConfigService widgetConfigService;
    @Mock private TenantResolver tenantResolver;
    @Mock private HttpServletRequest request;

    private AgentController controller;
    private MockRestServiceServer triggerServer;

    @BeforeEach
    void setUp() {
        controller = new AgentController(
            agentService, webhookTokenService, widgetConfigService, tenantResolver,
            new RequestParameterExtractor(), "https://app.test", "http://widget.test", TRIGGER_URL);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(controller, "triggerRestTemplate");
        triggerServer = MockRestServiceServer.bindTo(restTemplate).build();

        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        when(tenantResolver.resolveOrgRole(request)).thenReturn(ORG_ROLE);
        lenient().when(webhookTokenService.getWebhookUrl(anyString(), anyString()))
            .thenAnswer(inv -> "https://wh/" + inv.getArgument(1));
    }

    private AgentWebhookTokenEntity webhook(UUID agentId, String token) {
        AgentWebhookTokenEntity wh = new AgentWebhookTokenEntity();
        wh.setAgentId(agentId);
        wh.setToken(token);
        wh.setIsActive(true);
        return wh;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rowFor(List<Map<String, Object>> rows, UUID agentId) {
        return rows.stream().filter(r -> agentId.toString().equals(r.get("agentId"))).findFirst().orElse(null);
    }

    @Test
    @DisplayName("merges active webhooks + enabled agent schedules; skips workflow/disabled/malformed schedules")
    void mergesWebhooksAndSchedules() {
        when(webhookTokenService.findActiveByOrganization(ORG_ID))
            .thenReturn(List.of(webhook(AGENT_A, "tok-a"), webhook(AGENT_C, "tok-c")));

        // by-tenant returns ALL workspace schedules (workflow + agent); the endpoint
        // must keep only enabled agent ones.
        String scheduleJson = "["
            + "{\"agentEntityId\":\"" + AGENT_B + "\",\"enabled\":true,\"cronExpression\":\"0 9 * * *\",\"timezone\":\"UTC\"},"
            + "{\"agentEntityId\":\"" + AGENT_C + "\",\"enabled\":true,\"cronExpression\":\"0 0 * * *\",\"timezone\":\"Europe/Paris\"},"
            + "{\"agentEntityId\":null,\"enabled\":true,\"cronExpression\":\"* * * * *\"},"          // workflow schedule → skip
            + "{\"agentEntityId\":\"" + AGENT_D + "\",\"enabled\":false,\"cronExpression\":\"0 1 * * *\"}," // disabled → skip
            + "{\"agentEntityId\":\"not-a-uuid\",\"enabled\":true}"                                    // malformed → skip
            + "]";
        triggerServer.expect(requestTo(TRIGGER_URL + "/api/internal/trigger/schedules/by-tenant/" + TENANT_ID))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(scheduleJson, MediaType.APPLICATION_JSON));

        ResponseEntity<List<Map<String, Object>>> resp = controller.getFleetTriggers(request);
        List<Map<String, Object>> rows = resp.getBody();

        // Only A (webhook), B (schedule), C (both) - D/null/malformed omitted.
        assertThat(rows).hasSize(3);

        Map<String, Object> a = rowFor(rows, AGENT_A);
        assertThat(a).containsEntry("hasWebhook", true).containsEntry("hasSchedule", false)
            .containsEntry("webhookUrl", "https://wh/tok-a");

        Map<String, Object> b = rowFor(rows, AGENT_B);
        assertThat(b).containsEntry("hasWebhook", false).containsEntry("hasSchedule", true)
            .containsEntry("cronExpression", "0 9 * * *").containsEntry("timezone", "UTC");

        Map<String, Object> c = rowFor(rows, AGENT_C);
        assertThat(c).containsEntry("hasWebhook", true).containsEntry("hasSchedule", true)
            .containsEntry("webhookUrl", "https://wh/tok-c").containsEntry("cronExpression", "0 0 * * *");

        assertThat(rowFor(rows, AGENT_D)).isNull();
        triggerServer.verify();
    }

    @Test
    @DisplayName("a trigger-service failure still returns the webhook rows (schedules degrade, not the whole fleet)")
    void scheduleFailureStillReturnsWebhooks() {
        when(webhookTokenService.findActiveByOrganization(ORG_ID))
            .thenReturn(List.of(webhook(AGENT_A, "tok-a")));
        triggerServer.expect(requestTo(TRIGGER_URL + "/api/internal/trigger/schedules/by-tenant/" + TENANT_ID))
            .andRespond(withServerError());

        ResponseEntity<List<Map<String, Object>>> resp = controller.getFleetTriggers(request);
        List<Map<String, Object>> rows = resp.getBody();

        assertThat(rows).hasSize(1);
        assertThat(rowFor(rows, AGENT_A)).containsEntry("hasWebhook", true).containsEntry("hasSchedule", false);
    }

    @Test
    @DisplayName("no webhooks and no schedules → empty list, never null")
    void noTriggersYieldsEmptyList() {
        when(webhookTokenService.findActiveByOrganization(ORG_ID)).thenReturn(List.of());
        triggerServer.expect(requestTo(TRIGGER_URL + "/api/internal/trigger/schedules/by-tenant/" + TENANT_ID))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        ResponseEntity<List<Map<String, Object>>> resp = controller.getFleetTriggers(request);
        assertThat(resp.getBody()).isEmpty();
    }
}
