package com.apimarketplace.orchestrator.service;

import com.apimarketplace.orchestrator.domain.NodeTypeDocumentationEntity;
import com.apimarketplace.orchestrator.repository.NodeTypeDocumentationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DisplayName("NodeLibraryService Tests")
@ExtendWith(MockitoExtension.class)
class NodeLibraryServiceTest {

    @Mock
    private NodeTypeDocumentationRepository repository;

    private NodeLibraryService service;

    @BeforeEach
    void setUp() {
        service = new NodeLibraryService(repository);
    }

    private NodeTypeDocumentationEntity buildNode(String type, String description, String prefix) {
        NodeTypeDocumentationEntity node = new NodeTypeDocumentationEntity();
        node.setType(type);
        node.setLabel(type.substring(0, 1).toUpperCase() + type.substring(1));
        node.setCategory("test");
        node.setDescription(description);
        node.setVariablePrefix(prefix);
        node.setEnabled(true);
        node.setParameters(Map.of("param1", Map.of("type", "string", "required", true)));
        node.setOutputs(Map.of("result", Map.of("type", "object", "description", "The result")));
        node.setConcepts(List.of("Concept 1", "Concept 2"));
        node.setExamples(List.of("workflow(action='add_node', type='" + type + "')"));
        return node;
    }

    @Nested
    @DisplayName("getNodesByCategory")
    class GetNodesByCategory {

        @Test
        @DisplayName("Should return lightweight summary with type and description only")
        void returnsSummaryFormat() {
            var webhook = buildNode("webhook", "Triggers via HTTP webhook", "trigger");
            var schedule = buildNode("schedule", "Triggers on cron schedule", "trigger");
            when(repository.findByEnabledTrueAndVariablePrefix("trigger"))
                .thenReturn(List.of(webhook, schedule));

            Map<String, Object> result = service.getNodesByCategory("triggers");

            assertThat(result).containsEntry("category", "triggers");
            assertThat(result).containsEntry("prefix", "trigger");
            assertThat(result).containsEntry("node_count", 2);
            assertThat(result).containsKey("detail");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nodes = (List<Map<String, Object>>) result.get("nodes");
            assertThat(nodes).hasSize(2);

            // Each node should ONLY have type + description
            Map<String, Object> first = nodes.get(0);
            assertThat(first).containsOnlyKeys("type", "description");
            assertThat(first).containsEntry("type", "webhook");
            assertThat(first).containsEntry("description", "Triggers via HTTP webhook");
        }

        @Test
        @DisplayName("Summary should NOT contain parameters, outputs, concepts, or examples")
        void doesNotContainHeavyFields() {
            var decision = buildNode("decision", "If/else branching", "core");
            when(repository.findByEnabledTrueAndVariablePrefix("core"))
                .thenReturn(List.of(decision));

            Map<String, Object> result = service.getNodesByCategory("cores");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nodes = (List<Map<String, Object>>) result.get("nodes");
            Map<String, Object> node = nodes.get(0);

            assertThat(node).doesNotContainKey("parameters");
            assertThat(node).doesNotContainKey("outputs");
            assertThat(node).doesNotContainKey("concepts");
            assertThat(node).doesNotContainKey("examples");
            assertThat(node).doesNotContainKey("edge_ports");
            assertThat(node).doesNotContainKey("comparison");
            assertThat(node).doesNotContainKey("global_variables");
        }

        @Test
        @DisplayName("Should include detail hint for drill-down")
        void includesDetailHint() {
            when(repository.findByEnabledTrueAndVariablePrefix("agent"))
                .thenReturn(List.of(buildNode("agent", "AI agent", "agent")));

            Map<String, Object> result = service.getNodesByCategory("agents");

            String detail = (String) result.get("detail");
            assertThat(detail).contains("workflow(action='help', topics=['<node_type>'])");
        }

        @Test
        @DisplayName("Should resolve category by prefix alias")
        void resolvesByPrefix() {
            when(repository.findByEnabledTrueAndVariablePrefix("trigger"))
                .thenReturn(List.of(buildNode("webhook", "HTTP webhook", "trigger")));

            // "trigger" is a prefix, "triggers" is the category name - both should work
            Map<String, Object> result = service.getNodesByCategory("trigger");
            assertThat(result).containsEntry("category", "triggers");
        }

        @Test
        @DisplayName("Should return error for unknown category")
        void unknownCategory() {
            Map<String, Object> result = service.getNodesByCategory("nonexistent");
            assertThat(result).containsKey("error");
        }
    }

    @Nested
    @DisplayName("getNodesBatch - single node drill-down keeps full detail")
    class GetNodesBatch {

        @Test
        @DisplayName("Should return full node details for specific node requests")
        void returnsFullDetail() {
            var webhook = buildNode("webhook", "Triggers via HTTP webhook", "trigger");
            when(repository.findByType("webhook")).thenReturn(Optional.of(webhook));

            Map<String, Object> result = service.getNodesBatch(List.of("webhook"));

            assertThat(result).containsEntry("found", 1);

            @SuppressWarnings("unchecked")
            Map<String, Object> nodes = (Map<String, Object>) result.get("nodes");
            @SuppressWarnings("unchecked")
            Map<String, Object> node = (Map<String, Object>) nodes.get("webhook");

            // Full detail SHOULD include parameters, outputs, etc.
            assertThat(node).containsKey("parameters");
            assertThat(node).containsKey("outputs");
            assertThat(node).containsKey("description");
        }

        @Test
        @DisplayName("Should report not_found for unknown types")
        void reportsNotFound() {
            when(repository.findByType("nonexistent")).thenReturn(Optional.empty());

            Map<String, Object> result = service.getNodesBatch(List.of("nonexistent"));

            assertThat(result).containsEntry("found", 0);
            @SuppressWarnings("unchecked")
            List<String> notFound = (List<String>) result.get("not_found");
            assertThat(notFound).containsExactly("nonexistent");
        }
    }
}
