package com.apimarketplace.publication.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PublicationReceiptDto {
    private String tenantId;
    private UUID publicationId;
    private int creditsPaid;
    private Instant acquiredAt;

    public PublicationReceiptDto() {
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getPublicationId() {
        return publicationId;
    }

    public void setPublicationId(UUID publicationId) {
        this.publicationId = publicationId;
    }

    public int getCreditsPaid() {
        return creditsPaid;
    }

    public void setCreditsPaid(int creditsPaid) {
        this.creditsPaid = creditsPaid;
    }

    public Instant getAcquiredAt() {
        return acquiredAt;
    }

    public void setAcquiredAt(Instant acquiredAt) {
        this.acquiredAt = acquiredAt;
    }
}
