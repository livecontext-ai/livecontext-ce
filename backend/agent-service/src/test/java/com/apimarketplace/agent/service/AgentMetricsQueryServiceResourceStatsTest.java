package com.apimarketplace.agent.service;

import com.apimarketplace.agent.repository.AgentExecutionIterationRepository;
import com.apimarketplace.agent.repository.AgentExecutionMessageRepository;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentExecutionToolCallRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard for {@link AgentMetricsQueryService#getResourceStatsByAgent}.
 *
 * <p>Fixes the fleet bug where resource-family leaves (table/interface/workflow/
 * skill/application) were each stamped with the whole FAMILY aggregate, so every
 * table showed the same number and the leaf-summing rollup multiplied it by the
 * resource count (e.g. 31 interfaces × 36 = 1116). This method breaks the family
 * down by the specific resource id the agent targeted (read from the tool call's
 * {@code arguments} jsonb) so each leaf carries its OWN count.
 *
 * <p>The EntityManager is mocked (no live SQL), so these tests assert the Java
 * contract - scope binding, row mapping, null-id dropping - and the query SHAPE
 * (family filter + per-family jsonb extraction). The jsonb extraction itself is
 * proven against live Postgres by the e2e MCP verification.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentMetricsQueryService.getResourceStatsByAgent - per-resource breakdown")
class AgentMetricsQueryServiceResourceStatsTest {

    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final String TENANT_ID = "tenant-42";
    private static final String ORG_ID = "org-acme";

    @Mock private AgentExecutionRepository executionRepository;
    @Mock private AgentExecutionMessageRepository messageRepository;
    @Mock private AgentExecutionToolCallRepository toolCallRepository;
    @Mock private AgentExecutionIterationRepository iterationRepository;
    @Mock private EntityManager entityManager;
    @Mock private Query query;

    private AgentMetricsQueryService service;

    @BeforeEach
    void setUp() {
        service = new AgentMetricsQueryService(
            executionRepository, messageRepository, toolCallRepository,
            iterationRepository, entityManager);
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        lenient().when(query.setParameter(anyString(), any())).thenReturn(query);
    }

    private Object[] row(String toolName, String resourceId, long total, long success, long failure) {
        return new Object[]{ toolName, resourceId, BigInteger.valueOf(total), BigInteger.valueOf(success), BigInteger.valueOf(failure) };
    }

    @Test
    @DisplayName("maps each (tool_name, resource_id) row to its own counts and binds agent + org scope")
    void mapsPerResourceRowsAndBindsScope() {
        when(query.getResultList()).thenReturn(List.of(
            row("table", "123", 5, 5, 0),
            row("table", "456", 3, 2, 1),
            row("interface", "if-uuid-1", 7, 7, 0)
        ));

        List<Map<String, Object>> stats = service.getResourceStatsByAgent(TENANT_ID, ORG_ID, AGENT_ID);

        assertThat(stats).hasSize(3);
        assertThat(stats.get(0)).containsEntry("toolName", "table")
            .containsEntry("resourceId", "123")
            .containsEntry("totalCalls", 5L).containsEntry("successCount", 5L).containsEntry("failureCount", 0L);
        // Two tables get DISTINCT counts - the bug stamped both with the family total.
        assertThat(stats.get(1)).containsEntry("resourceId", "456")
            .containsEntry("successCount", 2L).containsEntry("failureCount", 1L);
        assertThat(stats.get(2)).containsEntry("toolName", "interface").containsEntry("resourceId", "if-uuid-1");

        verify(query).setParameter("agentId", AGENT_ID);
        verify(query).setParameter("orgId", ORG_ID);
    }

    @Test
    @DisplayName("drops rows whose resource id is null (action='list'/'create' targeted no resource)")
    void dropsNullResourceIdRows() {
        when(query.getResultList()).thenReturn(List.of(
            row("table", null, 4, 4, 0),     // e.g. action='list' - belongs to no leaf
            row("table", "789", 2, 2, 0)
        ));

        List<Map<String, Object>> stats = service.getResourceStatsByAgent(TENANT_ID, ORG_ID, AGENT_ID);

        assertThat(stats).hasSize(1);
        assertThat(stats.get(0)).containsEntry("resourceId", "789");
    }

    @Test
    @DisplayName("query restricts to the 5 resource-family tools, extracts each family's id arg, and groups by resource")
    void queryShapeFiltersFamiliesAndGroupsByResource() {
        when(query.getResultList()).thenReturn(List.of());

        service.getResourceStatsByAgent(TENANT_ID, ORG_ID, AGENT_ID);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        String q = sql.getValue();

        assertThat(q).contains("tc.tool_name IN ('table', 'interface', 'workflow', 'application', 'skill')");
        assertThat(q).contains("tc.arguments->>'table_id'").contains("tc.arguments->>'datasource_id'");
        assertThat(q).contains("tc.arguments->>'interface_id'");
        assertThat(q).contains("tc.arguments->>'workflow_id'");
        assertThat(q).contains("tc.arguments->>'application_id'");
        assertThat(q).contains("tc.arguments->>'skill_id'");
        // Regression: workflow/interface/table also accept a generic 'id' alias
        // (workflow(action='execute', id=...) is the most common workflow call) -
        // it MUST be COALESCEd in so those rows attribute to a leaf, not get dropped.
        assertThat(q).contains("COALESCE(tc.arguments->>'workflow_id', tc.arguments->>'id')");
        assertThat(q).contains("COALESCE(tc.arguments->>'interface_id', tc.arguments->>'id')");
        assertThat(q).contains("COALESCE(tc.arguments->>'table_id', tc.arguments->>'datasource_id', tc.arguments->>'id')");
        assertThat(q).contains("GROUP BY tc.tool_name, resource_id");
        // Org-strict scope predicate (post-V261) - never an unscoped cross-org read.
        assertThat(q).contains("ae.organization_id = :orgId");
    }

    @Test
    @DisplayName("empty result set → empty list, not null")
    void emptyResultYieldsEmptyList() {
        when(query.getResultList()).thenReturn(List.of());
        assertThat(service.getResourceStatsByAgent(TENANT_ID, ORG_ID, AGENT_ID)).isEmpty();
    }
}
