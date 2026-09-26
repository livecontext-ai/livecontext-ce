package com.apimarketplace.agent.service;

import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code source_id} a workflow agent execution is billed under when its observability row
 * cannot be saved.
 *
 * <p>Regression (audit of the replay-after-refusal fix, 2026-09-25): the fallback was
 * {@code request.getNodeId()}, which repeats on every run of that workflow node. auth-service now
 * answers a debit whose key already carries the same charge as "already paid", so the second run
 * of the node, billed under the same fallback key, was answered as paid and never billed. Before
 * that it collided on the unique index and was dead-lettered. The key must be unique per charge.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentObservabilityService - billing source_id when the observability row fails")
class AgentObservabilityBillingSourceIdTest {

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

    private AgentObservabilityService service;

    private static final String NODE_ID = "agent:summarize";

    @BeforeEach
    void setUp() {
        service = new AgentObservabilityService(
                executionRepository, iterationRepository, messageRepository,
                toolCallRepository, storageService, creditClient,
                aggregationService, breakdownService, agentRepository,
                prometheusMetrics, budgetReservationService);
    }

    private AgentObservabilityRequest runOfTheNode(String runId) {
        AgentObservabilityRequest req = new AgentObservabilityRequest();
        req.setTenantId("tenant-1");
        req.setAgentType("agent");
        req.setNodeId(NODE_ID);
        req.setRunId(runId);
        req.setProvider("anthropic");
        req.setModel("claude-3-sonnet");
        req.setStatus("COMPLETED");
        req.setPromptTokens(1000);
        req.setCompletionTokens(500);
        req.setTotalTokens(1500);
        return req;
    }

    private List<String> billedSourceIds(int calls) {
        ArgumentCaptor<String> sourceId = ArgumentCaptor.forClass(String.class);
        verify(creditClient, times(calls)).consumeCredits(
                eq("tenant-1"), eq("AGENT_EXECUTION"), sourceId.capture(), anyString(), anyString(),
                anyInt(), anyInt(), isNull(), any(com.apimarketplace.common.credit.LlmCacheTokens.class), any());
        return sourceId.getAllValues();
    }

    @Test
    @DisplayName("regression: two runs of the same node whose rows fail to save are billed under two DIFFERENT keys, never the node id")
    void twoRunsOfOneNodeGetDistinctKeys() {
        when(executionRepository.save(any())).thenThrow(new RuntimeException("DB down"));

        service.recordFromRequest(runOfTheNode("run-1"));
        service.recordFromRequest(runOfTheNode("run-2"));

        List<String> keys = billedSourceIds(2);
        assertThat(keys).doesNotContain(NODE_ID);
        assertThat(keys.get(0)).isNotEqualTo(keys.get(1));
    }

    @Test
    @DisplayName("a dispatcher-minted executionId is the key whether or not the row saved, so a re-send of the same execution keeps its key")
    void dispatcherExecutionIdIsTheKeyEvenWhenTheRowFails() {
        String executionId = UUID.randomUUID().toString();
        AgentObservabilityRequest req = runOfTheNode("run-1");
        req.setExecutionId(executionId);
        when(executionRepository.save(any())).thenThrow(new RuntimeException("DB down"));

        service.recordFromRequest(req);
        service.recordFromRequest(req);

        assertThat(billedSourceIds(2)).containsExactly(executionId, executionId);
    }

    @Test
    @DisplayName("one call, one minted key: the refused consume and its rejection dead-letter carry the SAME key")
    void mintedKeyIsReusedByPersistRejection() {
        when(executionRepository.save(any())).thenThrow(new RuntimeException("DB down"));
        when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), isNull(),
                any(com.apimarketplace.common.credit.LlmCacheTokens.class), any()))
                .thenReturn(java.util.Map.of("success", false, "error", "402 Insufficient credits"));

        service.recordFromRequest(runOfTheNode("run-1"));

        String consumed = billedSourceIds(1).get(0);
        ArgumentCaptor<String> rejected = ArgumentCaptor.forClass(String.class);
        verify(creditClient).persistRejection(eq("tenant-1"), eq("AGENT_EXECUTION"), rejected.capture(),
                any(), any(), anyInt(), anyInt(), any(), any(), any());
        // Recomputing the key per use would mint a second UUID here, and the replay of the refused
        // turn would then bill a key nothing else knows.
        assertThat(rejected.getValue()).isEqualTo(consumed);
    }

    @Test
    @DisplayName("one call, one minted key: a consume that throws is dead-lettered under the SAME key")
    void mintedKeyIsReusedByTheDeadLetter() {
        when(executionRepository.save(any())).thenThrow(new RuntimeException("DB down"));
        when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), isNull(),
                any(com.apimarketplace.common.credit.LlmCacheTokens.class), any()))
                .thenThrow(new RuntimeException("auth-service down"));

        service.recordFromRequest(runOfTheNode("run-1"));

        String consumed = billedSourceIds(1).get(0);
        ArgumentCaptor<String> deadLettered = ArgumentCaptor.forClass(String.class);
        verify(creditClient).consumeCreditsAsync(eq("tenant-1"), eq("AGENT_EXECUTION"), deadLettered.capture(),
                any(), any(), anyInt(), anyInt(), any());
        assertThat(deadLettered.getValue()).isEqualTo(consumed);
    }

    @Test
    @DisplayName("helper: recorded id first, then a well-formed requested id, then a fresh UUID; a malformed requested id is never a key")
    void billingSourceIdOrder() {
        UUID recorded = UUID.randomUUID();
        String requested = UUID.randomUUID().toString();

        assertThat(AgentObservabilityService.billingSourceId(recorded, requested)).isEqualTo(recorded.toString());
        assertThat(AgentObservabilityService.billingSourceId(null, requested)).isEqualTo(requested);
        String minted = AgentObservabilityService.billingSourceId(null, "not-a-uuid");
        assertThat(minted).isNotEqualTo("not-a-uuid");
        assertThat(UUID.fromString(minted)).isNotNull();
        assertThat(AgentObservabilityService.billingSourceId(null, null))
                .isNotEqualTo(AgentObservabilityService.billingSourceId(null, null));
    }
}
