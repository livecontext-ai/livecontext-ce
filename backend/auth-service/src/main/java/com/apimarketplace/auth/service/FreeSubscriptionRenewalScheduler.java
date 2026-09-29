package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduler that renews internal (non-Stripe) subscriptions monthly.
 *
 * <p>Internal subscriptions are created locally (not via Stripe), so {@code invoice.paid}
 * webhooks never fire for them. This covers BOTH the FREE plan AND admin-granted comp
 * Starter/Pro/Team rows (also {@code provider='internal'}). The scheduler detects expired
 * internal subscriptions and resets + re-grants their credits using
 * {@link CreditAttributionService#attributeOnRenewal} - FREE renews at its 1K plan-included
 * grant, comp plans renew at the 5K tier-0 base (admin-credits "5k/month max" rule).
 *
 * <p>The whole renewal of one subscription (advance the period, reset, re-grant) happens
 * inside {@link CreditAttributionService#attributeOnRenewal(Long, Subscription, java.time.LocalDateTime)}'s
 * transaction, one per row, so a failure on one subscription neither rolls back nor blocks
 * the others. This loop deliberately performs NO write of its own.
 *
 * <p>Idempotence: a renewal moves currentPeriodEnd a month forward in the same transaction
 * that grants the credits, so a second pass no longer selects that subscription at all. The
 * sourceId, derived from the NEW currentPeriodStart, is fresh every cycle - deriving it from
 * the OLD one used to collide with the key an admin plan grant had already consumed, and the
 * existsBySourceId guards then skipped the renewal silently.
 */
@Component
public class FreeSubscriptionRenewalScheduler {

    private static final Logger log = LoggerFactory.getLogger(FreeSubscriptionRenewalScheduler.class);

    private final SubscriptionRepository subscriptionRepository;
    private final CreditAttributionService creditAttributionService;
    private final AdminPlanService adminPlanService;

    public FreeSubscriptionRenewalScheduler(SubscriptionRepository subscriptionRepository,
                                             CreditAttributionService creditAttributionService,
                                             AdminPlanService adminPlanService) {
        this.subscriptionRepository = subscriptionRepository;
        this.creditAttributionService = creditAttributionService;
        this.adminPlanService = adminPlanService;
    }

    // Hourly by default. Overridable so a test that drives this pass explicitly can set "-"
    // (Spring's disabled marker) and not race its own fixture against a live firing.
    @Scheduled(cron = "${subscription.internal-renewal.cron:0 0 * * * *}")
    @SchedulerLock(name = "free_subscription_renewal", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void renewExpiredInternalSubscriptions() {
        LocalDateTime now = LocalDateTime.now();

        // V549: timed comps end on their own date, before any renewal is considered. A revert
        // re-anchors the cycle, so the reverted row is not picked up as expired just below.
        for (Subscription comp : subscriptionRepository.findEndedInternalComps(now)) {
            try {
                adminPlanService.revertExpiredComp(comp.getBillingCustomer().getUser().getId(), now);
            } catch (Exception e) {
                log.error("Failed to revert ended comp subscription id={}: {}", comp.getId(), e.getMessage());
            }
        }

        List<Subscription> expired = subscriptionRepository.findExpiredInternalSubscriptions(now);

        if (expired.isEmpty()) {
            return;
        }

        log.info("Found {} expired internal subscriptions to renew", expired.size());

        for (Subscription sub : expired) {
            try {
                Long userId = sub.getBillingCustomer().getUser().getId();

                // V549: a timed comp (partner creator code) whose end has passed reverts to
                // FREE instead of renewing. The revert re-anchors the cycle and grants the
                // FREE allowance itself, so this sub must not also go through the renewal.
                if (sub.getCompEndsAt() != null && !sub.getCompEndsAt().isAfter(now)) {
                    if (adminPlanService.revertExpiredComp(userId, now)) {
                        continue;
                    }
                }

                // Period advance + reset + re-grant, all inside attributeOnRenewal's single
                // transaction. This loop must NOT write the subscription row itself: `sub` is
                // detached (a @Scheduled thread has no persistence context) and the grant lands
                // on a re-resolved managed instance, so any save here would merge this pre-grant
                // copy back over the fresh balance and silently destroy the credits just granted.
                CreditAttributionService.RenewalOutcome outcome =
                        creditAttributionService.attributeOnRenewal(userId, sub, now);

                // Log what actually happened. Announcing "renewed" unconditionally is how a
                // renewal that granted nothing stayed invisible in production for weeks.
                if (outcome == CreditAttributionService.RenewalOutcome.RENEWED) {
                    log.info("Internal subscription id={} renewed for userId={}", sub.getId(), userId);
                } else if (outcome == CreditAttributionService.RenewalOutcome.ALREADY_RENEWED) {
                    // Benign and expected whenever an admin grant lands in the same minute -
                    // INFO, not WARN, so it never pages on healthy interleaving.
                    log.info("Internal subscription id={} already renewed by another actor, skipped", sub.getId());
                } else {
                    log.warn("Internal subscription id={} NOT renewed for userId={}: {}",
                            sub.getId(), userId, outcome);
                }
            } catch (Exception e) {
                log.error("Failed to renew internal subscription id={}: {}",
                        sub.getId(), e.getMessage());
            }
        }
    }
}
