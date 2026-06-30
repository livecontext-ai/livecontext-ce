package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Immutable;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only audit row for ce-link state changes. The DB enforces immutability
 * via BEFORE UPDATE/DELETE/TRUNCATE triggers (see V260 / {@code auth.ce_link_audit_immutable()}).
 *
 * <p>Mirrored to Loki via {@code auth.audit.AuditLogger} so DBA-with-DDL tampering
 * still leaves a forensic trail (doc §7 threat model "DBA on cloud (insider DDL)").
 *
 * <p>The {@code install_id} column has no FK to {@code auth.ce_link} so audit rows
 * deliberately survive registry deletion.
 */
@Entity
@Immutable
@Table(name = "ce_link_audit", schema = "auth")
public class CeLinkAudit {

    public enum ActorRole { OWNER, ADMIN, SYSTEM }

    public enum Event {
        REGISTER,
        REVOKE,
        RESET,
        SCOPE_GRANT,
        HEARTBEAT,
        NETWORK_CHANGE,
        SUSPECTED_CROSS_USER_RESET
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "install_id", nullable = false)
    private UUID installId;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", nullable = false, length = 16)
    private ActorRole actorRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "event", nullable = false, length = 32)
    private Event event;

    @Column(name = "scope_before", length = 256)
    private String scopeBefore;

    @Column(name = "scope_after", length = 256)
    private String scopeAfter;

    @Column(name = "ip_hash", length = 64)
    private String ipHash;

    @Column(name = "key_version", nullable = false)
    private Integer keyVersion = 1;

    @Column(name = "user_agent", length = 256)
    private String userAgent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public CeLinkAudit() {}

    public Long getId() { return id; }
    public UUID getInstallId() { return installId; }
    public void setInstallId(UUID installId) { this.installId = installId; }
    public Long getActorUserId() { return actorUserId; }
    public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }
    public ActorRole getActorRole() { return actorRole; }
    public void setActorRole(ActorRole actorRole) { this.actorRole = actorRole; }
    public Event getEvent() { return event; }
    public void setEvent(Event event) { this.event = event; }
    public String getScopeBefore() { return scopeBefore; }
    public void setScopeBefore(String scopeBefore) { this.scopeBefore = scopeBefore; }
    public String getScopeAfter() { return scopeAfter; }
    public void setScopeAfter(String scopeAfter) { this.scopeAfter = scopeAfter; }
    public String getIpHash() { return ipHash; }
    public void setIpHash(String ipHash) { this.ipHash = ipHash; }
    public Integer getKeyVersion() { return keyVersion; }
    public void setKeyVersion(Integer keyVersion) { this.keyVersion = keyVersion; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public Instant getCreatedAt() { return createdAt; }
}
