package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentExecutionEntity;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service-level coverage for the agent-type ({@code classify}/{@code guardrail}/
 * {@code browser_agent}) observability methods. The e2e contract suite
 * (CE-AGENT-OBS-028) asserts the HTTP 400 on an unknown type, but only the
 * SERVICE itself enforces the allow-list (the controller maps the throw) - and
 * nothing asserts the valid-type happy path binds the type + maps the summary.
 * EntityManager mocked → asserts the Java contract; live SQL is proven by e2e.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentMetricsQueryService - agent-type summary/executions")
class AgentMetricsQueryServiceAgentTypeTest {

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

    private String capturedSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        return sql.getValue();
    }

    @Test
    @DisplayName("summary rejects a type outside the allow-list before touching the DB")
    void summaryRejectsUnknownType() {
        assertThatThrownBy(() -> service.getAgentTypeSummary(TENANT_ID, ORG_ID, "bogus"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bogus");
        // 'agent' and 'chat' are real agent_type values but NOT valid here.
        assertThatThrownBy(() -> service.getAgentTypeSummary(TENANT_ID, ORG_ID, "agent"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getAgentTypeSummary(TENANT_ID, ORG_ID, "chat"))
            .isInstanceOf(IllegalArgumentException.class);
        // The allow-list check short-circuits BEFORE any query is built.
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("summary accepts a valid type, binds it, and maps the aggregate row")
    void summaryAcceptsValidTypeAndMaps() {
        when(query.getSingleResult()).thenReturn(new Object[]{
            BigInteger.valueOf(12), BigInteger.valueOf(10), BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO,
            BigInteger.valueOf(5000), BigInteger.valueOf(8), BigInteger.valueOf(6000), BigInteger.valueOf(500),
            "2026-06-13T10:00:00Z", new BigDecimal("2.5"), BigInteger.valueOf(1200),
        });

        Map<String, Object> summary = service.getAgentTypeSummary(TENANT_ID, ORG_ID, "classify");

        assertThat(summary)
            .containsEntry("totalExecutions", 12L)
            .containsEntry("successCount", 10L)
            .containsEntry("failureCount", 1L)
            .containsEntry("cancelledCount", 1L)
            .containsEntry("totalTokensUsed", 5000L)
            .containsEntry("totalCreditsConsumed", 2.5)
            // The cache-read subset is the LAST selected column (row[11]).
            .containsEntry("totalCachedTokens", 1200L)
            .containsEntry("successRate", 83.3) // round(10*1000/12)/10
            .containsEntry("lastExecutionAt", "2026-06-13T10:00:00Z");

        verify(query).setParameter("agentType", "classify");
        String sql = capturedSql();
        assertThat(sql).contains("ae.agent_type = :agentType");
        assertThat(sql).contains("total_cached_tokens");
    }

    @Test
    @DisplayName("summary defaults totalCachedTokens to 0 for a legacy row missing the trailing column")
    void summaryDefaultsCachedTokensWhenColumnAbsent() {
        // A defensive 11-column row (no cached-tokens column) - exercises the
        // `row.length > 11` guard so a stale query shape can't NPE.
        when(query.getSingleResult()).thenReturn(new Object[]{
            BigInteger.valueOf(4), BigInteger.valueOf(4), BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
            BigInteger.valueOf(100), BigInteger.valueOf(2), BigInteger.valueOf(900), BigInteger.valueOf(225),
            "2026-06-13T10:00:00Z", new BigDecimal("0.5"),
        });

        Map<String, Object> summary = service.getAgentTypeSummary(TENANT_ID, ORG_ID, "guardrail");

        assertThat(summary).containsEntry("totalCachedTokens", 0L);
    }

    @Test
    @DisplayName("chat summary maps the cache-read subset from the trailing column and selects it")
    void chatSummaryMapsCachedTokens() {
        when(query.getSingleResult()).thenReturn(new Object[]{
            BigInteger.valueOf(20), BigInteger.valueOf(18), BigInteger.valueOf(2), BigInteger.ZERO, BigInteger.ZERO,
            BigInteger.valueOf(9000), BigInteger.valueOf(15), BigInteger.valueOf(12000), BigInteger.valueOf(600),
            "2026-06-13T11:00:00Z", new BigDecimal("4.0"), BigInteger.valueOf(3300),
        });

        Map<String, Object> summary = service.getChatSummary(TENANT_ID, ORG_ID);

        assertThat(summary)
            .containsEntry("totalExecutions", 20L)
            .containsEntry("totalTokensUsed", 9000L)
            .containsEntry("totalCreditsConsumed", 4.0)
            // Cache-read subset is the LAST selected column (row[11]).
            .containsEntry("totalCachedTokens", 3300L)
            .containsEntry("lastExecutionAt", "2026-06-13T11:00:00Z");

        String sql = capturedSql();
        assertThat(sql).contains("ae.source = 'CHAT'");
        assertThat(sql).contains("total_cached_tokens");
    }

    @Test
    @DisplayName("executions rejects an unknown type without hitting the repository")
    void executionsRejectsUnknownType() {
        assertThatThrownBy(() -> service.getAgentTypeExecutions(TENANT_ID, ORG_ID, "bogus", PageRequest.of(0, 10)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bogus");
        verify(executionRepository, never())
            .findByAgentTypeAndOrganizationIdStrictOrderByStartedAtDesc(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("executions accepts a valid type and delegates to the org-strict finder")
    void executionsAcceptsValidTypeAndDelegates() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<AgentExecutionEntity> page = new PageImpl<>(List.of());
        when(executionRepository.findByAgentTypeAndOrganizationIdStrictOrderByStartedAtDesc("guardrail", ORG_ID, pageable))
            .thenReturn(page);

        Page<AgentExecutionEntity> result = service.getAgentTypeExecutions(TENANT_ID, ORG_ID, "guardrail", pageable);

        assertThat(result).isSameAs(page);
        verify(executionRepository)
            .findByAgentTypeAndOrganizationIdStrictOrderByStartedAtDesc("guardrail", ORG_ID, pageable);
    }
}
