package com.apimarketplace.publication.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "publication_receipts")
public class PublicationReceiptEntity implements OrgScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "publication_id", nullable = false)
    private UUID publicationId;

    @Column(name = "organization_id")
    private String organizationId;

    @Column(name = "credits_paid", nullable = false)
    private int creditsPaid;

    @Column(name = "acquired_at", nullable = false, updatable = false)
    private Instant acquiredAt;

    @Column(name = "remote_acquisition", nullable = false)
    private boolean remoteAcquisition = false;

    public PublicationReceiptEntity() {}

    public PublicationReceiptEntity(String tenantId, UUID publicationId, int creditsPaid) {
        this(tenantId, publicationId, creditsPaid, null);
    }

    public PublicationReceiptEntity(String tenantId, UUID publicationId, int creditsPaid, String organizationId) {
        this.tenantId = tenantId;
        this.publicationId = publicationId;
        this.creditsPaid = creditsPaid;
        this.organizationId = organizationId;
        this.acquiredAt = Instant.now();
    }

    @PrePersist
    private void onCreate() {
        if (this.acquiredAt == null) this.acquiredAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
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

    public boolean isRemoteAcquisition() {
        return remoteAcquisition;
    }

    public void setRemoteAcquisition(boolean remoteAcquisition) {
        this.remoteAcquisition = remoteAcquisition;
    }
}
