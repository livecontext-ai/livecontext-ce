package com.apimarketplace.agent.service;

import com.apimarketplace.agent.config.ModelPricingConfig;
import com.apimarketplace.agent.config.ModelPricingConfig.ModelRateLimitInfo;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.ratelimit.ModelRateLimit;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the 3-layer rate-limit resolution chain:
 *
 * <pre>
 *   DB override (per-field)  →  YAML default (seed)  →  provider default (null = inherit)
 * </pre>
 *
 * <p>The provider merges two sources into a single cache: YAML-declared defaults from
 * {@link ModelPricingConfig#getRateLimits()} and runtime overrides from
 * {@code agent.model_config_overrides}. These tests cover the merging semantics plus
 * the Anthropic itpm/otpm collapse and the "bare modelId vs provider:modelId" key
 * fallthrough used by {@link CachedModelRateLimitProvider#getModelLimit}.
 */
@DisplayName("CachedModelRateLimitProvider - YAML + DB merge")
@ExtendWith(MockitoExtension.class)
class CachedModelRateLimitProviderTest {

    @Mock
    private ModelConfigOverrideRepository repository;

    private ModelPricingConfig pricingConfig;
    private CachedModelRateLimitProvider provider;

    @BeforeEach
    void setUp() {
        pricingConfig = new ModelPricingConfig();
        provider = new CachedModelRateLimitProvider(repository, pricingConfig);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private ModelRateLimitInfo yamlInfo(Integer tpm, Integer itpm, Integer otpm,
                                         Integer rpm, Integer tpmPerTenant, Integer rpmPerTenant) {
        ModelRateLimitInfo info = new ModelRateLimitInfo();
        info.setTpm(tpm);
        info.setItpm(itpm);
        info.setOtpm(otpm);
        info.setRpm(rpm);
        info.setTpmPerTenant(tpmPerTenant);
        info.setRpmPerTenant(rpmPerTenant);
        return info;
    }

    private ModelConfigOverrideEntity dbOverride(String provider, String modelId,
                                                  Integer tpm, Integer rpm,
                                                  Integer tpmPerTenant, Integer rpmPerTenant) {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setProvider(provider);
        e.setModelId(modelId);
        e.setRateLimitTpm(tpm);
        e.setRateLimitRpm(rpm);
        e.setRateLimitTpmPerTenant(tpmPerTenant);
        e.setRateLimitRpmPerTenant(rpmPerTenant);
        return e;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // effectiveTpm() - Anthropic itpm/otpm collapse
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ModelRateLimitInfo.effectiveTpm()")
    class EffectiveTpm {

        @Test
        @DisplayName("explicit tpm wins over itpm/otpm")
        void explicitTpmWins() {
            assertThat(yamlInfo(5_000_000, 800_000, 160_000, null, null, null).effectiveTpm())
                    .isEqualTo(5_000_000);
        }

        @Test
        @DisplayName("itpm + otpm are summed when tpm is null")
        void sumItpmAndOtpm() {
            assertThat(yamlInfo(null, 800_000, 160_000, null, null, null).effectiveTpm())
                    .isEqualTo(960_000);
        }

        @Test
        @DisplayName("itpm alone is used when otpm is null")
        void itpmOnly() {
            assertThat(yamlInfo(null, 800_000, null, null, null, null).effectiveTpm())
                    .isEqualTo(800_000);
        }

        @Test
        @DisplayName("otpm alone is used when itpm is null")
        void otpmOnly() {
            assertThat(yamlInfo(null, null, 160_000, null, null, null).effectiveTpm())
                    .isEqualTo(160_000);
        }

        @Test
        @DisplayName("all null → null (inherit from provider default)")
        void allNull() {
            assertThat(yamlInfo(null, null, null, null, null, null).effectiveTpm()).isNull();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Layer 1: YAML defaults
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("YAML default is exposed under bare modelId key")
    void yamlDefaultLookupByBareModelId() {
        Map<String, ModelRateLimitInfo> yaml = new HashMap<>();
        yaml.put("gpt-5", yamlInfo(4_000_000, null, null, 10_000, 200_000, 500));
        pricingConfig.setRateLimits(yaml);
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        provider.refreshCache();

        ModelRateLimit limit = provider.getModelLimit("openai", "gpt-5");
        assertThat(limit).isNotNull();
        assertThat(limit.tpm()).isEqualTo(4_000_000);
        assertThat(limit.rpm()).isEqualTo(10_000);
        assertThat(limit.tpmPerTenant()).isEqualTo(200_000);
        assertThat(limit.rpmPerTenant()).isEqualTo(500);
    }

    @Test
    @DisplayName("YAML Anthropic entry collapses itpm+otpm into aggregate tpm")
    void yamlAnthropicCollapse() {
        Map<String, ModelRateLimitInfo> yaml = new HashMap<>();
        yaml.put("claude-opus-4-6", yamlInfo(null, 800_000, 160_000, 2_000, 40_000, 100));
        pricingConfig.setRateLimits(yaml);
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        provider.refreshCache();

        ModelRateLimit limit = provider.getModelLimit("anthropic", "claude-opus-4-6");
        assertThat(limit).isNotNull();
        assertThat(limit.tpm()).as("itpm + otpm").isEqualTo(960_000);
        assertThat(limit.rpm()).isEqualTo(2_000);
    }

    @Test
    @DisplayName("unknown model → null (inherit provider default)")
    void unknownModelReturnsNull() {
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());
        provider.refreshCache();

        assertThat(provider.getModelLimit("openai", "nonexistent-model")).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Layer 2: DB overrides
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DB override with no YAML seed → full DB values")
    void dbOverrideStandalone() {
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(
                dbOverride("openai", "gpt-5", 9_999_999, 999, 888_888, 88)
        ));

        provider.refreshCache();

        ModelRateLimit limit = provider.getModelLimit("openai", "gpt-5");
        assertThat(limit).isNotNull();
        assertThat(limit.tpm()).isEqualTo(9_999_999);
        assertThat(limit.rpm()).isEqualTo(999);
        assertThat(limit.tpmPerTenant()).isEqualTo(888_888);
        assertThat(limit.rpmPerTenant()).isEqualTo(88);
    }

    @Test
    @DisplayName("DB override per-field merge: null columns do NOT clear YAML defaults")
    void dbPartialOverrideMergesWithYaml() {
        Map<String, ModelRateLimitInfo> yaml = new HashMap<>();
        yaml.put("gpt-5", yamlInfo(4_000_000, null, null, 10_000, 200_000, 500));
        pricingConfig.setRateLimits(yaml);

        // DB only sets tpm-per-tenant; other columns are null.
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(
                dbOverride("openai", "gpt-5", null, null, 999_999, null)
        ));

        provider.refreshCache();

        ModelRateLimit limit = provider.getModelLimit("openai", "gpt-5");
        assertThat(limit).isNotNull();
        assertThat(limit.tpm()).as("YAML tpm preserved").isEqualTo(4_000_000);
        assertThat(limit.rpm()).as("YAML rpm preserved").isEqualTo(10_000);
        assertThat(limit.tpmPerTenant()).as("DB override wins").isEqualTo(999_999);
        assertThat(limit.rpmPerTenant()).as("YAML rpm-per-tenant preserved").isEqualTo(500);
    }

    @Test
    @DisplayName("DeepSeek V216 override disables tenant TPM without clearing global TPM/RPM")
    void deepSeekTenantTpmDisableKeepsGlobalLimits() {
        Map<String, ModelRateLimitInfo> yaml = new HashMap<>();
        yaml.put("deepseek-chat", yamlInfo(2_000_000, null, null, 5_000, 100_000, 250));
        pricingConfig.setRateLimits(yaml);

        // V216 writes only rate_limit_tpm_per_tenant=-1 for DeepSeek. The runtime
        // merge must keep the YAML global caps so one tenant cannot exhaust the
        // shared provider quota, while disabling the per-tenant prompt-size preflight.
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(
                dbOverride("deepseek", "deepseek-chat", null, null, -1, null)
        ));

        provider.refreshCache();

        ModelRateLimit limit = provider.getModelLimit("deepseek", "deepseek-chat");
        assertThat(limit).isNotNull();
        assertThat(limit.tpm()).as("YAML global TPM remains enforced").isEqualTo(2_000_000);
        assertThat(limit.rpm()).as("YAML global RPM remains enforced").isEqualTo(5_000);
        assertThat(limit.tpmPerTenant()).as("-1 disables tenant TPM preflight").isEqualTo(-1);
        assertThat(limit.rpmPerTenant()).as("YAML tenant RPM remains enforced").isEqualTo(250);
    }

    @Test
    @DisplayName("DB override wins over YAML default when both set the same field")
    void dbOverrideWinsOverYamlForSameField() {
        Map<String, ModelRateLimitInfo> yaml = new HashMap<>();
        yaml.put("gpt-5", yamlInfo(4_000_000, null, null, 10_000, 200_000, 500));
        pricingConfig.setRateLimits(yaml);

        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(
                dbOverride("openai", "gpt-5", 1_000_000, null, null, null)
        ));

        provider.refreshCache();

        ModelRateLimit limit = provider.getModelLimit("openai", "gpt-5");
        assertThat(limit.tpm()).as("DB tpm wins").isEqualTo(1_000_000);
        assertThat(limit.rpm()).as("YAML rpm preserved").isEqualTo(10_000);
    }

    @Test
    @DisplayName("DB entry with all-null rate-limit columns is skipped")
    void dbEntryWithAllNullsSkipped() {
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(
                dbOverride("openai", "gpt-5", null, null, null, null)
        ));

        provider.refreshCache();

        assertThat(provider.getModelLimit("openai", "gpt-5")).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Key resolution order: provider:modelId first, then bare modelId
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("scoped key 'provider:modelId' (DB) wins over bare modelId (YAML)")
    void scopedKeyWinsOverBareModelId() {
        Map<String, ModelRateLimitInfo> yaml = new HashMap<>();
        yaml.put("gpt-5", yamlInfo(4_000_000, null, null, 10_000, null, null));
        pricingConfig.setRateLimits(yaml);

        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(
                dbOverride("openai", "gpt-5", 7_777_777, 777, null, null)
        ));

        provider.refreshCache();

        // openai:gpt-5 is keyed by scoped key after DB merge → returns DB-merged limit
        ModelRateLimit limit = provider.getModelLimit("openai", "gpt-5");
        assertThat(limit.tpm()).isEqualTo(7_777_777);
        assertThat(limit.rpm()).isEqualTo(777);
    }

    @Test
    @DisplayName("bare modelId key is used when no scoped key exists (YAML-only fallback)")
    void bareModelIdFallbackWhenNoScopedKey() {
        Map<String, ModelRateLimitInfo> yaml = new HashMap<>();
        yaml.put("gpt-5", yamlInfo(4_000_000, null, null, 10_000, null, null));
        pricingConfig.setRateLimits(yaml);
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        provider.refreshCache();

        // Any provider name should resolve via the bare modelId fallback.
        assertThat(provider.getModelLimit("openai", "gpt-5")).isNotNull();
        assertThat(provider.getModelLimit("some-bridge", "gpt-5")).isNotNull();
    }

    @Test
    @DisplayName("getModelLimit with null modelId returns null (guard)")
    void nullModelIdReturnsNull() {
        pricingConfig.setRateLimits(Map.of());
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());
        provider.refreshCache();

        assertThat(provider.getModelLimit("openai", null)).isNull();
    }
}
