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

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@code GET /agents/metrics/fleet-summary}. The fleet-summary
 * e2e (CE-AGENT-OBS-032) asserts only {@code toBeGreaterThanOrEqual} on a few
 * keys, so the controller's Java-side computation is pinned NOWHERE: the
 * Spring-Data single-row unwrap branch, the INTEGER-division {@code avgDurationMs}
 * (which silently truncates), the {@code successRate} rounding, the
 * {@code row.length > 7} credits guard, and the 12-key fold of
 * {@code getFleetExtraCounts}. These tests lock all of that down.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentMetricsController.getFleetSummary")
class AgentMetricsControllerFleetSummaryTest {

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
        when(tenantResolver.resolve(httpRequest)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(ORG_ID);
    }

    @Test
    @DisplayName("folds a flat counter row + extra counts into all 13 keys, truncating avgDurationMs and rounding successRate")
    void foldsFlatRowIntoTwelveKeys() {
        // [agents, executions, tokens, toolCalls, duration, success, failure, credits]
        when(agentRepository.getFleetCountersByOrganizationIdStrict(ORG_ID))
            .thenReturn(new Object[]{ 3L, 12L, 5000L, 80L, 7000L, 10L, 2L, new BigDecimal("2.5") });
        // The cache-read rollup is sourced from agent_executions (the agents table
        // has no cached-token counter), so it travels in the extra-counts map.
        when(metricsQueryService.getFleetExtraCounts(TENANT_ID, ORG_ID))
            .thenReturn(Map.of("cancelledCount", 1L, "loopDetectedCount", 0L, "totalCachedTokens", 1500L));

        Map<String, Object> body = controller.getFleetSummary(httpRequest).getBody();

        assertThat(body).containsOnlyKeys(
            "totalAgents", "totalExecutions", "totalTokensUsed", "totalToolCalls", "totalDurationMs",
            "successCount", "failureCount", "totalCreditsConsumed", "totalCachedTokens",
            "cancelledCount", "loopDetectedCount",
            "avgDurationMs", "successRate");
        assertThat(body)
            .containsEntry("totalAgents", 3L).containsEntry("totalExecutions", 12L)
            .containsEntry("totalTokensUsed", 5000L).containsEntry("totalToolCalls", 80L)
            .containsEntry("totalDurationMs", 7000L).containsEntry("successCount", 10L)
            .containsEntry("failureCount", 2L).containsEntry("totalCreditsConsumed", 2.5)
            .containsEntry("totalCachedTokens", 1500L)
            .containsEntry("cancelledCount", 1L).containsEntry("loopDetectedCount", 0L)
            // 7000 / 12 = 583 (INTEGER division - truncates 583.33, NOT 583.3/583.5)
            .containsEntry("avgDurationMs", 583L)
            // round(10 * 1000 / 12) / 10 = 833 / 10 = 83.3
            .containsEntry("successRate", 83.3);
    }

    @Test
    @DisplayName("defaults totalCachedTokens to 0 when the extra-counts map omits it")
    void defaultsCachedTokensToZeroWhenMissing() {
        when(agentRepository.getFleetCountersByOrganizationIdStrict(ORG_ID))
            .thenReturn(new Object[]{ 3L, 12L, 5000L, 80L, 7000L, 10L, 2L, new BigDecimal("2.5") });
        when(metricsQueryService.getFleetExtraCounts(TENANT_ID, ORG_ID))
            .thenReturn(Map.of("cancelledCount", 1L, "loopDetectedCount", 0L));

        Map<String, Object> body = controller.getFleetSummary(httpRequest).getBody();

        assertThat(body).containsEntry("totalCachedTokens", 0L);
    }

    @Test
    @DisplayName("unwraps a Spring-Data-wrapped single-row result (Object[] containing the row)")
    void unwrapsWrappedRow() {
        when(agentRepository.getFleetCountersByOrganizationIdStrict(ORG_ID))
            .thenReturn(new Object[]{ new Object[]{ 3L, 12L, 5000L, 80L, 7000L, 10L, 2L, new BigDecimal("2.5") } });
        when(metricsQueryService.getFleetExtraCounts(TENANT_ID, ORG_ID)).thenReturn(Map.of());

        Map<String, Object> body = controller.getFleetSummary(httpRequest).getBody();

        // The nested Object[] must be unwrapped, not read as the row itself.
        assertThat(body).containsEntry("totalAgents", 3L).containsEntry("totalExecutions", 12L)
            .containsEntry("avgDurationMs", 583L);
    }

    @Test
    @DisplayName("zero executions yield zero avgDurationMs and successRate (no divide-by-zero); missing extra counts default to 0")
    void zeroExecutionsYieldZeroDerivedMetrics() {
        when(agentRepository.getFleetCountersByOrganizationIdStrict(ORG_ID))
            .thenReturn(new Object[]{ 0L, 0L, 0L, 0L, 0L, 0L, 0L, BigDecimal.ZERO });
        when(metricsQueryService.getFleetExtraCounts(TENANT_ID, ORG_ID)).thenReturn(Map.of());

        Map<String, Object> body = controller.getFleetSummary(httpRequest).getBody();

        assertThat(body).containsEntry("avgDurationMs", 0L).containsEntry("successRate", 0.0)
            .containsEntry("cancelledCount", 0L).containsEntry("loopDetectedCount", 0L);
    }

    @Test
    @DisplayName("a row without the credits column defaults totalCreditsConsumed to 0.0 (length-7 guard)")
    void missingCreditsColumnDefaultsToZero() {
        // A defensive 7-column row (no credits) - exercises the `row.length > 7` guard.
        when(agentRepository.getFleetCountersByOrganizationIdStrict(ORG_ID))
            .thenReturn(new Object[]{ 3L, 12L, 5000L, 80L, 7000L, 10L, 2L });
        lenient().when(metricsQueryService.getFleetExtraCounts(TENANT_ID, ORG_ID)).thenReturn(Map.of());

        Map<String, Object> body = controller.getFleetSummary(httpRequest).getBody();

        assertThat(body).containsEntry("totalCreditsConsumed", 0.0).containsEntry("totalExecutions", 12L);
    }
}
