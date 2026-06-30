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
    @DisplayName("withBilledIdentity re-stamps provider+model and preserves content/usage")
    void withBilledIdentityReStampsAndPreservesPayload() {
        AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
            true, "answer", "answer", List.of(), 1, Map.of("totalTokens", 42),
            null, 10, "codex", "gpt-5.3-codex", List.of(),
            "COMPLETED", Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);

        AgentExecutionResponseDto billed = bridgeResponse.withBilledIdentity("anthropic", "claude-opus-4-8");

        assertThat(billed.provider()).isEqualTo("anthropic");
        assertThat(billed.model()).isEqualTo("claude-opus-4-8");
        // The actual execution payload is untouched - only the billing identity changed.
        assertThat(billed.content()).isEqualTo("answer");
        assertThat(billed.totalUsage()).isEqualTo(Map.of("totalTokens", 42));
        assertThat(billed.success()).isTrue();
    }

    @Test
    @DisplayName("withBilledIdentity is a no-op (same instance) when provider and model are unchanged")
    void withBilledIdentityNoOpWhenUnchanged() {
        AgentExecutionResponseDto response = new AgentExecutionResponseDto(
            true, "answer", "answer", List.of(), 1, Map.of(),
            null, 10, "anthropic", "claude-opus-4-8", List.of(),
            "COMPLETED", Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
        assertThat(response.withBilledIdentity("anthropic", "claude-opus-4-8")).isSameAs(response);
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
