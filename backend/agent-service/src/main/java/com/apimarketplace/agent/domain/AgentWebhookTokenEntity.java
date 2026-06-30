package com.apimarketplace.agent.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Entity for agent_webhook_tokens table.
 * Stores webhook configuration for agents to receive external triggers.
 */
@Entity
@Table(name = "agent_webhook_tokens")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class AgentWebhookTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "token", nullable = false, unique = true)
    private String token;

    @Column(name = "http_method", length = 10)
    private String httpMethod = "POST";

    @Column(name = "auth_type", length = 20)
    private String authType = "none";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "auth_config", columnDefinition = "jsonb")
    private Map<String, Object> authConfig;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "memory_enabled", nullable = false)
    private Boolean memoryEnabled = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public AgentWebhookTokenEntity() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public AgentWebhookTokenEntity(UUID agentId, String token) {
        this();
        this.agentId = agentId;
        this.token = token;
    }

    public AgentWebhookTokenEntity(UUID agentId, String token, String httpMethod, String authType, Map<String, Object> authConfig) {
        this(agentId, token);
        this.httpMethod = httpMethod;
        this.authType = authType;
        this.authConfig = authConfig;
    }

    @PreUpdate
    private void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public void setAgentId(UUID agentId) {
        this.agentId = agentId;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public Map<String, Object> getAuthConfig() {
        return authConfig;
    }

    public void setAuthConfig(Map<String, Object> authConfig) {
        this.authConfig = authConfig;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Boolean getMemoryEnabled() {
        return memoryEnabled;
    }

    public void setMemoryEnabled(Boolean memoryEnabled) {
        this.memoryEnabled = memoryEnabled;
    }

    @Override
    public String toString() {
        return "AgentWebhookTokenEntity{" +
                "id=" + id +
                ", agentId=" + agentId +
                ", token='" + (token != null ? token.substring(0, Math.min(12, token.length())) + "..." : "null") + '\'' +
                ", httpMethod='" + httpMethod + '\'' +
                ", authType='" + authType + '\'' +
                ", isActive=" + isActive +
                '}';
    }
}
