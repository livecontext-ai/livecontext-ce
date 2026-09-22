package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.ModelPricing;
import com.apimarketplace.auth.repository.ModelPricingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Per-provider override of the cloud LLM billing multiplier
 * ({@code billing.llm.provider-multipliers}).
 *
 * <p>Two things are pinned here and they are not the same thing. The MECHANISM: an
 * override applies where it is configured, nothing moves anywhere else, and bad input
 * fails towards the standard margin rather than towards a free call. The POLICY: the
 * shipped configuration overrides NO provider, so every provider bills at the global
 * lever and the platform has one margin.
 *
 * <p>The mechanism tests therefore name a provider that does not exist ({@code acme}).
 * They used to configure {@code typesafe=10}, which was the shipped value for two days;
 * a fixture that doubles as a policy claim is how a test suite ends up asserting a price
 * the deployment no longer charges, so the two are kept apart on purpose. What the
 * decision provider actually costs a user is pinned in {@code EndToEnd} instead, at the
 * lever that really applies to it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelPricingService per-provider billing multiplier")
class ModelPricingServiceProviderMultiplierTest {

    /** The shipped global lever: 25% gross margin per request. */
    private static final BigDecimal GLOBAL = new BigDecimal("1.333333");

    @Mock
    private ModelPricingRepository pricingRepository;

    private ModelPricingService serviceWith(String providerMultipliersCsv) {
        return new ModelPricingService(pricingRepository, GLOBAL, providerMultipliersCsv);
    }

    /** A service configured exactly as the application ships it. */
    private ModelPricingService shippedService() {
        return serviceWith(ModelPricingService.DEFAULT_PROVIDER_MULTIPLIERS_VALUE);
    }

    private void mockPricing(String provider, String model, String inputRate, String outputRate) {
        ModelPricing pricing = new ModelPricing();
        pricing.setProvider(provider);
        pricing.setModel(model);
        pricing.setInputRate(new BigDecimal(inputRate));
        pricing.setOutputRate(new BigDecimal(outputRate));
        pricing.setFixedCost(BigDecimal.ZERO);
        when(pricingRepository.findCurrentPricing(anyString(), anyString()))
                .thenReturn(Optional.of(pricing));
    }

    @Nested
    @DisplayName("Shipped policy: no provider is overridden")
    class ShippedPolicy {

        @Test
        @DisplayName("the shipped default overrides nothing, so one margin covers every provider")
        void shippedDefaultOverridesNothing() {
            // Pinned here as well as in EconomicsConfigPinTest because that test compares
            // two configuration FILES: it would stay green if both were changed together,
            // which is exactly how a margin moves without anyone deciding it.
            assertThat(ModelPricingService.DEFAULT_PROVIDER_MULTIPLIERS_VALUE).isBlank();
        }

        @Test
        @DisplayName("the decision provider bills at the global lever like everything else")
        void decisionProviderBillsAtTheGlobalLever() {
            assertThat(shippedService().resolveCloudLlmBillingMultiplier("typesafe"))
                    .isEqualByComparingTo(GLOBAL);
        }

        @Test
        @DisplayName("and the estimate basis carries no per-provider correction at all")
        void shippedBasisCarriesNoCorrection() {
            // A picker quotes from coefficients that fold in the GLOBAL lever. With no
            // override anywhere, there is nothing to correct and the payload is the one
            // every provider is priced by.
            assertThat(shippedService().getProviderBillingScales()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Resolution")
    class Resolution {

        @Test
        @DisplayName("a configured provider resolves to its own multiplier, not the global one")
        void configuredProviderUsesItsOwnMultiplier() {
            ModelPricingService service = serviceWith("acme=10");

            assertThat(service.resolveCloudLlmBillingMultiplier("acme"))
                    .isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("an unconfigured provider keeps billing at the global multiplier")
        void unconfiguredProviderKeepsGlobalMultiplier() {
            ModelPricingService service = serviceWith("acme=10");

            assertThat(service.resolveCloudLlmBillingMultiplier("anthropic"))
                    .isEqualByComparingTo(GLOBAL);
        }

        @Test
        @DisplayName("the provider name is matched case-insensitively")
        void providerNameIsCaseInsensitive() {
            ModelPricingService service = serviceWith("AcMe=10");

            assertThat(service.resolveCloudLlmBillingMultiplier("ACME"))
                    .isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("a null provider falls back to the global multiplier instead of throwing")
        void nullProviderFallsBackToGlobal() {
            ModelPricingService service = serviceWith("acme=10");

            assertThat(service.resolveCloudLlmBillingMultiplier(null))
                    .isEqualByComparingTo(GLOBAL);
        }

        @Test
        @DisplayName("several providers can be configured in one comma-separated value")
        void severalProvidersInOneValue() {
            ModelPricingService service = serviceWith("acme=10,globex=2.5");

            assertThat(service.resolveCloudLlmBillingMultiplier("acme")).isEqualByComparingTo("10");
            assertThat(service.resolveCloudLlmBillingMultiplier("globex")).isEqualByComparingTo("2.5");
        }

        @Test
        @DisplayName("surrounding whitespace in the configured value is tolerated")
        void whitespaceIsTolerated() {
            ModelPricingService service = serviceWith("  acme = 10 , globex = 2.5  ");

            assertThat(service.resolveCloudLlmBillingMultiplier("acme")).isEqualByComparingTo("10");
            assertThat(service.resolveCloudLlmBillingMultiplier("globex")).isEqualByComparingTo("2.5");
        }
    }

    @Nested
    @DisplayName("Bad configuration fails towards the standard margin, never towards a free call")
    class BadConfiguration {

        @ParameterizedTest(name = "[{index}] \"{0}\"")
        @ValueSource(strings = {"", "   ", "acme", "=10", "acme=", "acme=abc", ",,,"})
        @DisplayName("a blank or malformed value leaves every provider on the global multiplier")
        void malformedValueLeavesGlobalMultiplier(String csv) {
            ModelPricingService service = serviceWith(csv);

            assertThat(service.resolveCloudLlmBillingMultiplier("acme"))
                    .isEqualByComparingTo(GLOBAL);
        }

        @ParameterizedTest(name = "[{index}] acme={0}")
        @ValueSource(strings = {"0", "0.0", "-1", "-0.5"})
        @DisplayName("a non-positive multiplier is dropped, so it can never zero out or invert a charge")
        void nonPositiveMultiplierIsDropped(String multiplier) {
            ModelPricingService service = serviceWith("acme=" + multiplier);

            assertThat(service.resolveCloudLlmBillingMultiplier("acme"))
                    .isEqualByComparingTo(GLOBAL);
        }

        @Test
        @DisplayName("one bad pair does not discard the good pairs beside it")
        void oneBadPairDoesNotDiscardTheGoodOnes() {
            ModelPricingService service = serviceWith("acme=oops,globex=2.5");

            assertThat(service.resolveCloudLlmBillingMultiplier("acme")).isEqualByComparingTo(GLOBAL);
            assertThat(service.resolveCloudLlmBillingMultiplier("globex")).isEqualByComparingTo("2.5");
        }
    }

    @Nested
    @DisplayName("Application to an amount")
    class Application {

        @Test
        @DisplayName("applyCloudLlmBillingMultiplier charges the configured provider at its own lever")
        void appliesTheProviderLever() {
            ModelPricingService service = serviceWith("acme=10");

            assertThat(service.applyCloudLlmBillingMultiplier(
                    "acme", "acme-1", new BigDecimal("0.048300")))
                    .isEqualByComparingTo("0.483000");
        }

        @Test
        @DisplayName("a provider excluded from the multiplier stays excluded even when configured")
        void exclusionWinsOverTheOverride() {
            ModelPricingService service = serviceWith("websearch=10");

            assertThat(service.applyCloudLlmBillingMultiplier(
                    "websearch", "search", new BigDecimal("1.000000")))
                    .isEqualByComparingTo("1.000000");
        }

        @Test
        @DisplayName("an image model stays excluded even when its provider carries an override")
        void imageModelStaysExcluded() {
            ModelPricingService service = serviceWith("openai=10");

            assertThat(service.applyCloudLlmBillingMultiplier(
                    "openai", "gpt-image-1-low", new BigDecimal("10.000000")))
                    .isEqualByComparingTo("10.000000");
        }
    }

    @Nested
    @DisplayName("What the published estimate is told")
    class PublishedEstimate {

        @Test
        @DisplayName("an overridden provider publishes the factor its bill differs by")
        void publishesTheFactorForAnOverriddenProvider() {
            // The estimate basis is a table of per-profile coefficients with the GLOBAL
            // lever folded in and no provider in scope. Without this factor a picker quotes
            // an overridden provider at the global lever and shows a price the ledger does
            // not debit, which is the one invariant LlmCostEstimateService exists to hold.
            ModelPricingService service = serviceWith("acme=10");

            // 10 / 1.333333 = 7.500002 at six places.
            assertThat(service.getProviderBillingScales())
                    .hasSize(1)
                    .hasEntrySatisfying("acme",
                            scale -> assertThat(scale).isEqualByComparingTo("7.500002"));
        }

        @Test
        @DisplayName("an install that overrides nothing publishes nothing, so the payload is unchanged")
        void publishesNothingWhenNothingIsOverridden() {
            assertThat(serviceWith("").getProviderBillingScales()).isEmpty();
        }

        @ParameterizedTest(name = "[{index}] cloud-multiplier={0}")
        @ValueSource(strings = {"0", "-1"})
        @DisplayName("a non-positive GLOBAL lever falls back rather than dividing by zero")
        void nonPositiveGlobalLeverFallsBack(String multiplier) {
            // The global lever became a DIVISOR when the per-provider factor was derived
            // from it, so a configured zero would throw on every estimate-basis request.
            // It was nonsense as a margin before that too: zero makes every call free.
            ModelPricingService service = new ModelPricingService(
                    pricingRepository, new BigDecimal(multiplier), "acme=10");

            assertThat(service.getCloudLlmBillingMultiplier()).isEqualByComparingTo(
                    ModelPricingService.DEFAULT_CLOUD_LLM_BILLING_MULTIPLIER_VALUE);
            // And the derived factor is computable rather than an exception.
            assertThat(service.getProviderBillingScales()).containsKey("acme");
        }

        @Test
        @DisplayName("the factor is exactly the ratio between what is quoted and what is debited")
        void theFactorReconcilesTheQuoteWithTheLedger() {
            mockPricing("acme", "acme-1", "0.042", "0");
            ModelPricingService service = serviceWith("acme=10");

            BigDecimal debited = service.calculateCost("acme", "acme-1", 1_150, 30);
            // What a picker would compute from the global-lever coefficients alone.
            BigDecimal quotedAtGlobalLever = new BigDecimal("0.048300").multiply(GLOBAL);
            BigDecimal scale = service.getProviderBillingScales().get("acme");

            assertThat(quotedAtGlobalLever.multiply(scale))
                    .isCloseTo(debited, org.assertj.core.data.Offset.offset(new BigDecimal("0.00001")));
        }
    }

    @Nested
    @DisplayName("End-to-end through calculateCost, at the levers that actually ship")
    class EndToEnd {

        /**
         * The measured unit of work: the median classify step on the platform, 2.6 credits
         * of PROVIDER cost on Claude Sonnet 5 ($2.00 / $10.00 per 1M), which is 1,150 input
         * and 30 output tokens (n=2,649, measured 2026-09-03). Do NOT read the 2.6 off
         * the frontend's {@code MEASURED_PROVIDER_COST.classifyStep}: that one carries
         * {@link LlmCostProfile#CLASSIFY_STEP} (1,200 / 60) and is priced on the
         * lightweight model its page publishes, so it is a different number twice over.
         */
        private static final int CLASSIFY_PROMPT_TOKENS = 1_150;
        private static final int CLASSIFY_COMPLETION_TOKENS = 30;

        @Test
        @DisplayName("the LLM path bills 3.47 credits for one classification")
        void llmPathBillsTheStandardMargin() {
            mockPricing("anthropic", "claude-sonnet-5", "2.0", "10.0");

            BigDecimal cost = shippedService().calculateCost("anthropic", "claude-sonnet-5",
                    CLASSIFY_PROMPT_TOKENS, CLASSIFY_COMPLETION_TOKENS);

            // 2.6 credits of provider cost x 1.333333
            assertThat(cost).isEqualByComparingTo("3.466666");
        }

        @Test
        @DisplayName("the decision model bills the same 25% margin, which is 54x less for the user")
        void decisionModelBillsTheStandardMargin() {
            mockPricing("typesafe", "jev-latest", "0.042", "0");

            BigDecimal cost = shippedService().calculateCost("typesafe", "jev-latest",
                    CLASSIFY_PROMPT_TOKENS, CLASSIFY_COMPLETION_TOKENS);
            BigDecimal margin = cost.subtract(new BigDecimal("0.048300"));

            // 0.0483 credits of provider cost (the output is free) x 1.333333.
            assertThat(cost).isEqualByComparingTo("0.064400");
            // The trade this margin decision makes, both halves of it, so neither can be
            // changed by accident: the user pays 54x less than the 3.466666 above, and the
            // platform earns 0.0161 credits ($0.0000161) on the call instead of 0.87.
            assertThat(margin).isEqualByComparingTo("0.016100");
            assertThat(cost).isLessThan(new BigDecimal("0.07"));
        }

        @Test
        @DisplayName("the same call would bill 0.483 credits if a x10 override were ever configured")
        void anOverrideWouldRaiseItTenfold() {
            // Kept as the counterfactual the configuration comment describes, so the effect
            // of setting billing.llm.provider-multipliers is measured rather than asserted
            // in prose. This is NOT what ships: see ShippedPolicy above.
            mockPricing("typesafe", "jev-latest", "0.042", "0");
            ModelPricingService service = serviceWith("typesafe=10");

            assertThat(service.calculateCost("typesafe", "jev-latest",
                    CLASSIFY_PROMPT_TOKENS, CLASSIFY_COMPLETION_TOKENS))
                    .isEqualByComparingTo("0.483000");
        }
    }
}
