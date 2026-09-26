package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * An email domain a workspace claims for SAML SSO. It routes logins and admits SAML users
 * only once {@link #getVerifiedAt()} is set, which happens when the DNS TXT record carrying
 * {@link #getVerificationToken()} is found.
 */
@Entity
@Table(name = "organization_sso_domain")
public class OrganizationSsoDomain {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 253)
    private String domain;

    @Column(name = "verification_token", nullable = false, length = 64)
    private String verificationToken;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public OrganizationSsoDomain() {
    }

    public OrganizationSsoDomain(Organization organization, String domain, String verificationToken) {
        this.organization = organization;
        this.domain = domain;
        this.verificationToken = verificationToken;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Organization getOrganization() { return organization; }
    public void setOrganization(Organization organization) { this.organization = organization; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getVerificationToken() { return verificationToken; }
    public void setVerificationToken(String verificationToken) { this.verificationToken = verificationToken; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(Instant lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
