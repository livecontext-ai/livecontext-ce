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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.assertj.core.api.Assertions;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Admin edits to the model catalog must take effect immediately - the rate-limit
 * cache (30 s TTL) and the credential hasDbKey cache (60 s TTL) cannot stay stale.
 * If {@link ModelCatalogService} forgets to flip the cache after a write, admins
 * flip a toggle in the UI and nothing happens for up to a minute - exactly the
 * drift the 2026-04-21 audit flagged (score 5/10, item #4).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelCatalogService - cache invalidation on admin writes")
class ModelCatalogServiceCacheInvalidationTest {

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

    private ModelConfigOverrideEntity pricingInput(String provider, String model) {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setProvider(provider);
        e.setModelId(model);
        // No priceInput/priceOutput - keeps the pricing-sync call off the critical
        // path so we can assert the cache eviction independently of the pricing sync.
        return e;
    }

    @Test
    @DisplayName("saveOverride refreshes rate-limit cache AND clears hasDbKey cache")
    void saveOverrideInvalidatesBothCaches() {
        ModelConfigOverrideEntity in = pricingInput("openai", "gpt-5");
        when(repository.findByProviderAndModelId("openai", "gpt-5")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveOverride(in);

        verify(cachedRateLimitProvider).refreshCache();
        verify(credentialRepository).clearHasDbKeyCacheAll();
    }

    @Test
    @DisplayName("deleteOverride refreshes rate-limit cache AND clears hasDbKey cache")
    void deleteOverrideInvalidatesBothCaches() {
        service.deleteOverride("openai", "gpt-5");

        verify(repository).deleteByProviderAndModelId("openai", "gpt-5");
        verify(cachedRateLimitProvider).refreshCache();
        verify(credentialRepository).clearHasDbKeyCacheAll();
    }

    @Test
    @DisplayName("resetAll refreshes rate-limit cache AND clears hasDbKey cache")
    void resetAllInvalidatesBothCaches() {
        service.resetAll();

        verify(repository).deleteAll();
        verify(cachedRateLimitProvider).refreshCache();
        verify(credentialRepository).clearHasDbKeyCacheAll();
    }

    @Test
    @DisplayName("bulkUpdateRankings refreshes caches ONCE after all writes, not per-row")
    void bulkUpdateRankingsInvalidatesOnceAtEnd() {
        when(repository.findByProviderAndModelId(any(), any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.bulkUpdateRankings(List.of(
                Map.of("provider", "openai",    "modelId", "gpt-5",        "ranking", 1),
                Map.of("provider", "anthropic", "modelId", "claude-opus", "ranking", 2),
                Map.of("provider", "google",    "modelId", "gemini-3",    "ranking", 3)
        ));

        // Three repo writes, then exactly ONE cache refresh - not once per row.
        verify(repository, times(3)).save(any());
        verify(cachedRateLimitProvider, times(1)).refreshCache();
        verify(credentialRepository, times(1)).clearHasDbKeyCacheAll();

        // And the invalidation must happen AFTER the writes, not before, otherwise a
        // concurrent reader can repopulate the cache from pre-write state.
        InOrder order = inOrder(repository, cachedRateLimitProvider);
        order.verify(repository, atLeastOnce()).save(any());
        order.verify(cachedRateLimitProvider).refreshCache();
    }

    @Test
    @DisplayName("bulkUpdateRankings populates display_name and provider_kind on new entity to satisfy NOT NULL constraints")
    void newEntitySetsRequiredNotNullColumns() {
        // Reproduce the bug: drag-and-drop reorder used to insert with display_name=null
        // and provider_kind=null, violating V109/V117 NOT NULL constraints. The fix must
        // populate display_name from catalog (or fall back to modelId) and provider_kind
        // via inferProviderKind.
        // Mutable list - getAvailableProvidersBase() calls removeIf(), and List.of()
        // returns an immutable list that would throw UnsupportedOperationException.
        Map<String, Object> openaiProvider = new HashMap<>();
        openaiProvider.put("name", "openai");
        openaiProvider.put("configured", Boolean.TRUE);
        openaiProvider.put("models", new ArrayList<>(List.of(Map.of("id", "gpt-5", "name", "GPT-5"))));
        Map<String, Object> bridgeProvider = new HashMap<>();
        bridgeProvider.put("name", "claude-code");
        bridgeProvider.put("configured", Boolean.TRUE);
        bridgeProvider.put("models", new ArrayList<>(List.of(Map.of("id", "claude-sonnet-4-6-cc", "name", "Claude Sonnet 4.6 CC"))));
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", new ArrayList<>(List.of(openaiProvider, bridgeProvider)));
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(base);
        when(credentialRepository.hasDbKey(any())).thenReturn(true);
        when(repository.findByProviderAndModelId(any(), any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.bulkUpdateRankings(List.of(
                Map.of("provider", "openai",      "modelId", "gpt-5",                "ranking", 1),
                Map.of("provider", "claude-code", "modelId", "claude-sonnet-4-6-cc", "ranking", 2),
                Map.of("provider", "openai",      "modelId", "unknown-model",        "ranking", 3)
        ));

        ArgumentCaptor<ModelConfigOverrideEntity> captor = ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
        verify(repository, times(3)).save(captor.capture());

        Map<String, ModelConfigOverrideEntity> byKey = captor.getAllValues().stream()
                .collect(Collectors.toMap(e -> e.getProvider() + ":" + e.getModelId(), e -> e));

        // display_name from catalog when present
        Assertions.assertThat(byKey.get("openai:gpt-5").getDisplayName()).isEqualTo("GPT-5");
        // display_name fallback to modelId when catalog has no entry
        Assertions.assertThat(byKey.get("openai:unknown-model").getDisplayName()).isEqualTo("unknown-model");
        // provider_kind = "bridge" inferred for bridge providers (V117 NOT NULL)
        Assertions.assertThat(byKey.get("claude-code:claude-sonnet-4-6-cc").getProviderKind()).isEqualTo("bridge");
        // provider_kind = "byok" for cloud providers without explicit kind
        Assertions.assertThat(byKey.get("openai:gpt-5").getProviderKind()).isEqualTo("byok");
        // ranking propagated
        Assertions.assertThat(byKey.get("openai:gpt-5").getRanking()).isEqualTo(1);
        Assertions.assertThat(byKey.get("claude-code:claude-sonnet-4-6-cc").getRanking()).isEqualTo(2);
    }

    @Test
    @DisplayName("Cache refresh failure does NOT propagate - admin save still succeeds")
    void cacheRefreshFailureIsSwallowed() {
        ModelConfigOverrideEntity in = pricingInput("openai", "gpt-5");
        when(repository.findByProviderAndModelId(any(), any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("cache down")).when(cachedRateLimitProvider).refreshCache();

        // If this threw, the admin would see a 500 after a successful DB write -
        // the admin panel would become unusable any time the cache was flapping.
        service.saveOverride(in);

        // The second invalidation step still runs even after the first one fails.
        verify(credentialRepository).clearHasDbKeyCacheAll();
    }
}
