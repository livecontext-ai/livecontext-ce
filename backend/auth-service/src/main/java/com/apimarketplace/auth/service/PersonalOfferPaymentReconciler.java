package com.apimarketplace.auth.service;

import com.apimarketplace.auth.repository.PersonalOfferCheckoutAttemptRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.data.domain.PageRequest;
import java.time.Instant;

/** Backstop for an invoice event recorded by BillingEvent before dispatch failed. */
@Component
@ConditionalOnProperty(name = "billing.provider", havingValue = "stripe")
public class PersonalOfferPaymentReconciler {
    private final PersonalOfferCheckoutAttemptRepository attempts;
    private final PersonalOfferPaymentService payments;

    public PersonalOfferPaymentReconciler(PersonalOfferCheckoutAttemptRepository attempts,
                                          PersonalOfferPaymentService payments) {
        this.attempts = attempts;
        this.payments = payments;
    }

    @Scheduled(fixedDelayString = "${reward.personal-offer.reconcile-ms:900000}")
    public void reconcile() {
        for (var attempt : attempts.findDueForReconcile(Instant.now(), PageRequest.of(0, 100))) {
            payments.reconcileAttempt(attempt);
        }
    }
}
