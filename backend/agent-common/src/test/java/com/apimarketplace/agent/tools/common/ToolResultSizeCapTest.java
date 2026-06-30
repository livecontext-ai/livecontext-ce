package com.apimarketplace.agent.tools.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the shared byte-cap walker. These are the contract
 * every leaky path in iterations 1-6 will be retro-fitted against - if the
 * walker stops capping, every consumer (workflow output snapshots,
 * web_search.fetch markdown, agent_browse extracted_data, …) silently
 * starts leaking again.
 */
@DisplayName("ToolResultSizeCap")
class ToolResultSizeCapTest {

    @Nested
    @DisplayName("capLargeStrings - preserves structure, caps strings only")
    class CapLargeStringsOnly {

        @Test
        @DisplayName("string > MAX_STRING_BYTES (128 KB) is replaced by a truncation stub with preview + original_length")
        void largeStringCapped() {
            int size = ToolResultSizeCap.MAX_STRING_BYTES * 2;
            String big = "A".repeat(size);
            Object result = ToolResultSizeCap.capLargeStrings(big);

            assertThat(result).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> stub = (Map<String, Object>) result;
            assertThat(stub).containsEntry("truncated", true);
            assertThat((Integer) stub.get("original_length")).isEqualTo(size);
            assertThat(((String) stub.get("preview")).length())
                    .isLessThanOrEqualTo(ToolResultSizeCap.STRING_PREVIEW_BYTES);
            assertThat(stub).containsKey("note"); // base64-shaped → "note" hint
        }

        @Test
        @DisplayName("string ≤ MAX_STRING_BYTES passes through verbatim")
        void smallStringUntouched() {
            String s = "hello world";
            assertThat(ToolResultSizeCap.capLargeStrings(s)).isEqualTo(s);
        }

        @Test
        @DisplayName("string at exactly MAX_STRING_BYTES is NOT capped (threshold is strictly >, not >=)")
        void boundaryStringUntouched() {
            String exact = "A".repeat(ToolResultSizeCap.MAX_STRING_BYTES);
            assertThat(ToolResultSizeCap.capLargeStrings(exact)).isEqualTo(exact);
        }

        @Test
        @DisplayName("Wikipedia-length article (~80 KB) passes through verbatim - legitimate text shouldn't be truncated")
        void typicalWikipediaArticleNotCapped() {
            // 80 KB of text-shaped content (no base64 alphabet, has punctuation)
            StringBuilder sb = new StringBuilder(80 * 1024);
            String para = "This is a sentence with words, punctuation, and numbers like 42. ";
            while (sb.length() < 80 * 1024) sb.append(para);
            String article = sb.substring(0, 80 * 1024);

            Object result = ToolResultSizeCap.capLargeStrings(article);
            assertThat(result)
                    .as("80 KB legitimate markdown must NOT be truncated - that was the v2.1 driver to bump cap from 32 KB → 128 KB")
                    .isEqualTo(article);
        }

        @Test
        @DisplayName("FileRef Map is NOT walked - passes through verbatim (lightweight reference, no recurse inside)")
        void fileRefPassThrough() {
            Map<String, Object> fileRef = Map.of(
                    "_type", "file",
                    "path", "tenant/general/catalog-binary/img.png",
                    "name", "img.png",
                    "mimeType", "image/png",
                    "size", 1_500_000L
            );
            assertThat(ToolResultSizeCap.capLargeStrings(fileRef)).isEqualTo(fileRef);
        }

        @Test
        @DisplayName("large list is NOT collapsed (only strings get capped)")
        void largeListNotCollapsed() {
            List<Map<String, Object>> bigList = new java.util.ArrayList<>();
            for (int i = 0; i < 50; i++) bigList.add(Map.of("id", i, "name", "row-" + i));
            Object result = ToolResultSizeCap.capLargeStrings(bigList);

            assertThat(result).isInstanceOf(List.class);
            assertThat((List<?>) result).hasSize(50);
        }

        @Test
        @DisplayName("string nested deep in Map+List structure gets capped")
        void deeplyNestedStringCapped() {
            String big = "B".repeat(ToolResultSizeCap.MAX_STRING_BYTES + 1024);
            Map<String, Object> tree = new LinkedHashMap<>();
            tree.put("candidates", List.of(Map.of(
                    "content", Map.of(
                            "parts", List.of(
                                    Map.of("inlineData", Map.of("data", big)))))));

            Object result = ToolResultSizeCap.capLargeStrings(tree);
            // Walk to the leaf - should be a stub, not the original 40 KB string.
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) result;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cands = (List<Map<String, Object>>) root.get("candidates");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> parts =
                    (List<Map<String, Object>>) ((Map<String, Object>) cands.get(0).get("content")).get("parts");
            Object data = ((Map<String, Object>) parts.get(0).get("inlineData")).get("data");
            assertThat(data).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> stub = (Map<String, Object>) data;
            assertThat(stub).containsEntry("truncated", true);
        }

        @Test
        @DisplayName("null input → null output (defensive)")
        void nullInput() {
            assertThat(ToolResultSizeCap.capLargeStrings((Object) null)).isNull();
        }
    }

    @Nested
    @DisplayName("capLargeStringsAndLists - also collapses large lists")
    class CapLargeStringsAndLists {

        @Test
        @DisplayName("list ≥ threshold is summarised with row_count + preview")
        void largeListCollapsed() {
            List<Map<String, Object>> bigList = new java.util.ArrayList<>();
            for (int i = 0; i < 50; i++) bigList.add(Map.of("id", i));

            Object result = ToolResultSizeCap.capLargeStringsAndLists(bigList,
                    /* threshold */ 4, /* preview */ 3);

            assertThat(result).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> stub = (Map<String, Object>) result;
            assertThat(stub).containsEntry("truncated", true)
                    .containsEntry("row_count", 50);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> preview = (List<Map<String, Object>>) stub.get("preview");
            assertThat(preview).hasSize(3);
        }

        @Test
        @DisplayName("list < threshold passes through")
        void smallListVerbatim() {
            List<Integer> l = List.of(1, 2, 3);
            Object result = ToolResultSizeCap.capLargeStringsAndLists(l, 4, 3);
            assertThat(result).isEqualTo(l);
        }
    }
}
