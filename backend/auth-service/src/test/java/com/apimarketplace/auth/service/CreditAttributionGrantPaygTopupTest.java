package com.apimarketplace.auth.service;

import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.service.CreditService.CreditConsumeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreditAttributionService.grantPaygTopup - PR3 Stripe PAYG webhook grant path")
class CreditAttributionGrantPaygTopupTest {

    @Mock
    private CreditService creditService;
    @Mock
    private CreditLedgerRepository ledgerRepository;
    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private com.apimarketplace.auth.repository.PendingCreditUpgradeRepository pendingCreditUpgradeRepository;

    private CreditAttributionService attributionService;

    private static final Long USER_ID = 42L;
    private static final String SESSION_ID = "cs_test_a1b2c3";
    private static final BigDecimal AMOUNT = new BigDecimal("5000");
    private static final String TIER = "small";

    @BeforeEach
    void setUp() {
        attributionService = new CreditAttributionService(
                creditService, ledgerRepository, subscriptionRepository, pendingCreditUpgradeRepository);
    }

    @Test
    @DisplayName("grantPaygTopup delegates to CreditService.grantCredits with sourceType=PAYG_TOPUP")
    void grantsViaCreditServiceWithPaygTopupSourceType() {
        when(ledgerRepository.existsBySourceId(SESSION_ID)).thenReturn(false);
        when(creditService.grantCredits(eq(USER_ID), any(), any(), any(), any()))
                .thenReturn(CreditConsumeResult.success(BigDecimal.ZERO, AMOUNT));

        attributionService.grantPaygTopup(USER_ID, AMOUNT, SESSION_ID, TIER);

        ArgumentCaptor<String> sourceTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sourceIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(creditService).grantCredits(eq(USER_ID), eq(AMOUNT),
                sourceTypeCaptor.capture(), sourceIdCaptor.capture(), any());
        assertThat(sourceTypeCaptor.getValue()).isEqualTo("PAYG_TOPUP");
        assertThat(sourceIdCaptor.getValue()).isEqualTo(SESSION_ID);
    }

    @Test
    @DisplayName("grantPaygTopup is idempotent - existsBySourceId pre-check skips duplicate grant")
    void idempotentWhenSourceIdAlreadyGranted() {
        when(ledgerRepository.existsBySourceId(SESSION_ID)).thenReturn(true);

        attributionService.grantPaygTopup(USER_ID, AMOUNT, SESSION_ID, TIER);

        verify(creditService, never()).grantCredits(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("grantPaygTopup throws IllegalStateException when CreditService grant fails (no subscription)")
    void throwsOnGrantFailure() {
        when(ledgerRepository.existsBySourceId(SESSION_ID)).thenReturn(false);
        when(creditService.grantCredits(any(), any(), any(), any(), any()))
                .thenReturn(CreditConsumeResult.noSubscription());

        assertThatThrownBy(() -> attributionService.grantPaygTopup(USER_ID, AMOUNT, SESSION_ID, TIER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAYG top-up grant failed");
    }

    @Test
    @DisplayName("grantPaygTopup description includes tier - audit trail traceability")
    void descriptionIncludesTier() {
        when(ledgerRepository.existsBySourceId(SESSION_ID)).thenReturn(false);
        when(creditService.grantCredits(any(), any(), any(), any(), any()))
                .thenReturn(CreditConsumeResult.success(BigDecimal.ZERO, AMOUNT));

        attributionService.grantPaygTopup(USER_ID, AMOUNT, SESSION_ID, "medium");

        ArgumentCaptor<String> descCaptor = ArgumentCaptor.forClass(String.class);
        verify(creditService).grantCredits(any(), any(), any(), any(), descCaptor.capture());
        assertThat(descCaptor.getValue()).contains("medium");
    }

    @Test
    @DisplayName("grantPaygTopup tolerates null tier in description - defensive against malformed webhook")
    void tolerantOfNullTier() {
        when(ledgerRepository.existsBySourceId(SESSION_ID)).thenReturn(false);
        when(creditService.grantCredits(any(), any(), any(), any(), any()))
                .thenReturn(CreditConsumeResult.success(BigDecimal.ZERO, AMOUNT));

        attributionService.grantPaygTopup(USER_ID, AMOUNT, SESSION_ID, null);

        ArgumentCaptor<String> descCaptor = ArgumentCaptor.forClass(String.class);
        verify(creditService).grantCredits(any(), any(), any(), any(), descCaptor.capture());
        assertThat(descCaptor.getValue()).contains("?");  // null tier rendered as "?"
    }
}
