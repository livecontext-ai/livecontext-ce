package com.apimarketplace.auth.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Cloud-side mirror of a CE install (cloud-link Phase 1).
 * Created by {@code POST /api/ce-link/register} after a CE finishes the
 * existing OAuth flow (publication-service.CloudLinkService) and identifies
 * itself with an install_id generated CE-side.
 *
 * <p>See {@code the project docs} §2.2 / V260 migration.
 *
 * <p><b>Invariants</b> (DB-enforced):
 * <ul>
 *   <li>{@code install_id} is the primary key - guarantees a single ACTIVE row
 *       per install across all users (squat-detection happens at insert time
 *       via the PK conflict).</li>
 *   <li>{@code status=ACTIVE} ⟺ ({@code revoked_at IS NULL} AND
 *       {@code revoked_by_user_id IS NULL} AND {@code revoke_reason IS NULL}).
 *       {@code status=REVOKED} ⟺ ({@code revoked_at IS NOT NULL} AND
 *       {@code revoke_reason IS NOT NULL}). Enforced by
 *       {@code chk_ce_link_revoked_consistency}.</li>
 *   <li>{@code revoke_reason} ∈ {USER, ADMIN, RESET_SIGNAL, SQUAT_RECOVERY,
 *       SYSTEM, DEACTIVATED_USER} - enforced by CHECK; mirrored by
 *       {@link RevokeReason}.</li>
 * </ul>
 */
@Entity
@Table(name = "ce_link", schema = "auth")
public class CeLink {

    /**
     * Status values mirroring the DB CHECK constraint. ACTIVE = caller can
     * use the link for marketplace/PAYG. REVOKED = no further authed calls.
     */
    public enum Status { ACTIVE, REVOKED }

    /**
     * Why this link was revoked. Mirrors the DB CHECK enum exactly.
     */
    public enum RevokeReason {
        /** User-initiated revoke from /app/settings/cloud-account (Connection tab). */
        USER,
        /** Admin/ops support action. */
        ADMIN,
        /** Auto-revoke triggered by X-LiveContext-Reset-Signal header. */
        RESET_SIGNAL,
        /** Auto-revoke via the squat-recovery email click flow. */
        SQUAT_RECOVERY,
        /** System cleanup (retention scheduler, etc.). */
        SYSTEM,
        /** User account deactivated - link logically revoked at runtime. */
        DEACTIVATED_USER
    }

    @Id
    @Column(name = "install_id", nullable = false, updatable = false)
    private UUID installId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "label", length = 128)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Column(name = "scopes", nullable = false, length = 256)
    private String scopes = "catalog,marketplace";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by_user_id")
    private Long revokedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason", length = 32)
    private RevokeReason revokeReason;

    public CeLink() {}

    public CeLink(UUID installId, Long userId, String label) {
        this.installId = installId;
        this.userId = userId;
        this.label = label;
    }

    // ===== Lifecycle helpers (keep all status mutation logic in one place) =====

    /**
     * Mark this link revoked. Sets revoked_at/revoked_by/revoke_reason atomically
     * so the {@code chk_ce_link_revoked_consistency} CHECK can't be violated.
     */
    public void revoke(RevokeReason reason, Long byUserId) {
        if (this.status == Status.REVOKED) {
            return; // idempotent
        }
        this.status = Status.REVOKED;
        this.revokedAt = Instant.now();
        this.revokeReason = reason;
        this.revokedByUserId = byUserId;
    }

    public boolean isActive() {
        return this.status == Status.ACTIVE;
    }

    // ===== Accessors =====

    public UUID getInstallId() { return installId; }
    public Long getUserId() { return userId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Status getStatus() { return status; }
    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public Long getRevokedByUserId() { return revokedByUserId; }
    public RevokeReason getRevokeReason() { return revokeReason; }
}
