package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.*;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.RewardCodeRepository;
import com.apimarketplace.auth.repository.RewardRedemptionRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the conversion lifecycle: qualify on paid conversion, release
 * after the hold (granting both parties via the PAYG source type), and clawback
 * on refund/dispute. The grant routing to the PAYG bucket is asserted in the
 * CreditService bucket-routing test; here we assert the source type and amounts.
 */
class RewardConversionLifecycleTest {

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

    private RewardCode referralCode() {
        RewardCode rc = new RewardCode();
        rc.setId(100L);
        rc.setProgram(RewardProgram.REFERRAL);
        rc.setOwnerUserId(2L);
        rc.setBenefitKind(BenefitKind.CREDIT_GRANT);
        rc.setBenefitAmount(8000);
        rc.setBenefitTrigger(BenefitTrigger.PAID_CONVERSION);
        rc.setOwnerRewardKind(OwnerRewardKind.CREDIT_GRANT);
        rc.setOwnerRewardAmount(8000);
        rc.setHoldDays(14);
        rc.setClawbackEnabled(true);
        return rc;
    }

    private RewardRedemption redemption(RewardStatus status) {
        RewardRedemption r = new RewardRedemption();
        r.setId(900L);
        r.setRewardCodeId(100L);
        r.setRedeemerUserId(1L);
        r.setOwnerUserId(2L);
        r.setProgram(RewardProgram.REFERRAL);
        r.setStatus(status);
        r.setRedeemedAt(Instant.now());
        r.setRedeemerRewardAmount(8000);
        r.setOwnerRewardAmount(8000);
        return r;
    }

    @Test
    @DisplayName("qualify: a PENDING conversion becomes QUALIFIED with snapshot + hold window")
    void qualifyPendingToQualified() {
        RewardRedemption pending = redemption(RewardStatus.PENDING);
        pending.setRedeemerRewardAmount(null);
        pending.setOwnerRewardAmount(null);
        when(redemptionRepository.findByRedeemerUserIdAndStatus(1L, RewardStatus.PENDING))
                .thenReturn(List.of(pending));
        when(codeRepository.findById(100L)).thenReturn(Optional.of(referralCode()));

        service.qualifyOnPaidConversion(1L, "sub_123");

        assertThat(pending.getStatus()).isEqualTo(RewardStatus.QUALIFIED);
        assertThat(pending.getProviderSubscriptionId()).isEqualTo("sub_123");
        assertThat(pending.getRedeemerRewardAmount()).isEqualTo(8000);
        assertThat(pending.getOwnerRewardAmount()).isEqualTo(8000);
        assertThat(pending.getReleaseDueAt()).isAfter(Instant.now().plus(13, ChronoUnit.DAYS));
        verify(redemptionRepository).save(pending);
    }

    @Test
    @DisplayName("qualify: nothing PENDING is a no-op (idempotent on replay)")
    void qualifyNoPending() {
        when(redemptionRepository.findByRedeemerUserIdAndStatus(1L, RewardStatus.PENDING))
                .thenReturn(List.of());
        service.qualifyOnPaidConversion(1L, "sub_123");
        verify(redemptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("release: a qualified reward grants BOTH parties on the PAYG source type and flips to RELEASED")
    void releaseGrantsBoth() {
        RewardRedemption r = redemption(RewardStatus.QUALIFIED);
        when(redemptionRepository.lockByIdForUpdate(900L)).thenReturn(Optional.of(r));
        when(ledgerRepository.existsBySourceId(anyString())).thenReturn(false);

        boolean released = service.releaseOne(900L);

        assertThat(released).isTrue();
        assertThat(r.getStatus()).isEqualTo(RewardStatus.RELEASED);
        verify(creditService).grantCredits(eq(1L), eq(BigDecimal.valueOf(8000)),
                eq("REWARD_REFERRAL"), eq("REWARD_REFERRAL_900_REDEEMER"), anyString());
        verify(creditService).grantCredits(eq(2L), eq(BigDecimal.valueOf(8000)),
                eq("REWARD_REFERRAL"), eq("REWARD_REFERRAL_900_OWNER"), anyString());
    }

    @Test
    @DisplayName("release: an already-granted source id is skipped (idempotent), status still RELEASED")
    void releaseIdempotent() {
        RewardRedemption r = redemption(RewardStatus.QUALIFIED);
        when(redemptionRepository.lockByIdForUpdate(900L)).thenReturn(Optional.of(r));
        when(ledgerRepository.existsBySourceId(anyString())).thenReturn(true);

        assertThat(service.releaseOne(900L)).isTrue();
        assertThat(r.getStatus()).isEqualTo(RewardStatus.RELEASED);
        verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("release: a non-qualified row is a no-op (the loser of the lock race)")
    void releaseNonQualified() {
        RewardRedemption r = redemption(RewardStatus.CLAWED_BACK);
        when(redemptionRepository.lockByIdForUpdate(900L)).thenReturn(Optional.of(r));
        assertThat(service.releaseOne(900L)).isFalse();
        verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("clawback in-hold: a qualified (ungranted) reward is revoked with no negative grant")
    void clawbackInHold() {
        RewardRedemption r = redemption(RewardStatus.QUALIFIED);
        when(redemptionRepository.findByRedeemerUserIdAndProgram(1L, RewardProgram.REFERRAL))
                .thenReturn(Optional.of(r));
        when(redemptionRepository.lockByIdForUpdate(900L)).thenReturn(Optional.of(r));

        service.clawbackByRedeemerUserId(1L, "REFUNDED");

        assertThat(r.getStatus()).isEqualTo(RewardStatus.CLAWED_BACK);
        assertThat(r.getClawedBackAt()).isNotNull();
        verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("clawback post-release: a released reward is negated for both parties on the clawback source type")
    void clawbackPostRelease() {
        RewardRedemption r = redemption(RewardStatus.RELEASED);
        when(redemptionRepository.findByRedeemerUserIdAndProgram(1L, RewardProgram.REFERRAL))
                .thenReturn(Optional.of(r));
        when(redemptionRepository.lockByIdForUpdate(900L)).thenReturn(Optional.of(r));
        when(ledgerRepository.existsBySourceId(anyString())).thenReturn(false);

        service.clawbackByRedeemerUserId(1L, "DISPUTED");

        assertThat(r.getStatus()).isEqualTo(RewardStatus.CLAWED_BACK);
        verify(creditService).grantCredits(eq(1L), eq(BigDecimal.valueOf(-8000)),
                eq("REWARD_CLAWBACK"), eq("REWARD_CLAWBACK_900_REDEEMER"), anyString());
        verify(creditService).grantCredits(eq(2L), eq(BigDecimal.valueOf(-8000)),
                eq("REWARD_CLAWBACK"), eq("REWARD_CLAWBACK_900_OWNER"), anyString());
    }

    @Test
    @DisplayName("release: a PARTNER conversion records and releases with NO owner credit grant (payout deferred to v2)")
    void releasePartnerInertSeam() {
        RewardRedemption r = redemption(RewardStatus.QUALIFIED);
        r.setProgram(RewardProgram.PARTNER);
        r.setRedeemerRewardAmount(0); // no redeemer credit benefit in this partner case
        when(redemptionRepository.lockByIdForUpdate(900L)).thenReturn(Optional.of(r));

        assertThat(service.releaseOne(900L)).isTrue();
        assertThat(r.getStatus()).isEqualTo(RewardStatus.RELEASED);
        // No PartnerPayoutSpi bean in v1, so no money-out and no PAYG credit grant.
        verify(creditService, never()).grantCredits(anyLong(), any(), anyString(), anyString(), anyString());
    }
}
