package com.apimarketplace.orchestrator.tools.workflow.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NodeFieldMerger}.
 *
 * <p>Each merge strategy gets its own nested class so a regression in one
 * strategy doesn't hide regressions in the others. The structure mirrors
 * the three strategies declared in the merger:
 * <ul>
 *   <li>{@link MergeMapStrategy} - params, actionMapping, variableMapping</li>
 *   <li>{@link MergeListByLabelStrategy} - switchCases, classifyCategories</li>
 *   <li>{@link ReplaceStrategy} - scalars and any unregistered field</li>
 * </ul>
 */
@DisplayName("NodeFieldMerger")
class NodeFieldMergerTest {

    @Nested
    @DisplayName("MERGE_MAP strategy (params, actionMapping, variableMapping, metadata)")
    class MergeMapStrategy {

        @Test
        @DisplayName("preserves untouched keys when LLM updates one param")
        void preservesUntouchedKeys() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("params", new LinkedHashMap<>(Map.of(
                    "q", "is:unread",
                    "maxResults", 20,
                    "labelIds", "INBOX"
            )));

            NodeFieldMerger.merge(node, "params", Map.of("maxResults", 50));

            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) node.get("params");
            assertThat(params)
                    .containsEntry("q", "is:unread")
                    .containsEntry("maxResults", 50)
                    .containsEntry("labelIds", "INBOX");
        }

        @Test
        @DisplayName("adds new keys to existing params")
        void addsNewKeys() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("params", new LinkedHashMap<>(Map.of("q", "is:unread")));

            NodeFieldMerger.merge(node, "params", Map.of("maxResults", 50, "format", "full"));

            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) node.get("params");
            assertThat(params)
                    .containsEntry("q", "is:unread")
                    .containsEntry("maxResults", 50)
                    .containsEntry("format", "full");
        }

        @Test
        @DisplayName("recursively merges nested map values")
        void recurseNestedMap() {
            Map<String, Object> node = new LinkedHashMap<>();
            Map<String, Object> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer old");
            headers.put("X-Custom", "abc");
            Map<String, Object> existingParams = new LinkedHashMap<>();
            existingParams.put("url", "https://api.example.com");
            existingParams.put("headers", headers);
            node.put("params", existingParams);

            // LLM only updates Authorization, doesn't mention X-Custom
            NodeFieldMerger.merge(node, "params", Map.of(
                    "headers", Map.of("Authorization", "Bearer new")
            ));

            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) node.get("params");
            assertThat(params).containsEntry("url", "https://api.example.com");
            @SuppressWarnings("unchecked")
            Map<String, Object> mergedHeaders = (Map<String, Object>) params.get("headers");
            assertThat(mergedHeaders)
                    .containsEntry("Authorization", "Bearer new")
                    .containsEntry("X-Custom", "abc");
        }

        @Test
        @DisplayName("explicit null on a sub-key removes it from the merged map")
        void nullSubKeyRemoves() {
            Map<String, Object> node = new LinkedHashMap<>();
            Map<String, Object> existing = new LinkedHashMap<>();
            existing.put("a", 1);
            existing.put("b", 2);
            node.put("params", existing);

            Map<String, Object> incoming = new LinkedHashMap<>();
            incoming.put("a", null);
            NodeFieldMerger.merge(node, "params", incoming);

            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) node.get("params");
            assertThat(params).doesNotContainKey("a").containsEntry("b", 2);
        }

        @Test
        @DisplayName("creates the field if it doesn't exist yet")
        void createsFieldIfMissing() {
            Map<String, Object> node = new LinkedHashMap<>();
            // node has no params yet

            NodeFieldMerger.merge(node, "params", Map.of("q", "is:unread"));

            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) node.get("params");
            assertThat(params).containsEntry("q", "is:unread");
        }

        @Test
        @DisplayName("actionMapping merges entries (interface node use case)")
        void actionMappingMerges() {
            Map<String, Object> node = new LinkedHashMap<>();
            Map<String, Object> existing = new LinkedHashMap<>();
            existing.put("save", "{{trigger:save}}");
            existing.put("delete", "{{trigger:delete}}");
            node.put("actionMapping", existing);

            NodeFieldMerger.merge(node, "actionMapping", Map.of("export", "{{trigger:export}}"));

            @SuppressWarnings("unchecked")
            Map<String, Object> mapping = (Map<String, Object>) node.get("actionMapping");
            assertThat(mapping).hasSize(3)
                    .containsEntry("save", "{{trigger:save}}")
                    .containsEntry("delete", "{{trigger:delete}}")
                    .containsEntry("export", "{{trigger:export}}");
        }
    }

    @Nested
    @DisplayName("MERGE_LIST_BY_LABEL strategy (switchCases, classifyCategories)")
    class MergeListByLabelStrategy {

        @Test
        @DisplayName("classifyCategories: adding one category preserves the others")
        void classifyAddsOneCategory() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("classifyCategories", List.of(
                    Map.of("label", "Finance", "description", "Bank emails"),
                    Map.of("label", "Tech", "description", "Dev tools")
            ));

            NodeFieldMerger.merge(node, "classifyCategories", List.of(
                    Map.of("label", "Spam", "description", "Junk")
            ));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> categories = (List<Map<String, Object>>) node.get("classifyCategories");
            assertThat(categories).hasSize(3);
            assertThat(categories).extracting(c -> c.get("label"))
                    .containsExactly("Spam", "Finance", "Tech");
        }

        @Test
        @DisplayName("classifyCategories: updating an existing label merges fields")
        void classifyUpdatesByLabel() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("classifyCategories", List.of(
                    new LinkedHashMap<>(Map.of("label", "Finance", "description", "Old"))
            ));

            NodeFieldMerger.merge(node, "classifyCategories", List.of(
                    Map.of("label", "Finance", "description", "Bank emails, invoices")
            ));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> categories = (List<Map<String, Object>>) node.get("classifyCategories");
            assertThat(categories).hasSize(1);
            assertThat(categories.get(0))
                    .containsEntry("label", "Finance")
                    .containsEntry("description", "Bank emails, invoices");
        }

        @Test
        @DisplayName("switchCases: same merge-by-label semantic")
        void switchCasesMerge() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("switchCases", List.of(
                    new LinkedHashMap<>(Map.of("id", "case-0", "type", "case", "label", "A", "value", "1")),
                    new LinkedHashMap<>(Map.of("id", "case-1", "type", "case", "label", "B", "value", "2"))
            ));

            NodeFieldMerger.merge(node, "switchCases", List.of(
                    Map.of("label", "C", "value", "3"),
                    Map.of("label", "A", "value", "99")  // update existing
            ));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cases = (List<Map<String, Object>>) node.get("switchCases");
            assertThat(cases).hasSize(3);
            // Order: incoming first (C, A), then preserved (B)
            assertThat(cases).extracting(c -> c.get("label"))
                    .containsExactly("C", "A", "B");
            // Updated A keeps original id (preserved from existing)
            Map<String, Object> aCase = cases.stream()
                    .filter(c -> "A".equals(c.get("label"))).findFirst().orElseThrow();
            assertThat(aCase.get("id")).isEqualTo("case-0");
            assertThat(aCase.get("value")).isEqualTo("99");
        }

        @Test
        @DisplayName("falls back to REPLACE when one side is not a list")
        void typeMismatchFallsBackToReplace() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("classifyCategories", "not-a-list");

            NodeFieldMerger.merge(node, "classifyCategories", List.of(Map.of("label", "X")));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> categories = (List<Map<String, Object>>) node.get("classifyCategories");
            assertThat(categories).hasSize(1);
        }
    }

    @Nested
    @DisplayName("REPLACE strategy (default)")
    class ReplaceStrategy {

        @Test
        @DisplayName("scalar fields are replaced")
        void scalarReplaced() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("label", "Old");

            NodeFieldMerger.merge(node, "label", "New");

            assertThat(node.get("label")).isEqualTo("New");
        }

        @Test
        @DisplayName("decisionConditions is REPLACE (positional roles)")
        void decisionConditionsReplaced() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("decisionConditions", List.of(
                    Map.of("id", "x-if", "type", "if", "label", "Yes"),
                    Map.of("id", "x-else", "type", "else", "label", "No")
            ));

            NodeFieldMerger.merge(node, "decisionConditions", List.of(
                    Map.of("id", "x-if", "type", "if", "label", "Positive"),
                    Map.of("id", "x-else", "type", "else", "label", "Negative")
            ));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> conditions = (List<Map<String, Object>>) node.get("decisionConditions");
            assertThat(conditions).hasSize(2);
            assertThat(conditions).extracting(c -> c.get("label"))
                    .containsExactly("Positive", "Negative");
        }

        @Test
        @DisplayName("explicit null deletes the field")
        void nullDeletes() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("description", "Old description");

            NodeFieldMerger.merge(node, "description", null);

            assertThat(node).doesNotContainKey("description");
        }
    }
}
