package com.apimarketplace.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * One row per (user, workspace, resourceType, resourceId) the user has favorited
 * from their own library. Drives the per-list favorites star and the
 * "favorites float to the top" ordering on {@code /app/workflow},
 * {@code /app/tables}, {@code /app/interface} and {@code /app/agent}. Backed by
 * the V361 migration ({@code orchestrator.user_resource_favorites}).
 *
 * <p>The native counterpart to publication-service's
 * {@code UserPublicationFavoriteEntity} (V359), which favorites marketplace
 * publications. This one favorites native resources, so it is keyed by an opaque
 * {@code resource_type} + {@code resource_id} string (no foreign key - the ids
 * live in four different service schemas).
 *
 * <p>{@code organizationId} is the active workspace and is {@code ""} (never
 * null) for the personal workspace, so the composite key stays well-defined.
 * This entity deliberately does NOT implement {@code OrgScopedEntity}: the org is
 * an explicit, normalized component of the primary key (mirroring V359), not an
 * auto-injected tenant column.
 */
@Entity
@Table(name = "user_resource_favorites", schema = "orchestrator")
@IdClass(ResourceFavoriteEntity.PK.class)
public class ResourceFavoriteEntity {

    @Id
    @Column(name = "user_id", nullable = false, length = 255)
    private String userId;

    @Id
    @Column(name = "organization_id", nullable = false, length = 255)
    private String organizationId;

    @Id
    @Column(name = "resource_type", nullable = false, length = 32)
    private String resourceType;

    @Id
    @Column(name = "resource_id", nullable = false, length = 255)
    private String resourceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ResourceFavoriteEntity() {
    }

    public ResourceFavoriteEntity(String userId, String organizationId, String resourceType, String resourceId) {
        this.userId = userId;
        this.organizationId = organizationId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.createdAt = Instant.now();
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public static class PK implements Serializable {
        private String userId;
        private String organizationId;
        private String resourceType;
        private String resourceId;

        public PK() {}
        public PK(String userId, String organizationId, String resourceType, String resourceId) {
            this.userId = userId;
            this.organizationId = organizationId;
            this.resourceType = resourceType;
            this.resourceId = resourceId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(userId, pk.userId)
                    && Objects.equals(organizationId, pk.organizationId)
                    && Objects.equals(resourceType, pk.resourceType)
                    && Objects.equals(resourceId, pk.resourceId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, organizationId, resourceType, resourceId);
        }
    }
}
