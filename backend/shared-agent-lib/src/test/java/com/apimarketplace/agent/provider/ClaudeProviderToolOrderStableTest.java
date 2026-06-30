package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 1a.1 - Anthropic caches the entire {@code tools} array under a single
 * prefix. A single tool out of place invalidates the cached prefix and forces
 * a full re-tokenization. Pod restart + {@code HashMap}/{@code ConcurrentHashMap}
 * iteration in {@code CoreToolsProvider} historically caused this at ~10% of
 * restarts. This test locks in deterministic sort-by-name ordering.
 */
@DisplayName("ClaudeProvider - deterministic tool serialization")
class ClaudeProviderToolOrderStableTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ClaudeProvider provider = new ClaudeProvider();

    private static ToolDefinition tool(String name) {
        return ToolDefinition.builder()
            .name(name)
            .description("desc-" + name)
            .parameters(List.of(ToolParameter.builder().name("q").type("string").description("d").build()))
            .build();
    }

    @Test
    @DisplayName("shuffling the input tools list produces byte-identical serialized body")
    void shuffledInputProducesIdenticalBody() throws Exception {
        List<ToolDefinition> canonical = List.of(
            tool("aaa"), tool("bbb"), tool("ccc"), tool("ddd"), tool("eee"), tool("fff")
        );

        String reference = serializeTools(canonical);

        for (int seed = 0; seed < 20; seed++) {
            List<ToolDefinition> shuffled = new ArrayList<>(canonical);
            Collections.shuffle(shuffled, new java.util.Random(seed));

            String actual = serializeTools(shuffled);
            assertThat(actual)
                .as("seed=%d shuffle must serialize byte-identically", seed)
                .isEqualTo(reference);
        }
    }

    @Test
    @DisplayName("reverse order still sorts to alphabetical")
    void reversedInputSortsToAlphabetical() throws Exception {
        String forward = serializeTools(List.of(tool("alpha"), tool("beta"), tool("gamma")));
        String reversed = serializeTools(List.of(tool("gamma"), tool("beta"), tool("alpha")));
        assertThat(reversed).isEqualTo(forward);
    }

    @SuppressWarnings("unchecked")
    private String serializeTools(List<ToolDefinition> tools) throws Exception {
        CompletionRequest req = CompletionRequest.builder()
            .model("claude-sonnet-4-5")
            .userPrompt("hi")
            .tools(tools)
            .build();
        Map<String, Object> body = provider.buildRequestBody(req);
        Object serializedTools = body.get("tools");
        return JSON.writeValueAsString(serializedTools);
    }
}
