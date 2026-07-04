package com.apimarketplace.agent.catalog.bundle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a manual bundle "sync now" tick OFF the request thread and holds a small,
 * in-memory RUNNING state so the admin UI can survive a page navigation: a poll
 * of the sync-status endpoint reports {@code running=true} while a tick is in
 * flight, so the loading indicator RESUMES on remount instead of being lost with
 * the button's local React state.
 *
 * <p><b>Why in-memory (no DB column / migration):</b> the manual-sync scheduler
 * bean only exists on a CE (self-hosted) install, and a CE monolith is a single
 * process - so a per-JVM flag is authoritative. On cloud (no scheduler bean) the
 * sync-now endpoint 503s and this runner is never exercised.
 *
 * <p><b>Cancel is best-effort:</b> {@link #cancel()} interrupts the worker and
 * flips {@code running} to false immediately so the UI stops spinning. The
 * underlying tick (an HTTP fetch + a DB apply) may already be mid-apply; the DB
 * transaction still commits or rolls back atomically, and the NEXT status poll
 * reflects the true outcome. "Stop" therefore means "stop waiting / free the UI",
 * not "guarantee the in-flight fetch is aborted".
 *
 * <p>Reusable across bundle surfaces (model / skill / API catalog): each surface
 * injects its own instance-scoped runner and passes its scheduler tick as the
 * {@link Runnable}. A second start while one is in flight is a no-op (returns
 * false) so a double-click or a concurrent cron firing never stacks ticks.
 */
@Slf4j
@Component
public class BundleSyncRunner {

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "bundle-sync-now");
                t.setDaemon(true);
                return t;
            });

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private volatile Instant startedAt;
    private volatile Future<?> inFlight;

    /** Immutable snapshot of the runner state for the status view. */
    public record RunState(boolean running, Instant startedAt, boolean cancelRequested) {}

    /**
     * Start {@code tick} on the worker thread if none is already running.
     *
     * @return true if this call started the tick; false if one was already in
     *         flight (the caller should treat that as "already syncing", not an
     *         error).
     */
    public boolean startAsync(Runnable tick) {
        if (!running.compareAndSet(false, true)) {
            return false; // already running - ignore the duplicate trigger
        }
        cancelRequested.set(false);
        startedAt = Instant.now();
        inFlight = executor.submit(() -> {
            try {
                tick.run();
            } catch (Exception e) {
                // The tick persists its own failure on the sync-status row; never
                // let it escape and kill the single worker thread.
                log.warn("Manual bundle sync tick failed (already persisted): {}", e.getMessage());
            } finally {
                running.set(false);
                inFlight = null;
            }
        });
        return true;
    }

    /**
     * Best-effort stop: request cancellation, interrupt the worker, and flip the
     * running flag off now so the UI stops spinning. See the class note on the
     * best-effort semantics.
     */
    public void cancel() {
        if (!running.get()) return;
        cancelRequested.set(true);
        Future<?> f = inFlight;
        if (f != null) f.cancel(true);
        running.set(false);
    }

    public RunState state() {
        return new RunState(running.get(), startedAt, cancelRequested.get());
    }
}
