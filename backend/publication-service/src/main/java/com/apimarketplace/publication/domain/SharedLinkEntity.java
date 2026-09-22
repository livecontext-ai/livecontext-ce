package com.apimarketplace.publication.domain;

import com.apimarketplace.common.security.token.EncryptedTokenConverter;
import com.apimarketplace.common.security.token.HashedTokenEntity;
import com.apimarketplace.common.security.token.HashedTokenListener;
import com.apimarketplace.common.security.token.TokenSlot;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@EntityListeners({OrgScopedEntityListener.class, HashedTokenListener.class})
@Table(name = "shared_links")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class SharedLinkEntity implements OrgScopedEntity, HashedTokenEntity {

    @Id
    private UUID id;

    /**
     * The public share-link token (sl_...).
     * Stored encrypted (ENC:...) through {@link EncryptedTokenConverter}; the entity always holds
     * the plaintext. Lookups go through {@link #getTokenHash()}, never through this column.
     */
    @Convert(converter = EncryptedTokenConverter.class)
    @Column(name = "token", nullable = false, unique = true, length = 255)
    private String token;

    /** HMAC-SHA256 of the plaintext, filled by {@link HashedTokenListener}; the only lookup key. */
    @Column(name = "token_hash", length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 30)
    private ResourceType resourceType;

    /**
     * The token of the underlying resource (a chat/form endpoint token), itself a secret.
     * Stored encrypted (ENC:...) through {@link EncryptedTokenConverter}; the entity always holds
     * the plaintext. Lookups go through {@link #getResourceTokenHash()}, never through this column.
     */
    @Convert(converter = EncryptedTokenConverter.class)
    @Column(name = "resource_token", nullable = false, length = 255)
    private String resourceToken;

    /** HMAC-SHA256 of the plaintext, filled by {@link HashedTokenListener}; the only lookup key. */
    @Column(name = "resource_token_hash", length = 64)
    private String resourceTokenHash;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Column(name = "organization_id")
    private String organizationId;

    @Column(name = "title", length = 256)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "access_config", columnDefinition = "jsonb")
    private Map<String, Object> accessConfig;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "access_count", nullable = false)
    private long accessCount = 0;

    @Column(name = "last_accessed")
    private Instant lastAccessed;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public enum ResourceType {
        CHAT, FORM, CONVERSATION, APPLICATION
    }

    @PrePersist
    private void ensureIdentifiers() {
        if (this.id == null) this.id = UUID.randomUUID();
        if (this.createdAt == null) this.createdAt = Instant.now();
        if (this.updatedAt == null) this.updatedAt = this.createdAt;
    }

    @PreUpdate
    private void updateTimestamp() {
        this.updatedAt = Instant.now();
    }

    // Getters and setters

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public ResourceType getResourceType() { return resourceType; }
    public void setResourceType(ResourceType resourceType) { this.resourceType = resourceType; }

    public String getResourceToken() { return resourceToken; }
    public void setResourceToken(String resourceToken) { this.resourceToken = resourceToken; }

    public UUID getResourceId() { return resourceId; }
    public void setResourceId(UUID resourceId) { this.resourceId = resourceId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    @JsonProperty("isActive")
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }

    public Map<String, Object> getAccessConfig() { return accessConfig; }
    public void setAccessConfig(Map<String, Object> accessConfig) { this.accessConfig = accessConfig; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public long getAccessCount() { return accessCount; }
    public void setAccessCount(long accessCount) { this.accessCount = accessCount; }

    public Instant getLastAccessed() { return lastAccessed; }
    public void setLastAccessed(Instant lastAccessed) { this.lastAccessed = lastAccessed; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @JsonIgnore
    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    @JsonIgnore
    public String getResourceTokenHash() {
        return resourceTokenHash;
    }

    public void setResourceTokenHash(String resourceTokenHash) {
        this.resourceTokenHash = resourceTokenHash;
    }

    @Override
    @JsonIgnore
    public List<TokenSlot> tokenSlots() {
        return List.of(new TokenSlot(this::getToken, this::setTokenHash), new TokenSlot(this::getResourceToken, this::setResourceTokenHash));
    }
}
