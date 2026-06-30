package com.apimarketplace.auth.service;

import com.apimarketplace.auth.billing.CreditTierConstants;
import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.common.plan.CloudPlanAccess;
import com.apimarketplace.common.web.TenantResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Milestone-2 (PR6) of the org/membership redesign - extract the
 * "what plan applies right now?" lookup into a dedicated, public service.
 *
 * <p>Three resolution shapes that {@code UserResolutionService} + the
 * billing gates need to distinguish:
 *
 * <ol>
 *   <li>{@link #resolveBillingPlan(Long)} - the user's OWN subscription
 *       plan. Stays per-user (Q5=a Stripe stays per-user). Used by the
 *       /api/billing/me read endpoint and the personal-org context.
 *       Always returns a Plan code, defaulting to FREE if no active
 *       subscription row exists.</li>
 *   <li>{@link #resolveOrgOwnerPlan(Organization)} - the plan of the
 *       org's OWNER. Used today by {@code OrganizationMemberService}
 *       to gate {@code supportsTeam()} and {@code maxMembers}. Public
 *       (was {@code OrganizationMemberService.getOwnerPlan} private).</li>
 *   <li>{@link #resolveActiveOrgTier(Long)} - the plan of the user's
 *       CURRENT (default) workspace's owner. This is what the FE plan
 *       badge should follow (Q1=b - plan follows current workspace).
 *       Returned as a dual-write {@code activeOrgPlan} field in
 *       UserResolutionResponse (PR6 dual-write); PR7 cutover will flip
 *       the gateway's {@code X-User-Plan} header to read this field
 *       instead of the per-user billingPlan.</li>
 * </ol>
 *
 * <p>Dual-write design: in PR6, the existing
 * {@code UserResolutionResponse.plan} keeps the user's billingPlan value
 * (no behaviour change). A new {@code billingPlan} field carries the
 * same value (explicit naming), and a new {@code activeOrgPlan} field
 * carries the active-workspace plan. PR7 cutover swaps which field the
 * gateway / frontend consume - full reversal via a config kill-switch.
 */
@Service
public class PlanResolutionService {

    private static final String FREE_PLAN_CODE = "FREE";

    private final SubscriptionRepository subscriptionRepository;
    private final OrganizationMemberRepository memberRepository;

    // CE↔Cloud pricing delegation: when present (CE / marketplace.mode=remote), a CLOUD-sourced
    // install's owner plan follows the bound cloud account, governing team capability exactly
    // like cloud. Both optional/field-injected so the existing 2-arg constructor keeps compiling
    // for unit tests; null in the cloud deployment → owner-plan resolution stays purely local.
    @Autowired(required = false)
    private CloudPlanAccess cloudPlanAccess;

    @Autowired(required = false)
    private PlanRepository planRepository;

    public PlanResolutionService(SubscriptionRepository subscriptionRepository,
                                 OrganizationMemberRepository memberRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.memberRepository = memberRepository;
    }

    /**
     * True when the organization's OWNER is on a team-capable plan
     * (TEAM / ENTERPRISE_*). Reuses the canonical {@link #resolveOrgOwnerPlan}
     * + {@link Plan#supportsTeam()} - the SAME path the invite gate uses; no
     * parallel capability logic. Owner without an active subscription → false.
     */
    public boolean orgOwnerSupportsTeam(Organization org) {
        Plan plan = resolveOrgOwnerPlan(org);
        return plan != null && plan.supportsTeam();
    }

    /**
     * Authorization predicate for the "dormant org" model: may this member
     * currently ENTER / act in this org?
     *
     * <ul>
     *   <li>The OWNER → always (a downgraded owner keeps SOLO access to their
     *       org; this ALSO covers every solo user's own personal workspace,
     *       whose membership role is OWNER - so a solo user is never locked
     *       out of their own workspace).</li>
     *   <li>Otherwise (any NON-owner member) → only if the owner is still on a
     *       team plan. When the owner is not team-capable the org is "dormant":
     *       the member keeps the membership (visible, leave-able) but cannot
     *       enter - closing the free team-sharing loophole.</li>
     * </ul>
     *
     * <p>{@code is_personal} is deliberately NOT part of the gate - only the
     * caller's role and the owner's team-capability matter. A blanket
     * personal-org exemption re-opens the loophole, because production personal
     * workspaces (max_members=1) can still carry stray invited members whose
     * owner ≠ member; such a member of a solo-owner workspace is the SAME
     * free-sharing loophole and is blocked too.
     */
    @Transactional(readOnly = true)
    public boolean canMemberActInOrg(OrganizationMember member) {
        if (member == null) return false;
        Organization org = member.getOrganization();
        if (org == null) return false;
        // V311 over-cap pause: a workspace beyond the owner's plan workspace cap
        // is non-enterable for EVERYONE - including the OWNER - until re-upgrade.
        // Checked before the owner short-circuit so a downgraded owner is locked
        // out of their own excess workspaces (the request edge falls back to personal).
        if (org.isPaused()) return false;
        if (member.getRole() == OrganizationRole.OWNER) return true;
        // CE no longer exempts team membership - a non-owner can act only when the owner's
        // (cloud-aware) plan supports team, exactly like cloud.
        return orgOwnerSupportsTeam(org);
    }

    /**
     * Batched "paused" computation for the member's workspace list - returns the
     * set of org ids that are DORMANT for this user: every org where the user is
     * a NON-owner member whose owner no longer supports team. Strict mirror of
     * {@link #canMemberActInOrg} (same role + owner-team-capability rule) so the
     * FE "paused" badge and the gateway's enforcement never diverge. Personal-ness
     * is NOT a factor - a non-owner member of a solo-owner personal workspace is
     * dormant too (closes the personal-org team-sharing loophole). Avoids the
     * per-org N+1 by resolving all candidate owners' plans in ONE query; a CLOUD-governed
     * owner's team capability follows the cloud plan, overriding that local result.
     */
    @Transactional(readOnly = true)
    public Set<UUID> resolvePausedOrgIds(List<OrganizationMember> memberships) {
        if (memberships == null || memberships.isEmpty()) {
            return Set.of();
        }

        Set<UUID> paused = new HashSet<>();

        // (1) V311 stored over-cap pause - applies to EVERY membership, including
        // the OWNER's own workspaces, in ALL editions. A downgraded owner sees
        // (and cannot enter) their excess workspaces; un-paused on re-upgrade by
        // OrganizationService.reconcileWorkspacePauseState.
        for (OrganizationMember m : memberships) {
            Organization org = m.getOrganization();
            if (org != null && org.isPaused()) {
                paused.add(org.getId());
            }
        }

        // (2) Computed "dormant team" pause - a NON-owner member whose owner is no longer
        // team-capable (closes the free team-sharing loophole). Applies in CE too now: team
        // follows the owner's (cloud-aware) plan, exactly like cloud. Batched local lookup
        // avoids the per-org N+1; a CLOUD-governed owner's capability overrides the local result.
        List<OrganizationMember> candidates = memberships.stream()
                .filter(m -> m.getOrganization() != null
                        && m.getRole() != OrganizationRole.OWNER)
                .toList();
        if (!candidates.isEmpty()) {
            Set<Long> ownerIds = candidates.stream()
                    .map(m -> m.getOrganization().getOwner())
                    .filter(Objects::nonNull)
                    .map(User::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            Map<Long, Boolean> ownerSupportsTeam = new HashMap<>();
            if (!ownerIds.isEmpty()) {
                for (Subscription s : subscriptionRepository.findActiveByOwnerUserIds(ownerIds)) {
                    Long ownerId = s.getBillingCustomer().getUser().getId();
                    // ORDER BY createdAt DESC → first row per owner is the most recent.
                    ownerSupportsTeam.putIfAbsent(ownerId, s.getPlan() != null && s.getPlan().supportsTeam());
                }
            }
            // CE↔Cloud: a CLOUD-governed owner's team capability follows the cloud plan,
            // overriding the local subscription (CE/cloud share identical plan rows).
            if (cloudPlanAccess != null && planRepository != null && !ownerIds.isEmpty()) {
                for (Long ownerId : ownerIds) {
                    Plan cloudPlan = cloudPlanAccess.governingPlanCode(ownerId)
                            .flatMap(planRepository::findByCode)
                            .orElse(null);
                    if (cloudPlan != null) {
                        ownerSupportsTeam.put(ownerId, cloudPlan.supportsTeam());
                    }
                }
            }

            for (OrganizationMember m : candidates) {
                User owner = m.getOrganization().getOwner();
                Long ownerId = owner != null ? owner.getId() : null;
                boolean supports = ownerId != null && Boolean.TRUE.equals(ownerSupportsTeam.get(ownerId));
                if (!supports) {
                    paused.add(m.getOrganization().getId());
                }
            }
        }
        return paused;
    }

    /**
     * The user's own subscription plan (the one their Stripe customer is
     * subscribed to). Returns "FREE" when no active subscription row exists.
     * Per Q5=a, Stripe stays per-user - this never resolves through orgs.
     */
    public String resolveBillingPlan(Long userId) {
        if (userId == null) return FREE_PLAN_CODE;
        return subscriptionRepository.findActiveByUserId(userId)
                .map(sub -> sub.getPlan() != null ? sub.getPlan().getCode() : FREE_PLAN_CODE)
                .orElse(FREE_PLAN_CODE);
    }

    /**
     * The plan attached to the organization's OWNER (resolved through
     * {@code Organization.owner}). Used to gate org-level capabilities
     * like {@code supportsTeam()} / {@code maxMembers}.
     */
    public Plan resolveOrgOwnerPlan(Organization org) {
        if (org == null || org.getOwner() == null) return null;
        Long ownerId = org.getOwner().getId();
        Plan cloudGoverned = resolveCloudGovernedPlan(ownerId);
        if (cloudGoverned != null) {
            return cloudGoverned;
        }
        return subscriptionRepository.findActiveByUserId(ownerId)
                .map(Subscription::getPlan)
                .orElse(null);
    }

    /**
     * CE↔Cloud delegation: when this install is linked to a cloud account with CLOUD as its
     * source, the bound account's plan governs team capabilities - resolved locally by code,
     * as CE and cloud share identical plan rows ("pricing is the same in CE as in cloud"). So
     * adding teammates / workspaces requires the cloud plan to support them, exactly like cloud.
     * {@code null} when not cloud-governed (BYOK, unlinked, or the cloud deployment where no
     * {@code CloudPlanAccess} bean exists) so resolution falls back to the local subscription.
     */
    private Plan resolveCloudGovernedPlan(Long ownerId) {
        if (cloudPlanAccess == null || planRepository == null || ownerId == null) {
            return null;
        }
        return cloudPlanAccess.governingPlanCode(ownerId)
                .flatMap(planRepository::findByCode)
                .orElse(null);
    }

    /**
     * The plan of the user's current default workspace's OWNER - i.e. the
     * capabilities tier that should follow the user as they switch
     * workspaces (Q1=b). PR6 dual-write: this is COMPUTED but not yet
     * consumed by the X-User-Plan header path (that's PR7 cutover).
     *
     * <p>Returns:
     * <ul>
     *   <li>The owner's plan code if the user has a default org with an
     *       OWNER who has an active subscription.</li>
     *   <li>{@code "FREE"} if the user has no default org, the org's
     *       owner has no subscription, or anything else goes wrong.
     *       Never throws - this is on the hot user-resolution path.</li>
     * </ul>
     */
    /**
     * PR8 (Q1=b) - resolve who pays for a credit-consuming action.
     *
     * <p>Returns the userId of the org owner if the executor is in a
     * non-personal default workspace AND the executor is NOT the owner.
     * Returns the executor's own userId otherwise (personal-org context,
     * unowned-org, or the executor IS the owner).
     *
     * <p>Examples:
     * <ul>
     *   <li>Alice in her personal org → returns Alice's userId</li>
     *   <li>Alice (MEMBER) in Bob's TEAM org → returns Bob's userId
     *       (Bob pays for Alice's consumption per Q1=b)</li>
     *   <li>Alice (OWNER) in her own TEAM org → returns Alice's userId</li>
     *   <li>Alice with no default org → returns Alice's userId
     *       (degenerate state, fall back to self-billing)</li>
     * </ul>
     *
     * <p>Soft-deleted orgs and null owners short-circuit to self-billing.
     * Never throws - hot path safety.
     */
    public Long resolvePayerUserId(Long executorUserId) {
        if (executorUserId == null) return null;
        try {
            Optional<OrganizationMember> workspaceMembership =
                    resolveCurrentWorkspaceMembership(executorUserId);
            if (workspaceMembership.isEmpty()) return executorUserId;

            Organization workspaceOrg = workspaceMembership.get().getOrganization();
            if (workspaceOrg == null || workspaceOrg.isDeleted()) return executorUserId;

            User owner = workspaceOrg.getOwner();
            if (owner == null || owner.getId() == null) return executorUserId;

            // Executor IS the owner - no redirect, same wallet. Covers both
            // solo workspaces (owner==self) AND owner running in their own
            // shared workspace. The previous isPersonal() shortcut was redundant
            // with this check AND wrong in production where is_personal=true
            // workspaces can have invited members whose owner ≠ executor.
            if (executorUserId.equals(owner.getId())) return executorUserId;

            return owner.getId();
        } catch (Exception e) {
            // Hot path - fall back to self-billing on any lookup glitch.
            return executorUserId;
        }
    }

    public String resolveActiveOrgTier(Long userId) {
        return resolveActiveOrgEntitlement(userId).planCode();
    }

    /**
     * Full governing entitlement for {@code userId}: the plan code AND the credit-tier index +
     * billing cadence of the user's active/default workspace OWNER's subscription. Same owner
     * resolution as {@link #resolveActiveOrgTier} (the X-User-Plan tier that follows the user),
     * extended so a linked CE install can mirror not just the plan but the exact credit tier and
     * cadence the cloud account is on. Returns {@code FREE / 0 / null} when no default-workspace
     * owner has an active subscription. Never throws - hot path safety.
     *
     * <p>Defence-in-depth against a soft-deleted default org: the derived membership query does NOT
     * filter on {@code organization.deleted_at IS NULL}, so the explicit isDeleted guard below stops
     * a tomb-stoned org's owner plan leaking through.
     */
    public ActiveOrgEntitlement resolveActiveOrgEntitlement(Long userId) {
        if (userId == null) return ActiveOrgEntitlement.free();
        try {
            Optional<OrganizationMember> workspaceMembership =
                    resolveCurrentWorkspaceMembership(userId);
            if (workspaceMembership.isEmpty()) return ActiveOrgEntitlement.free();

            Organization workspaceOrg = workspaceMembership.get().getOrganization();
            if (workspaceOrg == null || workspaceOrg.isDeleted()) return ActiveOrgEntitlement.free();

            User owner = workspaceOrg.getOwner();
            if (owner == null || owner.getId() == null) return ActiveOrgEntitlement.free();

            return subscriptionRepository.findActiveByUserId(owner.getId())
                    .map(sub -> {
                        String code = sub.getPlan() != null ? sub.getPlan().getCode() : FREE_PLAN_CODE;
                        int qty = sub.getCreditQuantity() != null ? sub.getCreditQuantity() : 0;
                        return new ActiveOrgEntitlement(code, CreditTierConstants.resolveTierIndex(qty, code), sub.getCadence());
                    })
                    .orElse(ActiveOrgEntitlement.free());
        } catch (Exception e) {
            // Hot path - never let a lookup glitch break user resolution.
            return ActiveOrgEntitlement.free();
        }
    }

    /** Governing plan code + credit-tier index + billing cadence (the X-User-Plan tier, extended). */
    public record ActiveOrgEntitlement(String planCode, int creditTierIndex, String cadence) {
        static ActiveOrgEntitlement free() {
            return new ActiveOrgEntitlement(FREE_PLAN_CODE, 0, null);
        }
    }

    /**
     * Resolve the membership for the request's current workspace. Gateway
     * validates {@code X-Active-Organization-ID} and forwards it downstream as
     * {@code X-Organization-ID}; that request scope is authoritative for
     * wallet and plan routing. Background and legacy callers without request
     * scope keep the historical default-membership fallback.
     */
    private Optional<OrganizationMember> resolveCurrentWorkspaceMembership(Long userId) {
        String requestOrgId = TenantResolver.currentRequestOrganizationId();
        if (requestOrgId != null && !requestOrgId.isBlank()) {
            try {
                return memberRepository.findActiveByOrganizationIdAndUserId(
                        UUID.fromString(requestOrgId), userId);
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
        // No request scope (background / legacy callers) → the user's default
        // workspace. NOTE: the user-facing "dormant org" fallback to personal is
        // handled at the request edge (gateway AuthenticationFilter / WS interceptor
        // and the CE MonolithOrganizationContextFilter), which never inject a paused
        // org's id as X-Organization-ID - so this no-request-scope path stays the
        // historical default lookup (billing/plan resolution is access-agnostic).
        return memberRepository.findByUser_IdAndIsDefaultTrue(userId);
    }
}
