package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Grants the MONTHLY credit cycles of YEARLY Stripe subscriptions (V498).
 *
 * <p>A monthly Stripe subscription is re-granted its credit pack on every {@code invoice.paid}
 * ({@code subscription_cycle}). A yearly one raises that invoice once every twelve months, so
 * before this scheduler it was granted one month of credits for a year of payment (the pack is
 * priced per unit per month on every cadence, and sold as "credits per month"). This pass
 * hands each active yearly Stripe subscription to
 * {@link CreditAttributionService#attributeMonthlyCreditCycle}, which decides from
 * {@code creditCycleIndex} whether a new cycle has started and, if so, resets and re-grants
 * exactly as a monthly renewal would.
 *
 * <p>Sibling of {@link FreeSubscriptionRenewalScheduler}, with the same discipline: the whole
 * change of one subscription (index advance, reset, re-grant) happens inside the attribution
 * service's transaction, one per row, and this loop performs NO write of its own. The
 * subscriptions it holds are DETACHED (a {@code @Scheduled} thread has no persistence
 * context); saving one here would merge a pre-grant copy back over the fresh balance, which is
 * the production defect the internal scheduler once had.
 *
 * <p>Internal (FREE / comp) and monthly Stripe subscriptions are never selected: their credit
 * cycle IS their billing period and is owned by the internal scheduler and by Stripe
 * respectively. Nothing here changes for them.
 */
@Component
public class YearlyCreditCycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(YearlyCreditCycleScheduler.class);

    private final SubscriptionRepository subscriptionRepository;
    private final CreditAttributionService creditAttributionService;
    private final SubscriptionCacheBuster subscriptionCacheBuster;

    public YearlyCreditCycleScheduler(SubscriptionRepository subscriptionRepository,
                                      CreditAttributionService creditAttributionService,
                                      SubscriptionCacheBuster subscriptionCacheBuster) {
        this.subscriptionRepository = subscriptionRepository;
        this.creditAttributionService = creditAttributionService;
        this.subscriptionCacheBuster = subscriptionCacheBuster;
    }

    // Hourly, five minutes after the internal renewal pass. Overridable so a test that drives
    // this pass explicitly can set "-" (Spring's disabled marker) and not race a live firing.
    @Scheduled(cron = "${subscription.yearly-credit-cycle.cron:0 5 * * * *}")
    @SchedulerLock(name = "yearly_credit_cycle", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void grantDueMonthlyCycles() {
        LocalDateTime now = LocalDateTime.now();
        List<Subscription> candidates = subscriptionRepository.findActiveYearlyStripeSubscriptions();

        if (candidates.isEmpty()) {
            return;
        }

        int granted = 0;
        for (Subscription sub : candidates) {
            try {
                Long userId = sub.getBillingCustomer().getUser().getId();

                CreditAttributionService.MonthlyCycleOutcome outcome =
                        creditAttributionService.attributeMonthlyCreditCycle(userId, sub, now);

                // Log what actually happened, never a blanket success: a pass that granted
                // nothing is exactly the failure that stays invisible otherwise.
                if (outcome == CreditAttributionService.MonthlyCycleOutcome.GRANTED) {
                    granted++;
                    log.info("Monthly credit cycle granted on yearly subscription id={} for userId={}", sub.getId(), userId);
                    // Same fan-out as the Stripe renewal path (invoice.paid.renewal): the
                    // gateway caches the owner's plan resolution for minutes, for the owner and
                    // for every member of the workspaces they own. Best-effort inside, never
                    // fails the pass.
                    subscriptionCacheBuster.fanOutForOwner(userId, "yearly.credit_cycle");
                } else if (outcome == CreditAttributionService.MonthlyCycleOutcome.ABSORBED) {
                    // The cycle's ledger keys already existed (rewound index, replay): the guards
                    // wrote nothing, so no cache fan-out and no claim of a grant.
                    log.info("Monthly credit cycle on yearly subscription id={} for userId={} was already in the ledger, index re-aligned",
                            sub.getId(), userId);
                } else if (outcome == CreditAttributionService.MonthlyCycleOutcome.SKIPPED) {
                    // Benign whenever a cycle change or a cancellation landed between the select
                    // and the lock; the attribution service already logs the abnormal causes
                    // (unresolvable row, no plan) at ERROR, so INFO here, like the internal pass.
                    log.info("Monthly credit cycle NOT granted on yearly subscription id={} for userId={}: {}",
                            sub.getId(), userId, outcome);
                }
                // NOT_DUE is the normal state of every row for most of the month: silent.
            } catch (Exception e) {
                // The exception itself, not only its message: an UnexpectedRollbackException or
                // an NPE has none, and an empty line is how a failing pass stays invisible.
                log.error("Failed to grant monthly credit cycle on yearly subscription id={}: {}",
                        sub.getId(), e.getMessage(), e);
            }
        }
        log.info("Yearly credit cycle pass: {} candidate(s), {} granted", candidates.size(), granted);
    }
}
