package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.service.CreditService.CommitOutcome;
import com.apimarketplace.auth.service.CreditService.CreditConsumeResult;
import com.apimarketplace.auth.service.CreditService.ReleaseOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * V148+ reservation lifecycle tests - covers the new {@code tryReserveMarkup}
 * (wide signature), {@code commitReservation}, {@code releaseReservation},
 * and {@code clearDelinquentIfPositive} helper.
 *
 * <p>Plan v9 §correctness: regression bar requires tests for:
 * <ul>
 *   <li>Concurrent reserve idempotency (DIVE filter on SQLState 23505)</li>
 *   <li>commitReservation 5 outcomes (COMMITTED, ALREADY_COMMITTED, RESERVATION_EXPIRED, COMMITTED_PARTIAL, COMMITTED_FLOORED)</li>
 *   <li>releaseReservation 3 outcomes (RELEASED, ALREADY_RELEASED, ALREADY_COMMITTED)</li>
 *   <li>Delinquent flag clearing parametrized matrix</li>
 *   <li>Workflow vs chat delinquent gate (RUN+pin bypass, STREAM never bypasses)</li>
 *   <li>Unlimited mode never sets delinquent (BLOCKER from v8 audit)</li>
 *   <li>Sum-of-ledger == sum-of-balance-changes invariant on FLOORED</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("V148+ Reservation Lifecycle")
class CreditServiceReservationLifecycleTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private CreditLedgerRepository ledgerRepository;

    @Mock
    private ModelPricingService pricingService;

    private CreditService creditService;

    private static final Long USER_ID = 42L;
    private static final String SOURCE_ID = "platform-markup:STREAM:stream-1:openai/openai-create-image:0";
    private static final BigDecimal PROJECTED = new BigDecimal("39.0000");

    @BeforeEach
    void setUp() {
        // 4-arg ctor: unlimited=false; legacy ctor sets markupEnabled=true.
        creditService = new CreditService(subscriptionRepository, ledgerRepository, pricingService, false);
    }

    private Subscription sub(BigDecimal balance, boolean delinquent) {
        Subscription s = new Subscription();
        s.setId(1L);
        s.setRemainingCredits(balance);
        s.setDelinquent(delinquent);
        return s;
    }

    private void mockSub(BigDecimal balance, boolean delinquent) {
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                .thenReturn(Optional.of(sub(balance, delinquent)));
    }

    // =========================================================================
    // tryReserveMarkup - happy path + delinquent gate
    // =========================================================================

    @Nested
    @DisplayName("tryReserveMarkup")
    class TryReserve {

        @Test
        @DisplayName("happy path writes RESERVE row with negative amount, debits balance")
        void happyPath() {
            mockSub(new BigDecimal("100"), false);
            when(ledgerRepository.existsBySourceId(SOURCE_ID)).thenReturn(false);

            CreditConsumeResult r = creditService.tryReserveMarkup(
                    USER_ID, SOURCE_ID, "openai", "gpt-image-1.5-medium",
                    PROJECTED, /* pinId */ 7L, /* ttlMinutes */ 10,
                    "STREAM", "stream-1", /* hasExistingPin */ false);

            assertThat(r.success()).isTrue();
            assertThat(r.delinquent()).isFalse();
            ArgumentCaptor<CreditLedgerEntry> cap = ArgumentCaptor.forClass(CreditLedgerEntry.class);
            verify(ledgerRepository).save(cap.capture());
            CreditLedgerEntry row = cap.getValue();
            assertThat(row.getSourceType()).isEqualTo("PLATFORM_MARKUP_RESERVE");
            assertThat(row.getAmount()).isEqualByComparingTo("-39.0000");
            assertThat(row.getPinId()).isEqualTo(7L);
            assertThat(row.getExpiresAt()).isNotNull();
        }

        @Test
        @DisplayName("idempotent on existing sourceId - no second debit")
        void idempotent() {
            when(ledgerRepository.existsBySourceId(SOURCE_ID)).thenReturn(true);
            // findSubscriptionForUpdate must NOT be invoked when already exists
            when(subscriptionRepository.findActiveByUserId(USER_ID))
                    .thenReturn(Optional.of(sub(new BigDecimal("50"), false)));

            CreditConsumeResult r = creditService.tryReserveMarkup(
                    USER_ID, SOURCE_ID, "openai", "x", PROJECTED, 0L, 10,
                    "STREAM", "s1", false);

            assertThat(r.success()).isTrue();
            verify(ledgerRepository, never()).save(any());
        }

        @Test
        @DisplayName("STREAM scope refuses when delinquent (no in-flight bypass for chat)")
        void streamRefusedWhenDelinquent() {
            mockSub(new BigDecimal("100"), true);
            when(ledgerRepository.existsBySourceId(SOURCE_ID)).thenReturn(false);

            CreditConsumeResult r = creditService.tryReserveMarkup(
                    USER_ID, SOURCE_ID, "p", "m", PROJECTED, 0L, 10,
                    "STREAM", "s1", /* hasExistingPin even if true */ true);

            assertThat(r.success()).isFalse();
            assertThat(r.delinquent()).isTrue();
            assertThat(r.error()).contains("delinquent");
            verify(ledgerRepository, never()).save(any());
        }

        @Test
        @DisplayName("RUN scope with existing pin bypasses delinquent (in-flight workflow atomicity)")
        void runWithPinBypassesDelinquent() {
            mockSub(new BigDecimal("100"), true);
            when(ledgerRepository.existsBySourceId(any())).thenReturn(false);

            CreditConsumeResult r = creditService.tryReserveMarkup(
                    USER_ID, "platform-markup:RUN:run-1:step:s:0:0:0:0:0",
                    "p", "m", PROJECTED, 0L, 10,
                    "RUN", "run-1", /* hasExistingPin */ true);

            assertThat(r.success()).isTrue();
            verify(ledgerRepository).save(any());
        }

        @Test
        @DisplayName("RUN scope WITHOUT pin refuses when delinquent (workflow can't START while delinquent)")
        void runWithoutPinRefusedWhenDelinquent() {
            mockSub(new BigDecimal("100"), true);
            when(ledgerRepository.existsBySourceId(any())).thenReturn(false);

            CreditConsumeResult r = creditService.tryReserveMarkup(
                    USER_ID, "platform-markup:INIT:RUN:run-1:7",
                    "p", "m", PROJECTED, 0L, 1440,
                    "RUN", "run-1", /* hasExistingPin */ false);

            assertThat(r.success()).isFalse();
            assertThat(r.delinquent()).isTrue();
        }

        @Test
        @DisplayName("LEGACY scope bypasses delinquent (90-day deprecation backwards compat)")
        void legacyBypassesDelinquent() {
            mockSub(new BigDecimal("100"), true);
            when(ledgerRepository.existsBySourceId(any())).thenReturn(false);

            CreditConsumeResult r = creditService.tryReserveMarkup(
                    USER_ID, "legacy-source", "p", "m", PROJECTED, 0L, 60,
                    "LEGACY", null, false);

            assertThat(r.success()).isTrue();
        }

        @Test
        @DisplayName("insufficient balance - refused without writing reserve row")
        void insufficientBalance() {
            mockSub(new BigDecimal("10"), false);
            when(ledgerRepository.existsBySourceId(SOURCE_ID)).thenReturn(false);

            CreditConsumeResult r = creditService.tryReserveMarkup(
                    USER_ID, SOURCE_ID, "p", "m", PROJECTED, 0L, 10,
                    "STREAM", "s1", false);

            assertThat(r.success()).isFalse();
            verify(ledgerRepository, never()).save(any());
        }
    }

    // =========================================================================
    // commitReservation - 5 outcomes
    // =========================================================================

    @Nested
    @DisplayName("commitReservation")
    class CommitReservation {

        private CreditLedgerEntry reserveRow(BigDecimal amount) {
            CreditLedgerEntry row = new CreditLedgerEntry();
            row.setUserId(USER_ID);
            row.setSourceId(SOURCE_ID);
            row.setSourceType("PLATFORM_MARKUP_RESERVE");
            row.setAmount(amount);
            return row;
        }

        @Test
        @DisplayName("happy path - actual <= reserved → COMMITTED, balance refunds delta")
        void committedHappy() {
            CreditLedgerEntry row = reserveRow(new BigDecimal("-39"));
            when(ledgerRepository.findFirstBySourceId(SOURCE_ID)).thenReturn(Optional.of(row));
            mockSub(new BigDecimal("61"), false); // 100 - 39 reserved

            CommitOutcome out = creditService.commitReservation(
                    SOURCE_ID, new BigDecimal("30"), "openai", "gpt-image-1.5-medium");

            assertThat(out).isEqualTo(CommitOutcome.COMMITTED);
            assertThat(row.getSourceType()).isEqualTo("PLATFORM_MARKUP");
            assertThat(row.getAmount()).isEqualByComparingTo("-30");
            assertThat(row.getExpiresAt()).isNull();
        }

        @Test
        @DisplayName("ALREADY_COMMITTED on PLATFORM_MARKUP row - idempotent retry")
        void alreadyCommitted() {
            CreditLedgerEntry row = reserveRow(new BigDecimal("-39"));
            row.setSourceType("PLATFORM_MARKUP");
            when(ledgerRepository.findFirstBySourceId(SOURCE_ID)).thenReturn(Optional.of(row));

            CommitOutcome out = creditService.commitReservation(SOURCE_ID, new BigDecimal("39"), "p", "m");

            assertThat(out).isEqualTo(CommitOutcome.ALREADY_COMMITTED);
            verify(ledgerRepository, never()).save(any());
        }

        @Test
        @DisplayName("RESERVATION_EXPIRED when row is already RELEASED")
        void reservationExpired() {
            CreditLedgerEntry row = reserveRow(new BigDecimal("-39"));
            row.setSourceType("PLATFORM_MARKUP_RELEASED_TIMEOUT");
            when(ledgerRepository.findFirstBySourceId(SOURCE_ID)).thenReturn(Optional.of(row));

            CommitOutcome out = creditService.commitReservation(SOURCE_ID, new BigDecimal("39"), "p", "m");

            assertThat(out).isEqualTo(CommitOutcome.RESERVATION_EXPIRED);
        }

        @Test
        @DisplayName("RESERVATION_EXPIRED when row not found")
        void rowNotFound() {
            when(ledgerRepository.findFirstBySourceId(SOURCE_ID)).thenReturn(Optional.empty());

            CommitOutcome out = creditService.commitReservation(SOURCE_ID, new BigDecimal("39"), "p", "m");

            assertThat(out).isEqualTo(CommitOutcome.RESERVATION_EXPIRED);
        }

        @Test
        @DisplayName("COMMITTED_PARTIAL - actual > maxChargeable, charge what user can pay, set delinquent")
        void committedPartial() {
            // Reserved 39, balance_at_commit (post-reserve) = -10, max_chargeable = 29
            // actual = 50 → maxChargeable < actual but maxChargeable >= 0 → partial path
            // Wait - balance_at_commit is post-reserve. If pre-reserve was 29 and reserved 39,
            // balance_at_commit would be -10 (debit went negative). The reserve insufficient-balance
            // path would have refused this normally. But the partial path handles concurrent
            // over-debit: another commit ate balance between our reserve and our commit.
            // Setup: balance_at_commit=10, reserved=39, max_chargeable=49, actual=80 → partial 49.
            CreditLedgerEntry row = reserveRow(new BigDecimal("-39"));
            when(ledgerRepository.findFirstBySourceId(SOURCE_ID)).thenReturn(Optional.of(row));
            Subscription s = sub(new BigDecimal("10"), false);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));

            CommitOutcome out = creditService.commitReservation(
                    SOURCE_ID, new BigDecimal("80"), "p", "m");

            assertThat(out).isEqualTo(CommitOutcome.COMMITTED_PARTIAL);
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("0");
            assertThat(s.getDelinquent()).isTrue();
            assertThat(row.getSourceType()).isEqualTo("PLATFORM_MARKUP");
            assertThat(row.getAmount()).isEqualByComparingTo("-49"); // max_chargeable
        }

        @Test
        @DisplayName("COMMITTED_FLOORED - balance already negative from concurrent over-debit")
        void committedFloored() {
            // balance_at_commit = -5 (concurrent partial-charge), reserved = 39,
            // max_chargeable = 34, but if actual = 50 we'd go to PARTIAL.
            // To trigger FLOORED: need max_chargeable < 0, so balance_at_commit < -reserved.
            CreditLedgerEntry row = reserveRow(new BigDecimal("-39"));
            when(ledgerRepository.findFirstBySourceId(SOURCE_ID)).thenReturn(Optional.of(row));
            Subscription s = sub(new BigDecimal("-50"), false); // way below -39
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));

            CommitOutcome out = creditService.commitReservation(
                    SOURCE_ID, new BigDecimal("80"), "p", "m");

            assertThat(out).isEqualTo(CommitOutcome.COMMITTED_FLOORED);
            // balance unchanged from balance_at_commit (no further debit)
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("-50");
            assertThat(s.getDelinquent()).isTrue();
            // Row keeps amount = -reserved (39) - preserves audit trace
            assertThat(row.getAmount()).isEqualByComparingTo("-39");
        }
    }

    // =========================================================================
    // releaseReservation - 3 outcomes
    // =========================================================================

    @Nested
    @DisplayName("releaseReservation")
    class ReleaseReservation {

        @Test
        @DisplayName("RELEASED on PLATFORM_MARKUP_RESERVE - refunds balance, flips to RELEASED")
        void released() {
            CreditLedgerEntry row = new CreditLedgerEntry();
            row.setUserId(USER_ID);
            row.setSourceId(SOURCE_ID);
            row.setSourceType("PLATFORM_MARKUP_RESERVE");
            row.setAmount(new BigDecimal("-39"));
            row.setDescription("Markup reservation: openai/gpt-image");
            when(ledgerRepository.findFirstBySourceId(SOURCE_ID)).thenReturn(Optional.of(row));
            Subscription s = sub(new BigDecimal("61"), false);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));

            ReleaseOutcome out = creditService.releaseReservation(SOURCE_ID, "explicit user cancel");

            assertThat(out).isEqualTo(ReleaseOutcome.RELEASED);
            assertThat(s.getRemainingCredits()).isEqualByComparingTo("100"); // refunded 39
            assertThat(row.getSourceType()).isEqualTo("PLATFORM_MARKUP_RELEASED");
            // amount kept at -reserved (preserves projection trace)
            assertThat(row.getAmount()).isEqualByComparingTo("-39");
            // description appended (not overwritten)
            assertThat(row.getDescription()).contains("Markup reservation").contains("explicit user cancel");
        }

        @Test
        @DisplayName("release reads the reservation row with a lifecycle lock")
        void releaseReadsReservationWithLifecycleLock() {
            CreditLedgerEntry row = new CreditLedgerEntry();
            row.setUserId(USER_ID);
            row.setSourceId(SOURCE_ID);
            row.setSourceType("PLATFORM_MARKUP_RESERVE");
            row.setAmount(new BigDecimal("-39"));
            when(ledgerRepository.findFirstBySourceIdForUpdate(SOURCE_ID)).thenReturn(Optional.of(row));
            Subscription s = sub(new BigDecimal("61"), false);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));

            ReleaseOutcome out = creditService.releaseReservation(SOURCE_ID, "explicit user cancel");

            assertThat(out).isEqualTo(ReleaseOutcome.RELEASED);
            verify(ledgerRepository).findFirstBySourceIdForUpdate(SOURCE_ID);
            verify(ledgerRepository, never()).findFirstBySourceId(SOURCE_ID);
        }

        @Test
        @DisplayName("auto-release-timeout reason produces _RELEASED_TIMEOUT source_type")
        void releasedTimeout() {
            CreditLedgerEntry row = new CreditLedgerEntry();
            row.setUserId(USER_ID);
            row.setSourceId(SOURCE_ID);
            row.setSourceType("PLATFORM_MARKUP_RESERVE");
            row.setAmount(new BigDecimal("-10"));
            when(ledgerRepository.findFirstBySourceId(SOURCE_ID)).thenReturn(Optional.of(row));
            Subscription s = sub(new BigDecimal("90"), false);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));

            ReleaseOutcome out = creditService.releaseReservation(
                    SOURCE_ID, "auto-release-timeout: reserve TTL elapsed");

            assertThat(out).isEqualTo(ReleaseOutcome.RELEASED);
            assertThat(row.getSourceType()).isEqualTo("PLATFORM_MARKUP_RELEASED_TIMEOUT");
        }

        @Test
        @DisplayName("ALREADY_RELEASED on _RELEASED_TIMEOUT - idempotent retry")
        void alreadyReleased() {
            CreditLedgerEntry row = new CreditLedgerEntry();
            row.setSourceId(SOURCE_ID);
            row.setSourceType("PLATFORM_MARKUP_RELEASED_TIMEOUT");
            row.setAmount(new BigDecimal("-10"));
            when(ledgerRepository.findFirstBySourceId(SOURCE_ID)).thenReturn(Optional.of(row));

            ReleaseOutcome out = creditService.releaseReservation(SOURCE_ID, "any");

            assertThat(out).isEqualTo(ReleaseOutcome.ALREADY_RELEASED);
            verify(subscriptionRepository, never()).findActiveByUserIdForUpdate(any());
        }

        @Test
        @DisplayName("ALREADY_COMMITTED on PLATFORM_MARKUP - caller does NOT refund")
        void alreadyCommitted() {
            CreditLedgerEntry row = new CreditLedgerEntry();
            row.setSourceId(SOURCE_ID);
            row.setSourceType("PLATFORM_MARKUP");
            row.setAmount(new BigDecimal("-39"));
            when(ledgerRepository.findFirstBySourceId(SOURCE_ID)).thenReturn(Optional.of(row));

            ReleaseOutcome out = creditService.releaseReservation(SOURCE_ID, "race lost to commit");

            assertThat(out).isEqualTo(ReleaseOutcome.ALREADY_COMMITTED);
            verify(subscriptionRepository, never()).findActiveByUserIdForUpdate(any());
        }
    }

    // =========================================================================
    // clearDelinquentIfPositive parametrized matrix
    // =========================================================================

    static Stream<Arguments> delinquentClearingCases() {
        return Stream.of(
                // initBalance, refund, expectedBalance, expectedDelinquent
                Arguments.of("-5", "3", "-2", true),  // partial refill - still delinquent (rule strict > 0)
                Arguments.of("-5", "5", "0", true),   // exact zero - still delinquent
                Arguments.of("-5", "6", "1", false),  // positive - cleared
                Arguments.of("0", "1", "1", false),   // already at zero, refund clears
                Arguments.of("3", "2", "5", false)    // already positive (idempotent flip-down)
        );
    }

    @ParameterizedTest
    @MethodSource("delinquentClearingCases")
    @DisplayName("clearDelinquentIfPositive matrix via grantCredits")
    void clearDelinquentMatrix(String initBalance, String refund, String expBalance, boolean expDelinquent) {
        Subscription s = sub(new BigDecimal(initBalance), true); // start delinquent
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));

        creditService.grantCredits(USER_ID, new BigDecimal(refund),
                "TEST_GRANT", "test-source", "matrix");

        assertThat(s.getRemainingCredits()).isEqualByComparingTo(expBalance);
        assertThat(s.getDelinquent()).isEqualTo(expDelinquent);
    }

    // =========================================================================
    // Unlimited mode - delinquent flag NEVER fires (BLOCKER fix from v8 audit)
    // =========================================================================

    @Test
    @DisplayName("Unlimited mode + COMMITTED_PARTIAL - delinquent flag NOT set (audit BLOCKER fix)")
    void unlimitedDoesNotSetDelinquent() {
        // Use 6-arg ctor with unlimited=true, markupEnabled=true
        creditService = new CreditService(subscriptionRepository, ledgerRepository, pricingService,
                /* unlimited */ true, /* markupEnabled */ true, /* markupShadow */ false);

        CreditLedgerEntry row = new CreditLedgerEntry();
        row.setUserId(USER_ID);
        row.setSourceId(SOURCE_ID);
        row.setSourceType("PLATFORM_MARKUP_RESERVE");
        row.setAmount(new BigDecimal("-39"));
        when(ledgerRepository.findFirstBySourceId(SOURCE_ID)).thenReturn(Optional.of(row));
        Subscription s = sub(new BigDecimal("10"), false);
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));

        // Force partial-charge math: actual > max_chargeable
        CommitOutcome out = creditService.commitReservation(
                SOURCE_ID, new BigDecimal("80"), "p", "m");

        assertThat(out).isEqualTo(CommitOutcome.COMMITTED_PARTIAL);
        // CRITICAL: in unlimited mode, the delinquent flag must NOT be set -
        // there's no real ledger pressure and clearDelinquentIfPositive is gated
        // by !unlimited, so once stuck=true it would never clear.
        assertThat(s.getDelinquent()).isFalse();
    }

    // =========================================================================
    // CE compat - markupEnabled=false short-circuits all 3 methods
    // =========================================================================

    @Test
    @DisplayName("CE compat: markupEnabled=false reserve returns success without ledger write")
    void ceReserveNoOp() {
        creditService = new CreditService(subscriptionRepository, ledgerRepository, pricingService,
                /* unlimited */ true, /* markupEnabled */ false, false);

        CreditConsumeResult r = creditService.tryReserveMarkup(
                USER_ID, SOURCE_ID, "p", "m", PROJECTED, 0L, 10,
                "STREAM", "s1", false);

        assertThat(r.success()).isTrue();
        verify(ledgerRepository, never()).save(any());
        verify(subscriptionRepository, never()).findActiveByUserIdForUpdate(any());
    }

    @Test
    @DisplayName("CE compat: markupEnabled=false commit returns COMMITTED without DB read")
    void ceCommitNoOp() {
        creditService = new CreditService(subscriptionRepository, ledgerRepository, pricingService,
                true, false, false);

        CommitOutcome out = creditService.commitReservation(SOURCE_ID, PROJECTED, "p", "m");

        assertThat(out).isEqualTo(CommitOutcome.COMMITTED);
        verify(ledgerRepository, never()).findFirstBySourceId(any());
    }

    @Test
    @DisplayName("CE compat: markupEnabled=false release returns RELEASED without DB read")
    void ceReleaseNoOp() {
        creditService = new CreditService(subscriptionRepository, ledgerRepository, pricingService,
                true, false, false);

        ReleaseOutcome out = creditService.releaseReservation(SOURCE_ID, "any");

        assertThat(out).isEqualTo(ReleaseOutcome.RELEASED);
        verify(ledgerRepository, never()).findFirstBySourceId(any());
    }
}
