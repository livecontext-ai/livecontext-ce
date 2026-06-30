package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.PendingCreditUpgrade;
import com.apimarketplace.auth.repository.PendingCreditUpgradeRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Invoice;
import com.stripe.net.RequestOptions;
import com.stripe.param.SubscriptionUpdateParams;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Reconciliation job for {@link PendingCreditUpgrade} rows stuck in non-terminal
 * states beyond the synchronous flow's reach.
 *
 * <p>Drives the durability guarantee of the Option A credit-upgrade flow
 * (see {@link StripeBillingService#upgradeCreditTierImmediate}):
 *
 * <ul>
 *   <li><b>PAID_SUB_PENDING</b> &gt; 5 min - the synchronous flow either crashed
 *       or returned with a non-card {@link StripeException} between
 *       {@code invoices.pay()} success and {@code subscriptions.update()}.
 *       Re-poll Stripe; if the invoice is paid, retry the sub-item alignment
 *       via the same Idempotency-Key, then mark COMPLETED. Without this, the
 *       NEXT renewal would bill the OLD tier silently.</li>
 *   <li><b>PENDING_3DS</b> &gt; 24 h - the user never completed Stripe Customer
 *       Action. Stripe will have abandoned the PaymentIntent; mark FAILED and
 *       best-effort void+delete the dangling invoice + item so it cannot ride
 *       a future renewal invoice.</li>
 * </ul>
 *
 * <p>Idempotence: every retry uses a stable {@code Idempotency-Key} derived
 * from the pending row's {@code stripeInvoiceId}. Stripe replays the original
 * response; the ledger's V6 UNIQUE on {@code source_id} absorbs duplicate
 * grants if the {@code invoice.paid} webhook also fires.
 *
 * <p>ShedLock guards against multi-replica concurrent execution: only one
 * scheduler-instance runs the reconcile at a time across the cluster.
 */
@Component
@ConditionalOnProperty(name = "billing.provider", havingValue = "stripe")
public class PendingCreditUpgradeReconciler {

    private static final Logger log = LoggerFactory.getLogger(PendingCreditUpgradeReconciler.class);

    /** Don't touch rows younger than this - synchronous flow may still be in-flight. */
    private static final Duration MIN_AGE_FOR_RECONCILE = Duration.ofMinutes(5);

    /** PENDING_3DS rows older than this are abandoned by the user. */
    private static final Duration MAX_3DS_AGE = Duration.ofHours(24);

    /** PAID_SUB_PENDING rows older than this are escalated to manual review. */
    private static final Duration MAX_PAID_SUB_PENDING_AGE = Duration.ofDays(7);

    /** Cap per-tick Stripe API traffic during incidents. */
    private static final int RECONCILE_BATCH_SIZE = 100;

    private final StripeClient stripe;
    private final PendingCreditUpgradeRepository pendingRepo;
    private final SubscriptionRepository subscriptionRepository;
    private final PriceCacheService priceCacheService;
    private final CreditAttributionService creditAttributionService;

    public PendingCreditUpgradeReconciler(StripeClient stripe,
                                           PendingCreditUpgradeRepository pendingRepo,
                                           SubscriptionRepository subscriptionRepository,
                                           PriceCacheService priceCacheService,
                                           CreditAttributionService creditAttributionService) {
        this.stripe = stripe;
        this.pendingRepo = pendingRepo;
        this.subscriptionRepository = subscriptionRepository;
        this.priceCacheService = priceCacheService;
        this.creditAttributionService = creditAttributionService;
    }

    @Scheduled(fixedDelayString = "PT5M")
    @SchedulerLock(name = "pending_credit_upgrade_reconcile", lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    public void reconcile() {
        try {
            reconcilePaidSubPending();
        } catch (Exception e) {
            log.error("Reconcile PAID_SUB_PENDING sweep failed: {}", e.getMessage(), e);
        }
        try {
            reconcilePending3DS();
        } catch (Exception e) {
            log.error("Reconcile PENDING_3DS sweep failed: {}", e.getMessage(), e);
        }
    }

    private void reconcilePaidSubPending() {
        LocalDateTime cutoff = LocalDateTime.now().minus(MIN_AGE_FOR_RECONCILE);
        List<PendingCreditUpgrade> rows = pendingRepo
                .findByStatusOrderByCreatedAtAsc(
                        PendingCreditUpgrade.STATUS_PAID_SUB_PENDING,
                        org.springframework.data.domain.PageRequest.of(0, RECONCILE_BATCH_SIZE));

        for (PendingCreditUpgrade pending : rows) {
            if (pending.getCreatedAt() == null || pending.getCreatedAt().isAfter(cutoff)) {
                continue; // too young - let the sync flow complete
            }
            try {
                Invoice invoice = stripe.invoices().retrieve(pending.getStripeInvoiceId());
                if (!"paid".equalsIgnoreCase(invoice.getStatus())) {
                    // Invoice not paid yet - leave for next tick. If too old, escalate.
                    if (pending.getCreatedAt().isBefore(LocalDateTime.now().minus(MAX_PAID_SUB_PENDING_AGE))) {
                        log.error("Pending credit upgrade {} stuck in PAID_SUB_PENDING > 7d (invoice status={}). " +
                                "Marking FAILED for manual review.",
                                pending.getId(), invoice.getStatus());
                        // Cleanup the dangling Stripe artifacts so they cannot ride a
                        // future renewal invoice. Both calls are best-effort.
                        voidQuietly(invoice.getId());
                        if (hasRealInvoiceItemId(pending)) {
                            deleteItemQuietly(pending.getStripeInvoiceItemId());
                        }
                        markFailed(pending, "stuck_paid_sub_pending_7d:invoice_status=" + invoice.getStatus());
                    }
                    continue;
                }
                // Invoice IS paid → retry sub-item alignment, then grant ledger if not yet.
                alignSubItemIdempotent(pending);
                creditAttributionService.handleCreditUpgradeInvoicePaid(pending);
                markCompleted(pending);
                log.info("Reconciled PAID_SUB_PENDING upgrade: id={}, invoice={}, sub={}",
                        pending.getId(), pending.getStripeInvoiceId(), pending.getProviderSubscriptionId());
            } catch (Exception e) {
                log.warn("Reconcile attempt failed for pending upgrade {} (invoice={}): {}",
                        pending.getId(), pending.getStripeInvoiceId(), e.getMessage());
                // Stay in PAID_SUB_PENDING; next tick will retry.
            }
        }
    }

    private void reconcilePending3DS() {
        LocalDateTime cutoff = LocalDateTime.now().minus(MAX_3DS_AGE);
        List<PendingCreditUpgrade> rows = pendingRepo
                .findByStatusOrderByCreatedAtAsc(
                        PendingCreditUpgrade.STATUS_PENDING_3DS,
                        org.springframework.data.domain.PageRequest.of(0, RECONCILE_BATCH_SIZE));

        for (PendingCreditUpgrade pending : rows) {
            if (pending.getCreatedAt() == null || pending.getCreatedAt().isAfter(cutoff)) {
                continue; // user may still complete 3DS
            }
            try {
                // Last check: maybe the user just completed 3DS and the invoice.paid
                // webhook is in-flight - verify with Stripe before voiding.
                Invoice invoice = stripe.invoices().retrieve(pending.getStripeInvoiceId());
                if ("paid".equalsIgnoreCase(invoice.getStatus())) {
                    // Race: the webhook hasn't landed but the invoice is paid. Drive completion.
                    alignSubItemIdempotent(pending);
                    creditAttributionService.handleCreditUpgradeInvoicePaid(pending);
                    markCompleted(pending);
                    log.info("Reconciled PENDING_3DS that turned out paid: id={}, invoice={}",
                            pending.getId(), pending.getStripeInvoiceId());
                    continue;
                }
                // Truly abandoned - void + delete + mark FAILED.
                voidQuietly(invoice.getId());
                // Skip item delete when we only have the metadata-fallback placeholder
                // (synthesised in WebhookController.reconstructPendingFromMetadata when
                // the synchronous DB save was rolled back). The real item id is unknown;
                // attempting delete would 404 on Stripe and pollute logs every tick.
                if (hasRealInvoiceItemId(pending)) {
                    deleteItemQuietly(pending.getStripeInvoiceItemId());
                } else {
                    log.warn("Skipping invoice-item delete for pending {} - synthetic item id ({})." +
                            " Manual cleanup may be required for invoice {}.",
                            pending.getId(), pending.getStripeInvoiceItemId(), pending.getStripeInvoiceId());
                }
                markFailed(pending, "3ds_abandoned_24h");
                log.info("Marked PENDING_3DS as FAILED after 24h: id={}, invoice={}",
                        pending.getId(), pending.getStripeInvoiceId());
            } catch (Exception e) {
                log.warn("Reconcile 3DS attempt failed for pending upgrade {}: {}",
                        pending.getId(), e.getMessage());
            }
        }
    }

    // No @Transactional: Stripe call + 2 independent saves. Each Spring Data
    // save() opens its own transaction. Self-invocation from reconcile() would
    // bypass any class-level @Transactional anyway (proxy limitation).
    public void alignSubItemIdempotent(PendingCreditUpgrade pending) throws StripeException {
        var stripeSub = stripe.subscriptions().retrieve(pending.getProviderSubscriptionId());
        String creditItemId = null;
        for (var item : stripeSub.getItems().getData()) {
            if (priceCacheService.isCreditPackPrice(item.getPrice().getId())) {
                creditItemId = item.getId();
                break;
            }
        }

        var itemBuilder = SubscriptionUpdateParams.Item.builder()
                .setPrice(pending.getTargetCreditPriceId())
                .setQuantity((long) pending.getTargetCreditQuantity());
        if (creditItemId != null) itemBuilder.setId(creditItemId);

        SubscriptionUpdateParams params = SubscriptionUpdateParams.builder()
                .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.NONE)
                .addItem(itemBuilder.build())
                .build();

        String idemKey = "upgrade-align:" + pending.getStripeInvoiceId() + ":sub";
        RequestOptions opts = RequestOptions.builder().setIdempotencyKey(idemKey).build();
        stripe.subscriptions().update(pending.getProviderSubscriptionId(), params, opts);

        // Mirror locally so the next customer.subscription.updated webhook sees
        // old == new and skips the legacy handleCreditPackChange path.
        subscriptionRepository.findById(pending.getSubscriptionId()).ifPresent(sub -> {
            sub.setCreditQuantity(pending.getTargetCreditQuantity());
            sub.setUpdatedAt(LocalDateTime.now());
            subscriptionRepository.save(sub);
        });
    }

    public void markCompleted(PendingCreditUpgrade pending) {
        if (PendingCreditUpgrade.STATUS_COMPLETED.equals(pending.getStatus())) return;
        pending.setStatus(PendingCreditUpgrade.STATUS_COMPLETED);
        pending.setCompletedAt(LocalDateTime.now());
        pendingRepo.save(pending);
    }

    public void markFailed(PendingCreditUpgrade pending, String reason) {
        pending.setStatus(PendingCreditUpgrade.STATUS_FAILED);
        pending.setErrorMessage(reason);
        pendingRepo.save(pending);
    }

    /**
     * Returns true when the stored {@code stripe_invoice_item_id} is a real
     * Stripe id (not the {@code metadata-fallback:} placeholder written by
     * {@code WebhookController.reconstructPendingFromMetadata} when the
     * synchronous flow's DB row was rolled back).
     */
    private static boolean hasRealInvoiceItemId(PendingCreditUpgrade pending) {
        String id = pending.getStripeInvoiceItemId();
        return id != null && !id.isBlank() && !id.startsWith("metadata-fallback:");
    }

    private void voidQuietly(String invoiceId) {
        try {
            stripe.invoices().voidInvoice(invoiceId);
            log.info("Voided invoice {} during 3DS abandon cleanup", invoiceId);
        } catch (Exception e) {
            log.warn("Could not void invoice {}: {}", invoiceId, e.getMessage());
        }
    }

    private void deleteItemQuietly(String invoiceItemId) {
        try {
            stripe.invoiceItems().delete(invoiceItemId);
            log.info("Deleted invoice item {} during 3DS abandon cleanup", invoiceItemId);
        } catch (Exception e) {
            log.warn("Could not delete invoice item {}: {}", invoiceItemId, e.getMessage());
        }
    }
}
