package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.service.ModelCatalogService;
import com.apimarketplace.agent.service.ModelCatalogService.AvailableModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AgentModelsController}.
 *
 * <p>Covers both the legacy nested catalog endpoint and the flat-list variant
 * that powers conversation-service's "Available AI Models" prompt injection.
 * The flat route is the critical contract - if its shape drifts,
 * {@code AgentConfigProvider.fetchAvailableModelsRemote()} silently degrades to
 * "empty catalog" and the LLM falls back to hallucinating training-data names.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentModelsController")
class AgentModelsControllerTest {

    @Mock private ModelCatalogService service;

    private AgentModelsController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentModelsController(service);
    }

    @Test
    @DisplayName("/models without category returns the legacy global catalog")
    void nestedEndpointDelegatesToService() {
        Map<String, Object> nested = Map.of("providers", List.of());
        when(service.getModelsForCategory(null, "tenant-1")).thenReturn(nested);

        ResponseEntity<Map<String, Object>> response = controller.getAvailableModels(null, "tenant-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(nested);
    }

    @Test
    @DisplayName("/models without tenant returns the public catalog")
    void nestedEndpointWithoutTenantDelegatesToPublicCatalog() {
        Map<String, Object> nested = Map.of("providers", List.of(Map.of("name", "openai")));
        when(service.getPublicModelsForCategory(null)).thenReturn(nested);

        ResponseEntity<Map<String, Object>> response = controller.getAvailableModels(null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(nested);
        verify(service).getPublicModelsForCategory(null);
    }

    @Test
    @DisplayName("/models?category=browser_agent forwards the category to the service")
    void nestedEndpointForwardsCategory() {
        Map<String, Object> nested = Map.of("providers", List.of());
        when(service.getModelsForCategory("browser_agent", "tenant-1")).thenReturn(nested);

        ResponseEntity<Map<String, Object>> response =
                controller.getAvailableModels("browser_agent", "tenant-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(nested);
    }

    @Test
    @DisplayName("/models?category=invalid is rejected before reaching the service (V156 shape CHECK)")
    void nestedEndpointRejectsInvalidCategory() {
        assertThatThrownBy(() -> controller.getAvailableModels("With Space", "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid category key");
        assertThatThrownBy(() -> controller.getAvailableModels("CHAT", "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("/models/flat returns the flat list from listAvailableModels")
    void flatEndpointDelegatesToService() {
        // The flat endpoint is the system-prompt injection contract: the
        // response body MUST be a List<AvailableModel> with provider/modelId/tier.
        // conversation-service parses this shape directly.
        List<AvailableModel> flat = List.of(
                new AvailableModel("anthropic", "claude-opus-4-6", "top", 1),
                new AvailableModel("openai", "gpt-5", "top", 1));
        when(service.listAvailableModels(null, "tenant-1")).thenReturn(flat);

        ResponseEntity<List<AvailableModel>> response = controller.getAvailableModelsFlat(null, "tenant-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(flat);
        // Sanity-check the record shape hasn't silently drifted: if someone
        // renames `provider()` / `modelId()` / `tier()` the compiler will
        // catch it here before the contract breaks.
        assertThat(response.getBody().get(0).provider()).isEqualTo("anthropic");
        assertThat(response.getBody().get(0).modelId()).isEqualTo("claude-opus-4-6");
        assertThat(response.getBody().get(0).tier()).isEqualTo("top");
    }

    @Test
    @DisplayName("/models/flat?category=image_generation forwards the category")
    void flatEndpointForwardsCategory() {
        List<AvailableModel> flat = List.of(
                new AvailableModel("openai", "gpt-image-1.5-medium", "mid", 101));
        when(service.listAvailableModels("image_generation", "tenant-1")).thenReturn(flat);

        ResponseEntity<List<AvailableModel>> response =
                controller.getAvailableModelsFlat("image_generation", "tenant-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(flat);
    }

    @Test
    @DisplayName("/models/flat returns empty list when no models are enabled")
    void flatEndpointEmpty() {
        when(service.listAvailableModels(null, "tenant-1")).thenReturn(List.of());

        ResponseEntity<List<AvailableModel>> response = controller.getAvailableModelsFlat(null, "tenant-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }
}
