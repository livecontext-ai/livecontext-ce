package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.CeLink;
import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.UserOnboarding;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.CeLinkHeartbeatRepository;
import com.apimarketplace.auth.repository.CeLinkRepository;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserOnboardingRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.CeLinkActiveRowCache;
import com.apimarketplace.auth.service.CeLinkActiveRowCachePublisher;
import com.apimarketplace.auth.service.CeLinkAuditService;
import com.apimarketplace.auth.service.CeLinkHeartbeatService;
import com.apimarketplace.auth.service.CeLinkService;
import com.apimarketplace.auth.service.IpHashService;
import com.apimarketplace.auth.service.OnboardingService;
import com.apimarketplace.auth.service.PlanResolutionService;
import com.apimarketplace.common.plan.CeLinkAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The CE cloud link, end to end against a real DB (H2 PostgreSQL-compat, full Spring context),
 * the REAL {@link PlanResolutionService} and the REAL {@link OnboardingService}:
 * <ul>
 *   <li>any plan, FREE included, may link: register and heartbeat never look at the plan, and a
 *       plan change never revokes the link (which would also log the user out of Keycloak);</li>
 *   <li>register needs a completed cloud onboarding, email code included;</li>
 *   <li>the paid relays read {@link CeLinkService#linkAccess}: PLAN_REQUIRED until the governing
 *       plan (the plan of the owner of the user's DEFAULT workspace, never the request's active
 *       workspace) is paid, ACTIVE as soon as it is, with no re-link.</li>
 * </ul>
 *
 * <p>The keycloak-gated services are constructed manually (they do not load under the embedded
 * test profile), with the plan cache disabled (TTL 0) so each call reads the database. The
 * audit + cache collaborators are mocked (the JSONB audit row is irrelevant here and avoids an
 * H2 type mismatch).
 */
@IntegrationTest
@DisplayName("CE cloud link - any plan links, paid relays need a paid plan (real DB, real plan + onboarding)")
class CeLinkHeartbeatEntitlementIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private UserRepository userRepository;
    @Autowired private BillingCustomerRepository billingCustomerRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private CeLinkRepository ceLinkRepository;
    @Autowired private CeLinkHeartbeatRepository ceLinkHeartbeatRepository;
    @Autowired private PlanResolutionService planResolutionService;
    @Autowired private OnboardingService onboardingService;
    @Autowired private UserOnboardingRepository onboardingRepository;

    private CeLinkService ceLinkService;
    private CeLinkHeartbeatService heartbeatService;
    private ApplicationEventPublisher eventPublisher;

    private static final UUID INSTALL = UUID.fromString("9d2b6f3a-1111-2222-3333-444455556666");
    private static final String CE_VERSION = "1.4.0";
    private static final String IP = "203.0.113.9";

    @BeforeEach
    void setUp() {
        CeLinkAuditService auditService = mock(CeLinkAuditService.class);
        IpHashService ipHashService = mock(IpHashService.class);
        lenient().when(ipHashService.hashWithCurrent(any(), any()))
                .thenReturn(new IpHashService.HashResult("hash-v1", 1));
        eventPublisher = mock(ApplicationEventPublisher.class);

        // Every call reaches the real repository except the Postgres-only row lock the register
        // path takes (SELECT ... FOR KEY SHARE, which H2 cannot parse): it answers "the account
        // exists" by reading the row through the same repository.
        UserRepository lockTolerantUsers = org.mockito.Mockito.mock(UserRepository.class,
                org.mockito.AdditionalAnswers.delegatesTo(userRepository));
        org.mockito.Mockito.doAnswer(inv -> userRepository.findById(inv.getArgument(0))
                        .map(u -> u.getId()))
                .when(lockTolerantUsers).lockExistingForKeyShare(org.mockito.ArgumentMatchers.any());
        ceLinkService = new CeLinkService(
                ceLinkRepository, ceLinkHeartbeatRepository, lockTolerantUsers, auditService,
                mock(CeLinkActiveRowCache.class), mock(CeLinkActiveRowCachePublisher.class),
                eventPublisher, planResolutionService, onboardingService, 0);
        heartbeatService = new CeLinkHeartbeatService(
                ceLinkRepository, ceLinkHeartbeatRepository, ipHashService, auditService);
    }

    @Test
    @DisplayName("paid plan: heartbeat OK and the link is ACTIVE")
    void paidPlanKeepsLinkActive() {
        User owner = userOwningDefaultWorkspace();
        subscribe(owner, "PRO");
        ceLinkRepository.saveAndFlush(new CeLink(INSTALL, owner.getId(), "Laptop"));

        assertThat(heartbeatService.heartbeat(owner.getId(), INSTALL, CE_VERSION, IP))
                .isEqualTo(CeLinkHeartbeatService.Outcome.OK);
        assertThat(ceLinkService.linkAccess(owner.getId(), INSTALL).access()).isEqualTo(CeLinkAccess.ACTIVE);
    }

    @Test
    @DisplayName("regression: a FREE account keeps a live link (heartbeat OK, never revoked) while the paid relays answer PLAN_REQUIRED")
    void freeAccountIsLinkedButPaidRelaysNeedAPlan() {
        User owner = userOwningDefaultWorkspace();
        subscribe(owner, "FREE");
        ceLinkRepository.saveAndFlush(new CeLink(INSTALL, owner.getId(), "Laptop"));

        assertThat(heartbeatService.heartbeat(owner.getId(), INSTALL, CE_VERSION, IP))
                .isEqualTo(CeLinkHeartbeatService.Outcome.OK);
        assertThat(ceLinkService.userOwnsActiveLink(owner.getId(), INSTALL)).isTrue();
        assertThat(ceLinkService.linkAccess(owner.getId(), INSTALL).access())
                .isEqualTo(CeLinkAccess.PLAN_REQUIRED);
        assertThat(ceLinkService.linkAccess(owner.getId(), INSTALL).isLinked()).isTrue();
    }

    @Test
    @DisplayName("regression: losing the paid plan keeps the link (heartbeat OK, no revoke), only the paid relays close; paying again reopens them")
    void downgradeClosesPaidRelaysOnlyThenUpgradeReopens() {
        User owner = userOwningDefaultWorkspace();
        Subscription paid = subscribe(owner, "PRO");
        ceLinkRepository.saveAndFlush(new CeLink(INSTALL, owner.getId(), "Laptop"));

        // Downgrade: the paid subscription ends, nothing active remains (resolves as FREE).
        paid.setStatus("canceled");
        subscriptionRepository.saveAndFlush(paid);

        assertThat(heartbeatService.heartbeat(owner.getId(), INSTALL, CE_VERSION, IP))
                .isEqualTo(CeLinkHeartbeatService.Outcome.OK);
        CeLink link = ceLinkRepository.findById(INSTALL).orElseThrow();
        assertThat(link.getStatus()).isEqualTo(CeLink.Status.ACTIVE);
        assertThat(link.getRevokeReason()).isNull();
        assertThat(ceLinkHeartbeatRepository.findById(INSTALL)).isPresent();
        assertThat(ceLinkService.linkAccess(owner.getId(), INSTALL).access())
                .isEqualTo(CeLinkAccess.PLAN_REQUIRED);
        // No revoke event, hence no Keycloak logout.
        verify(eventPublisher, never()).publishEvent(any());

        // The account pays again: the paid relays reopen with no re-link.
        subscribe(owner, "STARTER");

        assertThat(ceLinkService.linkAccess(owner.getId(), INSTALL).access()).isEqualTo(CeLinkAccess.ACTIVE);
    }

    @Test
    @DisplayName("the WORKSPACE OWNER's plan decides: a member of a paid TEAM workspace (no plan of their own) is ACTIVE")
    void teamWorkspaceMemberIsGovernedByOwnerPlan() {
        User teamOwner = newUser();
        subscribe(teamOwner, "TEAM");
        Organization team = organizationRepository.saveAndFlush(
                new Organization("Team", "team-" + UUID.randomUUID(), false, teamOwner));
        User member = newUser();
        memberRepository.saveAndFlush(new OrganizationMember(team, member, OrganizationRole.MEMBER, true));
        ceLinkRepository.saveAndFlush(new CeLink(INSTALL, member.getId(), "Member laptop"));

        assertThat(ceLinkService.linkAccess(member.getId(), INSTALL).access()).isEqualTo(CeLinkAccess.ACTIVE);
        assertThat(ceLinkService.linkAccess(member.getId(), INSTALL).planCode()).isEqualTo("TEAM");
    }

    @Test
    @DisplayName("regression: browsing a paid TEAM workspace (X-Organization-ID) with a FREE DEFAULT workspace does not open the paid relays")
    void activePaidWorkspaceDoesNotOverrideFreeDefault() {
        User teamOwner = newUser();
        subscribe(teamOwner, "TEAM");
        Organization team = organizationRepository.saveAndFlush(
                new Organization("Team", "team-" + UUID.randomUUID(), false, teamOwner));
        // The member's DEFAULT is their own FREE personal workspace; the TEAM one is not default.
        User member = userOwningDefaultWorkspace();
        subscribe(member, "FREE");
        memberRepository.saveAndFlush(new OrganizationMember(team, member, OrganizationRole.MEMBER, false));
        ceLinkRepository.saveAndFlush(new CeLink(INSTALL, member.getId(), "Member laptop"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Organization-ID", team.getId().toString());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            // Old code resolved the request workspace (TEAM) and answered ACTIVE here, while the
            // CE, whose calls carry no active-workspace claim, got the FREE default.
            assertThat(ceLinkService.linkAccess(member.getId(), INSTALL).access())
                    .isEqualTo(CeLinkAccess.PLAN_REQUIRED);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("regression: a FREE account that finished the onboarding registers a new install")
    void onboardedFreeAccountRegisters() {
        User owner = userOwningDefaultWorkspace();
        subscribe(owner, "FREE");
        completeOnboarding(owner);

        var response = ceLinkService.register(owner.getId(), INSTALL, CE_VERSION, "Laptop",
                com.apimarketplace.auth.service.RequestAuditContext.none());

        assertThat(response.registered()).isTrue();
        assertThat(ceLinkRepository.findById(INSTALL)).isPresent();
    }

    @Test
    @DisplayName("regression: an account with an unverified email (bare Keycloak sign-up) cannot register, even on a paid plan: nothing is written")
    void unverifiedAccountRegisterWritesNothing() {
        User owner = userOwningDefaultWorkspace();
        subscribe(owner, "PRO");
        // Even a completed onboarding row does not count while the email is unverified.
        onboardingRepository.saveAndFlush(completedOnboardingRow(owner));

        var response = ceLinkService.register(owner.getId(), INSTALL, CE_VERSION, "Laptop",
                com.apimarketplace.auth.service.RequestAuditContext.none());

        assertThat(response.isOnboardingRequired()).isTrue();
        assertThat(ceLinkRepository.findById(INSTALL)).isEmpty();
    }

    @Test
    @DisplayName("a verified account that SKIPPED the onboarding steps registers: the web app counts a skip as done")
    void verifiedAndSkippedOnboardingRegisters() {
        User owner = userOwningDefaultWorkspace();
        owner.setEmailVerified(true);
        userRepository.saveAndFlush(owner);
        UserOnboarding skipped = new UserOnboarding(owner, "celink-skip-" + owner.getId());
        skipped.markSkipped();
        onboardingRepository.saveAndFlush(skipped);

        var response = ceLinkService.register(owner.getId(), INSTALL, CE_VERSION, "Laptop",
                com.apimarketplace.auth.service.RequestAuditContext.none());

        assertThat(response.registered()).isTrue();
        assertThat(ceLinkRepository.findById(INSTALL)).isPresent();
    }

    @Test
    @DisplayName("a verified account that has not finished the onboarding cannot register either")
    void verifiedButNotOnboardedRegisterWritesNothing() {
        User owner = userOwningDefaultWorkspace();
        owner.setEmailVerified(true);
        userRepository.saveAndFlush(owner);

        var response = ceLinkService.register(owner.getId(), INSTALL, CE_VERSION, "Laptop",
                com.apimarketplace.auth.service.RequestAuditContext.none());

        assertThat(response.isOnboardingRequired()).isTrue();
        assertThat(ceLinkRepository.findById(INSTALL)).isEmpty();
    }

    // ---- seeding ----

    private User newUser() {
        int n = SEQ.incrementAndGet();
        User user = new User("celinkuser" + n, "celink" + n + "@example.com", AuthProvider.KEYCLOAK,
                UUID.randomUUID().toString());
        user.setEnabled(true);
        user.setRoles(Set.of("USER"));
        user.setUserVersion(1L);
        return userRepository.saveAndFlush(user);
    }

    /** A user who is the OWNER of their own default workspace (the common solo shape). */
    private User userOwningDefaultWorkspace() {
        User user = newUser();
        Organization org = organizationRepository.saveAndFlush(
                new Organization("Solo", "solo-" + UUID.randomUUID(), true, user));
        memberRepository.saveAndFlush(new OrganizationMember(org, user, OrganizationRole.OWNER, true));
        return user;
    }

    /** The state the cloud onboarding leaves behind: email verified and onboarding completed. */
    private void completeOnboarding(User user) {
        user.setEmailVerified(true);
        userRepository.saveAndFlush(user);
        onboardingRepository.saveAndFlush(completedOnboardingRow(user));
    }

    private static UserOnboarding completedOnboardingRow(User user) {
        UserOnboarding onboarding = new UserOnboarding(user, "celink-" + user.getId());
        onboarding.setOnboardingCompleted(true);
        return onboarding;
    }

    private Subscription subscribe(User user, String planCode) {
        Plan plan = planRepository.findByCode(planCode)
                .orElseGet(() -> planRepository.saveAndFlush(new Plan(planCode, planCode + " plan", planCode)));
        BillingCustomer customer = billingCustomerRepository.findByUserId(user.getId())
                .orElseGet(() -> billingCustomerRepository.saveAndFlush(new BillingCustomer(user, "internal")));
        Subscription sub = new Subscription();
        sub.setBillingCustomer(customer);
        sub.setPlan(plan);
        sub.setStatus("active");
        sub.setCadence("monthly");
        sub.setProvider("internal");
        sub.setQuantity(1);
        sub.setCurrentPeriodStart(LocalDateTime.now().minusDays(10));
        sub.setCurrentPeriodEnd(LocalDateTime.now().plusDays(20));
        sub.setCancelAtPeriodEnd(false);
        return subscriptionRepository.saveAndFlush(sub);
    }
}
