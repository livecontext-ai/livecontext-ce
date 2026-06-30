package com.apimarketplace.orchestrator.services.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branch coverage for {@link McpResultNormalizer#canonicalize(Object)}, which
 * unwraps at most one redundant envelope so both the API typed-execution path
 * ({@code {output:{...}}}) and the bridge REMOTE_MCP path
 * ({@code {content:[...],structuredContent:{...}}}) converge on the canonical
 * {@code label.output.<field>} shape before {@link CatalogToolsGateway} flattens.
 *
 * <p>The "why" each assertion guards: a wrong unwrap (or a failure to unwrap)
 * produces the infamous double {@code label.output.output.<field>} path that
 * downstream nodes mis-read.
 */
@DisplayName("McpResultNormalizer.canonicalize")
class McpResultNormalizerTest {

    @Nested
    @DisplayName("Non-map / empty inputs are returned unchanged")
    class PassThroughTrivial {

        @Test
        @DisplayName("null input is returned as-is")
        void nullInput() {
            assertThat(McpResultNormalizer.canonicalize(null)).isNull();
        }

        @Test
        @DisplayName("non-map input (String) is returned unchanged by identity")
        void stringInput() {
            String input = "already-a-scalar";
            Object out = McpResultNormalizer.canonicalize(input);
            // Conservative: scalars are not envelopes, so identity is preserved.
            assertThat(out).isSameAs(input);
        }

        @Test
        @DisplayName("non-map input (List) is returned unchanged by identity")
        void listInput() {
            List<String> input = List.of("a", "b");
            assertThat(McpResultNormalizer.canonicalize(input)).isSameAs(input);
        }

        @Test
        @DisplayName("empty map is returned unchanged by identity (no envelope to unwrap)")
        void emptyMap() {
            Map<String, Object> input = Map.of();
            assertThat(McpResultNormalizer.canonicalize(input)).isSameAs(input);
        }
    }

    @Nested
    @DisplayName("Bridge structuredContent takes precedence")
    class StructuredContent {

        @Test
        @DisplayName("non-empty structuredContent map is returned as the canonical payload")
        void structuredContentReturned() {
            Map<String, Object> typed = Map.of("temperature", 21, "unit", "C");
            Map<String, Object> envelope = Map.of(
                    "structuredContent", typed,
                    "content", List.of(Map.of("type", "text", "text", "{\"ignored\":true}")));

            Object out = McpResultNormalizer.canonicalize(envelope);

            // structuredContent is the typed payload; the text content block is ignored.
            assertThat(out).isSameAs(typed);
        }

        @Test
        @DisplayName("empty structuredContent map is ignored, falls through to content unwrap")
        void emptyStructuredContentFallsThroughToContent() {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("structuredContent", Map.of()); // empty → not used
            envelope.put("content", List.of(Map.of("type", "text", "text", "{\"k\":\"v\"}")));

            Object out = McpResultNormalizer.canonicalize(envelope);

            assertThat(out).isEqualTo(Map.of("k", "v"));
        }

        @Test
        @DisplayName("non-map structuredContent (string) is ignored, falls through to content unwrap")
        void nonMapStructuredContentFallsThrough() {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("structuredContent", "not-a-map");
            envelope.put("content", List.of(Map.of("type", "text", "text", "{\"k\":\"v\"}")));

            Object out = McpResultNormalizer.canonicalize(envelope);

            assertThat(out).isEqualTo(Map.of("k", "v"));
        }
    }

    @Nested
    @DisplayName("MCP content[] text-block unwrap")
    class ContentUnwrap {

        @Test
        @DisplayName("text block holding a JSON object is parsed into a Map")
        void textBlockJsonObjectParsed() {
            Map<String, Object> envelope = Map.of(
                    "content", List.of(Map.of("type", "text",
                            "text", "{\"name\":\"Ada\",\"count\":2}")));

            Object out = McpResultNormalizer.canonicalize(envelope);

            assertThat(out).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) out;
            assertThat(map).containsEntry("name", "Ada").containsEntry("count", 2);
        }

        @Test
        @DisplayName("text block holding a JSON array is parsed into a List")
        void textBlockJsonArrayParsed() {
            Map<String, Object> envelope = Map.of(
                    "content", List.of(Map.of("type", "text", "text", "[1,2,3]")));

            Object out = McpResultNormalizer.canonicalize(envelope);

            assertThat(out).isInstanceOf(List.class);
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) out;
            assertThat(list).containsExactly(1, 2, 3);
        }

        @Test
        @DisplayName("text block with JSON-looking-but-invalid string falls back to {text:<raw>}")
        void invalidJsonKeptAsTextShape() {
            // Starts with '{' so the parse is attempted, but it is malformed.
            Map<String, Object> envelope = Map.of(
                    "content", List.of(Map.of("type", "text", "text", "{not valid json")));

            Object out = McpResultNormalizer.canonicalize(envelope);

            // The ORIGINAL (untrimmed) text is preserved under "text".
            assertThat(out).isEqualTo(Map.of("text", "{not valid json"));
        }

        @Test
        @DisplayName("plain (non-JSON) text block is surfaced as {text:<value>}")
        void plainTextBlockSurfaced() {
            Map<String, Object> envelope = Map.of(
                    "content", List.of(Map.of("type", "text", "text", "just words")));

            Object out = McpResultNormalizer.canonicalize(envelope);

            assertThat(out).isEqualTo(Map.of("text", "just words"));
        }

        @Test
        @DisplayName("type omitted is treated as a text block and unwrapped")
        void missingTypeTreatedAsText() {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("text", "{\"ok\":true}"); // no "type" key
            Map<String, Object> envelope = Map.of("content", List.of(block));

            Object out = McpResultNormalizer.canonicalize(envelope);

            assertThat(out).isEqualTo(Map.of("ok", true));
        }

        @Test
        @DisplayName("non-text typed block is skipped, next usable text block wins")
        void nonTextBlockSkipped() {
            List<Object> blocks = new ArrayList<>();
            blocks.add(Map.of("type", "image", "data", "base64..."));   // skipped
            blocks.add(Map.of("type", "text", "text", "{\"after\":1}")); // used
            Map<String, Object> envelope = Map.of("content", blocks);

            Object out = McpResultNormalizer.canonicalize(envelope);

            assertThat(out).isEqualTo(Map.of("after", 1));
        }

        @Test
        @DisplayName("blank text block yields no usable block → original envelope unchanged")
        void blankTextLeavesEnvelopeIntact() {
            Map<String, Object> envelope = Map.of(
                    "content", List.of(Map.of("type", "text", "text", "   ")));

            Object out = McpResultNormalizer.canonicalize(envelope);

            // No usable block → unwrap returns null → envelope passes through unchanged.
            assertThat(out).isSameAs(envelope);
        }

        @Test
        @DisplayName("empty content list yields no usable block → envelope unchanged")
        void emptyContentListLeavesEnvelopeIntact() {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("content", List.of());

            Object out = McpResultNormalizer.canonicalize(envelope);

            assertThat(out).isSameAs(envelope);
        }

        @Test
        @DisplayName("content present but not a list → envelope unchanged")
        void nonListContentLeavesEnvelopeIntact() {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("content", "stringified");

            Object out = McpResultNormalizer.canonicalize(envelope);

            assertThat(out).isSameAs(envelope);
        }

        @Test
        @DisplayName("non-map block is skipped, following text block is used")
        void nonMapBlockSkipped() {
            List<Object> blocks = new ArrayList<>();
            blocks.add("loose-string-block");                            // skipped (not a Map)
            blocks.add(Map.of("type", "text", "text", "{\"x\":9}"));    // used
            Map<String, Object> envelope = Map.of("content", blocks);

            Object out = McpResultNormalizer.canonicalize(envelope);

            assertThat(out).isEqualTo(Map.of("x", 9));
        }
    }

    @Nested
    @DisplayName("Single {output:{...}} wrapper unwrap")
    class OutputWrapper {

        @Test
        @DisplayName("sole output key whose value is a map is unwrapped (back-compat output.output alias)")
        void soleOutputMapUnwrapped() {
            Map<String, Object> inner = Map.of("status", "ok", "id", 7);
            Map<String, Object> envelope = Map.of("output", inner);

            Object out = McpResultNormalizer.canonicalize(envelope);

            assertThat(out).isSameAs(inner);
        }

        @Test
        @DisplayName("output alongside other keys is NOT unwrapped (legitimate field named output)")
        void outputWithSiblingsNotUnwrapped() {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("output", Map.of("v", 1));
            envelope.put("status", "ok");

            Object out = McpResultNormalizer.canonicalize(envelope);

            // size > 1 → conservative pass-through, the whole envelope is preserved.
            assertThat(out).isSameAs(envelope);
        }

        @Test
        @DisplayName("sole output key whose value is NOT a map is left intact")
        void soleOutputScalarNotUnwrapped() {
            Map<String, Object> envelope = Map.of("output", "scalar-value");

            Object out = McpResultNormalizer.canonicalize(envelope);

            assertThat(out).isSameAs(envelope);
        }
    }

    @Nested
    @DisplayName("Already-canonical / unrecognized shapes pass through unmutated")
    class PassThroughCanonical {

        @Test
        @DisplayName("already-flat result is returned unchanged and unmutated")
        void alreadyFlatUnchanged() {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("title", "Hello");
            canonical.put("score", 42);
            Map<String, Object> snapshot = new LinkedHashMap<>(canonical);

            Object out = McpResultNormalizer.canonicalize(canonical);

            assertThat(out).isSameAs(canonical);
            // Pass-through must NOT mutate already-good input.
            assertThat(canonical).isEqualTo(snapshot);
        }

        @Test
        @DisplayName("map with multiple keys and no envelope markers passes through by identity")
        void multiKeyNoEnvelopePassThrough() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("a", 1);
            input.put("b", 2);

            assertThat(McpResultNormalizer.canonicalize(input)).isSameAs(input);
        }
    }
}
