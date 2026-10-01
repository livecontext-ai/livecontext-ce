package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.AgentStopReason;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.UsageInfo;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.common.credit.LlmCacheTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The loop hands its cache counters to the budget guards (2026-09-30).
 *
 * <p>A guard priced every prompt token at the input rate because the counters never
 * reached it: on a Claude-shaped turn (mostly cache reads) that projected about five
 * times the real debit. These tests pin the chain from the provider's usage report to
 * the {@link IterationContext} the guard receives, not just the guard in isolation.
 */
@DisplayName("Loop -> guard: cache counters")
class LoopGuardCacheTokensTest {

    private static UsageInfo anthropicUsage(int prompt, int cacheWrite, int cacheRead) {
        return UsageInfo.builder()
            .promptTokens(prompt).completionTokens(5)
            .cacheCreationInputTokens(cacheWrite).cacheReadInputTokens(cacheRead)
            .build();
    }

    @Test
    @DisplayName("LoopExecutionState exposes the run's cache totals and the latest iteration's alone")
    void stateExposesCacheTotalsAndLastIteration() {
        LoopExecutionState state = new LoopExecutionState("run-1", 10, 0);
        state.trackUsage(anthropicUsage(4, 12_000, 28_641));
        state.trackUsage(UsageInfo.builder().promptTokens(10).completionTokens(1).cachedTokens(7).build());

        assertThat(state.getCacheTokensSoFar()).isEqualTo(new LlmCacheTokens(12_000, 28_641, 7, 0));
        assertThat(state.getLastIterationCacheTokens()).isEqualTo(new LlmCacheTokens(null, null, 7, null));
    }

    @Test
    @DisplayName("before any iteration the latest-iteration cache counters are NO_CACHE")
    void noIterationYet() {
        assertThat(new LoopExecutionState("run-1", 10, 0).getLastIterationCacheTokens())
            .isEqualTo(IterationContext.NO_CACHE);
    }

    @Test
    @DisplayName("the guard's IterationContext carries the cache counters the provider reported")
    void guardReceivesCacheCounters() {
        LLMProviderFactory providerFactory = mock(LLMProviderFactory.class);
        LLMProvider llmProvider = mock(LLMProvider.class);
        lenient().when(providerFactory.getProvider(anyString())).thenReturn(llmProvider);
        lenient().when(llmProvider.isConfigured()).thenReturn(true);
        lenient().when(llmProvider.getDefaultModel()).thenReturn("test-model");
        lenient().when(llmProvider.getProviderName()).thenReturn("test-provider");
        when(llmProvider.complete(any())).thenReturn(CompletionResponse.builder()
            .content("ok").finishReason("tool_use").model("test-model")
            .toolCalls(List.of(new ToolCall("tc-1", "some_tool", Map.of(), null)))
            .usage(anthropicUsage(8, 18_971, 182_073))
            .build());

        List<IterationContext> seen = new ArrayList<>();
        PreIterationGuard guard = ctx -> {
            seen.add(ctx);
            return seen.size() < 2
                ? GuardResult.allow()
                : GuardResult.deny(AgentStopReason.BUDGET_EXHAUSTED, "test", "stop after one iteration");
        };
        AgentLoopContext context = AgentLoopContext.builder()
            .provider("test-provider").model("test-model")
            .systemPrompt("sys").userPrompt("hi").maxIterations(10)
            .preIterationGuard(guard)
            .build();

        new AgentLoopService(providerFactory, null, null, null).execute(context);

        assertThat(seen).hasSize(2);
        IterationContext afterFirst = seen.get(1);
        assertThat(afterFirst.cacheTokensSoFar().cacheCreationTokens()).isEqualTo(18_971);
        assertThat(afterFirst.cacheTokensSoFar().cacheReadTokens()).isEqualTo(182_073);
        assertThat(afterFirst.lastIterationCacheTokens().cacheCreationTokens()).isEqualTo(18_971);
        assertThat(afterFirst.lastIterationCacheTokens().cacheReadTokens()).isEqualTo(182_073);
        assertThat(afterFirst.promptTokensSoFar()).isEqualTo(8);
    }

    @Test
    @DisplayName("after two DIFFERENT calls the guard sees the run total and the last call apart")
    void totalsAndLastIterationAreNotInterchangeable() {
        LLMProviderFactory providerFactory = mock(LLMProviderFactory.class);
        LLMProvider llmProvider = mock(LLMProvider.class);
        lenient().when(providerFactory.getProvider(anyString())).thenReturn(llmProvider);
        lenient().when(llmProvider.isConfigured()).thenReturn(true);
        lenient().when(llmProvider.getDefaultModel()).thenReturn("test-model");
        lenient().when(llmProvider.getProviderName()).thenReturn("test-provider");
        AtomicInteger call = new AtomicInteger();
        when(llmProvider.complete(any())).thenAnswer(inv -> CompletionResponse.builder()
            .content("ok").finishReason("tool_use").model("test-model")
            .toolCalls(List.of(new ToolCall("tc-" + call.get(), "some_tool", Map.of(), null)))
            .usage(call.incrementAndGet() == 1 ? anthropicUsage(4, 12_000, 28_641) : anthropicUsage(2, 1_057, 57_122))
            .build());

        List<IterationContext> seen = new ArrayList<>();
        PreIterationGuard guard = ctx -> {
            seen.add(ctx);
            return seen.size() < 3
                ? GuardResult.allow()
                : GuardResult.deny(AgentStopReason.BUDGET_EXHAUSTED, "test", "stop after two iterations");
        };
        AgentLoopContext context = AgentLoopContext.builder()
            .provider("test-provider").model("test-model")
            .systemPrompt("sys").userPrompt("hi").maxIterations(10)
            .preIterationGuard(guard)
            .build();

        new AgentLoopService(providerFactory, null, null, null).execute(context);

        IterationContext afterSecond = seen.get(2);
        assertThat(afterSecond.cacheTokensSoFar().cacheCreationTokens()).isEqualTo(13_057);
        assertThat(afterSecond.cacheTokensSoFar().cacheReadTokens()).isEqualTo(85_763);
        assertThat(afterSecond.lastIterationCacheTokens().cacheCreationTokens()).isEqualTo(1_057);
        assertThat(afterSecond.lastIterationCacheTokens().cacheReadTokens()).isEqualTo(57_122);
    }
}
