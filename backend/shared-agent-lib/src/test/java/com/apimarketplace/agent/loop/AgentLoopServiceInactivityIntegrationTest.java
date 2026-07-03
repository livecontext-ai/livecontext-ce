package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.*;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.agent.tool.ToolExecutionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration of the in-process inactivity watchdog through the REAL
 * {@link AgentLoopService} loop (not the static helpers in isolation). A controllable mock provider
 * stands in for the LLM: a real model can't be made to stall on demand, so a provider that yields by
 * polling {@code shouldStop()} (exactly as the streaming read loop does per line) is the faithful way
 * to exercise the silence path. Verifies the headline contract:
 *
 * <ul>
 *   <li>a stalled agent is stopped at the configured window with {@code INACTIVITY_TIMEOUT};</li>
 *   <li>a working agent (streaming, or iterating across tool calls) is NEVER killed, regardless of
 *       total runtime exceeding the window;</li>
 *   <li>a real user cancel is NOT relabeled as inactivity;</li>
 *   <li>window {@code <= 0} disables the watchdog entirely.</li>
 * </ul>
 */
@DisplayName("AgentLoopService - inactivity watchdog (end-to-end through the real loop)")
@ExtendWith(MockitoExtension.class)
class AgentLoopServiceInactivityIntegrationTest {

    @Mock private LLMProviderFactory providerFactory;
    @Mock private LLMProvider llmProvider;
    @Mock private StreamingCallback userCallback;

    private AgentLoopService svc;

    void setUpProvider() {
        svc = new AgentLoopService(providerFactory, null, null, null);
        lenient().when(providerFactory.getProvider(anyString())).thenReturn(llmProvider);
        lenient().when(llmProvider.isConfigured()).thenReturn(true);
        lenient().when(llmProvider.getDefaultModel()).thenReturn("test-model");
        lenient().when(llmProvider.getProviderName()).thenReturn("test-provider");
    }

    private AgentLoopContext ctx(Integer inactivitySeconds) {
        return AgentLoopContext.builder()
            .provider("test-provider")
            .model("test-model")
            .systemPrompt("You are a test agent")
            .userPrompt("Hello")
            .maxIterations(10)
            .inactivityTimeout(inactivitySeconds)
            .build();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Context carrying the per-agent window ONLY via the production __inactivityTimeoutSeconds__ credential. */
    private AgentLoopContext ctxWithCredential(int inactivitySeconds) {
        return AgentLoopContext.builder()
            .provider("test-provider")
            .model("test-model")
            .systemPrompt("You are a test agent")
            .userPrompt("Hello")
            .maxIterations(10)
            .credentials(Map.of("__inactivityTimeoutSeconds__", inactivitySeconds))
            .build();
    }

    @Test
    @Timeout(20)
    @DisplayName("an out-of-contract per-agent credential window (1-9s) is IGNORED through the real loop - a stray small value cannot arm a seconds-scale watchdog that false-kills a healthy agent")
    void outOfContractCredentialWindowIgnoredThroughRealLoop() {
        setUpProvider();
        // Agent stalls 1.5s then completes. The credential carries an out-of-contract
        // window (1s, in the rejected 1-9 band): resolveInactivityWindowMs falls back to
        // the 5-min default (the credential channel validates 10-7200 to stop a stray raw
        // producer value from arming a seconds-scale watchdog - see
        // AgentLoopServiceInactivityWiringTest.belowContractOverrideIgnored), so the
        // watchdog does NOT trip and the healthy agent completes normally. This differs by
        // design from the context field (.inactivityTimeout, set by trusted internal code),
        // which accepts a 1s window - see silentAgentStoppedWithInactivityTimeout.
        doAnswer(inv -> {
            StreamingCallback cb = inv.getArgument(1);
            sleep(1500);
            cb.onComplete(CompletionResponse.builder().content("late").finishReason("stop").model("test-model").build());
            return null;
        }).when(llmProvider).completeStreaming(any(), any());

        AgentLoopResult result = svc.executeStreaming(ctxWithCredential(1), userCallback);

        assertThat(result.stopReason())
            .as("a below-contract credential window must NOT arm a seconds-scale watchdog through the real loop")
            .isEqualTo(AgentStopReason.COMPLETED);
    }

    // ── A stalled agent IS stopped at the window ──────────────────────────────

    @Test
    @Timeout(15)
    @DisplayName("a streaming agent that goes silent is stopped with INACTIVITY_TIMEOUT at the window")
    void silentAgentStoppedWithInactivityTimeout() {
        setUpProvider();
        // Provider yields nothing and only polls shouldStop() - i.e. it hangs. The watchdog (1s
        // window) must flip shouldStop() so this returns, and the loop must reclassify the stop.
        doAnswer(inv -> {
            StreamingCallback cb = inv.getArgument(1);
            long deadline = System.currentTimeMillis() + 8000; // safety net so a broken watchdog can't hang
            while (!cb.shouldStop() && System.currentTimeMillis() < deadline) {
                sleep(20);
            }
            cb.onComplete(CompletionResponse.builder().content("").finishReason("stop").model("test-model").build());
            return null;
        }).when(llmProvider).completeStreaming(any(), any());

        AgentLoopResult result = svc.executeStreaming(ctx(1), userCallback);

        assertThat(result.stopReason()).isEqualTo(AgentStopReason.INACTIVITY_TIMEOUT);
        assertThat(result.success()).isFalse();
    }

    // ── A WORKING agent is never killed (the user's core rule) ────────────────

    @Test
    @Timeout(15)
    @DisplayName("a streaming agent that keeps producing tokens is NOT killed even past the window")
    void streamingAgentThatKeepsProducingIsNotKilled() {
        setUpProvider();
        // Emit a token every 250ms for ~2.5s (well past the 2s window) - each token resets the clock.
        // The 250ms cadence vs 2s window gives a wide (1.75s) margin so a CI scheduling pause between
        // tokens cannot false-trip the watchdog.
        doAnswer(inv -> {
            StreamingCallback cb = inv.getArgument(1);
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                cb.onChunk("tok" + i);
                all.append("tok").append(i);
                sleep(250);
            }
            cb.onComplete(CompletionResponse.builder().content(all.toString())
                .finishReason("stop").model("test-model").build());
            return null;
        }).when(llmProvider).completeStreaming(any(), any());

        AgentLoopResult result = svc.executeStreaming(ctx(2), userCallback);

        assertThat(result.stopReason())
            .as("a continuously-streaming agent must finish normally, never INACTIVITY_TIMEOUT")
            .isEqualTo(AgentStopReason.COMPLETED);
        assertThat(result.success()).isTrue();
    }

    @Test
    @Timeout(20)
    @DisplayName("a long agent that keeps streaming ACROSS iterations is not killed by total runtime")
    void activeAgentAcrossIterationsNotKilledByTotalRuntime() {
        setUpProvider();
        // Three iterations, each streams a token then (for the first two) requests a tool, so the loop
        // continues. Total runtime (~1.8s) exceeds the 1s window, but every iteration shows activity.
        AtomicInteger calls = new AtomicInteger(0);
        ToolCall toolCall = new ToolCall("tc-1", "some_tool", Map.of(), null);
        doAnswer(inv -> {
            StreamingCallback cb = inv.getArgument(1);
            int n = calls.incrementAndGet();
            cb.onChunk("step" + n);
            sleep(800); // < 2s window per gap, but cumulative across 3 iterations (~2.4s) > window
            CompletionResponse.CompletionResponseBuilder b = CompletionResponse.builder()
                .content("step" + n).model("test-model");
            if (n < 3) {
                b.finishReason("tool_use").toolCalls(List.of(toolCall));
            } else {
                b.finishReason("stop");
            }
            cb.onComplete(b.build());
            return null;
        }).when(llmProvider).completeStreaming(any(), any());

        AgentLoopResult result = svc.executeStreaming(ctx(2), userCallback);

        assertThat(result.stopReason())
            .as("an agent active every iteration must not be killed for inactivity")
            .isNotEqualTo(AgentStopReason.INACTIVITY_TIMEOUT);
        assertThat(result.iterations()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @Timeout(25)
    @DisplayName("a slow MULTI-TOOL batch in one iteration is NOT killed - per-tool onKeepAlive resets the clock")
    void slowMultiToolBatchNotKilled() {
        // The exact MAJOR-#2 path: TWO sequential-only (workflow) tools in ONE iteration, each
        // running LONGER than the inactivity window. onToolResult only fires AFTER the whole batch,
        // so only the per-tool onKeepAlive reset keeps the between-tool shouldStop() check from
        // tripping. Without onKeepAlive, the 2nd tool's pre-check sees ~tool1-duration of idle
        // (> window) and the working agent is wrongly killed -> this test FAILS if onKeepAlive is removed.
        AtomicInteger toolRuns = new AtomicInteger(0);
        ToolExecutionService sleepingTools = new ToolExecutionService() {
            @Override public ToolResult executeTool(ToolCall tc, ToolDefinition td, String tenantId, Map<String, Object> creds) {
                toolRuns.incrementAndGet();
                sleep(1200); // each tool runs 1.2s, longer than the 1s window
                return ToolResult.builder().toolCall(tc).success(true).content("tool-done").build();
            }
            @Override public boolean isToolAvailable(ToolDefinition td, String tenantId) { return true; }
        };
        AgentLoopService svcWithTools = new AgentLoopService(providerFactory, sleepingTools, null, null);
        lenient().when(providerFactory.getProvider(anyString())).thenReturn(llmProvider);
        lenient().when(llmProvider.isConfigured()).thenReturn(true);
        lenient().when(llmProvider.getDefaultModel()).thenReturn("test-model");
        lenient().when(llmProvider.getProviderName()).thenReturn("test-provider");

        ToolDefinition workflowTool = ToolDefinition.builder().name("workflow").build();
        AtomicInteger calls = new AtomicInteger(0);
        doAnswer(inv -> {
            StreamingCallback cb = inv.getArgument(1);
            int n = calls.incrementAndGet();
            cb.onChunk("iter" + n);
            CompletionResponse.CompletionResponseBuilder b =
                CompletionResponse.builder().content("iter" + n).model("test-model");
            if (n == 1) {
                b.finishReason("tool_use").toolCalls(List.of(
                    new ToolCall("tc-1", "workflow", Map.of(), 0),
                    new ToolCall("tc-2", "workflow", Map.of(), 1)));
            } else {
                b.finishReason("stop");
            }
            cb.onComplete(b.build());
            return null;
        }).when(llmProvider).completeStreaming(any(), any());

        AgentLoopContext context = AgentLoopContext.builder()
            .provider("test-provider").model("test-model")
            .systemPrompt("You are a test agent").userPrompt("Hello")
            .maxIterations(10)
            .inactivityTimeout(1)              // 1s window; EACH tool runs 1.2s (cumulative 2.4s)
            .tools(List.of(workflowTool))
            .autoDiscoverTools(false)
            .build();

        AgentLoopResult result = svcWithTools.executeStreaming(context, userCallback);

        // Load-bearing assertion: BOTH tools must have executed. Without the per-tool onKeepAlive,
        // the between-tool watchdog check trips after tool1 and the sequential batch breaks, so tool2
        // is silently SKIPPED (toolRuns == 1) - a healthy agent losing work. With onKeepAlive the
        // idle clock resets after each tool, so tool2 still runs (toolRuns == 2).
        assertThat(toolRuns.get())
            .as("both sequential tools must run; a spurious inactivity trip mid-batch would skip the 2nd")
            .isEqualTo(2);
        assertThat(result.stopReason()).isNotEqualTo(AgentStopReason.INACTIVITY_TIMEOUT);
    }

    // ── A real user cancel must not be relabeled inactivity ───────────────────

    @Test
    @Timeout(15)
    @DisplayName("a real user cancel during streaming stays STOPPED_BY_USER (not relabeled inactivity)")
    void userCancelStaysStoppedByUser() {
        setUpProvider();
        // The user callback signals stop immediately - a cancel, not silence. The loop's first
        // shouldStop() check returns STOPPED_BY_USER before the provider is even called; the watchdog
        // short-circuits on the user's stop so idleTripped stays false and reclassify is a no-op.
        when(userCallback.shouldStop()).thenReturn(true);

        AgentLoopResult result = svc.executeStreaming(ctx(1), userCallback);

        assertThat(result.stopReason())
            .as("a genuine user cancel must NOT be reclassified as inactivity")
            .isEqualTo(AgentStopReason.STOPPED_BY_USER);
    }

    // ── window <= 0 disables the watchdog ─────────────────────────────────────

    @Test
    @Timeout(15)
    @DisplayName("inactivityTimeout=0 disables the watchdog - a silent agent is NOT inactivity-killed")
    void zeroWindowDisablesWatchdog() {
        setUpProvider();
        // With the watchdog disabled, the only shouldStop source is the (false) user callback, so the
        // provider runs to its own short safety stop and completes - never INACTIVITY_TIMEOUT.
        doAnswer(inv -> {
            StreamingCallback cb = inv.getArgument(1);
            long deadline = System.currentTimeMillis() + 1500;
            while (!cb.shouldStop() && System.currentTimeMillis() < deadline) {
                sleep(20);
            }
            cb.onComplete(CompletionResponse.builder().content("done").finishReason("stop").model("test-model").build());
            return null;
        }).when(llmProvider).completeStreaming(any(), any());

        AgentLoopResult result = svc.executeStreaming(ctx(0), userCallback);

        assertThat(result.stopReason()).isNotEqualTo(AgentStopReason.INACTIVITY_TIMEOUT);
        assertThat(result.stopReason()).isEqualTo(AgentStopReason.COMPLETED);
    }
}
