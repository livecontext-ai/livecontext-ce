package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.agent.loop.AgentLoopService;
import com.apimarketplace.agent.service.budget.GuardChainFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CASA LC-056, regression review 2026-09-29: a run sent to a CLI bridge is budgeted by the
 * bridge itself, from the request's {@code tenantBalance} / {@code maxCreditBudget} fields, and
 * each bridge guard disables itself when its field is missing or zero. Every workflow agent node
 * sent both as null, so a workflow agent routed to the bridge (directly, or through an execution
 * link) ran with no balance check at all, bounded only by its turn count (up to 1000).
 */
@DisplayName("AgentRemoteExecutionService - a bridge run carries its budget, resolved server-side (LC-056)")
@ExtendWith(MockitoExtension.class)
class AgentRemoteExecutionServiceBridgeBudgetTest {

    @Mock private AgentLoopService agentLoopService;
    @Mock private RedisStreamingCallback redisStreamingCallback;
    @Mock private ConversationRedisStreamingCallback conversationRedisStreamingCallback;
    @Mock private CoreToolsCache coreToolsCache;
    @Mock private AgentActivityPublisher agentActivityPublisher;
    @Mock private GuardChainFactory guardChainFactory;
    @Mock private ClassifyService classifyService;
    @Mock private GuardrailService guardrailService;
    @Mock private BridgeLoopDispatcher bridgeDispatcher;
    @Mock private com.apimarketplace.agent.service.ModelCatalogService modelCatalogService;
    @Mock private ActiveStreamRegistry activeStreamRegistry;

    private AgentRemoteExecutionService service;

    @BeforeEach
    void setUp() {
        service = new AgentRemoteExecutionService(
            agentLoopService, new ObjectMapper(), redisStreamingCallback,
            conversationRedisStreamingCallback, coreToolsCache, agentActivityPublisher,
            guardChainFactory, classifyService, guardrailService, bridgeDispatcher, modelCatalogService,
            activeStreamRegistry);
        lenient().when(bridgeDispatcher.shouldDispatch(any())).thenReturn(true);
        lenient().when(modelCatalogService.resolveProvider(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(modelCatalogService.resolveEffortWithDefault(any(), any(), any()))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(bridgeDispatcher.dispatchRaw(any(), any(), anyBoolean())).thenReturn(response());
    }

    private void serverBudget(Double balance, Double agentBudget, Double consumed) {
        when(guardChainFactory.bridgeBudget(eq("tenant-1"), eq("agent-1"), eq("claude-code"), eq("claude-opus-4-6")))
            .thenReturn(new GuardChainFactory.BridgeBudget(balance, agentBudget, consumed));
    }

    private AgentExecutionRequestDto dispatched() {
        ArgumentCaptor<AgentExecutionRequestDto> sent = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(bridgeDispatcher).dispatchRaw(sent.capture(), any(), anyBoolean());
        return sent.getValue();
    }

    @Test
    @DisplayName("regression: a workflow agent sent with no budget reaches the bridge with the tenant balance and the agent budget")
    void workflowRunGetsItsBudget() {
        serverBudget(70.0, 500.0, 120.0);

        service.executeAgent(request("workflow", null, null, null, null), null);

        AgentExecutionRequestDto sent = dispatched();
        assertThat(sent.tenantBalance()).isEqualTo(70.0);
        assertThat(sent.maxCreditBudget()).isEqualTo(500.0);
        assertThat(sent.creditsConsumedSoFar()).isEqualTo(120.0);
    }

    @Test
    @DisplayName("the platform's balance wins over a request value; request pricingRates never reach the bridge")
    void serverValuesWinAndPricingRatesAreDropped() {
        serverBudget(12.5, null, null);
        List<Map<String, Object>> rates = List.of(Map.of("provider", "claude-code", "model", "claude-opus-4-6",
            "inputRate", 0, "outputRate", 0));

        service.executeAgent(request("conversation", 1_000_000.0, 40.0, 5.0, rates), null);

        AgentExecutionRequestDto sent = dispatched();
        assertThat(sent.tenantBalance()).isEqualTo(12.5);
        // No agent budget on the platform side: the request's pair is kept as it came.
        assertThat(sent.maxCreditBudget()).isEqualTo(40.0);
        assertThat(sent.creditsConsumedSoFar()).isEqualTo(5.0);
        assertThat(sent.pricingRates()).isNull();
    }

    @Test
    @DisplayName("regression: a zero balance is refused before the bridge, which would read 0 as \"no budget\"")
    void zeroBalanceIsRefusedBeforeDispatch() {
        serverBudget(0.0, null, null);

        AgentExecutionResponseDto result = service.executeAgent(request("workflow", null, null, null, null), null);

        assertThat(result.success()).isFalse();
        assertThat(result.stopReason()).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(result.budgetScope()).isEqualTo("tenant");
        assertThat(result.error()).startsWith("Insufficient credits");
        verify(bridgeDispatcher, never()).dispatchRaw(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("when both sides carry an agent budget, the platform's window wins over the request's")
    void serverAgentBudgetWinsOverTheRequests() {
        serverBudget(70.0, 500.0, 120.0);

        service.executeAgent(request("workflow", 1_000_000.0, 40.0, 5.0, null), null);

        AgentExecutionRequestDto sent = dispatched();
        assertThat(sent.maxCreditBudget()).isEqualTo(500.0);
        assertThat(sent.creditsConsumedSoFar()).isEqualTo(120.0);
        assertThat(sent.tenantBalance()).isEqualTo(70.0);
    }

    @Test
    @DisplayName("a negative platform balance is refused")
    void negativeBalanceIsRefused() {
        serverBudget(-3.5, null, null);

        assertThat(service.executeAgent(request("workflow", null, null, null, null), null).budgetScope())
            .isEqualTo("tenant");
        verify(bridgeDispatcher, never()).dispatchRaw(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("a 0 balance the request brought is refused when the platform has none")
    void requestZeroBalanceIsRefused() {
        serverBudget(null, null, null);

        assertThat(service.executeAgent(request("workflow", 0.0, null, null, null), null).budgetScope())
            .isEqualTo("tenant");
        verify(bridgeDispatcher, never()).dispatchRaw(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("regression: a chat agent whose creditBudget is 0 (no budget) runs, as it did before")
    void zeroAgentBudgetFromTheRequestRuns() {
        serverBudget(50.0, null, null);

        service.executeAgent(request("conversation", null, 0.0, 0.0, null), null);

        assertThat(dispatched().maxCreditBudget()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("regression: an agent budget already spent is refused before the bridge, which would run one turn first")
    void spentAgentBudgetIsRefusedBeforeDispatch() {
        serverBudget(70.0, 100.0, 100.0);

        AgentExecutionResponseDto result = service.executeAgent(request("workflow", null, null, null, null), null);

        assertThat(result.stopReason()).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(result.budgetScope()).isEqualTo("agent");
        assertThat(result.error()).startsWith("Agent credit budget exhausted");
        verify(bridgeDispatcher, never()).dispatchRaw(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("without a credit client the platform has no balance: the request value is kept and nothing is refused")
    void noPlatformBalanceKeepsTheRequestValue() {
        serverBudget(null, null, null);

        service.executeAgent(request("workflow", 33.0, null, null, null), null);

        assertThat(dispatched().tenantBalance()).isEqualTo(33.0);
    }

    private AgentExecutionResponseDto response() {
        return new AgentExecutionResponseDto(
            true, "done", null, List.of(), 1, Map.of(), null, 5L,
            "claude-code", "claude-opus-4-6", List.of(), "COMPLETED",
            Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
    }

    private AgentExecutionRequestDto request(String streamingFormat, Double tenantBalance, Double maxCreditBudget,
                                             Double consumed, List<Map<String, Object>> pricingRates) {
        return new AgentExecutionRequestDto(
            "Answer.", "You are an agent.", "claude-code", "claude-opus-4-6", 0.0, 320, List.of(),
            false, 10, 1000, 150, null, "tenant-1",
            null, null, null, Map.of(),
            maxCreditBudget,
            null, null, null, "conversation-1", streamingFormat,
            null, null, null, null, null, null,
            "agent-1",
            tenantBalance, pricingRates, consumed,
            null, null, UUID.randomUUID().toString(), null, null, null);
    }
}
