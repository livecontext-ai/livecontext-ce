package com.apimarketplace.agent.service;

import com.apimarketplace.agent.credential.LlmCredentialRepository;
import com.apimarketplace.agent.domain.ModelCategorySettingsEntity;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.repository.ModelCategorySettingsRepository;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guard: a model with no price must not be enable-able.
 *
 * <p>Why this matters more than it looks. An unpriced model does NOT bill zero -
 * {@code ModelPricingService} falls back to its default rates (1.00 / 4.00 USD
 * per 1M) and only logs a warning. So enabling one charges every tenant a
 * fabricated rate that can be wrong in either direction, silently.
 *
 * <p>That was academic while every catalog row came from a feed carrying real
 * prices. It stopped being academic with {@code NativeModelDiscoveryService},
 * which adds rows from each provider's own {@code /models} endpoint - an
 * endpoint that publishes no pricing at all, and whose rows therefore land
 * unpriced on purpose rather than carrying a number borrowed from an
 * aggregator's resale rate.
 *
 * <p>Both doors into the picker are covered: the global {@code enabled} flag and
 * the per-category sidecar.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ModelCatalogService - an unpriced model cannot be enabled")
class ModelCatalogServiceUnpricedEnableGuardTest {

    @Mock private ModelConfigOverrideRepository repository;
    @Mock private ModelCategorySettingsRepository categoryRepository;
    @Mock private LLMProviderFactory llmProviderFactory;
    @Mock private LlmCredentialRepository credentialRepository;
    @Mock private CachedModelRateLimitProvider cachedRateLimitProvider;
    @Mock private AuthPricingSyncClient authPricingSyncClient;

    private ModelCatalogService service;

    @BeforeEach
    void setUp() {
        service = new ModelCatalogService(
                repository, categoryRepository, llmProviderFactory, credentialRepository,
                cachedRateLimitProvider, "", authPricingSyncClient);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static ModelConfigOverrideEntity row(String provider, String modelId,
                                                 BigDecimal in, BigDecimal out) {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setProvider(provider);
        e.setModelId(modelId);
        e.setPriceInput(in);
        e.setPriceOutput(out);
        return e;
    }

    private static ModelConfigOverrideEntity enableRequest(String provider, String modelId) {
        ModelConfigOverrideEntity input = new ModelConfigOverrideEntity();
        input.setProvider(provider);
        input.setModelId(modelId);
        input.setEnabled(true);
        return input;
    }

    @Test
    @DisplayName("Enabling a discovered, unpriced row is refused and names the model")
    void refusesToEnableAnUnpricedRow() {
        when(repository.findByProviderAndModelId("zai", "glm-5.3"))
                .thenReturn(Optional.of(row("zai", "glm-5.3", null, null)));

        assertThatThrownBy(() -> service.saveOverride(enableRequest("zai", "glm-5.3")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zai:glm-5.3")
                .hasMessageContaining("no price");

        verify(repository, never()).save(any());
        verify(authPricingSyncClient, never()).sync(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Enabling AND pricing in the same request is allowed - the normal admin flow")
    void allowsEnablingWhenThePriceArrivesInTheSameCall() {
        when(repository.findByProviderAndModelId("zai", "glm-5.3"))
                .thenReturn(Optional.of(row("zai", "glm-5.3", null, null)));

        ModelConfigOverrideEntity input = enableRequest("zai", "glm-5.3");
        input.setPriceInput(new BigDecimal("1.40"));
        input.setPriceOutput(new BigDecimal("4.40"));

        assertThatCode(() -> service.saveOverride(input)).doesNotThrowAnyException();
        verify(repository).save(any());
    }

    @Test
    @DisplayName("One price is enough - some models are genuinely free on one side")
    void oneSidedPricingIsAccepted() {
        when(repository.findByProviderAndModelId("zai", "glm-5.3"))
                .thenReturn(Optional.of(row("zai", "glm-5.3", null, new BigDecimal("4.40"))));

        assertThatCode(() -> service.saveOverride(enableRequest("zai", "glm-5.3")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("An already-priced row enables normally - the guard is not a new hurdle")
    void pricedRowsAreUnaffected() {
        when(repository.findByProviderAndModelId("zai", "glm-5.1"))
                .thenReturn(Optional.of(row("zai", "glm-5.1",
                        new BigDecimal("1.40"), new BigDecimal("4.40"))));

        assertThatCode(() -> service.saveOverride(enableRequest("zai", "glm-5.1")))
                .doesNotThrowAnyException();
        verify(repository).save(any());
    }

    @Test
    @DisplayName("DISABLING an unpriced row is always allowed")
    void disablingIsNeverBlocked() {
        when(repository.findByProviderAndModelId("zai", "glm-5.3"))
                .thenReturn(Optional.of(row("zai", "glm-5.3", null, null)));

        ModelConfigOverrideEntity input = enableRequest("zai", "glm-5.3");
        input.setEnabled(false);

        assertThatCode(() -> service.saveOverride(input)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Editing an unpriced row without touching enabled is allowed")
    void unrelatedEditsAreNotBlocked() {
        // enabled stays null on the entity, so the row is not being enabled -
        // an admin renaming or re-ranking a discovered model must not be stopped.
        when(repository.findByProviderAndModelId("zai", "glm-5.3"))
                .thenReturn(Optional.of(row("zai", "glm-5.3", null, null)));

        ModelConfigOverrideEntity input = new ModelConfigOverrideEntity();
        input.setProvider("zai");
        input.setModelId("glm-5.3");
        input.setDisplayName("GLM 5.3");

        assertThatCode(() -> service.saveOverride(input)).doesNotThrowAnyException();
        verify(repository).save(any());
    }

    @Test
    @DisplayName("REGRESSION: renaming an ALREADY-ENABLED unpriced model persists, instead of silently reverting")
    void renamingAnAlreadyEnabledUnpricedModelPersists() {
        // The case the test above just misses: there `enabled` is null on the ROW, so the
        // guard read false whichever state it tested. On a model that is already on, reading
        // the row instead of the request made a name-only edit throw, and the transactional
        // save rolled back the name AND its user-modified marker. The admin saw a generic
        // banner, the cell snapped back, and the next feed sync was free to keep writing its
        // own name over the one they had chosen. Reported as "my alias reverts on refresh".
        ModelConfigOverrideEntity stored = row("zai", "glm-5.3", null, null);
        stored.setEnabled(true);
        when(repository.findByProviderAndModelId("zai", "glm-5.3")).thenReturn(Optional.of(stored));

        ModelConfigOverrideEntity input = new ModelConfigOverrideEntity();
        input.setProvider("zai");
        input.setModelId("glm-5.3");
        input.setDisplayName("GLM 5.3 turbo maison");

        assertThatCode(() -> service.saveOverride(input)).doesNotThrowAnyException();

        ArgumentCaptor<ModelConfigOverrideEntity> saved =
                ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getDisplayName()).isEqualTo("GLM 5.3 turbo maison");
        // And the marker, or the next sync overwrites the name anyway and the bug survives
        // the fix in a slower form.
        assertThat(saved.getValue().getUserModifiedFields()).contains("displayName");
        // The model stays on: a rename is not a state change.
        assertThat(saved.getValue().getEnabled()).isTrue();
    }

    @Test
    @DisplayName("REGRESSION: renaming a model already open to the free tier persists too")
    void renamingAFreeTierModelPersists() {
        // Same shape, other guard: it ran on every save and read the row, so a model already
        // open to the free tier could not be renamed either once its price was out of the
        // billable range.
        ModelConfigOverrideEntity stored = row("openrouter", "openrouter/auto",
                new BigDecimal("-1"), new BigDecimal("-1"));
        stored.setFreeTierEnabled(true);
        when(repository.findByProviderAndModelId("openrouter", "openrouter/auto"))
                .thenReturn(Optional.of(stored));

        ModelConfigOverrideEntity input = new ModelConfigOverrideEntity();
        input.setProvider("openrouter");
        input.setModelId("openrouter/auto");
        input.setDisplayName("Auto router");

        assertThatCode(() -> service.saveOverride(input)).doesNotThrowAnyException();

        ArgumentCaptor<ModelConfigOverrideEntity> saved =
                ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
        verify(repository).save(saved.capture());
        // The name has to REACH the save, not merely fail to throw on the way there.
        assertThat(saved.getValue().getDisplayName()).isEqualTo("Auto router");
    }

    @Test
    @DisplayName("Opening a model to the free tier IS still refused when its price cannot be billed")
    void openingToTheFreeTierIsStillGuarded() {
        // The refusal the guard exists for must survive the fix: it fires when the REQUEST
        // carries the free-tier key, which is exactly when the admin is opening the model.
        ModelConfigOverrideEntity stored = row("openrouter", "openrouter/auto",
                new BigDecimal("-1"), new BigDecimal("-1"));
        when(repository.findByProviderAndModelId("openrouter", "openrouter/auto"))
                .thenReturn(Optional.of(stored));

        ModelConfigOverrideEntity input = new ModelConfigOverrideEntity();
        input.setProvider("openrouter");
        input.setModelId("openrouter/auto");
        input.setFreeTierEnabledExplicitlySet(true);
        input.setFreeTierEnabled(true);

        assertThatThrownBy(() -> service.saveOverride(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("free tier");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("REGRESSION: repricing a free-tier model to an unbillable rate is still refused")
    void repricingAFreeTierModelToAnUnbillableRateIsRefused() {
        // Narrowing the guard to "the request carries the free-tier key" gave the silent lie a
        // second door: a price-only save on a row already open to the free tier was accepted,
        // the mirror refused the rate, and the catalogue kept showing a price billing never
        // took, with a WARN log as the only trace.
        ModelConfigOverrideEntity stored = row("openrouter", "openrouter/auto", null, null);
        stored.setFreeTierEnabled(true);
        when(repository.findByProviderAndModelId("openrouter", "openrouter/auto"))
                .thenReturn(Optional.of(stored));

        ModelConfigOverrideEntity input = new ModelConfigOverrideEntity();
        input.setProvider("openrouter");
        input.setModelId("openrouter/auto");
        input.setPriceInput(new BigDecimal("-1"));
        input.setPriceOutput(new BigDecimal("-1"));

        assertThatThrownBy(() -> service.saveOverride(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("free tier");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("repricing a model that is NOT on the free tier is none of that guard's business")
    void repricingANonFreeTierModelIsAllowed() {
        ModelConfigOverrideEntity stored = row("openrouter", "openrouter/auto", null, null);
        when(repository.findByProviderAndModelId("openrouter", "openrouter/auto"))
                .thenReturn(Optional.of(stored));

        ModelConfigOverrideEntity input = new ModelConfigOverrideEntity();
        input.setProvider("openrouter");
        input.setModelId("openrouter/auto");
        input.setPriceInput(new BigDecimal("-1"));
        input.setPriceOutput(new BigDecimal("-1"));

        assertThatCode(() -> service.saveOverride(input)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The category sidecar is the other door in - it is guarded too")
    void categoryEnableIsGuardedAsWell() {
        ModelConfigOverrideEntity parent = row("zai", "glm-5.3", null, null);
        parent.setId(42L);
        when(repository.findByProviderAndModelId("zai", "glm-5.3"))
                .thenReturn(Optional.of(parent));

        assertThatThrownBy(() ->
                service.setCategoryEnabled("zai", "glm-5.3", "chat", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no price");

        verify(categoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Category-DISABLING an unpriced model still works")
    void categoryDisableIsNotBlocked() {
        ModelConfigOverrideEntity parent = row("zai", "glm-5.3", null, null);
        parent.setId(42L);
        when(repository.findByProviderAndModelId("zai", "glm-5.3"))
                .thenReturn(Optional.of(parent));
        when(categoryRepository.findById(any())).thenReturn(Optional.empty());
        when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> service.setCategoryEnabled("zai", "glm-5.3", "chat", false))
                .doesNotThrowAnyException();
        verify(categoryRepository).save(any(ModelCategorySettingsEntity.class));
    }
}
