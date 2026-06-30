package com.apimarketplace.agent.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ModelPricingConfig.
 */
@DisplayName("ModelPricingConfig")
class ModelPricingConfigTest {

    private ModelPricingConfig config;

    @BeforeEach
    void setUp() {
        config = new ModelPricingConfig();
    }

    @Nested
    @DisplayName("Pricing")
    class PricingTests {

        @Test
        @DisplayName("should return pricing for known model")
        void shouldReturnPricingForKnownModel() {
            ModelPricingConfig.PricingInfo info = new ModelPricingConfig.PricingInfo();
            info.setInput(2.5);
            info.setOutput(10.0);

            config.setPricing(Map.of("gpt-4", info));

            ModelPricingConfig.PricingInfo result = config.getPricingForModel("gpt-4");
            assertThat(result).isNotNull();
            assertThat(result.getInput()).isEqualTo(2.5);
            assertThat(result.getOutput()).isEqualTo(10.0);
        }

        @Test
        @DisplayName("should return null for unknown model")
        void shouldReturnNullForUnknown() {
            assertThat(config.getPricingForModel("unknown-model")).isNull();
        }

        @Test
        @DisplayName("should initialize with empty pricing map")
        void shouldInitializeEmpty() {
            assertThat(config.getPricing()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Rankings")
    class RankingsTests {

        @Test
        @DisplayName("should return ranking for known model")
        void shouldReturnRankingForKnownModel() {
            config.setRankings(Map.of("gpt-4", 1, "claude-3", 2));

            assertThat(config.getRankingForModel("gpt-4")).isEqualTo(1);
            assertThat(config.getRankingForModel("claude-3")).isEqualTo(2);
        }

        @Test
        @DisplayName("should return 999 for unknown model")
        void shouldReturnDefaultForUnknown() {
            assertThat(config.getRankingForModel("unknown-model")).isEqualTo(999);
        }

        @Test
        @DisplayName("should initialize with empty rankings map")
        void shouldInitializeEmpty() {
            assertThat(config.getRankings()).isEmpty();
        }
    }

    @Nested
    @DisplayName("RateLimits")
    class RateLimitTests {

        private ModelPricingConfig.ModelRateLimitInfo info(Integer tpm, Integer itpm, Integer otpm,
                                                            Integer rpm, Integer tpmPerTenant, Integer rpmPerTenant) {
            ModelPricingConfig.ModelRateLimitInfo i = new ModelPricingConfig.ModelRateLimitInfo();
            i.setTpm(tpm);
            i.setItpm(itpm);
            i.setOtpm(otpm);
            i.setRpm(rpm);
            i.setTpmPerTenant(tpmPerTenant);
            i.setRpmPerTenant(rpmPerTenant);
            return i;
        }

        @Test
        @DisplayName("should return rate limit for known model")
        void shouldReturnRateLimitForKnownModel() {
            ModelPricingConfig.ModelRateLimitInfo limit = info(4_000_000, null, null, 10_000, 200_000, 500);
            config.setRateLimits(Map.of("gpt-5", limit));

            ModelPricingConfig.ModelRateLimitInfo result = config.getRateLimitForModel("gpt-5");
            assertThat(result).isNotNull();
            assertThat(result.getTpm()).isEqualTo(4_000_000);
            assertThat(result.getRpm()).isEqualTo(10_000);
            assertThat(result.getTpmPerTenant()).isEqualTo(200_000);
            assertThat(result.getRpmPerTenant()).isEqualTo(500);
        }

        @Test
        @DisplayName("should return null for unknown model")
        void shouldReturnNullForUnknown() {
            assertThat(config.getRateLimitForModel("unknown")).isNull();
        }

        @Test
        @DisplayName("should initialize with empty rate-limits map")
        void shouldInitializeEmpty() {
            assertThat(config.getRateLimits()).isEmpty();
        }

        @Test
        @DisplayName("effectiveTpm prefers explicit tpm over itpm/otpm")
        void effectiveTpmPrefersExplicit() {
            assertThat(info(5_000_000, 800_000, 160_000, null, null, null).effectiveTpm())
                    .isEqualTo(5_000_000);
        }

        @Test
        @DisplayName("effectiveTpm sums itpm + otpm when explicit tpm is null (Anthropic)")
        void effectiveTpmSumsAnthropic() {
            assertThat(info(null, 800_000, 160_000, null, null, null).effectiveTpm())
                    .isEqualTo(960_000);
        }

        @Test
        @DisplayName("effectiveTpm returns null when all token dimensions are null")
        void effectiveTpmAllNull() {
            assertThat(info(null, null, null, 100, null, null).effectiveTpm()).isNull();
        }
    }

    @Nested
    @DisplayName("PricingInfo")
    class PricingInfoTests {

        @Test
        @DisplayName("should get and set input/output prices")
        void shouldGetAndSetPrices() {
            ModelPricingConfig.PricingInfo info = new ModelPricingConfig.PricingInfo();
            info.setInput(5.0);
            info.setOutput(15.0);

            assertThat(info.getInput()).isEqualTo(5.0);
            assertThat(info.getOutput()).isEqualTo(15.0);
        }

        @Test
        @DisplayName("should default to 0.0 for unset prices")
        void shouldDefaultToZero() {
            ModelPricingConfig.PricingInfo info = new ModelPricingConfig.PricingInfo();

            assertThat(info.getInput()).isEqualTo(0.0);
            assertThat(info.getOutput()).isEqualTo(0.0);
        }
    }
}
