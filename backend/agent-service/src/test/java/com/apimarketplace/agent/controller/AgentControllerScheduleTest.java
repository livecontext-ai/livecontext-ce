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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentController schedule contract")
class AgentControllerScheduleTest {

    private static final UUID AGENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String TENANT_ID = "tenant-1";
    private static final String ORG_ID = "22222222-2222-4222-8222-222222222222";
    private static final String ORG_ROLE = "MEMBER";
    private static final String TRIGGER_URL = "http://trigger-service.test";

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
            agentService,
            webhookTokenService,
            widgetConfigService,
            tenantResolver,
            new RequestParameterExtractor(),
            "",
            "http://widget.test",
            TRIGGER_URL
        );
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(controller, "triggerRestTemplate");
        triggerServer = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Test
    @DisplayName("DELETE /{id}/schedule forwards tenant and organization scope to trigger-service")
    void deleteScheduleForwardsScopeHeaders() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(agentService.getAgent(AGENT_ID, TENANT_ID, ORG_ID, ORG_ROLE)).thenReturn(Optional.of(agent()));

        triggerServer.expect(requestTo(TRIGGER_URL + "/api/internal/trigger/schedules/by-agent/" + AGENT_ID))
            .andExpect(method(HttpMethod.DELETE))
            .andExpect(header("X-User-ID", TENANT_ID))
            .andExpect(header("X-Organization-ID", ORG_ID))
            .andExpect(header("X-Organization-Role", ORG_ROLE))
            .andRespond(withNoContent());

        ResponseEntity<Void> response = controller.deleteSchedule(AGENT_ID, request, ORG_ID, ORG_ROLE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        triggerServer.verify();
    }

    @Test
    @DisplayName("DELETE /{id}/schedule returns 404 and does not call trigger when org access is denied")
    void deleteScheduleReturnsNotFoundWhenOrgAccessDenied() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(agentService.getAgent(AGENT_ID, TENANT_ID, ORG_ID, ORG_ROLE)).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.deleteSchedule(AGENT_ID, request, ORG_ID, ORG_ROLE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        triggerServer.verify();
    }

    @Test
    @DisplayName("GET /{id} returns 404 when org access is denied")
    void getAgentReturnsNotFoundWhenOrgAccessDenied() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(agentService.getAgent(AGENT_ID, TENANT_ID, ORG_ID, ORG_ROLE)).thenReturn(Optional.empty());

        ResponseEntity<AgentEntity> response = controller.getAgent(AGENT_ID, request, null, ORG_ID, ORG_ROLE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /{id}/schedule forwards organization scope in headers and body")
    void createScheduleForwardsOrganizationScope() {
        when(tenantResolver.resolveOrNull(eq(request))).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        when(tenantResolver.resolveOrgRole(request)).thenReturn(ORG_ROLE);
        when(agentService.getAgent(AGENT_ID, TENANT_ID, ORG_ID, ORG_ROLE)).thenReturn(Optional.of(agent()));

        triggerServer.expect(requestTo(TRIGGER_URL + "/api/internal/trigger/schedules/agent"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-User-ID", TENANT_ID))
            .andExpect(header("X-Organization-ID", ORG_ID))
            .andExpect(header("X-Organization-Role", ORG_ROLE))
            .andExpect(jsonPath("$.tenantId").value(TENANT_ID))
            .andExpect(jsonPath("$.organizationId").value(ORG_ID))
            .andExpect(jsonPath("$.cronExpression").value("0 9 * * *"))
            .andRespond(withSuccess("{\"id\":\"schedule-1\"}", org.springframework.http.MediaType.APPLICATION_JSON));

        ResponseEntity<?> response = controller.createOrUpdateSchedule(AGENT_ID, request, Map.of(
            "cron", "0 9 * * *",
            "timezone", "Europe/Paris"
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().toString()).contains("schedule-1");
        triggerServer.verify();
    }

    @Test
    @DisplayName("POST /{id}/schedule accepts snapshot cronExpression and preserves disabled state")
    void createScheduleAcceptsSnapshotCronExpressionAndDisabledState() {
        when(tenantResolver.resolveOrNull(eq(request))).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        when(tenantResolver.resolveOrgRole(request)).thenReturn(ORG_ROLE);
        when(agentService.getAgent(AGENT_ID, TENANT_ID, ORG_ID, ORG_ROLE)).thenReturn(Optional.of(agent()));

        triggerServer.expect(requestTo(TRIGGER_URL + "/api/internal/trigger/schedules/agent"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Organization-ID", ORG_ID))
            .andExpect(jsonPath("$.cronExpression").value("0 10 * * *"))
            .andExpect(jsonPath("$.enabled").value(false))
            .andRespond(withSuccess("{\"id\":\"schedule-2\",\"enabled\":false}",
                org.springframework.http.MediaType.APPLICATION_JSON));

        ResponseEntity<?> response = controller.createOrUpdateSchedule(AGENT_ID, request, Map.of(
            "cronExpression", "0 10 * * *",
            "enabled", false
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().toString()).contains("schedule-2");
        triggerServer.verify();
    }

    @Test
    @DisplayName("POST /{id}/schedule with cron only does NOT forward schedulePrompt/withMemory (prevents the 2026-06-14 clobber)")
    void createScheduleCronOnly_omitsPromptAndMemory() {
        when(tenantResolver.resolveOrNull(eq(request))).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        when(tenantResolver.resolveOrgRole(request)).thenReturn(ORG_ROLE);
        when(agentService.getAgent(AGENT_ID, TENANT_ID, ORG_ID, ORG_ROLE)).thenReturn(Optional.of(agent()));

        triggerServer.expect(requestTo(TRIGGER_URL + "/api/internal/trigger/schedules/agent"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.cronExpression").value("0 * * * *"))
            // Assert on the RAW body: the keys must be ABSENT, not merely JSON-null.
            // Spring's jsonPath().doesNotExist() treats an explicit null as "absent", so a
            // regression that re-introduces schedulePrompt:null would slip past it. The
            // serialized body must not mention the keys at all.
            .andExpect(content().string(not(containsString("schedulePrompt"))))
            .andExpect(content().string(not(containsString("withMemory"))))
            .andRespond(withSuccess("{\"id\":\"schedule-3\"}", org.springframework.http.MediaType.APPLICATION_JSON));

        // A cadence-only edit: the form sends just the cron. The controller must NOT
        // synthesize schedulePrompt=null / withMemory=false, which trigger-service would
        // otherwise persist and wipe the stored prompt/memory.
        ResponseEntity<?> response = controller.createOrUpdateSchedule(AGENT_ID, request, Map.of(
            "cron", "0 * * * *"
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        triggerServer.verify();
    }

    @Test
    @DisplayName("POST /{id}/schedule forwards schedulePrompt and withMemory when the caller supplies them")
    void createScheduleWithPromptAndMemory_forwardsThem() {
        when(tenantResolver.resolveOrNull(eq(request))).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        when(tenantResolver.resolveOrgRole(request)).thenReturn(ORG_ROLE);
        when(agentService.getAgent(AGENT_ID, TENANT_ID, ORG_ID, ORG_ROLE)).thenReturn(Optional.of(agent()));

        triggerServer.expect(requestTo(TRIGGER_URL + "/api/internal/trigger/schedules/agent"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.schedulePrompt").value("Build one app"))
            .andExpect(jsonPath("$.withMemory").value(true))
            .andRespond(withSuccess("{\"id\":\"schedule-4\"}", org.springframework.http.MediaType.APPLICATION_JSON));

        ResponseEntity<?> response = controller.createOrUpdateSchedule(AGENT_ID, request, Map.of(
            "cron", "0 * * * *",
            "schedulePrompt", "Build one app",
            "withMemory", true
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        triggerServer.verify();
    }

    @Test
    @DisplayName("GET /{id}/schedule forwards tenant and organization scope to trigger-service")
    void getScheduleForwardsScopeHeaders() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        when(tenantResolver.resolveOrgRole(request)).thenReturn(ORG_ROLE);
        when(agentService.getAgent(AGENT_ID, TENANT_ID, ORG_ID, ORG_ROLE)).thenReturn(Optional.of(agent()));

        triggerServer.expect(requestTo(TRIGGER_URL + "/api/internal/trigger/schedules/by-agent/" + AGENT_ID
                + "?tenantId=" + TENANT_ID))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("X-User-ID", TENANT_ID))
            .andExpect(header("X-Organization-ID", ORG_ID))
            .andExpect(header("X-Organization-Role", ORG_ROLE))
            .andRespond(withSuccess("{\"id\":\"schedule-1\"}", org.springframework.http.MediaType.APPLICATION_JSON));

        ResponseEntity<?> response = controller.getSchedule(AGENT_ID, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().toString()).contains("schedule-1");
        triggerServer.verify();
    }

    @Test
    @DisplayName("GET /{id}/schedule returns 404 and does not call trigger when org access is denied")
    void getScheduleReturnsNotFoundWhenOrgAccessDenied() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        when(tenantResolver.resolveOrgRole(request)).thenReturn(ORG_ROLE);
        when(agentService.getAgent(AGENT_ID, TENANT_ID, ORG_ID, ORG_ROLE)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getSchedule(AGENT_ID, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        triggerServer.verify();
    }

    @Test
    @DisplayName("PATCH /{id}/schedule forwards tenant and organization scope to trigger-service")
    void toggleScheduleForwardsScopeHeaders() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        when(tenantResolver.resolveOrgRole(request)).thenReturn(ORG_ROLE);
        when(agentService.getAgent(AGENT_ID, TENANT_ID, ORG_ID, ORG_ROLE)).thenReturn(Optional.of(agent()));

        triggerServer.expect(requestTo(TRIGGER_URL + "/api/internal/trigger/schedules/by-agent/" + AGENT_ID
                + "/toggle?tenantId=" + TENANT_ID + "&enabled=false"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(header("X-User-ID", TENANT_ID))
            .andExpect(header("X-Organization-ID", ORG_ID))
            .andExpect(header("X-Organization-Role", ORG_ROLE))
            .andRespond(withSuccess("{\"enabled\":false}", org.springframework.http.MediaType.APPLICATION_JSON));

        ResponseEntity<?> response = controller.toggleSchedule(AGENT_ID, request, Map.of("enabled", false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().toString()).contains("enabled=false");
        triggerServer.verify();
    }

    @Test
    @DisplayName("PATCH /{id}/schedule returns 404 and does not call trigger when org access is denied")
    void toggleScheduleReturnsNotFoundWhenOrgAccessDenied() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        when(tenantResolver.resolveOrgRole(request)).thenReturn(ORG_ROLE);
        when(agentService.getAgent(AGENT_ID, TENANT_ID, ORG_ID, ORG_ROLE)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.toggleSchedule(AGENT_ID, request, Map.of("enabled", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        triggerServer.verify();
    }

    @Test
    @DisplayName("POST /{id}/widget resolves agent in active organization scope")
    void createWidgetResolvesAgentInActiveOrganizationScope() {
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
        when(tenantResolver.resolveOrgRole(request)).thenReturn(ORG_ROLE);
        when(agentService.getAgent(AGENT_ID, TENANT_ID, ORG_ID, ORG_ROLE)).thenReturn(Optional.of(agent()));
        when(widgetConfigService.isValidPosition("bottom-right")).thenReturn(true);
        when(widgetConfigService.isValidTheme("light")).thenReturn(true);
        when(widgetConfigService.isValidColor("#3366ff")).thenReturn(true);
        AgentWidgetConfigEntity widget = new AgentWidgetConfigEntity(AGENT_ID);
        widget.setWidgetToken("widget-token");
        widget.setPosition("bottom-right");
        widget.setTheme("light");
        widget.setPrimaryColor("#3366ff");
        widget.setShowAvatar(true);
        widget.setIsActive(true);
        when(widgetConfigService.createOrUpdateWidgetConfig(
                eq(AGENT_ID), eq("bottom-right"), eq("light"), eq("#3366ff"),
                eq(null), eq(null), eq(true), eq(null), eq(null)))
                .thenReturn(widget);
        when(widgetConfigService.getWidgetScriptUrl("http://widget.test"))
                .thenReturn("http://widget.test/widget.js");

        ResponseEntity<Map<String, Object>> response = controller.createOrUpdateWidgetConfig(
                AGENT_ID,
                request,
                Map.of("position", "bottom-right", "theme", "light", "primaryColor", "#3366ff", "showAvatar", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(agentService).getAgent(AGENT_ID, TENANT_ID, ORG_ID, ORG_ROLE);
    }

    private static AgentEntity agent() {
        AgentEntity agent = new AgentEntity();
        agent.setId(AGENT_ID);
        agent.setTenantId(TENANT_ID);
        agent.setOrganizationId(ORG_ID);
        agent.setName("Schedule Test Agent");
        return agent;
    }
}
