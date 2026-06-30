package com.apimarketplace.auth.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Releases qualified referral rewards once their anti-refund hold elapses, and
 * sweeps for conversions a swallowed webhook may have missed.
 *
 * <p>Cloud-only: rewards convert through Stripe, so this is gated on the same
 * {@code billing.provider=stripe} as the webhook. Each row is processed in its
 * own transaction (the proxied {@code releaseOne}/{@code reconcileRedeemer} calls)
 * so one failure does not roll back the batch. ShedLock keeps a single instance
 * running it across a multi-replica deployment.
 */
@Component
@ConditionalOnProperty(name = "billing.provider", havingValue = "stripe")
public class RewardHoldReleaseScheduler {

    private static final Logger log = LoggerFactory.getLogger(RewardHoldReleaseScheduler.class);

    private final RewardService rewardService;

    public RewardHoldReleaseScheduler(RewardService rewardService) {
        this.rewardService = rewardService;
    }

    @Scheduled(cron = "0 0 * * * *") // Every hour
    @SchedulerLock(name = "reward_hold_release", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void releaseDueHolds() {
        // Reconcile first, so a freshly recovered conversion can be released in the
        // same tick once its hold has elapsed.
        List<Long> redeemerIds = rewardService.findPendingReferralRedeemerIds();
        for (Long redeemerId : redeemerIds) {
            try {
                rewardService.reconcileRedeemer(redeemerId);
            } catch (Exception e) {
                log.error("Reward sweeper failed for redeemer {}: {}", redeemerId, e.getMessage(), e);
            }
        }

        List<Long> dueIds = rewardService.findDueHoldIds();
        if (dueIds.isEmpty()) return;
        int released = 0;
        for (Long id : dueIds) {
            try {
                if (rewardService.releaseOne(id)) released++;
            } catch (Exception e) {
                log.error("Failed to release reward redemption {}: {}", id, e.getMessage(), e);
            }
        }
        log.info("Reward hold release: {} of {} due rewards released", released, dueIds.size());
    }
}
