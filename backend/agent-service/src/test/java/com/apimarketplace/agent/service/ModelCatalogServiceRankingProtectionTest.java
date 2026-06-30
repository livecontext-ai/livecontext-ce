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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression tests verifying that admin edits (ranking, rate limits, etc.)
 * add fields to {@code user_modified_fields} so future syncs don't overwrite them.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelCatalogService - admin edits populate userModifiedFields")
class ModelCatalogServiceRankingProtectionTest {

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

    @Test
    @DisplayName("bulkUpdateRankings marks 'ranking' as user-modified on each row")
    void bulkUpdateRankingsProtectsRanking() {
        ModelConfigOverrideEntity entity = existingRow("openai", "gpt-5.4");
        when(repository.findByProviderAndModelId("openai", "gpt-5.4"))
                .thenReturn(Optional.of(entity));

        List<Map<String, Object>> rankings = List.of(
                Map.of("provider", "openai", "modelId", "gpt-5.4", "ranking", 5)
        );

        service.bulkUpdateRankings(rankings);

        assertThat(entity.getRanking()).isEqualTo(5);
        assertThat(entity.getUserModifiedFields()).contains("ranking");
        verify(repository).save(entity);
    }

    @Test
    @DisplayName("bulkUpdateRankings creates new row with ranking protected")
    void bulkUpdateRankingsNewRowProtectsRanking() {
        when(repository.findByProviderAndModelId("openai", "gpt-5.5"))
                .thenReturn(Optional.empty());

        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                Map.of("providers", List.of()));

        List<Map<String, Object>> rankings = List.of(
                Map.of("provider", "openai", "modelId", "gpt-5.5", "ranking", 10)
        );

        service.bulkUpdateRankings(rankings);

        ArgumentCaptor<ModelConfigOverrideEntity> captor =
                ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
        verify(repository).save(captor.capture());

        ModelConfigOverrideEntity saved = captor.getValue();
        assertThat(saved.getRanking()).isEqualTo(10);
        assertThat(saved.getUserModifiedFields()).contains("ranking");
    }

    @Test
    @DisplayName("saveOverride marks all edited fields as user-modified")
    void saveOverrideProtectsAllEditedFields() {
        ModelConfigOverrideEntity existing = existingRow("openai", "gpt-5.4");
        when(repository.findByProviderAndModelId("openai", "gpt-5.4"))
                .thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ModelConfigOverrideEntity input = new ModelConfigOverrideEntity();
        input.setProvider("openai");
        input.setModelId("gpt-5.4");
        input.setEnabled(false);
        input.setDisplayName("GPT 5.4 Custom");
        input.setTier("premium");
        input.setRanking(1);
        input.setRecommended(true);
        input.setPriceInput(new BigDecimal("5.00"));
        input.setPriceOutput(new BigDecimal("30.00"));

        service.saveOverride(input);

        assertThat(existing.getUserModifiedFields())
                .contains("enabled", "displayName", "tier", "ranking",
                        "recommended", "priceInput", "priceOutput");
    }

    @Test
    @DisplayName("saveOverride marks rate limit fields as user-modified when explicitly set")
    void saveOverrideProtectsRateLimits() {
        ModelConfigOverrideEntity existing = existingRow("openai", "gpt-5.4");
        when(repository.findByProviderAndModelId("openai", "gpt-5.4"))
                .thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ModelConfigOverrideEntity input = new ModelConfigOverrideEntity();
        input.setProvider("openai");
        input.setModelId("gpt-5.4");
        input.setRateLimitsExplicitlySet(true);
        input.setRateLimitTpm(100000);
        input.setRateLimitRpm(500);
        input.setRateLimitTpmPerTenant(50000);
        input.setRateLimitRpmPerTenant(200);

        service.saveOverride(input);

        assertThat(existing.getUserModifiedFields())
                .contains("rateLimitTpm", "rateLimitRpm",
                        "rateLimitTpmPerTenant", "rateLimitRpmPerTenant");
    }

    // --- helpers ---

    private static ModelConfigOverrideEntity existingRow(String provider, String modelId) {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setId(1L);
        e.setProvider(provider);
        e.setModelId(modelId);
        e.setDisplayName(modelId);
        e.setRanking(99);
        e.setPriceInput(BigDecimal.ONE);
        e.setPriceOutput(BigDecimal.TEN);
        e.setEnabled(true);
        e.setUserModifiedFields(new String[0]);
        return e;
    }
}
