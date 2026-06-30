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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for the SINGLE-agent stat mappers ({@code get*StatsByAgent}). Their
 * batch siblings ({@code getAll*ByAgent}) are unit-tested, but the single-agent
 * variants are genuinely distinct code paths - notably {@code getToolStatsByAgent}
 * has a different column order (lastUsedAt@7, repeatCallCount@8) than the batch
 * row (which carries an extra agentId at index 0), so an off-by-one in either
 * would not be caught by the other's test. CE-AGENT-OBS-013/015/016 cover the
 * HTTP totals only - not the Java row→map mapping + the per-agent binding.
 * EntityManager mocked; live SQL proven by e2e.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentMetricsQueryService - single-agent stat mappers")
class AgentMetricsQueryServiceSingleAgentStatsTest {

    private static final String TENANT_ID = "tenant-42";
    private static final String ORG_ID = "org-acme";
    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

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
    @DisplayName("tool stats: maps the 9-column row (lastUsedAt@7, repeatCallCount@8), binds the agent id")
    void toolStatsMapsSingleAgentRow() {
        when(query.getResultList()).thenReturn(List.<Object[]>of(
            new Object[]{ "web_search", BigInteger.valueOf(5), BigInteger.valueOf(4), BigInteger.ONE,
                          new BigDecimal("80.00"), BigInteger.valueOf(120), BigInteger.valueOf(900),
                          "2026-06-08T10:00:00Z", BigInteger.valueOf(2) }
        ));

        List<Map<String, Object>> stats = service.getToolStatsByAgent(TENANT_ID, ORG_ID, AGENT);

        assertThat(stats).hasSize(1);
        assertThat(stats.get(0))
            .containsEntry("toolName", "web_search")
            .containsEntry("totalCalls", 5L).containsEntry("successCount", 4L).containsEntry("failureCount", 1L)
            .containsEntry("successRatePct", 80.0)
            .containsEntry("avgDurationMs", 120L).containsEntry("maxDurationMs", 900L)
            .containsEntry("lastUsedAt", "2026-06-08T10:00:00Z")
            .containsEntry("repeatCallCount", 2L);

        verify(query).setParameter("agentId", AGENT);
        assertThat(capturedSql())
            .contains("WHERE ae.agent_entity_id = :agentId")
            .contains("GROUP BY tc.tool_name");
    }

    @Test
    @DisplayName("model stats: maps budget_exhausted_count into its own key alongside failure_count, binds the agent id")
    void modelStatsMapsSingleAgentRow() {
        when(query.getResultList()).thenReturn(List.<Object[]>of(
            new Object[]{ "deepseek-chat", BigInteger.valueOf(10), BigInteger.valueOf(7), BigInteger.valueOf(3), BigInteger.ONE }
        ));

        List<Map<String, Object>> stats = service.getModelStatsByAgent(TENANT_ID, ORG_ID, AGENT);

        assertThat(stats).hasSize(1);
        assertThat(stats.get(0))
            .containsEntry("model", "deepseek-chat")
            .containsEntry("totalExecutions", 10L).containsEntry("successCount", 7L)
            .containsEntry("failureCount", 3L).containsEntry("budgetExhaustedCount", 1L);

        verify(query).setParameter("agentId", AGENT);
        assertThat(capturedSql())
            .contains("ae.model IS NOT NULL")
            .contains("GROUP BY ae.model")
            .contains("BUDGET_EXHAUSTED");
    }

    @Test
    @DisplayName("conversation stats: maps each conversation, filters non-null conversation ids, binds the agent id")
    void conversationStatsMapsSingleAgentRow() {
        when(query.getResultList()).thenReturn(List.<Object[]>of(
            new Object[]{ "conv-1", BigInteger.valueOf(4), BigInteger.valueOf(3), BigInteger.ONE }
        ));

        List<Map<String, Object>> stats = service.getConversationStatsByAgent(TENANT_ID, ORG_ID, AGENT);

        assertThat(stats).hasSize(1);
        assertThat(stats.get(0))
            .containsEntry("conversationId", "conv-1")
            .containsEntry("totalExecutions", 4L).containsEntry("successCount", 3L).containsEntry("failureCount", 1L);

        verify(query).setParameter("agentId", AGENT);
        assertThat(capturedSql())
            .contains("ae.conversation_id IS NOT NULL")
            .contains("GROUP BY ae.conversation_id");
    }

    @Test
    @DisplayName("empty result sets yield empty lists, never null")
    void emptyResultsYieldEmptyLists() {
        when(query.getResultList()).thenReturn(List.of());
        assertThat(service.getToolStatsByAgent(TENANT_ID, ORG_ID, AGENT)).isEmpty();
        assertThat(service.getModelStatsByAgent(TENANT_ID, ORG_ID, AGENT)).isEmpty();
        assertThat(service.getConversationStatsByAgent(TENANT_ID, ORG_ID, AGENT)).isEmpty();
    }
}
