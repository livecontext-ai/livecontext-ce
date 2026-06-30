package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.CeLink;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.CeLinkHeartbeatRepository;
import com.apimarketplace.auth.repository.CeLinkRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.CeLinkAuditService;
import com.apimarketplace.auth.service.CeLinkHeartbeatService;
import com.apimarketplace.auth.service.CeLinkService;
import com.apimarketplace.auth.service.CeLinkActiveRowCache;
import com.apimarketplace.auth.service.CeLinkActiveRowCachePublisher;
import com.apimarketplace.auth.service.IpHashService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Simulates the CE↔cloud entitlement re-validation end-to-end against a real DB
 * (H2 PostgreSQL-compat, full Spring context), exercising the production
 * {@link CeLinkHeartbeatService#heartbeat} + {@link CeLinkService#adminRevoke}
 * code paths - the cloud-side fix that cannot be e2e-tested against prod (prod
 * runs the pre-fix code).
 *
 * <p>Scenario: a CE install is linked while the cloud account has an active
 * subscription; the account then loses ALL active subscriptions (cancel/expire).
 * The very next heartbeat must REVOKE the link (→ 410 GONE → CE clears cloud
 * state), preventing a downgraded account from retaining cloud access "for life".
 *
 * <p>The keycloak-gated services ({@link CeLinkService}, {@link CeLinkHeartbeatService})
 * are constructed manually (they don't load under the embedded test profile);
 * the audit + cache collaborators are mocked (the JSONB audit row is irrelevant
 * to the revoke assertion and avoids an H2 type mismatch). Repositories are the
 * real Spring Data beans, so {@code findActiveByUserId} and the REVOKED write hit
 * the real database.
 */
@IntegrationTest
@DisplayName("CE cloud-link - heartbeat entitlement revoke (real DB)")
class CeLinkHeartbeatEntitlementIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private BillingCustomerRepository billingCustomerRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private CeLinkRepository ceLinkRepository;
    @Autowired private CeLinkHeartbeatRepository ceLinkHeartbeatRepository;

    private CeLinkHeartbeatService heartbeatService;
    private IpHashService ipHashService;

    private Long userId;
    private Subscription subscription;
    private static final UUID INSTALL = UUID.fromString("9d2b6f3a-1111-2222-3333-444455556666");
    private static final String CE_VERSION = "1.4.0";
    private static final String IP = "203.0.113.9";

    @BeforeEach
    void setUp() {
        // --- collaborators ---
        CeLinkAuditService auditService = mock(CeLinkAuditService.class);
        ipHashService = mock(IpHashService.class);
        // Only consulted on the OK (entitled) path; lenient so the revoke test doesn't trip strict stubs.
        lenient().when(ipHashService.hashWithCurrent(any(), any()))
                .thenReturn(new IpHashService.HashResult("hash-v1", 1));

        CeLinkService ceLinkService = new CeLinkService(
                ceLinkRepository, ceLinkHeartbeatRepository, userRepository, auditService,
                mock(CeLinkActiveRowCache.class), mock(CeLinkActiveRowCachePublisher.class),
                mock(ApplicationEventPublisher.class));
        heartbeatService = new CeLinkHeartbeatService(
                ceLinkRepository, ceLinkHeartbeatRepository, ipHashService, auditService,
                subscriptionRepository, ceLinkService);

        // --- seed: User → BillingCustomer → active Subscription, + an ACTIVE CeLink ---
        User user = new User("celinkuser", "celink@example.com", AuthProvider.KEYCLOAK,
                "f47ac10b-58cc-4372-a567-0e02b2c3d480");
        user.setEnabled(true);
        user.setRoles(Set.of("USER"));
        user.setUserVersion(1L);
        user = userRepository.saveAndFlush(user);
        userId = user.getId();

        Plan freePlan = new Plan("FREE", "Free Plan", "Basic free plan");
        freePlan.setIncludedToolCredits(100L);
        freePlan = planRepository.saveAndFlush(freePlan);

        BillingCustomer customer = billingCustomerRepository.saveAndFlush(new BillingCustomer(user, "internal"));

        subscription = new Subscription();
        subscription.setBillingCustomer(customer);
        subscription.setPlan(freePlan);
        subscription.setStatus("active");
        subscription.setCadence("monthly");
        subscription.setProvider("internal");
        subscription.setQuantity(1);
        subscription.setCurrentPeriodStart(LocalDateTime.now().minusDays(10));
        subscription.setCurrentPeriodEnd(LocalDateTime.now().plusDays(20));
        subscription.setCancelAtPeriodEnd(false);
        subscription = subscriptionRepository.saveAndFlush(subscription);

        ceLinkRepository.saveAndFlush(new CeLink(INSTALL, userId, "Test laptop"));
    }

    @Test
    @DisplayName("active subscription → heartbeat OK, link stays ACTIVE")
    void heartbeat_keeps_link_while_subscription_active() {
        CeLinkHeartbeatService.Outcome outcome = heartbeatService.heartbeat(userId, INSTALL, CE_VERSION, IP);

        assertThat(outcome).isEqualTo(CeLinkHeartbeatService.Outcome.OK);
        assertThat(ceLinkRepository.findById(INSTALL)).get()
                .extracting(CeLink::getStatus).isEqualTo(CeLink.Status.ACTIVE);
    }

    @Test
    @DisplayName("no active subscription (downgraded/canceled) → heartbeat REVOKES the link with SYSTEM reason")
    void heartbeat_revokes_link_when_subscription_lost() {
        // Simulate the downgrade/cancel: the only active subscription becomes 'canceled'.
        subscription.setStatus("canceled");
        subscriptionRepository.saveAndFlush(subscription);
        assertThat(subscriptionRepository.findActiveByUserId(userId)).isEmpty();

        CeLinkHeartbeatService.Outcome outcome = heartbeatService.heartbeat(userId, INSTALL, CE_VERSION, IP);

        assertThat(outcome).isEqualTo(CeLinkHeartbeatService.Outcome.REVOKED);
        CeLink revoked = ceLinkRepository.findById(INSTALL).orElseThrow();
        assertThat(revoked.getStatus()).isEqualTo(CeLink.Status.REVOKED);
        assertThat(revoked.getRevokeReason()).isEqualTo(CeLink.RevokeReason.SYSTEM);
    }
}
