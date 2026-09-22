package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.domain.PricingVersionEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a call's own CHOICES do to the amount it is charged.
 *
 * <p>The factor multiplies the PRICE rather than the quantity, and both halves
 * of that decision are pinned here: a quantity is reported back to the customer
 * as the size of what they bought, and a flat per-call row has no quantity to
 * scale at all - which is exactly the shape of the models that sell a
 * resolution at one price.
 */
class MarkupPolicyPriceMultiplierTest {

    private final MarkupPolicy policy = new MarkupPolicy();

    private static PlatformCredentialPricingVersion version(String defaultMarkup) {
        PlatformCredentialPricingVersion v = new PlatformCredentialPricingVersion();
        v.setDefaultMarkupCredits(new BigDecimal(defaultMarkup));
        return v;
    }

    private static Optional<PricingVersionEntry> row(String unit, String base, String perUnit,
                                                      String min, String max) {
        PricingVersionEntry entry = new PricingVersionEntry();
        entry.setPriceUnit(unit);
        entry.setMarkupCredits(new BigDecimal(base));
        entry.setUnitCredits(new BigDecimal(perUnit));
        if (min != null) entry.setMinCredits(new BigDecimal(min));
        if (max != null) entry.setMaxCredits(new BigDecimal(max));
        return Optional.of(entry);
    }

    @Test
    @DisplayName("no factor charges exactly what it charged before modifiers existed")
    void nullFactorChangesNothing() {
        Optional<PricingVersionEntry> entry = row("second", "0", "100", null, null);
        BigDecimal withoutFactor = policy.resolveEffectivePrice(version("0"), entry, new BigDecimal("10"));
        BigDecimal withNull = policy.resolveEffectivePrice(
                version("0"), entry, new BigDecimal("10"), null);

        assertThat(withNull).isEqualByComparingTo(withoutFactor).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("a factor of 1 is the same statement as no factor")
    void oneIsANoOp() {
        assertThat(policy.resolveEffectivePrice(version("0"), row("second", "0", "100", null, null),
                new BigDecimal("10"), BigDecimal.ONE)).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("the factor multiplies rate x quantity, so 10s at 100 with x2 is 2000")
    void factorMultipliesTheResolvedAmount() {
        assertThat(policy.resolveEffectivePrice(version("0"), row("second", "0", "100", null, null),
                new BigDecimal("10"), new BigDecimal("2"))).isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("the fixed component is multiplied too, because the whole call is bigger")
    void baseIsMultiplied() {
        assertThat(policy.resolveEffectivePrice(version("0"), row("second", "50", "100", null, null),
                new BigDecimal("10"), new BigDecimal("2"))).isEqualByComparingTo("2100");
    }

    @Test
    @DisplayName("a FLAT per-call price is modulated, which scaling a quantity could never do")
    void flatPriceIsModulated() {
        // Its billable quantity is 1 and its unit rate is zero: a factor folded
        // into the quantity would silently do nothing here.
        assertThat(policy.resolveEffectivePrice(version("0"), row("call", "4800", "0", null, null),
                null, new BigDecimal("2"))).isEqualByComparingTo("9600");
    }

    @Test
    @DisplayName("the published ceiling still binds after the factor")
    void maxClampsAfterTheFactor() {
        // A factor is a reason to charge more, not a licence to pass the
        // ceiling the platform owner put on one call.
        assertThat(policy.resolveEffectivePrice(version("0"), row("second", "0", "100", null, "1500"),
                new BigDecimal("10"), new BigDecimal("4"))).isEqualByComparingTo("1500");
    }

    @Test
    @DisplayName("the published floor still binds after the factor")
    void minClampsAfterTheFactor() {
        assertThat(policy.resolveEffectivePrice(version("0"), row("second", "0", "1", "900", null),
                new BigDecimal("10"), new BigDecimal("2"))).isEqualByComparingTo("900");
    }

    @Test
    @DisplayName("a zero or negative factor is read as NO factor, never as a free generation")
    void absurdFactorsDoNotZeroTheCharge() {
        Optional<PricingVersionEntry> entry = row("second", "0", "100", null, null);
        assertThat(policy.resolveEffectivePrice(version("0"), entry, new BigDecimal("10"),
                BigDecimal.ZERO)).isEqualByComparingTo("1000");
        assertThat(policy.resolveEffectivePrice(version("0"), entry, new BigDecimal("10"),
                new BigDecimal("-3"))).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("a per-minute row still converts the seconds, and the factor rides the result")
    void aPerMinuteRowConvertsAndThenTakesTheFactor() {
        // 60 seconds is one minute; one minute at 480 with a x2 factor is 960.
        //
        // This was called "conversion happens BEFORE the factor" and claimed to pin that order.
        // It does not, and no test can: `PriceUnit.quantityOf` divides exactly (HALF_UP at 12
        // digits, no ceiling), so conversion is linear and scaling the quantity by 2 reaches the
        // identical 960. The comment even named the discriminating case, "the moment a clamp is
        // involved", and then did not write one - and a clamp does not discriminate either, since
        // it applies after both.
        //
        // What IS observable about the order is pinned two tests up, by
        // `maxClampsAfterTheFactor`: the factor multiplies the price and the clamps bind
        // AFTER it, so a surcharge can never carry an amount past the ceiling its owner published.
        // What this test is worth on its own is the conversion itself: a per-minute row must not be
        // handed a count of seconds, which bills 60x, and the factor must not disturb that.
        assertThat(policy.resolveEffectivePrice(version("0"), row("minute", "0", "480", null, null),
                new BigDecimal("60"), new BigDecimal("2"))).isEqualByComparingTo("960");
        // The same row with no factor, so the conversion is asserted independently of it.
        assertThat(policy.resolveEffectivePrice(version("0"), row("minute", "0", "480", null, null),
                new BigDecimal("60"), null)).isEqualByComparingTo("480");
    }

    @Test
    @DisplayName("a call with no measurement bills one unit, then the factor")
    void unmeasuredCallStillTakesTheFactor() {
        // A missing measurement bills ONE unit rather than nothing; the factor
        // is a statement about WHAT was asked for, not about how big it was.
        assertThat(policy.resolveEffectivePrice(version("0"), row("second", "0", "100", null, null),
                null, new BigDecimal("2"))).isEqualByComparingTo("200");
    }

    @Test
    @DisplayName("the credential-wide default takes no factor: a generation is never sold on it")
    void versionDefaultIsUntouched() {
        // The billing path refuses a generation that resolved out of the
        // catch-all, so there is nothing here for a factor to apply to.
        assertThat(policy.resolveEffectivePrice(version("12"), Optional.empty(),
                new BigDecimal("10"), new BigDecimal("4"))).isEqualByComparingTo("12");
    }
}
