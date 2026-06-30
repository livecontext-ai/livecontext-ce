package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.license.EnterpriseLicenseResourceLimit;
import com.apimarketplace.auth.service.license.EnterpriseLicenseService;
import com.apimarketplace.common.plan.CloudPlanAccess;
import com.apimarketplace.common.web.AppEditionProvider;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Resolves a user's per-plan resource creation limit.
 * Self-Hosted Enterprise uses the signed local license before subscription lookup.
 *
 * <p>Lookup chain: providerId (Keycloak sub) → User row → active Subscription
 * → Plan → {@code Plan.getResourceLimit(resourceType)}.
 *
 * <p>Results are cached in-process for 60 seconds (Caffeine). The cache is
 * acceptable to be slightly stale after a plan upgrade - the user's next API
 * call within the next minute may still see the old limit, then it refreshes.
 *
 * <p>{@code null} from {@link #getLimit(String, String)} means <em>unlimited</em>
 * (consistent with the existing {@code Plan.included_*} pattern). Callers must
 * treat null as "no limit".
 */
@Service
public class PlanLimitService {

    private static final Logger log = LoggerFactory.getLogger(PlanLimitService.class);

    /** Sentinel: user has no active subscription. Treated as FREE-equivalent (default block). */
    public static final String NO_SUBSCRIPTION = "__NONE__";

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final AppEditionProvider editionProvider;
    private final EnterpriseLicenseService enterpriseLicenseService;

    /**
     * CE↔Cloud pricing delegation: when present (CE / {@code marketplace.mode=remote}),
     * a CLOUD-sourced install's plan is governed by the bound cloud account. Optional -
     * {@code null} in the cloud deployment, where the local plan is authoritative.
     */
    private CloudPlanAccess cloudPlanAccess;

    @Autowired(required = false)
    public void setCloudPlanAccess(CloudPlanAccess cloudPlanAccess) {
        this.cloudPlanAccess = cloudPlanAccess;
        if (cloudPlanAccess != null) {
            log.info("CloudPlanAccess wired - CE plan resolution delegates to the bound cloud account");
        }
    }

    /** Cache key: providerId + ":" + resourceType. Value: limit (-1 = unlimited sentinel). */
    private final Cache<String, Integer> limitCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(10_000)
            .build();

    /** Cache key: providerId. Value: planCode (or NO_SUBSCRIPTION). */
    private final Cache<String, String> planCodeCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(10_000)
            .build();

    public PlanLimitService(UserRepository userRepository,
                             SubscriptionRepository subscriptionRepository,
                             AppEditionProvider editionProvider,
                             EnterpriseLicenseService enterpriseLicenseService) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.editionProvider = editionProvider;
        this.enterpriseLicenseService = enterpriseLicenseService;
    }

    /**
     * Returns the resource limit for the given user and resource type.
     *
     * @return the limit, or {@code null} if unlimited.
     */
    public Integer getLimit(String providerId, String resourceType) {
        if (providerId == null || providerId.isBlank() || resourceType == null) {
            return null;
        }
        if (editionProvider.isSelfHostedEnterprise()) {
            EnterpriseLicenseResourceLimit licenseLimit = enterpriseLicenseService.resolveResourceLimit(resourceType);
            if (!licenseLimit.licensed()) {
                return 0;
            }
            return licenseLimit.limit();
        }
        String key = providerId + ":" + resourceType.toUpperCase();
        Integer cached = limitCache.getIfPresent(key);
        if (cached != null) {
            return cached == Integer.MIN_VALUE ? null : cached;
        }
        Integer fresh = loadLimit(providerId, resourceType);
        // Use MIN_VALUE as in-cache representation for null (unlimited),
        // because Caffeine cannot store null values.
        limitCache.put(key, fresh == null ? Integer.MIN_VALUE : fresh);
        return fresh;
    }

    /**
     * Returns the plan code currently active for the user, or {@link #NO_SUBSCRIPTION}.
     */
    public String getPlanCode(String providerId) {
        if (editionProvider.isSelfHostedEnterprise()) {
            var status = enterpriseLicenseService.currentStatus();
            return status.active() ? status.planCode() : NO_SUBSCRIPTION;
        }
        if (providerId == null || providerId.isBlank()) {
            return NO_SUBSCRIPTION;
        }
        return planCodeCache.get(providerId, this::loadPlanCode);
    }

    /**
     * Invalidate cached entries for a user. Call after subscription changes
     * (upgrade, downgrade, cancel) so the next read sees the new plan.
     */
    public void invalidate(String providerId) {
        if (providerId == null) return;
        planCodeCache.invalidate(providerId);
        // Limit cache keys are prefixed with providerId
        limitCache.asMap().keySet().removeIf(k -> k.startsWith(providerId + ":"));
    }

    // ===== private helpers =====

    private Integer loadLimit(String providerId, String resourceType) {
        Optional<User> userOpt = userRepository.findByProviderId(providerId);
        if (userOpt.isEmpty()) {
            log.debug("No user found for providerId={}, treating as no-limit", providerId);
            return null;
        }
        Optional<Subscription> subOpt = subscriptionRepository.findActiveByUserId(userOpt.get().getId());
        if (subOpt.isEmpty()) {
            log.debug("No active subscription for user={}, treating as no-limit", providerId);
            return null;
        }
        Plan plan = subOpt.get().getPlan();
        if (plan == null) {
            return null;
        }
        return plan.getResourceLimit(resourceType);
    }

    private String loadPlanCode(String providerId) {
        Optional<User> userOpt = userRepository.findByProviderId(providerId);
        if (userOpt.isEmpty()) {
            return NO_SUBSCRIPTION;
        }
        Long userId = userOpt.get().getId();
        String localPlan = subscriptionRepository.findActiveByUserId(userId)
                .map(s -> s.getPlan() != null ? s.getPlan().getCode() : NO_SUBSCRIPTION)
                .orElse(NO_SUBSCRIPTION);

        // CE↔Cloud delegation: a CLOUD-sourced linked install is governed by the bound
        // cloud account's plan, so paying on the cloud unlocks the plan's features here.
        // cloudPlanAccess is null in the cloud deployment (no-op). governingPlanCode only
        // returns a value when the install is CLOUD-sourced; EffectivePlanResolver fails
        // safe to the local plan when it is absent/unknown, so a transient cloud outage
        // never strips entitlements.
        if (cloudPlanAccess != null) {
            String cloudPlan = cloudPlanAccess.governingPlanCode(userId).orElse(null);
            return EffectivePlanResolver.resolve(localPlan, cloudPlan != null, true, cloudPlan);
        }
        return localPlan;
    }
}
