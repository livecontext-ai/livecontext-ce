package com.apimarketplace.credential.client.dto;

import java.math.BigDecimal;

/**
 * Response from {@code GET /api/internal/credentials/resolve-markup} -
 * the per-call markup rate for a pinned pricing version + api tool pair.
 * Consumed on every platform-sourced MCP debit in the orchestrator.
 *
 * <p>{@code found=false} means the pricing version id was unknown (stale
 * pin or admin-cancelled pricing); callers must skip markup billing.
 */
public class FrozenMarkupDto {

    private boolean found;
    private Long pricingVersionId;
    private Long credentialId;
    private Integer version;
    private BigDecimal effectiveMarkup;

    public FrozenMarkupDto() {
    }

    public boolean isFound() { return found; }
    public void setFound(boolean found) { this.found = found; }
    public Long getPricingVersionId() { return pricingVersionId; }
    public void setPricingVersionId(Long pricingVersionId) { this.pricingVersionId = pricingVersionId; }
    public Long getCredentialId() { return credentialId; }
    public void setCredentialId(Long credentialId) { this.credentialId = credentialId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public BigDecimal getEffectiveMarkup() { return effectiveMarkup; }
    public void setEffectiveMarkup(BigDecimal effectiveMarkup) { this.effectiveMarkup = effectiveMarkup; }
}
