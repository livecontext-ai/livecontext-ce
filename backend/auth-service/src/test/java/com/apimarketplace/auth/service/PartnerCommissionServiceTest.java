package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.*;
import com.apimarketplace.auth.repository.PartnerCommissionRepository;
import com.apimarketplace.auth.repository.RewardCodeRepository;
import com.apimarketplace.auth.repository.RewardRedemptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** V549 partner revenue share: which paid invoices earn a line, how much, and how refunds void it. */
class PartnerCommissionServiceTest {

    private static final long CUSTOMER = 7L;
    private static final long PARTNER = 99L;

    private PartnerCommissionRepository commissionRepository;
    private RewardRedemptionRepository redemptionRepository;
    private RewardCodeRepository codeRepository;
    private PartnerCommissionService service;
    private RewardCode code;
    private RewardRedemption redemption;

    @BeforeEach
    void setUp() {
        commissionRepository = mock(PartnerCommissionRepository.class);
        when(commissionRepository.voidIfOnHold(any(), any(), any())).thenReturn(1);
        redemptionRepository = mock(RewardRedemptionRepository.class);
        codeRepository = mock(RewardCodeRepository.class);
        service = new PartnerCommissionService(commissionRepository, redemptionRepository, codeRepository);

        code = new RewardCode();
        code.setId(400L);
        code.setProgram(RewardProgram.PARTNER);
        code.setOwnerUserId(PARTNER);
        code.setOwnerRewardKind(OwnerRewardKind.PARTNER_PAYOUT);
        code.setPayoutBps(3000);
        code.setPayoutMonths(12);
        code.setHoldDays(14);
        code.setActive(true);
        when(codeRepository.findById(400L)).thenReturn(Optional.of(code));

        redemption = new RewardRedemption();
        redemption.setId(555L);
        redemption.setRewardCodeId(400L);
        redemption.setOwnerUserId(PARTNER);
        redemption.setRedeemerUserId(CUSTOMER);
        redemption.setProgram(RewardProgram.PARTNER);
        redemption.setStatus(RewardStatus.GRANTED);
        redemption.setActive(true);
        when(redemptionRepository.findByRedeemerUserIdAndProgram(CUSTOMER, RewardProgram.PARTNER))
                .thenReturn(Optional.of(redemption));
        when(commissionRepository.findFirstInvoicePaidAt(555L)).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("records 30% of the invoice (excl. tax), held 14 days, owed to the partner")
    void recordsShareWithHold() {
        Instant paidAt = Instant.parse("2026-10-01T10:00:00Z");

        var outcome = service.recordPaidInvoice(CUSTOMER, "in_1", 2400, "USD", paidAt);

        assertThat(outcome).isEqualTo(PartnerCommissionService.RecordOutcome.RECORDED);
        ArgumentCaptor<PartnerCommission> saved = ArgumentCaptor.forClass(PartnerCommission.class);
        verify(commissionRepository).save(saved.capture());
        PartnerCommission c = saved.getValue();
        assertThat(c.getPartnerUserId()).isEqualTo(PARTNER);
        assertThat(c.getCommissionMinor()).isEqualTo(720);
        assertThat(c.getCurrency()).isEqualTo("usd");
        assertThat(c.getStatus()).isEqualTo(PartnerCommission.Status.HOLD);
        assertThat(c.getDueAt()).isEqualTo(paidAt.plus(14, ChronoUnit.DAYS));
        assertThat(c.isPayableAt(paidAt.plus(13, ChronoUnit.DAYS))).isFalse();
        assertThat(c.isPayableAt(paidAt.plus(14, ChronoUnit.DAYS))).isTrue();
    }

    @Test
    @DisplayName("the share is rounded DOWN: a partner is never paid a cent that was not earned")
    void shareRoundsDown() {
        assertThat(PartnerCommissionService.commissionOf(999, 3000)).isEqualTo(299);
        assertThat(PartnerCommissionService.commissionOf(1, 3000)).isZero();
    }

    @Test
    @DisplayName("a replayed invoice.paid records nothing new (idempotent on the invoice id)")
    void duplicateInvoiceIsIgnored() {
        when(commissionRepository.existsByProviderInvoiceId("in_1")).thenReturn(true);

        var outcome = service.recordPaidInvoice(CUSTOMER, "in_1", 2400, "usd", Instant.now());

        assertThat(outcome).isEqualTo(PartnerCommissionService.RecordOutcome.DUPLICATE);
        verify(commissionRepository, never()).save(any());
    }

    @Test
    @DisplayName("a customer no partner referred earns nobody anything")
    void unattributedCustomer() {
        when(redemptionRepository.findByRedeemerUserIdAndProgram(CUSTOMER, RewardProgram.PARTNER))
                .thenReturn(Optional.empty());

        assertThat(service.recordPaidInvoice(CUSTOMER, "in_1", 2400, "usd", Instant.now()))
                .isEqualTo(PartnerCommissionService.RecordOutcome.NOT_ATTRIBUTED);
        verify(commissionRepository, never()).save(any());
    }

    @Test
    @DisplayName("a clawed-back attribution earns nothing more")
    void clawedBackAttribution() {
        redemption.setStatus(RewardStatus.CLAWED_BACK);

        assertThat(service.recordPaidInvoice(CUSTOMER, "in_1", 2400, "usd", Instant.now()))
                .isEqualTo(PartnerCommissionService.RecordOutcome.NOT_ATTRIBUTED);
    }

    @Test
    @DisplayName("a disabled code (or a purged partner's) stops earning, even on customers it already brought")
    void disabledCodeStopsEarning() {
        code.setActive(false);

        assertThat(service.recordPaidInvoice(CUSTOMER, "in_1", 2400, "usd", Instant.now()))
                .isEqualTo(PartnerCommissionService.RecordOutcome.NOT_REVENUE_SHARE);
        verify(commissionRepository, never()).save(any());
    }

    @Test
    @DisplayName("a partner code without a revenue share (no PARTNER_PAYOUT) records nothing")
    void codeWithoutRevenueShare() {
        code.setOwnerRewardKind(OwnerRewardKind.NONE);

        assertThat(service.recordPaidInvoice(CUSTOMER, "in_1", 2400, "usd", Instant.now()))
                .isEqualTo(PartnerCommissionService.RecordOutcome.NOT_REVENUE_SHARE);
    }

    @Test
    @DisplayName("the window is 12 months from the customer's FIRST commissionable invoice, then it stops")
    void windowEndsAfterConfiguredMonths() {
        Instant first = Instant.parse("2026-01-15T00:00:00Z");
        when(commissionRepository.findFirstInvoicePaidAt(555L)).thenReturn(Optional.of(first));
        Instant lastInside = first.atOffset(ZoneOffset.UTC).plusMonths(12).minusSeconds(1).toInstant();
        Instant firstOutside = first.atOffset(ZoneOffset.UTC).plusMonths(12).toInstant();

        assertThat(service.recordPaidInvoice(CUSTOMER, "in_a", 2400, "usd", lastInside))
                .isEqualTo(PartnerCommissionService.RecordOutcome.RECORDED);
        assertThat(service.recordPaidInvoice(CUSTOMER, "in_b", 2400, "usd", firstOutside))
                .isEqualTo(PartnerCommissionService.RecordOutcome.WINDOW_ENDED);
    }

    @Test
    @DisplayName("a zero-amount invoice (trial, 100% coupon) records nothing")
    void zeroAmount() {
        assertThat(service.recordPaidInvoice(CUSTOMER, "in_1", 0, "usd", Instant.now()))
                .isEqualTo(PartnerCommissionService.RecordOutcome.ZERO_AMOUNT);
    }

    private PartnerCommission line(long id, long base, Instant paidAt) {
        PartnerCommission c = new PartnerCommission();
        c.setId(id);
        c.setBaseAmountMinor(base);
        c.setCommissionMinor(base * 3 / 10);
        c.setInvoicePaidAt(paidAt);
        c.setStatus(PartnerCommission.Status.HOLD);
        c.setCurrency("usd");
        return c;
    }

    @Test
    @DisplayName("refund: voids the line whose invoice matches the charged amount (tax included), not simply the newest")
    void refundVoidsMatchingLine() {
        PartnerCommission newest = line(2, 4200, Instant.parse("2026-11-01T00:00:00Z"));
        PartnerCommission older = line(1, 2400, Instant.parse("2026-10-01T00:00:00Z"));
        when(commissionRepository.findByCustomerUserIdAndStatusOrderByInvoicePaidAtDesc(CUSTOMER, PartnerCommission.Status.HOLD))
                .thenReturn(List.of(newest, older));

        int voided = service.voidForRefund(CUSTOMER, null, true, 2880L, "usd", "REFUNDED"); // 2400 + 20% VAT

        assertThat(voided).isEqualTo(1);
        assertThat(older.getStatus()).isEqualTo(PartnerCommission.Status.VOID);
        assertThat(older.getVoidReason()).isEqualTo("REFUNDED");
        assertThat(newest.getStatus()).isEqualTo(PartnerCommission.Status.HOLD);
    }

    @Test
    @DisplayName("refund matching no unsettled line voids nothing (never takes another invoice's commission)")
    void refundWithoutMatchVoidsNothing() {
        PartnerCommission newest = line(2, 4200, Instant.parse("2026-11-01T00:00:00Z"));
        PartnerCommission older = line(1, 2400, Instant.parse("2026-10-01T00:00:00Z"));
        when(commissionRepository.findByCustomerUserIdAndStatusOrderByInvoicePaidAtDesc(CUSTOMER, PartnerCommission.Status.HOLD))
                .thenReturn(List.of(newest, older));

        int voided = service.voidForRefund(CUSTOMER, null, true, 99_999L, "usd", "DISPUTED");

        assertThat(voided).isZero();
        assertThat(newest.getStatus()).isEqualTo(PartnerCommission.Status.HOLD);
        assertThat(older.getStatus()).isEqualTo(PartnerCommission.Status.HOLD);
        verify(commissionRepository, never()).save(any());
    }

    @Test
    @DisplayName("refund when every line is already PAID changes nothing (settled money is reconciled by hand)")
    void refundAfterPayoutChangesNothing() {
        when(commissionRepository.findByCustomerUserIdAndStatusOrderByInvoicePaidAtDesc(CUSTOMER, PartnerCommission.Status.HOLD))
                .thenReturn(List.of());
        when(commissionRepository.findByCustomerUserIdAndStatusOrderByInvoicePaidAtDesc(CUSTOMER, PartnerCommission.Status.PAID))
                .thenReturn(List.of(line(1, 2400, Instant.now())));

        assertThat(service.voidForRefund(CUSTOMER, null, true, 2400L, "usd", "REFUNDED")).isZero();
        verify(commissionRepository, never()).save(any());
    }

    @Test
    @DisplayName("refund in another currency never voids a line of the same amount (a refunded top-up in EUR vs a USD line)")
    void refundInOtherCurrencyVoidsNothing() {
        PartnerCommission usdLine = line(1, 2400, Instant.parse("2026-10-01T00:00:00Z"));
        when(commissionRepository.findByCustomerUserIdAndStatusOrderByInvoicePaidAtDesc(CUSTOMER, PartnerCommission.Status.HOLD))
                .thenReturn(List.of(usdLine));

        assertThat(service.voidForRefund(CUSTOMER, null, true, 2400L, "eur", "REFUNDED")).isZero();
        assertThat(usdLine.getStatus()).isEqualTo(PartnerCommission.Status.HOLD);
    }

    @Test
    @DisplayName("refund resolved to its invoice voids exactly that invoice's line, whatever the amounts of the others")
    void refundByInvoiceIsExact() {
        PartnerCommission target = line(1, 2400, Instant.parse("2026-10-01T00:00:00Z"));
        target.setProviderInvoiceId("in_oct");
        when(commissionRepository.findByProviderInvoiceId("in_oct")).thenReturn(Optional.of(target));

        assertThat(service.voidForRefund(CUSTOMER, "in_oct", false, 2880L, "usd", "REFUNDED")).isEqualTo(1);
        assertThat(target.getStatus()).isEqualTo(PartnerCommission.Status.VOID);
        verify(commissionRepository, never()).findByCustomerUserIdAndStatusOrderByInvoicePaidAtDesc(any(), any());
    }

    @Test
    @DisplayName("refund of an invoice that earned no commission (outside the window) voids nothing, never a look-alike")
    void refundOfInvoiceWithoutLineVoidsNothing() {
        when(commissionRepository.findByProviderInvoiceId("in_old")).thenReturn(Optional.empty());

        assertThat(service.voidForRefund(CUSTOMER, "in_old", false, 2400L, "usd", "REFUNDED")).isZero();
        verify(commissionRepository, never()).save(any());
    }

    @Test
    @DisplayName("refund of an invoice whose line was already PAID changes nothing (settled money is reconciled by hand)")
    void refundOfPaidInvoiceLineChangesNothing() {
        PartnerCommission paid = line(1, 2400, Instant.now());
        paid.setStatus(PartnerCommission.Status.PAID);
        when(commissionRepository.findByProviderInvoiceId("in_1")).thenReturn(Optional.of(paid));

        assertThat(service.voidForRefund(CUSTOMER, "in_1", false, 2400L, "usd", "REFUNDED")).isZero();
        assertThat(paid.getStatus()).isEqualTo(PartnerCommission.Status.PAID);
    }

    @Test
    @DisplayName("without an invoice id, two identical monthly lines are ambiguous: nothing is voided (WARN for a human)")
    void ambiguousAmountFallbackVoidsNothing() {
        PartnerCommission nov = line(2, 2400, Instant.parse("2026-11-01T00:00:00Z"));
        PartnerCommission oct = line(1, 2400, Instant.parse("2026-10-01T00:00:00Z"));
        when(commissionRepository.findByCustomerUserIdAndStatusOrderByInvoicePaidAtDesc(CUSTOMER, PartnerCommission.Status.HOLD))
                .thenReturn(List.of(nov, oct));

        assertThat(service.voidForRefund(CUSTOMER, null, true, 2880L, "usd", "REFUNDED")).isZero();
        assertThat(nov.getStatus()).isEqualTo(PartnerCommission.Status.HOLD);
        assertThat(oct.getStatus()).isEqualTo(PartnerCommission.Status.HOLD);
    }

    @Test
    @DisplayName("Stripe CONFIRMED the refunded payment belongs to no invoice: nothing is voided, never a look-alike line")
    void confirmedNoInvoiceVoidsNothing() {
        PartnerCommission lookAlike = line(1, 2400, Instant.parse("2026-10-01T00:00:00Z"));
        when(commissionRepository.findByCustomerUserIdAndStatusOrderByInvoicePaidAtDesc(CUSTOMER, PartnerCommission.Status.HOLD))
                .thenReturn(List.of(lookAlike));

        assertThat(service.voidForRefund(CUSTOMER, null, false, 2880L, "usd", "REFUNDED")).isZero();
        assertThat(lookAlike.getStatus()).isEqualTo(PartnerCommission.Status.HOLD);
    }

    @Test
    @DisplayName("a refund void that loses to an admin's concurrent payout changes nothing (the PAID record survives)")
    void voidLosesToConcurrentPayout() {
        PartnerCommission line = line(1, 2400, Instant.now());
        line.setProviderInvoiceId("in_1");
        when(commissionRepository.findByProviderInvoiceId("in_1")).thenReturn(Optional.of(line));
        when(commissionRepository.voidIfOnHold(eq(1L), any(), eq("REFUNDED"))).thenReturn(0);

        assertThat(service.voidForRefund(CUSTOMER, "in_1", false, 2400L, "usd", "REFUNDED")).isZero();
        assertThat(line.getStatus()).isEqualTo(PartnerCommission.Status.HOLD);
    }
}
