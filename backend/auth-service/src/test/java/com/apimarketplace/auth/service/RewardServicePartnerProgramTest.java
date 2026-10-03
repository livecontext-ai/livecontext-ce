package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.*;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.RewardCodeRepository;
import com.apimarketplace.auth.repository.RewardRedemptionRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * V549 redeem branches: a creator code (PAYG credits + timed PRO, granted on the spot) and a
 * partner code (PAYG credits for the new user + attribution to the partner, first code wins).
 */
class RewardServicePartnerProgramTest {

    private static final long USER = 7L;
    private static final long PARTNER = 99L;

    private RewardCodeRepository codeRepository;
    private RewardRedemptionRepository redemptionRepository;
    private SubscriptionRepository subscriptionRepository;
    private CreditService creditService;
    private CreditLedgerRepository ledgerRepository;
    private AdminPlanService adminPlanService;
    private com.apimarketplace.auth.repository.UserRepository userRepository;
    private User redeemer;
    private RewardService service;

    @BeforeEach
    void setUp() {
        codeRepository = mock(RewardCodeRepository.class);
        redemptionRepository = mock(RewardRedemptionRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        creditService = mock(CreditService.class);
        ledgerRepository = mock(CreditLedgerRepository.class);
        adminPlanService = mock(AdminPlanService.class);
        service = new RewardService(codeRepository, redemptionRepository, subscriptionRepository,
                creditService, ledgerRepository, 8000, 14, null);
        service.setAdminPlanService(adminPlanService);
        userRepository = mock(com.apimarketplace.auth.repository.UserRepository.class);
        service.setUserRepository(userRepository);
        redeemer = new User();
        redeemer.setEmailVerified(true);
        redeemer.setCreatedAt(LocalDateTime.now().minusDays(1));
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(redeemer));

        when(codeRepository.tryReserveRedemption(anyLong())).thenReturn(1);
        when(redemptionRepository.findByRedeemerUserIdAndRewardCodeId(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(redemptionRepository.findByRedeemerUserIdAndProgram(anyLong(), any())).thenReturn(Optional.empty());
        when(subscriptionRepository.findActiveByUserId(anyLong())).thenReturn(Optional.empty());
        when(redemptionRepository.save(any(RewardRedemption.class))).thenAnswer(inv -> {
            RewardRedemption r = inv.getArgument(0);
            if (r.getId() == null) r.setId(555L);
            return r;
        });
    }

    private RewardCode creatorCode() {
        RewardCode rc = new RewardCode();
        rc.setId(300L);
        rc.setCode("LC-CREATOR1");
        rc.setProgram(RewardProgram.PROMO);
        rc.setBenefitKind(BenefitKind.CREDIT_GRANT);
        rc.setBenefitTrigger(BenefitTrigger.REDEEM_TIME);
        rc.setBenefitAmount(50_000);
        rc.setBenefitPlanCode("PRO");
        rc.setBenefitPlanDays(90);
        rc.setCapScope(CapScope.GLOBAL);
        rc.setCapLimit(1);
        rc.setActive(true);
        rc.setValidFrom(Instant.now().minus(1, ChronoUnit.DAYS));
        when(codeRepository.findByCodeIgnoreCase("LC-CREATOR1")).thenReturn(Optional.of(rc));
        return rc;
    }

    private RewardCode partnerCode() {
        RewardCode rc = new RewardCode();
        rc.setId(400L);
        rc.setCode("TECHDOX");
        rc.setProgram(RewardProgram.PARTNER);
        rc.setOwnerUserId(PARTNER);
        rc.setBenefitKind(BenefitKind.CREDIT_GRANT);
        rc.setBenefitTrigger(BenefitTrigger.REDEEM_TIME);
        rc.setBenefitAmount(10_000);
        rc.setOwnerRewardKind(OwnerRewardKind.PARTNER_PAYOUT);
        rc.setPayoutBps(3000);
        rc.setPayoutMonths(12);
        rc.setHoldDays(14);
        rc.setCapScope(CapScope.NONE);
        rc.setActive(true);
        rc.setValidFrom(Instant.now().minus(1, ChronoUnit.DAYS));
        when(codeRepository.findByCodeIgnoreCase("TECHDOX")).thenReturn(Optional.of(rc));
        return rc;
    }

    private void payingStripeCustomer() {
        Plan plan = new Plan();
        plan.setCode("STARTER");
        Subscription s = new Subscription();
        s.setProvider("stripe");
        s.setPlan(plan);
        when(subscriptionRepository.findActiveByUserId(USER)).thenReturn(Optional.of(s));
    }

    @Test
    @DisplayName("creator code: grants 50,000 PAYG credits (REWARD_CODE) and a PRO plan ending in 90 days, on the spot")
    void creatorCodeGrantsCreditsAndTimedPro() {
        creatorCode();
        when(adminPlanService.grantTimedComp(eq(USER), eq("PRO"), any()))
                .thenReturn(AdminPlanService.AssignPlanResult.ok("FREE", "PRO"));

        RewardService.RedeemResult result = service.redeem(USER, "LC-CREATOR1");

        assertThat(result.status()).isEqualTo(RewardService.RedeemStatus.SUCCESS);
        assertThat(result.redemption().getStatus()).isEqualTo(RewardStatus.GRANTED);
        assertThat(result.grantedCredits()).isEqualTo(50_000);
        assertThat(result.grantedPlanCode()).isEqualTo("PRO");
        assertThat(result.planEndsAt()).isCloseTo(LocalDateTime.now().plusDays(90), within(1, ChronoUnit.MINUTES));
        verify(creditService).grantCredits(eq(USER), eq(BigDecimal.valueOf(50_000)), eq("REWARD_CODE"),
                eq("REWARD_CODE_555"), anyString());
    }

    @Test
    @DisplayName("creator code: an already-paying Stripe customer gets the credits and keeps their plan untouched")
    void creatorCodeForPayingCustomerGivesCreditsOnly() {
        creatorCode();
        payingStripeCustomer();

        RewardService.RedeemResult result = service.redeem(USER, "LC-CREATOR1");

        assertThat(result.status()).isEqualTo(RewardService.RedeemStatus.SUCCESS);
        assertThat(result.grantedCredits()).isEqualTo(50_000);
        assertThat(result.grantedPlanCode()).isNull();
        verifyNoInteractions(adminPlanService);
    }

    @Test
    @DisplayName("creator code: a refused plan grant (already on a higher tier) still delivers the credits")
    void refusedPlanStillGrantsCredits() {
        creatorCode();
        when(adminPlanService.grantTimedComp(eq(USER), eq("PRO"), any()))
                .thenReturn(AdminPlanService.AssignPlanResult.fail("already_on_higher_plan"));

        RewardService.RedeemResult result = service.redeem(USER, "LC-CREATOR1");

        assertThat(result.grantedCredits()).isEqualTo(50_000);
        assertThat(result.grantedPlanCode()).isNull();
        assertThat(result.planEndsAt()).isNull();
    }

    @Test
    @DisplayName("creator code: a replayed grant with an existing ledger row is not paid twice")
    void creditGrantIsIdempotentOnTheLedger() {
        creatorCode();
        when(ledgerRepository.existsBySourceId("REWARD_CODE_555")).thenReturn(true);
        when(adminPlanService.grantTimedComp(eq(USER), eq("PRO"), any()))
                .thenReturn(AdminPlanService.AssignPlanResult.ok("FREE", "PRO"));

        service.redeem(USER, "LC-CREATOR1");

        verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("partner code: the new user gets the audience credits and is attributed to the partner")
    void partnerCodeGrantsAudienceCreditsAndAttributes() {
        partnerCode();

        RewardService.RedeemResult result = service.redeem(USER, "TECHDOX");

        assertThat(result.status()).isEqualTo(RewardService.RedeemStatus.SUCCESS);
        ArgumentCaptor<RewardRedemption> saved = ArgumentCaptor.forClass(RewardRedemption.class);
        verify(redemptionRepository, atLeastOnce()).save(saved.capture());
        RewardRedemption r = saved.getValue();
        assertThat(r.getProgram()).isEqualTo(RewardProgram.PARTNER);
        assertThat(r.getOwnerUserId()).isEqualTo(PARTNER);
        assertThat(r.getStatus()).isEqualTo(RewardStatus.GRANTED);
        verify(creditService).grantCredits(eq(USER), eq(BigDecimal.valueOf(10_000)), eq("REWARD_CODE"),
                anyString(), anyString());
        verifyNoInteractions(adminPlanService);
    }

    @Test
    @DisplayName("partner code: the partner cannot redeem their own code")
    void partnerCannotRedeemOwnCode() {
        partnerCode();

        assertThat(service.redeem(PARTNER, "TECHDOX").status()).isEqualTo(RewardService.RedeemStatus.SELF_REFERRAL);
        verify(redemptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("partner code: a partner cannot be attributed to another partner (PARTNER_ACCOUNT), nothing granted")
    void partnerCannotRedeemAnotherPartnersCode() {
        partnerCode();
        // USER is a partner too: they own a partner code of their own.
        RewardCode own = new RewardCode();
        own.setProgram(RewardProgram.PARTNER);
        own.setOwnerUserId(USER);
        when(codeRepository.findByOwnerUserIdAndProgram(USER, RewardProgram.PARTNER)).thenReturn(Optional.of(own));

        assertThat(service.redeem(USER, "TECHDOX").status()).isEqualTo(RewardService.RedeemStatus.PARTNER_ACCOUNT);
        verify(codeRepository, never()).tryReserveRedemption(anyLong());
        verify(redemptionRepository, never()).save(any());
        verifyNoInteractions(creditService);
    }

    @Test
    @DisplayName("partner code: a disabled partner code still makes its owner a partner (PARTNER_ACCOUNT)")
    void disabledPartnerIsStillAPartner() {
        partnerCode();
        RewardCode own = new RewardCode();
        own.setProgram(RewardProgram.PARTNER);
        own.setOwnerUserId(USER);
        own.setActive(false);
        when(codeRepository.findByOwnerUserIdAndProgram(USER, RewardProgram.PARTNER)).thenReturn(Optional.of(own));

        assertThat(service.redeem(USER, "TECHDOX").status()).isEqualTo(RewardService.RedeemStatus.PARTNER_ACCOUNT);
    }

    @Test
    @DisplayName("a partner typing their OWN code is told so (SELF_REFERRAL), not that they are a partner")
    void ownCodeIsSelfReferralBeforePartnerAccount() {
        RewardCode own = partnerCode();
        when(codeRepository.findByOwnerUserIdAndProgram(PARTNER, RewardProgram.PARTNER)).thenReturn(Optional.of(own));

        assertThat(service.redeem(PARTNER, "TECHDOX").status()).isEqualTo(RewardService.RedeemStatus.SELF_REFERRAL);
    }

    @Test
    @DisplayName("a partner already attributed elsewhere is refused as a partner (PARTNER_ACCOUNT), not as attributed")
    void partnerAccountBeforeAlreadyAttributed() {
        partnerCode();
        RewardCode own = new RewardCode();
        own.setProgram(RewardProgram.PARTNER);
        own.setOwnerUserId(USER);
        when(codeRepository.findByOwnerUserIdAndProgram(USER, RewardProgram.PARTNER)).thenReturn(Optional.of(own));
        when(redemptionRepository.findByRedeemerUserIdAndProgram(USER, RewardProgram.PARTNER))
                .thenReturn(Optional.of(new RewardRedemption()));

        assertThat(service.redeem(USER, "TECHDOX").status()).isEqualTo(RewardService.RedeemStatus.PARTNER_ACCOUNT);
    }

    @Test
    @DisplayName("a partner may still redeem a creator code: the rule is about partner attribution only")
    void partnerMayRedeemCreatorCode() {
        creatorCode();
        RewardCode own = new RewardCode();
        own.setProgram(RewardProgram.PARTNER);
        own.setOwnerUserId(USER);
        when(codeRepository.findByOwnerUserIdAndProgram(USER, RewardProgram.PARTNER)).thenReturn(Optional.of(own));
        when(adminPlanService.grantTimedComp(eq(USER), eq("PRO"), any()))
                .thenReturn(AdminPlanService.AssignPlanResult.ok("FREE", "PRO"));

        assertThat(service.redeem(USER, "LC-CREATOR1").status()).isEqualTo(RewardService.RedeemStatus.SUCCESS);
    }

    @Test
    @DisplayName("partner code: a user who is not a partner is attributed as before (no false PARTNER_ACCOUNT)")
    void nonPartnerIsStillAttributed() {
        partnerCode();
        when(codeRepository.findByOwnerUserIdAndProgram(USER, RewardProgram.PARTNER)).thenReturn(Optional.empty());

        assertThat(service.redeem(USER, "TECHDOX").status()).isEqualTo(RewardService.RedeemStatus.SUCCESS);
        verify(codeRepository).tryReserveRedemption(400L);
    }

    @Test
    @DisplayName("partner code: a user already attributed to a partner keeps the first one (ALREADY_ATTRIBUTED)")
    void firstPartnerWins() {
        partnerCode();
        when(redemptionRepository.findByRedeemerUserIdAndProgram(USER, RewardProgram.PARTNER))
                .thenReturn(Optional.of(new RewardRedemption()));

        assertThat(service.redeem(USER, "TECHDOX").status()).isEqualTo(RewardService.RedeemStatus.ALREADY_ATTRIBUTED);
        verify(codeRepository, never()).tryReserveRedemption(anyLong());
        verifyNoInteractions(creditService);
    }

    @Test
    @DisplayName("partner code: an existing paying customer is never attributed (ALREADY_PAID), even at redeem time")
    void payingCustomerIsNotAttributed() {
        partnerCode();
        payingStripeCustomer();

        assertThat(service.redeem(USER, "TECHDOX").status()).isEqualTo(RewardService.RedeemStatus.ALREADY_PAID);
        verifyNoInteractions(creditService);
    }

    @Test
    @DisplayName("legacy free-node promo code keeps its immediate counter benefit and grants no credits")
    void freeNodePromoUnchanged() {
        RewardCode rc = new RewardCode();
        rc.setId(200L);
        rc.setCode("PROMO123");
        rc.setProgram(RewardProgram.PROMO);
        rc.setBenefitKind(BenefitKind.FREE_NODE_COUNTER);
        rc.setBenefitAmount(20_000);
        rc.setBenefitDurationDays(30);
        rc.setBenefitTrigger(BenefitTrigger.REDEEM_TIME);
        rc.setCapScope(CapScope.NONE);
        rc.setActive(true);
        rc.setValidFrom(Instant.now().minus(1, ChronoUnit.DAYS));
        when(codeRepository.findByCodeIgnoreCase("PROMO123")).thenReturn(Optional.of(rc));

        RewardService.RedeemResult result = service.redeem(USER, "PROMO123");

        assertThat(result.redemption().getBenefitType()).isEqualTo(RewardCode.BENEFIT_WORKFLOW_NODE_FREE);
        assertThat(result.grantedCredits()).isZero();
        verifyNoInteractions(creditService, adminPlanService);
    }

    @Test
    @DisplayName("creator code on a longer existing timed PRO reports the EFFECTIVE (later) end, not the requested one")
    void reportsEffectivePlanEnd() {
        creatorCode();
        when(adminPlanService.grantTimedComp(eq(USER), eq("PRO"), any()))
                .thenReturn(AdminPlanService.AssignPlanResult.ok("PRO", "PRO"));
        LocalDateTime later = LocalDateTime.now().plusDays(200);
        Subscription current = new Subscription();
        current.setProvider("internal");
        current.setCompEndsAt(later);
        when(subscriptionRepository.findActiveByUserId(USER))
                .thenReturn(Optional.empty())       // the paying check, before the grant
                .thenReturn(Optional.of(current));  // the effective end, after it

        RewardService.RedeemResult result = service.redeem(USER, "LC-CREATOR1");

        assertThat(result.planEndsAt()).isEqualTo(later);
    }

    @Test
    @DisplayName("a partner's audience redemptions do not inflate their personal Refer & earn numbers")
    void inviteStatsCountOnlyReferrals() {
        RewardCode referral = new RewardCode();
        referral.setCode("REFCODE1");
        when(codeRepository.findByOwnerUserIdAndProgram(PARTNER, RewardProgram.REFERRAL)).thenReturn(Optional.of(referral));
        RewardRedemption fromReferral = new RewardRedemption();
        fromReferral.setProgram(RewardProgram.REFERRAL);
        fromReferral.setStatus(RewardStatus.PENDING);
        RewardRedemption fromPartnerLink = new RewardRedemption();
        fromPartnerLink.setProgram(RewardProgram.PARTNER);
        fromPartnerLink.setStatus(RewardStatus.GRANTED);
        when(redemptionRepository.findByOwnerUserId(PARTNER)).thenReturn(java.util.List.of(fromReferral, fromPartnerLink));

        RewardService.InviteStats stats = service.getInviteStats(PARTNER);

        assertThat(stats.redeemedCount()).isEqualTo(1);
        assertThat(stats.pendingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unverified email gets no credits and no plan yet (EMAIL_NOT_VERIFIED), and the single use is kept")
    void unverifiedEmailWaits() {
        creatorCode();
        redeemer.setEmailVerified(false);

        assertThat(service.redeem(USER, "LC-CREATOR1").status()).isEqualTo(RewardService.RedeemStatus.EMAIL_NOT_VERIFIED);
        verify(codeRepository, never()).tryReserveRedemption(anyLong());
        verifyNoInteractions(creditService, adminPlanService);
    }

    @Test
    @DisplayName("partner code: an account older than the new-user window is not attributed (NOT_NEW_ACCOUNT)")
    void oldAccountIsNotANewUser() {
        partnerCode();
        redeemer.setCreatedAt(LocalDateTime.now().minusDays(31));

        assertThat(service.redeem(USER, "TECHDOX").status()).isEqualTo(RewardService.RedeemStatus.NOT_NEW_ACCOUNT);
        verify(codeRepository, never()).tryReserveRedemption(anyLong());
    }

    @Test
    @DisplayName("creator code: the new-user window does not apply (a creator may already have an account)")
    void creatorCodeIgnoresAccountAge() {
        creatorCode();
        redeemer.setCreatedAt(LocalDateTime.now().minusDays(400));
        when(adminPlanService.grantTimedComp(eq(USER), eq("PRO"), any()))
                .thenReturn(AdminPlanService.AssignPlanResult.ok("FREE", "PRO"));

        assertThat(service.redeem(USER, "LC-CREATOR1").status()).isEqualTo(RewardService.RedeemStatus.SUCCESS);
    }

    @Test
    @DisplayName("a plan-only code the account cannot receive is refused BEFORE its single use is consumed")
    void planOnlyCodeNotBurned() {
        RewardCode rc = creatorCode();
        rc.setBenefitAmount(0);
        when(adminPlanService.timedCompRefusal(eq(USER), eq("PRO"), any())).thenReturn("already_on_permanent_plan");

        assertThat(service.redeem(USER, "LC-CREATOR1").status()).isEqualTo(RewardService.RedeemStatus.NOTHING_TO_GRANT);
        verify(codeRepository, never()).tryReserveRedemption(anyLong());
    }

    @Test
    @DisplayName("a plan-only code on a paying Stripe customer is refused too (they keep their plan)")
    void planOnlyCodeOnPayingCustomer() {
        RewardCode rc = creatorCode();
        rc.setBenefitAmount(0);
        payingStripeCustomer();

        assertThat(service.redeem(USER, "LC-CREATOR1").status()).isEqualTo(RewardService.RedeemStatus.NOTHING_TO_GRANT);
    }
}
