package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.OutputFieldDef;
import com.apimarketplace.orchestrator.services.impl.CatalogMockClient;
import com.apimarketplace.orchestrator.services.persistence.schema.NodeDefinitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MockOutputSuggester} - the "start from something real"
 * half of the mock mode: projected catalog example for mcp catalog tools,
 * schema-synthesized skeleton for every other family, free-authoring fallback.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MockOutputSuggester - proposed mock outputs")
class MockOutputSuggesterTest {

    @Mock private NodeDefinitionRegistry registry;
    @Mock private CatalogMockClient catalogMockClient;

    private MockOutputSuggester suggester;

    @BeforeEach
    void setUp() {
        suggester = new MockOutputSuggester(registry);
        suggester.setCatalogMockClient(catalogMockClient);
    }

    @Test
    @DisplayName("mcp catalog tool: proposes the projected example WITHOUT transport envelope keys")
    void mcpProposesCatalogExample() {
        Map<String, Object> example = new HashMap<>(Map.of(
                "tool_id", "slack/post-message", "execution", true, "message", "served",
                "http_status", 200, "metadata", Map.of(),
                "ok", true, "channel", "C123"));
        when(catalogMockClient.fetchProjectedExample(eq("slack/post-message"), eq("tenant-1")))
                .thenReturn(example);
        Map<String, Object> node = Map.of("id", "slack/post-message", "type", "mcp", "label", "Post Message");

        MockOutputSuggester.Suggestion suggestion =
                suggester.suggest("mcp:post_message", node, "tenant-1");

        assertThat(suggestion.source()).isEqualTo("catalog_example");
        assertThat(suggestion.output())
                .containsEntry("ok", true)
                .containsEntry("channel", "C123")
                .doesNotContainKeys("tool_id", "execution", "metadata", "message", "http_status");
        assertThat(suggestion.hint()).contains("catalog_example");
    }

    @Test
    @DisplayName("mcp catalog fetch failure falls back to the schema skeleton path")
    void mcpFallsBackToSchemaOnFetchFailure() {
        when(catalogMockClient.fetchProjectedExample(eq("slack/post-message"), eq("tenant-1")))
                .thenThrow(new CatalogMockClient.MockExampleUnavailableException("nope"));
        when(registry.get("MCP")).thenReturn(Optional.empty());
        Map<String, Object> node = Map.of("id", "slack/post-message", "type", "mcp", "label", "Post Message");

        MockOutputSuggester.Suggestion suggestion =
                suggester.suggest("mcp:post_message", node, "tenant-1");

        assertThat(suggestion.source()).isEqualTo("none");
        assertThat(suggestion.output()).isEmpty();
        assertThat(suggestion.hint()).contains("mcp:post_message");
    }

    @Test
    @DisplayName("non-mcp node: skeleton synthesized from the declared output schema (defaults win, placeholders by type, runtime-only skipped)")
    void schemaSkeletonForCoreNode() {
        NodeDefinition definition = NodeDefinition.builder()
                .nodeType("TRANSFORM")
                .outputs(List.of(
                        OutputFieldDef.builder().key("result").type("object")
                                .children(List.of(
                                        OutputFieldDef.builder().key("total").type("number").build()))
                                .build(),
                        OutputFieldDef.builder().key("success").type("boolean").defaultValue(true).build(),
                        OutputFieldDef.builder().key("items").type("array").build(),
                        OutputFieldDef.builder().key("current_item").type("object").runtimeOnly(true).build()))
                .build();
        when(registry.get("TRANSFORM")).thenReturn(Optional.of(definition));
        Map<String, Object> node = Map.of("id", "t-1", "type", "transform", "label", "Format");

        MockOutputSuggester.Suggestion suggestion =
                suggester.suggest("core:format", node, "tenant-1");

        assertThat(suggestion.source()).isEqualTo("schema");
        assertThat(suggestion.output())
                .containsEntry("success", true)
                .containsEntry("items", List.of())
                .containsEntry("result", Map.of("total", 0))
                .doesNotContainKey("current_item");
    }

    @Test
    @DisplayName("node type resolution mirrors runtime tags (classify agent -> CLASSIFY, crud table -> GET_ROWS)")
    void nodeTypeResolutionMirrorsRuntime() {
        lenient().when(registry.get("CLASSIFY")).thenReturn(Optional.empty());
        lenient().when(registry.get("GET_ROWS")).thenReturn(Optional.empty());

        suggester.suggest("agent:sort", Map.of("type", "classify", "label", "Sort"), "t");
        suggester.suggest("table:find", Map.of("type", "crud-read-row", "label", "Find"), "t");

        org.mockito.Mockito.verify(registry).get("CLASSIFY");
        org.mockito.Mockito.verify(registry).get("GET_ROWS");
    }
}
