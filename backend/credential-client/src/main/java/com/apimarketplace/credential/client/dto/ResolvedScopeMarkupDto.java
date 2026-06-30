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
 */
public class ResolvedScopeMarkupDto {

    private boolean found;
    private Long pinId;
    private Long pricingVersionId;
    private BigDecimal effectiveMarkup;

    public ResolvedScopeMarkupDto() {}

    public boolean isFound() { return found; }
    public void setFound(boolean found) { this.found = found; }

    public Long getPinId() { return pinId; }
    public void setPinId(Long pinId) { this.pinId = pinId; }

    public Long getPricingVersionId() { return pricingVersionId; }
    public void setPricingVersionId(Long pricingVersionId) { this.pricingVersionId = pricingVersionId; }

    public BigDecimal getEffectiveMarkup() { return effectiveMarkup; }
    public void setEffectiveMarkup(BigDecimal effectiveMarkup) { this.effectiveMarkup = effectiveMarkup; }
}
