package com.apimarketplace.agent.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity for agent_widget_configs table.
 * Stores widget configuration for agents to embed on external websites.
 */
@Entity
@Table(name = "agent_widget_configs")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class AgentWidgetConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false, unique = true)
    private UUID agentId;

    // Appearance
    @Column(name = "position", length = 20)
    private String position = "bottom-right";

    @Column(name = "theme", length = 10)
    private String theme = "auto";

    @Column(name = "primary_color", length = 20)
    private String primaryColor = "#000000";

    // Content
    @Column(name = "welcome_message", columnDefinition = "TEXT")
    private String welcomeMessage = "Hello! How can I help you today?";

    @Column(name = "bubble_text", length = 100)
    private String bubbleText = "Chat with us";

    // Behavior
    @Column(name = "show_avatar")
    private Boolean showAvatar = true;

    @Column(name = "auto_open_delay")
    private Integer autoOpenDelay = 0;

    // Security
    @Column(name = "widget_token", length = 40, unique = true)
    private String widgetToken;

    @Column(name = "allowed_origins", columnDefinition = "TEXT")
    private String allowedOrigins;

    // Status
    @Column(name = "is_active")
    private Boolean isActive = true;

    // Timestamps
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public AgentWidgetConfigEntity() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public AgentWidgetConfigEntity(UUID agentId) {
        this();
        this.agentId = agentId;
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

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position;
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public String getPrimaryColor() {
        return primaryColor;
    }

    public void setPrimaryColor(String primaryColor) {
        this.primaryColor = primaryColor;
    }

    public String getWelcomeMessage() {
        return welcomeMessage;
    }

    public void setWelcomeMessage(String welcomeMessage) {
        this.welcomeMessage = welcomeMessage;
    }

    public String getBubbleText() {
        return bubbleText;
    }

    public void setBubbleText(String bubbleText) {
        this.bubbleText = bubbleText;
    }

    public Boolean getShowAvatar() {
        return showAvatar;
    }

    public void setShowAvatar(Boolean showAvatar) {
        this.showAvatar = showAvatar;
    }

    public Integer getAutoOpenDelay() {
        return autoOpenDelay;
    }

    public void setAutoOpenDelay(Integer autoOpenDelay) {
        this.autoOpenDelay = autoOpenDelay;
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

    public String getWidgetToken() {
        return widgetToken;
    }

    public void setWidgetToken(String widgetToken) {
        this.widgetToken = widgetToken;
    }

    public String getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public String toString() {
        return "AgentWidgetConfigEntity{" +
                "id=" + id +
                ", agentId=" + agentId +
                ", position='" + position + '\'' +
                ", theme='" + theme + '\'' +
                ", primaryColor='" + primaryColor + '\'' +
                ", isActive=" + isActive +
                '}';
    }
}
