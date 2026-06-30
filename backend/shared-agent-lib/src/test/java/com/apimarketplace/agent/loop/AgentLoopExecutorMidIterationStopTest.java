package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.*;
import com.apimarketplace.agent.logging.AgentLogger;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.agent.tool.ToolExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Regression for F1.2 - when the user STOPs mid-iteration (between the LLM
 * stream completing and tool execution, or between two sequential tools), the
 * agent loop must:
 *
 * <ul>
 *   <li>return {@link AgentLoopExecutor.IterationResult#cancelled()} (distinct
 *       from {@code complete()} / {@code error()} so observability books the
 *       run as STOPPED_BY_USER, not COMPLETED, not ERROR);</li>
 *   <li>set {@link AgentStopReason#STOPPED_BY_USER} on the loop state;</li>
 *   <li>NOT start any further tool calls in the sequential branch;</li>
 *   <li>still persist the partial tool_results that DID execute, so the
 *       conversation history stays coherent (no orphan tool_use without
 *       tool_result, which would break the next message validation).</li>
 * </ul>
 *
 * <p>Companion to {@link StreamingCollectorShouldStopDelegationTest}: that one
 * pins the per-chunk stop signal during the LLM stream; this one pins the
 * between-step checks the executor adds on top.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentLoopExecutor - STOP between stream and tools / between sequential tools")
class AgentLoopExecutorMidIterationStopTest {

    @Mock private ToolExecutionService toolExecutionService;

    private AgentLoopExecutor sequentialExecutor;
    private LoopExecutionState state;

    @BeforeEach
    void setUp() {
        sequentialExecutor = new AgentLoopExecutor(
            toolExecutionService, AgentLogger.NOOP,
            Executors.newSingleThreadExecutor(), 5000L, false);
        state = new LoopExecutionState("run-cancel-test", 10, 0);
    }

    private ToolCall makeCall(String name, int idx) {
        return ToolCall.builder()
            .id("call_" + name + "_" + idx)
            .toolName(name)
            .arguments(Map.of("k", "v"))
            .index(idx)
            .build();
    }

    private ToolDefinition makeDef(String name) {
        return ToolDefinition.builder().id(name).name(name).description(name).build();
    }

    @Test
    @DisplayName("IterationResult.cancelled() exists, is distinct from error/complete, sets isCancelled=true")
    void iterationResultCancelledIsDistinct() {
        AgentLoopExecutor.IterationResult cancelled = AgentLoopExecutor.IterationResult.cancelled();
        AgentLoopExecutor.IterationResult error = AgentLoopExecutor.IterationResult.error();
        AgentLoopExecutor.IterationResult complete = AgentLoopExecutor.IterationResult.complete();

        assertThat(cancelled.isCancelled()).isTrue();
        assertThat(cancelled.isError()).isFalse();
        assertThat(cancelled.isComplete()).isFalse();
        assertThat(cancelled.shouldContinue()).isFalse();

        assertThat(error.isCancelled()).isFalse();
        assertThat(complete.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("Sequential tool loop bails after current tool when callback signals stop - pre-fix executed all 3")
    void sequentialLoopBreaksOnStopBetweenTools() throws Exception {
        ToolCall c1 = makeCall("agent", 0);
        ToolCall c2 = makeCall("catalog", 1);
        ToolCall c3 = makeCall("table", 2);
        List<ToolCall> calls = List.of(c1, c2, c3);
        List<ToolDefinition> tools = List.of(makeDef("agent"), makeDef("catalog"), makeDef("table"));

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger toolsCompleted = new AtomicInteger(0);
        StreamingCallback cb = new StreamingCallback() {
            @Override public void onChunk(String c) {}
            @Override public void onToolCall(ToolCall t) {}
            @Override public void onComplete(CompletionResponse r) {}
            @Override public void onError(String e) {}
            @Override public boolean shouldStop() { return stop.get(); }
        };

        // Flip stop=true after 1 tool has executed - 2nd iteration's pre-loop check
        // will see stop=true and break before running tool[1].
        when(toolExecutionService.executeTool(any(), any(), any(), any()))
            .thenAnswer(inv -> {
                ToolCall tc = inv.getArgument(0);
                int n = toolsCompleted.incrementAndGet();
                if (n == 1) {
                    stop.set(true);
                }
                return ToolResult.success(tc, "ok-" + tc.toolName());
            });

        AgentLoopContext ctx = AgentLoopContext.builder().tenantId("t1").userPrompt("p").build();
        var method = AgentLoopExecutor.class.getDeclaredMethod(
            "executeToolCalls", List.class, List.class, AgentLoopContext.class, LoopExecutionState.class,
            StreamingCallback.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ToolResult> results = (List<ToolResult>) method.invoke(sequentialExecutor, calls, tools, ctx, state, cb);

        assertThat(results)
            .as("only the 1st tool should have run before stop - 2nd and 3rd skipped")
            .hasSize(1);
        assertThat(results.get(0).toolCall().toolName()).isEqualTo("agent");
    }

    @Test
    @DisplayName("Null callback - sequential loop runs ALL tools (regression: callback param must be optional)")
    void nullCallbackKeepsLoopRunning() throws Exception {
        ToolCall c1 = makeCall("agent", 0);
        ToolCall c2 = makeCall("catalog", 1);
        List<ToolCall> calls = List.of(c1, c2);
        List<ToolDefinition> tools = List.of(makeDef("agent"), makeDef("catalog"));

        when(toolExecutionService.executeTool(any(), any(), any(), any()))
            .thenAnswer(inv -> ToolResult.success(inv.getArgument(0), "ok"));

        AgentLoopContext ctx = AgentLoopContext.builder().tenantId("t1").userPrompt("p").build();
        var method = AgentLoopExecutor.class.getDeclaredMethod(
            "executeToolCalls", List.class, List.class, AgentLoopContext.class, LoopExecutionState.class,
            StreamingCallback.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ToolResult> results = (List<ToolResult>) method.invoke(sequentialExecutor, calls, tools, ctx, state, null);

        assertThat(results).as("null callback never trips the cancel guard").hasSize(2);
    }

    @Test
    @DisplayName("handleLoopStop cancel - STOPPED_BY_USER overrides MAX_ITERATIONS (audit P0.3)")
    void cancelDuringLoopWrapUpOverridesMaxIterations() throws Exception {
        // Reach into handleLoopStop directly: if the wrap-up final-call's collector
        // sees a STOP signal, the run must be classified STOPPED_BY_USER, not
        // MAX_ITERATIONS. Pre-fix, MAX_ITERATIONS was set unconditionally and the
        // user STOP was lost in observability.
        com.apimarketplace.agent.provider.LLMProvider mockProvider =
            org.mockito.Mockito.mock(com.apimarketplace.agent.provider.LLMProvider.class);

        // The wrap-up call uses streaming when callback != null. Provider is invoked
        // but does nothing; the StreamingCollector wraps our stop-on callback so
        // the collector.shouldStop() returns true after the call.
        org.mockito.Mockito.doNothing().when(mockProvider)
            .completeStreaming(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        StreamingCallback alwaysStop = new StreamingCallback() {
            @Override public void onChunk(String c) {}
            @Override public void onToolCall(ToolCall t) {}
            @Override public void onComplete(CompletionResponse r) {}
            @Override public void onError(String e) {}
            @Override public boolean shouldStop() { return true; }
        };

        AgentLoopExecutor.LoopCheckResult loopCheck = new AgentLoopExecutor.LoopCheckResult(
            true, false, "workflow", List.of("identical-loop-warning"));
        AgentLoopContext ctx = AgentLoopContext.builder().tenantId("t1").userPrompt("p").build();

        var method = AgentLoopExecutor.class.getDeclaredMethod(
            "handleLoopStop",
            com.apimarketplace.agent.provider.LLMProvider.class,
            String.class, AgentLoopContext.class, String.class, LoopExecutionState.class,
            List.class, AgentLoopExecutor.LoopCheckResult.class, StreamingCallback.class);
        method.setAccessible(true);

        AgentLoopExecutor.IterationResult result = (AgentLoopExecutor.IterationResult)
            method.invoke(sequentialExecutor, mockProvider, "model-x", ctx, "sys", state,
                List.of(makeCall("workflow", 0)), loopCheck, alwaysStop);

        assertThat(result.isCancelled())
            .as("handleLoopStop must return cancelled() when callback signals stop")
            .isTrue();
        assertThat(state.getStopReason())
            .as("STOPPED_BY_USER must override MAX_ITERATIONS on user cancel")
            .isEqualTo(AgentStopReason.STOPPED_BY_USER);
    }

    @Test
    @DisplayName("Stream error AFTER STOP signal classifies as STOPPED_BY_USER, not ERROR (regression: agent-fleet showed cancellations as 'execution error')")
    void streamErrorAfterStopClassifiesAsCancelled() throws Exception {
        // Build a provider mock that fails the stream (simulating an IOException
        // because the user STOP closed the underlying socket) and a callback
        // that reports shouldStop=true. Pre-fix, the loop returned
        // IterationResult.error() → state.stopReason=ERROR → status=FAILED →
        // dashboard shows red AlertCircle "Execution error". Post-fix the STOP
        // wins and we classify as STOPPED_BY_USER.
        com.apimarketplace.agent.provider.LLMProvider mockProvider =
            org.mockito.Mockito.mock(com.apimarketplace.agent.provider.LLMProvider.class);

        // The provider invokes onError on the wrapped collector - same effect
        // as a real network failure during the stream.
        org.mockito.Mockito.doAnswer(inv -> {
            StreamingCallback wrappedCb = inv.getArgument(1);
            wrappedCb.onError("Streaming error: connection reset");
            return null;
        }).when(mockProvider).completeStreaming(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        StreamingCallback userStop = new StreamingCallback() {
            @Override public void onChunk(String c) {}
            @Override public void onToolCall(ToolCall t) {}
            @Override public void onComplete(CompletionResponse r) {}
            @Override public void onError(String e) {}
            @Override public boolean shouldStop() { return true; }
        };

        AgentLoopContext ctx = AgentLoopContext.builder().tenantId("t1").userPrompt("p").build();
        AgentLoopExecutor.IterationResult result = sequentialExecutor.processIteration(
            mockProvider, "model-x", ctx, java.util.List.of(), state, "sys", userStop);

        assertThat(result.isCancelled())
            .as("when STOP is signaled, stream errors must be classified as cancellation, not ERROR")
            .isTrue();
        assertThat(result.isError()).isFalse();
        assertThat(state.getStopReason())
            .as("state must reflect STOPPED_BY_USER so observability (agent-fleet) shows the hand icon, not red")
            .isEqualTo(AgentStopReason.STOPPED_BY_USER);
    }

    @Test
    @DisplayName("Stream error WITHOUT STOP still classifies as ERROR (regression guard: don't swallow real failures)")
    void streamErrorWithoutStopStaysError() throws Exception {
        com.apimarketplace.agent.provider.LLMProvider mockProvider =
            org.mockito.Mockito.mock(com.apimarketplace.agent.provider.LLMProvider.class);
        org.mockito.Mockito.doAnswer(inv -> {
            StreamingCallback wrappedCb = inv.getArgument(1);
            wrappedCb.onError("Streaming error: 503 service unavailable");
            return null;
        }).when(mockProvider).completeStreaming(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        // shouldStop=false - this is a real provider failure, NOT a user cancel.
        StreamingCallback noStop = new StreamingCallback() {
            @Override public void onChunk(String c) {}
            @Override public void onToolCall(ToolCall t) {}
            @Override public void onComplete(CompletionResponse r) {}
            @Override public void onError(String e) {}
            @Override public boolean shouldStop() { return false; }
        };

        AgentLoopContext ctx = AgentLoopContext.builder().tenantId("t1").userPrompt("p").build();
        AgentLoopExecutor.IterationResult result = sequentialExecutor.processIteration(
            mockProvider, "model-x", ctx, java.util.List.of(), state, "sys", noStop);

        assertThat(result.isError())
            .as("real provider failures (no STOP signal) must remain classified as ERROR")
            .isTrue();
        assertThat(result.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("Parallel branch - pre-flight STOP skips the parallel submit (audit P0.2)")
    void parallelBranchPreFlightStopSkipsSubmit() throws Exception {
        AgentLoopExecutor parallelExecutor = new AgentLoopExecutor(
            toolExecutionService, AgentLogger.NOOP,
            Executors.newFixedThreadPool(4), 5000L, /*parallel=*/true);

        // 2 regular (parallelizable) tools so we hit the parallel-submit branch
        ToolCall c1 = makeCall("agent", 0);
        ToolCall c2 = makeCall("catalog", 1);
        List<ToolCall> calls = List.of(c1, c2);
        List<ToolDefinition> tools = List.of(makeDef("agent"), makeDef("catalog"));

        // Stop is true from the start - pre-flight check skips submit entirely
        StreamingCallback alwaysStop = new StreamingCallback() {
            @Override public void onChunk(String c) {}
            @Override public void onToolCall(ToolCall t) {}
            @Override public void onComplete(CompletionResponse r) {}
            @Override public void onError(String e) {}
            @Override public boolean shouldStop() { return true; }
        };

        AgentLoopContext ctx = AgentLoopContext.builder().tenantId("t1").userPrompt("p").build();
        var method = AgentLoopExecutor.class.getDeclaredMethod(
            "executeToolCalls", List.class, List.class, AgentLoopContext.class, LoopExecutionState.class,
            StreamingCallback.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ToolResult> results = (List<ToolResult>) method.invoke(parallelExecutor, calls, tools, ctx, state, alwaysStop);

        assertThat(results)
            .as("pre-flight STOP must skip the entire parallel batch - no tools executed")
            .isEmpty();
        // toolExecutionService is a mock; verify nothing was actually called
        org.mockito.Mockito.verifyNoInteractions(toolExecutionService);
    }
}
