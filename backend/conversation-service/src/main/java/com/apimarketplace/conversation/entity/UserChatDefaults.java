package com.apimarketplace.conversation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-(user, workspace) default chat options (V312).
 *
 * <p>Self-service personal defaults: each user owns one row per workspace
 * ({@code user_id} + {@code organization_id}). The JSONB {@code config} mirrors the
 * frontend {@code ChatConfig} shape (temperature, systemPrompt, maxTokens,
 * maxIterations, executionTimeout, toolsMode, webSearch, imageGeneration,
 * autoAuthorizeTools, defaultSkillIds, turnLimits). It seeds the message composer +
 * new conversations in that workspace.
 */
@Entity
@Table(name = "user_chat_defaults", schema = "conversation")
@IdClass(UserChatDefaults.Key.class)
public class UserChatDefaults {

    @Id
    @Column(name = "user_id", nullable = false)
    private String userId;

    @Id
    @Column(name = "organization_id", nullable = false)
    private String organizationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> config = new HashMap<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public UserChatDefaults() {
    }

    public UserChatDefaults(String userId, String organizationId, Map<String, Object> config) {
        this.userId = userId;
        this.organizationId = organizationId;
        this.config = config != null ? config : new HashMap<>();
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config != null ? config : new HashMap<>();
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /** Composite primary key (user_id, organization_id). */
    public static class Key implements Serializable {
        private String userId;
        private String organizationId;

        public Key() {
        }

        public Key(String userId, String organizationId) {
            this.userId = userId;
            this.organizationId = organizationId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(userId, key.userId)
                    && Objects.equals(organizationId, key.organizationId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, organizationId);
        }
    }
}
