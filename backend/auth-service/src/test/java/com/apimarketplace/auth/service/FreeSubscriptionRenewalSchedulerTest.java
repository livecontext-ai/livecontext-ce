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

/**
 * Routing-level tests for the internal renewal scheduler: which subscriptions it picks up,
 * what it delegates, and - critically - what it must NOT do itself.
 *
 * <p>These tests mock {@link CreditAttributionService}, so they can say nothing about whether
 * the credits actually persist. That is deliberate and it is also why this file alone was
 * blind to the production lost update: the defect lived in the transaction seam between the
 * scheduler and the real attribution service. The persistence contract is pinned separately
 * by {@code InternalRenewalCreditPersistenceTest} against a real database. What this file adds
 * is the structural guard that reintroducing the defect breaks a test here too: the scheduler
 * performs NO write of its own.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FreeSubscriptionRenewalScheduler Tests")
class FreeSubscriptionRenewalSchedulerTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private CreditAttributionService creditAttributionService;

    @Mock
    private AdminPlanService adminPlanService;

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
    @DisplayName("should renew expired FREE subscription: delegate with the new period start")
    void shouldRenewExpiredFreeSubscription() {
        LocalDateTime expiredEnd = LocalDateTime.of(2026, 1, 15, 0, 0);
        LocalDateTime before = LocalDateTime.now();
        Subscription sub = createExpiredFreeSubscription(expiredEnd);

        when(subscriptionRepository.findExpiredInternalSubscriptions(any(LocalDateTime.class)))
                .thenReturn(List.of(sub));

        scheduler.renewExpiredInternalSubscriptions();

        // The period start is handed to the attribution service, which advances the row and
        // derives the renewal sourceId from it inside ONE transaction.
        ArgumentCaptor<LocalDateTime> periodStart = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(creditAttributionService).attributeOnRenewal(eq(USER_ID), eq(sub), periodStart.capture());
        assertThat(periodStart.getValue()).isNotNull().isAfterOrEqualTo(before);

        // The expiry cutoff and the new period start must be the SAME instant: the guard that
        // skips an already-renewed row compares currentPeriodEnd against this value, so a
        // cutoff read later than the period start would make the comparison meaningless.
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(subscriptionRepository).findExpiredInternalSubscriptions(cutoff.capture());
        assertThat(periodStart.getValue()).isEqualTo(cutoff.getValue());
    }

    @Test
    @DisplayName("must never write the subscription row itself (lost-update guard)")
    void mustNotWriteTheSubscriptionItself() {
        LocalDateTime expiredEnd = LocalDateTime.of(2026, 1, 15, 0, 0);
        Subscription sub = createExpiredFreeSubscription(expiredEnd);

        when(subscriptionRepository.findExpiredInternalSubscriptions(any(LocalDateTime.class)))
                .thenReturn(List.of(sub));

        scheduler.renewExpiredInternalSubscriptions();

        // `sub` is detached (a @Scheduled thread has no persistence context) and the grant
        // lands on a re-resolved managed instance. ANY save from this loop merges the stale
        // pre-grant copy back over the fresh balance - which is exactly what production did.
        verify(subscriptionRepository, never()).save(any(Subscription.class));
        verify(subscriptionRepository, never()).saveAll(any());
        // Paired with a positive assertion so the test cannot pass by the loop doing nothing.
        verify(creditAttributionService).attributeOnRenewal(eq(USER_ID), eq(sub), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("announces a renewal only when one actually happened")
    void logsTheRealOutcome() {
        Subscription sub = createExpiredFreeSubscription(LocalDateTime.of(2026, 1, 15, 0, 0));
        when(subscriptionRepository.findExpiredInternalSubscriptions(any(LocalDateTime.class)))
                .thenReturn(List.of(sub));
        when(creditAttributionService.attributeOnRenewal(eq(USER_ID), eq(sub), any(LocalDateTime.class)))
                .thenReturn(CreditAttributionService.RenewalOutcome.ALREADY_RENEWED);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(FreeSubscriptionRenewalScheduler.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            scheduler.renewExpiredInternalSubscriptions();
        } finally {
            logger.detachAppender(appender);
        }

        // The pre-fix scheduler said "renewed" unconditionally. That is precisely why a renewal
        // granting nothing stayed invisible: the logs claimed success on the broken path.
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().matches(".*id=\\d+ renewed for.*"));
        assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains("already renewed by another actor"));
        // Expected interleaving, not an incident: it must not page.
        assertThat(appender.list)
                .filteredOn(e -> e.getFormattedMessage().contains("already renewed by another actor"))
                .allMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO);
    }

    @Test
    @DisplayName("announces the renewal when one really happened")
    void logsSuccessOnlyOnRenewed() {
        Subscription sub = createExpiredFreeSubscription(LocalDateTime.of(2026, 1, 15, 0, 0));
        when(subscriptionRepository.findExpiredInternalSubscriptions(any(LocalDateTime.class)))
                .thenReturn(List.of(sub));
        when(creditAttributionService.attributeOnRenewal(eq(USER_ID), eq(sub), any(LocalDateTime.class)))
                .thenReturn(CreditAttributionService.RenewalOutcome.RENEWED);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(FreeSubscriptionRenewalScheduler.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            scheduler.renewExpiredInternalSubscriptions();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().matches(".*id=\\d+ renewed for.*"));
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("NOT renewed"));
    }

    @Test
    @DisplayName("should do nothing when no expired FREE subscriptions found")
    void shouldDoNothingWhenNoExpired() {
        when(subscriptionRepository.findExpiredInternalSubscriptions(any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());

        scheduler.renewExpiredInternalSubscriptions();

        verify(creditAttributionService, never())
                .attributeOnRenewal(anyLong(), any(Subscription.class), any(LocalDateTime.class));
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

        // First subscription throws, second should still be processed. Per-row isolation is
        // the reason the loop is not wrapped in a single transaction.
        doThrow(new RuntimeException("DB error"))
                .when(creditAttributionService).attributeOnRenewal(eq(USER_ID), eq(sub1), any(LocalDateTime.class));

        scheduler.renewExpiredInternalSubscriptions();

        verify(creditAttributionService).attributeOnRenewal(eq(USER_ID), eq(sub2), any(LocalDateTime.class));
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

        verify(creditAttributionService, times(2))
                .attributeOnRenewal(anyLong(), any(Subscription.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("all subscriptions in one pass share the same new period start")
    void allSubscriptionsInAPassShareTheSamePeriodStart() {
        Subscription sub1 = createExpiredFreeSubscription(LocalDateTime.of(2026, 1, 10, 0, 0));
        sub1.setId(201L);
        Subscription sub2 = createExpiredFreeSubscription(LocalDateTime.of(2026, 1, 20, 0, 0));
        sub2.setId(202L);

        when(subscriptionRepository.findExpiredInternalSubscriptions(any(LocalDateTime.class)))
                .thenReturn(List.of(sub1, sub2));

        scheduler.renewExpiredInternalSubscriptions();

        ArgumentCaptor<LocalDateTime> starts = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(creditAttributionService, times(2))
                .attributeOnRenewal(anyLong(), any(Subscription.class), starts.capture());
        // One `now` captured once, outside the loop: the renewal instant is the pass, not the row.
        List<LocalDateTime> captured = starts.getAllValues();
        assertThat(captured).hasSize(2);
        assertThat(captured.get(1)).isEqualTo(captured.get(0));
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

        // Assert - the comp PRO row is renewed exactly like a FREE row.
        verify(creditAttributionService).attributeOnRenewal(eq(USER_ID), eq(compPro), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("V549: a timed comp past its end reverts to FREE and is NOT also renewed")
    void expiredTimedCompRevertsInsteadOfRenewing() {
        Subscription sub = createExpiredFreeSubscription(LocalDateTime.of(2026, 1, 15, 0, 0));
        sub.setCompEndsAt(LocalDateTime.of(2026, 1, 10, 0, 0));
        when(subscriptionRepository.findExpiredInternalSubscriptions(any(LocalDateTime.class)))
                .thenReturn(List.of(sub));
        when(adminPlanService.revertExpiredComp(eq(USER_ID), any(LocalDateTime.class))).thenReturn(true);

        scheduler.renewExpiredInternalSubscriptions();

        verify(adminPlanService).revertExpiredComp(eq(USER_ID), any(LocalDateTime.class));
        verify(creditAttributionService, never()).attributeOnRenewal(any(), any(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("V549: a timed comp still running renews normally and is never reverted")
    void runningTimedCompRenewsNormally() {
        Subscription sub = createExpiredFreeSubscription(LocalDateTime.of(2026, 1, 15, 0, 0));
        sub.setCompEndsAt(LocalDateTime.now().plusDays(30));
        when(subscriptionRepository.findExpiredInternalSubscriptions(any(LocalDateTime.class)))
                .thenReturn(List.of(sub));

        scheduler.renewExpiredInternalSubscriptions();

        verify(adminPlanService, never()).revertExpiredComp(any(), any());
        verify(creditAttributionService).attributeOnRenewal(eq(USER_ID), eq(sub), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("V549: when the revert declines (row changed under the lock) the sub still renews")
    void declinedRevertFallsBackToRenewal() {
        Subscription sub = createExpiredFreeSubscription(LocalDateTime.of(2026, 1, 15, 0, 0));
        sub.setCompEndsAt(LocalDateTime.of(2026, 1, 10, 0, 0));
        when(subscriptionRepository.findExpiredInternalSubscriptions(any(LocalDateTime.class)))
                .thenReturn(List.of(sub));
        when(adminPlanService.revertExpiredComp(eq(USER_ID), any(LocalDateTime.class))).thenReturn(false);

        scheduler.renewExpiredInternalSubscriptions();

        verify(creditAttributionService).attributeOnRenewal(eq(USER_ID), eq(sub), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("V549: a timed comp ends on its own date, mid-period, without waiting for the monthly renewal")
    void endedCompRevertsMidPeriod() {
        Subscription sub = createExpiredFreeSubscription(LocalDateTime.now().plusDays(20)); // period still running
        sub.setCompEndsAt(LocalDateTime.now().minusMinutes(5));
        when(subscriptionRepository.findEndedInternalComps(any(LocalDateTime.class))).thenReturn(List.of(sub));
        when(subscriptionRepository.findExpiredInternalSubscriptions(any(LocalDateTime.class))).thenReturn(List.of());

        scheduler.renewExpiredInternalSubscriptions();

        verify(adminPlanService).revertExpiredComp(eq(USER_ID), any(LocalDateTime.class));
        verify(creditAttributionService, never()).attributeOnRenewal(any(), any(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("V549: a failing revert does not stop the renewal pass for everyone else")
    void failingRevertDoesNotStopRenewals() {
        Subscription comp = createExpiredFreeSubscription(LocalDateTime.now().plusDays(20));
        comp.setCompEndsAt(LocalDateTime.now().minusMinutes(5));
        Subscription other = createExpiredFreeSubscription(LocalDateTime.of(2026, 1, 15, 0, 0));
        when(subscriptionRepository.findEndedInternalComps(any(LocalDateTime.class))).thenReturn(List.of(comp));
        when(adminPlanService.revertExpiredComp(eq(USER_ID), any(LocalDateTime.class))).thenThrow(new RuntimeException("boom"));
        when(subscriptionRepository.findExpiredInternalSubscriptions(any(LocalDateTime.class))).thenReturn(List.of(other));

        scheduler.renewExpiredInternalSubscriptions();

        verify(creditAttributionService).attributeOnRenewal(eq(USER_ID), eq(other), any(LocalDateTime.class));
    }
}
