package com.apimarketplace.agent.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-user override of {@link SkillEntity#getIsDefaultActive()} - V276
 * (2026-05-21). Lets a user opt out of a global or org-shared skill that the
 * owner/admin marked default-active, without affecting their teammates.
 *
 * <p>Resolution at chat-time (conversation-service AgentContextBuilder):
 * <pre>
 *   effective_active(user, skill) =
 *       COALESCE(user_skill_overrides.active, skills.is_default_active)
 * </pre>
 *
 * <p>Not org-scoped - the row is keyed by {@code user_id} directly. A user
 * brings their preference across every (tenant × org) they belong to, which
 * mirrors how the legacy localStorage seed worked before being lifted to DB.
 */
@Entity
@Table(name = "user_skill_overrides", schema = "agent")
@IdClass(UserSkillOverrideId.class)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class UserSkillOverrideEntity {

    @Id
    @Column(name = "user_id", nullable = false, length = 255)
    private String userId;

    @Id
    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UserSkillOverrideEntity() {}

    public UserSkillOverrideEntity(String userId, UUID skillId, boolean active) {
        this.userId = userId;
        this.skillId = skillId;
        this.active = active;
    }

    @PrePersist
    private void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) this.createdAt = now;
        if (this.updatedAt == null) this.updatedAt = now;
    }

    @PreUpdate
    private void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public UUID getSkillId() { return skillId; }
    public void setSkillId(UUID skillId) { this.skillId = skillId; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
