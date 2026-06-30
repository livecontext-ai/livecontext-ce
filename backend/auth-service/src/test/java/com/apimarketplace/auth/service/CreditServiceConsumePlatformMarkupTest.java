package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.service.CreditService.CreditConsumeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreditService - platform-markup debit/refund and reservation pre-check")
class CreditServiceConsumePlatformMarkupTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private CreditLedgerRepository ledgerRepository;
    @Mock
    private ModelPricingService pricingService;

    private CreditService creditService;

    private static final Long USER_ID = 42L;
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("100.0000");

    @BeforeEach
    void setUp() {
        // Default: markup enabled, shadow off - production path. Tests that
        // need to exercise the flag guards rebuild the service with different
        // flag values.
        creditService = newServiceWithFlags(true, false);
    }

    private CreditService newServiceWithFlags(boolean enabled, boolean shadow) {
        return new CreditService(
                subscriptionRepository, ledgerRepository, pricingService,
                false, enabled, shadow);
    }

    // ========== Helpers ==========

    private Subscription subscriptionWithBalance(BigDecimal balance) {
        Subscription sub = new Subscription();
        sub.setId(1L);
        sub.setRemainingCredits(balance);
        return sub;
    }

    private void mockActiveSubscription(BigDecimal balance) {
        Subscription sub = subscriptionWithBalance(balance);
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                .thenReturn(Optional.of(sub));
        // findActiveByUserId is used by getBalance() - only stub it when needed by the test.
        lenientBalanceRead(balance);
    }

    private void lenientBalanceRead(BigDecimal balance) {
        Subscription sub = subscriptionWithBalance(balance);
        // read path via getBalance()
        org.mockito.Mockito.lenient().when(subscriptionRepository.findActiveByUserId(USER_ID))
                .thenReturn(Optional.of(sub));
    }

    // ========== consumePlatformMarkup ==========

    @Nested
    @DisplayName("consumePlatformMarkup")
    class ConsumePlatformMarkup {

        @Test
        @DisplayName("returns success(0, balance) when the markup amount is null")
        void nullAmountIsNoOp() {
            // Arrange
            lenientBalanceRead(INITIAL_BALANCE);

            // Act
            CreditConsumeResult result = creditService.consumePlatformMarkup(
                    USER_ID, "src-1", "Slack", null, "run-1");

            // Assert
            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.remainingCredits()).isEqualByComparingTo(INITIAL_BALANCE);
            verify(ledgerRepository, never()).save(any(CreditLedgerEntry.class));
            verify(subscriptionRepository, never()).save(any(Subscription.class));
        }

        @Test
        @DisplayName("returns success(0, balance) when the markup amount is zero")
        void zeroAmountIsNoOp() {
            // Arrange
            lenientBalanceRead(INITIAL_BALANCE);

            // Act
            CreditConsumeResult result = creditService.consumePlatformMarkup(
                    USER_ID, "src-1", "Slack", BigDecimal.ZERO, "run-1");

            // Assert
            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
            verify(ledgerRepository, never()).save(any(CreditLedgerEntry.class));
            verify(subscriptionRepository, never()).save(any(Subscription.class));
        }

        @Test
        @DisplayName("feature flag enabled=false: no ledger row, no subscription save - markup billing is fully off")
        void flagDisabledSkipsLedger() {
            // Arrange
            CreditService disabled = newServiceWithFlags(false, false);
            lenientBalanceRead(INITIAL_BALANCE);

            // Act
            CreditConsumeResult result = disabled.consumePlatformMarkup(
                    USER_ID, "src-off", "Slack", new BigDecimal("0.25"), "run-1");

            // Assert
            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
            verify(ledgerRepository, never()).save(any(CreditLedgerEntry.class));
            verify(ledgerRepository, never()).existsBySourceId(any());
            verify(subscriptionRepository, never()).save(any(Subscription.class));
        }

        @Test
        @DisplayName("shadow mode: debit is logged but no ledger row is written - dark-launch projection")
        void shadowModeSkipsLedger() {
            // Arrange
            CreditService shadow = newServiceWithFlags(true, true);
            lenientBalanceRead(INITIAL_BALANCE);

            // Act
            CreditConsumeResult result = shadow.consumePlatformMarkup(
                    USER_ID, "src-shadow", "Gmail", new BigDecimal("0.10"), "run-1");

            // Assert
            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
            verify(ledgerRepository, never()).save(any(CreditLedgerEntry.class));
            // Shadow mode must not even touch the idempotency index - we are not
            // writing, so duplicate detection is moot.
            verify(ledgerRepository, never()).existsBySourceId(any());
            verify(subscriptionRepository, never()).save(any(Subscription.class));
        }

        @Test
        @DisplayName("is idempotent when the sourceId already exists in the ledger")
        void idempotentOnDuplicateSourceId() {
            // Arrange
            when(ledgerRepository.existsBySourceId("dup")).thenReturn(true);
            lenientBalanceRead(INITIAL_BALANCE);

            // Act
            CreditConsumeResult result = creditService.consumePlatformMarkup(
                    USER_ID, "dup", "Slack", new BigDecimal("0.50"), "run-1");

            // Assert
            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
            verify(ledgerRepository, never()).save(any(CreditLedgerEntry.class));
            verify(subscriptionRepository, never()).save(any(Subscription.class));
        }

        @Test
        @DisplayName("debits the amount and writes a ledger row tagged sourceType=PLATFORM_MARKUP")
        void debitsAndWritesLedgerRow() {
            // Arrange
            when(ledgerRepository.existsBySourceId("src-ok")).thenReturn(false);
            mockActiveSubscription(INITIAL_BALANCE);

            // Act
            CreditConsumeResult result = creditService.consumePlatformMarkup(
                    USER_ID, "src-ok", "Slack", new BigDecimal("0.50"), "run-1");

            // Assert
            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(new BigDecimal("0.50"));
            assertThat(result.remainingCredits())
                    .isEqualByComparingTo(new BigDecimal("99.5000"));

            ArgumentCaptor<CreditLedgerEntry> captor =
                    ArgumentCaptor.forClass(CreditLedgerEntry.class);
            verify(ledgerRepository).save(captor.capture());
            CreditLedgerEntry entry = captor.getValue();
            assertThat(entry.getSourceType()).isEqualTo("PLATFORM_MARKUP");
            assertThat(entry.getSourceId()).isEqualTo("src-ok");
            assertThat(entry.getAmount()).isEqualByComparingTo(new BigDecimal("-0.50"));
            assertThat(entry.getUserId()).isEqualTo(USER_ID);
        }
    }

}
