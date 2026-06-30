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
        Subscription sub = subWithBuckets(new BigDecimal("30"), BigDecimal.ZERO);
        when(ledgerRepository.findAllDistinctUserIds()).thenReturn(List.of(1L));
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.of(sub));
        when(ledgerRepository.sumAmountByUserIdSince(eq(1L), any())).thenReturn(new BigDecimal("100"));

        service.reconcile();

        ArgumentCaptor<CreditReconciliationLog> logCaptor = ArgumentCaptor.forClass(CreditReconciliationLog.class);
        verify(reconciliationLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getDrift()).isEqualByComparingTo("-70");
    }

    @Test
    @DisplayName("Real drift on the PAYG bucket alone is detected (catches grantPaygTopup writing the wrong amount)")
    void realPaygDriftDetected() {
        // Sub = 0 + PAYG = 50, ledger sum says 200 - somehow the grant landed at half value.
        Subscription sub = subWithBuckets(BigDecimal.ZERO, new BigDecimal("50"));
        when(ledgerRepository.findAllDistinctUserIds()).thenReturn(List.of(1L));
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.of(sub));
        when(ledgerRepository.sumAmountByUserIdSince(eq(1L), any())).thenReturn(new BigDecimal("200"));

        service.reconcile();

        verify(reconciliationLogRepository).save(any(CreditReconciliationLog.class));
    }
}
