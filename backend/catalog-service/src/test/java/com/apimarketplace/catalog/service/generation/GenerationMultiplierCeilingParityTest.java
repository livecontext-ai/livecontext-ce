package com.apimarketplace.catalog.service.generation;

import com.apimarketplace.common.web.BillingContextHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One ceiling, read by every door that acts on a price factor.
 *
 * <p><b>The shape this replaces.</b> The descriptor parser had its own 100, and auth-service had a
 * second one under a javadoc that said "the same rule the descriptor parser enforces". Nothing tied
 * them together. Raise one to admit a legitimate modifier and the other keeps silently dropping the
 * factor: the reader is shown the published rate, the server charges the surcharge, and the
 * disagreement surfaces on an invoice nobody has a reason to distrust.
 *
 * <p>There is now a single constant in the module all of them already depend on for the header name
 * itself. This test is what stops a fourth door from inventing a fifth number: it asserts the
 * relationships, not the value, so raising the ceiling is a one-line change and forking it is not.
 */
@DisplayName("the price-factor ceiling has exactly one definition")
class GenerationMultiplierCeilingParityTest {

    @Test
    @DisplayName("the descriptor parser enforces the shared ceiling, not a copy of it")
    void theParserReadsTheSharedConstant() {
        // Identity, not equality: two BigDecimals that happen to be 100 today is precisely the
        // arrangement that drifted.
        assertThat(GenerationSpec.PriceModifier.MAX_FACTOR)
                .isSameAs(BillingContextHeaders.MAX_GENERATION_MULTIPLIER);
    }

    @Test
    @DisplayName("the shared sanitizer keeps a factor a descriptor can actually produce")
    void anOrdinaryFactorSurvives() {
        // The half that pays. A sanitizer that dropped everything would satisfy every refusal test
        // in the suite and quietly bill every modulated call at the published rate.
        assertThat(BillingContextHeaders.sanitizeGenerationMultiplier(new BigDecimal("1.2")))
                .isEqualByComparingTo("1.2");
        assertThat(BillingContextHeaders.sanitizeGenerationMultiplier(
                BillingContextHeaders.MAX_GENERATION_MULTIPLIER))
                .as("the ceiling itself is reachable, since the parser accepts a product equal to it")
                .isEqualByComparingTo(BillingContextHeaders.MAX_GENERATION_MULTIPLIER);
    }

    @Test
    @DisplayName("nothing outside the band survives, in either direction")
    void absurdFactorsAreDropped() {
        // Zero would multiply a whole charge away; a negative one is not a price; above the ceiling
        // cannot come from any descriptor this platform accepts.
        assertThat(BillingContextHeaders.sanitizeGenerationMultiplier(null)).isNull();
        assertThat(BillingContextHeaders.sanitizeGenerationMultiplier(BigDecimal.ZERO)).isNull();
        assertThat(BillingContextHeaders.sanitizeGenerationMultiplier(new BigDecimal("-3"))).isNull();
        assertThat(BillingContextHeaders.sanitizeGenerationMultiplier(
                BillingContextHeaders.MAX_GENERATION_MULTIPLIER.add(new BigDecimal("0.01")))).isNull();
    }

    @Test
    @DisplayName("a model whose modifiers reach the ceiling TOGETHER is the bound, not one factor")
    void theParserBoundsTheProduct() {
        // Stated here because the two are easy to confuse and the confusion is expensive: a single
        // factor under the ceiling can still be refused, and a quote drops the PRODUCT. If this
        // ever stops being the product, every door above is bounding the wrong number.
        BigDecimal single = new BigDecimal("4");
        assertThat(single).isLessThan(BillingContextHeaders.MAX_GENERATION_MULTIPLIER);
        assertThat(single.multiply(new BigDecimal("31")))
                .as("4x resolution and a 3-slot per-file rate of 10 reach 124x together")
                .isGreaterThan(BillingContextHeaders.MAX_GENERATION_MULTIPLIER);
    }
}
