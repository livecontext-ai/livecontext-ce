package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CreditReconciliationLog;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.CreditReconciliationLogRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreditReconciliationService")
class CreditReconciliationServiceTest {

    @Mock private CreditLedgerRepository ledgerRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private CreditReconciliationLogRepository reconciliationLogRepository;
    @Mock private CreditConsumptionDeadLetterService deadLetterService;

    private CreditReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new CreditReconciliationService(
                ledgerRepository, subscriptionRepository, reconciliationLogRepository,
                deadLetterService, /*unlimited*/ false);
        lenient().when(deadLetterService.countPendingForTenant(anyString())).thenReturn(0L);
    }

    private Subscription subWithBuckets(BigDecimal sub, BigDecimal payg) {
        Subscription s = new Subscription();
        s.setId(10L);
        s.setStatus("active");
        s.setCurrentPeriodStart(LocalDateTime.now().minusDays(5));
        s.setRemainingCredits(sub);
        s.setPaygRemainingCredits(payg);
        return s;
    }

    @Test
    @DisplayName("V250 regression: PAYG top-up mid-period does NOT trigger phantom drift - without two-bucket awareness this paged ops on every PAYG user")
    void paygTopupMidPeriodDoesNotDriftFalsely() {
        // Period grant of 100 sub credits + a mid-period PAYG top-up of 200 →
        // ledger sum = 100 + 200 = 300. Sub bucket = 100, PAYG bucket = 200,
        // total balance = 300. Drift = 0, no log.
        Subscription sub = subWithBuckets(new BigDecimal("100"), new BigDecimal("200"));
        when(ledgerRepository.findAllDistinctUserIds()).thenReturn(List.of(1L));
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.of(sub));
        when(ledgerRepository.sumAmountByUserIdSince(eq(1L), any())).thenReturn(new BigDecimal("300"));

        service.reconcile();

        // Critical: pre-fix this would have logged drift = 100 - 300 = -200 (the PAYG amount).
        verify(reconciliationLogRepository, never()).save(any(CreditReconciliationLog.class));
    }

    @Test
    @DisplayName("Real drift on the sub bucket is still detected when the PAYG balance is zero")
    void realSubDriftStillDetected() {
        // Sub bucket = 30 but ledger says 100 → 70 credits unaccounted for (drift).
        // A real drift is off in the lifetime books too (same missing movement),
        // so it must stay UNEXPLAINED.
        Subscription sub = subWithBuckets(new BigDecimal("30"), BigDecimal.ZERO);
        when(ledgerRepository.findAllDistinctUserIds()).thenReturn(List.of(1L));
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.of(sub));
        when(ledgerRepository.sumAmountByUserIdSince(eq(1L), any())).thenReturn(new BigDecimal("100"));
        when(ledgerRepository.sumAmountByUserIdExcludingReleasedReserves(1L)).thenReturn(new BigDecimal("100"));

        service.reconcile();

        ArgumentCaptor<CreditReconciliationLog> logCaptor = ArgumentCaptor.forClass(CreditReconciliationLog.class);
        verify(reconciliationLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getDrift()).isEqualByComparingTo("-70");
        assertThat(logCaptor.getValue().isExplained()).isFalse();
    }

    @Test
    @DisplayName("Real drift on the PAYG bucket alone is detected (catches grantPaygTopup writing the wrong amount)")
    void realPaygDriftDetected() {
        // Sub = 0 + PAYG = 50, ledger sum says 200 - somehow the grant landed at half value.
        Subscription sub = subWithBuckets(BigDecimal.ZERO, new BigDecimal("50"));
        when(ledgerRepository.findAllDistinctUserIds()).thenReturn(List.of(1L));
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.of(sub));
        when(ledgerRepository.sumAmountByUserIdSince(eq(1L), any())).thenReturn(new BigDecimal("200"));
        when(ledgerRepository.sumAmountByUserIdExcludingReleasedReserves(1L)).thenReturn(new BigDecimal("200"));

        service.reconcile();

        ArgumentCaptor<CreditReconciliationLog> logCaptor = ArgumentCaptor.forClass(CreditReconciliationLog.class);
        verify(reconciliationLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().isExplained()).isFalse();
    }

    @Test
    @DisplayName("PAYG carried over from an earlier period is logged as EXPLAINED - the lifetime books balance, only the period baseline is off")
    void paygCarryOverFromEarlierPeriodIsExplained() {
        // Regression: a PAYG top-up bought in a PREVIOUS period persists across
        // renewal but has no ledger row inside the current period. Pre-fix the
        // period-scoped comparison reported "CREDIT DRIFT (unexplained)" equal to
        // the carried balance for every such user, every day - pure alert noise.
        // Current period: PLAN_RESET(-0) + PLAN_GRANT(+100) → period sum = 100.
        // Balance = 100 sub + 200 carried PAYG = 300. Period drift = +200.
        // Lifetime (excluding balance-neutral RELEASED reservation rows, which
        // keep their -reserved audit amount): sum = 300 = balance.
        Subscription sub = subWithBuckets(new BigDecimal("100"), new BigDecimal("200"));
        when(ledgerRepository.findAllDistinctUserIds()).thenReturn(List.of(1L));
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.of(sub));
        when(ledgerRepository.sumAmountByUserIdSince(eq(1L), any())).thenReturn(new BigDecimal("100"));
        when(ledgerRepository.sumAmountByUserIdExcludingReleasedReserves(1L)).thenReturn(new BigDecimal("300"));

        service.reconcile();

        ArgumentCaptor<CreditReconciliationLog> logCaptor = ArgumentCaptor.forClass(CreditReconciliationLog.class);
        verify(reconciliationLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getDrift()).isEqualByComparingTo("200");
        assertThat(logCaptor.getValue().isExplained()).isTrue();
    }

    @Test
    @DisplayName("Carried PAYG plus a real lifetime discrepancy stays UNEXPLAINED - the lifetime check does not mask genuine drift")
    void carryOverPlusRealDriftStaysUnexplained() {
        // Same carry-over shape as above, but the lifetime sum disagrees with the
        // balance by 50 (a genuinely lost movement). The carry-over must NOT act
        // as a blanket excuse: lifetime books unbalanced → unexplained.
        Subscription sub = subWithBuckets(new BigDecimal("100"), new BigDecimal("200"));
        when(ledgerRepository.findAllDistinctUserIds()).thenReturn(List.of(1L));
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.of(sub));
        when(ledgerRepository.sumAmountByUserIdSince(eq(1L), any())).thenReturn(new BigDecimal("100"));
        when(ledgerRepository.sumAmountByUserIdExcludingReleasedReserves(1L)).thenReturn(new BigDecimal("250"));

        service.reconcile();

        ArgumentCaptor<CreditReconciliationLog> logCaptor = ArgumentCaptor.forClass(CreditReconciliationLog.class);
        verify(reconciliationLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().isExplained()).isFalse();
    }

    @Test
    @DisplayName("Zero period drift never queries the lifetime sum - the extra read is drift-gated")
    void noDriftSkipsLifetimeQuery() {
        Subscription sub = subWithBuckets(new BigDecimal("100"), new BigDecimal("200"));
        when(ledgerRepository.findAllDistinctUserIds()).thenReturn(List.of(1L));
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.of(sub));
        when(ledgerRepository.sumAmountByUserIdSince(eq(1L), any())).thenReturn(new BigDecimal("300"));

        service.reconcile();

        verify(ledgerRepository, never()).sumAmountByUserIdExcludingReleasedReserves(any());
        verify(reconciliationLogRepository, never()).save(any(CreditReconciliationLog.class));
    }
}
