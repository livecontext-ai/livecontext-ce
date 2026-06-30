package com.apimarketplace.agent.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A free-form note attached to an {@link AgentTaskEntity}.
 * Used by humans or the calling / assignee agent to leave context.
 */
@Entity
@Table(name = "agent_task_notes", schema = "agent")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class AgentTaskNoteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    /**
     * Stamped from the parent task's {@code organization_id} on insert (V281). Defense-in-depth
     * against a controller-layer regression that fetches notes without first validating the
     * parent task is in scope.
     */
    @Column(name = "organization_id", nullable = false)
    private String organizationId;

    @Column(name = "author_agent_id")
    private UUID authorAgentId;

    @Column(name = "author_user_id")
    private String authorUserId;

    // Plain text column (NOT @Lob) - see SkillEntity.instructions note. Audit 2026-06-14.
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public AgentTaskNoteEntity() {}

    @PrePersist
    private void onCreate() {
        if (this.createdAt == null) this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public UUID getAuthorAgentId() { return authorAgentId; }
    public void setAuthorAgentId(UUID authorAgentId) { this.authorAgentId = authorAgentId; }

    public String getAuthorUserId() { return authorUserId; }
    public void setAuthorUserId(String authorUserId) { this.authorUserId = authorUserId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
