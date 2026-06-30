package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.service.CreditService.CreditConsumeResult;
import com.apimarketplace.common.credit.SourceIdBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreditService V250 two-bucket model - sub + payg routing")
class CreditServiceTwoBucketTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private CreditLedgerRepository ledgerRepository;
    @Mock
    private ModelPricingService pricingService;

    private CreditService creditService;

    private static final Long USER_ID = 42L;

    @BeforeEach
    void setUp() {
        creditService = new CreditService(subscriptionRepository, ledgerRepository, pricingService, false);
    }

    private Subscription sub(BigDecimal subBucket, BigDecimal paygBucket) {
        Subscription s = new Subscription();
        s.setId(1L);
        s.setRemainingCredits(subBucket);
        s.setPaygRemainingCredits(paygBucket);
        s.setDelinquent(false);
        return s;
    }

    // ==========================================================================
    // grantCredits routing - PAYG_TOPUP vs other sourceTypes
    // ==========================================================================

    @Test
    @DisplayName("grantCredits sourceType=PAYG_TOPUP routes +amount to payg bucket")
    void grantPaygTopupRoutesToPaygBucket() {
        Subscription s = sub(new BigDecimal("25.00"), new BigDecimal("0.00"));
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));
        when(subscriptionRepository.save(any())).thenReturn(s);

        creditService.grantCredits(USER_ID, new BigDecimal("50.00"), "PAYG_TOPUP", "cs_test_1", "Top-up");

        assertThat(s.getRemainingCredits()).isEqualByComparingTo("25.00");
        assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("50.00");
        assertThat(s.getTotalBalance()).isEqualByComparingTo("75.00");
    }

    @Test
    @DisplayName("grantCredits sourceType=GRANT (non-PAYG) routes +amount to sub bucket - backward compat")
    void grantNonPaygTopupRoutesToSubBucket() {
        Subscription s = sub(new BigDecimal("10.00"), new BigDecimal("5.00"));
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));
        when(subscriptionRepository.save(any())).thenReturn(s);

        creditService.grantCredits(USER_ID, new BigDecimal("20.00"), "GRANT", "admin-fix-1", "Manual adjustment");

        assertThat(s.getRemainingCredits()).isEqualByComparingTo("30.00");
        assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("grantCredits PURCHASE sourceType lands on sub bucket - Stripe TEAM sub renewal path preserved")
    void grantPurchaseRoutesToSubBucket() {
        Subscription s = sub(new BigDecimal("0.00"), new BigDecimal("0.00"));
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));
        when(subscriptionRepository.save(any())).thenReturn(s);

        creditService.grantCredits(USER_ID, new BigDecimal("25.00"), "PURCHASE", "in_test_1", "Monthly renewal");

        assertThat(s.getRemainingCredits()).isEqualByComparingTo("25.00");
        assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("grantCredits sourceType=REWARD_REFERRAL routes +amount to the payg bucket (survives renewal)")
    void grantReferralRewardRoutesToPaygBucket() {
        Subscription s = sub(new BigDecimal("25.00"), new BigDecimal("0.00"));
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));
        when(subscriptionRepository.save(any())).thenReturn(s);

        creditService.grantCredits(USER_ID, new BigDecimal("8000"), "REWARD_REFERRAL",
                "REWARD_REFERRAL_1_OWNER", "Referral reward");

        // Referral reward MUST land on payg (persists across renewal), never the sub bucket.
        assertThat(s.getRemainingCredits()).isEqualByComparingTo("25.00");
        assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("8000");
    }

    @Test
    @DisplayName("grantCredits sourceType=REWARD_CLAWBACK debits the SAME payg bucket - reward+clawback net zero on payg, sub untouched")
    void rewardClawbackDebitsPaygBucketNetZero() {
        Subscription s = sub(new BigDecimal("25.00"), new BigDecimal("0.00"));
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));
        when(subscriptionRepository.save(any())).thenReturn(s);

        creditService.grantCredits(USER_ID, new BigDecimal("8000"), "REWARD_REFERRAL",
                "REWARD_REFERRAL_1_OWNER", "Referral reward");
        creditService.grantCredits(USER_ID, new BigDecimal("-8000"), "REWARD_CLAWBACK",
                "REWARD_CLAWBACK_1_OWNER", "Referral clawback");

        // Both legs hit payg: net zero there, and the sub bucket is never touched
        // (a clawback on the wrong bucket would silently burn unrelated sub credits).
        assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("0");
        assertThat(s.getRemainingCredits()).isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("grantCredits writes ledger row with balance_after = totalBalance (sub + payg)")
    void grantWritesLedgerWithTotalBalance() {
        Subscription s = sub(new BigDecimal("10.00"), new BigDecimal("5.00"));
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));
        when(subscriptionRepository.save(any())).thenReturn(s);

        creditService.grantCredits(USER_ID, new BigDecimal("3.00"), "PAYG_TOPUP", "cs_test_2", "Top-up");

        ArgumentCaptor<CreditLedgerEntry> captor = ArgumentCaptor.forClass(CreditLedgerEntry.class);
        verify(ledgerRepository).save(captor.capture());
        // Total = 10 (sub) + 5 (payg) + 3 (new payg) = 18
        assertThat(captor.getValue().getBalanceAfter()).isEqualByComparingTo("18.00");
    }

    // ==========================================================================
    // tryReserveMarkup 2-bucket arithmetic
    // ==========================================================================

    @Test
    @DisplayName("tryReserveMarkup drains sub fully then payg - projected 7, sub=5, payg=3 → sub=0, payg=1")
    void reserveSplitsSubThenPayg() {
        Subscription s = sub(new BigDecimal("5.00"), new BigDecimal("3.00"));
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));
        when(subscriptionRepository.save(any())).thenReturn(s);
        when(ledgerRepository.existsBySourceId(anyString())).thenReturn(false);

        CreditConsumeResult result = creditService.tryReserveMarkup(
                USER_ID, "platform-markup:STREAM:abc", "anthropic", "claude-3-5-sonnet",
                new BigDecimal("7.00"), null, 15, "STREAM", "scope-1", false);

        assertThat(result.success()).isTrue();
        assertThat(s.getRemainingCredits()).isEqualByComparingTo("0.00");
        assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("1.00");
        assertThat(s.getTotalBalance()).isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("tryReserveMarkup uses payg entirely when sub bucket is empty - sub=0 payg=10 reserve 3")
    void reserveUsesPaygWhenSubEmpty() {
        Subscription s = sub(new BigDecimal("0.00"), new BigDecimal("10.00"));
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));
        when(subscriptionRepository.save(any())).thenReturn(s);
        when(ledgerRepository.existsBySourceId(anyString())).thenReturn(false);

        CreditConsumeResult result = creditService.tryReserveMarkup(
                USER_ID, "platform-markup:STREAM:abc", "anthropic", "claude-3-5-sonnet",
                new BigDecimal("3.00"), null, 15, "STREAM", "scope-1", false);

        assertThat(result.success()).isTrue();
        assertThat(s.getRemainingCredits()).isEqualByComparingTo("0.00");
        assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("7.00");
    }

    @Test
    @DisplayName("tryReserveMarkup refuses when total (sub + payg) < projected - insufficient")
    void reserveRefusesWhenTotalInsufficient() {
        Subscription s = sub(new BigDecimal("2.00"), new BigDecimal("1.00"));  // total = 3
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));
        when(ledgerRepository.existsBySourceId(anyString())).thenReturn(false);

        CreditConsumeResult result = creditService.tryReserveMarkup(
                USER_ID, "platform-markup:STREAM:abc", "anthropic", "claude-3-5-sonnet",
                new BigDecimal("10.00"), null, 15, "STREAM", "scope-1", false);

        assertThat(result.success()).isFalse();
        // Balances unchanged on insufficient
        assertThat(s.getRemainingCredits()).isEqualByComparingTo("2.00");
        assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("V254 regression: tryReserveMarkup stamps paygPortion on RESERVE row - sub=5, payg=10, reserve=7 → paygPortion=2 (the spillover into PAYG)")
    void reserveStampsPaygPortionForMixedSplit() {
        Subscription s = sub(new BigDecimal("5.00"), new BigDecimal("10.00"));
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));
        when(subscriptionRepository.save(any())).thenReturn(s);
        when(ledgerRepository.existsBySourceId(anyString())).thenReturn(false);

        creditService.tryReserveMarkup(
                USER_ID, "platform-markup:STREAM:abc", "openai", "gpt-4o",
                new BigDecimal("7.00"), null, 15, "STREAM", "scope-1", false);

        ArgumentCaptor<CreditLedgerEntry> captor = ArgumentCaptor.forClass(CreditLedgerEntry.class);
        verify(ledgerRepository).save(captor.capture());
        // Sub bucket fully drained ($5), the remaining $2 came out of PAYG.
        // Without paygPortion=2 stamped, the release refund would dump the
        // entire $7 onto sub, silently turning $2 of PAYG money into sub-cycle
        // credits that get wiped at the next renewal.
        assertThat(captor.getValue().getPaygPortion()).isEqualByComparingTo("2.00");
    }

    @Test
    @DisplayName("V254 regression: tryReserveMarkup stamps paygPortion=reserved when sub bucket empty (entire reserve out of PAYG)")
    void reserveStampsFullPaygPortionWhenSubEmpty() {
        Subscription s = sub(BigDecimal.ZERO, new BigDecimal("10.00"));
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));
        when(subscriptionRepository.save(any())).thenReturn(s);
        when(ledgerRepository.existsBySourceId(anyString())).thenReturn(false);

        creditService.tryReserveMarkup(
                USER_ID, "platform-markup:STREAM:abc", "openai", "gpt-4o",
                new BigDecimal("3.00"), null, 15, "STREAM", "scope-1", false);

        ArgumentCaptor<CreditLedgerEntry> captor = ArgumentCaptor.forClass(CreditLedgerEntry.class);
        verify(ledgerRepository).save(captor.capture());
        assertThat(captor.getValue().getPaygPortion()).isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("V254 regression: tryReserveMarkup stamps paygPortion=0 when no PAYG balance (legacy single-bucket path)")
    void reserveStampsZeroPaygPortionForSubOnly() {
        Subscription s = sub(new BigDecimal("10.00"), BigDecimal.ZERO);
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));
        when(subscriptionRepository.save(any())).thenReturn(s);
        when(ledgerRepository.existsBySourceId(anyString())).thenReturn(false);

        creditService.tryReserveMarkup(
                USER_ID, "platform-markup:STREAM:abc", "openai", "gpt-4o",
                new BigDecimal("3.00"), null, 15, "STREAM", "scope-1", false);

        ArgumentCaptor<CreditLedgerEntry> captor = ArgumentCaptor.forClass(CreditLedgerEntry.class);
        verify(ledgerRepository).save(captor.capture());
        assertThat(captor.getValue().getPaygPortion()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("V254 regression: releaseReservation refunds PAYG-funded reserve back to the PAYG bucket - without this, $2 of paid PAYG money would land on sub and get wiped at next renewal")
    void releaseRefundsPaygBucketSymmetrically() {
        // Sub=3 + PAYG=0 post-reserve (because reserve drained sub fully + $2 PAYG).
        // The ledger row carries paygPortion=2 from the V254 stamp.
        Subscription s = sub(new BigDecimal("3.00"), BigDecimal.ZERO);
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));
        when(subscriptionRepository.save(any())).thenReturn(s);

        CreditLedgerEntry reserveRow = new CreditLedgerEntry();
        reserveRow.setUserId(USER_ID);
        reserveRow.setSourceId("platform-markup:STREAM:expired");
        reserveRow.setSourceType("PLATFORM_MARKUP_RESERVE");
        reserveRow.setAmount(new BigDecimal("-7.00"));     // reserved 7
        reserveRow.setPaygPortion(new BigDecimal("2.00")); // 2 of the 7 was PAYG
        when(ledgerRepository.findFirstBySourceId("platform-markup:STREAM:expired"))
                .thenReturn(Optional.of(reserveRow));

        creditService.releaseReservation("platform-markup:STREAM:expired", "auto-release-timeout");

        // The 7 unit refund splits: $5 to sub (matches original sub drain) +
        // $2 back to PAYG (matches the stored paygPortion).
        assertThat(s.getRemainingCredits()).isEqualByComparingTo("8.00");
        assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("2.00");
        assertThat(s.getTotalBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("V254 backward-compat: releaseReservation on pre-V254 row (paygPortion=0) falls back to refund-to-sub - historical ledger rows preserved")
    void releaseFallsBackToSubWhenPaygPortionZero() {
        Subscription s = sub(new BigDecimal("3.00"), BigDecimal.ZERO);
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));
        when(subscriptionRepository.save(any())).thenReturn(s);

        CreditLedgerEntry legacyRow = new CreditLedgerEntry();
        legacyRow.setUserId(USER_ID);
        legacyRow.setSourceId("platform-markup:STREAM:legacy");
        legacyRow.setSourceType("PLATFORM_MARKUP_RESERVE");
        legacyRow.setAmount(new BigDecimal("-5.00"));
        // paygPortion defaults to 0 - pre-V254 row
        when(ledgerRepository.findFirstBySourceId("platform-markup:STREAM:legacy"))
                .thenReturn(Optional.of(legacyRow));

        creditService.releaseReservation("platform-markup:STREAM:legacy", "explicit");

        // Legacy semantics: entire refund credits sub bucket (total balance correct,
        // bucket fidelity lost - acceptable for historical rows).
        assertThat(s.getRemainingCredits()).isEqualByComparingTo("8.00");
        assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("0.00");
    }

    // ==========================================================================
    // deductCredits 2-bucket overshoot (allowNegative=true)
    // ==========================================================================

    @Test
    @DisplayName("deductCredits with allowNegative=true drains sub+payg, overshoot lands on sub (PAYG never negative from overshoot)")
    void deductOvershootDrainsBothBucketsAndSetsDelinquent() {
        Subscription s = sub(new BigDecimal("5.00"), new BigDecimal("3.00"));  // total = 8
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));
        when(subscriptionRepository.save(any())).thenReturn(s);
        // allowNegative=true path: chat post-flight overshoot
        when(pricingService.calculateCost("anthropic", "claude-3-5-sonnet", LlmTokenBreakdown.of(1000, 500)))
                .thenReturn(new BigDecimal("10.00"));

        CreditConsumeResult result = creditService.consumeForChat(
                USER_ID, "conv-1", "anthropic", "claude-3-5-sonnet", 1000, 500);

        assertThat(result.success()).isTrue();
        // Drain order: sub $5 → 0, then payg $3 → 0, overshoot $2 continues on sub → -2
        assertThat(s.getRemainingCredits()).isEqualByComparingTo("-2.00");
        assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("0.00");  // PAYG cash never negative from overshoot
        assertThat(s.getTotalBalance()).isEqualByComparingTo("-2.00");
        assertThat(s.getDelinquent()).isTrue();  // V148 invariant on totalBalance < 0
    }

    @Test
    @DisplayName("deductCredits refuses when total < cost and allowNegative=false - high-cost image gen")
    void deductRefusesInsufficientWhenAllowNegativeFalse() {
        Subscription s = sub(new BigDecimal("2.00"), new BigDecimal("1.00"));  // total = 3
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(s));
        when(pricingService.calculateUnitCost("openai", "dall-e-3", 100))
                .thenReturn(new BigDecimal("999.00"));
        when(pricingService.hasPricing("openai", "dall-e-3")).thenReturn(true);

        CreditConsumeResult result = creditService.consumeForImageGeneration(
                USER_ID, "img-1", "openai", "dall-e-3", 100);

        assertThat(result.success()).isFalse();
        // Balances unchanged on refuse (no debit applied)
        assertThat(s.getRemainingCredits()).isEqualByComparingTo("2.00");
        assertThat(s.getPaygRemainingCredits()).isEqualByComparingTo("1.00");
    }

    // ==========================================================================
    // getBalance + getBalanceForSelf - totalBalance semantics
    // ==========================================================================

    @Test
    @DisplayName("getBalance returns total = sub + payg - V250 canonical wallet read")
    void getBalanceReturnsTotal() {
        Subscription s = sub(new BigDecimal("12.50"), new BigDecimal("7.50"));
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(s));

        BigDecimal balance = creditService.getBalance(USER_ID);

        assertThat(balance).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("getBalance returns ZERO when no active subscription - no NPE")
    void getBalanceZeroWhenNoSub() {
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThat(creditService.getBalance(USER_ID)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("hasSufficientCredits true when totalBalance ≥ 1 - PAYG-only user with sub=0 can chat")
    void hasSufficientCreditsForPaygOnlyUser() {
        Subscription s = sub(new BigDecimal("0.00"), new BigDecimal("5.00"));
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(s));

        assertThat(creditService.hasSufficientCredits(USER_ID)).isTrue();
    }

    @Test
    @DisplayName("hasSufficientCredits false when totalBalance < 1 - both buckets empty")
    void hasSufficientCreditsFalseWhenTotalUnder1() {
        Subscription s = sub(new BigDecimal("0.50"), new BigDecimal("0.00"));
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(s));

        assertThat(creditService.hasSufficientCredits(USER_ID)).isFalse();
    }
}
