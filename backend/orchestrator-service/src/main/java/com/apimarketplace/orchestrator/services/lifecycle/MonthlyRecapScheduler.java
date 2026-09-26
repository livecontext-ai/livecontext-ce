package com.apimarketplace.orchestrator.services.lifecycle;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.AuthClient.LifecycleEventResult;
import com.apimarketplace.common.web.AppEditionProvider;
import jakarta.annotation.PreDestroy;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The monthly recap email: on the 1st of each month, everyone who had at least one successful
 * run in the previous calendar month gets ONE {@code recap.monthly} event (runs, active
 * workflows, trophies unlocked). auth-service localizes it and Resend sends it, to people who
 * opted in to marketing email only (the consent check lives in the automation).
 *
 * <p><b>Once per person and month</b>, across pods and restarts: each recap is claimed in
 * {@code orchestrator.lifecycle_monthly_recaps} before it is sent and the candidate query skips
 * claimed people. A send that auth-service could not take ({@code RETRY_LATER}) gives its claim
 * back, so the next slot of the day retries it; a refused one (unknown user) keeps it. So does
 * one auth-service did not send because its lifecycle emails are off ({@code INACTIVE}): the claim
 * is given back and the pass stops, so a kill switch flipped on the 1st never consumes the month.
 *
 * <p><b>Paced</b> at {@code lifecycle.recap.max-per-second} (default 5): auth-service funnels
 * every lifecycle event through ONE Resend worker (Resend allows 10 requests/s, a recap costs
 * two), and it answers 503 rather than silently dropping a recap when that queue is half full.
 * On a 503 this pass backs off and retries the same person, and stops after
 * {@link #MAX_ATTEMPTS} so an auth-service outage costs nothing but a later slot.
 *
 * <p>The pass runs on its own thread, not the shared scheduler pool (3 threads, which the
 * signal pollers need), and never throws. A no-op in a self-hosted edition, where lifecycle
 * emails do not exist.
 */
@Component
public class MonthlyRecapScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonthlyRecapScheduler.class);

    static final String EVENT = "recap.monthly";
    static final int MAX_ATTEMPTS = 3;
    static final Duration BACKOFF = Duration.ofSeconds(30);

    /** Waits between sends; a seam so tests run instantly and can read the pacing. */
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** What one pass did. */
    record PassResult(int sent, int refused, int deferred, boolean stopped) {
    }

    private final MonthlyRecapStore store;
    private final AuthClient authClient;
    private final Clock clock;
    private final Sleeper sleeper;
    private final boolean enabled;
    private final Duration spacing;
    private final ExecutorService worker;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    public MonthlyRecapScheduler(MonthlyRecapStore store, AuthClient authClient, AppEditionProvider editionProvider,
                                 @Value("${lifecycle.recap.enabled:true}") boolean enabled,
                                 @Value("${lifecycle.recap.max-per-second:5}") int maxPerSecond) {
        this(store, authClient, Clock.systemUTC(), d -> Thread.sleep(d.toMillis()),
                enabled && (editionProvider == null || !editionProvider.isSelfHosted()), maxPerSecond,
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "lifecycle-monthly-recap");
                    t.setDaemon(true);
                    return t;
                }));
    }

    MonthlyRecapScheduler(MonthlyRecapStore store, AuthClient authClient, Clock clock, Sleeper sleeper,
                          boolean enabled, int maxPerSecond, ExecutorService worker) {
        this.store = store;
        this.authClient = authClient;
        this.clock = clock;
        this.sleeper = sleeper;
        this.enabled = enabled;
        this.spacing = Duration.ofMillis(1000L / Math.max(1, Math.min(maxPerSecond, 5)));
        this.worker = worker;
    }

    /**
     * 09:00 UTC on the 1st, then 13:00 and 17:00 for whatever the first pass could not hand
     * over. ShedLock keeps a slot on one pod; {@code lockAtLeastFor} covers the pass, which
     * runs on its own thread after this method returns.
     */
    @Scheduled(cron = "${lifecycle.recap.cron:0 0 9,13,17 1 * *}", zone = "UTC")
    @SchedulerLock(name = "lifecycle-monthly-recap", lockAtLeastFor = "PT2H", lockAtMostFor = "PT3H")
    public void scheduled() {
        if (!enabled) return;
        YearMonth previous = YearMonth.now(clock.withZone(ZoneOffset.UTC)).minusMonths(1);
        try {
            worker.execute(() -> sendRecaps(previous));
        } catch (Exception e) {
            log.warn("[lifecycle] monthly recap for {} not started: {}", previous, e.toString());
        }
    }

    /** One pass over {@code month}. Synchronous; never throws; skipped if a pass is already running here. */
    PassResult sendRecaps(YearMonth month) {
        if (!running.compareAndSet(false, true)) return new PassResult(0, 0, 0, true);
        int sent = 0;
        int refused = 0;
        int deferred = 0;
        try {
            List<MonthlyRecapStore.Recap> recaps = store.candidates(month);
            log.info("[lifecycle] monthly recap {}: {} person(s) to send", month, recaps.size());
            for (MonthlyRecapStore.Recap recap : recaps) {
                if (!store.claim(recap.tenantId(), month)) continue;
                LifecycleEventResult result;
                try {
                    result = send(recap, month);
                } catch (InterruptedException ie) {
                    // Interrupted in a back-off (shutdown): nothing was accepted for this person,
                    // so give the claim back before stopping, or the month is never sent to them.
                    releaseQuietly(recap.tenantId(), month);
                    Thread.currentThread().interrupt();
                    log.info("[lifecycle] monthly recap {} interrupted after {} sent", month, sent);
                    return new PassResult(sent, refused, deferred + 1, true);
                }
                if (result == LifecycleEventResult.ACCEPTED) {
                    sent++;
                } else if (result == LifecycleEventResult.REFUSED) {
                    refused++;
                } else if (result == LifecycleEventResult.INACTIVE) {
                    // Lifecycle emails are off in auth-service: nothing was sent, so the month is
                    // not consumed. Give the claim back and stop: every other send would be a no-op.
                    releaseQuietly(recap.tenantId(), month);
                    deferred++;
                    log.warn("[lifecycle] monthly recap {} stopped after {} sent: lifecycle emails are off in auth-service",
                            month, sent);
                    return new PassResult(sent, refused, deferred, true);
                } else {
                    // auth-service cannot take it now: give the claim back and stop the pass,
                    // the next slot starts again from everyone still unclaimed.
                    releaseQuietly(recap.tenantId(), month);
                    deferred++;
                    log.warn("[lifecycle] monthly recap {} paused after {} sent: auth-service busy or unreachable",
                            month, sent);
                    return new PassResult(sent, refused, deferred, true);
                }
                sleeper.sleep(spacing);
            }
            log.info("[lifecycle] monthly recap {}: {} sent, {} refused", month, sent, refused);
            return new PassResult(sent, refused, deferred, false);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new PassResult(sent, refused, deferred, true);
        } catch (Exception e) {
            log.warn("[lifecycle] monthly recap {} failed after {} sent: {}", month, sent, e.toString());
            return new PassResult(sent, refused, deferred, true);
        } finally {
            running.set(false);
        }
    }

    /**
     * Gives a claim back, never throwing: a failing release must not end the pass on the generic
     * catch with nothing saying which person now holds a claim for a recap that was never sent.
     */
    private void releaseQuietly(String tenantId, YearMonth month) {
        try {
            store.release(tenantId, month);
        } catch (RuntimeException e) {
            log.warn("[lifecycle] monthly recap {} claim of {} could not be released (not sent, will not be retried "
                    + "until the claim row is removed): {}", month, tenantId, e.toString());
        }
    }

    /** Up to {@link #MAX_ATTEMPTS} tries, {@link #BACKOFF} apart, while auth-service answers RETRY_LATER. */
    private LifecycleEventResult send(MonthlyRecapStore.Recap recap, YearMonth month) throws InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("month", month.toString());
        payload.put("runs", recap.runs());
        payload.put("active_workflows", recap.activeWorkflows());
        payload.put("badges", recap.badges());
        LifecycleEventResult result = LifecycleEventResult.RETRY_LATER;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                result = authClient.emitLifecycleEvent(recap.tenantId(), EVENT, payload);
            } catch (RuntimeException e) {
                // AuthClient never throws by contract; if it ever did, the claim must still be released.
                result = LifecycleEventResult.RETRY_LATER;
            }
            if (result != LifecycleEventResult.RETRY_LATER) return result;
            if (attempt < MAX_ATTEMPTS) sleeper.sleep(BACKOFF);
        }
        return result;
    }

    Duration spacing() {
        return spacing;
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
