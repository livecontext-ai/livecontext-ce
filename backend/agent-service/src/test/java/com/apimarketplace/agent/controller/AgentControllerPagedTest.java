package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.AgentWidgetConfigService;
import com.apimarketplace.agent.util.RequestParameterExtractor;
import com.apimarketplace.agent.webhook.AgentWebhookTokenService;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full Spring-MVC test of {@code GET /api/agents/paged} via {@code standaloneSetup} (real
 * DispatcherServlet routing + {@code @RequestParam} binding + Jackson serialization of the page
 * envelope), with {@link AgentService} mocked. Proves the controller:
 *  - binds {@code sort} / {@code visibility} / {@code q} / {@code page} / {@code size} and threads
 *    them into {@link AgentService#listAgentsPaged} (defaulting page=0/size=25, sort/visibility null);
 *  - serializes the {@code { items, totalCount, page, size, publicationStatuses }} envelope, with the
 *    per-row publication badge inlined under {@code publicationStatuses} (the key this change added).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentController.listAgentsPaged - server-paged envelope over MVC")
class AgentControllerPagedTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String ORG_ID = "22222222-2222-4222-8222-222222222222";
    private static final String ORG_ROLE = "MEMBER";

    @Mock private AgentService agentService;
    @Mock private AgentWebhookTokenService webhookTokenService;
    @Mock private AgentWidgetConfigService widgetConfigService;
    @Mock private TenantResolver tenantResolver;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AgentController controller = new AgentController(
                agentService, webhookTokenService, widgetConfigService, tenantResolver,
                new RequestParameterExtractor(), "https://app.test", "http://widget.test", "http://trigger.test");
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        when(tenantResolver.resolveOrNull(any())).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(any())).thenReturn(ORG_ID);
        when(tenantResolver.resolveOrgRole(any())).thenReturn(ORG_ROLE);
    }

    private AgentEntity agentRow(UUID id, String name) {
        AgentEntity e = new AgentEntity();
        e.setId(id);
        e.setTenantId(TENANT_ID);
        e.setName(name);
        return e;
    }

    @Test
    @DisplayName("threads sort/visibility/q/page/size into the service and serializes the inlined publicationStatuses")
    void threadsParamsAndSerializesEnvelope() throws Exception {
        UUID sharedId = UUID.randomUUID();
        Map<String, Map<String, String>> statuses = new LinkedHashMap<>();
        statuses.put(sharedId.toString(), Map.of("status", "ACTIVE"));
        when(agentService.listAgentsPaged(
                eq(TENANT_ID), eq(ORG_ID), eq(ORG_ROLE), eq("bot"), eq(2), eq(10), eq("name"), eq("public")))
                .thenReturn(new AgentService.AgentPage(
                        List.of(agentRow(sharedId, "Shared Bot")), 1, 2, 10, statuses));

        mockMvc.perform(get("/api/agents/paged")
                        .header("X-User-ID", TENANT_ID)
                        .param("q", "bot").param("page", "2").param("size", "10")
                        .param("sort", "name").param("visibility", "public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Shared Bot"))
                // The badge the client reads to paint the Globe, inlined on the envelope.
                .andExpect(jsonPath("$.publicationStatuses." + sharedId + ".status").value("ACTIVE"));

        // The bound params reached the service verbatim (no silent drop / default override).
        verify(agentService).listAgentsPaged(
                eq(TENANT_ID), eq(ORG_ID), eq(ORG_ROLE), eq("bot"), eq(2), eq(10), eq("name"), eq("public"));
    }

    @Test
    @DisplayName("defaults page=0/size=25 and leaves sort/visibility null when those params are absent")
    void appliesDefaultsWhenParamsAbsent() throws Exception {
        when(agentService.listAgentsPaged(
                eq(TENANT_ID), eq(ORG_ID), eq(ORG_ROLE), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new AgentService.AgentPage(List.of(), 0, 0, 25, Map.of()));

        mockMvc.perform(get("/api/agents/paged").header("X-User-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.publicationStatuses").isMap());

        // page/size fall back to their @RequestParam defaults; sort/visibility bind to null (server
        // applies its own lastModified/all defaults downstream).
        verify(agentService).listAgentsPaged(
                eq(TENANT_ID), eq(ORG_ID), eq(ORG_ROLE), eq(null), eq(0), eq(25), eq(null), eq(null));
    }
}
