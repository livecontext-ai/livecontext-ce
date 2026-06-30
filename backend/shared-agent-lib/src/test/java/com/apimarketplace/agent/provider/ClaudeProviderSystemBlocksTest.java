package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.SystemBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 1a.1 - Claude emits a native {@code system: [...]} array when the
 * caller supplies layered {@link SystemBlock}s, with
 * {@code cache_control: {type: ephemeral}} on blocks flagged as breakpoints.
 *
 * <p>Each test pins one aspect of the contract: breakpoint marking, block
 * ordering, blank-block skipping, and legacy {@code systemPrompt()} fallback.
 * Together they guard against cache-invalidation regressions - the whole
 * reason layered blocks exist is to keep the serialized prefix byte-stable
 * across turns.
 */
@DisplayName("ClaudeProvider - system: [...] array from layered SystemBlocks (breakpoints #1 & #2)")
class ClaudeProviderSystemBlocksTest {

    private final ClaudeProvider provider = new ClaudeProvider();

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildSystemArray(CompletionRequest request) {
        Object sys = provider.buildRequestBody(request).get("system");
        if (sys == null) return List.of();
        return (List<Map<String, Object>>) sys;
    }

    @Test
    @DisplayName("Flagged blocks carry cache_control: ephemeral, non-flagged blocks do not")
    void breakpointBlocksGetCacheControl() {
        CompletionRequest req = CompletionRequest.builder()
            .model("claude-sonnet-4-5")
            .systemBlocks(List.of(
                SystemBlock.breakpoint("static base prompt"),
                SystemBlock.of("tool restriction"),
                SystemBlock.of("default skills"),
                SystemBlock.breakpoint("agent skills"),
                SystemBlock.of("task delegation summary")
            ))
            .userPrompt("hello")
            .build();

        List<Map<String, Object>> systemArray = buildSystemArray(req);
        assertThat(systemArray).hasSize(5);

        assertThat(systemArray.get(0).get("type")).isEqualTo("text");
        assertThat(systemArray.get(0).get("text")).isEqualTo("static base prompt");
        assertThat(systemArray.get(0).get("cache_control"))
            .as("block [0] is a breakpoint - must carry ephemeral cache_control")
            .isEqualTo(Map.of("type", "ephemeral"));

        assertThat(systemArray.get(1)).doesNotContainKey("cache_control");
        assertThat(systemArray.get(2)).doesNotContainKey("cache_control");

        assertThat(systemArray.get(3).get("cache_control"))
            .as("block [3] is a breakpoint - must carry ephemeral cache_control")
            .isEqualTo(Map.of("type", "ephemeral"));

        assertThat(systemArray.get(4)).doesNotContainKey("cache_control");
    }

    @Test
    @DisplayName("Blank blocks are skipped so cache_control never lands on empty text")
    void blankBlocksAreSkipped() {
        List<SystemBlock> blocks = new ArrayList<>();
        blocks.add(SystemBlock.breakpoint("base"));
        blocks.add(SystemBlock.of(""));           // empty - must not emit an entry
        blocks.add(SystemBlock.of("   \n\t   ")); // whitespace only - must not emit an entry
        blocks.add(SystemBlock.breakpoint("skills section"));

        CompletionRequest req = CompletionRequest.builder()
            .model("claude-sonnet-4-5")
            .systemBlocks(blocks)
            .userPrompt("hi")
            .build();

        List<Map<String, Object>> systemArray = buildSystemArray(req);
        assertThat(systemArray)
            .as("only two non-blank blocks should be serialized")
            .hasSize(2);

        assertThat(systemArray.get(0).get("text")).isEqualTo("base");
        assertThat(systemArray.get(1).get("text")).isEqualTo("skills section");
        assertThat(systemArray.get(0).get("cache_control")).isEqualTo(Map.of("type", "ephemeral"));
        assertThat(systemArray.get(1).get("cache_control")).isEqualTo(Map.of("type", "ephemeral"));
    }

    @Test
    @DisplayName("Block order is preserved (callers rely on static-prefix → dynamic-suffix layout)")
    void blockOrderIsPreserved() {
        CompletionRequest req = CompletionRequest.builder()
            .model("claude-sonnet-4-5")
            .systemBlocks(List.of(
                SystemBlock.of("alpha"),
                SystemBlock.of("beta"),
                SystemBlock.of("gamma")
            ))
            .userPrompt("x")
            .build();

        List<Map<String, Object>> systemArray = buildSystemArray(req);
        assertThat(systemArray).extracting(e -> e.get("text"))
            .containsExactly("alpha", "beta", "gamma");
    }

    @Test
    @DisplayName("Legacy systemPrompt() fallback - single block with trailing cache_control ephemeral")
    void legacySystemPromptFallback() {
        CompletionRequest req = CompletionRequest.builder()
            .model("claude-sonnet-4-5")
            .systemPrompt("legacy monolith prompt")
            .userPrompt("hi")
            .build();

        List<Map<String, Object>> systemArray = buildSystemArray(req);
        assertThat(systemArray)
            .as("legacy callers produce exactly one system block - preserves prior behavior")
            .hasSize(1);
        Map<String, Object> entry = systemArray.get(0);
        assertThat(entry.get("type")).isEqualTo("text");
        assertThat(entry.get("text")).isEqualTo("legacy monolith prompt");
        assertThat(entry.get("cache_control")).isEqualTo(Map.of("type", "ephemeral"));
    }

    @Test
    @DisplayName("systemBlocks takes precedence over legacy systemPrompt when both are present")
    void systemBlocksWinsOverLegacyString() {
        CompletionRequest req = CompletionRequest.builder()
            .model("claude-sonnet-4-5")
            .systemPrompt("legacy concatenation")
            .systemBlocks(List.of(
                SystemBlock.breakpoint("layered-base"),
                SystemBlock.of("layered-tail")
            ))
            .userPrompt("x")
            .build();

        List<Map<String, Object>> systemArray = buildSystemArray(req);
        assertThat(systemArray).hasSize(2);
        assertThat(systemArray.get(0).get("text")).isEqualTo("layered-base");
        assertThat(systemArray.get(1).get("text")).isEqualTo("layered-tail");
        assertThat(systemArray).extracting(e -> e.get("text"))
            .as("legacy concatenation must not leak in when systemBlocks are provided")
            .doesNotContain("legacy concatenation");
    }

    @Test
    @DisplayName("Neither systemBlocks nor systemPrompt → no system field in the request body")
    void noSystemFieldWhenNothingSet() {
        CompletionRequest req = CompletionRequest.builder()
            .model("claude-sonnet-4-5")
            .userPrompt("hello")
            .build();

        Map<String, Object> body = provider.buildRequestBody(req);
        assertThat(body)
            .as("omitting the system field is an explicit contract - Anthropic accepts missing system but not empty array")
            .doesNotContainKey("system");
    }

    @Test
    @DisplayName("Breakpoint on a blank block is silently dropped - no cache_control wasted on empty text")
    void breakpointOnBlankBlockIsDropped() {
        CompletionRequest req = CompletionRequest.builder()
            .model("claude-sonnet-4-5")
            .systemBlocks(List.of(
                SystemBlock.breakpoint("real base"),
                SystemBlock.breakpoint(""),       // blank + flagged - must NOT emit
                SystemBlock.breakpoint("   \n "), // whitespace + flagged - must NOT emit
                SystemBlock.breakpoint("real tail")
            ))
            .userPrompt("x")
            .build();

        List<Map<String, Object>> systemArray = buildSystemArray(req);
        assertThat(systemArray)
            .as("blank blocks are skipped before the breakpoint flag is read - no empty cache segments")
            .hasSize(2);
        assertThat(systemArray.get(0).get("text")).isEqualTo("real base");
        assertThat(systemArray.get(1).get("text")).isEqualTo("real tail");
        // Both surviving blocks keep their cache_control - blank-drop must not nuke it on the neighbors.
        assertThat(systemArray.get(0).get("cache_control")).isEqualTo(Map.of("type", "ephemeral"));
        assertThat(systemArray.get(1).get("cache_control")).isEqualTo(Map.of("type", "ephemeral"));
    }

    @Test
    @DisplayName("All-blank systemBlocks → no system field emitted")
    void allBlankBlocksProduceNoSystemField() {
        CompletionRequest req = CompletionRequest.builder()
            .model("claude-sonnet-4-5")
            .systemBlocks(List.of(
                SystemBlock.of(""),
                SystemBlock.of("  "),
                SystemBlock.of("\n")
            ))
            .userPrompt("x")
            .build();

        Map<String, Object> body = provider.buildRequestBody(req);
        assertThat(body).doesNotContainKey("system");
    }
}
