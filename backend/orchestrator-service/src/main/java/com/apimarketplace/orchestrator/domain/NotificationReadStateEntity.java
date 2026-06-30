package com.apimarketplace.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Per-user bell-read cursor. Single timestamp per user marks the boundary
 * between "read" and "unread" events. Events whose {@code ended_at} is
 * greater than {@code last_seen_at} are considered unread.
 *
 * <p>MVP scope (V171): single source = failed runs of pinned workflows.
 * Future expansion may add per-source cursors as new event sources land.
 */
@Entity
@Table(name = "notification_read_state", schema = "orchestrator")
public class NotificationReadStateEntity {

    @Id
    @Column(name = "user_id", length = 255)
    private String userId;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public NotificationReadStateEntity() {
        this.lastSeenAt = Instant.EPOCH;
        this.updatedAt = Instant.now();
    }

    public NotificationReadStateEntity(String userId, Instant lastSeenAt) {
        this.userId = userId;
        this.lastSeenAt = lastSeenAt;
        this.updatedAt = Instant.now();
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
