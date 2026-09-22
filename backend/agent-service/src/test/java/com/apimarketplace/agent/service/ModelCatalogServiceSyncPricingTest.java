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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    /** The mirror takes the write. Needed wherever the free-tier flag moves: a refused
     *  mirror aborts the save, which is the point of {@link #refusedMirrorAbortsTheSave}. */
    private void mirrorAccepts() {
        when(authPricingSyncClient.sync(anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);
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
                eq("byok"), eq((BigDecimal) null), eq((BigDecimal) null), eq(false));
    }

    @Test
    @DisplayName("V493: opening a model to the free tier reaches the billing mirror in the same save")
    void forwardsFreeTierFlagWhenOpened() {
        ModelConfigOverrideEntity in = input("anthropic", "claude-haiku-4-5",
                new BigDecimal("1.00"), new BigDecimal("5.00"));
        in.setFreeTierEnabledExplicitlySet(true);
        in.setFreeTierEnabled(true);
        when(repository.findByProviderAndModelId("anthropic", "claude-haiku-4-5"))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mirrorAccepts();

        service.saveOverride(in);

        // The gate reads auth.model_pricing.free_tier, not this table. Without this
        // push an admin could open a model here and free grants would still refuse it.
        verify(authPricingSyncClient).sync(
                eq("anthropic"), eq("claude-haiku-4-5"),
                eq(new BigDecimal("1.00")), eq(new BigDecimal("5.00")),
                eq("byok"), eq((BigDecimal) null), eq((BigDecimal) null), eq(true));
    }

    @Test
    @DisplayName("V493: closing a model to the free tier also reaches the mirror - the flag must not go stale")
    void forwardsFreeTierFlagWhenClosed() {
        ModelConfigOverrideEntity stored = input("anthropic", "claude-haiku-4-5",
                new BigDecimal("1.00"), new BigDecimal("5.00"));
        stored.setFreeTierEnabled(true);
        ModelConfigOverrideEntity in = input("anthropic", "claude-haiku-4-5",
                new BigDecimal("1.00"), new BigDecimal("5.00"));
        in.setFreeTierEnabledExplicitlySet(true);
        in.setFreeTierEnabled(false);
        when(repository.findByProviderAndModelId("anthropic", "claude-haiku-4-5"))
                .thenReturn(Optional.of(stored));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mirrorAccepts();

        service.saveOverride(in);

        verify(authPricingSyncClient).sync(
                eq("anthropic"), eq("claude-haiku-4-5"),
                eq(new BigDecimal("1.00")), eq(new BigDecimal("5.00")),
                eq("byok"), eq((BigDecimal) null), eq((BigDecimal) null), eq(false));
    }

    @Test
    @DisplayName("V493: a save that does not mention the flag leaves it untouched - no silent close")
    void untouchedFlagSurvivesAnUnrelatedEdit() {
        ModelConfigOverrideEntity stored = input("anthropic", "claude-haiku-4-5",
                new BigDecimal("1.00"), new BigDecimal("5.00"));
        stored.setFreeTierEnabled(true);
        // An unrelated edit (a price change) that never carries freeTierEnabled.
        ModelConfigOverrideEntity in = input("anthropic", "claude-haiku-4-5",
                new BigDecimal("2.00"), new BigDecimal("5.00"));
        when(repository.findByProviderAndModelId("anthropic", "claude-haiku-4-5"))
                .thenReturn(Optional.of(stored));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveOverride(in);

        verify(authPricingSyncClient).sync(
                eq("anthropic"), eq("claude-haiku-4-5"),
                eq(new BigDecimal("2.00")), eq(new BigDecimal("5.00")),
                eq("byok"), eq((BigDecimal) null), eq((BigDecimal) null), eq(true));
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
                eq("byok"), eq((BigDecimal) null), eq((BigDecimal) null), eq(false));
    }

    @Test
    @DisplayName("V491: saveOverride forwards the model's own cache prices, so the billing mirror stops falling back to the family multiplier")
    void forwardsCachePrices() {
        ModelConfigOverrideEntity in = input("anthropic", "claude-fable-5-1",
                new BigDecimal("10.00"), new BigDecimal("50.00"));
        in.setPriceCacheRead(new BigDecimal("0.25"));
        in.setPriceCacheWrite(new BigDecimal("12.50"));
        when(repository.findByProviderAndModelId("anthropic", "claude-fable-5-1"))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveOverride(in);

        verify(authPricingSyncClient).sync(
                eq("anthropic"), eq("claude-fable-5-1"),
                eq(new BigDecimal("10.00")), eq(new BigDecimal("50.00")),
                eq("byok"), eq(new BigDecimal("0.25")), eq(new BigDecimal("12.50")), eq(false));
    }

    @Test
    @DisplayName("saveOverride skips sync when both prices are null (no billing impact)")
    void skipsSyncWhenBothPricesNull() {
        ModelConfigOverrideEntity in = input("openai", "gpt-5.4", null, null);
        when(repository.findByProviderAndModelId(any(), any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveOverride(in);

        verify(authPricingSyncClient, never()).sync(any(), any(), any(), any(), any(), any(), any(), any());
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
                    eq("bridge"), eq((BigDecimal) null), eq((BigDecimal) null), eq(false));
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

    @Test
    @DisplayName("V493: an aborted OPEN also pushes a compensating close, in case the open did land")
    void abortedOpenCompensates() {
        // "Not mirrored" covers a response we never received, and auth-service may have
        // committed the TRUE before the connection dropped. Rolling back the catalog row
        // alone would then leave the mirror OPEN for a model the admin was told did not
        // save - the one direction that costs money. Without this assertion the whole
        // compensating block can be deleted and every other test stays green.
        ModelConfigOverrideEntity in = input("anthropic", "claude-haiku-4-5",
                new BigDecimal("1.00"), new BigDecimal("5.00"));
        in.setFreeTierEnabledExplicitlySet(true);
        in.setFreeTierEnabled(true);
        when(repository.findByProviderAndModelId("anthropic", "claude-haiku-4-5"))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(authPricingSyncClient.sync(anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.saveOverride(in)).isInstanceOf(IllegalStateException.class);

        verify(authPricingSyncClient).sync(eq("anthropic"), eq("claude-haiku-4-5"),
                any(), any(), any(), any(), any(), eq(true));
        verify(authPricingSyncClient).sync(eq("anthropic"), eq("claude-haiku-4-5"),
                any(), any(), any(), any(), any(), eq(false));
    }

    @Test
    @DisplayName("V493: an aborted CLOSE does not push anything extra - there is nothing to undo")
    void abortedCloseDoesNotCompensate() {
        // The inverse guard. Compensating here would push a TRUE, i.e. re-open a model
        // the admin was closing, which is the same failure wearing the other hat.
        ModelConfigOverrideEntity stored = input("anthropic", "claude-haiku-4-5",
                new BigDecimal("1.00"), new BigDecimal("5.00"));
        stored.setFreeTierEnabled(true);
        ModelConfigOverrideEntity in = input("anthropic", "claude-haiku-4-5",
                new BigDecimal("1.00"), new BigDecimal("5.00"));
        in.setFreeTierEnabledExplicitlySet(true);
        in.setFreeTierEnabled(false);
        when(repository.findByProviderAndModelId("anthropic", "claude-haiku-4-5"))
                .thenReturn(Optional.of(stored));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(authPricingSyncClient.sync(anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.saveOverride(in)).isInstanceOf(IllegalStateException.class);

        verify(authPricingSyncClient, times(1)).sync(anyString(), anyString(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("V493: a mirror that refuses the flag aborts the save instead of diverging")
    void refusedMirrorAbortsTheSave() {
        // The gate reads the mirror, not this table. If the push is dropped and the row
        // is kept, the chip reads "open" forever while every free account is refused, and
        // nobody knows to save the row a second time. @Transactional makes the throw undo
        // the local write, so both sides stay equal and the admin is told to retry.
        ModelConfigOverrideEntity in = input("anthropic", "claude-haiku-4-5",
                new BigDecimal("1.00"), new BigDecimal("5.00"));
        in.setFreeTierEnabledExplicitlySet(true);
        in.setFreeTierEnabled(true);
        when(repository.findByProviderAndModelId("anthropic", "claude-haiku-4-5"))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(authPricingSyncClient.sync(anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.saveOverride(in))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("free-tier");
    }

    @Test
    @DisplayName("V493: a dropped mirror write on an ordinary price edit still saves")
    void refusedMirrorOnAPriceEditIsTolerated() {
        // Rates are recoverable: reconciliation squares the ledger and the next save
        // re-sends them. Aborting here would make every model edit depend on
        // auth-service being up, which is a worse trade than the one it prevents.
        ModelConfigOverrideEntity in = input("openai", "gpt-5",
                new BigDecimal("1.25"), new BigDecimal("10.00"));
        when(repository.findByProviderAndModelId("openai", "gpt-5")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(authPricingSyncClient.sync(anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(false);

        assertThatCode(() -> service.saveOverride(in)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("V493: a sentinel-priced model is refused with a fixable reason, not 'retry later'")
    void sentinelPricedModelCannotBeOpened() {
        // The mirror REFUSES a rate it cannot store (the openrouter/auto "-1" list price
        // is the real case), so the sync would answer "not mirrored" for a reason no
        // retry can change - and the save would tell the admin to wait for auth-service,
        // which is up. Caught here instead, with the only instruction that helps.
        ModelConfigOverrideEntity in = input("openrouter", "auto",
                new BigDecimal("-1"), new BigDecimal("-1"));
        in.setFreeTierEnabledExplicitlySet(true);
        in.setFreeTierEnabled(true);
        when(repository.findByProviderAndModelId("openrouter", "auto")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.saveOverride(in))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside what billing can store");
        verify(repository, never()).save(any());
        verifyNoInteractions(authPricingSyncClient);
    }

    @Test
    @DisplayName("V493: a sentinel price is fine as long as the model stays closed")
    void sentinelPriceIsToleratedWhenNotOpening() {
        // The guard is scoped to the free-tier flag. A router row with a sentinel price
        // has always been storable in the catalog; refusing to save it at all would
        // break an unrelated admin edit.
        ModelConfigOverrideEntity in = input("openrouter", "auto",
                new BigDecimal("-1"), new BigDecimal("-1"));
        when(repository.findByProviderAndModelId("openrouter", "auto")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> service.saveOverride(in)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("V493: an unpriced model cannot be opened to the free tier at all")
    void unpricedModelCannotBeOpened() {
        // The sync needs a price, so opening a priceless row would write the catalog
        // column and reach no mirror: chip lit, gate shut, no error anywhere. A row
        // created by the drag-and-drop re-rank path is exactly that - unpriced, and
        // treated as enabled because its 'enabled' column is null.
        ModelConfigOverrideEntity in = input("openai", "discovered-model", null, null);
        in.setFreeTierEnabledExplicitlySet(true);
        in.setFreeTierEnabled(true);
        when(repository.findByProviderAndModelId("openai", "discovered-model"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.saveOverride(in))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no price");
        verify(repository, never()).save(any());
        verifyNoInteractions(authPricingSyncClient);
    }
    @Test
    @DisplayName("V493: deleting an OPEN model closes it in the billing mirror")
    void deletingAnOpenModelClosesTheMirror() {
        // The gate reads auth.model_pricing.free_tier, not this table. Deleting the row
        // without closing the mirror leaves the allowance funding a model the admin just
        // removed, while the panel draws the chip OFF (no row, no flag) - the same
        // catalog/mirror divergence saveOverride refuses to commit, except this one fails
        // OPEN and costs money.
        ModelConfigOverrideEntity stored = input("anthropic", "claude-haiku-4-5",
                new BigDecimal("1.00"), new BigDecimal("5.00"));
        stored.setFreeTierEnabled(true);
        when(repository.findByProviderAndModelId("anthropic", "claude-haiku-4-5"))
                .thenReturn(Optional.of(stored));
        mirrorAccepts();

        service.deleteOverride("anthropic", "claude-haiku-4-5");

        verify(authPricingSyncClient).sync(
                eq("anthropic"), eq("claude-haiku-4-5"),
                eq(new BigDecimal("1.00")), eq(new BigDecimal("5.00")),
                eq("byok"), any(), any(), eq(false));
        verify(repository).deleteByProviderAndModelId("anthropic", "claude-haiku-4-5");
    }

    @Test
    @DisplayName("V493: deleting a CLOSED model touches the mirror not at all")
    void deletingAClosedModelLeavesTheMirrorAlone() {
        // An extra write per delete would be noise on a path that runs for every model
        // an admin removes, and it could clobber a flag set by another actor meanwhile.
        ModelConfigOverrideEntity stored = input("anthropic", "claude-haiku-4-5",
                new BigDecimal("1.00"), new BigDecimal("5.00"));
        stored.setFreeTierEnabled(false);
        when(repository.findByProviderAndModelId("anthropic", "claude-haiku-4-5"))
                .thenReturn(Optional.of(stored));

        service.deleteOverride("anthropic", "claude-haiku-4-5");

        verifyNoInteractions(authPricingSyncClient);
        verify(repository).deleteByProviderAndModelId("anthropic", "claude-haiku-4-5");
    }

    @Test
    @DisplayName("V493: a failed close still deletes the row, and says so loudly")
    void aFailedCloseDoesNotBlockTheDelete() {
        // The row is on its way out either way; refusing the delete would leave the admin
        // unable to remove a model because an unrelated service is down. The log is the
        // recovery path, and it names the model.
        ModelConfigOverrideEntity stored = input("anthropic", "claude-haiku-4-5",
                new BigDecimal("1.00"), new BigDecimal("5.00"));
        stored.setFreeTierEnabled(true);
        when(repository.findByProviderAndModelId("anthropic", "claude-haiku-4-5"))
                .thenReturn(Optional.of(stored));
        when(authPricingSyncClient.sync(anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(false);

        assertThatCode(() -> service.deleteOverride("anthropic", "claude-haiku-4-5"))
                .doesNotThrowAnyException();
        verify(repository).deleteByProviderAndModelId("anthropic", "claude-haiku-4-5");
    }

    @Test
    @DisplayName("V493: resetAll closes every model it had opened")
    void resetAllClosesEveryOpenModel() {
        ModelConfigOverrideEntity open = input("anthropic", "haiku",
                new BigDecimal("1.00"), new BigDecimal("5.00"));
        open.setFreeTierEnabled(true);
        ModelConfigOverrideEntity closed = input("openai", "gpt-5",
                new BigDecimal("2.00"), new BigDecimal("8.00"));
        when(repository.findAllByOrderByRankingAsc()).thenReturn(java.util.List.of(open, closed));
        mirrorAccepts();

        service.resetAll();

        verify(authPricingSyncClient).sync(eq("anthropic"), eq("haiku"), any(), any(), any(), any(), any(), eq(false));
        verify(authPricingSyncClient, never()).sync(eq("openai"), eq("gpt-5"), any(), any(), any(), any(), any(), any());
        verify(repository).deleteAll();
    }
}
