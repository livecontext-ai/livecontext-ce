package com.apimarketplace.agent.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Row-level import error surfaced by a CE instance after trying to apply a
 * bundle. Written by CE (PR3), read here by support tooling to triage
 * integration issues across deployments.
 */
@Entity
@Table(name = "catalog_import_errors")
public class CatalogImportErrorEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bundle_id")
    private Long bundleId;

    @Column(name = "provider", length = 50)
    private String provider;

    @Column(name = "model_id", length = 150)
    private String modelId;

    @Column(name = "error_code", nullable = false, length = 50)
    private String errorCode;

    @Column(name = "error_detail", columnDefinition = "text")
    private String errorDetail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @PrePersist
    protected void onCreate() {
        if (occurredAt == null) occurredAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getBundleId() { return bundleId; }
    public void setBundleId(Long bundleId) { this.bundleId = bundleId; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
}
