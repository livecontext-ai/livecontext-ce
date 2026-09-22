package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Creates the bootstrap FREE subscription for a user, exactly once.
 *
 * <p><b>Why this is its own bean.</b> The provisioning must be one transaction that holds a
 * lock across the "does one already exist?" check and the insert. It used to live as a private
 * method inside {@link UserResolutionService}, called from {@code resolveUser} - and there the
 * {@code @Transactional} was inert twice over: Spring cannot proxy a private method, and even a
 * public one would have been self-invoked from within the same bean. So the check and the insert
 * ran as two independent transactions with nothing between them.
 *
 * <p><b>What that cost.</b> {@code resolveUser} runs on EVERY gateway request. On a first login
 * the gateway's user cache is cold, so the handful of calls the app fires while booting all
 * resolve the same user at once, each sees no subscription, and each inserts one. Production has
 * three users carrying two active FREE subscriptions created 7-15 ms apart (ids 18/19, 32/33,
 * 38/39). Only one of each pair ever received its {@code _init} grant; the twin is a permanent
 * zero-balance ghost. It gets worse downstream: the credit paths resolve the wallet by USER
 * (most-recent active row) while the renewal iterates rows, so a grant keyed to one row lands on
 * the other, and the sibling's reset then wipes it.
 *
 * <p><b>The mutex.</b> There is exactly one {@code billing_customer} per user (unique index on
 * {@code user_id}), so locking that row serialises provisioning per user - and does so in the
 * DATABASE, which is what makes it work across the multiple auth replicas the race actually
 * spans. A partial unique index on active subscriptions backs this up as defence in depth.
 */
@Service
public class FreeSubscriptionProvisioner {

    private static final Logger log = LoggerFactory.getLogger(FreeSubscriptionProvisioner.class);

    private final SubscriptionRepository subscriptionRepository;
    private final BillingCustomerRepository billingCustomerRepository;
    private final PlanRepository planRepository;
    private final PlanStorageQuotaSyncer quotaSyncer;

    public FreeSubscriptionProvisioner(SubscriptionRepository subscriptionRepository,
                                       BillingCustomerRepository billingCustomerRepository,
                                       PlanRepository planRepository,
                                       PlanStorageQuotaSyncer quotaSyncer) {
        this.subscriptionRepository = subscriptionRepository;
        this.billingCustomerRepository = billingCustomerRepository;
        this.planRepository = planRepository;
        this.quotaSyncer = quotaSyncer;
    }

    /**
     * Give {@code user} a FREE subscription if they do not already have an active one.
     *
     * <p>Credits are attributed separately by
     * {@link UserResolutionService#attributeCreditsIfEligible} once the email is verified.
     *
     * <p><b>Failures propagate.</b> This method deliberately catches nothing. A transaction
     * cannot recover from its own failure: once a statement has raised, PostgreSQL has aborted
     * the transaction and Spring has flagged it rollback-only, so a catch here would return a
     * tidy value and then be overruled by an {@code UnexpectedRollbackException} thrown at the
     * commit, outside this method, where no catch of ours can reach it. That is not a
     * hypothetical: it is how a swallowed duplicate-key ended up failing users' logins. Only the
     * caller, which is outside this transaction, is in a position to decide what a failed
     * provisioning means, and {@code UserResolutionService.ensureFreeSubscription} decides it
     * means "log it and carry on".
     *
     * @return the subscription id when this call created one, otherwise empty (already had one,
     *         or the FREE plan is missing)
     */
    @Transactional
    public Optional<Long> provisionIfMissing(User user) {
        if (user == null || user.getId() == null) {
            return Optional.empty();
        }
        // Cheap idempotent early-out, taken BEFORE the mutex. resolveUser runs on every gateway
        // request while the subscription is created exactly once, so this is the answer virtually
        // every time, and what it saves on that path is a PESSIMISTIC_WRITE row lock taken on the
        // user's billing customer by every single request. It does NOT save the first-login racers
        // anything: on a cold start none of them sees a subscription, so they all fall through to
        // the mutex below. What keeps THEM alive is that acquiring it can no longer raise.
        if (subscriptionRepository.findActiveByUserId(user.getId()).isPresent()) {
            return Optional.empty();
        }

        BillingCustomer billingCustomer = lockOrCreateBillingCustomer(user);

        // Re-check INSIDE the lock. A concurrent request that got here first has already
        // committed its subscription by the time it released the billing-customer row.
        if (subscriptionRepository.findActiveByUserId(user.getId()).isPresent()) {
            return Optional.empty();
        }

        Optional<Plan> freePlanOpt = planRepository.findByCode("FREE");
        if (freePlanOpt.isEmpty()) {
            log.error("FREE plan not found in database");
            return Optional.empty();
        }
        Plan freePlan = freePlanOpt.get();

        LocalDateTime now = LocalDateTime.now();
        Subscription sub = new Subscription();
        sub.setBillingCustomer(billingCustomer);
        sub.setPlan(freePlan);
        sub.setCadence("monthly");
        sub.setStatus("active");
        sub.setProvider("internal");
        sub.setCurrentPeriodStart(now);
        sub.setCurrentPeriodEnd(now.plusMonths(1));
        sub.setCancelAtPeriodEnd(false);
        // V494: the AI allowance is NOT seeded here. It buys real platform-key
        // inference, so it is granted where the monthly credits are granted, behind the
        // same email-verification gate (CreditAttributionService.attributeOnSubscription,
        // reached from UserResolutionService.attributeCreditsIfEligible). Seeding it on
        // the row at creation looked harmless and was not: it handed every unverified
        // throwaway signup an immediate, scriptable 100 credits of inference.
        Subscription saved = subscriptionRepository.save(sub);

        log.info("FREE subscription created for userId={} (subId={}). Credits pending email verification.",
                user.getId(), saved.getId());

        quotaSyncer.syncAfterCommit(user.getId(), freePlan);
        return Optional.ofNullable(saved.getId());
    }

    /**
     * The per-user mutex. Locks the existing billing-customer row, or creates it first.
     *
     * <p>The creation goes through {@link BillingCustomerRepository#insertIfAbsent}, which cannot
     * raise. It used to be a {@code save()} wrapped in a catch that re-read the winner's row on a
     * unique-index violation, and that shape is unfixable from the inside: the violation has
     * already aborted the PostgreSQL transaction and flagged it rollback-only, so the re-read
     * itself fails and the commit throws past every catch in this class. See the repository method
     * for the full account.
     */
    private BillingCustomer lockOrCreateBillingCustomer(User user) {
        Optional<BillingCustomer> existing = billingCustomerRepository.findByUserIdForUpdate(user.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        int inserted = billingCustomerRepository.insertIfAbsent(user.getId(), "internal");
        return billingCustomerRepository.findByUserIdForUpdate(user.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "billing customer still absent after insertIfAbsent(inserted=" + inserted
                                + ") for userId=" + user.getId()));
    }
}
