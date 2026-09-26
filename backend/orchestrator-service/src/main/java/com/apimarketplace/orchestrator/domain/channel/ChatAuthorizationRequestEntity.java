package com.apimarketplace.orchestrator.domain.channel;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A permission an agent asked for from a run nobody is watching, delivered to a
 * chat destination.
 *
 * <p>The row is the server-side half of the buttons in that message: the button
 * carries only {@link #callbackToken} and a verdict flag, and everything needed
 * to act on the answer - which conversation, which parked call, which rule - is
 * here, one indexed lookup away.
 *
 * <p>Deliberately not {@code ApprovalChannelDeliveryEntity}: that one is keyed to
 * a workflow signal and denormalises run/node/epoch/item, none of which exists
 * for an agent's tool call. Same transport, different subject.
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "chat_authorization_requests", schema = "orchestrator")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class ChatAuthorizationRequestEntity implements OrgScopedEntity {

    /**
     * Lifecycle.
     *
     * <p>There is no PENDING and no FAILED: a row exists only once a message really
     * reached the chat. A failed send writes nothing on purpose, because a row is what
     * the duplicate rule reads, and one left behind by a send that never arrived would
     * block the next ask behind a question nobody can see.
     */
    /**
     * What the person is being shown, and therefore how an answer comes back.
     *
     * <p>Closed on purpose, mirrored by {@code ck_chat_auth_requests_kind}: an unreadable
     * kind cannot be stored, so no switch downstream meets a value it has no branch for.
     */
    public enum RequestKind {
        /** Two buttons, approve or refuse. The original and only kind before V519. */
        APPROVAL,
        /** One button per option, plus Done when several may be picked. */
        CHOICE,
        /** No buttons: the person replies to the message. */
        TEXT
    }

    public enum RequestStatus {
        /** Delivered, buttons live, waiting for someone to press one. */
        SENT,
        /** Someone pressed a button. {@link #decision} says which. */
        RESOLVED,
        /** Nobody answered within the request's lifetime. */
        EXPIRED
    }

    public enum Decision { APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "organization_id", nullable = false)
    private String organizationId;

    @Column(name = "link_id", nullable = false)
    private UUID linkId;

    @Column(name = "channel", nullable = false, length = 30)
    private String channel;

    @Column(name = "credential_id", nullable = false)
    private Long credentialId;

    @Column(name = "chat_id", nullable = false, length = 120)
    private String chatId;

    @Column(name = "callback_token", nullable = false, length = 64)
    private String callbackToken;

    @Column(name = "conversation_id", nullable = false, length = 100)
    private String conversationId;

    /** The parked tool call this answer releases: the in-app card's own id. */
    @Column(name = "gate_key", nullable = false, length = 200)
    private String gateKey;

    @Column(name = "rule", nullable = false, length = 120)
    private String rule;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "agent_name")
    private String agentName;

    /**
     * What makes "the same request asked again" recognisable: the rule plus a
     * digest of the call's arguments. Backed by a partial unique index over live
     * rows, so tomorrow's run of the same nightly agent cannot send a second copy
     * of a question still waiting.
     */
    @Column(name = "fingerprint", nullable = false, length = 80)
    private String fingerprint;

    @Column(name = "message_id", length = 40)
    private String messageId;

    /**
     * Which message type this row is (V519).
     *
     * <p>A closed type rather than a flag, and stored rather than derived. Both answer paths
     * read it to refuse a row of the other family: a person replying in text to an APPROVAL
     * message must not have that reply consumed as the answer to a question, which would
     * resolve the permission request with no verdict at all; and a question's token pressed
     * under the approval prefix must not apply a verdict to a row that asked for a value. The
     * sweep and the close do not need it, since expiring and taking buttons away are the same
     * for every kind.
     *
     * <p>Defaulted to APPROVAL at the column, so every row written before the question
     * feature existed reads as exactly what it is with no backfill.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private RequestKind kind = RequestKind.APPROVAL;

    /**
     * The rows of ONE {@code ask_user} call, which answer together (V519).
     *
     * <p>A call asks several questions and owes the agent a single answer envelope, so the
     * last one answered has to be able to find its siblings. Null for an approval, which is
     * always alone.
     */
    @Column(name = "group_key", length = 64)
    private String groupKey;

    /**
     * The question as it was SENT (V519).
     *
     * <p>Same reason {@code messageText} exists for an approval: what the person was shown is
     * what the record has to be able to say they were shown. Rebuilding it from the tool call
     * later rewords it, and the reworded version is the one the audit trail would keep.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private Map<String, Object> payload;

    /**
     * Draft selections while a multi-select is being toggled, then the answer (V519).
     *
     * <p>In the row rather than in memory because the next press can land on the other
     * replica, and a toggle nobody remembers is a checkbox that refuses to stay checked.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answer", columnDefinition = "jsonb")
    private Map<String, Object> answer;

    /**
     * The body as it was sent, already capped to what the connector accepts.
     *
     * <p>Kept so the closing edit can preserve WHAT was approved. Rebuilding a shorter message
     * there deleted the summary from the one audit trail a phone user has.
     */
    @Column(name = "message_text", columnDefinition = "TEXT")
    private String messageText;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RequestStatus status = RequestStatus.SENT;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", length = 20)
    private Decision decision;

    @Column(name = "decided_by", length = 120)
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /**
     * When this call's answers were handed to the conversation (V520).
     *
     * <p>The claim that makes the hand-over happen exactly once per group, not a timestamp
     * anybody reads. Two answers landing in the same second both see every row RESOLVED and
     * both would hand the same answers over, which starts a second agent turn on the same
     * conversation while the first is still running.
     */
    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "error")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

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

    /** True once the request can no longer be answered. */
    public boolean isTerminal() {
        return status == RequestStatus.RESOLVED || status == RequestStatus.EXPIRED;
    }

    /**
     * True once the deadline has passed, whether or not the sweep has been round yet.
     *
     * <p>The status alone answers a different question. A row stays SENT until a scheduler
     * retires it, in batches, on a timer, so between the deadline and the sweep the row says
     * "live" and the message in the chat still has buttons. Every decision about whether this
     * request may still be answered asks THIS, and the claim carries the same predicate so the
     * answer cannot change between the check and the write.
     *
     * <p>A missing deadline counts as past. The column is NOT NULL and has been since the table
     * was created, so there is no row this can legitimately happen to; if one ever appears, this
     * method decides whether a permission may still be granted, and the safe reading of "no
     * deadline recorded" is that the request is over. Refusing it costs an agent one re-ask.
     */
    public boolean isPastDeadline(Instant now) {
        return expiresAt == null || !expiresAt.isAfter(now);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    @Override
    public String getOrganizationId() { return organizationId; }

    @Override
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public UUID getLinkId() { return linkId; }
    public void setLinkId(UUID linkId) { this.linkId = linkId; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public Long getCredentialId() { return credentialId; }
    public void setCredentialId(Long credentialId) { this.credentialId = credentialId; }

    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }

    public String getCallbackToken() { return callbackToken; }
    public void setCallbackToken(String callbackToken) { this.callbackToken = callbackToken; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public String getGateKey() { return gateKey; }
    public void setGateKey(String gateKey) { this.gateKey = gateKey; }

    public String getRule() { return rule; }
    public void setRule(String rule) { this.rule = rule; }

    public UUID getAgentId() { return agentId; }
    public void setAgentId(UUID agentId) { this.agentId = agentId; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }

    public String getMessageId() { return messageId; }
    public String getMessageText() { return messageText; }
    public void setMessageText(String messageText) { this.messageText = messageText; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public Instant getAppliedAt() { return appliedAt; }
    public void setAppliedAt(Instant appliedAt) { this.appliedAt = appliedAt; }

    public RequestKind getKind() { return kind; }
    public void setKind(RequestKind kind) { this.kind = kind; }

    public String getGroupKey() { return groupKey; }
    public void setGroupKey(String groupKey) { this.groupKey = groupKey; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }

    public Map<String, Object> getAnswer() { return answer; }
    public void setAnswer(Map<String, Object> answer) { this.answer = answer; }

    public RequestStatus getStatus() { return status; }
    public void setStatus(RequestStatus status) { this.status = status; }

    public Decision getDecision() { return decision; }
    public void setDecision(Decision decision) { this.decision = decision; }

    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }

    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
