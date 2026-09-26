package com.apimarketplace.agent.service;

import com.apimarketplace.agent.credential.LlmCredentialRepository;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.repository.ModelCategorySettingsRepository;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import com.apimarketplace.agent.service.ModelCatalogService.ModelRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V533 admin retirement: retire, restore, the retired list, and the guards that keep an admin
 * write from silently undoing a retirement.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelCatalogService - model retirement")
class ModelCatalogServiceRetirementTest {

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
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** The YAML/bridge catalogue the admin panel lists: one provider, one model. */
    private void yamlCatalog(String provider, String modelId, String name) {
        Map<String, Object> model = new java.util.LinkedHashMap<>();
        model.put("id", modelId);
        model.put("name", name);
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("name", provider);
        p.put("configured", true);
        p.put("models", new java.util.ArrayList<>(List.of(model)));
        Map<String, Object> base = new java.util.LinkedHashMap<>();
        base.put("providers", new java.util.ArrayList<>(List.of(p)));
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(base);
    }

    private static ModelConfigOverrideEntity row(String provider, String modelId) {
        ModelConfigOverrideEntity r = new ModelConfigOverrideEntity();
        r.setProvider(provider);
        r.setModelId(modelId);
        r.setDisplayName(modelId);
        return r;
    }

    private static ModelConfigOverrideEntity retired(String provider, String modelId) {
        ModelConfigOverrideEntity r = row(provider, modelId);
        r.setEnabled(false);
        r.setBundleEnabled(false);
        r.setRetiredAt(Instant.parse("2026-09-25T10:00:00Z"));
        r.setRetiredBy("7");
        return r;
    }

    @Nested
    @DisplayName("retireModels")
    class Retire {

        @Test
        @DisplayName("an existing row is disabled, not shipped, closed to the free tier and stamped")
        void retiresExistingRow() {
            ModelConfigOverrideEntity r = row("openai", "gpt-4o");
            r.setEnabled(true);
            r.setBundleEnabled(true);
            when(repository.findByProviderAndModelId("openai", "gpt-4o")).thenReturn(Optional.of(r));

            int count = service.retireModels(List.of(new ModelRef("openai", "gpt-4o")), "7");

            assertThat(count).isEqualTo(1);
            assertThat(r.isRetired()).isTrue();
            assertThat(r.getRetiredBy()).isEqualTo("7");
            assertThat(r.getEnabled()).isFalse();
            assertThat(r.getBundleEnabled()).isFalse();
            assertThat(r.isFreeTierEnabled()).isFalse();
            verify(repository).save(r);
            verify(cachedRateLimitProvider).refreshCache();
        }

        @Test
        @DisplayName("a model declared only in application.yml gets a row, since the row is the tombstone")
        void yamlOnlyModelGetsATombstoneRow() {
            when(repository.findByProviderAndModelId("xai", "grok-3-beta")).thenReturn(Optional.empty());
            yamlCatalog("xai", "grok-3-beta", "Grok 3 Beta");

            service.retireModels(List.of(new ModelRef("xai", "grok-3-beta")), "7");

            ArgumentCaptor<ModelConfigOverrideEntity> saved = ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
            verify(repository).save(saved.capture());
            assertThat(saved.getValue().getProvider()).isEqualTo("xai");
            assertThat(saved.getValue().getModelId()).isEqualTo("grok-3-beta");
            assertThat(saved.getValue().getDisplayName()).isEqualTo("Grok 3 Beta");
            assertThat(saved.getValue().getEnabled()).isFalse();
            assertThat(saved.getValue().isRetired()).isTrue();
            assertThat(saved.getValue().getProviderKind()).isEqualTo("byok");
        }

        @Test
        @DisplayName("a bridge model's tombstone row keeps the bridge kind")
        void bridgeTombstoneKeepsKind() {
            when(repository.findByProviderAndModelId("claude-code", "claude-opus-4-6")).thenReturn(Optional.empty());
            yamlCatalog("claude-code", "claude-opus-4-6", "Claude Opus 4.6");

            service.retireModels(List.of(new ModelRef("claude-code", "claude-opus-4-6")), "7");

            ArgumentCaptor<ModelConfigOverrideEntity> saved = ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
            verify(repository).save(saved.capture());
            assertThat(saved.getValue().getProviderKind()).isEqualTo("bridge");
        }

        @Test
        @DisplayName("an unknown pair (a typo) is refused and nothing is written, not turned into a tombstone")
        void unknownPairRefused() {
            ModelConfigOverrideEntity openToFreeTier = row("openai", "gpt-4o");
            openToFreeTier.setFreeTierEnabled(true);
            openToFreeTier.setPriceInput(new BigDecimal("2.50"));
            when(repository.findByProviderAndModelId("openai", "gpt-4o")).thenReturn(Optional.of(openToFreeTier));
            when(repository.findByProviderAndModelId("openai", "gtp-4o")).thenReturn(Optional.empty());
            yamlCatalog("openai", "gpt-6-sol", "GPT-6 Sol");

            assertThatThrownBy(() -> service.retireModels(List.of(
                    new ModelRef("openai", "gpt-4o"), new ModelRef("openai", "gtp-4o")), "7"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown model: openai:gtp-4o");
            // Every entry is validated before any side effect: the valid first model was neither
            // saved nor closed in the billing mirror, an HTTP call a rollback could not undo.
            verify(repository, never()).save(any());
            verify(authPricingSyncClient, never()).sync(any(), any(), any(), any(), any(), any(), any(), any());
            assertThat(openToFreeTier.isFreeTierEnabled()).isTrue();
        }

        @Test
        @DisplayName("a model named twice in one call is retired and counted once (e2e: it answered retired=3 for 2)")
        void duplicateCountedOnce() {
            ModelConfigOverrideEntity r = row("openai", "gpt-5.4");
            when(repository.findByProviderAndModelId("openai", "gpt-5.4")).thenReturn(Optional.of(r));

            int count = service.retireModels(List.of(
                    new ModelRef("openai", "gpt-5.4"), new ModelRef("openai", "gpt-5.4")), "1");

            assertThat(count).isEqualTo(1);
            verify(repository, org.mockito.Mockito.times(1)).save(r);
        }

        @Test
        @DisplayName("a model already retired is left alone and not counted (its date is kept)")
        void alreadyRetiredIsANoOp() {
            ModelConfigOverrideEntity r = retired("openai", "gpt-4o");
            when(repository.findByProviderAndModelId("openai", "gpt-4o")).thenReturn(Optional.of(r));

            int count = service.retireModels(List.of(new ModelRef("openai", "gpt-4o")), "9");

            assertThat(count).isZero();
            assertThat(r.getRetiredAt()).isEqualTo(Instant.parse("2026-09-25T10:00:00Z"));
            assertThat(r.getRetiredBy()).isEqualTo("7");
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("a model open to the free tier is closed in the billing mirror")
        void closesFreeTierMirror() {
            ModelConfigOverrideEntity r = row("anthropic", "claude-haiku-4-5");
            r.setFreeTierEnabled(true);
            r.setPriceInput(new BigDecimal("1.00"));
            r.setPriceOutput(new BigDecimal("5.00"));
            when(repository.findByProviderAndModelId("anthropic", "claude-haiku-4-5")).thenReturn(Optional.of(r));
            when(authPricingSyncClient.sync(anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(true);

            service.retireModels(List.of(new ModelRef("anthropic", "claude-haiku-4-5")), "7");

            verify(authPricingSyncClient).sync(eq("anthropic"), eq("claude-haiku-4-5"),
                    any(), any(), any(), any(), any(), eq(false));
            assertThat(r.isFreeTierEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("restoreModels")
    class Restore {

        @Test
        @DisplayName("a restored model comes back DISABLED with the bundle flag on inherit")
        void restoresDisabled() {
            ModelConfigOverrideEntity r = retired("openai", "gpt-4o");
            when(repository.findByProviderAndModelId("openai", "gpt-4o")).thenReturn(Optional.of(r));

            int count = service.restoreModels(List.of(new ModelRef("openai", "gpt-4o")));

            assertThat(count).isEqualTo(1);
            assertThat(r.isRetired()).isFalse();
            assertThat(r.getRetiredBy()).isNull();
            assertThat(r.getEnabled()).isFalse();
            assertThat(r.getBundleEnabled()).isNull();
            verify(repository).save(r);
        }

        @Test
        @DisplayName("a model that is not retired, or unknown, is skipped and not counted")
        void notRetiredIsSkipped() {
            ModelConfigOverrideEntity live = row("openai", "gpt-6-sol");
            live.setEnabled(true);
            when(repository.findByProviderAndModelId("openai", "gpt-6-sol")).thenReturn(Optional.of(live));
            when(repository.findByProviderAndModelId("openai", "ghost")).thenReturn(Optional.empty());

            int count = service.restoreModels(List.of(
                    new ModelRef("openai", "gpt-6-sol"), new ModelRef("openai", "ghost")));

            assertThat(count).isZero();
            assertThat(live.getEnabled()).isTrue();
            verify(repository, never()).save(any());
        }
    }

    @Test
    @DisplayName("the retired list carries what the admin needs to decide a restore")
    void listRetired() {
        ModelConfigOverrideEntity r = retired("openai", "gpt-4o");
        r.setReleaseDate(java.time.LocalDate.parse("2024-05-13"));
        when(repository.findByRetiredAtIsNotNullOrderByRetiredAtDesc()).thenReturn(List.of(r));

        List<Map<String, Object>> list = service.listRetiredModels();

        assertThat(list).singleElement().satisfies(m -> {
            assertThat(m).containsEntry("provider", "openai")
                    .containsEntry("modelId", "gpt-4o")
                    .containsEntry("retiredAt", "2026-09-25T10:00:00Z")
                    .containsEntry("retiredBy", "7")
                    .containsEntry("releaseDate", "2024-05-13");
        });
    }

    @Nested
    @DisplayName("admin writes cannot silently undo a retirement")
    class Guards {

        @Test
        @DisplayName("enabling a retired model is refused with the way out, instead of answering saved")
        void enablingRetiredIsRefused() {
            when(repository.findByProviderAndModelId("openai", "gpt-4o"))
                    .thenReturn(Optional.of(retired("openai", "gpt-4o")));
            ModelConfigOverrideEntity input = row("openai", "gpt-4o");
            input.setEnabled(true);

            assertThatThrownBy(() -> service.saveOverride(input))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is retired")
                    .hasMessageContaining("Restore");
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("shipping a retired model to CE (bundleEnabled=true) is refused the same way")
        void shippingRetiredIsRefused() {
            when(repository.findByProviderAndModelId("openai", "gpt-4o"))
                    .thenReturn(Optional.of(retired("openai", "gpt-4o")));
            ModelConfigOverrideEntity input = row("openai", "gpt-4o");
            input.setBundleEnabledExplicitlySet(true);
            input.setBundleEnabled(true);

            assertThatThrownBy(() -> service.saveOverride(input))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is retired");
        }

        @Test
        @DisplayName("opening a retired model to the free tier is refused the same way")
        void freeTierOnRetiredIsRefused() {
            when(repository.findByProviderAndModelId("openai", "gpt-4o"))
                    .thenReturn(Optional.of(retired("openai", "gpt-4o")));
            ModelConfigOverrideEntity input = row("openai", "gpt-4o");
            input.setFreeTierEnabledExplicitlySet(true);
            input.setFreeTierEnabled(true);

            assertThatThrownBy(() -> service.saveOverride(input))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is retired");
            verify(authPricingSyncClient, never()).sync(any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("enabling a retired model in a category is refused instead of answering saved")
        void categoryEnableOnRetiredIsRefused() {
            when(repository.findByProviderAndModelId("openai", "gpt-4o"))
                    .thenReturn(Optional.of(retired("openai", "gpt-4o")));

            assertThatThrownBy(() -> service.setCategoryEnabled("openai", "gpt-4o", "browser_agent", true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is retired");
            verify(categoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("editing another field of a retired model (its name) is still allowed")
        void otherEditsAllowed() {
            ModelConfigOverrideEntity r = retired("openai", "gpt-4o");
            when(repository.findByProviderAndModelId("openai", "gpt-4o")).thenReturn(Optional.of(r));
            ModelConfigOverrideEntity input = row("openai", "gpt-4o");
            input.setDisplayName("GPT-4o (retired)");

            service.saveOverride(input);

            assertThat(r.getDisplayName()).isEqualTo("GPT-4o (retired)");
        }

        @Test
        @DisplayName("deleting a retired model's row is refused: the row IS the retirement")
        void deletingRetiredIsRefused() {
            when(repository.findByProviderAndModelId("openai", "gpt-4o"))
                    .thenReturn(Optional.of(retired("openai", "gpt-4o")));

            assertThatThrownBy(() -> service.deleteOverride("openai", "gpt-4o"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is retired");
            verify(repository, never()).deleteByProviderAndModelId(any(), any());
        }

        @Test
        @DisplayName("a reset deletes only the rows that are not retired")
        void resetKeepsRetiredRows() {
            ModelConfigOverrideEntity plain = row("openai", "gpt-6-sol");
            when(repository.findByRetiredAtIsNull()).thenReturn(List.of(plain));

            service.resetAll();

            verify(repository).deleteAll(List.of(plain));
            verify(repository, never()).deleteAll();
        }
    }
}
