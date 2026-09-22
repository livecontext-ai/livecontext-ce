package com.apimarketplace.agent.client.dto.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model-execution-link helpers that keep the billed identity separate from
 * the CLI bridge execution identity. {@code withExecutionTarget} swaps the pair
 * for the bridge dispatch; {@code withBilledIdentity} re-stamps the billed pair
 * onto the response so billing stays on the billed price.
 */
@DisplayName("Execution-link DTO helpers")
class ExecutionLinkDtoHelpersTest {

    @Test
    @DisplayName("withExecutionTarget swaps provider+model and preserves every other field")
    void withExecutionTargetSwapsIdentityAndPreservesRest() {
        AgentExecutionRequestDto base = requestDto("anthropic", "claude-opus-4-8");

        AgentExecutionRequestDto swapped = base.withExecutionTarget("codex", "gpt-5.3-codex");

        assertThat(swapped.provider()).isEqualTo("codex");
        assertThat(swapped.model()).isEqualTo("gpt-5.3-codex");
        // Untouched fields survive the copy.
        assertThat(swapped.prompt()).isEqualTo(base.prompt());
        assertThat(swapped.tenantId()).isEqualTo(base.tenantId());
        assertThat(swapped.executionId()).isEqualTo(base.executionId());
    }

    @Test
    @DisplayName("withExecutionTarget is a no-op (same instance) when provider and model are unchanged")
    void withExecutionTargetNoOpWhenUnchanged() {
        AgentExecutionRequestDto base = requestDto("anthropic", "claude-opus-4-8");
        assertThat(base.withExecutionTarget("anthropic", "claude-opus-4-8")).isSameAs(base);
    }

    @Test
    @DisplayName("withBilledIdentity re-stamps provider+model and preserves the execution payload")
    void withBilledIdentityReStampsAndPreservesPayload() {
        AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
            true, "answer", "answer", List.of(), 1, Map.of("totalTokens", 42),
            null, 10, "codex", "gpt-5.3-codex", List.of(),
            "COMPLETED", Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);

        AgentExecutionResponseDto billed =
            bridgeResponse.withBilledIdentity("anthropic", "claude-opus-4-8", "codex");

        assertThat(billed.provider()).isEqualTo("anthropic");
        assertThat(billed.model()).isEqualTo("claude-opus-4-8");
        // Codex and the Anthropic API count the same way (a cached SUBSET inside the
        // prompt total), so there is nothing to convert and the payload is untouched.
        assertThat(billed.content()).isEqualTo("answer");
        assertThat(billed.totalUsage()).isEqualTo(Map.of("totalTokens", 42));
        assertThat(billed.success()).isTrue();
    }

    @Test
    @DisplayName("withBilledIdentity strips the Claude Code bridge's cache out of the prompt total, so the billed identity is not charged for it twice")
    void withBilledIdentityConvertsInclusivePromptToTheBilledConvention() {
        // Shape taken from a real production row: the bridge reports
        // promptTokens = 6 plain input + 18945 cache write + 79368 cache read.
        Map<String, Object> bridgeUsage = Map.of(
            "promptTokens", 98319,
            "completionTokens", 1915,
            "totalTokens", 100234,
            "cacheCreationInputTokens", 18945,
            "cacheReadInputTokens", 79368);
        AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
            true, "answer", "answer", List.of(), 1, bridgeUsage,
            null, 10, "claude-code", "claude-fable-5", List.of(),
            "COMPLETED", Map.of(), List.of(bridgeUsage), List.of(), List.of(), List.of(), List.of(), null);

        AgentExecutionResponseDto billed =
            bridgeResponse.withBilledIdentity("anthropic", "claude-fable-5", "claude-code");

        // The Anthropic API reports cache tokens BESIDE the prompt, so the billed prompt
        // is the plain input only. Left inclusive, billing would charge the 98325 cached
        // tokens at full input rate and then again on their own discounted line.
        assertThat(billed.totalUsage()).containsEntry("promptTokens", 6);
        assertThat(billed.totalUsage()).containsEntry("totalTokens", 1921);
        // The cache counters themselves are untouched - they are what gets billed at the
        // cache rate, and only their double-count inside the prompt total was wrong.
        assertThat(billed.totalUsage()).containsEntry("cacheCreationInputTokens", 18945);
        assertThat(billed.totalUsage()).containsEntry("cacheReadInputTokens", 79368);
        assertThat(billed.totalUsage()).containsEntry("completionTokens", 1915);
        // Per-iteration usage feeds the same observability rows, so it converts too.
        assertThat(billed.usagePerIteration()).hasSize(1);
        assertThat(billed.usagePerIteration().get(0)).containsEntry("promptTokens", 6);
    }

    @Test
    @DisplayName("withBilledIdentity never drives the prompt total negative when the counters disagree")
    void withBilledIdentityClampsAtZero() {
        Map<String, Object> inconsistent = Map.of(
            "promptTokens", 100,
            "completionTokens", 10,
            "totalTokens", 110,
            "cacheCreationInputTokens", 0,
            "cacheReadInputTokens", 5000);
        AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
            true, "answer", "answer", List.of(), 1, inconsistent,
            null, 10, "claude-code", "claude-fable-5", List.of(),
            "COMPLETED", Map.of(), null, List.of(), List.of(), List.of(), List.of(), null);

        AgentExecutionResponseDto billed =
            bridgeResponse.withBilledIdentity("anthropic", "claude-fable-5", "claude-code");

        assertThat(billed.totalUsage()).containsEntry("promptTokens", 0);
        assertThat(billed.totalUsage()).containsEntry("totalTokens", 10);
        assertThat(billed.usagePerIteration()).isNull();
    }

    @Test
    @DisplayName("withBilledIdentity is a no-op (same instance) when provider and model are unchanged")
    void withBilledIdentityNoOpWhenUnchanged() {
        AgentExecutionResponseDto response = new AgentExecutionResponseDto(
            true, "answer", "answer", List.of(), 1, Map.of(),
            null, 10, "anthropic", "claude-opus-4-8", List.of(),
            "COMPLETED", Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
        assertThat(response.withBilledIdentity("anthropic", "claude-opus-4-8", "anthropic")).isSameAs(response);
    }

    @Test
    @DisplayName("withRestrictedToolset(true) flags the run via the credentials map and preserves existing entries")
    void withRestrictedToolsetTrueAddsCredentialFlag() {
        AgentExecutionRequestDto base = requestDtoWithCredentials(Map.of("__executionId__", "exec-1"));

        AgentExecutionRequestDto restricted = base.withRestrictedToolset(true);

        assertThat(restricted.credentials())
            .containsEntry("__restrictedToolset__", true)   // the bridge reads this to lock the CLI down
            .containsEntry("__executionId__", "exec-1");    // existing markers survive
        // Identity untouched - the flag is purely an execution-transport hint.
        assertThat(restricted.provider()).isEqualTo(base.provider());
        assertThat(restricted.model()).isEqualTo(base.model());
        // The original is not mutated (immutability of the source credentials).
        assertThat(base.credentials()).doesNotContainKey("__restrictedToolset__");
    }

    @Test
    @DisplayName("withRestrictedToolset(false) is a no-op (same instance) so direct bridge runs stay unrestricted")
    void withRestrictedToolsetFalseNoOp() {
        AgentExecutionRequestDto base = requestDto("anthropic", "claude-opus-4-8");
        assertThat(base.withRestrictedToolset(false)).isSameAs(base);
    }

    @Test
    @DisplayName("hasNoVisibleOutput is true for a bare failure (no content, no tools, no thinking)")
    void hasNoVisibleOutputTrueForBareFailure() {
        AgentExecutionResponseDto failure = new AgentExecutionResponseDto(
            false, null, null, List.of(), 0, Map.of(), "CLI crashed", 10, "codex", "gpt-5.3-codex",
            List.of(), "ERROR", Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
        assertThat(failure.hasNoVisibleOutput()).isTrue();
    }

    @Test
    @DisplayName("a turn the user stopped is flagged as cancelled, so the silent retry never fires on it")
    void cancelledByUserIsRecognised() {
        // Stop pressed in the first seconds: nothing reached the screen, so the run reads as
        // "invisible" and the execution-link fallback would re-run the WHOLE turn on the billed
        // pair's direct API - a full paid turn for a chat the user just cancelled, with no trace
        // the user could connect it to. Intent, not output, decides this one.
        AgentExecutionResponseDto stopped = new AgentExecutionResponseDto(
            false, null, null, List.of(), 0, Map.of(), null, 8000, "codex", "gpt-5.6-sol",
            List.of(), "STOPPED_BY_USER", Map.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), null);

        assertThat(stopped.hasNoVisibleOutput()).isTrue();
        assertThat(stopped.wasCancelledByUser()).isTrue();
    }

    @Test
    @DisplayName("a SYSTEM cancellation is not a user stop - the invisible retry it was built for still runs")
    void systemCancellationIsNotAUserStop() {
        // CANCELLED means a deploy or a scale-down ended the run. Nobody asked for the
        // turn to stop, so retrying it on the billed pair is the rescue this fallback
        // exists to provide - blocking it here would strand the user for an operational
        // event they never saw.
        AgentExecutionResponseDto cancelled = new AgentExecutionResponseDto(
            false, null, null, List.of(), 0, Map.of(), null, 8000, "codex", "gpt-5.6-sol",
            List.of(), "CANCELLED", Map.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), null);

        assertThat(cancelled.wasCancelledByUser()).isFalse();
    }

    @Test
    @DisplayName("a genuine crash is NOT a cancellation - the silent retry it was built for still runs")
    void crashIsNotACancellation() {
        AgentExecutionResponseDto crashed = new AgentExecutionResponseDto(
            false, null, null, List.of(), 0, Map.of(), "CLI crashed", 10, "codex", "gpt-5.6-sol",
            List.of(), "ERROR", Map.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), null);

        assertThat(crashed.wasCancelledByUser()).isFalse();
    }

    @Test
    @DisplayName("a missing stop reason is not treated as a cancellation")
    void nullStopReasonIsNotACancellation() {
        AgentExecutionResponseDto unknown = new AgentExecutionResponseDto(
            false, null, null, List.of(), 0, Map.of(), "no response", 10, "codex", "gpt-5.6-sol",
            List.of(), null, Map.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), null);

        assertThat(unknown.wasCancelledByUser()).isFalse();
    }

    @Test
    @DisplayName("hasNoVisibleOutput is false when content already reached the user")
    void hasNoVisibleOutputFalseWithContent() {
        AgentExecutionResponseDto partial = new AgentExecutionResponseDto(
            false, "partial answer", "partial answer", List.of(), 0, Map.of(), "CLI crashed mid-stream",
            10, "codex", "gpt-5.3-codex", List.of(), "ERROR", Map.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), null);
        assertThat(partial.hasNoVisibleOutput()).isFalse();
    }

    @Test
    @DisplayName("hasNoVisibleOutput is false when tool results already reached the user")
    void hasNoVisibleOutputFalseWithToolResults() {
        AgentExecutionResponseDto withTool = new AgentExecutionResponseDto(
            false, null, null, List.of(Map.of("tool", "search")), 0, Map.of(), "CLI crashed",
            10, "codex", "gpt-5.3-codex", List.of(), "ERROR", Map.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), null);
        assertThat(withTool.hasNoVisibleOutput()).isFalse();
    }

    @Test
    @DisplayName("hasNoVisibleOutput is false when extended-thinking content was already streamed - the bridge publishes thinking deltas live, before the final answer")
    void hasNoVisibleOutputFalseWithThinkingSections() {
        AgentExecutionResponseDto withThinking = new AgentExecutionResponseDto(
            false, null, null, List.of(), 0, Map.of(), "CLI crashed",
            10, "codex", "gpt-5.3-codex", List.of(), "ERROR", Map.of(), List.of(), List.of(), List.of(),
            List.of(Map.of("content", "reasoning about the task...")), List.of(), null);
        assertThat(withThinking.hasNoVisibleOutput()).isFalse();
    }

    @Test
    @DisplayName("hasNoVisibleOutput is false when orderedEntries already carry streamed content")
    void hasNoVisibleOutputFalseWithOrderedEntries() {
        AgentExecutionResponseDto withEntries = new AgentExecutionResponseDto(
            false, null, null, List.of(), 0, Map.of(), "CLI crashed",
            10, "codex", "gpt-5.3-codex", List.of(), "ERROR", Map.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(Map.of("type", "thinking")), null);
        assertThat(withEntries.hasNoVisibleOutput()).isFalse();
    }

    private static AgentExecutionRequestDto requestDto(String provider, String model) {
        return new AgentExecutionRequestDto(
            "prompt", "system", provider, model, 0.0, 320, List.of(), false, 10, 4, 150, null,
            "tenant-1", null, null, null, Map.of(), null, null, null, null, "conv-1", "conversation",
            null, null, null, null, null, null, "agent-1", 100.0, null, 0.0, null, null,
            "exec-1", "CHAT", null, null);
    }

    private static AgentExecutionRequestDto requestDtoWithCredentials(Map<String, Object> creds) {
        return new AgentExecutionRequestDto(
            "prompt", "system", "anthropic", "claude-opus-4-8", 0.0, 320, List.of(), false, 10, 4, 150, null,
            "tenant-1", null, null, null, creds, null, null, null, null, "conv-1", "conversation",
            null, null, null, null, null, null, "agent-1", 100.0, null, 0.0, null, null,
            "exec-1", "CHAT", null, null);
    }
}
