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
 * <p>Idempotence: the renewal sourceId includes the subscription's currentPeriodStart, which
 * is unique per period. If the scheduler runs twice for the same period, attributeOnRenewal()
 * detects the duplicate via ledgerRepository.existsBySourceId("reset_" + ...) and skips.
 */
@Component
public class FreeSubscriptionRenewalScheduler {

    private static final Logger log = LoggerFactory.getLogger(FreeSubscriptionRenewalScheduler.class);

    private final SubscriptionRepository subscriptionRepository;
    private final CreditAttributionService creditAttributionService;

    public FreeSubscriptionRenewalScheduler(SubscriptionRepository subscriptionRepository,
                                             CreditAttributionService creditAttributionService) {
        this.subscriptionRepository = subscriptionRepository;
        this.creditAttributionService = creditAttributionService;
    }

    @Scheduled(cron = "0 0 * * * *") // Every hour
    @SchedulerLock(name = "free_subscription_renewal", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void renewExpiredInternalSubscriptions() {
        LocalDateTime now = LocalDateTime.now();
        List<Subscription> expired = subscriptionRepository.findExpiredInternalSubscriptions(now);

        if (expired.isEmpty()) {
            return;
        }

        log.info("Found {} expired internal subscriptions to renew", expired.size());

        for (Subscription sub : expired) {
            try {
                Long userId = sub.getBillingCustomer().getUser().getId();

                creditAttributionService.attributeOnRenewal(userId, sub);

                sub.setCurrentPeriodStart(now);
                sub.setCurrentPeriodEnd(now.plusMonths(1));
                sub.setUpdatedAt(now);
                subscriptionRepository.save(sub);

                log.info("Internal subscription renewed for userId={} (plan={}), new period ends {}",
                        userId, sub.getPlan() != null ? sub.getPlan().getCode() : "?", sub.getCurrentPeriodEnd());
            } catch (Exception e) {
                log.error("Failed to renew internal subscription id={}: {}",
                        sub.getId(), e.getMessage());
            }
        }
    }
}
