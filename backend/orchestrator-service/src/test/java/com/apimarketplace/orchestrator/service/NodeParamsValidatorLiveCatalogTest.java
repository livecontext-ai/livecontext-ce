package com.apimarketplace.orchestrator.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.orchestrator.domain.NodeTypeDocumentationEntity;
import com.apimarketplace.orchestrator.service.validation.ValidationResult;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Regression tests proving NodeParamsValidator consults the live model catalog for
 * classify/guardrail - preventing the divergence where the help layer advertised a
 * live-catalog provider (e.g. {@code zai}) but add_node still rejected it against
 * the stale V11 Flyway seed enum.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NodeParamsValidator - live model catalog provider enum")
class NodeParamsValidatorLiveCatalogTest {

    @Mock private NodeLibraryService nodeLibraryService;
    @Mock private AgentClient agentClient;

    private NodeParamsValidator validator;

    @BeforeEach
    void setUp() {
        validator = new NodeParamsValidator(nodeLibraryService, new ModelCatalogEnricher(agentClient));
    }

    /** Seed mirrors the V11 Flyway-style schema: hardcoded stale provider enum + default. */
    private NodeTypeDocumentationEntity seededLlmNode(String type) {
        NodeTypeDocumentationEntity node = new NodeTypeDocumentationEntity();
        node.setType(type);
        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("type", "string");
        provider.put("enum", List.of("openai", "anthropic", "google", "mistral", "deepseek"));
        provider.put("default", "openai");
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("type", "string");
        model.put("default", "claude-sonnet-4-20250514");
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("type", "string");
        prompt.put("required", false);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("provider", provider);
        params.put("model", model);
        params.put("prompt", prompt);
        // classify needs categories to exist in the schema (not required for these tests)
        if ("classify".equals(type)) {
            params.put("categories", Map.of("type", "array", "required", false));
        }
        if ("guardrail".equals(type)) {
            params.put("rules", Map.of("type", "object", "required", false));
            params.put("input", Map.of("type", "string", "required", false));
        }
        node.setParameters(params);
        return node;
    }

    private Map<String, Object> liveCatalog(List<String> providers, String defaultProvider, String defaultModel) {
        List<Map<String, Object>> providerEntries = new java.util.ArrayList<>();
        for (String name : providers) providerEntries.add(Map.of("name", name));
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("providers", providerEntries);
        catalog.put("defaultProvider", defaultProvider);
        catalog.put("defaultModel", defaultModel);
        return catalog;
    }

    @Nested
    @DisplayName("classify + guardrail - provider acceptance matches live catalog")
    class LiveAcceptance {

        @Test
        @DisplayName("classify accepts a provider present in live catalog but absent from stale seed (zai)")
        void classifyAcceptsLiveCatalogOnlyProvider() {
            when(nodeLibraryService.findByType("classify")).thenReturn(Optional.of(seededLlmNode("classify")));
            when(agentClient.getModelsInfo()).thenReturn(
                liveCatalog(List.of("anthropic", "openai", "zai"), "anthropic", "claude-opus-4-6"));

            ValidationResult result = validator.validate("classify", Map.of(
                "provider", "zai",
                "model", "glm-5-turbo",
                "prompt", "Classify: {{trigger:t.output.message}}"
            ));

            assertThat(result.valid())
                .as("zai is in the live catalog - must be accepted even though absent from V11 seed")
                .isTrue();
        }

        @Test
        @DisplayName("guardrail accepts a live-catalog-only provider via the same path as classify")
        void guardrailAcceptsLiveCatalogOnlyProvider() {
            when(nodeLibraryService.findByType("guardrail")).thenReturn(Optional.of(seededLlmNode("guardrail")));
            when(agentClient.getModelsInfo()).thenReturn(
                liveCatalog(List.of("anthropic", "openai", "zai"), "anthropic", "claude-opus-4-6"));

            ValidationResult result = validator.validate("guardrail", Map.of(
                "provider", "zai",
                "model", "glm-5-turbo",
                "input", "{{trigger:t.output.message}}",
                "rules", Map.of("pii", "block pii")
            ));

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("classify rejects a provider absent from BOTH live catalog and seed")
        void rejectsProviderAbsentFromLiveCatalog() {
            when(nodeLibraryService.findByType("classify")).thenReturn(Optional.of(seededLlmNode("classify")));
            when(agentClient.getModelsInfo()).thenReturn(
                liveCatalog(List.of("anthropic", "openai", "zai"), "anthropic", "claude-opus-4-6"));

            ValidationResult result = validator.validate("classify", Map.of(
                "provider", "pretendai",
                "prompt", "x"
            ));

            assertThat(result.valid()).isFalse();
            assertThat(result.errors())
                .anySatisfy(err -> {
                    assertThat(err.code()).isEqualTo("INVALID_ENUM");
                    assertThat(err.parameter()).isEqualTo("provider");
                });
        }
    }

    @Nested
    @DisplayName("Fail-soft - falls back to seeded enum when catalog unavailable")
    class FailSoft {

        @Test
        @DisplayName("When AgentClient throws, the seeded enum still accepts seeded providers")
        void catalogFailureFallsBackToSeededEnum() {
            when(nodeLibraryService.findByType("classify")).thenReturn(Optional.of(seededLlmNode("classify")));
            when(agentClient.getModelsInfo()).thenThrow(new RuntimeException("connection refused"));

            ValidationResult result = validator.validate("classify", Map.of(
                "provider", "openai",
                "prompt", "x"
            ));

            assertThat(result.valid())
                .as("seeded provider 'openai' must still validate under fail-soft")
                .isTrue();
        }

        @Test
        @DisplayName("Empty catalog keeps seeded enum - seeded-only providers still accepted")
        void emptyCatalogPreservesSeededEnum() {
            when(nodeLibraryService.findByType("classify")).thenReturn(Optional.of(seededLlmNode("classify")));
            when(agentClient.getModelsInfo()).thenReturn(Map.of());

            ValidationResult result = validator.validate("classify", Map.of(
                "provider", "mistral",
                "prompt", "x"
            ));

            assertThat(result.valid()).isTrue();
        }
    }
}
