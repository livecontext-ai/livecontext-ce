package com.apimarketplace.auth.bridge.service;

import com.apimarketplace.auth.bridge.domain.BridgeAccessModels.AccessDecision;
import com.apimarketplace.auth.bridge.domain.BridgeAccessModels.AccessMode;
import com.apimarketplace.auth.bridge.domain.BridgeAccessModels.BridgeAccessAllowlistEntry;
import com.apimarketplace.auth.bridge.domain.BridgeAccessModels.BridgeAccessPolicy;
import com.apimarketplace.auth.bridge.domain.BridgeAccessModels.BridgeAccessView;
import com.apimarketplace.auth.bridge.domain.BridgeAccessModels.UpdatePolicyRequest;
import com.apimarketplace.auth.bridge.domain.BridgeAccessModels.UsageStat;
import com.apimarketplace.auth.bridge.repository.BridgeAccessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Business logic for CLI bridge access control.
 *
 * <p>Used by two callers:
 * <ul>
 *   <li>Admin REST ({@code BridgeAccessController}) - CRUD on policies and
 *       the allowlist.</li>
 *   <li>Internal REST ({@code InternalBridgeAccessController}) - the
 *       {@code checkAccess(...)} decision, called by shared-agent-lib on
 *       every bridge-provider dispatch.</li>
 * </ul>
 */
@Service
public class BridgeAccessService {

    private static final Logger log = LoggerFactory.getLogger(BridgeAccessService.class);

    private final BridgeAccessRepository repository;

    public BridgeAccessService(BridgeAccessRepository repository) {
        this.repository = repository;
    }

    // ---------------------------------------------------------------------
    // Admin-facing
    // ---------------------------------------------------------------------

    public List<BridgeAccessPolicy> listPolicies() {
        return repository.findAllPolicies();
    }

    public BridgeAccessView getPolicyView(String bridgeProvider) {
        BridgeAccessPolicy policy = repository.findByBridgeProvider(bridgeProvider)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown bridge provider: " + bridgeProvider));
        List<BridgeAccessAllowlistEntry> allowlist = repository.findAllowlist(policy.id());
        List<UsageStat> usage = repository.findRecentUsage(bridgeProvider, 7, 50);
        return new BridgeAccessView(policy, allowlist, usage);
    }

    @Transactional
    public BridgeAccessPolicy updatePolicy(String bridgeProvider,
                                           UpdatePolicyRequest request,
                                           String updatedBy) {
        BridgeAccessPolicy existing = repository.findByBridgeProvider(bridgeProvider)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown bridge provider: " + bridgeProvider));

        AccessMode mode = request.accessMode() != null ? request.accessMode() : existing.accessMode();
        Integer daily = request.maxRequestsPerUserPerDay();

        if (daily != null && daily <= 0) {
            throw new IllegalArgumentException("max_requests_per_user_per_day must be > 0 or null");
        }

        repository.upsertPolicy(bridgeProvider, mode, daily, updatedBy);
        log.info("Bridge access policy updated: bridge={} mode={} daily={} by={}",
                bridgeProvider, mode, daily, updatedBy);
        return repository.findByBridgeProvider(bridgeProvider).orElseThrow();
    }

    @Transactional
    public BridgeAccessAllowlistEntry grantAccess(String bridgeProvider,
                                                  String userId,
                                                  String grantedBy) {
        BridgeAccessPolicy policy = repository.findByBridgeProvider(bridgeProvider)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown bridge provider: " + bridgeProvider));
        repository.addToAllowlist(policy.id(), userId, grantedBy);
        log.info("Bridge access granted: bridge={} user={} by={}", bridgeProvider, userId, grantedBy);
        return new BridgeAccessAllowlistEntry(policy.id(), userId, java.time.Instant.now(), grantedBy);
    }

    @Transactional
    public boolean revokeAccess(String bridgeProvider, String userId) {
        BridgeAccessPolicy policy = repository.findByBridgeProvider(bridgeProvider)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown bridge provider: " + bridgeProvider));
        boolean removed = repository.removeFromAllowlist(policy.id(), userId);
        if (removed) {
            log.info("Bridge access revoked: bridge={} user={}", bridgeProvider, userId);
        }
        return removed;
    }

    // ---------------------------------------------------------------------
    // Internal - called by shared-agent-lib's BridgeAccessGuard
    // ---------------------------------------------------------------------

    /**
     * Authoritative allow/deny decision, counter side-effect guarded by
     * {@code incrementUsage = true}.
     *
     * @param userId         caller's stable user id (from X-User-ID header)
     * @param isAdmin        true iff the caller has the ADMIN role
     * @param bridgeProvider e.g. {@code claude-code}
     * @param incrementUsage true → increment today's counter after allow;
     *                       false → evaluate only (for UI catalog filter).
     */
    @Transactional
    public AccessDecision checkAccess(String userId,
                                      boolean isAdmin,
                                      String bridgeProvider,
                                      boolean incrementUsage) {
        Optional<BridgeAccessPolicy> opt = repository.findByBridgeProvider(bridgeProvider);
        if (opt.isEmpty()) {
            return AccessDecision.deny(bridgeProvider, AccessDecision.REASON_UNKNOWN_BRIDGE);
        }
        BridgeAccessPolicy policy = opt.get();

        switch (policy.accessMode()) {
            case DISABLED -> {
                return AccessDecision.deny(bridgeProvider, AccessDecision.REASON_DISABLED);
            }
            case ADMIN_ONLY -> {
                if (!isAdmin) {
                    return AccessDecision.deny(bridgeProvider, AccessDecision.REASON_NOT_ADMIN);
                }
            }
            case ALLOWLIST -> {
                if (!repository.isUserAllowlisted(policy.id(), userId)) {
                    return AccessDecision.deny(bridgeProvider, AccessDecision.REASON_NOT_ALLOWLISTED);
                }
            }
            case ALL_USERS -> {
                // no identity gate
            }
        }

        // Quota check. daily == null → unlimited.
        Integer daily = policy.maxRequestsPerUserPerDay();
        Integer remaining = null;
        if (daily != null) {
            int usedSoFar = repository.getUsageToday(userId, bridgeProvider);
            if (usedSoFar >= daily) {
                return AccessDecision.deny(bridgeProvider, AccessDecision.REASON_QUOTA_EXHAUSTED);
            }
            remaining = daily - usedSoFar - (incrementUsage ? 1 : 0);
        }

        if (incrementUsage) {
            int newCount = repository.incrementUsage(userId, bridgeProvider);
            if (daily != null && newCount > daily) {
                // Concurrent increment raced past the cap - undo by deny.
                // Keeping the row (not compensating) is fine: next call sees used >= daily.
                log.warn("Bridge quota race: user={} bridge={} count={} daily={}",
                        userId, bridgeProvider, newCount, daily);
                return AccessDecision.deny(bridgeProvider, AccessDecision.REASON_QUOTA_EXHAUSTED);
            }
            if (daily != null) {
                remaining = daily - newCount;
            }
        }

        return AccessDecision.allow(bridgeProvider, remaining);
    }
}
