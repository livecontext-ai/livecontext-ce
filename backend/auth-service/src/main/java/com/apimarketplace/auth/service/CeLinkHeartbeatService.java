package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CeLink;
import com.apimarketplace.auth.domain.CeLinkAudit;
import com.apimarketplace.auth.domain.CeLinkHeartbeat;
import com.apimarketplace.auth.repository.CeLinkHeartbeatRepository;
import com.apimarketplace.auth.repository.CeLinkRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-install heartbeat handling (doc §3.5).
 *
 * <p>Inbound flow on {@code POST /api/ce-link/{installId}/heartbeat}:
 * <ol>
 *   <li>Ownership-scoped lookup. {@link Outcome#NOT_FOUND} → controller 404 (no enumeration oracle).</li>
 *   <li>Status pre-check. {@link Outcome#REVOKED} → controller 410 GONE so CE stops heartbeating.</li>
 *   <li>Hash the caller's IP with the CURRENT generation. Detect IP change vs the stored
 *       hash by re-hashing under that row's {@code key_version} (rotation-safe).</li>
 *   <li>Upsert {@code ce_link_heartbeat} row (HOT-update friendly - no parent ce_link touch).</li>
 *   <li>Emit an audit row per §1 #27 cadence: {@code NETWORK_CHANGE} on IP change, otherwise
 *       {@code HEARTBEAT} when (last_audited_at older than 24h) OR (count_since_audit >= 1000).
 *       The cadence keeps the audit table bounded under stable-IP load.</li>
 * </ol>
 *
 * <p>Audit cadence design note: we emit on (a) IP change (security signal),
 * (b) every 24h (liveness signal - proves the row hasn't been silently dropped),
 * (c) every 1000th call (stable-IP credential-abuse pre-detection, §7 threat
 * "Stable-IP credential abuse"). Counter resets when an audit row is emitted.
 */
@Service
@ConditionalOnProperty(name = "auth.mode", havingValue = "keycloak", matchIfMissing = false)
public class CeLinkHeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(CeLinkHeartbeatService.class);

    /** Per doc §1 #27 - liveness audit cadence. */
    static final Duration AUDIT_INTERVAL = Duration.ofHours(24);
    /** Per doc §1 #27 - stable-IP cadence. */
    static final long AUDIT_CALL_THRESHOLD = 1000L;

    private final CeLinkRepository ceLinkRepository;
    private final CeLinkHeartbeatRepository heartbeatRepository;
    private final IpHashService ipHashService;
    private final CeLinkAuditService auditService;
    private final SubscriptionRepository subscriptionRepository;
    private final CeLinkService ceLinkService;

    public CeLinkHeartbeatService(CeLinkRepository ceLinkRepository,
                                  CeLinkHeartbeatRepository heartbeatRepository,
                                  IpHashService ipHashService,
                                  CeLinkAuditService auditService,
                                  SubscriptionRepository subscriptionRepository,
                                  CeLinkService ceLinkService) {
        this.ceLinkRepository = ceLinkRepository;
        this.heartbeatRepository = heartbeatRepository;
        this.ipHashService = ipHashService;
        this.auditService = auditService;
        this.subscriptionRepository = subscriptionRepository;
        this.ceLinkService = ceLinkService;
    }

    /**
     * Process one heartbeat. {@code clientIp} is the raw caller IP - never
     * persisted in plaintext; immediately HMAC-hashed.
     */
    @Transactional
    public Outcome heartbeat(Long callerUserId, UUID installId, String ceVersion, String clientIp) {
        Optional<CeLink> linkOpt = ceLinkRepository.findByInstallIdAndUserId(installId, callerUserId);
        if (linkOpt.isEmpty()) {
            return Outcome.NOT_FOUND;
        }
        CeLink link = linkOpt.get();
        if (link.getStatus() != CeLink.Status.ACTIVE) {
            return Outcome.REVOKED;
        }

        // Live entitlement re-validation: a CE↔cloud link must not outlive the cloud
        // account's subscription. If the account has NO active subscription (fully
        // canceled/expired - an active Free plan still counts as active), revoke the
        // link so the CE clears its cloud state on the resulting 410 GONE. Without this,
        // a downgrade/cancel left the link ACTIVE indefinitely and the only cleanup was
        // the 90-day liveness sweep (which never fires while the install keeps beating).
        if (subscriptionRepository.findActiveByUserId(callerUserId).isEmpty()) {
            log.info("Heartbeat revoking link installId={} userId={} - no active subscription (entitlement lost)",
                    installId, callerUserId);
            ceLinkService.adminRevoke(installId, CeLink.RevokeReason.SYSTEM, null, RequestAuditContext.none());
            return Outcome.REVOKED;
        }

        IpHashService.HashResult fresh = ipHashService.hashWithCurrent(installId, clientIp);
        Instant now = Instant.now();

        CeLinkHeartbeat existing = heartbeatRepository.findById(installId).orElse(null);
        boolean firstSeen = existing == null;
        boolean ipChanged;

        if (firstSeen) {
            // No prior IP to compare against - emit a NETWORK_CHANGE (= "first seen")
            // so the audit table has at least one row per install.
            ipChanged = true;
            existing = new CeLinkHeartbeat(installId, now, fresh.hash(), fresh.keyVersion(), ceVersion);
        } else {
            // Compare under the PRIOR row's key_version, not the current one - rotation-safe.
            boolean sameIp = ipHashService.matches(installId, clientIp,
                    existing.getLastSeenIpHash(), existing.getKeyVersion());
            ipChanged = !sameIp;
            existing.applyHeartbeat(now, fresh.hash(), fresh.keyVersion(), ceVersion);
        }

        // §1 #27 cadence - emit audit on IP change OR 24h elapsed OR threshold reached.
        Instant lastAudit = existing.getLastAuditedAt();
        boolean stalenessDue = lastAudit == null || Duration.between(lastAudit, now).compareTo(AUDIT_INTERVAL) >= 0;
        boolean countDue = existing.getHeartbeatCountSinceAudit() >= AUDIT_CALL_THRESHOLD;
        boolean emitAudit = ipChanged || stalenessDue || countDue;

        if (emitAudit) {
            CeLinkAudit.Event event = ipChanged ? CeLinkAudit.Event.NETWORK_CHANGE : CeLinkAudit.Event.HEARTBEAT;
            String reason = ipChanged ? "ip_change" : (stalenessDue ? "interval_24h" : "count_threshold");
            auditService.record(
                    installId,
                    callerUserId,
                    CeLinkAudit.ActorRole.OWNER,
                    event,
                    fresh.keyVersion(),
                    fresh.hash(),
                    null,
                    Map.of("reason", reason, "ce_version", ceVersion)
            );
            existing.recordAuditEmission(now);
        }

        heartbeatRepository.saveAndFlush(existing);
        log.debug("Heartbeat installId={} userId={} ipChanged={} emitAudit={}",
                installId, callerUserId, ipChanged, emitAudit);
        return Outcome.OK;
    }

    /** Result of one heartbeat call. Mapped 1:1 to HTTP status by the controller. */
    public enum Outcome {
        /** Heartbeat persisted. → 204 No Content. */
        OK,
        /** install_id is not in caller's namespace. → 404 (no enumeration oracle). */
        NOT_FOUND,
        /** Link exists but was revoked. → 410 GONE so CE stops heartbeating. */
        REVOKED
    }
}
