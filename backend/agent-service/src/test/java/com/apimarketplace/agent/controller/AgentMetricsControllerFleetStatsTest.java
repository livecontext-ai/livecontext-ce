package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.service.AgentMetricsQueryService;
import com.apimarketplace.agent.service.FleetStatsService;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@code GET /agents/stats} (fleet batch stats). Assembly + cache +
 * parallelism now live in {@link FleetStatsService} (unit-tested in
 * {@code FleetStatsServiceTest}); the controller's remaining job is to resolve + validate
 * the workspace scope and DELEGATE. These tests pin exactly that: the resolved tenant/org
 * are threaded into the service, the service's payload is returned verbatim, and the
 * controller does NOT fan out to {@link AgentMetricsQueryService} itself anymore (which
 * would re-introduce the sequential-scan path this change removed).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentMetricsController.getFleetStats")
class AgentMetricsControllerFleetStatsTest {

    private static final String TENANT_ID = "tenant-42";
    private static final String ORG_ID = "org-acme";

    @Mock private AgentMetricsQueryService metricsQueryService;
    @Mock private AgentRepository agentRepository;
    @Mock private TenantResolver tenantResolver;
    @Mock private FleetStatsService fleetStatsService;
    @Mock private HttpServletRequest httpRequest;

    private AgentMetricsController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentMetricsController(metricsQueryService, agentRepository, tenantResolver, fleetStatsService);
    }

    @Test
    @DisplayName("validates the tenant, threads the resolved tenant + org into the service, and returns its payload verbatim")
    void delegatesToFleetStatsServiceScopedToTenantAndOrg() {
        when(tenantResolver.resolve(httpRequest)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(ORG_ID);

        Map<String, Object> payload = Map.of(
            "toolStats", List.of(Map.of("agentId", "a", "toolName", "web_search")),
            "resourceStats", List.of(),
            "subAgentStats", List.of(),
            "modelStats", List.of(Map.of("agentId", "a", "model", "deepseek-chat")));
        when(fleetStatsService.getFleetStats(TENANT_ID, ORG_ID)).thenReturn(payload);

        ResponseEntity<Map<String, Object>> response = controller.getFleetStats(httpRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // Returned verbatim - the controller does not re-wrap or re-key the service payload.
        assertThat(response.getBody()).isSameAs(payload);

        verify(tenantResolver).validate(TENANT_ID);
        verify(fleetStatsService).getFleetStats(TENANT_ID, ORG_ID);
        // The controller must NOT fan out to the query service directly - that path moved
        // into FleetStatsService (cache-aside + parallel). Re-introducing it here would
        // resurrect the sequential full-history scan on the request thread.
        verifyNoInteractions(metricsQueryService);
    }
}
