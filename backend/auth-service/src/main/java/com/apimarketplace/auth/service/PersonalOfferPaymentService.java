package com.apimarketplace.auth.service;

import com.apimarketplace.auth.billing.CreditTierConstants;
import com.apimarketplace.auth.domain.*;
import com.apimarketplace.auth.repository.*;
import com.stripe.StripeClient;
import com.stripe.model.Invoice;
import com.stripe.model.checkout.Session;
import com.stripe.param.InvoiceListParams;
import com.stripe.param.checkout.SessionListParams;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Verified invoice to one-shot PAYG reward bridge. Replays are keyed by invoice and ledger source. */
@Service
@ConditionalOnProperty(name = "billing.provider", havingValue = "stripe")
public class PersonalOfferPaymentService {
    private static final Logger log = LoggerFactory.getLogger(PersonalOfferPaymentService.class);
    private final PersonalOfferCheckoutAttemptRepository attempts;
    private final PersonalOfferFirstPaidPurchaseRepository firstPaid;
    private final PersonalOfferReversedInvoiceRepository reversals;
    private final RewardCodeRepository codes;
    private final RewardRedemptionRepository redemptions;
    private final BillingCustomerRepository customers;
    private final SubscriptionRepository subscriptions;
    private final UserRepository users;
    private final RewardService rewards;
    private final PriceCacheService prices;
    private final StripeClient stripe;
    private final TransactionTemplate transactions;
    @PersistenceContext
    private EntityManager entityManager;

    public PersonalOfferPaymentService(PersonalOfferCheckoutAttemptRepository attempts,
                                       PersonalOfferFirstPaidPurchaseRepository firstPaid,
                                       PersonalOfferReversedInvoiceRepository reversals,
                                       RewardCodeRepository codes,
                                       RewardRedemptionRepository redemptions,
                                       BillingCustomerRepository customers,
                                       SubscriptionRepository subscriptions,
                                       UserRepository users,
                                       RewardService rewards,
                                       PriceCacheService prices,
                                       StripeClient stripe,
                                       PlatformTransactionManager transactionManager) {
        this.attempts = attempts;
        this.firstPaid = firstPaid;
        this.reversals = reversals;
        this.codes = codes;
        this.redemptions = redemptions;
        this.customers = customers;
        this.subscriptions = subscriptions;
        this.users = users;
        this.rewards = rewards;
        this.prices = prices;
        this.stripe = stripe;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Called for every positive Stripe subscription invoice, including users without an offer. */
    @Transactional
    public void onPaidInvoice(Invoice invoice, String subscriptionId) {
        if (invoice == null || subscriptionId == null || !"paid".equalsIgnoreCase(invoice.getStatus())
                || invoice.getAmountPaid() == null) return;
        if (invoice.getAmountPaid() <= 0) {
            recordUnexpectedZeroFirstInvoice(invoice, subscriptionId);
            return;
        }
        if (invoice.getBillingReason() == null || !invoice.getBillingReason().startsWith("subscription_")) return;
        lockInvoice(invoice.getId());
        BillingCustomer customer = customers.findByProviderCustomerId(invoice.getCustomer()).orElse(null);
        if (customer == null) return;
        Long userId = customer.getUser().getId();
        users.lockForPersonalOffer(userId).orElseThrow();
        PersonalOfferCheckoutAttempt attempt = findAttempt(subscriptionId);
        PersonalOfferFirstPaidPurchase purchase = firstPaid.findById(userId).orElse(null);
        Instant paidAt = invoice.getStatusTransitions() != null && invoice.getStatusTransitions().getPaidAt() != null
                ? Instant.ofEpochSecond(invoice.getStatusTransitions().getPaidAt()) : Instant.now();
        if (purchase != null && "PAID".equals(purchase.getStatus())
                && !Objects.equals(purchase.getInvoiceId(), invoice.getId())
                && (purchase.getPaidAt() == null || !paidAt.isBefore(purchase.getPaidAt()))) return;

        // A later paid checkout can reach us before the first one's webhook. Stripe's
        // invoice timestamps, not delivery order, decide the first subscription payment.
        boolean earliest = attempt == null || isEarliestSubscriptionPayment(customer.getProviderCustomerId(), invoice);
        if (purchase == null) {
            purchase = new PersonalOfferFirstPaidPurchase();
            purchase.setUserId(userId);
        }
        purchase.setStatus("PAID");
        purchase.setInvoiceId(invoice.getId());
        purchase.setProviderSubscriptionId(subscriptionId);
        purchase.setPaidAt(paidAt);
        purchase.setVerifiedAt(Instant.now());
        firstPaid.saveAndFlush(purchase);
        // A refund may arrive before this webhook, including while an asynchronous
        // checkout is settling. It consumes first-purchase eligibility but never grants.
        if (reversals.existsById(invoice.getId())) return;
        if (attempt == null) return;
        if (!earliest) {
            log.warn("Personal offer invoice {} is not the customer's first paid subscription invoice", invoice.getId());
            return;
        }

        if (!Objects.equals(attempt.getRecipientUserId(), userId)
                || !"subscription_create".equals(invoice.getBillingReason())
                || !SetOfPayable.contains(attempt.getStatus())
                || !invoiceMatchesSelection(invoice, attempt)) {
            log.error("Paid personal offer invoice {} failed checkout selection verification", invoice.getId());
            return;
        }
        attempt.setStripeSubscriptionId(subscriptionId);
        attempt.setStripeInvoiceId(invoice.getId());
        attempt.setStatus(attempt.getBonusCredits() == 0 ? "NO_BONUS" : "PAID");
        attempts.save(attempt);
        if (attempt.getBonusCredits() == 0) return;
        RewardCode code = codes.findById(attempt.getRewardCodeId()).orElse(null);
        if (code == null || !Objects.equals(code.getRecipientUserId(), userId)) return;
        if (grantIfProvisioned(code, attempt, invoice.getId(), subscriptionId, paidAt)) {
            attempt.setStatus("GRANTED");
            attempts.save(attempt);
        }
    }

    private static final java.util.Set<String> SetOfPayable = java.util.Set.of("CREATING", "OPEN", "COMPLETED", "PAID");

    private void recordUnexpectedZeroFirstInvoice(Invoice invoice, String subscriptionId) {
        if (!"subscription_create".equals(invoice.getBillingReason())) return;
        lockInvoice(invoice.getId());
        BillingCustomer customer = customers.findByProviderCustomerId(invoice.getCustomer()).orElse(null);
        if (customer == null) return;
        Long userId = customer.getUser().getId();
        users.lockForPersonalOffer(userId).orElseThrow();
        PersonalOfferCheckoutAttempt attempt = findAttempt(subscriptionId);
        if (attempt == null || !Objects.equals(attempt.getRecipientUserId(), userId)
                || !SetOfPayable.contains(attempt.getStatus())) return;
        attempt.setStripeSubscriptionId(subscriptionId);
        attempt.setStripeInvoiceId(invoice.getId());
        attempt.setStatus("REVIEW_REQUIRED");
        attempts.saveAndFlush(attempt);
        log.error("Zero first invoice requires manual review for personal offer attempt {}", attempt.getId());
    }

    private PersonalOfferCheckoutAttempt findAttempt(String subscriptionId) {
        Optional<PersonalOfferCheckoutAttempt> direct = attempts.findByStripeSubscriptionId(subscriptionId);
        if (direct.isPresent()) return attempts.lockById(direct.get().getId()).orElse(null);
        try {
            String attemptId = stripe.subscriptions().retrieve(subscriptionId).getMetadata().get("personal_offer_attempt_id");
            if (attemptId == null) return null;
            return attempts.lockById(UUID.fromString(attemptId)).orElse(null);
        } catch (Exception inaccessible) {
            log.warn("Could not resolve personal offer attempt for subscription {}", subscriptionId);
            return null;
        }
    }

    private boolean invoiceMatchesSelection(Invoice invoice, PersonalOfferCheckoutAttempt attempt) {
        String expectedPlanPrice = attempt.getPlanPriceId();
        String expectedCreditPrice = attempt.getCreditPriceId();
        if (expectedPlanPrice == null || invoice.getLines() == null || invoice.getLines().getData() == null) return false;
        boolean planFound = false;
        long creditQuantity = 0;
        for (var line : invoice.getLines().getData()) {
            if (line.getPricing() == null || line.getPricing().getPriceDetails() == null) continue;
            String priceId = line.getPricing().getPriceDetails().getPrice();
            if (expectedPlanPrice.equals(priceId) && line.getQuantity() != null && line.getQuantity() == 1) planFound = true;
            if (expectedCreditPrice != null && expectedCreditPrice.equals(priceId) && line.getQuantity() != null)
                creditQuantity += line.getQuantity();
        }
        return planFound && creditQuantity == CreditTierConstants.getCreditCost(attempt.getCreditTierIndex(),
                attempt.getPlanCode());
    }

    private boolean isEarliestSubscriptionPayment(String customerId, Invoice candidate) {
        try {
            var params = InvoiceListParams.builder().setCustomer(customerId).setLimit(100L).build();
            long candidateAt = candidate.getStatusTransitions() != null && candidate.getStatusTransitions().getPaidAt() != null
                    ? candidate.getStatusTransitions().getPaidAt() : Long.MAX_VALUE;
            for (Invoice other : stripe.invoices().list(params).autoPagingIterable()) {
                if (Objects.equals(other.getId(), candidate.getId()) || !"paid".equalsIgnoreCase(other.getStatus())
                        || other.getAmountPaid() == null || other.getAmountPaid() <= 0) continue;
                String reason = other.getBillingReason();
                if (reason == null) return false;
                if (!reason.startsWith("subscription_")) continue;
                long paidAt = other.getStatusTransitions() != null && other.getStatusTransitions().getPaidAt() != null
                        ? other.getStatusTransitions().getPaidAt() : Long.MIN_VALUE;
                if (paidAt <= candidateAt) return false;
            }
            return candidateAt != Long.MAX_VALUE;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private boolean grantIfProvisioned(RewardCode code, PersonalOfferCheckoutAttempt attempt,
                                    String invoiceId, String subscriptionId, Instant paidAt) {
        Subscription subscription = subscriptions.findByProviderSubscriptionId(subscriptionId).orElse(null);
        if (subscription == null || !"active".equalsIgnoreCase(subscription.getStatus())
                || !Objects.equals(subscription.getBillingCustomer().getUser().getId(), attempt.getRecipientUserId())) {
            return false; // Reconciler retries once subscription provisioning finishes.
        }
        RewardRedemption grant = rewards.qualifyPersonalPaid(code, attempt.getId(), subscriptionId,
                invoiceId, attempt.getBonusCredits(), paidAt);
        if (grant.getStatus() == RewardStatus.QUALIFIED) rewards.releaseOne(grant.getId());
        return grant.getStatus() == RewardStatus.RELEASED ||
                redemptions.findById(grant.getId()).map(r -> r.getStatus() == RewardStatus.RELEASED).orElse(false);
    }

    @Transactional
    public void onInvoiceReversed(String invoiceId, String reason) {
        if (invoiceId == null || invoiceId.isBlank()) return;
        lockInvoice(invoiceId);
        reversals.recordIfAbsent(invoiceId, reason);
        rewards.clawbackPersonalByInvoice(invoiceId, reason);
    }

    private void lockInvoice(String invoiceId) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:key))")
                .setParameter("key", "personal-offer-invoice:" + invoiceId)
                .getSingleResult();
    }

    /** Provider calls are outside SQL transactions; invoice and attempt locks never reverse order. */
    public void reconcileAttempt(PersonalOfferCheckoutAttempt attempt) {
        if (attempt == null) return;
        try {
            Session session = attempt.getStripeSessionId() == null
                    ? recoverCreatedSession(attempt)
                    : stripe.checkout().sessions().retrieve(attempt.getStripeSessionId());
            if (session == null) {
                if ("CREATING".equals(attempt.getStatus()) && attempt.getSessionExpiresAt().isBefore(Instant.now()))
                    log.error("Personal offer attempt {} has an unresolved Stripe create outcome", attempt.getId());
                return;
            }
            transactions.executeWithoutResult(status -> recordSessionState(attempt.getId(), session));
            if (session.getSubscription() == null) return;
            var params = InvoiceListParams.builder().setSubscription(session.getSubscription()).setLimit(20L).build();
            for (Invoice invoice : stripe.invoices().list(params).autoPagingIterable()) {
                if ("subscription_create".equals(invoice.getBillingReason()) && "paid".equals(invoice.getStatus())) {
                    transactions.executeWithoutResult(status -> onPaidInvoice(invoice, session.getSubscription()));
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("Personal offer attempt {} reconciliation deferred: {}", attempt.getId(), e.toString());
        } finally {
            transactions.executeWithoutResult(status -> attempts.scheduleNextReconcile(
                    attempt.getId(), Instant.now().plusSeconds(900)));
        }
    }

    private void recordSessionState(java.util.UUID attemptId, Session session) {
        PersonalOfferCheckoutAttempt attempt = attempts.lockById(attemptId).orElse(null);
        if (attempt == null || !java.util.Set.of("CREATING", "OPEN", "COMPLETED", "PAID")
                .contains(attempt.getStatus())) return;
        if (attempt.getStripeSessionId() != null && !attempt.getStripeSessionId().equals(session.getId())) return;
        attempt.setStripeSessionId(session.getId());
        attempt.setSessionUrl(session.getUrl());
        if (session.getExpiresAt() != null)
            attempt.setSessionExpiresAt(Instant.ofEpochSecond(session.getExpiresAt()));
        if (session.getSubscription() != null) attempt.setStripeSubscriptionId(session.getSubscription());
        if ("expired".equals(session.getStatus()) && session.getSubscription() == null)
            attempt.setStatus("EXPIRED");
        else if ("complete".equals(session.getStatus())
                && java.util.Set.of("CREATING", "OPEN").contains(attempt.getStatus()))
            attempt.setStatus("COMPLETED");
        else if ("CREATING".equals(attempt.getStatus())) attempt.setStatus("OPEN");
        attempts.save(attempt);
    }

    private Session recoverCreatedSession(PersonalOfferCheckoutAttempt attempt) throws Exception {
        if (attempt.getStripeCustomerId() == null || attempt.getCreatedAt() == null) return null;
        var params = SessionListParams.builder()
                .setCustomer(attempt.getStripeCustomerId())
                .setCreated(SessionListParams.Created.builder()
                        .setGte(attempt.getCreatedAt().minusSeconds(300).getEpochSecond()).build())
                .setLimit(100L).build();
        int scanned = 0;
        for (Session session : stripe.checkout().sessions().list(params).autoPagingIterable()) {
            if (++scanned > 500) break;
            if (attempt.getId().toString().equals(session.getMetadata() == null
                    ? null : session.getMetadata().get("personal_offer_attempt_id"))) return session;
        }
        return null;
    }
}
