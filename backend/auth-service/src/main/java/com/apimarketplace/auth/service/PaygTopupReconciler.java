package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.BillingEvent;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.BillingEventRepository;
import com.apimarketplace.auth.util.NonceUtil;
import com.fasterxml.jackson.databind.JsonNode;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Re-grants PAYG top-ups that were paid but never credited.
 *
 * <p>A PAYG top-up is a Stripe {@code mode=payment} checkout: the ONLY event that credits it is
 * {@code checkout.session.completed}, nothing follows. The webhook stores the event first and
 * grants second, so anything that breaks the grant after the store (an unreadable nonce when
 * the AES key differed between replicas, a crash, a DB hiccup) leaves a charged card and no
 * credits, with no retry from Stripe: the event id is already recorded, so a Stripe redelivery
 * is deduplicated away. Production lost two top-ups that way in 2026-09.
 *
 * <p>This job closes the gap from the stored events: every paid {@code payg_topup} session with
 * no {@code credit_ledger.source_id} is attributed again (nonce first, then the Stripe customer)
 * and granted through the same {@link CreditAttributionService#grantPaygTopup} the webhook uses,
 * so the ledger row, the PAYG bucket and the idempotence key (the session id) are identical to a
 * webhook grant. Events younger than {@link #MIN_AGE} are left to the live webhook.
 *
 * <p>ShedLock keeps a single pass running across replicas.
 */
@Component
@ConditionalOnProperty(name = "billing.provider", havingValue = "stripe")
public class PaygTopupReconciler {

    private static final Logger log = LoggerFactory.getLogger(PaygTopupReconciler.class);

    /** Never race the live webhook: it stores the event, then grants within the same request. */
    static final Duration MIN_AGE = Duration.ofMinutes(2);
    /** Cap per pass; the next pass continues where this one stopped (oldest first). */
    static final int BATCH_SIZE = 100;

    private final BillingEventRepository billingEventRepository;
    private final BillingCustomerRepository billingCustomerRepository;
    private final CreditAttributionService creditAttributionService;
    private final NonceUtil nonceUtil;
    private final ObjectProvider<SubscriptionCacheBuster> cacheBuster;
    /**
     * One transaction PER event (REQUIRES_NEW): a failed recovery rolls back only itself, and
     * the cache fan-out walks lazy org/member relations, which needs an open session (the
     * first prod pass granted fine but logged a LazyInitializationException on the fan-out).
     */
    private final TransactionTemplate perEventTransaction;

    public PaygTopupReconciler(BillingEventRepository billingEventRepository,
                               BillingCustomerRepository billingCustomerRepository,
                               CreditAttributionService creditAttributionService,
                               NonceUtil nonceUtil,
                               ObjectProvider<SubscriptionCacheBuster> cacheBuster,
                               PlatformTransactionManager transactionManager) {
        this.billingEventRepository = billingEventRepository;
        this.billingCustomerRepository = billingCustomerRepository;
        this.creditAttributionService = creditAttributionService;
        this.nonceUtil = nonceUtil;
        this.cacheBuster = cacheBuster;
        this.perEventTransaction = new TransactionTemplate(transactionManager);
        this.perEventTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(initialDelayString = "${billing.payg-topup.reconcile.initial-delay:PT2M}",
               fixedDelayString = "${billing.payg-topup.reconcile.delay:PT15M}")
    @SchedulerLock(name = "payg_topup_reconcile", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void reconcile() {
        try {
            reconcileOnce();
        } catch (Exception e) {
            log.error("PAYG top-up reconcile pass failed: {}", e.getMessage(), e);
        }
    }

    /**
     * One pass. Returns the number of top-ups granted so a caller (test, ops) can assert it.
     */
    public int reconcileOnce() {
        LocalDateTime cutoff = LocalDateTime.now().minus(MIN_AGE);
        List<BillingEvent> events = billingEventRepository.findPaidPaygTopupsWithoutLedger(cutoff, BATCH_SIZE);
        if (events.isEmpty()) {
            return 0;
        }
        int granted = 0;
        for (BillingEvent event : events) {
            try {
                if (Boolean.TRUE.equals(perEventTransaction.execute(status -> recover(event)))) {
                    granted++;
                }
            } catch (Exception e) {
                log.error("PAYG top-up recovery failed for event {}: {}", event.getEventId(), e.getMessage(), e);
            }
        }
        log.warn("PAYG top-up reconcile: {} paid session(s) had no ledger entry, {} granted in this pass",
                events.size(), granted);
        return granted;
    }

    private boolean recover(BillingEvent event) {
        JsonNode object = event.getPayload().path("data").path("object");
        String sessionId = object.path("id").asText(null);
        String customerId = object.path("customer").asText(null);
        String nonce = object.path("client_reference_id").asText(null);
        JsonNode metadata = object.path("metadata");
        String tier = metadata.path("tier").asText(null);
        String creditAmountStr = metadata.path("credit_amount").asText(null);

        if (sessionId == null || sessionId.isBlank()) {
            log.warn("PAYG top-up event {} has no session id, skipping", event.getEventId());
            return false;
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(creditAmountStr);
        } catch (NumberFormatException | NullPointerException e) {
            log.warn("PAYG top-up session {} (event {}) has unusable credit_amount metadata '{}', skipping",
                    sessionId, event.getEventId(), creditAmountStr);
            return false;
        }
        if (amount.signum() <= 0) {
            log.warn("PAYG top-up session {} (event {}) has non-positive credit_amount {}, skipping",
                    sessionId, event.getEventId(), amount);
            return false;
        }

        Long userId = resolveUser(nonce, customerId);
        if (userId == null) {
            log.error("PAYG top-up session {} (event {}) cannot be attributed: nonce unreadable and Stripe " +
                    "customer {} unknown locally. Needs a manual grant.", sessionId, event.getEventId(), customerId);
            return false;
        }

        creditAttributionService.grantPaygTopup(userId, amount, sessionId, tier);
        SubscriptionCacheBuster buster = cacheBuster.getIfAvailable();
        if (buster != null) {
            buster.fanOutForOwner(userId, "reconcile.payg_topup");
        }
        log.warn("PAYG top-up recovered: event={}, session={}, user={}, amount={}, tier={}",
                event.getEventId(), sessionId, userId, amount, tier);
        return true;
    }

    private Long resolveUser(String nonce, String customerId) {
        Long viaNonce = (nonce != null && !nonce.isBlank()) ? nonceUtil.decodeNonce(nonce) : null;
        if (viaNonce != null) {
            return viaNonce;
        }
        if (customerId == null || customerId.isBlank()) {
            return null;
        }
        return billingCustomerRepository.findByProviderCustomerId(customerId)
                .map(bc -> bc.getUser().getId())
                .orElse(null);
    }
}
