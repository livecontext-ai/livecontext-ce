package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.*;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.streaming.StreamingCallback;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for PreIterationGuard (BUDGET_EXHAUSTED) behavior in AgentLoopService.
 * Covers both sync (executeLoop) and streaming (executeStreamingLoop) paths.
 */
@DisplayName("AgentLoopService - Budget Exhaustion")
@ExtendWith(MockitoExtension.class)
class AgentLoopServiceBudgetExhaustedTest {

    @Mock
    private LLMProviderFactory providerFactory;

    @Mock
    private LLMProvider llmProvider;

    @Mock
    private StreamingCallback callback;

    private AgentLoopService agentLoopService;

    @BeforeEach
    void setUp() {
        agentLoopService = new AgentLoopService(providerFactory, null, null, null);

        lenient().when(providerFactory.getProvider(anyString())).thenReturn(llmProvider);
        lenient().when(llmProvider.isConfigured()).thenReturn(true);
        lenient().when(llmProvider.getDefaultModel()).thenReturn("test-model");
        lenient().when(llmProvider.getProviderName()).thenReturn("test-provider");
    }

    private AgentLoopContext buildContext(PreIterationGuard guard) {
        return AgentLoopContext.builder()
                .provider("test-provider")
                .model("test-model")
                .systemPrompt("You are a test agent")
                .userPrompt("Hello")
                .maxIterations(10)
                .preIterationGuard(guard)
                .build();
    }

    // ── Streaming path tests ───────────────────────────────────────

    @Nested
    @DisplayName("executeStreaming - budget exhaustion")
    class StreamingBudgetTests {

        @Test
        @DisplayName("should stop immediately and call onComplete when guard returns false on first iteration")
        void shouldStopOnFirstIteration_ZeroBudget() {
            PreIterationGuard guard = ctx -> GuardResult.deny(AgentStopReason.BUDGET_EXHAUSTED, "test", "exhausted");
            AgentLoopContext context = buildContext(guard);

            AgentLoopResult result = agentLoopService.executeStreaming(context, callback);

            assertThat(result.success()).isFalse();
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.BUDGET_EXHAUSTED);
            assertThat(result.iterations()).isZero();

            // Verify callback.onComplete was called with correct finishReason
            ArgumentCaptor<CompletionResponse> captor = ArgumentCaptor.forClass(CompletionResponse.class);
            verify(callback).onComplete(captor.capture());
            assertThat(captor.getValue().finishReason()).isEqualTo("budget_exhausted");
            assertThat(captor.getValue().model()).isEqualTo("test-model");

            // LLM should never have been called
            verify(llmProvider, never()).complete(any());
            verify(llmProvider, never()).completeStreaming(any(), any());
        }

        @Test
        @DisplayName("should include empty content in onComplete when budget exhausted before any LLM call")
        void shouldIncludeEmptyContentWhenNoLLMCall() {
            PreIterationGuard guard = ctx -> GuardResult.deny(AgentStopReason.BUDGET_EXHAUSTED, "test", "exhausted");
            AgentLoopContext context = buildContext(guard);

            agentLoopService.executeStreaming(context, callback);

            ArgumentCaptor<CompletionResponse> captor = ArgumentCaptor.forClass(CompletionResponse.class);
            verify(callback).onComplete(captor.capture());
            // Content should be empty since no LLM call was made
            assertThat(captor.getValue().content()).isEmpty();
        }

        @Test
        @DisplayName("should proceed normally when guard is null (no budget constraint)")
        void shouldProceedWhenGuardIsNull() {
            AgentLoopContext context = buildContext(null);

            // Mock LLM to complete immediately via streaming
            doAnswer(invocation -> {
                StreamingCallback cb = invocation.getArgument(1);
                cb.onChunk("Hello there!");
                cb.onComplete(CompletionResponse.builder()
                        .content("Hello there!")
                        .finishReason("stop")
                        .model("test-model")
                        .build());
                return null;
            }).when(llmProvider).completeStreaming(any(), any());

            AgentLoopResult result = agentLoopService.executeStreaming(context, callback);

            assertThat(result.stopReason()).isEqualTo(AgentStopReason.COMPLETED);
            // LLM was called (guard was null, so no blocking)
            verify(llmProvider).completeStreaming(any(), any());
        }

        @Test
        @DisplayName("should proceed normally when guard always returns true")
        void shouldProceedWhenGuardAlwaysAllows() {
            AgentLoopContext context = buildContext(PreIterationGuard.ALWAYS_PROCEED);

            doAnswer(invocation -> {
                StreamingCallback cb = invocation.getArgument(1);
                cb.onChunk("All good");
                cb.onComplete(CompletionResponse.builder()
                        .content("All good")
                        .finishReason("stop")
                        .model("test-model")
                        .build());
                return null;
            }).when(llmProvider).completeStreaming(any(), any());

            AgentLoopResult result = agentLoopService.executeStreaming(context, callback);

            assertThat(result.stopReason()).isEqualTo(AgentStopReason.COMPLETED);
        }

        @Test
        @DisplayName("should prioritize shouldStop over budget check")
        void shouldPrioritizeUserStopOverBudget() {
            PreIterationGuard guard = ctx -> GuardResult.deny(AgentStopReason.BUDGET_EXHAUSTED, "test", "exhausted"); // budget exhausted
            AgentLoopContext context = buildContext(guard);
            when(callback.shouldStop()).thenReturn(true); // user also wants to stop

            AgentLoopResult result = agentLoopService.executeStreaming(context, callback);

            // shouldStop is checked first in the loop, so STOPPED_BY_USER takes priority
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.STOPPED_BY_USER);
        }

        @Test
        @DisplayName("should not call onError when budget is exhausted")
        void shouldNotCallOnErrorOnBudgetExhaustion() {
            PreIterationGuard guard = ctx -> GuardResult.deny(AgentStopReason.BUDGET_EXHAUSTED, "test", "exhausted");
            AgentLoopContext context = buildContext(guard);

            agentLoopService.executeStreaming(context, callback);

            // Budget exhaustion is a graceful stop, not an error
            verify(callback, never()).onError(anyString());
            verify(callback).onComplete(any(CompletionResponse.class));
        }

        @Test
        @DisplayName("should return zero iterations when budget exhausted immediately")
        void shouldReturnZeroIterationsOnImmediateExhaustion() {
            PreIterationGuard guard = ctx -> GuardResult.deny(AgentStopReason.BUDGET_EXHAUSTED, "test", "exhausted");
            AgentLoopContext context = buildContext(guard);

            AgentLoopResult result = agentLoopService.executeStreaming(context, callback);

            assertThat(result.iterations()).isZero();
            assertThat(result.toolResults()).isEmpty();
            assertThat(result.provider()).isEqualTo("test-provider");
            assertThat(result.model()).isEqualTo("test-model");
        }
    }

    // ── Sync path tests ───────────────────────────────────────────

    @Nested
    @DisplayName("execute (sync) - budget exhaustion")
    class SyncBudgetTests {

        @Test
        @DisplayName("should stop immediately when guard returns false on first iteration")
        void shouldStopOnFirstIteration_Sync() {
            PreIterationGuard guard = ctx -> GuardResult.deny(AgentStopReason.BUDGET_EXHAUSTED, "test", "exhausted");
            AgentLoopContext context = buildContext(guard);

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.stopReason()).isEqualTo(AgentStopReason.BUDGET_EXHAUSTED);
            assertThat(result.iterations()).isZero();

            // LLM should never have been called
            verify(llmProvider, never()).complete(any());
        }

        @Test
        @DisplayName("should proceed normally when guard is null (sync)")
        void shouldProceedWhenGuardIsNull_Sync() {
            AgentLoopContext context = buildContext(null);

            CompletionResponse completeResponse = CompletionResponse.builder()
                    .content("Hello!")
                    .finishReason("stop")
                    .model("test-model")
                    .build();
            when(llmProvider.complete(any())).thenReturn(completeResponse);

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.stopReason()).isEqualTo(AgentStopReason.COMPLETED);
        }

        @Test
        @DisplayName("should report BUDGET_EXHAUSTED with sync callback")
        void shouldReportBudgetExhaustedWithCallback() {
            PreIterationGuard guard = ctx -> GuardResult.deny(AgentStopReason.BUDGET_EXHAUSTED, "test", "exhausted");
            AgentLoopContext context = buildContext(guard);

            AgentLoopResult result = agentLoopService.execute(context, callback);

            assertThat(result.stopReason()).isEqualTo(AgentStopReason.BUDGET_EXHAUSTED);
            assertThat(result.iterations()).isZero();
        }

        @Test
        @DisplayName("should return proper result structure on budget exhaustion (sync)")
        void shouldReturnProperResultStructure() {
            PreIterationGuard guard = ctx -> GuardResult.deny(AgentStopReason.BUDGET_EXHAUSTED, "test", "exhausted");
            AgentLoopContext context = buildContext(guard);

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.stopReason()).isEqualTo(AgentStopReason.BUDGET_EXHAUSTED);
            assertThat(result.provider()).isEqualTo("test-provider");
            assertThat(result.model()).isEqualTo("test-model");
            assertThat(result.toolResults()).isEmpty();
            assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
        }
    }

    // ── Mid-execution budget exhaustion ──────────────────────────────

    @Nested
    @DisplayName("mid-execution budget exhaustion")
    class MidExecutionBudgetTests {

        @Test
        @DisplayName("streaming: should stop after N iterations when guard exhausts mid-loop")
        void shouldStopAfterNIterations_Streaming() {
            AtomicInteger budget = new AtomicInteger(2);
            PreIterationGuard guard = ctx -> budget.decrementAndGet() >= 0
                ? GuardResult.allow()
                : GuardResult.deny(AgentStopReason.BUDGET_EXHAUSTED, "test", "budget exhausted");
            AgentLoopContext context = buildContext(guard);

            // Mock LLM: each call returns a tool call to force loop continuation
            // (toolExecutionService is null, so tool returns failure result - loop still continues)
            ToolCall toolCall = new ToolCall("tc-1", "some_tool", Map.of(), null);
            doAnswer(invocation -> {
                StreamingCallback cb = invocation.getArgument(1);
                cb.onChunk("partial");
                cb.onComplete(CompletionResponse.builder()
                        .content("partial")
                        .finishReason("tool_use")
                        .model("test-model")
                        .toolCalls(List.of(toolCall))
                        .build());
                return null;
            }).when(llmProvider).completeStreaming(any(), any());

            AgentLoopResult result = agentLoopService.executeStreaming(context, callback);

            // Guard allowed 2 iterations, then blocked on 3rd
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.BUDGET_EXHAUSTED);
            assertThat(result.iterations()).isEqualTo(2);
            assertThat(result.success()).isFalse();
            assertThat(result.provider()).isEqualTo("test-provider");

            // LLM was called exactly 2 times
            verify(llmProvider, times(2)).completeStreaming(any(), any());

            // onComplete should have been called (budget exhaustion sends terminal event)
            verify(callback).onComplete(any(CompletionResponse.class));
        }

        @Test
        @DisplayName("sync: should stop after N iterations when guard exhausts mid-loop")
        void shouldStopAfterNIterations_Sync() {
            AtomicInteger budget = new AtomicInteger(2);
            PreIterationGuard guard = ctx -> budget.decrementAndGet() >= 0
                ? GuardResult.allow()
                : GuardResult.deny(AgentStopReason.BUDGET_EXHAUSTED, "test", "budget exhausted");
            AgentLoopContext context = buildContext(guard);

            // Mock LLM: each call returns tool calls to force loop continuation
            ToolCall toolCall = new ToolCall("tc-1", "some_tool", Map.of(), null);
            CompletionResponse response = CompletionResponse.builder()
                    .content("ok")
                    .finishReason("tool_use")
                    .model("test-model")
                    .toolCalls(List.of(toolCall))
                    .build();
            when(llmProvider.complete(any())).thenReturn(response);

            AgentLoopResult result = agentLoopService.execute(context);

            // Guard allowed 2 iterations, then blocked on 3rd
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.BUDGET_EXHAUSTED);
            assertThat(result.iterations()).isEqualTo(2);
            verify(llmProvider, times(2)).complete(any());
        }
    }

    // ── Error path tests ─────────────────────────────────────────────

    @Nested
    @DisplayName("error path handling")
    class ErrorPathTests {

        @Test
        @DisplayName("streaming: should call onComplete with error finishReason when LLM errors")
        void shouldCallOnCompleteOnStreamingError() {
            AgentLoopContext context = buildContext(null);

            // Mock LLM: streaming call triggers onError
            doAnswer(invocation -> {
                StreamingCallback cb = invocation.getArgument(1);
                cb.onError("LLM provider error");
                return null;
            }).when(llmProvider).completeStreaming(any(), any());

            AgentLoopResult result = agentLoopService.executeStreaming(context, callback);

            assertThat(result.success()).isFalse();
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.ERROR);

            // callback.onComplete MUST be called (this was the S3 bug)
            ArgumentCaptor<CompletionResponse> captor = ArgumentCaptor.forClass(CompletionResponse.class);
            verify(callback).onComplete(captor.capture());
            assertThat(captor.getValue().finishReason()).isEqualTo("error");
        }

        @Test
        @DisplayName("sync: should report ERROR stopReason when LLM throws exception")
        void shouldReportErrorStopReasonOnSyncException() {
            AgentLoopContext context = buildContext(null);

            when(llmProvider.complete(any())).thenThrow(new RuntimeException("LLM failed"));

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.success()).isFalse();
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.ERROR);
            assertThat(result.error()).contains("LLM failed");
            // toolResults should never be null even on failure
            assertThat(result.toolResults()).isNotNull();
        }

        @Test
        @DisplayName("streaming: error path should not call onError redundantly from loop level")
        void shouldNotCallOnErrorFromLoopOnStreamingError() {
            AgentLoopContext context = buildContext(null);

            doAnswer(invocation -> {
                StreamingCallback cb = invocation.getArgument(1);
                cb.onError("Provider down");
                return null;
            }).when(llmProvider).completeStreaming(any(), any());

            agentLoopService.executeStreaming(context, callback);

            // onComplete called exactly once (not duplicated)
            verify(callback, times(1)).onComplete(any(CompletionResponse.class));
        }

        @Test
        @DisplayName("failure result should have non-null collection fields")
        void failureResultShouldHaveNonNullCollections() {
            AgentLoopResult result = AgentLoopResult.failure("test error", 100L, "provider");

            assertThat(result.toolResults()).isNotNull().isEmpty();
            assertThat(result.conversationHistory()).isNotNull().isEmpty();
            assertThat(result.usagePerIteration()).isNotNull().isEmpty();
            assertThat(result.iterationDurations()).isNotNull().isEmpty();
            assertThat(result.finishReasonsPerIteration()).isNotNull().isEmpty();
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.ERROR);
        }
    }
}
