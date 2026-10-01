package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.config.AgentNameConflictExceptionHandler;
import com.apimarketplace.agent.config.GlobalExceptionHandler;
import com.apimarketplace.agent.service.AgentNameConflictException;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The name conflict through the REAL AgentController and the real advices, as a browser reaches
 * them: POST /api/agents and PUT /api/agents/{id}. Pins that the controller is covered by the
 * conflict advice (it carries the marker) and that an update which loses the race at the index
 * gets the RENAME answer, with the agent's own current name counted as free.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentController - agent name conflict over HTTP")
class AgentControllerNameConflictTest {

    private static final UUID AGENT_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID EXISTING = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final String ORG = "99999999-9999-4999-8999-999999999999";

    @Mock private AgentService agentService;
    @Mock private AgentWebhookTokenService webhookTokenService;
    @Mock private AgentWidgetConfigService widgetConfigService;
    @Mock private TenantResolver tenantResolver;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AgentController controller = new AgentController(
            agentService, webhookTokenService, widgetConfigService, tenantResolver,
            new RequestParameterExtractor(), "", "http://widget.test", "http://trigger.test");
        AgentNameConflictExceptionHandler advice = new AgentNameConflictExceptionHandler();
        ReflectionTestUtils.setField(advice, "agentService", agentService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler(), advice)
            .build();
        lenient().when(tenantResolver.resolveOrNull(any(HttpServletRequest.class))).thenReturn("121");
        lenient().when(tenantResolver.resolveOrgId(any(HttpServletRequest.class))).thenReturn(ORG);
    }

    @Test
    @DisplayName("POST a taken name -> 409 AGENT_NAME_CONFLICT with suggestedName and existingAgentId")
    void createConflictIs409() throws Exception {
        when(agentService.createAgent(any(), eq("Nova"), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new AgentNameConflictException("Nova", EXISTING, "Nova (2)"));

        mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Nova\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("AGENT_NAME_CONFLICT"))
            .andExpect(jsonPath("$.suggestedName").value("Nova (2)"))
            .andExpect(jsonPath("$.existingAgentId").value(EXISTING.toString()));
    }

    @Test
    @DisplayName("regression: PUT that loses the race at the index -> 409 with the RENAME advice, own name counted as free")
    void updateLostRaceGetsRenameAdvice() throws Exception {
        when(agentService.updateAgent(eq(AGENT_ID), any(), eq("Nova"), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), anyBoolean()))
            .thenThrow(new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("ERROR: duplicate key value violates unique constraint "
                    + "\"uq_agents_org_name_active\"\n  Detail: Key (organization_id, name)=("
                    + ORG + ", Nova) already exists.")));
        when(agentService.allocateAgentNameForRename(ORG, "Nova", AGENT_ID)).thenReturn("Nova (2)");

        mockMvc.perform(put("/api/agents/" + AGENT_ID).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Nova\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("AGENT_NAME_CONFLICT"))
            .andExpect(jsonPath("$.suggestedName").value("Nova (2)"))
            .andExpect(jsonPath("$.message").value(containsString("Retry with name='Nova (2)'")))
            .andExpect(jsonPath("$.message").value(not(containsString("agent(action='list')"))));
    }
}
