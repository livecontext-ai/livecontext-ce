package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FreeSubscriptionRenewalScheduler Tests")
class FreeSubscriptionRenewalSchedulerTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private CreditAttributionService creditAttributionService;

    @InjectMocks
    private FreeSubscriptionRenewalScheduler scheduler;

    private static final Long USER_ID = 42L;

    private Plan freePlan;

    @BeforeEach
    void setUp() {
        freePlan = new Plan();
        freePlan.setId(1L);
        freePlan.setCode("FREE");
        freePlan.setName("FREE");
        freePlan.setIncludedToolCredits(1000L);
    }

    private Subscription createExpiredFreeSubscription(LocalDateTime periodEnd) {
        User user = new User();
        user.setId(USER_ID);

        BillingCustomer bc = new BillingCustomer(user, "internal");
        bc.setId(10L);

        Subscription sub = new Subscription();
        sub.setId(100L);
        sub.setPlan(freePlan);
        sub.setBillingCustomer(bc);
        sub.setStatus("active");
        sub.setCadence("monthly");
        sub.setCurrentPeriodStart(periodEnd.minusMonths(1));
        sub.setCurrentPeriodEnd(periodEnd);
        sub.setRemainingCredits(new BigDecimal("200"));
        sub.setCreditQuantity(0);
        return sub;
    }

    @Test
    @DisplayName("should renew expired FREE subscription: reset credits + roll period forward")
    void shouldRenewExpiredFreeSubscription() {
        LocalDateTime expiredEnd = LocalDateTime.of(2026, 1, 15, 0, 0);
        Subscription sub = createExpiredFreeSubscription(expiredEnd);

        when(subscriptionRepository.findExpiredInternalSubscriptions(any(LocalDateTime.class)))
                .thenReturn(List.of(sub));

        scheduler.renewExpiredInternalSubscriptions();

        // Should call attributeOnRenewal with the subscription (sourceId derived internally)
        verify(creditAttributionService).attributeOnRenewal(eq(USER_ID), eq(sub));

        // Should save with rolled-forward period
        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(captor.capture());
        Subscription saved = captor.getValue();
        assertThat(saved.getCurrentPeriodEnd()).isAfter(saved.getCurrentPeriodStart());
    }

    @Test
    @DisplayName("should do nothing when no expired FREE subscriptions found")
    void shouldDoNothingWhenNoExpired() {
        when(subscriptionRepository.findExpiredInternalSubscriptions(any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());

        scheduler.renewExpiredInternalSubscriptions();

        verify(creditAttributionService, never()).attributeOnRenewal(anyLong(), any(Subscription.class));
        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }

    @Test
    @DisplayName("should continue processing remaining subscriptions when one fails")
    void shouldContinueOnException() {
        LocalDateTime expiredEnd1 = LocalDateTime.of(2026, 1, 15, 0, 0);
        LocalDateTime expiredEnd2 = LocalDateTime.of(2026, 1, 20, 0, 0);

        Subscription sub1 = createExpiredFreeSubscription(expiredEnd1);
        sub1.setId(101L);

        Subscription sub2 = createExpiredFreeSubscription(expiredEnd2);
        sub2.setId(102L);

        when(subscriptionRepository.findExpiredInternalSubscriptions(any(LocalDateTime.class)))
                .thenReturn(List.of(sub1, sub2));

        // First subscription throws, second should still be processed
        doThrow(new RuntimeException("DB error"))
                .when(creditAttributionService).attributeOnRenewal(eq(USER_ID), eq(sub1));

        scheduler.renewExpiredInternalSubscriptions();

        // sub2 should still be renewed
        verify(creditAttributionService).attributeOnRenewal(eq(USER_ID), eq(sub2));
        verify(subscriptionRepository).save(sub2);

        // sub1 should NOT be saved (failed before save)
        verify(subscriptionRepository, never()).save(sub1);
    }

    @Test
    @DisplayName("should renew multiple expired FREE subscriptions")
    void shouldRenewMultipleExpiredSubscriptions() {
        LocalDateTime expiredEnd1 = LocalDateTime.of(2026, 1, 10, 0, 0);
        LocalDateTime expiredEnd2 = LocalDateTime.of(2026, 1, 20, 0, 0);

        Subscription sub1 = createExpiredFreeSubscription(expiredEnd1);
        sub1.setId(201L);

        User user2 = new User();
        user2.setId(99L);
        BillingCustomer bc2 = new BillingCustomer(user2, "internal");
        bc2.setId(20L);
        Subscription sub2 = createExpiredFreeSubscription(expiredEnd2);
        sub2.setId(202L);
        sub2.setBillingCustomer(bc2);

        when(subscriptionRepository.findExpiredInternalSubscriptions(any(LocalDateTime.class)))
                .thenReturn(List.of(sub1, sub2));

        scheduler.renewExpiredInternalSubscriptions();

        verify(creditAttributionService, times(2)).attributeOnRenewal(anyLong(), any(Subscription.class));
        verify(subscriptionRepository, times(2)).save(any(Subscription.class));
    }

    @Test
    @DisplayName("renews an admin-granted comp PRO internal subscription (broadened from FREE-only to provider=internal)")
    void renewsCompProInternalSubscription() {
        // Arrange - a comp PRO sub is provider='internal' (no Stripe), plan=PRO.
        // Pre-fix the query keyed on plan.code='FREE' and would have skipped this row,
        // leaving the comp user's credits un-renewed.
        Plan proPlan = new Plan();
        proPlan.setId(3L);
        proPlan.setCode("PRO");
        proPlan.setName("Pro");

        User user = new User();
        user.setId(USER_ID);
        BillingCustomer bc = new BillingCustomer(user, "internal");
        bc.setId(30L);

        Subscription compPro = new Subscription();
        compPro.setId(300L);
        compPro.setPlan(proPlan);
        compPro.setBillingCustomer(bc);
        compPro.setStatus("active");
        compPro.setCadence("monthly");
        compPro.setProvider("internal");
        compPro.setCurrentPeriodStart(LocalDateTime.of(2026, 1, 1, 0, 0));
        compPro.setCurrentPeriodEnd(LocalDateTime.of(2026, 2, 1, 0, 0));
        compPro.setCreditQuantity(0);

        when(subscriptionRepository.findExpiredInternalSubscriptions(any(LocalDateTime.class)))
                .thenReturn(List.of(compPro));

        // Act
        scheduler.renewExpiredInternalSubscriptions();

        // Assert - the comp PRO row is renewed exactly like a FREE row (period rolled, credits re-granted).
        verify(creditAttributionService).attributeOnRenewal(eq(USER_ID), eq(compPro));
        verify(subscriptionRepository).save(compPro);
        assertThat(compPro.getCurrentPeriodEnd()).isAfter(compPro.getCurrentPeriodStart());
    }

    @Test
    @DisplayName("period should roll forward by 1 month after renewal")
    void shouldRollPeriodForwardByOneMonth() {
        LocalDateTime expiredEnd = LocalDateTime.of(2026, 1, 15, 0, 0);
        Subscription sub = createExpiredFreeSubscription(expiredEnd);

        when(subscriptionRepository.findExpiredInternalSubscriptions(any(LocalDateTime.class)))
                .thenReturn(List.of(sub));

        scheduler.renewExpiredInternalSubscriptions();

        // After renewal, currentPeriodStart = now, currentPeriodEnd = now + 1 month
        assertThat(sub.getCurrentPeriodStart()).isNotNull();
        assertThat(sub.getCurrentPeriodEnd()).isNotNull();
        assertThat(sub.getCurrentPeriodEnd()).isAfter(sub.getCurrentPeriodStart());
        // The period should be approximately 1 month apart
        assertThat(sub.getCurrentPeriodEnd().getMonthValue())
                .isNotEqualTo(sub.getCurrentPeriodStart().getMonthValue());
    }
}
