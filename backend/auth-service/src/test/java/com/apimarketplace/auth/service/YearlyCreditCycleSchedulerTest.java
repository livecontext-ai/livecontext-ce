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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Routing-level tests for the yearly credit-cycle scheduler: which rows it hands over, with
 * what clock, and what it must NOT do itself. Whether the credits actually land is pinned by
 * {@code YearlyCreditCyclePersistenceTest} against a real Postgres, for the same reason the
 * internal scheduler has one: the lost-update defect lives in the transaction seam, where a
 * mocked attribution service is blind.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("YearlyCreditCycleScheduler Tests")
class YearlyCreditCycleSchedulerTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private CreditAttributionService creditAttributionService;

    @Mock
    private SubscriptionCacheBuster subscriptionCacheBuster;

    @InjectMocks
    private YearlyCreditCycleScheduler scheduler;

    private Plan teamPlan;

    @BeforeEach
    void setUp() {
        teamPlan = new Plan();
        teamPlan.setId(23L);
        teamPlan.setCode("TEAM");
        teamPlan.setName("Team");
    }

    private Subscription yearlyStripeSub(long subId, long userId) {
        User user = new User();
        user.setId(userId);
        BillingCustomer bc = new BillingCustomer(user, "stripe");
        bc.setId(subId * 10);

        Subscription sub = new Subscription();
        sub.setId(subId);
        sub.setPlan(teamPlan);
        sub.setBillingCustomer(bc);
        sub.setProvider("stripe");
        sub.setCadence("yearly");
        sub.setStatus("active");
        sub.setCreditQuantity(100);
        LocalDateTime periodStart = LocalDateTime.of(2026, 9, 14, 23, 53, 9);
        sub.setCurrentPeriodStart(periodStart);
        sub.setCurrentPeriodEnd(periodStart.plusMonths(12));
        sub.setRemainingCredits(new BigDecimal("-100.8285"));
        return sub;
    }

    @Test
    @DisplayName("hands every active yearly Stripe subscription to the attribution service with ONE clock for the pass")
    void delegatesEachCandidateWithOneClock() {
        Subscription a = yearlyStripeSub(84L, 121L);
        Subscription b = yearlyStripeSub(85L, 122L);
        when(subscriptionRepository.findActiveYearlyStripeSubscriptions()).thenReturn(List.of(a, b));
        when(creditAttributionService.attributeMonthlyCreditCycle(anyLong(), any(), any()))
                .thenReturn(CreditAttributionService.MonthlyCycleOutcome.NOT_DUE);
        LocalDateTime before = LocalDateTime.now();

        scheduler.grantDueMonthlyCycles();

        ArgumentCaptor<LocalDateTime> clock = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(creditAttributionService).attributeMonthlyCreditCycle(eq(121L), eq(a), clock.capture());
        verify(creditAttributionService).attributeMonthlyCreditCycle(eq(122L), eq(b), clock.capture());
        assertThat(clock.getAllValues()).hasSize(2);
        // One instant for the whole pass, so two rows on the same anchor are judged alike.
        assertThat(clock.getAllValues().get(0)).isEqualTo(clock.getAllValues().get(1)).isAfterOrEqualTo(before);
    }

    @Test
    @DisplayName("must never write the subscription row itself (lost-update guard)")
    void mustNotWriteTheSubscriptionItself() {
        Subscription sub = yearlyStripeSub(84L, 121L);
        when(subscriptionRepository.findActiveYearlyStripeSubscriptions()).thenReturn(List.of(sub));
        when(creditAttributionService.attributeMonthlyCreditCycle(anyLong(), any(), any()))
                .thenReturn(CreditAttributionService.MonthlyCycleOutcome.GRANTED);

        scheduler.grantDueMonthlyCycles();

        // `sub` is detached (a @Scheduled thread has no persistence context) and the grant
        // lands on a re-resolved managed instance. ANY save from this loop merges the stale
        // pre-grant copy back over the fresh balance.
        verify(subscriptionRepository, never()).save(any(Subscription.class));
        verify(subscriptionRepository, never()).saveAll(any());
        verify(creditAttributionService).attributeMonthlyCreditCycle(eq(121L), eq(sub), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("busts the owner's gateway cache only when a cycle was actually granted")
    void bustsTheCacheOnlyOnAGrant() {
        Subscription granted = yearlyStripeSub(84L, 121L);
        Subscription notDue = yearlyStripeSub(85L, 122L);
        Subscription skipped = yearlyStripeSub(86L, 123L);
        Subscription absorbed = yearlyStripeSub(87L, 124L);
        when(subscriptionRepository.findActiveYearlyStripeSubscriptions()).thenReturn(List.of(granted, notDue, skipped, absorbed));
        when(creditAttributionService.attributeMonthlyCreditCycle(eq(121L), any(), any()))
                .thenReturn(CreditAttributionService.MonthlyCycleOutcome.GRANTED);
        when(creditAttributionService.attributeMonthlyCreditCycle(eq(122L), any(), any()))
                .thenReturn(CreditAttributionService.MonthlyCycleOutcome.NOT_DUE);
        when(creditAttributionService.attributeMonthlyCreditCycle(eq(123L), any(), any()))
                .thenReturn(CreditAttributionService.MonthlyCycleOutcome.SKIPPED);
        when(creditAttributionService.attributeMonthlyCreditCycle(eq(124L), any(), any()))
                .thenReturn(CreditAttributionService.MonthlyCycleOutcome.ABSORBED);

        scheduler.grantDueMonthlyCycles();

        // A TEAM yearly re-fills the wallet of every member of the owner's workspaces; the
        // gateway caches the plan for minutes, so the fan-out is what unblocks them now. An
        // ABSORBED pass wrote nothing, so it must not pretend otherwise.
        verify(subscriptionCacheBuster).fanOutForOwner(eq(121L), anyString());
        verify(subscriptionCacheBuster, never()).fanOutForOwner(eq(122L), anyString());
        verify(subscriptionCacheBuster, never()).fanOutForOwner(eq(123L), anyString());
        verify(subscriptionCacheBuster, never()).fanOutForOwner(eq(124L), anyString());
    }

    @Test
    @DisplayName("one row blowing up does not stop the others from being evaluated")
    void oneFailureDoesNotStopThePass() {
        Subscription broken = yearlyStripeSub(84L, 121L);
        Subscription fine = yearlyStripeSub(85L, 122L);
        when(subscriptionRepository.findActiveYearlyStripeSubscriptions()).thenReturn(List.of(broken, fine));
        when(creditAttributionService.attributeMonthlyCreditCycle(eq(121L), any(), any()))
                .thenThrow(new RuntimeException("db hiccup"));
        when(creditAttributionService.attributeMonthlyCreditCycle(eq(122L), any(), any()))
                .thenReturn(CreditAttributionService.MonthlyCycleOutcome.GRANTED);

        scheduler.grantDueMonthlyCycles();

        verify(creditAttributionService).attributeMonthlyCreditCycle(eq(122L), eq(fine), any(LocalDateTime.class));
        verify(subscriptionCacheBuster).fanOutForOwner(eq(122L), anyString());
    }

    @Test
    @DisplayName("with no yearly Stripe subscription the pass does nothing at all")
    void noCandidatesNoWork() {
        when(subscriptionRepository.findActiveYearlyStripeSubscriptions()).thenReturn(Collections.emptyList());

        scheduler.grantDueMonthlyCycles();

        verifyNoInteractions(creditAttributionService);
        verifyNoInteractions(subscriptionCacheBuster);
    }
}
