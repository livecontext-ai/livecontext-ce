package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.credential.LlmCredentialRepository;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.repository.ModelCategorySettingsRepository;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The bundle_enabled intake state machine (V381): the transient
 * {@code bundleEnabledExplicitlySet} flag distinguishes "reset to inherit"
 * (explicit null) from "key absent, leave untouched" - the same contract as
 * the rate-limit fields. This is the cloud-admin CE-ship control; a silent
 * regression here would either clobber overrides on unrelated edits or make
 * "reset to inherit" impossible.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelCatalogService.saveOverride - bundleEnabled 3-state intake")
class ModelCatalogServiceBundleEnabledTest {

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

    private ModelConfigOverrideEntity existingRow() {
        ModelConfigOverrideEntity row = new ModelConfigOverrideEntity();
        row.setProvider("anthropic");
        row.setModelId("claude-fable-5");
        row.setDisplayName("Fable");
        return row;
    }

    private ModelConfigOverrideEntity input(Boolean bundleEnabled, boolean explicitlySet) {
        ModelConfigOverrideEntity in = new ModelConfigOverrideEntity();
        in.setProvider("anthropic");
        in.setModelId("claude-fable-5");
        in.setBundleEnabled(bundleEnabled);
        in.setBundleEnabledExplicitlySet(explicitlySet);
        return in;
    }

    @Test
    @DisplayName("explicit true is stored - the model ships to CE despite local greying")
    void explicitTrueStored() {
        ModelConfigOverrideEntity row = existingRow();
        when(repository.findByProviderAndModelId("anthropic", "claude-fable-5"))
                .thenReturn(Optional.of(row));

        service.saveOverride(input(true, true));

        assertThat(row.getBundleEnabled()).isTrue();
    }

    @Test
    @DisplayName("explicit false is stored - the model is withheld from CE despite local use")
    void explicitFalseStored() {
        ModelConfigOverrideEntity row = existingRow();
        when(repository.findByProviderAndModelId("anthropic", "claude-fable-5"))
                .thenReturn(Optional.of(row));

        service.saveOverride(input(false, true));

        assertThat(row.getBundleEnabled()).isFalse();
    }

    @Test
    @DisplayName("explicit null RESETS to inherit")
    void explicitNullResets() {
        ModelConfigOverrideEntity row = existingRow();
        row.setBundleEnabled(true);
        when(repository.findByProviderAndModelId("anthropic", "claude-fable-5"))
                .thenReturn(Optional.of(row));

        service.saveOverride(input(null, true));

        assertThat(row.getBundleEnabled())
                .as("explicitly-sent null must clear the override back to inherit")
                .isNull();
    }

    @Test
    @DisplayName("key absent leaves the stored override untouched (regression: unrelated edits must not clobber)")
    void absentKeyPreserves() {
        ModelConfigOverrideEntity row = existingRow();
        row.setBundleEnabled(false);
        when(repository.findByProviderAndModelId("anthropic", "claude-fable-5"))
                .thenReturn(Optional.of(row));

        ModelConfigOverrideEntity in = input(null, false);
        in.setDisplayName("Renamed"); // an ordinary edit that never mentions bundleEnabled
        service.saveOverride(in);

        assertThat(row.getBundleEnabled())
                .as("an update that never mentioned bundleEnabled must not clobber it")
                .isFalse();
    }

    @Test
    @DisplayName("bundleEnabled is NOT recorded in userModifiedFields - it never travels in a payload, so no merge can clobber it")
    void notTrackedAsUserModified() {
        ModelConfigOverrideEntity row = existingRow();
        when(repository.findByProviderAndModelId("anthropic", "claude-fable-5"))
                .thenReturn(Optional.of(row));

        service.saveOverride(input(true, true));

        String[] fields = row.getUserModifiedFields();
        assertThat(fields == null ? new String[0] : fields)
                .doesNotContain("bundleEnabled");
    }
}
