package com.apimarketplace.auth.credential.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row per immutable pricing snapshot for a platform credential.
 * Created by {@code PlatformCredentialPricingService} under an advisory lock so
 * version numbers are monotonically increasing with no gaps per credential.
 * Never updated after insert - a rate change produces a new version row.
 */
@Entity
@Table(
        schema = "auth",
        name = "platform_credential_pricing_version",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pcpv_cred_version",
                columnNames = {"platform_credential_id", "version"})
)
public class PlatformCredentialPricingVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "platform_credential_id", nullable = false)
    private Long platformCredentialId;

    @Column(nullable = false)
    private Integer version;

    /**
     * API-wide default per-call markup. Nullable: a null default means
     * "no API-wide rate - only per-tool overrides apply". Tools without an
     * override then bill zero markup.
     */
    @Column(name = "default_markup_credits", precision = 10, scale = 6)
    private BigDecimal defaultMarkupCredits;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPlatformCredentialId() { return platformCredentialId; }
    public void setPlatformCredentialId(Long platformCredentialId) { this.platformCredentialId = platformCredentialId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public BigDecimal getDefaultMarkupCredits() { return defaultMarkupCredits; }
    public void setDefaultMarkupCredits(BigDecimal defaultMarkupCredits) { this.defaultMarkupCredits = defaultMarkupCredits; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
