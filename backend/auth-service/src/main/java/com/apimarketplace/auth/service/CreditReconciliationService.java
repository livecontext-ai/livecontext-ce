package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CreditReconciliationLog;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.CreditReconciliationLogRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Scheduled reconciliation service that verifies credit ledger consistency
 * within each user's current billing period.
 *
 * Logic:
 *   balance_at_period_start = balance_before + SUM(ledger entries since period_start)
 *   expected_balance = SUM(ledger entries since period_start)
 *                      (the first entry is usually a PLAN_GRANT/PLAN_RESET that sets the base)
 *   drift = (remaining_credits + payg_remaining_credits) - expected_balance
 *
 * <p>Two-bucket awareness (V250): {@code periodLedgerSum} sums every ledger entry
 * for the user since {@code periodStart}, which includes any PAYG_TOPUP grants that
 * landed mid-period. The {@link Subscription#getTotalBalance()} call mirrors that on
 * the read side by summing both the cycle bucket and the PAYG bucket. Comparing only
 * {@code getRemainingCredits()} would page ops on every user who bought a PAYG top-up
 * during the current billing period: the grant inflates the ledger sum but the sub
 * bucket is unchanged, generating a phantom "drift" equal to the PAYG amount.
 *
 * <p>PAYG carry-over (cross-period): a PAYG balance bought in an EARLIER period
 * persists across renewals but has no ledger row inside the current period, so the
 * period-scoped comparison reports a phantom positive drift equal to the carried
 * balance - for every such user, every day. When a period drift is detected, the
 * LIFETIME ledger sum is checked (balance starts at 0 - no baseline problem;
 * released markup reservations are excluded because a RELEASED row keeps its
 * -reserved audit amount while the release refunded the balance, so raw lifetime
 * sums under-count by exactly the released total): if the lifetime books balance,
 * the drift is the carry-over artifact and is logged as explained. A real drift
 * (lost or duplicated movement) is off in the lifetime sum too and stays
 * unexplained.
 *
 * <p>Cross-references pending dead-letter entries to distinguish explained drift
 * (pending retries) from unexplained drift (real issue).
 *
 * <p>Runs daily at 4:00 AM. Skipped in unlimited (CE) mode where balance is a sentinel.
 */
@Service
public class CreditReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(CreditReconciliationService.class);

    /** Drift threshold - ignore rounding differences. */
    private static final BigDecimal DRIFT_THRESHOLD = new BigDecimal("0.01");

    private final CreditLedgerRepository ledgerRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final CreditReconciliationLogRepository reconciliationLogRepository;
    private final CreditConsumptionDeadLetterService deadLetterService;
    private final boolean unlimited;

    public CreditReconciliationService(CreditLedgerRepository ledgerRepository,
                                        SubscriptionRepository subscriptionRepository,
                                        CreditReconciliationLogRepository reconciliationLogRepository,
                                        CreditConsumptionDeadLetterService deadLetterService,
                                        @Value("${credit.unlimited:false}") boolean unlimited) {
        this.ledgerRepository = ledgerRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.reconciliationLogRepository = reconciliationLogRepository;
        this.deadLetterService = deadLetterService;
        this.unlimited = unlimited;
    }

    /**
     * Daily reconciliation - scoped to each user's current billing period.
     *
     * For each user with an active subscription:
     *   1. Get currentPeriodStart from subscription
     *   2. Sum ledger entries since periodStart (includes PLAN_GRANT + consumptions)
     *   3. Compare with remaining_credits
     *   4. If drift detected, check dead-letter tables for explanation
     */
    @Scheduled(cron = "0 0 4 * * *")
    @SchedulerLock(name = "credit_reconciliation", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void reconcile() {
        if (unlimited) {
            log.info("Credit reconciliation skipped: unlimited mode (CE) - balance is a sentinel, drift is meaningless");
            return;
        }

        log.info("Starting credit reconciliation...");

        List<Long> userIds = ledgerRepository.findAllDistinctUserIds();
        int checked = 0;
        int drifted = 0;
        int explained = 0;

        for (Long userId : userIds) {
            try {
                Subscription sub = subscriptionRepository.findActiveByUserId(userId).orElse(null);
                if (sub == null) continue;

                LocalDateTime periodStart = sub.getCurrentPeriodStart();
                if (periodStart == null) continue;

                // Sum all ledger entries in current billing period
                // Includes: PLAN_GRANT (positive) + PLAN_RESET + PAYG_TOPUP grants + consumptions (negative)
                BigDecimal periodLedgerSum = zeroIfNull(ledgerRepository.sumAmountByUserIdSince(userId, periodStart));
                // V250 two-bucket: compare against the SUM of both buckets so a PAYG
                // top-up that lands mid-period doesn't appear as drift (the grant
                // inflates the ledger sum but only the PAYG column moves on the sub).
                BigDecimal balance = sub.getTotalBalance();
                // V494: an AI-funded row records what the buckets gave up in `amount`,
                // and the monthly AI allowance is a third bucket getTotalBalance()
                // excludes - that part of the movement never touched the balance
                // compared here.
                // Adding it back is what keeps a free-tier turn from reading as a lost
                // movement; without it drift grows by the cost of every such turn.
                BigDecimal aiFundedInPeriod = zeroIfNull(
                        ledgerRepository.sumAiPortionByUserIdSince(userId, periodStart));
                BigDecimal drift = balance.subtract(periodLedgerSum.add(aiFundedInPeriod));

                if (drift.abs().compareTo(DRIFT_THRESHOLD) > 0) {
                    int pendingDl = countPendingDeadLetters(String.valueOf(userId));

                    // PAYG carry-over is NOT drift: the PAYG bucket persists across
                    // renewals (V250) but no ledger row re-asserts it at period start,
                    // so the period-scoped sum under-counts by exactly the balance
                    // carried in. The lifetime books have no such baseline problem
                    // (balance starts at 0): when they balance, the period "drift" is
                    // the carry-over artifact, not a lost/duplicated movement. A REAL
                    // drift (missing or double row) is off in the lifetime sum too and
                    // stays unexplained. One class of rows must be excluded for the
                    // lifetime invariant to hold: PLATFORM_MARKUP_RELEASED* rows keep
                    // their -reserved amount as an audit trail while the release
                    // refunded the balance - balance-neutral but sum-visible (see the
                    // repository method's javadoc).
                    // Same AI add-back as the period sum above: the lifetime invariant
                    // has to net out the allowance-funded movements too, or a free-tier
                    // account never balances and every drift reads as unexplained.
                    BigDecimal lifetimeDrift = balance.subtract(
                            zeroIfNull(ledgerRepository.sumAmountByUserIdExcludingReleasedReserves(userId))
                                    .add(zeroIfNull(ledgerRepository.sumAiPortionByUserId(userId))));
                    boolean lifetimeBalanced = lifetimeDrift.abs().compareTo(DRIFT_THRESHOLD) <= 0;

                    boolean isExplained = pendingDl > 0 || lifetimeBalanced;

                    // Persist the sum that `drift` was actually computed against, not the
                    // raw one: an operator reading credit_reconciliation_log must be able
                    // to recompute drift = balance - ledger_sum. Logging the raw sum would
                    // make every free-tier account appear off by exactly its ai_portion,
                    // which is the confusion the add-back exists to remove.
                    reconciliationLogRepository.save(new CreditReconciliationLog(
                            userId, balance, periodLedgerSum.add(aiFundedInPeriod), drift, pendingDl, isExplained));

                    if (isExplained) {
                        log.info("CREDIT DRIFT (explained): user={}, drift={}, pending_dead_letters={}, lifetime_balanced={}, period_start={}",
                                userId, drift, pendingDl, lifetimeBalanced, periodStart);
                        explained++;
                    } else {
                        log.warn("CREDIT DRIFT (unexplained): user={}, balance={}, ledger_sum={}, drift={}, lifetime_drift={}, period_start={}",
                                userId, balance, periodLedgerSum, drift, lifetimeDrift, periodStart);
                    }
                    drifted++;
                }
                checked++;
            } catch (Exception e) {
                log.error("Reconciliation error for user {}: {}", userId, e.getMessage());
            }
        }

        log.info("Credit reconciliation complete: {} checked, {} drifted ({} explained, {} unexplained)",
                checked, drifted, explained, drifted - explained);
    }

    /**
     * Count pending dead-letter entries from the local auth schema table.
     * All dead-letter entries are now centralized in auth.credit_consumption_dead_letter.
     */
    private int countPendingDeadLetters(String tenantId) {
        try {
            return (int) deadLetterService.countPendingForTenant(tenantId);
        } catch (Exception e) {
            log.debug("Could not query dead-letter table: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Get drift records from the last N days.
     */
    public List<CreditReconciliationLog> getRecentDrifts(int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        return reconciliationLogRepository.findByCreatedAtAfterOrderByDriftDesc(since);
    }

    /**
     * A COALESCE-backed sum still returns null through a mocked repository, and a
     * reconciliation pass must not die on one user because one aggregate came back
     * empty. Treating absent as zero is also the arithmetically correct reading: no
     * rows summed means no movement to account for.
     */
    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
