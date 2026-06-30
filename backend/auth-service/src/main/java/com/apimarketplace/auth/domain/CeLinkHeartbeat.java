package com.apimarketplace.auth.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Hot-table row mirroring {@code auth.ce_link_heartbeat} (V260).
 *
 * <p>Separate from {@link CeLink} so the per-heartbeat UPDATE never bloats the
 * cold registry row (HOT-update friendly). Cascade-deleted with the parent
 * {@code ce_link} row via the DB FK; no application-level join needed.
 *
 * <p><b>Audit cadence</b> (doc §1 #27): a {@code ce_link_audit} row is written
 * only on IP change OR every 24h OR every 1000th call
 * ({@code heartbeat_count_since_audit >= 1000}). The counter resets when an
 * audit row is emitted. This keeps the audit table bounded under stable-IP load.
 */
@Entity
@Table(name = "ce_link_heartbeat", schema = "auth")
public class CeLinkHeartbeat {

    @Id
    @Column(name = "install_id", nullable = false, updatable = false)
    private UUID installId;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "last_seen_ip_hash", nullable = false, length = 64)
    private String lastSeenIpHash;

    @Column(name = "key_version", nullable = false)
    private int keyVersion;

    @Column(name = "last_seen_ce_version", length = 32)
    private String lastSeenCeVersion;

    @Column(name = "last_audited_at")
    private Instant lastAuditedAt;

    @Column(name = "heartbeat_count_since_audit", nullable = false)
    private long heartbeatCountSinceAudit;

    public CeLinkHeartbeat() {}

    /**
     * Construct a fresh row for the FIRST heartbeat of an install. The caller
     * is expected to subsequently {@link #recordAuditEmission(Instant)} when
     * it persists the matching {@code HEARTBEAT} audit row.
     */
    public CeLinkHeartbeat(UUID installId, Instant lastSeenAt, String lastSeenIpHash,
                           int keyVersion, String lastSeenCeVersion) {
        this.installId = installId;
        this.lastSeenAt = lastSeenAt;
        this.lastSeenIpHash = lastSeenIpHash;
        this.keyVersion = keyVersion;
        this.lastSeenCeVersion = lastSeenCeVersion;
        this.heartbeatCountSinceAudit = 0L;
    }

    /**
     * Apply a fresh heartbeat: update last-seen fields and bump the audit
     * counter. Caller decides whether to also call
     * {@link #recordAuditEmission(Instant)} based on the §1 #27 cadence.
     */
    public void applyHeartbeat(Instant at, String ipHash, int keyVersion, String ceVersion) {
        this.lastSeenAt = at;
        this.lastSeenIpHash = ipHash;
        this.keyVersion = keyVersion;
        this.lastSeenCeVersion = ceVersion;
        this.heartbeatCountSinceAudit = this.heartbeatCountSinceAudit + 1L;
    }

    /**
     * Mark that the heartbeat just audited at {@code at}. Resets the rolling
     * counter so the next 1000 / 24h / IP-change window starts from this point.
     */
    public void recordAuditEmission(Instant at) {
        this.lastAuditedAt = at;
        this.heartbeatCountSinceAudit = 0L;
    }

    // ===== Accessors =====
    public UUID getInstallId() { return installId; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public String getLastSeenIpHash() { return lastSeenIpHash; }
    public int getKeyVersion() { return keyVersion; }
    public String getLastSeenCeVersion() { return lastSeenCeVersion; }
    public Instant getLastAuditedAt() { return lastAuditedAt; }
    public long getHeartbeatCountSinceAudit() { return heartbeatCountSinceAudit; }
}
