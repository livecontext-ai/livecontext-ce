package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.compaction.ContextCompactionTools;
import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.SystemBlock;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.tokenizer.TokenEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 1b.2 - pin the Claude native {@code context_management} wire
 * shape for the {@code clear_tool_uses_20250919} edit.
 *
 * <p><b>Why this test.</b> Anthropic's context-management feature lets
 * us delegate long-history masking to the API <em>after</em> the cache
 * prefix has been read - so our prompt cache is NOT invalidated by the
 * mask. That only holds if we emit the exact wire shape Anthropic
 * expects: a top-level {@code context_management.edits[]} array with
 * pinned type strings, pinned trigger semantics, and pinned tool
 * allowlists. Any drift in type strings (e.g., a typo or a version bump
 * that silently ships) would either reject the request or - worse -
 * run a stale masking policy and return malformed content. This test
 * is the tripwire.
 *
 * <p><b>Cache-preservation invariant.</b> Adding {@code context_management}
 * must NOT disturb existing {@code cache_control} markers on tools,
 * system blocks, or history messages. The last assertion in
 * {@link #cacheMarkersSurvive} guards that - a future refactor that
 * inadvertently strips or shifts those markers would break prompt-cache
 * hit rates and show up as cost regression, not test failure, without
 * this check.
 */
@DisplayName("ClaudeProvider - native context_management wire shape (Stage 1b.2)")
class ClaudeNativeContextManagementTest {

    private ClaudeProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ClaudeProvider();
    }

    /** TokenEstimator stub that forces a specific prompt-token estimate
     *  regardless of actual content - lets us cross the 180k trigger
     *  without constructing a 750k-char history. */
    private static TokenEstimator stubEstimator(int totalTokens) {
        return new TokenEstimator() {
            @Override public String name() { return "stub"; }
            @Override public int estimate(CompletionRequest request) { return totalTokens; }
        };
    }

    private static CompletionRequest smallRequest() {
        return CompletionRequest.builder()
                .model("claude-sonnet-4-5")
                .userPrompt("hello")
                .build();
    }

    @Test
    @DisplayName("below 180k trigger → no context_management key at all (body shape unchanged)")
    void belowTriggerEmitsNothing() {
        provider.setTokenEstimator(stubEstimator(10_000));

        Map<String, Object> body = provider.buildRequestBody(smallRequest());

        assertThat(body)
                .as("cache-sensitive: turns below the trigger must not gain a new top-level key")
                .doesNotContainKey("context_management");
    }

    @Test
    @DisplayName("above 180k trigger → context_management.edits[] carries the clear_tool_uses edit")
    void aboveTriggerEmitsClearToolUsesEdit() {
        // 200k prompt + 4k maxTokens defaults → promptEstimate = 196k > 180k trigger.
        provider.setTokenEstimator(stubEstimator(200_000));

        Map<String, Object> body = provider.buildRequestBody(smallRequest());

        assertThat(body).containsKey("context_management");

        @SuppressWarnings("unchecked")
        Map<String, Object> cm = (Map<String, Object>) body.get("context_management");
        assertThat(cm).containsOnlyKeys("edits");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) cm.get("edits");
        assertThat(edits).hasSize(1);

        Map<String, Object> edit = edits.get(0);
        assertThat(edit.get("type")).isEqualTo("clear_tool_uses_20250919");
        assertThat(edit.get("trigger"))
                .isEqualTo(Map.of("type", "input_tokens", "value", 180_000L));
        assertThat(edit.get("clear_at_least"))
                .isEqualTo(Map.of("type", "input_tokens", "value", 140_000L));

        @SuppressWarnings("unchecked")
        List<String> clearToolInputs = (List<String>) edit.get("clear_tool_inputs");
        assertThat(clearToolInputs)
                .as("must match ContextCompactionTools.COMPACTABLE_TOOLS exactly")
                .containsExactlyInAnyOrderElementsOf(ContextCompactionTools.COMPACTABLE_TOOLS);

        @SuppressWarnings("unchecked")
        List<String> excludeTools = (List<String>) edit.get("exclude_tools");
        assertThat(excludeTools)
                .as("must match ContextCompactionTools.NEVER_MASK_TOOLS exactly")
                .containsExactlyInAnyOrderElementsOf(ContextCompactionTools.NEVER_MASK_TOOLS);
    }

    @Test
    @DisplayName("edit key order is insertion-stable (LinkedHashMap) - protects byte-identical retries")
    void editKeyOrderIsStable() {
        provider.setTokenEstimator(stubEstimator(200_000));

        Map<String, Object> body = provider.buildRequestBody(smallRequest());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>)
                ((Map<String, Object>) body.get("context_management")).get("edits");
        Map<String, Object> edit = edits.get(0);

        // Plain Map.keySet() reflects insertion order only for LinkedHashMap.
        // A future refactor that switched to HashMap would break retry
        // byte-identity - catch it here.
        assertThat(edit.keySet())
                .containsExactly("type", "trigger", "clear_at_least",
                        "clear_tool_inputs", "exclude_tools");
    }

    @Test
    @DisplayName("tool-name lists are deterministically sorted - protects byte-identical retries")
    void toolNameListsAreSorted() {
        provider.setTokenEstimator(stubEstimator(200_000));

        Map<String, Object> body = provider.buildRequestBody(smallRequest());
        @SuppressWarnings("unchecked")
        Map<String, Object> edit = ((List<Map<String, Object>>)
                ((Map<String, Object>) body.get("context_management")).get("edits")).get(0);

        @SuppressWarnings("unchecked")
        List<String> clearToolInputs = (List<String>) edit.get("clear_tool_inputs");
        assertThat(clearToolInputs).isSorted();

        @SuppressWarnings("unchecked")
        List<String> excludeTools = (List<String>) edit.get("exclude_tools");
        assertThat(excludeTools).isSorted();
    }

    @Test
    @DisplayName("adding context_management preserves all pre-existing cache_control markers")
    void cacheMarkersSurvive() {
        provider.setTokenEstimator(stubEstimator(200_000));

        // Build a request with: layered systemBlocks (so system[] gets a
        // cache marker on the last block), a history + userPrompt (so
        // the last history message gets marked), and a tool (so the
        // last tool definition gets marked). All three boundaries must
        // keep their cache_control after context_management is added.
        CompletionRequest req = CompletionRequest.builder()
                .model("claude-sonnet-4-5")
                .systemBlocks(List.of(
                        SystemBlock.of("policy preamble"),
                        SystemBlock.breakpoint("stable skill snapshot")))
                .conversationHistory(List.of(
                        Message.user("hello"),
                        Message.assistant("hi there")))
                .userPrompt("follow-up")
                .tools(List.of(
                        ToolDefinition.builder()
                                .name("alpha")
                                .description("first")
                                .parameters(List.of(
                                        ToolParameter.builder().name("q").type("string").required(true).build()))
                                .build(),
                        ToolDefinition.builder()
                                .name("zeta")
                                .description("last")
                                .parameters(List.of(
                                        ToolParameter.builder().name("q").type("string").required(true).build()))
                                .build()))
                .build();

        Map<String, Object> body = provider.buildRequestBody(req);

        // Last system block carries the marker.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> systemArr = (List<Map<String, Object>>) body.get("system");
        assertThat(systemArr.get(systemArr.size() - 1))
                .as("last system block must keep its cache_control")
                .containsEntry("cache_control", Map.of("type", "ephemeral"));

        // Last tool carries the marker.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) body.get("tools");
        assertThat(tools.get(tools.size() - 1))
                .as("last tool must keep its cache_control")
                .containsEntry("cache_control", Map.of("type", "ephemeral"));

        // Last history message carries the marker. Content can be a
        // String (plain) or a List of blocks; handle both.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        Map<String, Object> lastHistory = messages.get(messages.size() - 2); // userPrompt is last
        Object content = lastHistory.get("content");
        if (content instanceof List<?> blocks && !blocks.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> lastBlock = (Map<String, Object>) blocks.get(blocks.size() - 1);
            assertThat(lastBlock)
                    .as("last-history-message last content block must keep cache_control")
                    .containsEntry("cache_control", Map.of("type", "ephemeral"));
        }

        // And of course context_management is still there.
        assertThat(body).containsKey("context_management");
    }

    @Test
    @DisplayName("context_management lands as a top-level key, alongside existing body fields")
    void topLevelKey() {
        provider.setTokenEstimator(stubEstimator(200_000));

        Map<String, Object> body = provider.buildRequestBody(smallRequest());

        // Sanity: the six mandatory Anthropic fields are still there,
        // context_management rides alongside them.
        assertThat(body).containsKeys("model", "max_tokens", "messages", "temperature",
                "context_management");
    }

    @Test
    @DisplayName("estimator-free fallback (chars/4) still gates correctly on huge payloads")
    void heuristicFallbackGates() {
        // Leave tokenEstimator null - AbstractLLMProvider falls back to
        // estimateTokensHeuristic (chars/4). Build a ~800k-char prompt
        // to cross the 180k token trigger under the heuristic too.
        StringBuilder big = new StringBuilder();
        while (big.length() < 800_000) big.append("abcdefgh"); // 800k chars ≈ 200k tokens

        CompletionRequest req = CompletionRequest.builder()
                .model("claude-sonnet-4-5")
                .userPrompt(big.toString())
                .build();

        Map<String, Object> body = provider.buildRequestBody(req);
        assertThat(body)
                .as("the heuristic path must still trigger context_management at large sizes")
                .containsKey("context_management");
    }

    @Test
    @DisplayName("short prompt with tokenEstimator wired stays below trigger (defence against off-by-one)")
    void borderlineUnderTrigger() {
        // 180_001 tokens total, maxTokens default 4096 → promptEstimate
        // = 175_905. Below the 180k trigger; no edit should fire.
        provider.setTokenEstimator(stubEstimator(180_001));

        Map<String, Object> body = provider.buildRequestBody(
                CompletionRequest.builder()
                        .model("claude-sonnet-4-5")
                        .userPrompt("x")
                        .maxTokens(4096)
                        .build());

        assertThat(body).doesNotContainKey("context_management");
    }

    @Test
    @DisplayName("tools-preserving clear-at-least: 140k floor literal is pinned")
    void clearAtLeastValuePinned() {
        // A sanity pin so if someone changes 140_000 → 150_000 in the
        // provider constant, this fires and forces an intentional review.
        // The plan's exact pair is (trigger=180k, clear_at_least=140k);
        // both are load-bearing - trigger is the when, clear_at_least is
        // the how-much, and a conservative floor is what keeps the
        // cache from being blown away entirely.
        assertThat(ClaudeProvider.NATIVE_COMPACT_TRIGGER_TOKENS).isEqualTo(180_000L);
        assertThat(ClaudeProvider.NATIVE_COMPACT_CLEAR_AT_LEAST_VALUE).isEqualTo(140_000L);
    }

    @Test
    @DisplayName("COMPACTABLE and NEVER_MASK sets are disjoint - explicit-intent invariant")
    void compactableAndNeverMaskAreDisjoint() {
        // A tool listed in BOTH would produce a self-contradictory edit
        // (we said "clear this" and "never mask this" at once). Anthropic's
        // behaviour on conflict is unspecified, so surface any overlap as
        // a test failure at author-time.
        List<String> overlap = new ArrayList<>(ContextCompactionTools.COMPACTABLE_TOOLS);
        overlap.retainAll(ContextCompactionTools.NEVER_MASK_TOOLS);
        assertThat(overlap)
                .as("no tool may appear in both COMPACTABLE_TOOLS and NEVER_MASK_TOOLS")
                .isEmpty();
    }
}
