package com.apimarketplace.auth.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Organization-owned SAML identity-provider configuration.
 *
 * <p>The row is the auth-service source of truth. Keycloak is provisioned from
 * this data and can be rebuilt from it if the realm drifts.
 */
@Entity
@Table(name = "organization_saml_connection")
public class OrganizationSamlConnection {

    public enum Status {
        DRAFT,
        ACTIVE,
        ERROR,
        DISABLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false, unique = true)
    private Organization organization;

    @Column(name = "idp_alias", nullable = false, unique = true, length = 120)
    private String idpAlias;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(name = "idp_entity_id", nullable = false, length = 512)
    private String idpEntityId;

    @Column(name = "sso_url", nullable = false, length = 2048)
    private String ssoUrl;

    @Column(name = "x509_certificate", nullable = false, columnDefinition = "text")
    private String x509Certificate;

    @Column(name = "hide_on_login_page", nullable = false)
    private boolean hideOnLoginPage = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.DRAFT;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public OrganizationSamlConnection() {
    }

    public OrganizationSamlConnection(Organization organization, String idpAlias) {
        this.organization = organization;
        this.idpAlias = idpAlias;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Organization getOrganization() {
        return organization;
    }

    public void setOrganization(Organization organization) {
        this.organization = organization;
    }

    public String getIdpAlias() {
        return idpAlias;
    }

    public void setIdpAlias(String idpAlias) {
        this.idpAlias = idpAlias;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getIdpEntityId() {
        return idpEntityId;
    }

    public void setIdpEntityId(String idpEntityId) {
        this.idpEntityId = idpEntityId;
    }

    public String getSsoUrl() {
        return ssoUrl;
    }

    public void setSsoUrl(String ssoUrl) {
        this.ssoUrl = ssoUrl;
    }

    public String getX509Certificate() {
        return x509Certificate;
    }

    public void setX509Certificate(String x509Certificate) {
        this.x509Certificate = x509Certificate;
    }

    public boolean isHideOnLoginPage() {
        return hideOnLoginPage;
    }

    public void setHideOnLoginPage(boolean hideOnLoginPage) {
        this.hideOnLoginPage = hideOnLoginPage;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(Instant lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
