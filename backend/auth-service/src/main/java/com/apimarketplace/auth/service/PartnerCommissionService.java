package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.OwnerRewardKind;
import com.apimarketplace.auth.domain.PartnerCommission;
import com.apimarketplace.auth.domain.RewardCode;
import com.apimarketplace.auth.domain.RewardProgram;
import com.apimarketplace.auth.domain.RewardRedemption;
import com.apimarketplace.auth.domain.RewardStatus;
import com.apimarketplace.auth.repository.PartnerCommissionRepository;
import com.apimarketplace.auth.repository.RewardCodeRepository;
import com.apimarketplace.auth.repository.RewardRedemptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Partner revenue share (V549): records one {@link PartnerCommission} line per paid Stripe
 * invoice of a customer attributed to a PARTNER code, and voids unsettled lines when the
 * customer's payment is refunded or disputed.
 *
 * <p>Attribution is the customer's single PARTNER redemption (first code wins, unique per
 * customer). The share is the partner's rate (V556: the higher of the code's {@code payout_bps}
 * and the partner's tier rate, see {@link PartnerTierService}) of the invoice amount excluding tax, for
 * {@code payout_months} counted from the customer's FIRST commissionable invoice. Each line
 * is held {@code hold_days} (the refund window) before it becomes payable. Money never
 * moves here: an admin settles payable lines and marks them PAID.
 */
@Service
public class PartnerCommissionService {

    private static final Logger log = LoggerFactory.getLogger(PartnerCommissionService.class);

    public enum RecordOutcome { RECORDED, DUPLICATE, NOT_ATTRIBUTED, NOT_REVENUE_SHARE, WINDOW_ENDED, ZERO_AMOUNT }

    private final PartnerCommissionRepository commissionRepository;
    private final RewardRedemptionRepository redemptionRepository;
    private final RewardCodeRepository codeRepository;
    private final PartnerTierService tierService;

    public PartnerCommissionService(PartnerCommissionRepository commissionRepository,
                                    RewardRedemptionRepository redemptionRepository,
                                    RewardCodeRepository codeRepository,
                                    PartnerTierService tierService) {
        this.commissionRepository = commissionRepository;
        this.redemptionRepository = redemptionRepository;
        this.codeRepository = codeRepository;
        this.tierService = tierService;
    }

    /**
     * Record the partner's share of one paid invoice. Idempotent on the invoice id, so a
     * replayed {@code invoice.paid} (typed or RAW, or both paid events) records nothing new.
     *
     * @param baseAmountMinor invoice amount excluding tax, in minor units (cents)
     */
    @Transactional
    public RecordOutcome recordPaidInvoice(Long customerUserId, String invoiceId, long baseAmountMinor,
                                           String currency, Instant paidAt) {
        if (customerUserId == null || invoiceId == null || invoiceId.isBlank()) {
            return RecordOutcome.NOT_ATTRIBUTED;
        }
        if (baseAmountMinor <= 0) return RecordOutcome.ZERO_AMOUNT;
        Optional<RewardRedemption> attribution =
                redemptionRepository.findByRedeemerUserIdAndProgram(customerUserId, RewardProgram.PARTNER);
        if (attribution.isEmpty()) return RecordOutcome.NOT_ATTRIBUTED;
        RewardRedemption redemption = attribution.get();
        if (!redemption.isActive() || redemption.getStatus() == RewardStatus.CLAWED_BACK
                || redemption.getOwnerUserId() == null) {
            return RecordOutcome.NOT_ATTRIBUTED;
        }
        RewardCode code = codeRepository.findById(redemption.getRewardCodeId()).orElse(null);
        // A disabled code stops earning, including on customers it already brought (that is
        // what "Disable" means in the admin page, and what a purged partner's codes become).
        if (code == null || !code.isActive() || code.getOwnerRewardKind() != OwnerRewardKind.PARTNER_PAYOUT
                || code.getPayoutBps() == null || code.getPayoutMonths() == null) {
            return RecordOutcome.NOT_REVENUE_SHARE;
        }
        if (commissionRepository.existsByProviderInvoiceId(invoiceId)) return RecordOutcome.DUPLICATE;

        Instant when = paidAt != null ? paidAt : Instant.now();
        Instant windowStart = commissionRepository.findFirstInvoicePaidAt(redemption.getId()).orElse(when);
        Instant windowEnd = windowStart.atOffset(ZoneOffset.UTC).plusMonths(code.getPayoutMonths()).toInstant();
        if (!when.isBefore(windowEnd)) return RecordOutcome.WINDOW_ENDED;

        // The tier is brought up to date first, so an invoice paid after the partner crossed a
        // threshold already earns the higher rate. This invoice itself is not counted yet: it is
        // inside its refund window.
        int bps = tierService.effectiveRateBps(code.getPayoutBps(),
                tierService.refresh(redemption.getOwnerUserId()).tier());

        PartnerCommission c = new PartnerCommission();
        c.setRedemptionId(redemption.getId());
        c.setRewardCodeId(code.getId());
        c.setPartnerUserId(redemption.getOwnerUserId());
        c.setCustomerUserId(customerUserId);
        c.setProviderInvoiceId(invoiceId);
        c.setBaseAmountMinor(baseAmountMinor);
        c.setCurrency(currency == null ? "usd" : currency.toLowerCase(Locale.ROOT));
        c.setPayoutBps(bps);
        c.setCommissionMinor(commissionOf(baseAmountMinor, bps));
        c.setStatus(PartnerCommission.Status.HOLD);
        c.setInvoicePaidAt(when);
        c.setDueAt(when.plus(Math.max(0, code.getHoldDays()), ChronoUnit.DAYS));
        commissionRepository.save(c);
        log.info("Partner commission recorded: partner={} customer={} invoice={} base={} {} share={}",
                c.getPartnerUserId(), customerUserId, invoiceId, baseAmountMinor, c.getCurrency(), c.getCommissionMinor());
        return RecordOutcome.RECORDED;
    }

    /** Share in minor units, rounded down: a partner is never paid a cent that was not earned. */
    static long commissionOf(long baseAmountMinor, int payoutBps) {
        return Math.floorDiv(baseAmountMinor * payoutBps, 10_000L);
    }

    /**
     * Void the line of the invoice a refunded or disputed payment paid. The caller resolves
     * that invoice from the charge's payment intent ({@code invoiceId}). When Stripe CONFIRMED
     * the payment belongs to no invoice ({@code invoiceId} null, {@code lookupFailed} false),
     * nothing is voided: that payment earned no commission. Only when the lookup could not be
     * made ({@code lookupFailed}: no payment intent, Stripe unreachable) is the line matched on
     * the customer, the currency and the charged amount, and ONLY when exactly one unsettled
     * line matches: two identical
     * monthly invoices are ambiguous, and voiding the wrong one would take a commission the
     * partner did earn, so that case logs a WARN for manual reconciliation instead. A refund of
     * an invoice that carries no line (outside the window, before attribution) voids nothing.
     * A line already PAID is left alone and logged. A dispute the platform later WINS does not
     * restore a voided line; that is reconciled by hand.
     */
    @Transactional
    public int voidForRefund(Long customerUserId, String invoiceId, boolean lookupFailed, Long chargedAmountMinor,
                             String chargedCurrency, String reason) {
        if (customerUserId == null) return 0;
        if (invoiceId != null && !invoiceId.isBlank()) {
            Optional<PartnerCommission> line = commissionRepository.findByProviderInvoiceId(invoiceId);
            if (line.isEmpty()) return 0;
            PartnerCommission c = line.get();
            if (c.getStatus() == PartnerCommission.Status.PAID) {
                log.warn("Refund/dispute ({}) of invoice {} after its partner commission was PAID: reconcile by hand",
                        reason, invoiceId);
                return 0;
            }
            if (c.getStatus() != PartnerCommission.Status.HOLD) return 0;
            return voidLine(c, reason);
        }
        if (!lookupFailed) return 0; // Stripe says: no invoice behind this payment, so no line
        List<PartnerCommission> open = commissionRepository
                .findByCustomerUserIdAndStatusOrderByInvoicePaidAtDesc(customerUserId, PartnerCommission.Status.HOLD);
        if (open.isEmpty()) {
            if (!commissionRepository.findByCustomerUserIdAndStatusOrderByInvoicePaidAtDesc(
                    customerUserId, PartnerCommission.Status.PAID).isEmpty()) {
                log.warn("Refund/dispute for customer {} after partner commission was already PAID ({}): reconcile by hand",
                        customerUserId, reason);
            }
            return 0;
        }
        List<PartnerCommission> matches = open.stream()
                .filter(c -> chargedCurrency == null || chargedCurrency.equalsIgnoreCase(c.getCurrency()))
                .filter(c -> chargedAmountMinor != null && chargedAmountMinor > 0
                        && c.getBaseAmountMinor() <= chargedAmountMinor
                        && chargedAmountMinor - c.getBaseAmountMinor() <= taxHeadroom(c.getBaseAmountMinor()))
                .toList();
        if (matches.size() != 1) {
            log.warn("Refund/dispute ({}) of {} {} for customer {} matches {} unsettled partner commissions: reconcile by hand",
                    reason, chargedAmountMinor, chargedCurrency, customerUserId, matches.size());
            return 0;
        }
        return voidLine(matches.get(0), reason);
    }

    private int voidLine(PartnerCommission target, String reason) {
        // Conditional: an admin's mark-paid that committed in between wins, and the PAID record
        // (when, by whom) is never erased by this write.
        if (commissionRepository.voidIfOnHold(target.getId(), Instant.now(), reason) == 0) {
            log.warn("Refund/dispute ({}) of invoice {} lost to a concurrent payout: line already PAID, reconcile by hand",
                    reason, target.getProviderInvoiceId());
            return 0;
        }
        target.setStatus(PartnerCommission.Status.VOID);
        target.setVoidReason(reason);
        log.info("Partner commission voided: id={} customer={} invoice={} reason={}",
                target.getId(), target.getCustomerUserId(), target.getProviderInvoiceId(), reason);
        return 1;
    }

    /**
     * The charge includes tax while the line's base excludes it: accept a charge up to 30%
     * above the base as "the same payment" (covers every VAT rate the product sells under).
     */
    private static long taxHeadroom(long baseAmountMinor) {
        return baseAmountMinor * 3 / 10;
    }
}
