package com.apimarketplace.agent.domain;

import com.apimarketplace.common.security.token.EncryptedTokenConverter;
import com.apimarketplace.common.security.token.HashedTokenEntity;
import com.apimarketplace.common.security.token.HashedTokenListener;
import com.apimarketplace.common.security.token.TokenSlot;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity for agent_widget_configs table.
 * Stores widget configuration for agents to embed on external websites.
 */
@Entity
@EntityListeners(HashedTokenListener.class)
@Table(name = "agent_widget_configs")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class AgentWidgetConfigEntity implements HashedTokenEntity {

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
    /**
     * The embeddable widget token (/w/embed/{token}).
     * Stored encrypted (ENC:...) through {@link EncryptedTokenConverter}; the entity always holds
     * the plaintext. Lookups go through {@link #getWidgetTokenHash()}, never through this column.
     */
    @Convert(converter = EncryptedTokenConverter.class)
    @Column(name = "widget_token", unique = true, length = 255)
    private String widgetToken;

    /** HMAC-SHA256 of the plaintext, filled by {@link HashedTokenListener}; the only lookup key. */
    @Column(name = "widget_token_hash", length = 64)
    private String widgetTokenHash;

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

    @JsonIgnore
    public String getWidgetTokenHash() {
        return widgetTokenHash;
    }

    public void setWidgetTokenHash(String widgetTokenHash) {
        this.widgetTokenHash = widgetTokenHash;
    }

    @Override
    @JsonIgnore
    public List<TokenSlot> tokenSlots() {
        return List.of(new TokenSlot(this::getWidgetToken, this::setWidgetTokenHash));
    }
}
