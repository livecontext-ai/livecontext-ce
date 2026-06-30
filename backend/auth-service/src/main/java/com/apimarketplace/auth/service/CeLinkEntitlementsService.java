package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CeLink;
import com.apimarketplace.auth.dto.CeLinkEntitlements;
import com.apimarketplace.auth.repository.CeLinkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Resolves the plan entitlements of the cloud account a CE install is bound to.
 *
 * <p>Tranche&nbsp;1 of the CE&nbsp;&#8596;&nbsp;Cloud pricing delegation. Runs on the
 * CLOUD side, where the bound user and their subscription live. A CE install
 * fetches the result over the ce-link channel and applies the matching plan
 * locally, so the cloud subscription drives what the self-hosted install can do.
 *
 * <p>Read-only and side-effect free: it never mutates billing state. The CE
 * never inherits a plan for an unknown or revoked install (returns
 * {@link CeLinkEntitlements#none()}), so a stale binding can't silently grant
 * paid features.
 */
@Service
public class CeLinkEntitlementsService {

    private final CeLinkRepository ceLinkRepository;
    private final PlanResolutionService planResolutionService;

    public CeLinkEntitlementsService(CeLinkRepository ceLinkRepository,
                                     PlanResolutionService planResolutionService) {
        this.ceLinkRepository = ceLinkRepository;
        this.planResolutionService = planResolutionService;
    }

    /**
     * Plan entitlements for the cloud account bound to {@code installId}, scoped
     * to {@code callerUserId} so one tenant can never read another's plan.
     * Returns {@link CeLinkEntitlements#none()} when the install is unknown,
     * revoked, or not owned by the caller.
     */
    @Transactional(readOnly = true)
    public CeLinkEntitlements entitlementsForCaller(Long callerUserId, UUID installId) {
        if (callerUserId == null || installId == null) {
            return CeLinkEntitlements.none();
        }
        return ceLinkRepository.findById(installId)
                .filter(CeLink::isActive)
                .filter(link -> callerUserId.equals(link.getUserId()))
                .map(link -> {
                    PlanResolutionService.ActiveOrgEntitlement gov =
                            planResolutionService.resolveActiveOrgEntitlement(link.getUserId());
                    return new CeLinkEntitlements(
                            governingPlanCode(gov.planCode()),
                            link.getUserId(),
                            gov.creditTierIndex(),
                            gov.cadence());
                })
                .orElseGet(CeLinkEntitlements::none);
    }

    /**
     * The plan that GOVERNS the bound cloud account - the tier of the user's active/default
     * workspace OWNER, exactly what the cloud's {@code X-User-Plan} / sidebar resolves
     * ({@link PlanResolutionService#resolveActiveOrgEntitlement}). A self-hosted install must inherit
     * the SAME plan the cloud account operates under, so a TEAM-workspace member reads as TEAM - not
     * "no subscription". The previous {@code planLimitService.getPlanCode(userId)} was a per-user
     * resolution that "never resolves through orgs", so it returned {@code __NONE__} for a user whose
     * Team comes from a workspace, surfacing as a literal "None" in the CE UI.
     *
     * <p>Maps {@code FREE}/blank → {@link PlanLimitService#NO_SUBSCRIPTION}, preserving the prior
     * contract so {@code EffectivePlanResolver} keeps the CE's own local plan rather than pinning it
     * to FREE.
     */
    private static String governingPlanCode(String tier) {
        return (tier == null || "FREE".equals(tier))
                ? PlanLimitService.NO_SUBSCRIPTION
                : tier;
    }
}
