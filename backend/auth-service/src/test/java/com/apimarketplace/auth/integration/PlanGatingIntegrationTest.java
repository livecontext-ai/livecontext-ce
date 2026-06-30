package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.PlanResolutionService;
import com.apimarketplace.common.plan.CloudPlanAccess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Simulates plan-gating end-to-end against a real DB: the same org-owner-plan
 * resolution the platform uses ({@link PlanResolutionService#orgOwnerSupportsTeam})
 * must unlock Team capabilities ONLY for a TEAM/ENTERPRISE_* plan, not for FREE.
 * This is the "Team débloque X, Free non" check, exercised without a second cloud
 * account by seeding distinct owners on distinct plans.
 */
@IntegrationTest
// Adding @MockBean below forks a fresh (non-shared) Spring context; disable the gateway
// verification filter so its bean does not demand a secret-key (this test calls the service
// directly and issues no HTTP requests, so the filter is irrelevant here).
@TestPropertySource(properties = "gateway.filter.verification-enabled=false")
@DisplayName("Plan gating - Team vs Free (real DB)")
class PlanGatingIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private BillingCustomerRepository billingCustomerRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private PlanResolutionService planResolutionService;

    /**
     * CE↔Cloud delegation bean. Present here as a mock so we can prove the cloud-governed
     * unlock through the REAL {@link PlanResolutionService} + the REAL {@code PlanRepository}
     * (cloud plan code → DB {@code findByCode} → {@code Plan.supportsTeam()}), which the
     * unit tests stub out. Unstubbed it returns {@link Optional#empty()} (Mockito default for
     * Optional), so the pre-existing local-only test is unaffected.
     */
    @MockBean private CloudPlanAccess cloudPlanAccess;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Test
    @DisplayName("TEAM plan owner → supportsTeam() true; FREE owner → false; ENTERPRISE_* → true")
    void orgOwnerSupportsTeam_gatedByPlan() {
        Organization teamOrg = orgOwnedByUserOnPlan("TEAM", "Team Plan");
        Organization freeOrg = orgOwnedByUserOnPlan("FREE", "Free Plan");
        Organization entOrg = orgOwnedByUserOnPlan("ENTERPRISE_GOLD", "Enterprise Gold");

        assertThat(planResolutionService.orgOwnerSupportsTeam(teamOrg)).isTrue();
        assertThat(planResolutionService.orgOwnerSupportsTeam(freeOrg)).isFalse();
        assertThat(planResolutionService.orgOwnerSupportsTeam(entOrg)).isTrue();
    }

    @Test
    @DisplayName("CLOUD-governed owner unlocks Team end-to-end (real DB + PlanResolutionService + PlanRepository), "
            + "overriding a local FREE subscription - this is the SAME path the invite gate uses")
    void cloudGovernedOwnerUnlocksTeamOverLocalFree() {
        // Owner's LOCAL subscription is FREE (no team)...
        Organization org = orgOwnedByUserOnPlan("FREE", "Free Plan");
        // ...but the bound cloud account governs TEAM. Seed the TEAM row the resolver will findByCode,
        // and have the (mocked) CE cloud-link delegate report TEAM for this owner.
        planRepository.saveAndFlush(new Plan("TEAM", "Team Plan", "Team Plan"));
        when(cloudPlanAccess.governingPlanCode(org.getOwner().getId())).thenReturn(Optional.of("TEAM"));

        // orgOwnerSupportsTeam is the canonical capability check the invite gate + dormant-org gate
        // both funnel through, so this proves "paying TEAM on the cloud unlocks team on the CE".
        assertThat(planResolutionService.orgOwnerSupportsTeam(org))
                .as("cloud TEAM must override the local FREE subscription")
                .isTrue();
        // Pin that the unlock came from the cloud delegation, not the local sub.
        verify(cloudPlanAccess).governingPlanCode(org.getOwner().getId());
    }

    @Test
    @DisplayName("CLOUD plan is authoritative when present: cloud FREE overrides a local TEAM subscription → no team "
            + "(the bound account governs in BOTH directions, not just upward)")
    void cloudGovernedDowngradeOverridesLocalTeam() {
        // Owner's LOCAL subscription is TEAM, but the bound cloud account governs FREE.
        Organization org = orgOwnedByUserOnPlan("TEAM", "Team Plan");
        planRepository.saveAndFlush(new Plan("FREE", "Free Plan", "Free Plan"));
        when(cloudPlanAccess.governingPlanCode(org.getOwner().getId())).thenReturn(Optional.of("FREE"));

        assertThat(planResolutionService.orgOwnerSupportsTeam(org))
                .as("cloud FREE must override the local TEAM subscription (cloud is authoritative when present)")
                .isFalse();
    }

    @Test
    @DisplayName("CLOUD plan empty (BYOK / unlinked / cloud unreachable) → falls back to the local subscription "
            + "(FREE owner → no team), never strips entitlements")
    void cloudEmptyFallsBackToLocalSubscription() {
        Organization freeOrg = orgOwnedByUserOnPlan("FREE", "Free Plan");
        Organization teamOrg = orgOwnedByUserOnPlan("TEAM", "Team Plan");
        // No cloud governance for either owner → local plan is authoritative.
        when(cloudPlanAccess.governingPlanCode(freeOrg.getOwner().getId())).thenReturn(Optional.empty());
        when(cloudPlanAccess.governingPlanCode(teamOrg.getOwner().getId())).thenReturn(Optional.empty());

        assertThat(planResolutionService.orgOwnerSupportsTeam(freeOrg)).isFalse();
        assertThat(planResolutionService.orgOwnerSupportsTeam(teamOrg)).isTrue();
    }

    /** Seed User → BillingCustomer → active Subscription(plan), return an org owned by that user. */
    private Organization orgOwnedByUserOnPlan(String planCode, String planName) {
        int n = SEQ.incrementAndGet();
        User owner = new User("owner" + n, "owner" + n + "@example.com", AuthProvider.KEYCLOAK,
                "00000000-0000-0000-0000-0000000000" + String.format("%02d", n));
        owner.setEnabled(true);
        owner.setRoles(Set.of("USER"));
        owner.setUserVersion(1L);
        owner = userRepository.saveAndFlush(owner);

        Plan plan = planRepository.saveAndFlush(new Plan(planCode, planName, planName));
        BillingCustomer customer = billingCustomerRepository.saveAndFlush(new BillingCustomer(owner, "internal"));

        Subscription sub = new Subscription();
        sub.setBillingCustomer(customer);
        sub.setPlan(plan);
        sub.setStatus("active");
        sub.setCadence("monthly");
        sub.setProvider("internal");
        sub.setQuantity(1);
        sub.setCurrentPeriodStart(LocalDateTime.now().minusDays(1));
        sub.setCurrentPeriodEnd(LocalDateTime.now().plusDays(29));
        sub.setCancelAtPeriodEnd(false);
        subscriptionRepository.saveAndFlush(sub);

        return new Organization("Org " + n, "org-" + n, false, owner);
    }
}
