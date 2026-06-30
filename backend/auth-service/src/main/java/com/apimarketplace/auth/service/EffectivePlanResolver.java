package com.apimarketplace.auth.service;

/**
 * Decides which plan governs a CE (self-hosted) install: the bound cloud
 * account's plan when the install is linked to the cloud with CLOUD as its
 * billing/LLM source, otherwise the install's own local plan.
 *
 * <p>Tranche 4 (decision core) of the CE&#8596;Cloud pricing delegation. Kept as
 * pure logic - the cloud plan code is supplied by the caller (resolved over the
 * ce-link via {@code CloudLinkService.fetchCloudPlanCode}), so this carries no
 * cross-module dependency and is exhaustively unit-testable. Wiring it into the
 * live {@code PlanLimitService} resolution is a separate, review-gated step
 * because that path gates paid features.
 *
 * <p>Fail-safe: a linked-but-unknown cloud plan ({@code null} /
 * {@link PlanLimitService#NO_SUBSCRIPTION}) falls back to the local plan rather
 * than stripping the install of its entitlements.
 */
public final class EffectivePlanResolver {

    private EffectivePlanResolver() {}

    /**
     * @param localPlanCode the install's own plan code (never {@code null}; use
     *                      {@link PlanLimitService#NO_SUBSCRIPTION} for "none")
     * @param cloudLinked   whether this install is bound to a cloud account
     * @param cloudSource   whether the install's source is CLOUD (vs BYOK)
     * @param cloudPlanCode the bound cloud account's plan code, or {@code null}
     * @return the plan code that should govern this install
     */
    public static String resolve(String localPlanCode,
                                 boolean cloudLinked,
                                 boolean cloudSource,
                                 String cloudPlanCode) {
        if (cloudLinked
                && cloudSource
                && cloudPlanCode != null
                && !cloudPlanCode.isBlank()
                && !PlanLimitService.NO_SUBSCRIPTION.equals(cloudPlanCode)) {
            return cloudPlanCode;
        }
        return localPlanCode;
    }
}
