package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.repository.ModelCategorySettingsRepository;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import com.apimarketplace.agent.credential.LlmCredentialRepository;
import com.apimarketplace.agent.factory.LLMProviderFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The lookup the context monitor depends on.
 *
 * <p>It reads {@code agent.model_config_overrides}, NOT the pricing snapshot. That choice is the
 * whole fix: in production 744 of the pricing snapshot's 816 rows carry a null context window -
 * {@code deepseek-v4-pro}, the model that produced the alerts, among them - against 26 of 805 in
 * this catalog. Sourcing it from the snapshot would report "window unknown" for the very models
 * being watched, and a monitor that has gone quiet is indistinguishable from one that was fixed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelCatalogService.resolveContextWindow")
class ModelCatalogServiceContextWindowTest {

    @Mock private ModelConfigOverrideRepository repository;
    @Mock private ModelCategorySettingsRepository categoryRepository;
    @Mock private LlmCredentialRepository credentialRepository;
    @Mock private LLMProviderFactory llmProviderFactory;
    @Mock private CachedModelRateLimitProvider cachedRateLimitProvider;

    private ModelCatalogService service() {
        return new ModelCatalogService(repository, categoryRepository, llmProviderFactory,
            credentialRepository, cachedRateLimitProvider, "", null, true, null);
    }

    @Test
    @DisplayName("returns the window the catalog carries for the pair")
    void returnsTheCatalogValue() {
        ModelConfigOverrideEntity row = new ModelConfigOverrideEntity();
        row.setContextWindow(1_000_000);
        when(repository.findByProviderAndModelId("deepseek", "deepseek-v4-pro"))
            .thenReturn(Optional.of(row));

        assertThat(service().resolveContextWindow("deepseek", "deepseek-v4-pro"))
            .isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("a model with no row reports null, never a guessed default")
    void unknownModelReportsNull() {
        when(repository.findByProviderAndModelId(any(), any())).thenReturn(Optional.empty());

        assertThat(service().resolveContextWindow("deepseek", "not-in-the-catalog"))
            .as("a fabricated window would produce fabricated occupancy percentages; the loop "
                + "is built to claim no severity when it does not know")
            .isNull();
    }

    @Test
    @DisplayName("a row whose window column is null reports null")
    void rowWithoutAWindowReportsNull() {
        when(repository.findByProviderAndModelId(any(), any()))
            .thenReturn(Optional.of(new ModelConfigOverrideEntity()));

        assertThat(service().resolveContextWindow("deepseek", "deepseek-v4-pro")).isNull();
    }

    @Test
    @DisplayName("a null provider or model is answered without touching the database")
    void nullInputsShortCircuit() {
        ModelCatalogService service = service();

        assertThat(service.resolveContextWindow(null, "deepseek-v4-pro")).isNull();
        assertThat(service.resolveContextWindow("deepseek", null)).isNull();

        verify(repository, never()).findByProviderAndModelId(any(), any());
    }
}
