package com.apimarketplace.agent.service;

import com.apimarketplace.agent.credential.LlmCredentialRepository;
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

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for the catalog service → auth-service pricing-sync wiring.
 *
 * <p>After extracting the HTTP call into {@link AuthPricingSyncClient} the concern
 * here is only that {@link ModelCatalogService#saveOverride} delegates to the
 * client with the saved entity's raw prices. The unit contract (USD/1M raw, no
 * unit conversion) is covered by {@code AuthPricingSyncClientTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelCatalogService - pricing-sync delegation")
class ModelCatalogServiceSyncPricingTest {

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
    }

    private ModelConfigOverrideEntity input(String provider, String model,
                                            BigDecimal priceIn, BigDecimal priceOut) {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setProvider(provider);
        e.setModelId(model);
        e.setDisplayName(model);
        e.setPriceInput(priceIn);
        e.setPriceOutput(priceOut);
        return e;
    }

    @Test
    @DisplayName("saveOverride forwards saved prices unchanged to AuthPricingSyncClient")
    void forwardsSavedPrices() {
        ModelConfigOverrideEntity in = input("openai", "gpt-5",
                new BigDecimal("1.25"), new BigDecimal("10.00"));
        when(repository.findByProviderAndModelId("openai", "gpt-5")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveOverride(in);

        verify(authPricingSyncClient).sync(
                eq("openai"), eq("gpt-5"),
                eq(new BigDecimal("1.25")), eq(new BigDecimal("10.00")),
                eq("byok"));
    }

    @Test
    @DisplayName("saveOverride still syncs when only priceInput is set (null output forwarded as null)")
    void forwardsPartialPrices() {
        ModelConfigOverrideEntity in = input("anthropic", "claude-opus-4-6",
                new BigDecimal("5.00"), null);
        when(repository.findByProviderAndModelId("anthropic", "claude-opus-4-6"))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveOverride(in);

        // Client receives raw nulls; the coerce-to-ZERO rule is the client's contract,
        // not ModelCatalogService's.
        verify(authPricingSyncClient).sync(
                eq("anthropic"), eq("claude-opus-4-6"),
                eq(new BigDecimal("5.00")), eq((BigDecimal) null),
                eq("byok"));
    }

    @Test
    @DisplayName("saveOverride skips sync when both prices are null (no billing impact)")
    void skipsSyncWhenBothPricesNull() {
        ModelConfigOverrideEntity in = input("openai", "gpt-5.4", null, null);
        when(repository.findByProviderAndModelId(any(), any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveOverride(in);

        verify(authPricingSyncClient, never()).sync(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Bridge providers sync with providerKind='bridge' so auth.model_pricing carries the right discriminator")
    void bridgeSyncPropagatesProviderKind() {
        // Post-V130 contract: bridges bill at cloud rates via auth.model_pricing.
        // saveOverride MUST forward the row to AuthPricingSyncClient with
        // providerKind='bridge' so the mirror row lands with the catalog-origin
        // discriminator instead of the 'byok' column default. See
        // CreditService.consumeForChat Javadoc for why bridges are not
        // short-circuited at the billing layer anymore.
        for (String bridge : new String[] {"claude-code", "codex", "gemini-cli", "mistral-vibe"}) {
            ModelConfigOverrideEntity in = input(bridge, "any-model",
                    new BigDecimal("5"), new BigDecimal("10"));
            when(repository.findByProviderAndModelId(eq(bridge), eq("any-model")))
                    .thenReturn(Optional.empty());
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.saveOverride(in);

            verify(authPricingSyncClient).sync(
                    eq(bridge), eq("any-model"),
                    eq(new BigDecimal("5")), eq(new BigDecimal("10")),
                    eq("bridge"));
        }
    }

    @Test
    @DisplayName("inferProviderKind: bridges always classify as 'bridge' regardless of requested kind")
    void inferProviderKindClassifiesBridges() {
        assertThat(ModelCatalogService.inferProviderKind("claude-code", "byok")).isEqualTo("bridge");
        assertThat(ModelCatalogService.inferProviderKind("codex",       "cloud")).isEqualTo("bridge");
        assertThat(ModelCatalogService.inferProviderKind("gemini-cli",  null)).isEqualTo("bridge");
        assertThat(ModelCatalogService.inferProviderKind("mistral-vibe", "")).isEqualTo("bridge");
    }

    @Test
    @DisplayName("inferProviderKind: non-bridge providers pass through requested kind ('cloud' | else 'byok')")
    void inferProviderKindNonBridgeKinds() {
        assertThat(ModelCatalogService.inferProviderKind("openai",    "cloud")).isEqualTo("cloud");
        assertThat(ModelCatalogService.inferProviderKind("openai",    "CLOUD")).isEqualTo("cloud");
        assertThat(ModelCatalogService.inferProviderKind("anthropic", null)).isEqualTo("byok");
        assertThat(ModelCatalogService.inferProviderKind("anthropic", "byok")).isEqualTo("byok");
        assertThat(ModelCatalogService.inferProviderKind("anthropic", "garbage")).isEqualTo("byok");
    }
}
