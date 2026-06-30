package com.apimarketplace.agent.service;

import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
import com.apimarketplace.agent.domain.AgentExecutionEntity;
import com.apimarketplace.agent.domain.AgentExecutionIterationEntity;
import com.apimarketplace.agent.domain.AgentExecutionMessageEntity;
import com.apimarketplace.agent.domain.AgentExecutionToolCallEntity;
import com.apimarketplace.agent.repository.AgentExecutionIterationRepository;
import com.apimarketplace.agent.repository.AgentExecutionMessageRepository;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentExecutionToolCallRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.storage.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Regression guard for the bell Activity tab freshness contract: every agent
 * execution terminal status (COMPLETED / FAILED / CANCELLED) must invoke
 * {@link AgentExecutionRepository#incrementCounters} so that the
 * {@code a.updatedAt = :now} SET clause (added alongside the existing
 * {@code a.lastExecutionAt = :now}) bumps the parent agent's {@code updated_at}.
 * The aggregator at {@code InternalAgentController.getRecentActivity} reads
 * {@code agents.updated_at}; without these bumps an actively-executing agent
 * stayed stuck at its last-config-edit timestamp.
 *
 * <p>Companion test {@link com.apimarketplace.agent.repository.AgentExecutionRepositoryIncrementCountersQueryTest}
 * pins the JPQL string itself (defense in depth - JPQL bulk UPDATE bypasses
 * {@code @PreUpdate}, so the explicit SET is the only thing that bumps the column).
 */
@DisplayName("AgentObservabilityService - recordFromRequest bumps agents.updated_at on every terminal status")
@ExtendWith(MockitoExtension.class)
class AgentObservabilityServiceUpdatedAtBumpTest {

    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock private AgentExecutionRepository executionRepository;
    @Mock private AgentExecutionIterationRepository iterationRepository;
    @Mock private AgentExecutionMessageRepository messageRepository;
    @Mock private AgentExecutionToolCallRepository toolCallRepository;
    @Mock private StorageService storageService;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private AgentMetricsAggregationService aggregationService;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private AgentRepository agentRepository;
    @Mock private com.apimarketplace.agent.metrics.AgentPrometheusMetrics prometheusMetrics;
    @Mock private com.apimarketplace.agent.service.budget.BudgetReservationService budgetReservationService;

    @Captor private ArgumentCaptor<Instant> nowCaptor;

    private AgentObservabilityService service;

    @BeforeEach
    void setUp() {
        service = new AgentObservabilityService(
            executionRepository, iterationRepository, messageRepository,
            toolCallRepository, storageService, creditClient,
            aggregationService, breakdownService, agentRepository,
            prometheusMetrics, budgetReservationService
        );
    }

    @Test
    @DisplayName("COMPLETED execution calls incrementCounters with a non-null :now (bumps updatedAt)")
    void completedBumpsUpdatedAt() {
        AgentObservabilityRequest req = buildRequest("COMPLETED");

        service.recordFromRequest(req);

        Instant now = captureIncrementCountersNow();
        assertThat(now)
            .as("Activity tab contract: COMPLETED status must flow :now into the SET a.updatedAt = :now clause")
            .isNotNull();
    }

    @Test
    @DisplayName("FAILED execution calls incrementCounters with a non-null :now (bumps updatedAt)")
    void failedBumpsUpdatedAt() {
        AgentObservabilityRequest req = buildRequest("FAILED");

        service.recordFromRequest(req);

        Instant now = captureIncrementCountersNow();
        assertThat(now)
            .as("Activity tab contract: FAILED status must flow :now into the SET a.updatedAt = :now clause")
            .isNotNull();
    }

    @Test
    @DisplayName("CANCELLED execution calls incrementCounters with a non-null :now (bumps updatedAt)")
    void cancelledBumpsUpdatedAt() {
        // CANCELLED is the path V3 audit V1 worried might bypass - V3 audit V2 F5 verified
        // it does NOT bypass: AgentObservabilityService:414-424 passes Instant.now()
        // regardless of status, and success=0/failure=0 for CANCELLED is irrelevant to the
        // updatedAt bump. This test pins the contract so a future refactor that gates
        // incrementCounters on non-CANCELLED status doesn't silently break the Activity feed.
        AgentObservabilityRequest req = buildRequest("CANCELLED");

        service.recordFromRequest(req);

        Instant now = captureIncrementCountersNow();
        assertThat(now)
            .as("Activity tab contract: CANCELLED status must also flow :now into SET a.updatedAt = :now")
            .isNotNull();
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private Instant captureIncrementCountersNow() {
        verify(executionRepository).incrementCounters(
            eq(AGENT_ID), anyLong(), anyInt(), anyInt(), anyInt(), anyLong(), nowCaptor.capture());
        return nowCaptor.getValue();
    }

    private AgentObservabilityRequest buildRequest(String status) {
        AgentObservabilityRequest req = new AgentObservabilityRequest();
        req.setTenantId("tenant-1");
        req.setAgentEntityId(AGENT_ID);
        req.setAgentType("agent");
        req.setNodeId("agent:my_agent");
        req.setRunId("run-123");
        req.setProvider("anthropic");
        req.setModel("claude-3-sonnet");
        req.setStatus(status);
        req.setDurationMs(5000L);
        req.setPromptTokens(1000);
        req.setCompletionTokens(500);
        req.setTotalTokens(1500);
        req.setIterationCount(2);
        req.setTotalToolCalls(3);
        return req;
    }
}
