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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for {@code getFleetExtraCounts} - the cancelled / loop-detected counts
 * the fleet summary folds in. The fleet-summary e2e (CE-AGENT-OBS-032) consumes
 * these numbers but cannot assert the key invariant: the count must EXCLUDE
 * executions whose agent was hard-deleted (orphans), via the
 * {@code EXISTS(SELECT 1 FROM agents …)} guard. EntityManager mocked; live SQL
 * proven by e2e.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentMetricsQueryService - fleet extra counts (cancelled / loop)")
class AgentMetricsQueryServiceFleetExtraCountsTest {

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

    @Test
    @DisplayName("maps cancelled/loop counts + the cache-read rollup and excludes orphaned executions via EXISTS(agents)")
    void mapsCountsAndExcludesOrphans() {
        // [cancelled_count, loop_detected_count, total_cached_tokens]
        when(query.getSingleResult())
            .thenReturn(new Object[]{ BigInteger.valueOf(7), BigInteger.valueOf(2), BigInteger.valueOf(4200) });

        Map<String, Long> counts = service.getFleetExtraCounts(TENANT_ID, ORG_ID);

        assertThat(counts)
            .containsEntry("cancelledCount", 7L)
            .containsEntry("loopDetectedCount", 2L)
            // The fleet summary's cache-read subset rolls up from agent_executions here.
            .containsEntry("totalCachedTokens", 4200L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        String q = sql.getValue();
        // The orphan-exclusion guard: count chat rows (null agent) OR rows whose agent still exists.
        assertThat(q).contains("ae.agent_entity_id IS NULL OR EXISTS (SELECT 1 FROM agents a WHERE a.id = ae.agent_entity_id)");
        assertThat(q).contains("'CANCELLED', 'STOPPED_BY_USER', 'TIMEOUT'");
        assertThat(q).contains("ae.loop_detected");
        assertThat(q).contains("total_cached_tokens");
        // The cache-read rollup is FLEET-ONLY: it must FILTER out chat rows
        // (agent_entity_id IS NULL) so the dashboard overview, which adds
        // chatSummary.totalCachedTokens on top, never double-counts chat cache.
        assertThat(q).contains("SUM(ae.total_cached_tokens) FILTER (WHERE ae.agent_entity_id IS NOT NULL)");
    }

    @Test
    @DisplayName("defaults totalCachedTokens to 0 when a legacy 2-column row lacks the trailing column")
    void defaultsCachedTokensWhenColumnAbsent() {
        when(query.getSingleResult())
            .thenReturn(new Object[]{ BigInteger.valueOf(1), BigInteger.valueOf(0) });

        Map<String, Long> counts = service.getFleetExtraCounts(TENANT_ID, ORG_ID);

        assertThat(counts).containsEntry("totalCachedTokens", 0L);
    }
}
