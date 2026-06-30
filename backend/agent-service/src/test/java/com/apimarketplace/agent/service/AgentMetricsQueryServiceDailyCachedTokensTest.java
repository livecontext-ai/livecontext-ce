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
 * Coverage for the cache-read token rollup ({@code cachedTokens}) added to the
 * daily time-series. The three daily-stats methods ({@code getDailyStats},
 * {@code getChatDailyStats}, {@code getDailyStatsByAgent}) share the
 * {@code mapDailyStatsResults} helper, so they MUST keep an identical SELECT
 * column list; {@code total_cached_tokens} is appended as the LAST column on all
 * three and read at the trailing index by the shared mapper.
 *
 * <p>EntityManager mocked → asserts the Java contract (row→map mapping of the new
 * field + every daily query selecting the column); live SQL is proven by e2e.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentMetricsQueryService - daily stats cached-tokens rollup")
class AgentMetricsQueryServiceDailyCachedTokensTest {

    private static final String TENANT_ID = "tenant-42";
    private static final String ORG_ID = "org-acme";
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

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

    /**
     * A full 13-column daily-stats row. Indices 0-11 are the original columns;
     * the LAST element (index 12) is the appended {@code total_cached_tokens}.
     */
    private Object[] dailyRow(long cachedTokens) {
        return new Object[]{
            "2026-06-13",                 // 0 execution_date
            "deepseek",                   // 1 provider
            "deepseek-chat",              // 2 model
            BigInteger.valueOf(10),       // 3 total_executions
            BigInteger.valueOf(8),        // 4 completed_count
            BigInteger.valueOf(1),        // 5 failed_count
            BigInteger.ONE,               // 6 cancelled_count
            BigInteger.ZERO,              // 7 loop_detected_count
            BigInteger.valueOf(20),       // 8 total_tool_calls
            BigInteger.valueOf(7000),     // 9 total_tokens
            BigInteger.valueOf(600),      // 10 avg_duration_ms
            new BigDecimal("2.5"),        // 11 avg_iterations
            BigInteger.valueOf(cachedTokens), // 12 total_cached_tokens (LAST)
        };
    }

    @Test
    @DisplayName("getDailyStats maps the trailing cached-tokens column and selects it")
    void dailyStatsMapsCachedTokens() {
        when(query.getResultList()).thenReturn(List.<Object[]>of(dailyRow(1800)));

        List<Map<String, Object>> stats = service.getDailyStats(TENANT_ID, ORG_ID, 30);

        assertThat(stats).hasSize(1);
        assertThat(stats.get(0))
            .containsEntry("totalTokens", 7000L)
            .containsEntry("cachedTokens", 1800L);
        assertThat(capturedSql()).contains("total_cached_tokens");
    }

    @Test
    @DisplayName("getChatDailyStats maps cached tokens and selects the column (CHAT-scoped)")
    void chatDailyStatsMapsCachedTokens() {
        when(query.getResultList()).thenReturn(List.<Object[]>of(dailyRow(950)));

        List<Map<String, Object>> stats = service.getChatDailyStats(TENANT_ID, ORG_ID, 30);

        assertThat(stats).hasSize(1);
        assertThat(stats.get(0)).containsEntry("cachedTokens", 950L);
        String q = capturedSql();
        assertThat(q).contains("ae.source = 'CHAT'");
        assertThat(q).contains("total_cached_tokens");
    }

    @Test
    @DisplayName("getDailyStatsByAgent maps cached tokens, binds the agent, and selects the column")
    void dailyStatsByAgentMapsCachedTokens() {
        when(query.getResultList()).thenReturn(List.<Object[]>of(dailyRow(4100)));

        List<Map<String, Object>> stats = service.getDailyStatsByAgent(TENANT_ID, ORG_ID, 30, AGENT_ID);

        assertThat(stats).hasSize(1);
        assertThat(stats.get(0)).containsEntry("cachedTokens", 4100L);
        verify(query).setParameter("agentId", AGENT_ID);
        assertThat(capturedSql()).contains("total_cached_tokens");
    }

    @Test
    @DisplayName("a legacy 12-column daily row (no cached-tokens column) defaults cachedTokens to 0")
    void daily12ColumnRowDefaultsCachedTokensToZero() {
        Object[] legacy = new Object[]{
            "2026-06-13", "deepseek", "deepseek-chat",
            BigInteger.valueOf(10), BigInteger.valueOf(8), BigInteger.valueOf(1),
            BigInteger.ONE, BigInteger.ZERO, BigInteger.valueOf(20),
            BigInteger.valueOf(7000), BigInteger.valueOf(600), new BigDecimal("2.5"),
        };
        when(query.getResultList()).thenReturn(List.<Object[]>of(legacy));

        List<Map<String, Object>> stats = service.getDailyStats(TENANT_ID, ORG_ID, 30);

        assertThat(stats).hasSize(1);
        assertThat(stats.get(0)).containsEntry("cachedTokens", 0L);
    }
}
