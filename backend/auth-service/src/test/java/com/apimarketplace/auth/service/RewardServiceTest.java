package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.*;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.RewardCodeRepository;
import com.apimarketplace.auth.repository.RewardRedemptionRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RewardService}: every redeem branch and the lazy mint.
 * Conversion-gated grants happen elsewhere (the webhook + releaser), so here a
 * referral redeem only proves the PENDING attribution is recorded.
 */
class RewardServiceTest {

    private RewardCodeRepository codeRepository;
    private RewardRedemptionRepository redemptionRepository;
    private SubscriptionRepository subscriptionRepository;
    private CreditService creditService;
    private CreditLedgerRepository ledgerRepository;
    private RewardService service;

    @BeforeEach
    void setUp() {
        codeRepository = mock(RewardCodeRepository.class);
        redemptionRepository = mock(RewardRedemptionRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        creditService = mock(CreditService.class);
        ledgerRepository = mock(CreditLedgerRepository.class);
        service = new RewardService(codeRepository, redemptionRepository, subscriptionRepository,
                creditService, ledgerRepository, 8000, 14, null);
    }

    private RewardCode referralCode(Long owner) {
        RewardCode rc = new RewardCode();
        rc.setId(100L);
        rc.setCode("ABCD2345");
        rc.setProgram(RewardProgram.REFERRAL);
        rc.setOwnerUserId(owner);
        rc.setBenefitKind(BenefitKind.CREDIT_GRANT);
        rc.setBenefitAmount(8000);
        rc.setBenefitTrigger(BenefitTrigger.PAID_CONVERSION);
        rc.setOwnerRewardKind(OwnerRewardKind.CREDIT_GRANT);
        rc.setOwnerRewardAmount(8000);
        rc.setHoldDays(14);
        rc.setClawbackEnabled(true);
        rc.setCapScope(CapScope.NONE);
        rc.setActive(true);
        rc.setValidFrom(Instant.now().minus(1, ChronoUnit.DAYS));
        return rc;
    }

    private RewardCode promoCode() {
        RewardCode rc = new RewardCode();
        rc.setId(200L);
        rc.setCode("PROMO123");
        rc.setProgram(RewardProgram.PROMO);
        rc.setBenefitKind(BenefitKind.FREE_NODE_COUNTER);
        rc.setBenefitAmount(20000);
        rc.setBenefitDurationDays(30);
        rc.setBenefitTrigger(BenefitTrigger.REDEEM_TIME);
        rc.setCapScope(CapScope.GLOBAL);
        rc.setCapLimit(null); // uncapped global
        rc.setActive(true);
        rc.setValidFrom(Instant.now().minus(1, ChronoUnit.DAYS));
        return rc;
    }

    @Test
    @DisplayName("redeem: null user is rejected as unknown code")
    void redeemNullUser() {
        assertThat(service.redeem(null, "X").status()).isEqualTo(RewardService.RedeemStatus.UNKNOWN_CODE);
    }

    @Test
    @DisplayName("redeem: blank or unknown code returns UNKNOWN_CODE")
    void redeemUnknownCode() {
        when(codeRepository.findByCodeIgnoreCase("NOPE")).thenReturn(Optional.empty());
        assertThat(service.redeem(1L, "  ").status()).isEqualTo(RewardService.RedeemStatus.UNKNOWN_CODE);
        assertThat(service.redeem(1L, "NOPE").status()).isEqualTo(RewardService.RedeemStatus.UNKNOWN_CODE);
    }

    @Test
    @DisplayName("redeem: inactive code is NOT_REDEEMABLE")
    void redeemInactive() {
        RewardCode rc = referralCode(2L);
        rc.setActive(false);
        when(codeRepository.findByCodeIgnoreCase("ABCD2345")).thenReturn(Optional.of(rc));
        assertThat(service.redeem(1L, "ABCD2345").status()).isEqualTo(RewardService.RedeemStatus.NOT_REDEEMABLE);
        verify(redemptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("redeem: a global cap that is reached returns EXHAUSTED")
    void redeemExhausted() {
        RewardCode rc = promoCode();
        rc.setCapLimit(5);
        rc.setCurrentRedemptions(5);
        when(codeRepository.findByCodeIgnoreCase("PROMO123")).thenReturn(Optional.of(rc));
        assertThat(service.redeem(1L, "PROMO123").status()).isEqualTo(RewardService.RedeemStatus.EXHAUSTED);
    }

    @Test
    @DisplayName("redeem: a user cannot redeem their own referral code")
    void redeemSelfReferral() {
        when(codeRepository.findByCodeIgnoreCase("ABCD2345")).thenReturn(Optional.of(referralCode(1L)));
        assertThat(service.redeem(1L, "ABCD2345").status()).isEqualTo(RewardService.RedeemStatus.SELF_REFERRAL);
        verify(redemptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("redeem: redeeming the same code twice returns ALREADY_REDEEMED")
    void redeemAlreadyRedeemedThisCode() {
        RewardCode rc = referralCode(2L);
        when(codeRepository.findByCodeIgnoreCase("ABCD2345")).thenReturn(Optional.of(rc));
        when(redemptionRepository.findByRedeemerUserIdAndRewardCodeId(1L, 100L))
                .thenReturn(Optional.of(new RewardRedemption()));
        assertThat(service.redeem(1L, "ABCD2345").status()).isEqualTo(RewardService.RedeemStatus.ALREADY_REDEEMED);
    }

    @Test
    @DisplayName("redeem: a second referral by the same referee returns ALREADY_REDEEMED")
    void redeemAlreadyReferred() {
        RewardCode rc = referralCode(2L);
        when(codeRepository.findByCodeIgnoreCase("ABCD2345")).thenReturn(Optional.of(rc));
        when(redemptionRepository.findByRedeemerUserIdAndRewardCodeId(1L, 100L)).thenReturn(Optional.empty());
        when(redemptionRepository.findByRedeemerUserIdAndProgram(1L, RewardProgram.REFERRAL))
                .thenReturn(Optional.of(new RewardRedemption()));
        assertThat(service.redeem(1L, "ABCD2345").status()).isEqualTo(RewardService.RedeemStatus.ALREADY_REDEEMED);
    }

    @Test
    @DisplayName("redeem: an existing paid subscriber cannot start a referral (ALREADY_PAID)")
    void redeemAlreadyPaid() {
        RewardCode rc = referralCode(2L);
        when(codeRepository.findByCodeIgnoreCase("ABCD2345")).thenReturn(Optional.of(rc));
        when(redemptionRepository.findByRedeemerUserIdAndRewardCodeId(1L, 100L)).thenReturn(Optional.empty());
        when(redemptionRepository.findByRedeemerUserIdAndProgram(1L, RewardProgram.REFERRAL)).thenReturn(Optional.empty());

        Plan plan = new Plan();
        plan.setCode("PRO");
        Subscription sub = mock(Subscription.class);
        when(sub.getProvider()).thenReturn("stripe");
        when(sub.getPlan()).thenReturn(plan);
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.of(sub));

        assertThat(service.redeem(1L, "ABCD2345").status()).isEqualTo(RewardService.RedeemStatus.ALREADY_PAID);
        verify(codeRepository, never()).tryReserveRedemption(anyLong());
    }

    @Test
    @DisplayName("redeem: a fresh referral is attributed as PENDING (granted later on conversion)")
    void redeemReferralPending() {
        RewardCode rc = referralCode(2L);
        when(codeRepository.findByCodeIgnoreCase("ABCD2345")).thenReturn(Optional.of(rc));
        when(redemptionRepository.findByRedeemerUserIdAndRewardCodeId(1L, 100L)).thenReturn(Optional.empty());
        when(redemptionRepository.findByRedeemerUserIdAndProgram(1L, RewardProgram.REFERRAL)).thenReturn(Optional.empty());
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.empty());
        when(codeRepository.tryReserveRedemption(100L)).thenReturn(1);
        when(codeRepository.findById(100L)).thenReturn(Optional.of(rc));

        RewardService.RedeemResult result = service.redeem(1L, "ABCD2345");

        assertThat(result.status()).isEqualTo(RewardService.RedeemStatus.PENDING_CONVERSION);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.redemption().getStatus()).isEqualTo(RewardStatus.PENDING);
        assertThat(result.redemption().getOwnerUserId()).isEqualTo(2L);
        verify(redemptionRepository).save(any(RewardRedemption.class));
    }

    @Test
    @DisplayName("redeem: a promo free-node code grants an immediate GRANTED counter benefit")
    void redeemPromoImmediate() {
        RewardCode rc = promoCode();
        when(codeRepository.findByCodeIgnoreCase("PROMO123")).thenReturn(Optional.of(rc));
        when(redemptionRepository.findByRedeemerUserIdAndRewardCodeId(1L, 200L)).thenReturn(Optional.empty());
        when(codeRepository.tryReserveRedemption(200L)).thenReturn(1);

        RewardService.RedeemResult result = service.redeem(1L, "PROMO123");

        assertThat(result.status()).isEqualTo(RewardService.RedeemStatus.SUCCESS);
        RewardRedemption r = result.redemption();
        assertThat(r.getStatus()).isEqualTo(RewardStatus.GRANTED);
        assertThat(r.getBenefitType()).isEqualTo(RewardCode.BENEFIT_WORKFLOW_NODE_FREE);
        assertThat(r.getFreeCreditsCap()).isEqualTo(20000);
        assertThat(r.getBenefitUntil()).isNotNull();
        verify(redemptionRepository).save(any(RewardRedemption.class));
    }

    @Test
    @DisplayName("redeem: a lost reservation re-classifies as NOT_REDEEMABLE")
    void redeemReservationLost() {
        RewardCode rc = referralCode(2L);
        when(codeRepository.findByCodeIgnoreCase("ABCD2345")).thenReturn(Optional.of(rc));
        when(redemptionRepository.findByRedeemerUserIdAndRewardCodeId(1L, 100L)).thenReturn(Optional.empty());
        when(redemptionRepository.findByRedeemerUserIdAndProgram(1L, RewardProgram.REFERRAL)).thenReturn(Optional.empty());
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.empty());
        when(codeRepository.tryReserveRedemption(100L)).thenReturn(0);
        when(codeRepository.findById(100L)).thenReturn(Optional.of(rc));

        assertThat(service.redeem(1L, "ABCD2345").status()).isEqualTo(RewardService.RedeemStatus.NOT_REDEEMABLE);
        verify(redemptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("redeem: a soft-cap overflow is recorded as TRACK_ONLY, not blocked")
    void redeemSoftCapTrackOnly() {
        RewardService svc = new RewardService(codeRepository, redemptionRepository, subscriptionRepository,
                creditService, ledgerRepository, 8000, 14, 2);
        RewardCode rc = referralCode(2L);
        rc.setCapScope(CapScope.PER_OWNER_SOFT);
        rc.setCapLimit(2);
        when(codeRepository.findByCodeIgnoreCase("ABCD2345")).thenReturn(Optional.of(rc));
        when(redemptionRepository.findByRedeemerUserIdAndRewardCodeId(1L, 100L)).thenReturn(Optional.empty());
        when(redemptionRepository.findByRedeemerUserIdAndProgram(1L, RewardProgram.REFERRAL)).thenReturn(Optional.empty());
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.empty());
        when(codeRepository.tryReserveRedemption(100L)).thenReturn(1);
        RewardCode afterReserve = referralCode(2L);
        afterReserve.setCapScope(CapScope.PER_OWNER_SOFT);
        afterReserve.setCapLimit(2);
        afterReserve.setCurrentRedemptions(3); // past the soft cap of 2
        when(codeRepository.findById(100L)).thenReturn(Optional.of(afterReserve));

        RewardService.RedeemResult result = svc.redeem(1L, "ABCD2345");

        assertThat(result.status()).isEqualTo(RewardService.RedeemStatus.TRACK_ONLY);
        assertThat(result.redemption().getStatus()).isEqualTo(RewardStatus.TRACK_ONLY);
        verify(redemptionRepository).save(any(RewardRedemption.class));
    }

    @Test
    @DisplayName("getOrMintReferralCode: returns the existing code without minting a new one")
    void mintReturnsExisting() {
        RewardCode existing = referralCode(7L);
        when(codeRepository.findByOwnerUserIdAndProgram(7L, RewardProgram.REFERRAL))
                .thenReturn(Optional.of(existing));
        assertThat(service.getOrMintReferralCode(7L)).isSameAs(existing);
        verify(codeRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("getOrMintReferralCode: mints a referral code configured for both-party conversion reward")
    void mintCreatesReferralCode() {
        when(codeRepository.findByOwnerUserIdAndProgram(7L, RewardProgram.REFERRAL)).thenReturn(Optional.empty());
        when(codeRepository.saveAndFlush(any(RewardCode.class))).thenAnswer(inv -> inv.getArgument(0));

        RewardCode minted = service.getOrMintReferralCode(7L);

        assertThat(minted.getProgram()).isEqualTo(RewardProgram.REFERRAL);
        assertThat(minted.getOwnerUserId()).isEqualTo(7L);
        assertThat(minted.getBenefitKind()).isEqualTo(BenefitKind.CREDIT_GRANT);
        assertThat(minted.getBenefitTrigger()).isEqualTo(BenefitTrigger.PAID_CONVERSION);
        assertThat(minted.getOwnerRewardAmount()).isEqualTo(8000);
        assertThat(minted.getHoldDays()).isEqualTo(14);
        assertThat(minted.isClawbackEnabled()).isTrue();
        assertThat(minted.getCapScope()).isEqualTo(CapScope.NONE);
        assertThat(minted.getCode()).hasSize(8);
    }

    @Test
    @DisplayName("claimFreeWorkflowNode: delegates to the atomic per-node claim")
    void claimFreeNode() {
        when(redemptionRepository.claimFreeWorkflowNode(eq(5L), eq(RewardCode.BENEFIT_WORKFLOW_NODE_FREE)))
                .thenReturn(1);
        assertThat(service.claimFreeWorkflowNode(5L)).isTrue();
        assertThat(service.claimFreeWorkflowNode(null)).isFalse();
    }
}
