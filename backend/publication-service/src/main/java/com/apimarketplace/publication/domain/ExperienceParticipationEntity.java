package com.apimarketplace.publication.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Tracks daily participation in experiences.
 * Enforces the unique constraint (publication_id, user_id, participated_at)
 * so each user can participate at most once per day per experience.
 */
@Entity
@Table(name = "experience_participations", schema = "publication")
public class ExperienceParticipationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "publication_id", nullable = false)
    private UUID publicationId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "participated_at", nullable = false)
    private LocalDate participatedAt;

    @Column(name = "run_id")
    private String runId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    private void prePersist() {
        if (this.participatedAt == null) this.participatedAt = LocalDate.now();
        if (this.createdAt == null) this.createdAt = Instant.now();
    }

    public ExperienceParticipationEntity() {}

    public ExperienceParticipationEntity(UUID publicationId, String userId, String runId) {
        this.publicationId = publicationId;
        this.userId = userId;
        this.runId = runId;
        this.participatedAt = LocalDate.now();
        this.createdAt = Instant.now();
    }

    // Getters & setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getPublicationId() { return publicationId; }
    public void setPublicationId(UUID publicationId) { this.publicationId = publicationId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public LocalDate getParticipatedAt() { return participatedAt; }
    public void setParticipatedAt(LocalDate participatedAt) { this.participatedAt = participatedAt; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
