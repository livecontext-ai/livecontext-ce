package com.apimarketplace.agent.service.budget;

import com.apimarketplace.agent.domain.AgentStopReason;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.UsageInfo;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.loop.AgentLoopResult;
import com.apimarketplace.agent.loop.AgentLoopService;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The real loop driving the real {@link TenantBudgetGuard} (2026-09-30): the usage a
 * provider reports travels through the loop state into the guard, which prices it the
 * way the ledger does. Only the LLM and the balance lookup are stubbed.
 */
@DisplayName("AgentLoopService + TenantBudgetGuard - cache-aware projection")
class LoopTenantGuardCacheIntegrationTest {

    /** claude-sonnet-5-5 at the prod snapshot rates x 4/3. */
    private static final ModelCostCalculator SONNET = new ModelCostCalculator(
        new BigDecimal("2.666667"), new BigDecimal("13.333333"), BigDecimal.ZERO, null, null,
        new BigDecimal("0.266667"), new BigDecimal("3.333333"));

    /** gpt-6-luna at the prod snapshot rates x 4/3. */
    private static final ModelCostCalculator LUNA = new ModelCostCalculator(
        new BigDecimal("0.133333"), new BigDecimal("0.666667"), BigDecimal.ZERO, null, null,
        new BigDecimal("0.013333"), new BigDecimal("0.133333"));

    private static AgentLoopResult run(String provider, UsageInfo usagePerCall,
                                       ModelCostCalculator rates, String balance) {
        LLMProviderFactory factory = mock(LLMProviderFactory.class);
        LLMProvider llm = mock(LLMProvider.class);
        lenient().when(factory.getProvider(anyString())).thenReturn(llm);
        lenient().when(llm.isConfigured()).thenReturn(true);
        lenient().when(llm.getDefaultModel()).thenReturn("m");
        lenient().when(llm.getProviderName()).thenReturn(provider);
        // Every call asks for a tool, so only the guard or the iteration cap ends the loop.
        when(llm.complete(any())).thenReturn(CompletionResponse.builder()
            .content("working").finishReason("tool_use").model("m")
            .toolCalls(List.of(new ToolCall("tc-1", "some_tool", Map.of(), null)))
            .usage(usagePerCall)
            .build());

        CreditConsumptionClient credit = mock(CreditConsumptionClient.class);
        when(credit.fetchLlmSpendableBalance(any(), any(), any())).thenReturn(new BigDecimal(balance));

        AgentLoopContext context = AgentLoopContext.builder()
            .tenantId("141").provider(provider).model("m")
            .systemPrompt("sys").userPrompt("hi").maxIterations(5)
            .preIterationGuard(new TenantBudgetGuard(credit, rates))
            .build();
        return new AgentLoopService(factory, null, null, null).execute(context);
    }

    /** One direct Anthropic call: 2 fresh input tokens, the rest served from the cache. */
    private static final UsageInfo ANTHROPIC_CALL = UsageInfo.builder()
        .promptTokens(2).completionTokens(5)
        .cacheCreationInputTokens(1_057).cacheReadInputTokens(57_122)
        .build();

    @Test
    @DisplayName("direct Anthropic: a cache the prompt count cannot see is priced, so 100 credits stop the loop after four calls")
    void anthropicCacheIsPricedByTheLoopGuard() {
        // Each call really costs 18.83 (2 fresh + 1,057 written + 57,122 read). Before, the
        // guard priced 2 prompt tokens per call and ran all five unseen. Now it stops when the
        // spend plus 2 x the last call no longer fits: 75.3 + 37.7 > 100 before the fifth.
        // No cache-miss reservation on this path (ModelCostCalculator.cacheMissReserve).
        AgentLoopResult result = run("anthropic", ANTHROPIC_CALL, SONNET, "100");

        assertThat(result.stopReason()).isEqualTo(AgentStopReason.BUDGET_EXHAUSTED);
        assertThat(result.iterations()).isEqualTo(4);
    }

    @Test
    @DisplayName("direct Anthropic: 733 credits cover five cached calls, the loop runs to its iteration cap")
    void anthropicLoopRunsWhenTheBalanceCoversIt() {
        AgentLoopResult result = run("anthropic", ANTHROPIC_CALL, SONNET, "733.5726");

        assertThat(result.stopReason()).isNotEqualTo(AgentStopReason.BUDGET_EXHAUSTED);
        assertThat(result.iterations()).isEqualTo(5);
    }

    @Test
    @DisplayName("subset provider: the cached part is priced at the cache rate, so 25 credits allow two GPT Luna calls, not one")
    void subsetCachedTokensLetTheLoopGoFurther() {
        // Per call: 115,668 prompt of which 87,808 cached. Cache-aware cost 5.11, a miss
        // 15.65. Before, a call cost 15.65 and the projection 31.29: the loop stopped at 1.
        UsageInfo lunaCall = UsageInfo.builder()
            .promptTokens(115_668).completionTokens(336).cachedTokens(87_808)
            .build();

        AgentLoopResult result = run("openai", lunaCall, LUNA, "25");

        assertThat(result.stopReason()).isEqualTo(AgentStopReason.BUDGET_EXHAUSTED);
        assertThat(result.iterations()).isEqualTo(2);
    }
}
