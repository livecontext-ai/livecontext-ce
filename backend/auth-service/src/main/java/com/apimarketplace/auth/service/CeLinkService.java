package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CeLink;
import com.apimarketplace.auth.domain.CeLinkAudit;
import com.apimarketplace.auth.domain.CeLinkHeartbeat;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.CeLinkRegisterResponse;
import com.apimarketplace.auth.dto.CeLinkSummary;
import com.apimarketplace.auth.repository.CeLinkHeartbeatRepository;
import com.apimarketplace.auth.repository.CeLinkRepository;
import com.apimarketplace.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Business logic for ce-link CRUD endpoints (doc §3.2, §3.3).
 *
 * <p><b>Register flow</b> ({@link #register}):
 * <ol>
 *   <li>If install_id row exists:
 *     <ul>
 *       <li>same user, ACTIVE → 201 idempotent (return existing scopes)</li>
 *       <li>same user, REVOKED → 409 ALREADY_BOUND with caller's masked email
 *           (signal: they need to reset via §3.4 to get a new install_id)</li>
 *       <li>different user → 409 ALREADY_BOUND with {@code boundToEmail=null}
 *           (no info leak - closes the squat-confirmation oracle, doc §1 #16)</li>
 *     </ul>
 *   </li>
 *   <li>Else: INSERT new row + REGISTER audit + return 201.</li>
 * </ol>
 *
 * <p>Constant-time response across all 201/409 branches is enforced by the
 * 400ms {@code CeLinkConstantTimeFilter} (PR3d) - service returns the right
 * shape, filter pads to the budget.
 *
 * <p><b>Revoke</b> ({@link #revoke}): ownership-scoped find (returns 404 if
 * the install_id doesn't belong to the caller). Idempotent on already-revoked.
 * KC admin-API logout call lives in PR3c {@code KcAdminLogoutService}.
 *
 * <p><b>Mine</b> ({@link #mine}): paginated list of caller's ACTIVE installs.
 * Heartbeat join (last_seen_at, network-change pill) added in PR3b.
 */
@Service
@ConditionalOnProperty(name = "auth.mode", havingValue = "keycloak", matchIfMissing = false)
public class CeLinkService {

    private static final Logger log = LoggerFactory.getLogger(CeLinkService.class);

    private final CeLinkRepository repository;
    private final CeLinkHeartbeatRepository heartbeatRepository;
    private final UserRepository userRepository;
    private final CeLinkAuditService auditService;
    private final CeLinkActiveRowCache activeRowCache;
    private final CeLinkActiveRowCachePublisher cachePublisher;
    private final ApplicationEventPublisher eventPublisher;

    public CeLinkService(CeLinkRepository repository,
                         CeLinkHeartbeatRepository heartbeatRepository,
                         UserRepository userRepository,
                         CeLinkAuditService auditService,
                         CeLinkActiveRowCache activeRowCache,
                         CeLinkActiveRowCachePublisher cachePublisher,
                         ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.heartbeatRepository = heartbeatRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.activeRowCache = activeRowCache;
        this.cachePublisher = cachePublisher;
        this.eventPublisher = eventPublisher;
    }

    /** Drop the local cache + broadcast to sibling replicas. Single chokepoint. */
    private void invalidateActiveRowCache(Long userId) {
        activeRowCache.invalidate(userId);
        cachePublisher.broadcastInvalidate(userId);
    }

    /**
     * Register an install for the caller. Idempotent on (install_id, user_id).
     * See class Javadoc for branch matrix.
     */
    @Transactional
    public CeLinkRegisterResponse register(Long callerUserId, UUID installId, String ceVersion, String label,
                                           RequestAuditContext audit) {
        Optional<CeLink> existing = repository.findById(installId);
        if (existing.isPresent()) {
            CeLink row = existing.get();
            if (row.getUserId().equals(callerUserId)) {
                if (row.getStatus() == CeLink.Status.ACTIVE) {
                    // Idempotent retry: return current scopes, no audit row (not a state change).
                    log.debug("CeLink register idempotent for installId={} userId={}", installId, callerUserId);
                    return CeLinkRegisterResponse.ok(row.getScopes());
                }
                // Same user, prior link revoked - benign signal (their CE will reset).
                // No audit row: the existence of a (caller, install) pair was already
                // established by the REGISTER + REVOKE rows for this install - the
                // 409 is informational, not a security event.
                return CeLinkRegisterResponse.alreadyBound(maskedEmailFor(callerUserId));
            }
            // Different user owns this install_id. Audit it as
            // SUSPECTED_CROSS_USER_RESET (doc §7 threat) - the trail lives in
            // auth.ce_link_audit, not just app logs (rotated, lower retention).
            // Metadata is bounded (no PII): just the install_id (already keyed)
            // and the owning user's id (numeric, internal).
            log.warn("CeLink register collision: installId={} owned by userId={} attempted by userId={}",
                    installId, row.getUserId(), callerUserId);
            auditService.record(
                    installId,
                    callerUserId,
                    CeLinkAudit.ActorRole.OWNER,
                    CeLinkAudit.Event.SUSPECTED_CROSS_USER_RESET,
                    audit.keyVersion(),
                    audit.ipHash(),
                    audit.userAgent(),
                    Map.of("owned_by_user_id", row.getUserId())
            );
            // Notify the legitimate owner via SquatRecoveryService (one-time HMAC
            // link + email, rate-limited 5/h per victim per doc §1 #41).
            eventPublisher.publishEvent(new CeLinkSquatDetectedEvent(
                    row.getUserId(), callerUserId, installId));
            return CeLinkRegisterResponse.alreadyBound(null);
        }

        // New register.
        CeLink fresh = new CeLink(installId, callerUserId,
                label == null || label.isBlank() ? "CE install" : label);
        repository.save(fresh);

        auditService.record(
                installId,
                callerUserId,
                CeLinkAudit.ActorRole.OWNER,
                CeLinkAudit.Event.REGISTER,
                audit.keyVersion(),
                audit.ipHash(),
                audit.userAgent(),
                Map.of("ce_version", ceVersion, "label", fresh.getLabel())
        );

        invalidateActiveRowCache(callerUserId);
        log.info("CeLink registered: installId={} userId={} ce_version={}", installId, callerUserId, ceVersion);
        return CeLinkRegisterResponse.ok(fresh.getScopes());
    }

    /**
     * List caller's ACTIVE installs (paginated). Each summary is enriched with
     * its heartbeat row ({@code last_seen_at}, {@code last_seen_ce_version})
     * via a single bulk {@code findAllById} fetch - one extra query per page,
     * indexed by PK, no JPQL projection complexity.
     */
    @Transactional(readOnly = true)
    public Page<CeLinkSummary> mine(Long callerUserId, Pageable pageable) {
        Page<CeLink> page = repository.findByUserIdAndStatus(callerUserId, CeLink.Status.ACTIVE, pageable);
        if (page.isEmpty()) {
            return page.map(CeLinkSummary::of);
        }
        List<UUID> installIds = page.getContent().stream().map(CeLink::getInstallId).toList();
        Map<UUID, CeLinkHeartbeat> heartbeatsById = heartbeatRepository.findAllById(installIds).stream()
                .collect(Collectors.toMap(CeLinkHeartbeat::getInstallId, Function.identity(),
                        (a, b) -> a, HashMap::new));
        return page.map(link -> CeLinkSummary.of(link, heartbeatsById.get(link.getInstallId())));
    }

    /**
     * Admin-flavored revoke for the squat-recovery flow + future ops tooling
     * (doc §1 #41). Bypasses caller-ownership scoping - the install_id alone
     * targets the row. The {@code reason} is stamped on the audit row so
     * forensics can distinguish USER-initiated revokes from SYSTEM/SQUAT_RECOVERY.
     *
     * <p>Returns true if the row was found and transitioned (or was already
     * REVOKED - idempotent), false if the install_id is unknown.
     */
    @Transactional
    public boolean adminRevoke(UUID installId, CeLink.RevokeReason reason, Long actingUserId,
                               RequestAuditContext audit) {
        Optional<CeLink> row = repository.findById(installId);
        if (row.isEmpty()) {
            return false;
        }
        CeLink link = row.get();
        if (link.getStatus() == CeLink.Status.REVOKED) {
            return true;   // idempotent
        }
        link.revoke(reason, actingUserId);
        repository.save(link);
        auditService.record(
                installId,
                actingUserId,
                CeLinkAudit.ActorRole.SYSTEM,
                CeLinkAudit.Event.REVOKE,
                audit.keyVersion(),
                audit.ipHash(),
                audit.userAgent(),
                Map.of("reason", reason.name())
        );
        invalidateActiveRowCache(link.getUserId());
        eventPublisher.publishEvent(new CeLinkRevokedEvent(link.getUserId(), installId));
        log.info("CeLink adminRevoke: installId={} reason={} actingUserId={}", installId, reason, actingUserId);
        return true;
    }

    /**
     * Revoke an install. Ownership-scoped - returns false if the install_id
     * doesn't belong to the caller (controller returns 404 - no enumeration
     * oracle). Idempotent on already-revoked.
     */
    @Transactional
    public boolean revoke(Long callerUserId, UUID installId, RequestAuditContext audit) {
        Optional<CeLink> row = repository.findByInstallIdAndUserId(installId, callerUserId);
        if (row.isEmpty()) {
            return false; // 404 - install not found in caller's namespace
        }
        CeLink link = row.get();
        if (link.getStatus() == CeLink.Status.REVOKED) {
            return true;  // idempotent - already revoked, no audit (not a state change)
        }
        link.revoke(CeLink.RevokeReason.USER, callerUserId);
        repository.save(link);

        auditService.record(
                installId,
                callerUserId,
                CeLinkAudit.ActorRole.OWNER,
                CeLinkAudit.Event.REVOKE,
                audit.keyVersion(),
                audit.ipHash(),
                audit.userAgent(),
                Map.of("reason", "USER")
        );

        invalidateActiveRowCache(callerUserId);
        // KC session logout fires from KcAdminLogoutService via @TransactionalEventListener
        // AFTER_COMMIT - keeps the user-facing 204 response off the KC admin-API latency
        // budget and ensures we never logout sessions for a revoke that ended up rolling back.
        eventPublisher.publishEvent(new CeLinkRevokedEvent(callerUserId, installId));
        log.info("CeLink revoked: installId={} userId={}", installId, callerUserId);
        return true;
    }

    /**
     * §1 #4 mandatory-header gate: does this caller have any ACTIVE ce_link?
     * Caffeine-cached (TTL configured by {@code cloud-link.active-link-cache.ttl-seconds}).
     * Local invalidate + Redis broadcast via {@link CeLinkActiveRowCachePublisher} keep
     * cross-replica staleness sub-second; TTL is the fallback when Redis is unavailable.
     */
    @Transactional(readOnly = true)
    public boolean userHasAnyActiveLink(Long userId) {
        return activeRowCache.get(userId, repository::userHasAnyActiveLink);
    }

    @Transactional(readOnly = true)
    public boolean userOwnsActiveLink(Long userId, UUID installId) {
        if (userId == null || installId == null) {
            return false;
        }
        return repository.findByInstallIdAndUserId(installId, userId)
                .map(CeLink::isActive)
                .orElse(false);
    }

    /**
     * Return the caller's email masked as {@code "lu***@gmail.com"} for use
     * in 409 ALREADY_BOUND responses. Constant masking format (first 2 chars
     * + asterisks + domain) so a future change reaches all callsites.
     */
    private String maskedEmailFor(Long userId) {
        return userRepository.findById(userId)
                .map(User::getEmail)
                .map(CeLinkService::maskEmail)
                .orElse(null);
    }

    static String maskEmail(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) return "*".repeat(local.length()) + domain;
        return local.substring(0, 2) + "***" + domain;
    }
}
