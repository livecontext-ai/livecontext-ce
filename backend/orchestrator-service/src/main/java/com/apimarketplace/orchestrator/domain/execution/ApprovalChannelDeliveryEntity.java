package com.apimarketplace.orchestrator.domain.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * JPA entity for approval_channel_deliveries (V393).
 *
 * One row per (signal_wait, channel) outbound delegated-approval notification
 * (v1: a Telegram message with inline approve/reject buttons). The row is the
 * server-side half of the channel button: the button's callback carries only
 * {@link #callbackToken} + a verdict flag (Telegram callback_data is capped at
 * 64 bytes), and everything needed to resolve the approval (run/node/item/epoch
 * /tenant) is denormalized here for a single indexed lookup on click.
 *
 * Lifecycle: PENDING (inserted, send in flight) then SENT (message delivered,
 * message id captured) or FAILED (send error), then RESOLVED (approval decided,
 * message edited) or CANCELLED (signal cancelled, message edited).
 */
@Entity
@Table(name = "approval_channel_deliveries", schema = "orchestrator",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"callback_token"}),
        @UniqueConstraint(columnNames = {"signal_wait_id", "channel"})
    })
public class ApprovalChannelDeliveryEntity {

    /** Delivery lifecycle status (mirrors chk_acd_status_v1). */
    public enum DeliveryStatus {
        PENDING,
        SENT,
        FAILED,
        RESOLVED,
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "signal_wait_id", nullable = false)
    private Long signalWaitId;

    @Column(name = "channel", nullable = false, length = 30)
    private String channel;

    @Column(name = "callback_token", nullable = false, length = 64)
    private String callbackToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @Column(name = "tenant_id", nullable = false, length = 255)
    private String tenantId;

    /** PUBLIC run id (matches workflow_signal_waits.run_id), not the UUID PK. */
    @Column(name = "run_id", nullable = false, length = 100)
    private String runId;

    @Column(name = "node_id", nullable = false, length = 200)
    private String nodeId;

    @Column(name = "item_id", nullable = false, length = 50)
    private String itemId = "0";

    @Column(name = "epoch", nullable = false)
    private int epoch;

    @Column(name = "credential_id")
    private Long credentialId;

    @Column(name = "chat_id", length = 100)
    private String chatId;

    @Column(name = "message_id", length = 50)
    private String messageId;

    @Column(name = "message_text", columnDefinition = "text")
    private String messageText;

    /**
     * Optional allowlist of channel user ids (Telegram from.id as strings)
     * allowed to decide. Empty or null: anyone in the chat may decide.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_user_ids", columnDefinition = "jsonb")
    private List<String> allowedUserIds;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public ApprovalChannelDeliveryEntity() {
    }

    // ========================================================================
    // BUSINESS METHODS
    // ========================================================================

    /** True once the approval decision landed (RESOLVED or CANCELLED). */
    public boolean isTerminal() {
        return status == DeliveryStatus.RESOLVED || status == DeliveryStatus.CANCELLED;
    }

    public boolean isSent() {
        return status == DeliveryStatus.SENT;
    }

    // ========================================================================
    // GETTERS AND SETTERS
    // ========================================================================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSignalWaitId() {
        return signalWaitId;
    }

    public void setSignalWaitId(Long signalWaitId) {
        this.signalWaitId = signalWaitId;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getCallbackToken() {
        return callbackToken;
    }

    public void setCallbackToken(String callbackToken) {
        this.callbackToken = callbackToken;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public void setStatus(DeliveryStatus status) {
        this.status = status;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public int getEpoch() {
        return epoch;
    }

    public void setEpoch(int epoch) {
        this.epoch = epoch;
    }

    public Long getCredentialId() {
        return credentialId;
    }

    public void setCredentialId(Long credentialId) {
        this.credentialId = credentialId;
    }

    public String getChatId() {
        return chatId;
    }

    public void setChatId(String chatId) {
        this.chatId = chatId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getMessageText() {
        return messageText;
    }

    public void setMessageText(String messageText) {
        this.messageText = messageText;
    }

    /** Never null: a null jsonb column reads as the empty allowlist (anyone may decide). */
    public List<String> getAllowedUserIds() {
        return allowedUserIds != null ? allowedUserIds : List.of();
    }

    public void setAllowedUserIds(List<String> allowedUserIds) {
        this.allowedUserIds = allowedUserIds;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        // Send failures embed provider payloads; keep the column bounded so a
        // pathological error body cannot bloat the row.
        this.error = error == null || error.length() <= 2000
                ? error
                : error.substring(0, 2000);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    @Override
    public String toString() {
        return "ApprovalChannelDeliveryEntity{" +
            "id=" + id +
            ", signalWaitId=" + signalWaitId +
            ", channel='" + channel + '\'' +
            ", status=" + status +
            ", runId='" + runId + '\'' +
            ", nodeId='" + nodeId + '\'' +
            ", epoch=" + epoch +
            '}';
    }
}
