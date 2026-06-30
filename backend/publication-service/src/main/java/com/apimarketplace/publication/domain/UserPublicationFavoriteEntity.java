package com.apimarketplace.publication.domain;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row per (user, workspace, publication) the user has favorited. Drives the
 * personal "Favorites" view on Home, distinct from the admin-curated highlights
 * ({@link PublicationHighlightEntity}). Backed by the V359 migration.
 *
 * <p>{@code organizationId} is the active workspace and is {@code ""} (never null)
 * for the personal workspace, so the composite key stays well-defined.
 */
@Entity
@Table(name = "user_publication_favorites")
@IdClass(UserPublicationFavoriteEntity.PK.class)
public class UserPublicationFavoriteEntity {

    @Id
    @Column(name = "user_id", nullable = false, length = 255)
    private String userId;

    @Id
    @Column(name = "organization_id", nullable = false, length = 255)
    private String organizationId;

    @Id
    @Column(name = "publication_id", nullable = false)
    private UUID publicationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public UserPublicationFavoriteEntity() {
    }

    public UserPublicationFavoriteEntity(String userId, String organizationId, UUID publicationId) {
        this.userId = userId;
        this.organizationId = organizationId;
        this.publicationId = publicationId;
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

    public UUID getPublicationId() { return publicationId; }
    public void setPublicationId(UUID publicationId) { this.publicationId = publicationId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public static class PK implements Serializable {
        private String userId;
        private String organizationId;
        private UUID publicationId;

        public PK() {}
        public PK(String userId, String organizationId, UUID publicationId) {
            this.userId = userId;
            this.organizationId = organizationId;
            this.publicationId = publicationId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(userId, pk.userId)
                    && Objects.equals(organizationId, pk.organizationId)
                    && Objects.equals(publicationId, pk.publicationId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, organizationId, publicationId);
        }
    }
}
