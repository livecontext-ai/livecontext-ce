package com.apimarketplace.auth.credential.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Per-endpoint markup override attached to a pricing version.
 * Absence of an entry for a tool means "use version.defaultMarkupCredits".
 */
@Entity
@Table(
        schema = "auth",
        name = "pricing_version_entry",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pve_version_tool",
                columnNames = {"pricing_version_id", "api_tool_id"})
)
public class PricingVersionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pricing_version_id", nullable = false)
    private Long pricingVersionId;

    @Column(name = "api_tool_id", nullable = false)
    private UUID apiToolId;

    @Column(name = "markup_credits", nullable = false, precision = 10, scale = 6)
    private BigDecimal markupCredits;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPricingVersionId() { return pricingVersionId; }
    public void setPricingVersionId(Long pricingVersionId) { this.pricingVersionId = pricingVersionId; }
    public UUID getApiToolId() { return apiToolId; }
    public void setApiToolId(UUID apiToolId) { this.apiToolId = apiToolId; }
    public BigDecimal getMarkupCredits() { return markupCredits; }
    public void setMarkupCredits(BigDecimal markupCredits) { this.markupCredits = markupCredits; }
}
