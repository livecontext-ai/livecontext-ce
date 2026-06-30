package com.apimarketplace.publication.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Stores the link between a CE instance admin and their cloud (EE) account.
 * The encrypted refresh token is used server-to-server for paid marketplace operations.
 * Only one cloud link per CE instance (unique on tenantId).
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "ce_cloud_links")
public class CeCloudLinkEntity implements OrgScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, unique = true)
    private Long tenantId;

    @Column(name = "organization_id")
    private String organizationId;

    @Column(name = "cloud_user_id", nullable = false)
    private String cloudUserId;

    @Column(name = "cloud_username", nullable = false)
    private String cloudUsername;

    @Column(name = "encrypted_refresh_token", nullable = false, length = 4096)
    private String encryptedRefreshToken;

    @Column(name = "cached_access_token", length = 4096)
    private String cachedAccessToken;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /**
     * Stable install identifier for cloud-link Phase 1 (doc CLOUD_LINK_ONBOARDING.md).
     * V259 added the column with DEFAULT gen_random_uuid() + UNIQUE, so every existing
     * row already has a value; new rows can leave it null at construction and rely on
     * the DB default. {@code @PrePersist} fills it explicitly so the in-memory entity
     * matches what the DB will store.
     */
    @Column(name = "install_id", nullable = false, unique = true, updatable = false)
    private UUID installId;

    /**
     * Set once the CE side successfully POSTs to cloud {@code /api/ce-link/register}
     * (CloudLinkService.registerWithCloud). Null = still pending or rejected - the
     * heartbeat scheduler skips rows where this is null.
     */
    @Column(name = "registered_at")
    private Instant registeredAt;

    /** Display name shown on cloud /app/settings/cloud-account list (V259 column, nullable). */
    @Column(name = "label", length = 128)
    private String label;

    /**
     * CE runtime source for API LLM providers. BYOK keeps local keys; CLOUD relays
     * only the completion call to the linked LiveContext account.
     */
    @Column(name = "llm_source", nullable = false, length = 16)
    private String llmSource = "BYOK";

    public CeCloudLinkEntity() {}

    @PrePersist
    private void onCreate() {
        if (this.linkedAt == null) this.linkedAt = Instant.now();
        if (this.installId == null) this.installId = UUID.randomUUID();
    }

    // Getters and setters

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getCloudUserId() { return cloudUserId; }
    public void setCloudUserId(String cloudUserId) { this.cloudUserId = cloudUserId; }

    public String getCloudUsername() { return cloudUsername; }
    public void setCloudUsername(String cloudUsername) { this.cloudUsername = cloudUsername; }

    public String getEncryptedRefreshToken() { return encryptedRefreshToken; }
    public void setEncryptedRefreshToken(String encryptedRefreshToken) { this.encryptedRefreshToken = encryptedRefreshToken; }

    public String getCachedAccessToken() { return cachedAccessToken; }
    public void setCachedAccessToken(String cachedAccessToken) { this.cachedAccessToken = cachedAccessToken; }

    public Instant getTokenExpiresAt() { return tokenExpiresAt; }
    public void setTokenExpiresAt(Instant tokenExpiresAt) { this.tokenExpiresAt = tokenExpiresAt; }

    public Instant getLinkedAt() { return linkedAt; }
    public void setLinkedAt(Instant linkedAt) { this.linkedAt = linkedAt; }

    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public UUID getInstallId() { return installId; }
    public void setInstallId(UUID installId) { this.installId = installId; }

    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getLlmSource() { return llmSource; }
    public void setLlmSource(String llmSource) { this.llmSource = llmSource; }
}
