package com.apimarketplace.agent.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the agent_skills join table.
 * Links an agent to a skill.
 */
@Entity
@Table(name = "agent_skills",
       uniqueConstraints = @UniqueConstraint(columnNames = {"agent_id", "skill_id"}))
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class AgentSkillEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Eagerly loaded skill reference for API responses.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "skill_id", insertable = false, updatable = false)
    private SkillEntity skill;

    public AgentSkillEntity() {
    }

    public AgentSkillEntity(UUID agentId, UUID skillId, Integer sortOrder) {
        this.agentId = agentId;
        this.skillId = skillId;
        this.sortOrder = sortOrder != null ? sortOrder : 0;
    }

    @PrePersist
    private void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.sortOrder == null) {
            this.sortOrder = 0;
        }
    }

    // Getters / setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public void setAgentId(UUID agentId) {
        this.agentId = agentId;
    }

    public UUID getSkillId() {
        return skillId;
    }

    public void setSkillId(UUID skillId) {
        this.skillId = skillId;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public SkillEntity getSkill() {
        return skill;
    }

    public void setSkill(SkillEntity skill) {
        this.skill = skill;
    }
}
