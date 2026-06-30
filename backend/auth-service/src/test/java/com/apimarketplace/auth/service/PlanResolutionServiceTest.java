package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.common.plan.CloudPlanAccess;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PR6 regression - pin the three plan-resolution shapes that
 * UserResolutionService + billing gates need to distinguish.
 *
 * <p>Dual-write design pre-condition: PR6 returns dual values
 * (billingPlan + activeOrgPlan), PR7 cutover flips which one is
 * canonical for X-User-Plan. Drift in these resolutions breaks the
 * cutover plan.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlanResolutionService - PR6 contract")
class PlanResolutionServiceTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private OrganizationMemberRepository memberRepository;

    private PlanResolutionService service;

    private User user;
    private User orgOwner;
    private Organization org;
    private Plan freePlan;
    private Plan teamPlan;

    @BeforeEach
    void setUp() {
        service = new PlanResolutionService(subscriptionRepository, memberRepository);

        user = new User("u", "u@test.com", AuthProvider.KEYCLOAK, "kc-u");
        user.setId(42L);

        orgOwner = new User("owner", "owner@test.com", AuthProvider.KEYCLOAK, "kc-owner");
        orgOwner.setId(1L);

        org = new Organization("Team", "team", false, orgOwner);
        org.setId(UUID.randomUUID());

        freePlan = new Plan("FREE", "Free", "Free plan");
        teamPlan = new Plan("TEAM", "Team", "Team plan");
    }

    private Subscription sub(Plan plan) {
        Subscription s = new Subscription();
        s.setPlan(plan);
        s.setStatus("active");
        return s;
    }

    // ---- resolveBillingPlan ----

    @Test
    @DisplayName("resolveBillingPlan returns user's own subscription plan code")
    void billingPlanFromUserSubscription() {
        when(subscriptionRepository.findActiveByUserId(42L)).thenReturn(Optional.of(sub(teamPlan)));

        assertThat(service.resolveBillingPlan(42L)).isEqualTo("TEAM");
    }

    @Test
    @DisplayName("resolveBillingPlan defaults to FREE when user has no active subscription")
    void billingPlanDefaultsToFreeWhenNoSubscription() {
        when(subscriptionRepository.findActiveByUserId(42L)).thenReturn(Optional.empty());

        assertThat(service.resolveBillingPlan(42L)).isEqualTo("FREE");
    }

    @Test
    @DisplayName("resolveBillingPlan: null userId → FREE (defensive, hot path)")
    void billingPlanNullUserIdReturnsFree() {
        assertThat(service.resolveBillingPlan(null)).isEqualTo("FREE");
    }

    @Test
    @DisplayName("resolveBillingPlan: subscription with null Plan FK → FREE (defensive)")
    void billingPlanNullPlanFkReturnsFree() {
        Subscription orphan = new Subscription();
        orphan.setPlan(null);
        when(subscriptionRepository.findActiveByUserId(42L)).thenReturn(Optional.of(orphan));

        assertThat(service.resolveBillingPlan(42L)).isEqualTo("FREE");
    }

    // ---- resolveOrgOwnerPlan ----

    @Test
    @DisplayName("resolveOrgOwnerPlan returns the org owner's subscription plan")
    void orgOwnerPlanFromOwnerSubscription() {
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.of(sub(teamPlan)));

        assertThat(service.resolveOrgOwnerPlan(org)).isEqualTo(teamPlan);
    }

    @Test
    @DisplayName("resolveOrgOwnerPlan returns null when owner has no subscription "
            + "(callers handle FREE fallback explicitly)")
    void orgOwnerPlanNullWhenNoSubscription() {
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.empty());

        assertThat(service.resolveOrgOwnerPlan(org)).isNull();
    }

    @Test
    @DisplayName("resolveOrgOwnerPlan: null org or null owner → null (defensive)")
    void orgOwnerPlanNullSafety() {
        assertThat(service.resolveOrgOwnerPlan(null)).isNull();
        Organization orphan = new Organization("orphan", "orphan", false, null);
        assertThat(service.resolveOrgOwnerPlan(orphan)).isNull();
    }

    @Test
    @DisplayName("resolveOrgOwnerPlan: CLOUD-governed install resolves the cloud plan over the local subscription")
    void orgOwnerPlanFollowsCloudWhenGoverned() {
        // Owner's local subscription would be FREE, but the bound cloud account governs (TEAM) →
        // CE follows the cloud plan, resolved locally by code (CE and cloud share identical rows).
        CloudPlanAccess cloudPlanAccess = mock(CloudPlanAccess.class);
        PlanRepository planRepository = mock(PlanRepository.class);
        when(cloudPlanAccess.governingPlanCode(1L)).thenReturn(Optional.of("TEAM"));
        when(planRepository.findByCode("TEAM")).thenReturn(Optional.of(teamPlan));
        ReflectionTestUtils.setField(service, "cloudPlanAccess", cloudPlanAccess);
        ReflectionTestUtils.setField(service, "planRepository", planRepository);

        assertThat(service.resolveOrgOwnerPlan(org)).isEqualTo(teamPlan);
        verifyNoInteractions(subscriptionRepository);
    }

    @Test
    @DisplayName("resolveOrgOwnerPlan: cloud not governing (BYOK/unlinked) → falls back to the local subscription")
    void orgOwnerPlanFallsBackToLocalWhenCloudEmpty() {
        CloudPlanAccess cloudPlanAccess = mock(CloudPlanAccess.class);
        PlanRepository planRepository = mock(PlanRepository.class);
        when(cloudPlanAccess.governingPlanCode(1L)).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(service, "cloudPlanAccess", cloudPlanAccess);
        ReflectionTestUtils.setField(service, "planRepository", planRepository);
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.of(sub(freePlan)));

        assertThat(service.resolveOrgOwnerPlan(org)).isEqualTo(freePlan);
    }

    // ---- resolveActiveOrgTier ----

    @Test
    @DisplayName("resolveActiveOrgTier returns the active workspace's owner's plan code")
    void activeOrgTierFromDefaultOrgsOwner() {
        OrganizationMember mem = new OrganizationMember(org, user, OrganizationRole.MEMBER, true);
        when(memberRepository.findByUser_IdAndIsDefaultTrue(42L)).thenReturn(Optional.of(mem));
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.of(sub(teamPlan)));

        // CRITICAL pin: a FREE-plan MEMBER of a TEAM org sees TEAM tier
        // (Q1=b plan-follows-workspace) - NOT their own FREE.
        assertThat(service.resolveActiveOrgTier(42L)).isEqualTo("TEAM");
    }

    @Test
    @DisplayName("resolveActiveOrgTier follows request workspace before persisted default org")
    void activeOrgTierUsesRequestWorkspaceBeforeDefaultOrg() {
        OrganizationMember teamMembership = new OrganizationMember(org, user, OrganizationRole.MEMBER, false);

        when(memberRepository.findActiveByOrganizationIdAndUserId(org.getId(), 42L))
                .thenReturn(Optional.of(teamMembership));
        lenient().when(subscriptionRepository.findActiveByUserId(42L)).thenReturn(Optional.of(sub(freePlan)));
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.of(sub(teamPlan)));

        TenantResolver.runWithOrgScope(org.getId().toString(), () ->
                assertThat(service.resolveActiveOrgTier(42L))
                        .as("plan must follow X-Organization-ID, not the persisted default org")
                        .isEqualTo("TEAM"));
    }

    @Test
    @DisplayName("resolveActiveOrgTier returns FREE when user has no default org")
    void activeOrgTierFreeWhenNoDefault() {
        when(memberRepository.findByUser_IdAndIsDefaultTrue(42L)).thenReturn(Optional.empty());

        assertThat(service.resolveActiveOrgTier(42L)).isEqualTo("FREE");
    }

    @Test
    @DisplayName("resolveActiveOrgTier returns FREE when default org's owner has no subscription "
            + "(closes the §9.3 'TEAM org with no active subscription' question - degrade to FREE)")
    void activeOrgTierFreeWhenOwnerHasNoSubscription() {
        OrganizationMember mem = new OrganizationMember(org, user, OrganizationRole.MEMBER, true);
        when(memberRepository.findByUser_IdAndIsDefaultTrue(42L)).thenReturn(Optional.of(mem));
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.empty());

        assertThat(service.resolveActiveOrgTier(42L)).isEqualTo("FREE");
    }

    @Test
    @DisplayName("PR6 audit B fix: soft-deleted default org → FREE (defence in depth, "
            + "since findByUser_IdAndIsDefaultTrue doesn't filter deletedAt)")
    void activeOrgTierFreeWhenDefaultOrgIsSoftDeleted() {
        org.setDeletedAt(java.time.LocalDateTime.now());
        OrganizationMember mem = new OrganizationMember(org, user, OrganizationRole.MEMBER, true);
        when(memberRepository.findByUser_IdAndIsDefaultTrue(42L)).thenReturn(Optional.of(mem));

        // Owner sub lookup MUST NOT fire - soft-delete short-circuits earlier.
        assertThat(service.resolveActiveOrgTier(42L)).isEqualTo("FREE");
    }

    @Test
    @DisplayName("PR6 audit B fix: hard-deleted/orphan owner → FREE (explicit null-owner guard)")
    void activeOrgTierFreeWhenOwnerIsNull() {
        Organization orphan = new Organization("orphan", "orphan", false, null);
        orphan.setId(UUID.randomUUID());
        OrganizationMember mem = new OrganizationMember(orphan, user, OrganizationRole.MEMBER, true);
        when(memberRepository.findByUser_IdAndIsDefaultTrue(42L)).thenReturn(Optional.of(mem));

        assertThat(service.resolveActiveOrgTier(42L)).isEqualTo("FREE");
    }

    @Test
    @DisplayName("PR6 audit B fix: subscription with null Plan FK on the org owner → FREE "
            + "(mirrors the same defence in resolveBillingPlan)")
    void activeOrgTierFreeWhenOwnerSubscriptionHasNullPlan() {
        OrganizationMember mem = new OrganizationMember(org, user, OrganizationRole.MEMBER, true);
        when(memberRepository.findByUser_IdAndIsDefaultTrue(42L)).thenReturn(Optional.of(mem));
        Subscription orphan = new Subscription();
        orphan.setPlan(null);
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.of(orphan));

        assertThat(service.resolveActiveOrgTier(42L)).isEqualTo("FREE");
    }

    @Test
    @DisplayName("PR6 audit B fix: subscriptionRepository exception swallowed to FREE "
            + "(asymmetric coverage gap - previously only memberRepository throwing was tested)")
    void activeOrgTierSwallowsSubscriptionRepoException() {
        OrganizationMember mem = new OrganizationMember(org, user, OrganizationRole.MEMBER, true);
        when(memberRepository.findByUser_IdAndIsDefaultTrue(42L)).thenReturn(Optional.of(mem));
        when(subscriptionRepository.findActiveByUserId(1L))
                .thenThrow(new RuntimeException("subscription lookup hiccup"));

        assertThat(service.resolveActiveOrgTier(42L)).isEqualTo("FREE");
    }

    @Test
    @DisplayName("resolveActiveOrgTier: any internal exception swallowed to FREE - hot path "
            + "must never throw from UserResolutionService")
    void activeOrgTierSwallowsExceptionsToFree() {
        when(memberRepository.findByUser_IdAndIsDefaultTrue(42L))
                .thenThrow(new RuntimeException("DB hiccup"));

        assertThat(service.resolveActiveOrgTier(42L)).isEqualTo("FREE");
    }

    @Test
    @DisplayName("resolveActiveOrgTier: null userId → FREE")
    void activeOrgTierNullUserIdReturnsFree() {
        assertThat(service.resolveActiveOrgTier(null)).isEqualTo("FREE");
    }

    @Test
    @DisplayName("Personal-org context: user IS owner of their personal org → activeOrgPlan "
            + "resolves to their own subscription (no inversion vs billingPlan)")
    void activeOrgTierMatchesBillingPlanInPersonalOrg() {
        Organization personalOrg = new Organization("personal", "personal", true, user);
        personalOrg.setId(UUID.randomUUID());
        OrganizationMember mem = new OrganizationMember(personalOrg, user, OrganizationRole.OWNER, true);

        // User has subscription PRO; their personal org also has owner=user.
        Plan proPlan = new Plan("PRO", "Pro", "Pro plan");
        when(memberRepository.findByUser_IdAndIsDefaultTrue(42L)).thenReturn(Optional.of(mem));
        when(subscriptionRepository.findActiveByUserId(42L)).thenReturn(Optional.of(sub(proPlan)));

        assertThat(service.resolveActiveOrgTier(42L)).isEqualTo("PRO");
        assertThat(service.resolveBillingPlan(42L)).isEqualTo("PRO");
    }

    @Test
    @DisplayName("PR6 audit B fix: distinct user/owner IDs prove the chain routes through "
            + "the org's owner - NOT the executing user (Q1=b correctness)")
    void activeOrgTierRoutesThroughOrgOwnerNotUser() {
        // User 42 is MEMBER of an org owned by user 1. Subscriptions:
        //   user 42's own sub = FREE
        //   org-owner (user 1)'s sub = TEAM
        // If the chain shortcuts via user.id (instead of orgOwner.id), the
        // result would be FREE. The pin asserts TEAM - the org owner's plan.
        OrganizationMember mem = new OrganizationMember(org, user, OrganizationRole.MEMBER, true);
        when(memberRepository.findByUser_IdAndIsDefaultTrue(42L)).thenReturn(Optional.of(mem));
        // Stub BOTH user.id=42 AND owner.id=1 so a degenerate impl that
        // queries user.id would also "succeed" with the WRONG answer.
        lenient().when(subscriptionRepository.findActiveByUserId(42L)).thenReturn(Optional.of(sub(freePlan)));
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.of(sub(teamPlan)));

        assertThat(service.resolveActiveOrgTier(42L))
                .as("MUST route through orgOwner.id (=1), not user.id (=42)")
                .isEqualTo("TEAM");
    }

    // ---- canMemberActInOrg (dormant-org gate - single source of truth) ----

    @Test
    @DisplayName("canMemberActInOrg: the OWNER keeps SOLO access to their team org after downgrading below team")
    void canActOwnerKeepsAccessAfterDowngrade() {
        OrganizationMember ownerMem = new OrganizationMember(org, orgOwner, OrganizationRole.OWNER, true);
        // Owner short-circuits BEFORE any plan lookup - no subscription stub needed.
        assertThat(service.canMemberActInOrg(ownerMem)).isTrue();
    }

    @Test
    @DisplayName("canMemberActInOrg: a PAUSED (over-cap) workspace is non-enterable even for the OWNER (V311 downgrade)")
    void canActOwnerBlockedFromPausedOverCapWorkspace() {
        org.setPausedAt(java.time.LocalDateTime.now());
        OrganizationMember ownerMem = new OrganizationMember(org, orgOwner, OrganizationRole.OWNER, true);
        // Paused check runs BEFORE the owner short-circuit - no subscription stub needed.
        assertThat(service.canMemberActInOrg(ownerMem))
                .as("downgraded owner cannot enter their own over-cap workspace")
                .isFalse();
    }

    @Test
    @DisplayName("resolvePausedOrgIds: includes a stored-paused workspace the user OWNS (over-cap), not just dormant-team member orgs")
    void resolvePausedIncludesOwnedOverCapWorkspace() {
        org.setPausedAt(java.time.LocalDateTime.now());
        OrganizationMember ownerMem = new OrganizationMember(org, orgOwner, OrganizationRole.OWNER, true);
        // Owner membership is excluded from the team-capability candidates, so the
        // stored over-cap pause is the only thing that flags it - no subscription stub.
        assertThat(service.resolvePausedOrgIds(java.util.List.of(ownerMem)))
                .contains(org.getId());
    }

    @Test
    @DisplayName("canMemberActInOrg: the OWNER of a personal org is NEVER blocked (solo user keeps their own workspace)")
    void canActPersonalOrgOwnerNeverBlocked() {
        Organization personal = new Organization("p", "p", true, user);
        personal.setId(UUID.randomUUID());
        OrganizationMember mem = new OrganizationMember(personal, user, OrganizationRole.OWNER, true);
        // OWNER short-circuits BEFORE any plan lookup - no subscription stub needed.
        assertThat(service.canMemberActInOrg(mem)).isTrue();
    }

    @Test
    @DisplayName("canMemberActInOrg: a NON-owner member of a solo-owner PERSONAL workspace is BLOCKED "
            + "(prod bug - contact@ was a member of a PRO owner's personal org and could still switch in; "
            + "personal-ness must NOT exempt the free team-sharing loophole)")
    void canActBlocksNonOwnerMemberOfSoloPersonalOrg() {
        // org owner (id 1) is on a non-team plan (PRO); the personal workspace
        // carries a stray invited member (id 42). is_personal must NOT grant entry.
        Organization personalOfOwner = new Organization("owner-personal", "owner-personal", true, orgOwner);
        personalOfOwner.setId(UUID.randomUUID());
        OrganizationMember strayMember = new OrganizationMember(personalOfOwner, user, OrganizationRole.MEMBER, true);
        when(subscriptionRepository.findActiveByUserId(1L))
                .thenReturn(Optional.of(sub(new Plan("PRO", "Pro", "Pro plan"))));

        assertThat(service.canMemberActInOrg(strayMember))
                .as("non-owner member of a PRO owner's personal workspace must be blocked")
                .isFalse();
    }

    @Test
    @DisplayName("canMemberActInOrg: non-owner member BLOCKED when org owner is no longer on a team plan (dormant)")
    void canActBlocksNonOwnerWhenOwnerNotTeam() {
        OrganizationMember mem = new OrganizationMember(org, user, OrganizationRole.MEMBER, true);
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.of(sub(freePlan)));
        assertThat(service.canMemberActInOrg(mem)).isFalse();
    }

    @Test
    @DisplayName("canMemberActInOrg: non-owner member ALLOWED when org owner is on a team plan")
    void canActAllowsNonOwnerWhenOwnerOnTeam() {
        OrganizationMember mem = new OrganizationMember(org, user, OrganizationRole.MEMBER, true);
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.of(sub(teamPlan)));
        assertThat(service.canMemberActInOrg(mem)).isTrue();
    }

    @Test
    @DisplayName("canMemberActInOrg: ENTERPRISE_* counts as team - non-owner member allowed")
    void canActAllowsNonOwnerWhenOwnerOnEnterprise() {
        OrganizationMember mem = new OrganizationMember(org, user, OrganizationRole.MEMBER, true);
        when(subscriptionRepository.findActiveByUserId(1L))
                .thenReturn(Optional.of(sub(new Plan("ENTERPRISE_BASIC", "Ent", "Enterprise"))));
        assertThat(service.canMemberActInOrg(mem)).isTrue();
    }

    @Test
    @DisplayName("canMemberActInOrg: owner with NO active subscription → member blocked (null plan = not team)")
    void canActBlocksWhenOwnerHasNoSubscription() {
        OrganizationMember mem = new OrganizationMember(org, user, OrganizationRole.MEMBER, true);
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.empty());
        assertThat(service.canMemberActInOrg(mem)).isFalse();
    }

    @Test
    @DisplayName("canMemberActInOrg: a CLOUD-governed TEAM owner keeps non-owner members active (cloud delegation)")
    void canActFollowsCloudTeamPlan() {
        CloudPlanAccess cloudPlanAccess = mock(CloudPlanAccess.class);
        PlanRepository planRepository = mock(PlanRepository.class);
        when(cloudPlanAccess.governingPlanCode(1L)).thenReturn(Optional.of("TEAM"));
        when(planRepository.findByCode("TEAM")).thenReturn(Optional.of(teamPlan));
        ReflectionTestUtils.setField(service, "cloudPlanAccess", cloudPlanAccess);
        ReflectionTestUtils.setField(service, "planRepository", planRepository);
        OrganizationMember mem = new OrganizationMember(org, user, OrganizationRole.MEMBER, true);
        assertThat(service.canMemberActInOrg(mem)).isTrue();
    }

    @Test
    @DisplayName("canMemberActInOrg: null member / null org → false (defensive)")
    void canActNullSafety() {
        assertThat(service.canMemberActInOrg(null)).isFalse();
        OrganizationMember orphan = new OrganizationMember(null, user, OrganizationRole.MEMBER, true);
        assertThat(service.canMemberActInOrg(orphan)).isFalse();
    }

    // ---- resolvePausedOrgIds (batched workspace-list flag) ----

    @Test
    @DisplayName("resolvePausedOrgIds: flags ONLY the dormant team org (owner not team); skips owner-held and team-owned")
    void resolvePausedOrgIdsMarksDormantOnly() {
        // teamA (org, owner id 1) - user is MEMBER, owner has no team plan → DORMANT
        OrganizationMember memA = new OrganizationMember(org, user, OrganizationRole.MEMBER, false);
        // teamB (orgB, owner id 2) - user is MEMBER, owner IS on TEAM → not dormant
        User ownerB = new User("ob", "ob@test.com", AuthProvider.KEYCLOAK, "kc-ob");
        ownerB.setId(2L);
        Organization orgB = new Organization("B", "b", false, ownerB);
        orgB.setId(UUID.randomUUID());
        OrganizationMember memB = new OrganizationMember(orgB, user, OrganizationRole.MEMBER, false);
        // personal - user is OWNER → never dormant (excluded by the role==OWNER filter)
        Organization personal = new Organization("p", "p", true, user);
        personal.setId(UUID.randomUUID());
        OrganizationMember memP = new OrganizationMember(personal, user, OrganizationRole.OWNER, true);

        // Batch returns ONLY owner 2's TEAM sub; owner 1 is absent → treated as not-team.
        when(subscriptionRepository.findActiveByOwnerUserIds(any()))
                .thenReturn(List.of(ownerSub(ownerB, teamPlan)));

        Set<UUID> paused = service.resolvePausedOrgIds(List.of(memP, memA, memB));

        assertThat(paused).containsExactly(org.getId());
    }

    @Test
    @DisplayName("resolvePausedOrgIds: a NON-owner member of a solo-owner PERSONAL workspace IS flagged dormant "
            + "(prod regression - drives the FE 'paused' badge + gateway rejection for contact@'s membership "
            + "in a PRO owner's personal org)")
    void resolvePausedOrgIdsFlagsNonOwnerMemberOfSoloPersonalOrg() {
        // Owner (id 1) holds a PERSONAL workspace and is on a non-team plan; the
        // user (id 42) is a stray MEMBER. is_personal must NOT exempt it.
        Organization personalOfOwner = new Organization("owner-personal", "owner-personal", true, orgOwner);
        personalOfOwner.setId(UUID.randomUUID());
        OrganizationMember strayMember = new OrganizationMember(personalOfOwner, user, OrganizationRole.MEMBER, true);

        // Owner 1 has no team sub in the batch → not team-capable → dormant.
        when(subscriptionRepository.findActiveByOwnerUserIds(any())).thenReturn(List.of());

        Set<UUID> paused = service.resolvePausedOrgIds(List.of(strayMember));

        assertThat(paused)
                .as("the personal workspace of a non-team owner must be dormant for its stray member")
                .containsExactly(personalOfOwner.getId());
    }

    @Test
    @DisplayName("resolvePausedOrgIds: CE no longer exempts - a non-owner of a non-team owner IS dormant, like cloud")
    void resolvePausedOrgIdsCeFollowsPlan() {
        OrganizationMember memA = new OrganizationMember(org, user, OrganizationRole.MEMBER, false);
        when(subscriptionRepository.findActiveByOwnerUserIds(any())).thenReturn(List.of()); // owner 1 not team
        assertThat(service.resolvePausedOrgIds(List.of(memA))).containsExactly(org.getId());
    }

    @Test
    @DisplayName("resolvePausedOrgIds: a CLOUD-governed TEAM owner keeps the org non-dormant (cloud overrides local)")
    void resolvePausedOrgIdsCloudTeamNotDormant() {
        CloudPlanAccess cloudPlanAccess = mock(CloudPlanAccess.class);
        PlanRepository planRepository = mock(PlanRepository.class);
        when(cloudPlanAccess.governingPlanCode(1L)).thenReturn(Optional.of("TEAM"));
        when(planRepository.findByCode("TEAM")).thenReturn(Optional.of(teamPlan));
        ReflectionTestUtils.setField(service, "cloudPlanAccess", cloudPlanAccess);
        ReflectionTestUtils.setField(service, "planRepository", planRepository);
        OrganizationMember memA = new OrganizationMember(org, user, OrganizationRole.MEMBER, false);
        when(subscriptionRepository.findActiveByOwnerUserIds(any())).thenReturn(List.of()); // local none → cloud TEAM wins
        assertThat(service.resolvePausedOrgIds(List.of(memA))).isEmpty();
    }

    private Subscription ownerSub(User owner, Plan plan) {
        Subscription s = sub(plan);
        s.setBillingCustomer(new BillingCustomer(owner, "stripe"));
        return s;
    }
}
