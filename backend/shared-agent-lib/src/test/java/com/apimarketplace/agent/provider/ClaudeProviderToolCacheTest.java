package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 1a.1 - Anthropic prompt caching uses up to 4 {@code cache_control: ephemeral}
 * breakpoints, applied in order {@code tools → system → messages}. Breakpoint #1 goes
 * on the LAST tool so the entire tools array becomes one cached prefix. This test
 * pins that only the last tool (after the deterministic alphabetical sort) carries
 * {@code cache_control}, that the value is {@code {type: "ephemeral"}}, and that the
 * cached prefix is byte-stable when the input order is perturbed.
 */
@DisplayName("ClaudeProvider - cache_control on last tool (breakpoint #1)")
class ClaudeProviderToolCacheTest {

    private final ClaudeProvider provider = new ClaudeProvider();

    private static ToolDefinition tool(String name) {
        return ToolDefinition.builder()
            .name(name)
            .description("desc-" + name)
            .parameters(List.of(ToolParameter.builder().name("q").type("string").description("d").build()))
            .build();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildTools(List<ToolDefinition> tools) {
        CompletionRequest req = CompletionRequest.builder()
            .model("claude-sonnet-4-5")
            .userPrompt("hi")
            .tools(tools)
            .build();
        Map<String, Object> body = provider.buildRequestBody(req);
        return (List<Map<String, Object>>) body.get("tools");
    }

    @Test
    @DisplayName("only the LAST tool (alphabetically) carries cache_control=ephemeral")
    void onlyLastToolIsCacheMarked() {
        List<Map<String, Object>> serialized = buildTools(List.of(
            tool("delta"), tool("alpha"), tool("charlie"), tool("bravo")
        ));

        // After sort: alpha, bravo, charlie, delta
        assertThat(serialized).hasSize(4);
        assertThat(serialized.get(0).get("name")).isEqualTo("alpha");
        assertThat(serialized.get(3).get("name")).isEqualTo("delta");

        assertThat(serialized.get(0)).doesNotContainKey("cache_control");
        assertThat(serialized.get(1)).doesNotContainKey("cache_control");
        assertThat(serialized.get(2)).doesNotContainKey("cache_control");

        Object marker = serialized.get(3).get("cache_control");
        assertThat(marker).isEqualTo(Map.of("type", "ephemeral"));
    }

    @Test
    @DisplayName("single tool - that one tool carries cache_control")
    void singleToolIsCacheMarked() {
        List<Map<String, Object>> serialized = buildTools(List.of(tool("only")));

        assertThat(serialized).hasSize(1);
        assertThat(serialized.get(0).get("cache_control"))
            .isEqualTo(Map.of("type", "ephemeral"));
    }

    @Test
    @DisplayName("cache_control key appears AFTER input_schema (LinkedHashMap insertion order)")
    void cacheControlIsLastKeyForByteStability() {
        List<Map<String, Object>> serialized = buildTools(List.of(tool("alpha"), tool("zulu")));
        Map<String, Object> lastTool = serialized.get(1);
        List<String> keys = List.copyOf(lastTool.keySet());
        // Insertion order locked: name, description, input_schema, cache_control.
        // LinkedHashMap preserves this, so the serialized JSON is byte-stable across JVMs.
        assertThat(keys).containsExactly("name", "description", "input_schema", "cache_control");
    }

    @Test
    @DisplayName("empty tools list → empty array, no marker leaks through")
    void emptyToolsProducesEmptyArray() {
        List<Map<String, Object>> serialized = buildTools(List.of());
        // When tools list is empty, buildRequestBody does not include "tools" at all.
        assertThat(serialized).isNull();
    }

    @Test
    @DisplayName("null-name tool sorts last via nullsLast, still receives cache_control")
    void nullNameSortsLastAndGetsMarker() {
        ToolDefinition nullNamed = ToolDefinition.builder()
            .name(null)
            .description("desc-null")
            .parameters(List.of(ToolParameter.builder().name("q").type("string").description("d").build()))
            .build();
        List<Map<String, Object>> serialized = buildTools(List.of(tool("alpha"), nullNamed, tool("zulu")));

        // nullsLast → null-name sorts AFTER zulu, so it is the last element and carries the marker.
        assertThat(serialized).hasSize(3);
        assertThat(serialized.get(0).get("name")).isEqualTo("alpha");
        assertThat(serialized.get(1).get("name")).isEqualTo("zulu");
        assertThat(serialized.get(2).get("name")).isNull();
        assertThat(serialized.get(2).get("cache_control"))
            .isEqualTo(Map.of("type", "ephemeral"));
        assertThat(serialized.get(0)).doesNotContainKey("cache_control");
        assertThat(serialized.get(1)).doesNotContainKey("cache_control");
    }

    @Test
    @DisplayName("cache_control placement survives input shuffling - same tool is marked regardless of input order")
    void cacheMarkerFollowsSortedLast() {
        // Forward order
        List<Map<String, Object>> forward = buildTools(List.of(tool("a"), tool("b"), tool("c")));
        // Reversed input - still sorts to a,b,c so 'c' is still last and still marked
        List<Map<String, Object>> reversed = buildTools(List.of(tool("c"), tool("b"), tool("a")));

        assertThat(forward.get(2).get("name")).isEqualTo("c");
        assertThat(reversed.get(2).get("name")).isEqualTo("c");

        assertThat(forward.get(2).get("cache_control"))
            .isEqualTo(Map.of("type", "ephemeral"));
        assertThat(reversed.get(2).get("cache_control"))
            .isEqualTo(Map.of("type", "ephemeral"));

        // Non-last tools never get the marker, regardless of input order
        assertThat(forward.get(0)).doesNotContainKey("cache_control");
        assertThat(forward.get(1)).doesNotContainKey("cache_control");
        assertThat(reversed.get(0)).doesNotContainKey("cache_control");
        assertThat(reversed.get(1)).doesNotContainKey("cache_control");
    }
}
