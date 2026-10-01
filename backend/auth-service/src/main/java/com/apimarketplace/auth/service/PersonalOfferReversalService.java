package com.apimarketplace.auth.service;

import com.apimarketplace.auth.billing.StripeInvoiceResolver;
import com.apimarketplace.auth.repository.PersonalOfferReversalRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.stripe.StripeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/** Captures reversal intent before webhook deduplication, independently of Stripe availability. */
@Service
@ConditionalOnProperty(name = "billing.provider", havingValue = "stripe")
public class PersonalOfferReversalService {
    private static final Logger log = LoggerFactory.getLogger(PersonalOfferReversalService.class);
    private final PersonalOfferReversalRepository tasks;
    private final PersonalOfferPaymentService payments;
    private final StripeClient stripe;

    public PersonalOfferReversalService(PersonalOfferReversalRepository tasks,
                                       PersonalOfferPaymentService payments, StripeClient stripe) {
        this.tasks = tasks;
        this.payments = payments;
        this.stripe = stripe;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void capture(String eventType, JsonNode object) {
        if ("charge.refunded".equals(eventType)) {
            long amount = object.path("amount").asLong(0);
            if (amount <= 0 || object.path("amount_refunded").asLong(0) < amount) return;
            enqueue(object.path("id").asText(null), "REFUNDED");
        } else if ("charge.dispute.created".equals(eventType)) {
            JsonNode charge = object.path("charge");
            enqueue(charge.isTextual() ? charge.asText() : charge.path("id").asText(null), "DISPUTED");
        }
    }

    private void enqueue(String charge, String reason) {
        if (charge == null || charge.isBlank()) throw new IllegalArgumentException("Reversal charge ID required");
        tasks.enqueue(charge, reason);
    }

    @Scheduled(initialDelayString = "${reward.personal-offer.reversal-initial-delay-ms:120000}",
            fixedDelayString = "${reward.personal-offer.reconcile-ms:900000}")
    public void reconcile() {
        for (var task : tasks.due(Instant.now())) process(task);
    }

    public void reconcileCharge(String chargeId) {
        if (chargeId != null) tasks.pending(chargeId).ifPresent(this::process);
    }

    private void process(PersonalOfferReversalRepository.Task task) {
        try {
            var charge = stripe.charges().retrieve(task.chargeId());
            var invoice = StripeInvoiceResolver.resolve(stripe, charge);
            if (invoice.failed()) {
                tasks.defer(task, Instant.now().plusSeconds(900));
                return;
            }
            if (invoice.invoiceId() != null) payments.onInvoiceReversed(invoice.invoiceId(), task.reason());
            tasks.complete(task, invoice.invoiceId());
        } catch (Exception unavailable) {
            tasks.defer(task, Instant.now().plusSeconds(900));
            log.warn("Personal offer reversal for charge {} deferred: {}", task.chargeId(), unavailable.toString());
        }
    }
}
