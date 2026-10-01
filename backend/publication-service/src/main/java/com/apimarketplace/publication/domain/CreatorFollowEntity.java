package com.apimarketplace.publication.domain;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * One row per (follower, creator): the follower is notified whenever a new listing by the
 * creator goes live on the marketplace. Both ids are numeric auth user ids as strings, the
 * same form as {@code workflow_publications.publisher_id}. Backed by the V551 migration.
 *
 * <p>Deliberately per USER, not per workspace: following is a person-to-person relation,
 * and the notification lands in the follower's personal workspace.
 */
@Entity
@Table(name = "creator_follows")
@IdClass(CreatorFollowEntity.PK.class)
public class CreatorFollowEntity {

    @Id
    @Column(name = "follower_id", nullable = false, length = 255)
    private String followerId;

    @Id
    @Column(name = "creator_id", nullable = false, length = 255)
    private String creatorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public CreatorFollowEntity() {
    }

    public CreatorFollowEntity(String followerId, String creatorId) {
        this.followerId = followerId;
        this.creatorId = creatorId;
        this.createdAt = Instant.now();
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public String getFollowerId() { return followerId; }
    public void setFollowerId(String followerId) { this.followerId = followerId; }

    public String getCreatorId() { return creatorId; }
    public void setCreatorId(String creatorId) { this.creatorId = creatorId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public static class PK implements Serializable {
        private String followerId;
        private String creatorId;

        public PK() {}
        public PK(String followerId, String creatorId) {
            this.followerId = followerId;
            this.creatorId = creatorId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(followerId, pk.followerId) && Objects.equals(creatorId, pk.creatorId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(followerId, creatorId);
        }
    }
}
