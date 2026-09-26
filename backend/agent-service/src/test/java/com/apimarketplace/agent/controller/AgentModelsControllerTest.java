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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
    @Mock private com.apimarketplace.agent.service.ModelReplacementResolver replacementResolver;

    private AgentModelsController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentModelsController(service, replacementResolver);
        // The real method mutates the catalog in place and hands back the SAME map, so the
        // pass-through stub keeps every isSameAs() assertion below meaning what it meant before
        // the filter existed. Lenient: the flat route and the rejected-category route never reach it.
        lenient().when(service.hideBridgeProviders(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("/models/effective echoes an enabled pair with substituted=false")
    void effectiveEchoesEnabledPair() {
        when(replacementResolver.substituteIfDisabled("anthropic", "claude-opus-4-9"))
                .thenReturn(java.util.Optional.empty());

        Map<String, Object> body = controller.getEffectiveModel("anthropic", "claude-opus-4-9").getBody();

        assertThat(body).containsEntry("provider", "anthropic")
                .containsEntry("model", "claude-opus-4-9")
                .containsEntry("substituted", false);
    }

    @Test
    @DisplayName("/models/effective returns the replacement of a disabled pair (what a CLI chat turn runs on)")
    void effectiveReturnsReplacement() {
        when(replacementResolver.substituteIfDisabled("claude-code", "claude-opus-4-8"))
                .thenReturn(java.util.Optional.of(new com.apimarketplace.agent.service.ModelReplacementResolver.Substitution(
                        "claude-code", "claude-opus-4-9", "claude-code", "claude-opus-4-8", true)));

        Map<String, Object> body = controller.getEffectiveModel("claude-code", "claude-opus-4-8").getBody();

        assertThat(body).containsEntry("provider", "claude-code")
                .containsEntry("model", "claude-opus-4-9")
                .containsEntry("substituted", true)
                .containsEntry("explicit", true)
                .containsEntry("replacedModel", "claude-opus-4-8");
    }

    @Test
    @DisplayName("/models without category returns the legacy global catalog")
    void nestedEndpointDelegatesToService() {
        Map<String, Object> nested = Map.of("providers", List.of());
        when(service.getModelsForCategory(null, "tenant-1")).thenReturn(nested);

        ResponseEntity<Map<String, Object>> response = controller.getAvailableModels(null, false, false, "tenant-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(nested);
    }

    @Test
    @DisplayName("/models without tenant returns the public catalog")
    void nestedEndpointWithoutTenantDelegatesToPublicCatalog() {
        Map<String, Object> nested = Map.of("providers", List.of(Map.of("name", "openai")));
        when(service.getPublicModelsForCategory(null)).thenReturn(nested);

        ResponseEntity<Map<String, Object>> response = controller.getAvailableModels(null, true, false, null);

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
                controller.getAvailableModels("browser_agent", false, false, "tenant-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(nested);
    }

    @Test
    @DisplayName("ONLY the anonymous nested shape is bridge-filtered")
    void onlyTheAnonymousShapeIsFiltered() {
        // Deliberately UNEQUAL: Mockito matches a verify() argument by equals, so two catalogs
        // that are both Map.of("providers", List.of()) would each satisfy either verification and
        // the pair below could not tell the branches apart.
        Map<String, Object> withTenant = Map.of("providers", List.of(Map.of("name", "anthropic")));
        Map<String, Object> anonymous = Map.of("providers", List.of(Map.of("name", "openai")));
        when(service.getModelsForCategory(null, "tenant-1")).thenReturn(withTenant);
        when(service.getPublicModelsForCategory(null)).thenReturn(anonymous);

        controller.getAvailableModels(null, false, false, "tenant-1");
        controller.getAvailableModels(null, true, false, null);

        // The anonymous branch is the production leak: this endpoint is re-served publicly as
        // /api/v3/chat/models and was advertising claude-code and codex, plus the bridge host's
        // LAN address, to callers with no token.
        verify(service).hideBridgeProviders(anonymous);
        // The authenticated branch must NOT be filtered, and this is the assertion that keeps the
        // change from breaking three things that read the same shape: ModelCatalogEnricher (which
        // rewrites the provider.enum NodeParamsValidator enforces at WRITE time), the default
        // resolution in SmartDefaultsEngine / ChatDispatchService, and the cloud-only admin panel
        // that creates the execution links - which would otherwise lose the very providers those
        // links exist to point at.
        verify(service, never()).hideBridgeProviders(withTenant);
    }

    @Test
    @DisplayName("an AUTHENTICATED read is bridge-filtered when the caller asks, and only then")
    void authenticatedReadIsFilteredOnDemand() {
        // The signed-in non-admin case. Until this existed the bridges travelled to every
        // signed-in user and only a React hook kept them off the screen, so any picker that
        // forgot the hook named the operator's CLI subscription to an end user.
        Map<String, Object> withTenant = Map.of("providers", List.of(Map.of("name", "anthropic")));
        when(service.getModelsForCategory(null, "tenant-1")).thenReturn(withTenant);

        controller.getAvailableModels(null, false, true, "tenant-1");

        verify(service).hideBridgeProviders(withTenant);
        // NOT the public branch: that one also widens the catalogue to providers with no key,
        // and a signed-in user must lose the bridges without gaining those.
        verify(service, never()).getPublicModelsForCategory(null);
    }

    @Test
    @DisplayName("the flat catalog is NOT bridge-filtered - it feeds validation, not a picker")
    void flatCatalogIsNotFiltered() {
        when(service.listAvailableModels(null, "tenant-1")).thenReturn(List.of());

        controller.getAvailableModelsFlat(null, "tenant-1");

        // This is the non-regression that protects what is already running. The flat shape backs
        // model VALIDATION and the prompt-injected catalog, and the platform routes billed pairs
        // onto a CLI through agent.model_execution_links. Filter a bridge out here and every agent
        // already bound to one becomes "model not available" on its next save, while the links
        // keep dispatching to a provider the catalog now denies knowing.
        verify(service, never()).hideBridgeProviders(any());
    }

    @Test
    @DisplayName("/models?category=invalid is rejected before reaching the service (V156 shape CHECK)")
    void nestedEndpointRejectsInvalidCategory() {
        assertThatThrownBy(() -> controller.getAvailableModels("With Space", false, false, "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid category key");
        assertThatThrownBy(() -> controller.getAvailableModels("CHAT", false, false, "tenant-1"))
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
