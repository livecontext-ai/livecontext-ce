package com.apimarketplace.publication.domain;

import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Admin-curated highlight slot. One row per (display_mode, publication) pair;
 * {@link #rank} drives the order of the homepage Highlights row for the
 * given display_mode. Backed by V164 migration.
 */
@Entity
@Table(name = "publication_highlights")
@IdClass(PublicationHighlightEntity.PK.class)
public class PublicationHighlightEntity {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "display_mode", nullable = false, length = 50)
    private DisplayMode displayMode;

    @Id
    @Column(name = "publication_id", nullable = false)
    private UUID publicationId;

    @Column(name = "rank", nullable = false)
    private int rank;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, length = 255)
    private String createdBy;

    public PublicationHighlightEntity() {
    }

    public PublicationHighlightEntity(DisplayMode displayMode, UUID publicationId, int rank, String createdBy) {
        this.displayMode = displayMode;
        this.publicationId = publicationId;
        this.rank = rank;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public DisplayMode getDisplayMode() { return displayMode; }
    public void setDisplayMode(DisplayMode displayMode) { this.displayMode = displayMode; }

    public UUID getPublicationId() { return publicationId; }
    public void setPublicationId(UUID publicationId) { this.publicationId = publicationId; }

    public int getRank() { return rank; }
    public void setRank(int rank) { this.rank = rank; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public static class PK implements Serializable {
        private DisplayMode displayMode;
        private UUID publicationId;

        public PK() {}
        public PK(DisplayMode displayMode, UUID publicationId) {
            this.displayMode = displayMode;
            this.publicationId = publicationId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return displayMode == pk.displayMode && Objects.equals(publicationId, pk.publicationId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(displayMode, publicationId);
        }
    }
}
