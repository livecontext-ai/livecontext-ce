package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType;
import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.domain.PricingVersionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MarkupPolicy Tests")
class MarkupPolicyTest {

    private MarkupPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new MarkupPolicy();
    }

    @Nested
    @DisplayName("validateMarkupConfig")
    class ValidateMarkupConfig {

        @Test
        @DisplayName("Accepts zero markup + zero maxCalls for any auth type")
        void acceptsZeroForAnyAuthType() {
            for (AuthType t : AuthType.values()) {
                policy.validateMarkupConfig(t, BigDecimal.ZERO, 0);
            }
        }

        @Test
        @DisplayName("Accepts positive markup for API_KEY")
        void acceptsPositiveForApiKey() {
            policy.validateMarkupConfig(AuthType.API_KEY, new BigDecimal("0.5"), 100);
        }

        @Test
        @DisplayName("Accepts null markup - means 'no default, overrides only'")
        void acceptsNullMarkupAsNoDefault() {
            // V135 widened the contract: a null default is legal and means
            // per-tool overrides are the only source of markup. The service
            // layer rejects the degenerate case (null default AND empty
            // overrides); the policy itself no longer does.
            policy.validateMarkupConfig(AuthType.API_KEY, null, 10);
        }

        @Test
        @DisplayName("Accepts null markup on OAUTH2 when maxCalls is zero")
        void acceptsNullMarkupOnOauth2() {
            policy.validateMarkupConfig(AuthType.OAUTH2, null, 0);
        }

        @Test
        @DisplayName("Rejects null markup on OAUTH2 when maxCalls is non-zero")
        void rejectsNullMarkupOnOauth2WithCalls() {
            assertThatThrownBy(() -> policy.validateMarkupConfig(AuthType.OAUTH2, null, 3))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("OAuth2");
        }

        @Test
        @DisplayName("Rejects negative markup")
        void rejectsNegativeMarkup() {
            assertThatThrownBy(() -> policy.validateMarkupConfig(
                    AuthType.API_KEY, new BigDecimal("-0.01"), 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(">= 0");
        }

        @Test
        @DisplayName("Rejects negative maxCallsPerRun")
        void rejectsNegativeMaxCalls() {
            assertThatThrownBy(() -> policy.validateMarkupConfig(
                    AuthType.API_KEY, BigDecimal.ZERO, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxCallsPerRun");
        }

        @Test
        @DisplayName("Rejects positive markup on OAUTH2")
        void rejectsOauth2WithMarkup() {
            assertThatThrownBy(() -> policy.validateMarkupConfig(
                    AuthType.OAUTH2, new BigDecimal("0.1"), 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("OAuth2");
        }

        @Test
        @DisplayName("Rejects positive maxCalls on OAUTH2 even when markup=0")
        void rejectsOauth2WithMaxCalls() {
            assertThatThrownBy(() -> policy.validateMarkupConfig(
                    AuthType.OAUTH2, BigDecimal.ZERO, 5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("OAuth2");
        }
    }

    @Nested
    @DisplayName("resolveEffectiveMarkup")
    class ResolveEffectiveMarkup {

        @Test
        @DisplayName("Returns ZERO when version is null")
        void returnsZeroForNullVersion() {
            assertThat(policy.resolveEffectiveMarkup(null, Optional.empty()))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Uses version default when no per-tool override")
        void usesVersionDefaultWhenNoOverride() {
            PlatformCredentialPricingVersion v = new PlatformCredentialPricingVersion();
            v.setDefaultMarkupCredits(new BigDecimal("0.25"));

            BigDecimal rate = policy.resolveEffectiveMarkup(v, Optional.empty());

            assertThat(rate).isEqualByComparingTo("0.25");
        }

        @Test
        @DisplayName("Per-tool override wins over version default")
        void perToolOverrideWins() {
            PlatformCredentialPricingVersion v = new PlatformCredentialPricingVersion();
            v.setDefaultMarkupCredits(new BigDecimal("0.25"));
            PricingVersionEntry entry = new PricingVersionEntry();
            entry.setMarkupCredits(new BigDecimal("1.50"));

            BigDecimal rate = policy.resolveEffectiveMarkup(v, Optional.of(entry));

            assertThat(rate).isEqualByComparingTo("1.50");
        }

        @Test
        @DisplayName("Per-tool override of zero is respected (not treated as absent)")
        void perToolZeroIsRespected() {
            PlatformCredentialPricingVersion v = new PlatformCredentialPricingVersion();
            v.setDefaultMarkupCredits(new BigDecimal("0.25"));
            PricingVersionEntry entry = new PricingVersionEntry();
            entry.setMarkupCredits(BigDecimal.ZERO);

            BigDecimal rate = policy.resolveEffectiveMarkup(v, Optional.of(entry));

            assertThat(rate).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Null markup on version falls to ZERO")
        void nullVersionDefaultIsZero() {
            PlatformCredentialPricingVersion v = new PlatformCredentialPricingVersion();
            v.setDefaultMarkupCredits(null);

            BigDecimal rate = policy.resolveEffectiveMarkup(v, Optional.empty());

            assertThat(rate).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("projectedMarkup")
    class ProjectedMarkup {

        @Test
        @DisplayName("Clamps remainingCalls by maxPerRun")
        void clampsByMaxPerRun() {
            BigDecimal proj = policy.projectedMarkup(new BigDecimal("0.10"), 50, 10);
            assertThat(proj).isEqualByComparingTo("1.00");
        }

        @Test
        @DisplayName("Uses remainingCalls when smaller than maxPerRun")
        void usesRemainingWhenSmaller() {
            BigDecimal proj = policy.projectedMarkup(new BigDecimal("0.10"), 3, 100);
            assertThat(proj).isEqualByComparingTo("0.30");
        }

        @Test
        @DisplayName("Returns ZERO when perCallMarkup is null or zero")
        void returnsZeroForZeroRate() {
            assertThat(policy.projectedMarkup(null, 10, 10)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(policy.projectedMarkup(BigDecimal.ZERO, 10, 10)).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Returns ZERO when remainingCalls <= 0")
        void returnsZeroForNoRemaining() {
            assertThat(policy.projectedMarkup(new BigDecimal("0.10"), 0, 10))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Returns ZERO when maxPerRun <= 0")
        void returnsZeroForZeroMax() {
            assertThat(policy.projectedMarkup(new BigDecimal("0.10"), 10, 0))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }
}
