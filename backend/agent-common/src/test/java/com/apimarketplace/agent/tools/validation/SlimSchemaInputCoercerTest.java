package com.apimarketplace.agent.tools.validation;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stage 4a.1b - pin the reverse-coercion contract for slim-schema tool calls.
 *
 * <p>These tests lock the behaviour of {@link SlimSchemaInputCoercer} so the
 * validator in {@code ToolsRegistrationService.executeTool()} sees real-typed
 * values (Long / Double / Boolean / List / Map) instead of the strings the
 * slim-schema LLM sends.
 */
@DisplayName("SlimSchemaInputCoercer - reverse coercion (Stage 4a.1b)")
class SlimSchemaInputCoercerTest {

    private AgentToolRegistry registry;
    private SlimSchemaInputCoercer coercer;

    @BeforeEach
    void setUp() {
        registry = mock(AgentToolRegistry.class);
        coercer = new SlimSchemaInputCoercer(registry, new ObjectMapper());
    }

    private void stubTool(String name, List<ToolParameter> params) {
        AgentToolDefinition def = AgentToolDefinition.builder()
                .name(name)
                .parameters(params)
                .build();
        when(registry.getToolByName(name)).thenReturn(Optional.of(def));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return (List<Object>) o;
    }

    @Nested
    @DisplayName("null / empty / unknown short-circuits")
    class ShortCircuits {

        @Test
        @DisplayName("null input map returns the same null reference")
        void nullInputReturnsNull() {
            assertThat(coercer.coerce("anything", null)).isNull();
        }

        @Test
        @DisplayName("empty input map passes through unchanged")
        void emptyInputPassesThrough() {
            Map<String, Object> empty = Map.of();
            assertThat(coercer.coerce("anything", empty)).isSameAs(empty);
        }

        @Test
        @DisplayName("unknown tool is a no-op - no coercion performed")
        void unknownToolPassesThrough() {
            when(registry.getToolByName("mystery")).thenReturn(Optional.empty());
            Map<String, Object> in = Map.of("limit", "10");

            Map<String, Object> out = coercer.coerce("mystery", in);

            // Unknown tool: no parameter metadata → return input untouched.
            assertThat(out).isEqualTo(Map.of("limit", "10"));
        }

        @Test
        @DisplayName("tool with null parameters list is a no-op")
        void nullParametersListPassesThrough() {
            AgentToolDefinition def = AgentToolDefinition.builder().name("table").parameters(null).build();
            when(registry.getToolByName("table")).thenReturn(Optional.of(def));
            Map<String, Object> in = Map.of("limit", "10");

            assertThat(coercer.coerce("table", in)).isEqualTo(Map.of("limit", "10"));
        }

        @Test
        @DisplayName("tool with empty parameters list is a no-op")
        void emptyParametersListPassesThrough() {
            stubTool("table", List.of());
            Map<String, Object> in = Map.of("limit", "10");

            assertThat(coercer.coerce("table", in)).isEqualTo(Map.of("limit", "10"));
        }
    }

    @Nested
    @DisplayName("per-type coercion - happy path")
    class HappyPath {

        @Test
        @DisplayName("integer: '10' → 10L")
        void integerParses() {
            stubTool("table", List.of(ToolParameter.builder().name("limit").type("integer").build()));
            Map<String, Object> out = coercer.coerce("table", Map.of("limit", "10"));
            assertThat(out.get("limit")).isEqualTo(10L);
        }

        @Test
        @DisplayName("int alias is treated as integer")
        void intAliasParses() {
            stubTool("table", List.of(ToolParameter.builder().name("n").type("int").build()));
            Map<String, Object> out = coercer.coerce("table", Map.of("n", "42"));
            assertThat(out.get("n")).isEqualTo(42L);
        }

        @Test
        @DisplayName("number: '3.14' → 3.14 double")
        void numberParses() {
            stubTool("table", List.of(ToolParameter.builder().name("offset").type("number").build()));
            Map<String, Object> out = coercer.coerce("table", Map.of("offset", "3.14"));
            assertThat(out.get("offset")).isEqualTo(3.14);
        }

        @Test
        @DisplayName("boolean: 'true' → true, 'false' → false, 'TRUE' → true (case-insensitive)")
        void booleanParses() {
            stubTool("table", List.of(ToolParameter.builder().name("distinct").type("boolean").build()));

            assertThat(coercer.coerce("table", Map.of("distinct", "true")).get("distinct")).isEqualTo(true);
            assertThat(coercer.coerce("table", Map.of("distinct", "false")).get("distinct")).isEqualTo(false);
            assertThat(coercer.coerce("table", Map.of("distinct", "TRUE")).get("distinct")).isEqualTo(true);
        }

        @Test
        @DisplayName("array: '[1,2,3]' → List(1,2,3) via JSON")
        void arrayJsonParses() {
            stubTool("table", List.of(ToolParameter.builder().name("rows").type("array").build()));
            Map<String, Object> out = coercer.coerce("table", Map.of("rows", "[1,2,3]"));
            List<Object> rows = asList(out.get("rows"));
            assertThat(rows).containsExactly(1, 2, 3);
        }

        @Test
        @DisplayName("array: 'a,b,c' → List('a','b','c') via CSV fallback")
        void arrayCsvFallback() {
            stubTool("table", List.of(ToolParameter.builder().name("tags").type("array").build()));
            Map<String, Object> out = coercer.coerce("table", Map.of("tags", "a,b,c"));
            List<Object> tags = asList(out.get("tags"));
            assertThat(tags).containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("array CSV drops empty slots - 'a,,b' → ['a','b']")
        void arrayCsvDropsEmptySlots() {
            stubTool("table", List.of(ToolParameter.builder().name("tags").type("array").build()));
            Map<String, Object> out = coercer.coerce("table", Map.of("tags", "a,,b"));
            List<Object> tags = asList(out.get("tags"));
            assertThat(tags).containsExactly("a", "b");
        }

        @Test
        @DisplayName("object: '{\"k\":\"v\"}' → Map")
        void objectParses() {
            stubTool("table", List.of(ToolParameter.builder().name("filter").type("object").build()));
            Map<String, Object> out = coercer.coerce("table", Map.of("filter", "{\"k\":\"v\",\"n\":1}"));
            @SuppressWarnings("unchecked")
            Map<String, Object> filter = (Map<String, Object>) out.get("filter");
            assertThat(filter).containsEntry("k", "v").containsEntry("n", 1);
        }

        @Test
        @DisplayName("string: value passed through unchanged")
        void stringPassesThrough() {
            stubTool("table", List.of(ToolParameter.builder().name("name").type("string").build()));
            Map<String, Object> out = coercer.coerce("table", Map.of("name", "foo"));
            assertThat(out.get("name")).isEqualTo("foo");
        }
    }

    @Nested
    @DisplayName("empty / blank handling")
    class EmptyBlank {

        @Test
        @DisplayName("integer with blank string passes through untouched (validator enforces required)")
        void integerBlankPassthrough() {
            stubTool("table", List.of(ToolParameter.builder().name("limit").type("integer").build()));
            Map<String, Object> out = coercer.coerce("table", Map.of("limit", "  "));
            assertThat(out.get("limit")).isEqualTo("  ");
        }

        @Test
        @DisplayName("number with empty string passes through untouched")
        void numberEmptyPassthrough() {
            stubTool("table", List.of(ToolParameter.builder().name("x").type("number").build()));
            Map<String, Object> out = coercer.coerce("table", Map.of("x", ""));
            assertThat(out.get("x")).isEqualTo("");
        }

        @Test
        @DisplayName("boolean with empty/blank string passes through unchanged - validator must see missing input, not silent false")
        void booleanEmptyPassthrough() {
            stubTool("table", List.of(ToolParameter.builder().name("flag").type("boolean").build()));
            // Matches integer/number passthrough so a required flag with "" → required-param error
            // instead of silently fabricating a false that passes validation.
            assertThat(coercer.coerce("table", Map.of("flag", "")).get("flag")).isEqualTo("");
            assertThat(coercer.coerce("table", Map.of("flag", "   ")).get("flag")).isEqualTo("   ");
        }

        @Test
        @DisplayName("array with empty string → empty list")
        void arrayEmptyIsEmptyList() {
            stubTool("table", List.of(ToolParameter.builder().name("tags").type("array").build()));
            Map<String, Object> out = coercer.coerce("table", Map.of("tags", ""));
            List<Object> tags = asList(out.get("tags"));
            assertThat(tags).isEmpty();
        }

        @Test
        @DisplayName("object with empty string → empty map")
        void objectEmptyIsEmptyMap() {
            stubTool("table", List.of(ToolParameter.builder().name("filter").type("object").build()));
            Map<String, Object> out = coercer.coerce("table", Map.of("filter", ""));
            assertThat((Map<?, ?>) out.get("filter")).isEmpty();
        }
    }

    @Nested
    @DisplayName("tolerance rules")
    class Tolerance {

        @Test
        @DisplayName("non-string value is left untouched - validator enforces type match")
        void nonStringIsNoop() {
            stubTool("table", List.of(ToolParameter.builder().name("limit").type("integer").build()));
            Map<String, Object> out = coercer.coerce("table", Map.of("limit", 10L));
            assertThat(out.get("limit")).isEqualTo(10L);
        }

        @Test
        @DisplayName("null value inside map stays null (missing keys not added)")
        void nullValueIsNoop() {
            stubTool("table", List.of(ToolParameter.builder().name("limit").type("integer").build()));
            Map<String, Object> in = new HashMap<>();
            in.put("limit", null);
            Map<String, Object> out = coercer.coerce("table", in);
            assertThat(out).containsKey("limit");
            assertThat(out.get("limit")).isNull();
        }

        @Test
        @DisplayName("param not declared in definition passes through unchanged")
        void unknownParamPassesThrough() {
            stubTool("table", List.of(ToolParameter.builder().name("limit").type("integer").build()));
            Map<String, Object> out = coercer.coerce("table", Map.of("limit", "10", "unknown_param", "raw"));
            assertThat(out).containsEntry("limit", 10L).containsEntry("unknown_param", "raw");
        }

        @Test
        @DisplayName("param with null type falls through unchanged")
        void nullTypePassesThrough() {
            stubTool("table", List.of(ToolParameter.builder().name("weird").type(null).build()));
            Map<String, Object> out = coercer.coerce("table", Map.of("weird", "stay"));
            assertThat(out.get("weird")).isEqualTo("stay");
        }

        @Test
        @DisplayName("unknown declared type is a pass-through (no exception)")
        void unknownTypeIsPassthrough() {
            stubTool("table", List.of(ToolParameter.builder().name("custom").type("uuid").build()));
            Map<String, Object> out = coercer.coerce("table", Map.of("custom", "abc"));
            assertThat(out.get("custom")).isEqualTo("abc");
        }
    }

    @Nested
    @DisplayName("best-effort fallback on parse failure")
    class ParseFailures {

        @Test
        @DisplayName("unparseable integer leaves the raw string for the validator to reject")
        void integerUnparseableLeftAsIs() {
            stubTool("table", List.of(ToolParameter.builder().name("limit").type("integer").build()));
            Map<String, Object> out = coercer.coerce("table", Map.of("limit", "not-a-number"));
            assertThat(out.get("limit")).isEqualTo("not-a-number");
        }

        @Test
        @DisplayName("unparseable number leaves the raw string")
        void numberUnparseableLeftAsIs() {
            stubTool("table", List.of(ToolParameter.builder().name("x").type("number").build()));
            Map<String, Object> out = coercer.coerce("table", Map.of("x", "abc"));
            assertThat(out.get("x")).isEqualTo("abc");
        }

        @Test
        @DisplayName("malformed JSON object leaves the raw string")
        void objectMalformedLeftAsIs() {
            stubTool("table", List.of(ToolParameter.builder().name("filter").type("object").build()));
            Map<String, Object> out = coercer.coerce("table", Map.of("filter", "{not json"));
            assertThat(out.get("filter")).isEqualTo("{not json");
        }

        @Test
        @DisplayName("malformed JSON array with leading bracket leaves the raw string (no CSV fallback)")
        void arrayMalformedJsonLeftAsIs() {
            stubTool("table", List.of(ToolParameter.builder().name("rows").type("array").build()));
            Map<String, Object> out = coercer.coerce("table", Map.of("rows", "[1, 2"));
            assertThat(out.get("rows")).isEqualTo("[1, 2");
        }
    }

    @Nested
    @DisplayName("case-insensitive type matching")
    class TypeCasing {

        @Test
        @DisplayName("uppercase declared type still coerces ('INTEGER' → integer)")
        void uppercaseTypeMatches() {
            stubTool("table", List.of(ToolParameter.builder().name("n").type("INTEGER").build()));
            Map<String, Object> out = coercer.coerce("table", Map.of("n", "5"));
            assertThat(out.get("n")).isEqualTo(5L);
        }
    }
}
