package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.AdminPlanService.AssignPlanResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * V549 timed complimentary plans: a partner creator code grants PRO until a date, the
 * internal renewal reverts it to FREE after that date, and an admin grant stays permanent.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminPlanService timed comp (V549)")
class AdminPlanServiceTimedCompTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private BillingCustomerRepository billingCustomerRepository;
    @Mock private PlanRepository planRepository;
    @Mock private UserRepository userRepository;
    @Mock private CreditAttributionService creditAttributionService;
    @Mock private PlanStorageQuotaSyncer quotaSyncer;
    @Mock private SubscriptionCacheBuster subscriptionCacheBuster;
    @Mock private OrganizationService organizationService;

    @InjectMocks private AdminPlanService service;

    private static final Long USER_ID = 42L;

    private Plan plan(String code) {
        Plan p = new Plan();
        p.setId(7L);
        p.setCode(code);
        p.setName(code);
        return p;
    }

    private Subscription sub(String provider, String planCode, LocalDateTime compEndsAt) {
        Subscription s = new Subscription();
        s.setId(100L);
        s.setProvider(provider);
        s.setPlan(plan(planCode));
        s.setStatus("active");
        s.setCompEndsAt(compEndsAt);
        return s;
    }

    private void stubFullAssign(String planCode, Subscription current) {
        when(planRepository.findByCode(planCode)).thenReturn(Optional.of(plan(planCode)));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(mock(User.class)));
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(current));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("a FREE account redeeming a creator code gets PRO with the code's end date")
    void freeAccountGetsTimedPro() {
        LocalDateTime endsAt = LocalDateTime.now().plusDays(90);
        stubFullAssign("PRO", sub("internal", "FREE", null));

        AssignPlanResult result = service.grantTimedComp(USER_ID, "PRO", endsAt);

        assertThat(result.success()).isTrue();
        ArgumentCaptor<Subscription> saved = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(saved.capture());
        assertThat(saved.getValue().getPlan().getCode()).isEqualTo("PRO");
        assertThat(saved.getValue().getCompEndsAt()).isEqualTo(endsAt);
        verify(creditAttributionService).attributeOnRenewal(eq(USER_ID), any(Subscription.class));
    }

    @Test
    @DisplayName("an account on a HIGHER timed tier (TEAM from another code) is never downgraded by a PRO creator code")
    void neverDowngradesHigherTier() {
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                .thenReturn(Optional.of(sub("internal", "TEAM", LocalDateTime.now().plusDays(30))));

        AssignPlanResult result = service.grantTimedComp(USER_ID, "PRO", LocalDateTime.now().plusDays(90));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("already_on_higher_plan");
        verify(subscriptionRepository, never()).save(any());
        verifyNoInteractions(creditAttributionService);
    }

    @Test
    @DisplayName("a permanent PRO (admin grant) is never turned into a timed one")
    void permanentSameTierIsLeftAlone() {
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                .thenReturn(Optional.of(sub("internal", "PRO", null)));

        AssignPlanResult result = service.grantTimedComp(USER_ID, "PRO", LocalDateTime.now().plusDays(90));

        assertThat(result.error()).isEqualTo("already_on_permanent_plan");
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("a permanent LOWER comp (admin STARTER) is never replaced: the timed PRO would revert to FREE and lose it")
    void permanentLowerCompIsNotReplaced() {
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                .thenReturn(Optional.of(sub("internal", "STARTER", null)));

        AssignPlanResult result = service.grantTimedComp(USER_ID, "PRO", LocalDateTime.now().plusDays(90));

        assertThat(result.error()).isEqualTo("already_on_permanent_plan");
        verify(subscriptionRepository, never()).save(any());
        verifyNoInteractions(creditAttributionService);
    }

    @Test
    @DisplayName("a timed PRO ending earlier is extended to the later date without re-anchoring the cycle")
    void timedSameTierIsExtendedOnly() {
        LocalDateTime earlier = LocalDateTime.now().plusDays(10);
        LocalDateTime later = LocalDateTime.now().plusDays(90);
        Subscription current = sub("internal", "PRO", earlier);
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(current));

        AssignPlanResult result = service.grantTimedComp(USER_ID, "PRO", later);

        assertThat(result.success()).isTrue();
        assertThat(current.getCompEndsAt()).isEqualTo(later);
        verify(subscriptionRepository).save(current);
        verifyNoInteractions(creditAttributionService);
    }

    @Test
    @DisplayName("a timed PRO is never SHORTENED by a code whose end date is earlier")
    void timedSameTierIsNeverShortened() {
        LocalDateTime later = LocalDateTime.now().plusDays(90);
        Subscription current = sub("internal", "PRO", later);
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(current));

        service.grantTimedComp(USER_ID, "PRO", LocalDateTime.now().plusDays(5));

        assertThat(current.getCompEndsAt()).isEqualTo(later);
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("an active paid Stripe subscription is refused, exactly as the admin grant refuses it")
    void paidStripeSubscriptionIsRefused() {
        when(planRepository.findByCode("PRO")).thenReturn(Optional.of(plan("PRO")));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(mock(User.class)));
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                .thenReturn(Optional.of(sub("stripe", "STARTER", null)));

        AssignPlanResult result = service.grantTimedComp(USER_ID, "PRO", LocalDateTime.now().plusDays(90));

        assertThat(result.error()).isEqualTo("has_paid_subscription");
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("an admin grant is permanent: it clears the end date a creator code had set")
    void adminGrantClearsCompEnd() {
        stubFullAssign("PRO", sub("internal", "PRO", LocalDateTime.now().plusDays(30)));

        service.assignPlan(USER_ID, "PRO", 1L);

        ArgumentCaptor<Subscription> saved = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(saved.capture());
        assertThat(saved.getValue().getCompEndsAt()).isNull();
    }

    @Test
    @DisplayName("revertExpiredComp: a timed PRO past its end date reverts to FREE and loses the end date")
    void expiredCompRevertsToFree() {
        LocalDateTime now = LocalDateTime.now();
        stubFullAssign("FREE", sub("internal", "PRO", now.minusHours(1)));

        boolean reverted = service.revertExpiredComp(USER_ID, now);

        assertThat(reverted).isTrue();
        ArgumentCaptor<Subscription> saved = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(saved.capture());
        assertThat(saved.getValue().getPlan().getCode()).isEqualTo("FREE");
        assertThat(saved.getValue().getCompEndsAt()).isNull();
    }

    @Test
    @DisplayName("revertExpiredComp: a comp still running, or a sub that became paid meanwhile, is left alone")
    void notExpiredOrPaidIsLeftAlone() {
        LocalDateTime now = LocalDateTime.now();
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                .thenReturn(Optional.of(sub("internal", "PRO", now.plusDays(3))))
                .thenReturn(Optional.of(sub("stripe", "PRO", now.minusDays(3))));

        assertThat(service.revertExpiredComp(USER_ID, now)).isFalse();
        assertThat(service.revertExpiredComp(USER_ID, now)).isFalse();
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("timedCompRefusal probes the SAME rule without writing (permanent, higher, paid, grantable)")
    void refusalProbeMatchesTheGrantRule() {
        when(subscriptionRepository.findActiveByUserId(USER_ID))
                .thenReturn(Optional.of(sub("internal", "STARTER", null)))
                .thenReturn(Optional.of(sub("internal", "TEAM", LocalDateTime.now().plusDays(9))))
                .thenReturn(Optional.of(sub("stripe", "STARTER", null)))
                .thenReturn(Optional.of(sub("internal", "FREE", null)));

        assertThat(service.timedCompRefusal(USER_ID, "PRO", LocalDateTime.now().plusDays(90))).isEqualTo("already_on_permanent_plan");
        assertThat(service.timedCompRefusal(USER_ID, "PRO", LocalDateTime.now().plusDays(90))).isEqualTo("already_on_higher_plan");
        assertThat(service.timedCompRefusal(USER_ID, "PRO", LocalDateTime.now().plusDays(90))).isEqualTo("has_paid_subscription");
        assertThat(service.timedCompRefusal(USER_ID, "PRO", LocalDateTime.now().plusDays(90))).isNull();
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("inside a transaction the cache bust waits for COMMIT: a rolled-back redeem never announces a plan")
    void sideEffectsWaitForCommit() {
        stubFullAssign("PRO", sub("internal", "FREE", null));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.grantTimedComp(USER_ID, "PRO", LocalDateTime.now().plusDays(90));

            verify(subscriptionCacheBuster, never()).fanOutForOwner(any(), any());
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            verify(subscriptionCacheBuster).fanOutForOwner(USER_ID, "admin.plan.grant");
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("probe: a same-tier comp already running PAST the code's end would gain nothing -> refused")
    void probeRefusesANoOpExtension() {
        when(subscriptionRepository.findActiveByUserId(USER_ID))
                .thenReturn(Optional.of(sub("internal", "PRO", LocalDateTime.now().plusDays(200))));

        assertThat(service.timedCompRefusal(USER_ID, "PRO", LocalDateTime.now().plusDays(90)))
                .isEqualTo("already_on_plan_until_later");
    }

    @Test
    @DisplayName("analytics: a creator-code grant is a planGranted event, a comp EXPIRY is not")
    void expiryIsNotAPlanGrantEvent() {
        com.apimarketplace.auth.analytics.AuthAnalyticsEmitter analytics =
                mock(com.apimarketplace.auth.analytics.AuthAnalyticsEmitter.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "analytics", analytics);
        LocalDateTime now = LocalDateTime.now();
        stubFullAssign("FREE", sub("internal", "PRO", now.minusHours(1)));

        service.revertExpiredComp(USER_ID, now);

        verify(analytics, never()).planGranted(any(), any(), any());
    }
}
