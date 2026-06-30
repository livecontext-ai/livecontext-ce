package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Auto-release sweeper for orphaned {@code PLATFORM_MARKUP_RESERVE} ledger rows.
 *
 * <p><b>Why this exists:</b> {@code tryReserveMarkup} debits the user's balance
 * upfront and writes a {@code _RESERVE} ledger row with an {@code expires_at}
 * deadline. Normally {@code commitReservation} or {@code releaseReservation}
 * flips the row before {@code expires_at} elapses. If the catalog crashes,
 * loses connection to auth-service, or the upstream call hangs past TTL, the
 * reserve row would otherwise stay debited forever - silent revenue loss.
 *
 * <p>This sweeper closes the gap. Every 5 minutes it scans the indexed view
 * (V150 partial index on {@code (expires_at) WHERE source_type='PLATFORM_MARKUP_RESERVE'
 * AND expires_at IS NOT NULL}) for past-deadline reservations and calls
 * {@link CreditService#releaseReservation} on each one. Refunds the user's
 * balance and flips the row to {@code PLATFORM_MARKUP_RELEASED_TIMEOUT}.
 *
 * <p><b>TTL conventions</b> (set by callers at reserve time, NOT this sweeper):
 * <ul>
 *   <li>Catalog post-flight reserve: 10 min (catalog hard timeout 5s + safety)</li>
 *   <li>Per-step short LLM call: 15 min (configurable)</li>
 *   <li>Long-running step (browser-agent, classify): 60 min</li>
 *   <li>Workflow run-init reserve: 1440 min (24h, matches workflow max)</li>
 * </ul>
 *
 * <p>The sweeper does NOT make policy decisions on threshold - it just respects
 * whatever {@code expires_at} the caller wrote. If a caller picks too short a
 * TTL (TTL &lt; upstream timeout), the sweeper auto-releases mid-call → upstream
 * commits to a row that's already RELEASED → commit returns {@code RESERVATION_EXPIRED}
 * → metric fires + PagerDuty (rate-based). Documented in
 * {@code OPERATOR_RUNBOOK §reservation-expired}.
 *
 * <p><b>Heartbeat:</b> {@link #lastRunEpochSeconds} is updated on every successful
 * sweep. Grafana alerts when the value goes stale (&gt;2h since last update),
 * indicating the sweeper has stopped (auth-service crash loop, ShedLock stuck,
 * etc.). On extended outage, ops manually triggers a full release pass.
 *
 * <p><b>ShedLock:</b> exclusive lock {@code markup_reserve_sweep} prevents two
 * auth-service instances from racing. {@code lockAtMostFor=PT4M} is less than
 * the 5-min cadence so a crashed worker doesn't starve the next scheduled run.
 */
@Service
public class PlatformMarkupReserveSweeper {

    private static final Logger log = LoggerFactory.getLogger(PlatformMarkupReserveSweeper.class);

    /** Cap per scheduled run so a backlog doesn't monopolize the auth-service event thread. */
    private static final int BATCH_LIMIT = 500;

    private final CreditLedgerRepository ledgerRepository;
    private final CreditService creditService;
    private final boolean markupEnabled;

    /**
     * Last successful sweep epoch seconds. Surfaced as the
     * {@code markup_reserve_sweep_last_run_epoch_seconds} gauge. Initialized to
     * application start time so a fresh deploy doesn't immediately page.
     */
    private final AtomicLong lastRunEpochSeconds = new AtomicLong(System.currentTimeMillis() / 1000L);

    public PlatformMarkupReserveSweeper(CreditLedgerRepository ledgerRepository,
                                         CreditService creditService,
                                         @Value("${credentials.platform.markup.enabled:true}") boolean markupEnabled) {
        this.ledgerRepository = ledgerRepository;
        this.creditService = creditService;
        this.markupEnabled = markupEnabled;
        if (!markupEnabled) {
            log.info("PlatformMarkupReserveSweeper: markup billing DISABLED - sweeper will run but find nothing");
        }
    }

    /**
     * Scheduled every 5 minutes. ShedLock holds the lock for up to 4 minutes -
     * a crashed worker releases the lock before the next tick.
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000L, initialDelay = 90 * 1000L)
    @SchedulerLock(name = "markup_reserve_sweep", lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    public void sweepExpiredReservations() {
        if (!markupEnabled) {
            // Still update heartbeat so the staleness alert doesn't fire when
            // markup is intentionally disabled (CE deploys).
            lastRunEpochSeconds.set(System.currentTimeMillis() / 1000L);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<CreditLedgerEntry> expired = ledgerRepository.findExpiredReserves(
                now, PageRequest.of(0, BATCH_LIMIT));
        if (expired.isEmpty()) {
            lastRunEpochSeconds.set(System.currentTimeMillis() / 1000L);
            return;
        }
        log.info("Sweeper found {} expired reservation(s)", expired.size());

        int released = 0;
        int alreadyDone = 0;
        int failures = 0;
        for (CreditLedgerEntry row : expired) {
            try {
                CreditService.ReleaseOutcome outcome = creditService.releaseReservation(
                        row.getSourceId(),
                        "auto-release-timeout: reserve TTL elapsed (sourceId=" + row.getSourceId() + ")");
                switch (outcome) {
                    case RELEASED -> released++;
                    case ALREADY_RELEASED, ALREADY_COMMITTED -> alreadyDone++;
                }
            } catch (Exception e) {
                failures++;
                // Fail-soft: log + continue. A row that fails this tick will be
                // retried next tick (still past expires_at, still in the index).
                log.warn("Sweeper failed to release sourceId={}: {}", row.getSourceId(), e.getMessage());
            }
        }
        log.info("Sweeper pass: released={} alreadyDone={} failures={} of {}",
                released, alreadyDone, failures, expired.size());

        // Heartbeat updated only on a "clean enough" pass. Massive failures
        // would otherwise mask a real outage by keeping the gauge fresh.
        if (failures < expired.size()) {
            lastRunEpochSeconds.set(System.currentTimeMillis() / 1000L);
        }
    }

    /**
     * Exposed for {@code MetricsConfig} to bind as a Micrometer gauge:
     * {@code markup_reserve_sweep_last_run_epoch_seconds}. Grafana alert:
     * {@code time() - markup_reserve_sweep_last_run_epoch_seconds > 7200}
     * (2h staleness).
     */
    public long getLastRunEpochSeconds() {
        return lastRunEpochSeconds.get();
    }
}
