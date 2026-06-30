package com.apimarketplace.credential.client.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response from {@code GET /api/internal/credentials/pricing/{credentialId}/latest} -
 * the newest pricing snapshot for a platform credential. Read at run-init
 * to decide which version to pin; {@code found=false} means the credential
 * has no published pricing (markup is off for this credential).
 */
public class PricingVersionDto {

    private boolean found;
    private Long pricingVersionId;
    private Long credentialId;
    private Integer version;
    private BigDecimal defaultMarkupCredits;
    private Instant createdAt;

    public PricingVersionDto() {
    }

    public boolean isFound() { return found; }
    public void setFound(boolean found) { this.found = found; }
    public Long getPricingVersionId() { return pricingVersionId; }
    public void setPricingVersionId(Long pricingVersionId) { this.pricingVersionId = pricingVersionId; }
    public Long getCredentialId() { return credentialId; }
    public void setCredentialId(Long credentialId) { this.credentialId = credentialId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public BigDecimal getDefaultMarkupCredits() { return defaultMarkupCredits; }
    public void setDefaultMarkupCredits(BigDecimal defaultMarkupCredits) { this.defaultMarkupCredits = defaultMarkupCredits; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
