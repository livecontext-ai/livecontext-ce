package com.apimarketplace.auth.dto;

import com.apimarketplace.auth.service.PlanLimitService;

/**
 * Plan entitlements of the cloud account a CE (self-hosted) install is bound to.
 *
 * <p>Part of the CE&nbsp;&#8596;&nbsp;Cloud pricing delegation: the cloud account's
 * subscription is the single source of truth, and a linked self-hosted install
 * inherits it. The CE reads {@code planCode} and resolves the concrete limits
 * from its own (identically-seeded) {@code Plan} table, so paying on the cloud
 * unlocks the same features on the CE install &mdash; no separate billing in CE.
 *
 * @param planCode        the bound account's active plan code (FREE/STARTER/PRO/TEAM/&#8230;),
 *                        or {@link PlanLimitService#NO_SUBSCRIPTION} when none.
 * @param userId          the bound cloud user id, or {@code null} when unbound.
 * @param creditTierIndex the governing subscription's credit-tier index (0 when none) - lets a
 *                        linked CE align its pricing credit slider with the cloud account.
 * @param cadence         the governing subscription's billing cadence (monthly/yearly), or
 *                        {@code null} when none.
 */
public record CeLinkEntitlements(String planCode, Long userId, int creditTierIndex, String cadence) {

    /** True when the bound cloud account has an active (non-free) subscription. */
    public boolean hasSubscription() {
        return planCode != null && !PlanLimitService.NO_SUBSCRIPTION.equals(planCode);
    }

    /**
     * No bound account / no subscription. A CE that gets this falls back to its
     * own local defaults instead of inheriting a stale or foreign plan.
     */
    public static CeLinkEntitlements none() {
        return new CeLinkEntitlements(PlanLimitService.NO_SUBSCRIPTION, null, 0, null);
    }
}
