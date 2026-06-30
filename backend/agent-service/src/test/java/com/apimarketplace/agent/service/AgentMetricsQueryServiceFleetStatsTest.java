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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the Agent Fleet BATCH stats methods
 * ({@code getAll*ByAgent}). These collapse the per-agent {@code *ByAgent} fan-out
 * (one request per agent) into one scope-wide query each, grouped by
 * {@code agent_entity_id} so every returned row carries {@code agentId}.
 *
 * <p>The EntityManager is mocked (no live SQL), so these tests assert the Java
 * contract - row→map mapping incl. the new {@code agentId} dimension, org-scope
 * binding, and the absence of a per-agent filter - plus the query SHAPE
 * ({@code GROUP BY ae.agent_entity_id ...}). The aggregation itself is proven
 * against live Postgres by the e2e MCP verification.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentMetricsQueryService - fleet batch (all-agents) stats")
class AgentMetricsQueryServiceFleetStatsTest {

    private static final String TENANT_ID = "tenant-42";
    private static final String ORG_ID = "org-acme";
    private static final String AGENT_A = "00000000-0000-0000-0000-0000000000a1";
    private static final String AGENT_B = "00000000-0000-0000-0000-0000000000b2";

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

    private String capturedSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        return sql.getValue();
    }

    @Test
    @DisplayName("tool stats: maps each (agentId, tool) row, groups by agent, org-scoped, no per-agent filter")
    void allToolStatsMapsAgentIdAndShape() {
        when(query.getResultList()).thenReturn(List.<Object[]>of(
            new Object[]{ AGENT_A, "web_search", BigInteger.valueOf(5), BigInteger.valueOf(5), BigInteger.ZERO,
                          new BigDecimal("100.00"), BigInteger.valueOf(12), BigInteger.valueOf(20), "2026-06-08T10:00:00Z", BigInteger.ZERO },
            new Object[]{ AGENT_B, "table", BigInteger.valueOf(3), BigInteger.valueOf(2), BigInteger.ONE,
                          new BigDecimal("66.67"), BigInteger.valueOf(8), BigInteger.valueOf(15), "2026-06-08T11:00:00Z", BigInteger.ONE }
        ));

        List<Map<String, Object>> stats = service.getAllToolStatsByAgent(TENANT_ID, ORG_ID);

        assertThat(stats).hasSize(2);
        assertThat(stats.get(0)).containsEntry("agentId", AGENT_A).containsEntry("toolName", "web_search")
            .containsEntry("totalCalls", 5L).containsEntry("successCount", 5L).containsEntry("failureCount", 0L)
            .containsEntry("successRatePct", 100.0).containsEntry("repeatCallCount", 0L);
        assertThat(stats.get(1)).containsEntry("agentId", AGENT_B).containsEntry("toolName", "table")
            .containsEntry("failureCount", 1L).containsEntry("repeatCallCount", 1L);

        String q = capturedSql();
        assertThat(q).contains("FROM agent_tool_call_stats_by_agent_org_live");
        assertThat(q).contains("WHERE organization_id = :orgId");
        assertThat(q).contains("GROUP BY agent_entity_id, tool_name");
        assertThat(q).doesNotContain("agent_execution_tool_calls");
        // Fleet-wide: must NOT bind a single agent id.
        verify(query, never()).setParameter(eq("agentId"), any());
        verify(query).setParameter("orgId", ORG_ID);
    }

    @Test
    @DisplayName("resource stats: carries agentId, drops null resource ids, groups by agent+tool+resource")
    void allResourceStatsMapsAgentIdAndDropsNull() {
        when(query.getResultList()).thenReturn(List.<Object[]>of(
            new Object[]{ AGENT_A, "table", "123", BigInteger.valueOf(5), BigInteger.valueOf(5), BigInteger.ZERO },
            new Object[]{ AGENT_A, "table", null,  BigInteger.valueOf(9), BigInteger.valueOf(9), BigInteger.ZERO }, // action='list' → dropped
            new Object[]{ AGENT_B, "interface", "if-1", BigInteger.valueOf(7), BigInteger.valueOf(6), BigInteger.ONE }
        ));

        List<Map<String, Object>> stats = service.getAllResourceStatsByAgent(TENANT_ID, ORG_ID);

        assertThat(stats).hasSize(2); // null-resource row dropped
        assertThat(stats.get(0)).containsEntry("agentId", AGENT_A).containsEntry("toolName", "table")
            .containsEntry("resourceId", "123").containsEntry("totalCalls", 5L);
        assertThat(stats.get(1)).containsEntry("agentId", AGENT_B).containsEntry("resourceId", "if-1")
            .containsEntry("failureCount", 1L);

        String q = capturedSql();
        assertThat(q).contains("FROM agent_resource_call_stats_by_agent_org_live");
        assertThat(q).contains("WHERE organization_id = :orgId");
        assertThat(q).contains("GROUP BY agent_entity_id, tool_name, resource_id");
        assertThat(q).doesNotContain("agent_execution_tool_calls");
        verify(query, never()).setParameter(eq("agentId"), any());
    }

    @Test
    @DisplayName("sub-agent stats: keyed by caller (agentId) → callee, groups by caller+callee")
    void allSubAgentStatsMapsCallerAndCallee() {
        when(query.getResultList()).thenReturn(List.<Object[]>of(
            new Object[]{ AGENT_A, AGENT_B, BigInteger.valueOf(4), BigInteger.valueOf(3), BigInteger.ONE }
        ));

        List<Map<String, Object>> stats = service.getAllSubAgentCallStats(TENANT_ID, ORG_ID);

        assertThat(stats).hasSize(1);
        assertThat(stats.get(0)).containsEntry("agentId", AGENT_A).containsEntry("calleeAgentId", AGENT_B)
            .containsEntry("totalCalls", 4L).containsEntry("successCount", 3L).containsEntry("failureCount", 1L);

        String q = capturedSql();
        assertThat(q).contains("FROM agent_sub_agent_call_stats_org_live");
        assertThat(q).contains("WHERE organization_id = :orgId");
        assertThat(q).contains("GROUP BY caller_agent_id, callee_agent_id");
        assertThat(q).doesNotContain("agent_executions");
        verify(query, never()).setParameter(eq("callerId"), any());
    }

    @Test
    @DisplayName("model stats: carries agentId + budgetExhausted subset, groups by agent+model")
    void allModelStatsMapsAgentIdAndBudget() {
        when(query.getResultList()).thenReturn(List.<Object[]>of(
            new Object[]{ AGENT_A, "deepseek-chat", BigInteger.valueOf(10), BigInteger.valueOf(7), BigInteger.valueOf(3), BigInteger.ONE }
        ));

        List<Map<String, Object>> stats = service.getAllModelStatsByAgent(TENANT_ID, ORG_ID);

        assertThat(stats).hasSize(1);
        assertThat(stats.get(0)).containsEntry("agentId", AGENT_A).containsEntry("model", "deepseek-chat")
            .containsEntry("totalExecutions", 10L).containsEntry("successCount", 7L)
            .containsEntry("failureCount", 3L).containsEntry("budgetExhaustedCount", 1L);

        String q = capturedSql();
        assertThat(q).contains("FROM agent_model_exec_stats_by_agent_org_live");
        assertThat(q).contains("WHERE organization_id = :orgId");
        assertThat(q).contains("GROUP BY agent_entity_id, model");
        assertThat(q).contains("SUM(budget_exhausted_count) AS budget_exhausted_count");
        verify(query, never()).setParameter(eq("agentId"), any());
    }

    @Test
    @DisplayName("empty result sets → empty lists, never null")
    void emptyResultsYieldEmptyLists() {
        when(query.getResultList()).thenReturn(List.of());
        assertThat(service.getAllToolStatsByAgent(TENANT_ID, ORG_ID)).isEmpty();
        assertThat(service.getAllResourceStatsByAgent(TENANT_ID, ORG_ID)).isEmpty();
        assertThat(service.getAllSubAgentCallStats(TENANT_ID, ORG_ID)).isEmpty();
        assertThat(service.getAllModelStatsByAgent(TENANT_ID, ORG_ID)).isEmpty();
    }
}
