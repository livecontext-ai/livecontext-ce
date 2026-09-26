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

import java.time.Instant;
import java.util.UUID;

/**
 * A connected outbound chat bot (v1: Telegram), one row per
 * (workspace, channel, credential).
 *
 * <p>Holds what belongs to the BOT rather than to any one destination, and the
 * split exists for one concrete reason: a Telegram bot has exactly ONE webhook
 * URL. Storing {@code webhookUrl} beside a chat id would make the column lie as
 * soon as the same bot serves a second chat, since both rows describe the same
 * single webhook. Destinations live in {@link ChatChannelLinkEntity}.
 *
 * <p><b>{@link #credentialId} is NOT NULL</b>, where the workflow approval
 * node's delegation block accepts a blank "send with my default Telegram
 * credential". The node can afford that: it resolves the default at send time
 * and a wrong guess costs one message. A link cannot, because the inbound half
 * (the button click) comes back through the webhook of ONE specific bot, so a
 * destination that does not know which bot it belongs to cannot be resolved on
 * the way back. "The default one" is resolved once, at connect time, and the
 * resolved id is what is stored.
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "chat_channel_bots", schema = "orchestrator")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class ChatChannelBotEntity implements OrgScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    /** Connecting user. Attribution only: isolation is {@link #organizationId}. */
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "organization_id", nullable = false)
    private String organizationId;

    /**
     * Channel id, matched against {@code ApprovalChannelNotifierRegistry}. No DB
     * CHECK constraint backs it, on purpose: the registry is open by
     * construction (a new channel is one new bean), and a CHECK would mean a
     * migration is required to ship one.
     */
    @Column(name = "channel", nullable = false, length = 30)
    private String channel;

    @Column(name = "credential_id", nullable = false)
    private Long credentialId;

    /** Provider-reported identity, so the UI can name the bot instead of a credential id. */
    @Column(name = "bot_identity", length = 120)
    private String botIdentity;

    @Column(name = "bot_username", length = 120)
    private String botUsername;

    /**
     * The webhook URL as WE set it, kept verbatim rather than recomputed. A
     * stored value that no longer matches the current public base URL is the
     * only available evidence that inbound clicks are landing nowhere, which is
     * otherwise a completely silent failure: the outbound message still sends.
     */
    @Column(name = "webhook_url")
    private String webhookUrl;

    /**
     * What this bot's inbound callbacks are verified with (V521): the Discord application's
     * public key, or the WhatsApp verify token we generated. Not a credential.
     */
    @Column(name = "inbound_key", length = 200)
    private String inboundKey;

    @Column(name = "webhook_set_at")
    private Instant webhookSetAt;

    /**
     * Last successful identity round trip with the provider. Deliberately
     * distinct from {@link #webhookSetAt}: a bot can answer perfectly while its
     * webhook points at someone else's installation.
     */
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

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public Long getCredentialId() { return credentialId; }
    public void setCredentialId(Long credentialId) { this.credentialId = credentialId; }

    public String getBotIdentity() { return botIdentity; }
    public void setBotIdentity(String botIdentity) { this.botIdentity = botIdentity; }

    public String getBotUsername() { return botUsername; }
    public void setBotUsername(String botUsername) { this.botUsername = botUsername; }

    public String getInboundKey() { return inboundKey; }
    public void setInboundKey(String inboundKey) { this.inboundKey = inboundKey; }

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

    public Instant getWebhookSetAt() { return webhookSetAt; }
    public void setWebhookSetAt(Instant webhookSetAt) { this.webhookSetAt = webhookSetAt; }

    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
