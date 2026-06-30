package com.apimarketplace.orchestrator.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.orchestrator.domain.NodeTypeDocumentationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DisplayName("NodeHelpFormatter - live model catalog rewrites")
@ExtendWith(MockitoExtension.class)
class NodeHelpFormatterTest {

    @Mock
    private AgentClient agentClient;

    private NodeHelpFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new NodeHelpFormatter(new ModelCatalogEnricher(agentClient));
    }

    /** Build a classify/guardrail node with the SAME param shape as the V11 Flyway seed. */
    private NodeTypeDocumentationEntity buildLlmNode(String type) {
        NodeTypeDocumentationEntity node = new NodeTypeDocumentationEntity();
        node.setType(type);
        node.setDescription("AI " + type);
        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("enum", List.of("openai", "anthropic"));
        provider.put("default", "openai");
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("default", "claude-sonnet-4-20250514");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("provider", provider);
        params.put("model", model);
        node.setParameters(params);
        return node;
    }

    /** Build a minimal live-catalog payload as returned by {@code /api/internal/agent/models}. */
    private Map<String, Object> liveCatalog(List<String> providers, String defaultProvider, String defaultModel) {
        List<Map<String, Object>> providerEntries = new java.util.ArrayList<>();
        for (String name : providers) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            providerEntries.add(entry);
        }
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("providers", providerEntries);
        catalog.put("defaultProvider", defaultProvider);
        catalog.put("defaultModel", defaultModel);
        return catalog;
    }

    @Nested
    @DisplayName("classify + guardrail - live catalog rewrites")
    class LiveRewrite {

        @Test
        @DisplayName("classify help reflects live provider enum + live defaults from agent-service")
        void classifyNodeGetsLiveCatalog() {
            NodeTypeDocumentationEntity node = buildLlmNode("classify");
            when(agentClient.getModelsInfo()).thenReturn(
                liveCatalog(List.of("anthropic", "openai", "zai"), "anthropic", "claude-opus-4-7"));

            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) formatter.formatNodeHelp(node).get("params");
            @SuppressWarnings("unchecked")
            Map<String, Object> provider = (Map<String, Object>) params.get("provider");
            @SuppressWarnings("unchecked")
            Map<String, Object> model = (Map<String, Object>) params.get("model");

            assertThat(provider.get("enum")).isEqualTo(List.of("anthropic", "openai", "zai"));
            assertThat(provider.get("default")).isEqualTo("anthropic");
            assertThat(model.get("default")).isEqualTo("claude-opus-4-7");
        }

        @Test
        @DisplayName("guardrail help reflects live provider enum + live defaults - identical path to classify")
        void guardrailNodeGetsLiveCatalog() {
            NodeTypeDocumentationEntity node = buildLlmNode("guardrail");
            when(agentClient.getModelsInfo()).thenReturn(
                liveCatalog(List.of("anthropic", "openai"), "openai", "gpt-5"));

            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) formatter.formatNodeHelp(node).get("params");
            @SuppressWarnings("unchecked")
            Map<String, Object> provider = (Map<String, Object>) params.get("provider");
            @SuppressWarnings("unchecked")
            Map<String, Object> model = (Map<String, Object>) params.get("model");

            assertThat(provider.get("enum")).isEqualTo(List.of("anthropic", "openai"));
            assertThat(provider.get("default")).isEqualTo("openai");
            assertThat(model.get("default")).isEqualTo("gpt-5");
        }

        @Test
        @DisplayName("Rewrite does NOT mutate the entity's shared parameters map (defensive copy)")
        void doesNotMutateEntityParameters() {
            NodeTypeDocumentationEntity node = buildLlmNode("classify");
            Map<String, Object> originalEntityParams = node.getParameters();
            Object originalProviderEnum = ((Map<?, ?>) originalEntityParams.get("provider")).get("enum");
            Object originalModelDefault = ((Map<?, ?>) originalEntityParams.get("model")).get("default");
            when(agentClient.getModelsInfo()).thenReturn(
                liveCatalog(List.of("x", "y"), "x", "x-model-1"));

            formatter.formatNodeHelp(node);

            assertThat(((Map<?, ?>) originalEntityParams.get("provider")).get("enum"))
                .as("entity's provider.enum must remain the seeded value")
                .isEqualTo(originalProviderEnum);
            assertThat(((Map<?, ?>) originalEntityParams.get("model")).get("default"))
                .as("entity's model.default must remain the seeded value")
                .isEqualTo(originalModelDefault);
        }
    }

    @Nested
    @DisplayName("Non-LLM nodes - no catalog lookup, no mutation")
    class NonLlmNodes {

        @Test
        @DisplayName("transform node passes through verbatim (no call to agent-service)")
        void transformNodeUntouched() {
            NodeTypeDocumentationEntity node = new NodeTypeDocumentationEntity();
            node.setType("transform");
            node.setDescription("Transform");
            node.setParameters(Map.of("expression", Map.of("type", "string")));

            Map<String, Object> result = formatter.formatNodeHelp(node);

            assertThat(result.get("type")).isEqualTo("transform");
            assertThat(result.get("params")).isEqualTo(Map.of("expression", Map.of("type", "string")));
            // Enricher is invoked but returns params unchanged for non-LLM; AgentClient never consulted.
            org.mockito.Mockito.verifyNoInteractions(agentClient);
        }
    }

    @Nested
    @DisplayName("Fail-soft - seeded values preserved when catalog unavailable")
    class FailSoft {

        @Test
        @DisplayName("Empty catalog response keeps seeded provider enum and defaults")
        void emptyCatalogPreservesSeed() {
            NodeTypeDocumentationEntity node = buildLlmNode("classify");
            when(agentClient.getModelsInfo()).thenReturn(Map.of());

            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) formatter.formatNodeHelp(node).get("params");
            @SuppressWarnings("unchecked")
            Map<String, Object> provider = (Map<String, Object>) params.get("provider");
            @SuppressWarnings("unchecked")
            Map<String, Object> model = (Map<String, Object>) params.get("model");

            assertThat(provider.get("enum")).isEqualTo(List.of("openai", "anthropic"));
            assertThat(provider.get("default")).isEqualTo("openai");
            assertThat(model.get("default")).isEqualTo("claude-sonnet-4-20250514");
        }

        @Test
        @DisplayName("Unexpected exception from AgentClient keeps seeded values - help must not fail the workflow tool")
        void thrownExceptionPreservesSeed() {
            NodeTypeDocumentationEntity node = buildLlmNode("guardrail");
            when(agentClient.getModelsInfo()).thenThrow(new RuntimeException("connection refused"));

            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) formatter.formatNodeHelp(node).get("params");
            @SuppressWarnings("unchecked")
            Map<String, Object> provider = (Map<String, Object>) params.get("provider");
            @SuppressWarnings("unchecked")
            Map<String, Object> model = (Map<String, Object>) params.get("model");

            assertThat(provider.get("enum")).isEqualTo(List.of("openai", "anthropic"));
            assertThat(provider.get("default")).isEqualTo("openai");
            assertThat(model.get("default")).isEqualTo("claude-sonnet-4-20250514");
        }

        @Test
        @DisplayName("Partial catalog (defaults only, no providers list) still rewrites defaults and leaves enum untouched")
        void partialCatalogRewritesDefaultsOnly() {
            NodeTypeDocumentationEntity node = buildLlmNode("classify");
            Map<String, Object> catalog = new LinkedHashMap<>();
            catalog.put("providers", List.of());
            catalog.put("defaultProvider", "anthropic");
            catalog.put("defaultModel", "claude-sonnet-4-6");
            when(agentClient.getModelsInfo()).thenReturn(catalog);

            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) formatter.formatNodeHelp(node).get("params");
            @SuppressWarnings("unchecked")
            Map<String, Object> provider = (Map<String, Object>) params.get("provider");
            @SuppressWarnings("unchecked")
            Map<String, Object> model = (Map<String, Object>) params.get("model");

            assertThat(provider.get("enum"))
                .as("Empty provider list from catalog must NOT overwrite the seeded enum")
                .isEqualTo(List.of("openai", "anthropic"));
            assertThat(provider.get("default")).isEqualTo("anthropic");
            assertThat(model.get("default")).isEqualTo("claude-sonnet-4-6");
        }
    }
}
