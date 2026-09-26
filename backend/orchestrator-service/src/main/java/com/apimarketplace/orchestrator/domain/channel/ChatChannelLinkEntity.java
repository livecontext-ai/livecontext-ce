package com.apimarketplace.orchestrator.domain.channel;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A destination a message actually lands in, reachable through one
 * {@link ChatChannelBotEntity}.
 *
 * <p>This is the address book entry every surface resolves when it must reach
 * someone who is not watching the app: a delegated workflow approval, and an
 * agent asking permission to run a sensitive action from an unattended run.
 *
 * <p><b>{@link #verifiedAt} is per destination, and that is the point.</b> The
 * bot answering {@code get_me} proves the token is good; it proves nothing at
 * all about this chat. Telegram refuses (403) when the user has never started
 * the bot, so "connected" and "can actually write to you" fail separately and
 * must be recorded separately, otherwise a green connection hides a channel
 * that has never delivered anything.
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "chat_channel_links", schema = "orchestrator")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class ChatChannelLinkEntity implements OrgScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    /** Connecting user. Attribution only: isolation is {@link #organizationId}. */
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "organization_id", nullable = false)
    private String organizationId;

    @Column(name = "bot_id", nullable = false)
    private UUID botId;

    /**
     * Provider-side chat identifier, as text. Telegram's is a signed 64-bit
     * integer for a private chat but a channel is addressable as {@code @name},
     * and this is the same shape the approval node's delegation block carries.
     */
    @Column(name = "chat_id", nullable = false, length = 120)
    private String chatId;

    @Column(name = "chat_title", length = 255)
    private String chatTitle;

    @Column(name = "chat_type", length = 40)
    private String chatType;

    /**
     * Provider user ids allowed to press a button, same semantics as the
     * approval node's {@code allowedUserIds}. Empty = anyone in the chat
     * decides, which is right for a private chat and a deliberate choice for a
     * group.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_user_ids", nullable = false)
    private List<String> allowedUserIds = new ArrayList<>();

    /**
     * The destination used when a caller asks for "this workspace's channel"
     * without naming one. At most one per workspace, across channels rather
     * than per channel, because the caller that needs it wants one answer.
     */
    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    @Override
    public String getOrganizationId() { return organizationId; }

    @Override
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public UUID getBotId() { return botId; }
    public void setBotId(UUID botId) { this.botId = botId; }

    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }

    public String getChatTitle() { return chatTitle; }
    public void setChatTitle(String chatTitle) { this.chatTitle = chatTitle; }

    public String getChatType() { return chatType; }
    public void setChatType(String chatType) { this.chatType = chatType; }

    public List<String> getAllowedUserIds() { return allowedUserIds; }

    public void setAllowedUserIds(List<String> allowedUserIds) {
        this.allowedUserIds = allowedUserIds == null ? new ArrayList<>() : new ArrayList<>(allowedUserIds);
    }

    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean aDefault) { this.isDefault = aDefault; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
