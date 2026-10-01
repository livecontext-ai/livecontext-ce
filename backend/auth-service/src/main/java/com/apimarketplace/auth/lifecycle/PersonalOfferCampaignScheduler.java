package com.apimarketplace.auth.lifecycle;

import com.apimarketplace.auth.domain.PersonalOfferMatrix;
import com.apimarketplace.auth.domain.PersonalOfferPolicy;
import com.apimarketplace.auth.service.PersonalOfferService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Observes FREE exhaustion, issues one account-bound offer, and dispatches its due email steps. */
@Component
@ConditionalOnProperty(name = "billing.provider", havingValue = "stripe")
public class PersonalOfferCampaignScheduler {
    private static final Logger log = LoggerFactory.getLogger(PersonalOfferCampaignScheduler.class);
    private static final String CAMPAIGN = PersonalOfferLifecycleRepository.CAMPAIGN_KEY;

    private final PersonalOfferLifecycleRepository lifecycle;
    private final PersonalOfferService offers;
    private final LifecycleEmailService email;
    private final Clock clock;
    private final int deliveryMarginMinutes;

    @Autowired
    public PersonalOfferCampaignScheduler(PersonalOfferLifecycleRepository lifecycle,
                                          PersonalOfferService offers,
                                          LifecycleEmailService email,
                                          @Value("${personal-offer.delivery-margin-minutes:120}")
                                          int deliveryMarginMinutes) {
        this(lifecycle, offers, email, Clock.systemUTC(), deliveryMarginMinutes);
    }

    PersonalOfferCampaignScheduler(PersonalOfferLifecycleRepository lifecycle, PersonalOfferService offers,
                                   LifecycleEmailService email, Clock clock, int deliveryMarginMinutes) {
        this.lifecycle = lifecycle;
        this.offers = offers;
        this.email = email;
        this.clock = clock;
        this.deliveryMarginMinutes = Math.max(0, deliveryMarginMinutes);
    }

    @Scheduled(initialDelayString = "${personal-offer.scan-initial-delay-ms:120000}",
            fixedDelayString = "${personal-offer.scan-interval-ms:900000}")
    @SchedulerLock(name = "personal-offer-campaign-scan", lockAtMostFor = "PT10M")
    public void scan() {
        Instant now = clock.instant();
        try {
            lifecycle.markStaleClaimsUnknown(now.minus(10, ChronoUnit.MINUTES), now);
            Optional<PersonalOfferPolicy> active = offers.activePolicy(CAMPAIGN);
            // DRAFT is the installed default. Pausing issuance also stops dispatch, while
            // issued codes retain their original absolute expiry.
            if (active.isEmpty()) return;
            observe(now);
            issueDue(active.get(), now);
            dispatchDue(now);
        } catch (RuntimeException e) {
            log.warn("[personal-offer] scan failed: {}", e.toString());
        }
    }

    private void observe(Instant now) {
        long after = 0;
        List<Long> page;
        do {
            page = lifecycle.exhaustedFreeUsers(after);
            for (long userId : page) {
                after = userId;
                try { lifecycle.observe(userId, now); }
                catch (RuntimeException e) { log.warn("[personal-offer] observation failed for user {}: {}", userId, e.toString()); }
            }
        } while (page.size() == 500);
    }

    private void issueDue(PersonalOfferPolicy policy, Instant now) {
        long after = 0;
        List<PersonalOfferLifecycleRepository.Observation> page;
        do {
            page = lifecycle.observations(after);
            for (var observed : page) {
                after = observed.userId();
                try { issueOne(policy, observed, now); }
                catch (RuntimeException e) { log.warn("[personal-offer] issue failed for user {}: {}", observed.userId(), e.toString()); }
            }
        } while (page.size() == 500);
    }

    private void issueOne(PersonalOfferPolicy policy, PersonalOfferLifecycleRepository.Observation observation,
                          Instant now) {
        long userId = observation.userId();
        if (lifecycle.hasPositiveGrantSince(userId, observation.exhaustedAt())
                || !lifecycle.isStillFreeAndExhausted(userId)) {
            lifecycle.resetUnissued(userId, now);
            // A top-up that was fully spent again begins a fresh waiting period.
            if (lifecycle.isExhaustedFree(userId)) lifecycle.observe(userId, now);
            return;
        }
        if (now.isBefore(observation.exhaustedAt().plus(policy.getWaitHours(), ChronoUnit.HOURS))) return;
        if (!lifecycle.isUserMarketingEligible(userId)) return;
        // UNKNOWN first-payment history is verified by issueForEligible against Stripe.
        // A precheck here would permanently exclude all migrated FREE accounts.
        offers.issueForEligible(userId, CAMPAIGN, now).ifPresent(issued -> {
            lifecycle.attachIssued(userId, issued.offerId(), now);
            // Checkout recovery reads this contact property even while the two offer
            // automations are disabled. Do not tie suppression to campaign delivery.
            email.syncContact(userId);
        });
    }

    private void dispatchDue(Instant now) {
        long after = 0;
        List<PersonalOfferLifecycleRepository.Issued> page;
        do {
            page = lifecycle.issuedWithPendingEmails(after);
            for (var row : page) {
                after = row.userId();
                try { dispatchOne(row, now); }
                catch (RuntimeException e) { log.warn("[personal-offer] dispatch failed for user {}: {}", row.userId(), e.toString()); }
            }
        } while (page.size() == 500);
    }

    private void dispatchOne(PersonalOfferLifecycleRepository.Issued row, Instant now) {
        long userId = row.userId();
        var issued = offers.readCurrentForUser(userId).orElse(null);
        if (issued == null || issued.offerId() != row.codeId()) {
            lifecycle.suppressPending(userId, "offer_missing", now);
            return;
        }
        var policy = offers.policyById(issued.policyId()).orElse(null);
        if (policy == null) {
            lifecycle.suppressPending(userId, "policy_missing", now);
            return;
        }
        // Match the reward-code URL contract, including personalized names and legacy codes.
        if (issued.code() == null || !issued.code().matches("[A-Z0-9][A-Z0-9_-]{2,63}")) {
            lifecycle.suppressPending(userId, "invalid_code", now);
            return;
        }
        if (offers.activePolicy(CAMPAIGN).isEmpty()) return;
        if (!stillTarget(userId, issued, now)) {
            lifecycle.suppressPending(userId, "ineligible", now);
            return;
        }
        Instant latest = issued.expiresAt().minus(deliveryMarginMinutes, ChronoUnit.MINUTES);
        if (!now.isBefore(latest)) {
            lifecycle.suppressPending(userId, "delivery_window", now);
            return;
        }
        String current = offers.current(userId).status();
        // A checkout can be canceled or time out while the original code remains valid.
        // Defer emails during payment, then reconsider within the same absolute window.
        if ("CHECKOUT_OPEN".equals(current) || "PENDING_PAYMENT".equals(current)) return;
        if (!"AVAILABLE".equals(current)) {
            lifecycle.suppressPending(userId, "ineligible", now);
            return;
        }
        List<PersonalOfferMatrix> matrix = offers.matrixForPolicy(issued.policyId());
        if (matrix.isEmpty()) {
            lifecycle.suppressPending(userId, "policy_missing", now);
            return;
        }
        send(userId, issued, policy, matrix, PersonalOfferLifecycleRepository.Step.INITIAL,
                LifecycleEvents.PERSONAL_OFFER_INITIAL_DUE, now, latest);

        if (!policy.isReminderEnabled()) {
            lifecycle.suppressStep(userId, PersonalOfferLifecycleRepository.Step.REMINDER, "reminder_disabled", now);
            return;
        }
        Instant due = issued.expiresAt().minus(policy.getReminderHours(), ChronoUnit.HOURS);
        // The scheduler is a scan: any time within the due-to-expiry window is valid.
        if (!now.isBefore(due) && now.isBefore(latest) && lifecycle.initialAccepted(userId))
            send(userId, issued, policy, matrix, PersonalOfferLifecycleRepository.Step.REMINDER,
                    LifecycleEvents.PERSONAL_OFFER_REMINDER_DUE, now, latest);
    }

    private boolean stillTarget(long userId, PersonalOfferService.IssuedOffer issued, Instant at) {
        return at.isBefore(issued.expiresAt()) && lifecycle.isUserMarketingEligible(userId)
                && lifecycle.isStillFreeAndExhausted(userId)
                && !lifecycle.hasPositiveGrantSince(userId, issued.issuedAt())
                && !offers.hasFirstPaidPurchase(userId);
    }

    private boolean eligibleForEmail(long userId, PersonalOfferService.IssuedOffer issued, Instant at) {
        return offers.activePolicy(CAMPAIGN).isPresent() && stillTarget(userId, issued, at)
                && "AVAILABLE".equals(offers.current(userId).status());
    }

    private void send(long userId, PersonalOfferService.IssuedOffer issued, PersonalOfferPolicy policy,
                      List<PersonalOfferMatrix> matrix, PersonalOfferLifecycleRepository.Step step,
                      String event, Instant now, Instant latest) {
        AtomicReference<Instant> claimAt = new AtomicReference<>();
        LifecycleEmailService.Dispatch dispatch = email.submitPersonalOffer(userId, event, locale -> Map.of(
                "code", issued.code(),
                "expires_at", PersonalOfferEmailContent.expiry(issued.expiresAt(), locale),
                "terms", PersonalOfferEmailContent.terms(policy, matrix, locale)),
                () -> {
                    Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
                    if (!at.isBefore(latest) || !eligibleForEmail(userId, issued, at)) return false;
                    if (!lifecycle.claim(userId, step, at)) return false;
                    claimAt.set(at);
                    return true;
                },
                () -> {
                    Instant at = clock.instant();
                    return at.isBefore(latest) && eligibleForEmail(userId, issued, at);
                },
                outcome -> {
                    Instant claimed = claimAt.get();
                    if (claimed != null) lifecycle.finish(userId, step, claimed, outcome, clock.instant());
                });
        if (dispatch == LifecycleEmailService.Dispatch.INACTIVE)
            log.debug("[personal-offer] email channel inactive; step remains pending for user {}", userId);
    }
}
