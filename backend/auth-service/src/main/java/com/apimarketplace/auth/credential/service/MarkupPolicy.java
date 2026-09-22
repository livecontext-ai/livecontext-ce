package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType;
import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.domain.PriceUnit;
import com.apimarketplace.auth.credential.domain.PricingVersionEntry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Central enforcement point for markup business rules.
 * Kept as a small, pure component so the same rules apply whether markup is
 * being written (pricing-version publish) or read (per-call resolve). The DB
 * CHECK constraints enforce the same invariants as a last line of defense;
 * this class exists so the service layer can fail fast with a clear error
 * instead of bubbling up an opaque constraint-violation SQLException.
 */
@Component
public class MarkupPolicy {

    /**
     * Reject configurations that cannot legally exist.
     *
     * <p>OAuth2 credentials never carry markup - they represent a delegated user
     * auth flow where billing the platform owner for the user's own OAuth quota
     * makes no sense. Negative markup / negative call limits are obviously
     * nonsense and are blocked here before they reach the DB.
     *
     * <p>A null {@code markup} is allowed and means "no API-wide default -
     * per-tool overrides are the sole source of pricing". Callers that pass
     * null must still ensure at least one per-tool override exists before
     * publishing; this policy does not see the overrides, so that invariant
     * is enforced one level up in the pricing service.
     */
    public void validateMarkupConfig(AuthType authType, BigDecimal markup, int maxCallsPerRun) {
        if (markup != null && markup.signum() < 0) {
            throw new IllegalArgumentException(
                    "defaultMarkupCredits must be >= 0 (got " + markup + ")");
        }
        if (maxCallsPerRun < 0) {
            throw new IllegalArgumentException(
                    "maxCallsPerRun must be >= 0 (got " + maxCallsPerRun + ")");
        }
        if (authType == AuthType.OAUTH2
                && ((markup != null && markup.signum() != 0) || maxCallsPerRun != 0)) {
            throw new IllegalArgumentException(
                    "OAuth2 credentials cannot carry markup (markup=" + markup
                            + ", maxCallsPerRun=" + maxCallsPerRun + ")");
        }
    }

    /**
     * Refuse a republish that would price a row in a unit nothing measures for
     * it.
     *
     * <p>A caller measures a call ONCE, in the platform unit of the dimension
     * the row was seeded with: a video endpoint reports seconds, a speech
     * endpoint reports characters, a flat endpoint reports nothing at all. The
     * published unit is what that measurement is converted into, so it may move
     * freely WITHIN the dimension (per-second to per-minute is the same
     * measurement at another scale, and {@link #billableQuantity} converts it),
     * and it may always collapse to {@code call}, which reads no measurement.
     *
     * <p>What it may not do is cross to another dimension. Republishing a
     * per-call price as per-second does not make anything start measuring
     * seconds: the caller keeps reporting one call, and every request would bill
     * a single second, which is a silent undercharge on every call rather than a
     * visible failure. The mirror case (a per-second row republished per
     * character) bills a count of seconds at a per-character rate. Neither has a
     * defensible amount to charge, so the publish is refused instead, with the
     * two ways out named in the message.
     *
     * @param current  unit the row carries in the version being replaced
     * @param incoming unit the publish is asking for
     * @param where    endpoint (and model) quoted in the error message
     */
    public void validateUnitChange(PriceUnit current, PriceUnit incoming, String where) {
        if (current == null || incoming == null) {
            return;
        }
        if (incoming.canPriceMeasurementFor(current)) {
            return;
        }
        String reason = current == PriceUnit.CALL
                ? "this price is per call, so nothing measures " + incoming.dimension().measurementNoun()
                        + " for it and every request would be billed a single " + incoming.wire()
                        + ". Keep the flat 'call' price."
                : "this price is per " + current.wire() + ", so a call here is measured in "
                        + current.dimension().measurementNoun() + " and nothing would measure "
                        + incoming.dimension().measurementNoun() + " for it. Publish a rate per "
                        + current.wire() + ", or a flat 'call' price.";
        throw new IllegalArgumentException(
                "cannot publish " + where + " per " + incoming.wire() + ": " + reason);
    }

    /**
     * Resolve the effective per-call markup for a given tool under a pricing version.
     *
     * <p>Per-tool override wins over the version-wide default. A null version
     * (credential not yet priced) yields {@link BigDecimal#ZERO} so billing
     * defaults to "no markup" rather than "fail closed" - the rate-cap / flag
     * gate upstream is the actual on/off switch.
     */
    public BigDecimal resolveEffectiveMarkup(PlatformCredentialPricingVersion version,
                                              Optional<PricingVersionEntry> perTool) {
        if (version == null) {
            return BigDecimal.ZERO;
        }
        if (perTool != null && perTool.isPresent()) {
            BigDecimal rate = perTool.get().getMarkupCredits();
            return rate != null ? rate : BigDecimal.ZERO;
        }
        BigDecimal def = version.getDefaultMarkupCredits();
        return def != null ? def : BigDecimal.ZERO;
    }

    /**
     * Price a call that arrives with NO quantity because its caller has no
     * notion of one.
     *
     * <p>Only the generation surface knows how big a call is (seconds of video,
     * characters of speech). Every other path through the platform hits an
     * endpoint expecting a flat per-call amount. Before V428 that was always
     * true, so those callers could read the base and be right.
     *
     * <p>V428 made it possible for an admin to attach a per-unit price to an
     * endpoint that some OTHER path also calls. Reading the base there would
     * charge the fixed part only, which for a pure per-second rate is ZERO: the
     * platform would hand out its own provider key for free, silently, which is
     * the exact failure this feature exists to prevent.
     *
     * <p>So a unit-priced row seen without a quantity bills ONE unit. That is
     * the smallest amount that is defensible rather than invented, it is never
     * zero, and the caller is told about it by {@link #isUnitPricedWithoutQuantity}
     * so the mismatch can be logged instead of passing unnoticed.
     *
     * <p>This is now the same rule {@link #resolveEffectivePrice} applies to a
     * null quantity, so the two entry points cannot drift. The method survives
     * as the name a caller uses to SAY it has no quantity, which reads better at
     * the call site than passing a bare null.
     */
    public BigDecimal resolveEffectivePriceWithoutQuantity(PlatformCredentialPricingVersion version,
                                                            Optional<PricingVersionEntry> perTool) {
        return resolveEffectivePrice(version, perTool, null);
    }

    /**
     * True when this row prices per unit but the caller could not say how many.
     * A caller in that position is billing an approximation and should say so
     * in its logs.
     */
    public boolean isUnitPricedWithoutQuantity(Optional<PricingVersionEntry> perTool) {
        return perTool != null && perTool.isPresent()
                && perTool.get().getUnitCredits() != null
                && perTool.get().getUnitCredits().signum() > 0;
    }

    /**
     * How many PUBLISHED units a call is billed for, given the platform
     * measurement its caller took.
     *
     * <p>This is the single place the two halves of a price meet: the caller
     * measures a call in the platform's own unit (seconds, assets, characters)
     * and the published row says what it is charged per, so the conversion has
     * to happen where the row is read. Doing it in the caller instead is what
     * let a per-minute rate be multiplied by a count of seconds, billing 60x.
     *
     * <p>A NULL quantity and a ZERO quantity are different statements and must
     * not collapse into one another. ZERO means the caller measured the call and
     * it was empty, so only the base is due. NULL means the caller had no way to
     * measure it, which is every path that predates unit pricing. Reading NULL as
     * zero there charges the fixed part only, and for a pure per-second rate the
     * fixed part is nothing: the platform would hand out its own provider key
     * for free, silently, which is the single outcome this whole feature exists
     * to prevent. So NULL bills ONE unit: the smallest amount that is defensible
     * rather than invented, and never zero.
     *
     * <p>A negative measurement is treated as zero. Never bill a negative
     * amount, never guess a missing one.
     */
    public BigDecimal billableQuantity(Optional<PricingVersionEntry> perTool, BigDecimal quantity) {
        if (perTool == null || perTool.isEmpty()) {
            // No row means no unit, so there is nothing to convert into.
            return quantity;
        }
        if (quantity == null) {
            return BigDecimal.ONE;
        }
        if (quantity.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return perTool.get().unit().quantityOf(quantity);
    }

    /**
     * Resolve the effective price for one call, honouring V428 unit pricing.
     *
     * <p>Extends {@link #resolveEffectiveMarkup(PlatformCredentialPricingVersion, Optional)}
     * with the quantity dimension:
     * <pre>
     *   price = base + unitCredits x billableQuantity,  clamped to [minCredits, maxCredits]
     * </pre>
     *
     * <p>The two-arg overload stays the entry point for flat, per-call pricing
     * and simply delegates here with a quantity of 1. A row that has never been
     * given a unit is {@code CALL} + {@code unitCredits=0}, so it returns its
     * base unchanged - which is why no existing price changes on upgrade.
     *
     * <p>{@code quantity} is the PLATFORM measurement of the call (seconds,
     * assets, characters), supplied by the caller because only the call site
     * knows the actual parameters. It is converted into the row's published unit
     * by {@link #billableQuantity} before it multiplies anything, so the unit
     * that scales the quantity is always the unit that carries the rate.
     */
    public BigDecimal resolveEffectivePrice(PlatformCredentialPricingVersion version,
                                             Optional<PricingVersionEntry> perTool,
                                             BigDecimal quantity) {
        return resolveEffectivePrice(version, perTool, quantity, null);
    }

    /**
     * Resolve the effective price for one call, honouring both V428 unit
     * pricing and what the call's own CHOICES do to it.
     *
     * <pre>
     *   price = (base + unitCredits x billableQuantity) x multiplier,
     *           clamped to [minCredits, maxCredits]
     * </pre>
     *
     * <p><b>Why the factor multiplies the PRICE and not the quantity.</b> Two
     * reasons, and the second one is the reason. A quantity is reported back to
     * the customer as the size of what they bought, so scaling it would have a
     * ten second clip billed and reported as twenty seconds of video that no
     * player would show. And a flat {@code call} price has no quantity to
     * scale at all: its billable quantity is 1 by definition and its unit rate
     * is zero, so a factor folded in there would silently do nothing on exactly
     * the models that sell a resolution or a quality tier at one price.
     *
     * <p><b>Why the clamps apply after.</b> {@code maxCredits} is the ceiling
     * the platform owner put on what one call may cost. A factor is a reason to
     * charge more, not a licence to pass the ceiling, so it is applied first and
     * the clamp still binds.
     *
     * @param multiplier what the call's choices do to the published rate, or
     *                   null for a call at that rate. Null, 1, and any
     *                   non-positive value all leave the amount untouched: a
     *                   price of zero can only be published, never derived, so
     *                   a factor that would produce one is read as no factor at
     *                   all rather than as a free generation.
     */
    public BigDecimal resolveEffectivePrice(PlatformCredentialPricingVersion version,
                                             Optional<PricingVersionEntry> perTool,
                                             BigDecimal quantity,
                                             BigDecimal multiplier) {
        BigDecimal base = resolveEffectiveMarkup(version, perTool);
        if (perTool == null || perTool.isEmpty()) {
            // No per-tool row: the version default is a flat per-call amount by
            // definition (a version carries no unit), so there is nothing to
            // scale. It is also never a generation price - the generation path
            // refuses a call that resolved out of the credential-wide default -
            // so there is nothing here for a factor to apply to either.
            return base;
        }
        PricingVersionEntry entry = perTool.get();
        BigDecimal unitRate = entry.getUnitCredits();
        BigDecimal price = base;
        if (unitRate != null && unitRate.signum() > 0) {
            price = price.add(unitRate.multiply(billableQuantity(perTool, quantity)));
        }
        if (multiplier != null && multiplier.signum() > 0
                && multiplier.compareTo(BigDecimal.ONE) != 0) {
            price = price.multiply(multiplier);
        }
        BigDecimal min = entry.getMinCredits();
        if (min != null && price.compareTo(min) < 0) {
            price = min;
        }
        BigDecimal max = entry.getMaxCredits();
        if (max != null && price.compareTo(max) > 0) {
            price = max;
        }
        return price.signum() < 0 ? BigDecimal.ZERO : price;
    }

    /**
     * Projected markup cost for a budget pre-check.
     *
     * <p>Used to reserve budget up-front: {@code perCallMarkup × min(remainingCalls, maxPerRun)}.
     * {@code remainingCalls} is the caller's estimate of how many more debits will
     * happen in the run; we clamp by {@code maxPerRun} so a runaway loop cannot
     * be projected past the configured cap. Zero- or negative-input cases
     * degrade to {@link BigDecimal#ZERO}.
     */
    public BigDecimal projectedMarkup(BigDecimal perCallMarkup, int remainingCalls, int maxPerRun) {
        if (perCallMarkup == null || perCallMarkup.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (remainingCalls <= 0 || maxPerRun <= 0) {
            return BigDecimal.ZERO;
        }
        int effective = Math.min(remainingCalls, maxPerRun);
        return perCallMarkup.multiply(BigDecimal.valueOf(effective));
    }
}
