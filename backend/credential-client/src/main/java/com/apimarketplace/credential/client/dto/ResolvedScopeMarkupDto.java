package com.apimarketplace.credential.client.dto;

import java.math.BigDecimal;

/**
 * V148+ unified markup-rate response for the scope-aware lookup.
 *
 * <p>Returned by {@code GET /api/internal/credentials/markup/scope-rate}.
 * The catalog's {@code CatalogToolBillingService} reads this verbatim and
 * uses {@link #pinId} as the {@code credit_ledger.pin_id} FK on the reserve
 * row, and {@link #effectiveMarkup} as the per-call charge.
 *
 * <p>{@link #isFound()} false signals "no published pricing version for this
 * credential" - caller fail-closes and refuses the upstream call.
 *
 * <p><b>V428 - unit pricing.</b> {@link #getEffectiveMarkup()} keeps its exact
 * meaning: the amount to charge for THIS call, already resolved. When the
 * caller supplied a quantity, that resolution is
 * {@code base + unitCredits x quantity} clamped to the published bounds;
 * otherwise it is the flat amount it has always been. Existing callers
 * therefore need no change.
 *
 * <p>The component fields alongside it exist so a surface can EXPLAIN the
 * price ("60 credits per second") rather than only state it, which is what the
 * builder inspector and the tool's discovery payload show.
 */
public class ResolvedScopeMarkupDto {

    private boolean found;
    private Long pinId;
    private Long pricingVersionId;
    private BigDecimal effectiveMarkup;

    /** Unit the variable component is charged per: call, second, minute, image, character. */
    private String priceUnit;
    /** Fixed component of the published price. */
    private BigDecimal baseCredits;
    /** Credits per unit. Zero for a flat price. */
    private BigDecimal unitCredits;
    /** Published floor, or null when unbounded. */
    private BigDecimal minCredits;
    /** Published ceiling, or null when unbounded. */
    private BigDecimal maxCredits;
    /**
     * How many {@link #priceUnit} units were charged, so a quote can be
     * explained ("480 credits per minute, 1 minute"). This is the CONVERTED
     * quantity, not the platform measurement the caller sent: a 60 second call
     * on a per-minute row reports 1. Reporting the measurement next to the
     * row's own unit would contradict the rate printed beside it.
     */
    private BigDecimal quantity;

    /**
     * What the CHOICES in this call did to the published rate, echoed so the
     * amount can be explained: a total that is not {@code rate x quantity} has
     * no visible reason without it, and the first thing a reader suspects about
     * an unexplained total is one of the two numbers printed beside it.
     *
     * <p>Null for a call at the published rate, which is every ordinary tool
     * and every generation whose model declares no modifiers.
     */
    private BigDecimal priceMultiplier;

    /**
     * True when this amount came from a price published for THIS endpoint,
     * false when it fell back to the credential-wide default.
     *
     * <p>The distinction is only interesting to a caller that must not sell
     * something the owner never priced deliberately. A credential-wide default
     * is authored for the ordinary endpoints of an API, where every call costs
     * about the same; a generation on the same key can cost the owner dollars
     * of provider money per call, and inheriting the catch-all would sell it at
     * the price of a weather lookup. So the generation surface refuses on this
     * flag rather than charging it, and every other caller ignores it and keeps
     * the amount, which is what the default is for.
     *
     * <p>The five component fields above are absent in the same case (there is
     * no row to describe them). Reading their absence would work today and
     * would silently stop working the day one of them gains a default, so this
     * says it outright instead.
     *
     * <p>NULL is a third answer and is not the same as false: it means the
     * responding service is older than this field and did not answer at all.
     * Reading that as "the default applied" would refuse every resold
     * generation for as long as a rolling deploy has one old peer left, which
     * trades a latent mispricing for an outage. So null keeps the behaviour
     * that shipped before the flag existed, and only an explicit false refuses.
     */
    private Boolean pricedByPublishedRow;

    public ResolvedScopeMarkupDto() {}

    public boolean isFound() { return found; }
    public void setFound(boolean found) { this.found = found; }

    public Long getPinId() { return pinId; }
    public void setPinId(Long pinId) { this.pinId = pinId; }

    public Long getPricingVersionId() { return pricingVersionId; }
    public void setPricingVersionId(Long pricingVersionId) { this.pricingVersionId = pricingVersionId; }

    public BigDecimal getEffectiveMarkup() { return effectiveMarkup; }
    public void setEffectiveMarkup(BigDecimal effectiveMarkup) { this.effectiveMarkup = effectiveMarkup; }

    public String getPriceUnit() { return priceUnit; }
    public void setPriceUnit(String priceUnit) { this.priceUnit = priceUnit; }

    public BigDecimal getBaseCredits() { return baseCredits; }
    public void setBaseCredits(BigDecimal baseCredits) { this.baseCredits = baseCredits; }

    public BigDecimal getUnitCredits() { return unitCredits; }
    public void setUnitCredits(BigDecimal unitCredits) { this.unitCredits = unitCredits; }

    public BigDecimal getMinCredits() { return minCredits; }
    public void setMinCredits(BigDecimal minCredits) { this.minCredits = minCredits; }

    public BigDecimal getMaxCredits() { return maxCredits; }
    public void setMaxCredits(BigDecimal maxCredits) { this.maxCredits = maxCredits; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public BigDecimal getPriceMultiplier() { return priceMultiplier; }
    public void setPriceMultiplier(BigDecimal priceMultiplier) { this.priceMultiplier = priceMultiplier; }

    public Boolean getPricedByPublishedRow() { return pricedByPublishedRow; }
    public void setPricedByPublishedRow(Boolean pricedByPublishedRow) { this.pricedByPublishedRow = pricedByPublishedRow; }

    /**
     * True only when the responder said outright that this amount came from the
     * credential-wide default. A missing answer is not that statement.
     */
    public boolean isKnownToBeVersionDefault() { return Boolean.FALSE.equals(pricedByPublishedRow); }

    /**
     * Whether this published rate can price a call measured in {@code
     * measuredUnit}, in the PLATFORM's own units.
     *
     * @see PriceDimensions for the rule, why it is restated in this JAR rather
     *      than imported from the enum that owns it, and why it answers TRUE
     *      whenever it cannot tell
     */
    public boolean canPriceMeasurementIn(String measuredUnit) {
        return PriceDimensions.canPriceMeasurementIn(priceUnit, measuredUnit);
    }

    /**
     * Human-readable rendering of the published rate, e.g.
     * {@code "60 credits per second"} or {@code "12 credits"}. Used by the
     * builder inspector and the tool discovery payload so a price is always
     * shown with the reason it is what it is.
     */
    public String describeRate() {
        BigDecimal per = unitCredits;
        if (per != null && per.signum() > 0 && priceUnit != null && !"call".equals(priceUnit)) {
            String rate = per.stripTrailingZeros().toPlainString() + " credits per " + priceUnit;
            if (baseCredits != null && baseCredits.signum() > 0) {
                return baseCredits.stripTrailingZeros().toPlainString() + " credits + " + rate;
            }
            return rate;
        }
        BigDecimal flat = effectiveMarkup != null ? effectiveMarkup : baseCredits;
        return flat == null ? "no published price" : flat.stripTrailingZeros().toPlainString() + " credits";
    }
}
