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
 * Stage 1a.1 - the same CoreToolsProvider ConcurrentHashMap feeds every provider, so
 * deterministic serialization must hold for the OpenAI-compatible path too (OpenAI,
 * DeepSeek, Mistral, ZAI, Groq, custom OpenAI-compatible endpoints). Otherwise prompt
 * caches invalidate on any pod restart. Tests use {@link OpenAIProvider} as a concrete
 * exercise of the shared {@code buildOpenAITools} in {@code AbstractLLMProvider}.
 */
@DisplayName("OpenAI-compatible - deterministic tool serialization")
class OpenAICompatibleToolOrderStableTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final OpenAIProvider provider = new OpenAIProvider();

    private static ToolDefinition tool(String name) {
        return ToolDefinition.builder()
            .name(name)
            .description("desc-" + name)
            .parameters(List.of(
                ToolParameter.builder().name("q").type("string").description("d").required(true).build(),
                ToolParameter.builder().name("limit").type("integer").description("max").build()
            ))
            .build();
    }

    @Test
    @DisplayName("shuffled input → byte-identical serialized tools")
    @SuppressWarnings("unchecked")
    void shuffledInputProducesIdenticalBody() throws Exception {
        List<ToolDefinition> canonical = List.of(
            tool("aaa"), tool("bbb"), tool("ccc"), tool("ddd"), tool("eee"), tool("fff")
        );

        String reference = JSON.writeValueAsString(provider.buildOpenAITools(canonical));

        for (int seed = 0; seed < 20; seed++) {
            List<ToolDefinition> shuffled = new ArrayList<>(canonical);
            Collections.shuffle(shuffled, new java.util.Random(seed));
            String actual = JSON.writeValueAsString(provider.buildOpenAITools(shuffled));
            assertThat(actual).as("seed=%d", seed).isEqualTo(reference);
        }
    }

    @Test
    @DisplayName("per-tool JSON keys appear in a fixed order (type → function → name → description → parameters)")
    void perToolKeyOrderIsFixed() throws Exception {
        String json = JSON.writeValueAsString(provider.buildOpenAITools(List.of(tool("solo"))));

        int typeIdx = json.indexOf("\"type\"");
        int functionIdx = json.indexOf("\"function\"");
        int nameIdx = json.indexOf("\"name\"");
        int descIdx = json.indexOf("\"description\"");
        int paramsIdx = json.indexOf("\"parameters\"");

        // All keys must be present.
        assertThat(typeIdx).as("type key").isGreaterThanOrEqualTo(0);
        assertThat(functionIdx).as("function key").isGreaterThanOrEqualTo(0);
        assertThat(nameIdx).as("name key").isGreaterThanOrEqualTo(0);
        assertThat(descIdx).as("description key").isGreaterThanOrEqualTo(0);
        assertThat(paramsIdx).as("parameters key").isGreaterThanOrEqualTo(0);

        // Ordering invariant: wrapper first (type, function), then the function body (name, description, parameters).
        assertThat(typeIdx).isLessThan(functionIdx);
        assertThat(functionIdx).isLessThan(nameIdx);
        assertThat(nameIdx).isLessThan(descIdx);
        assertThat(descIdx).isLessThan(paramsIdx);
    }

    @Test
    @DisplayName("null tool-name sorts last (nullsLast comparator) instead of NPE-ing")
    @SuppressWarnings("unchecked")
    void nullNameDoesNotNpe() throws Exception {
        ToolDefinition nullNamed = ToolDefinition.builder()
            .name(null)
            .description("nameless")
            .parameters(List.of())
            .build();
        List<ToolDefinition> mixed = List.of(tool("zebra"), nullNamed, tool("alpha"));

        // Should not throw
        List<Map<String, Object>> serialized = provider.buildOpenAITools(mixed);

        assertThat(serialized).hasSize(3);
        // alphabetical then nulls last
        Map<String, Object> first = (Map<String, Object>) ((Map<String, Object>) serialized.get(0)).get("function");
        Map<String, Object> second = (Map<String, Object>) ((Map<String, Object>) serialized.get(1)).get("function");
        Map<String, Object> third = (Map<String, Object>) ((Map<String, Object>) serialized.get(2)).get("function");
        assertThat(first.get("name")).isEqualTo("alpha");
        assertThat(second.get("name")).isEqualTo("zebra");
        assertThat(third.get("name")).isNull();
    }

    @Test
    @DisplayName("empty tools list returns null (existing contract preserved)")
    void emptyToolsReturnsNull() {
        assertThat(provider.buildOpenAITools(List.of())).isNull();
        assertThat(provider.buildOpenAITools(null)).isNull();
    }
}
