package com.apimarketplace.orchestrator.services.streaming;

import java.util.OptionalLong;
import java.util.function.ToLongFunction;

/**
 * Storage strategy for the per-run monotonic WS sequence counter used by
 * {@link WsEventSequencer}.
 *
 * <p><b>Why a strategy</b>: in single-pod / {@code scaling.backend=memory} mode a
 * per-pod {@code AtomicLong} is sufficient. But prod runs the orchestrator with
 * {@code replicas: 2} and {@code scaling.backend=redis}; with a Redis work queue
 * <b>any</b> pod can process a step / epoch / signal-resolution for the same
 * {@code runId}. A per-pod counter then diverges: pod A reaches seq=363 while pod
 * B (seeded from the lagging {@code workflow_runs.last_event_seq}) re-emits
 * seq=221..  The frontend's strict-{@code <} {@code lastKnownSeq} guard drops the
 * lower-seq events and REST refreshes, freezing the run page and desyncing nodes
 * (e.g. a Send Email that ran but never showed completed). See
 * {@code the project docs} #2.
 *
 * <p>The {@link RedisRunSeqBackend} variant makes the counter a single shared
 * atomic across all pods, restoring a globally-monotonic seq. The
 * {@link InMemoryRunSeqBackend} variant preserves the original single-pod
 * behavior for {@code scaling.backend=memory} (local dev, CE monolith).
 *
 * <p><b>Seeding</b>: {@code seedLoader} supplies the durable cross-restart seed
 * (the DB-persisted {@code workflow_runs.last_event_seq} high-water mark). It is
 * invoked at most once per fresh counter (never on the hot path) so a pod that
 * starts cold for a runId resumes above the frontend's already-known seq instead
 * of restarting at 1.
 */
interface RunSeqBackend {

    /**
     * Return the next strictly-increasing seq for {@code runId}.
     *
     * @param runId      run identifier; never null
     * @param seedLoader supplies the DB high-water seed when this backend has no
     *                   value for {@code runId} yet (called lazily, at most once
     *                   per fresh counter)
     * @return the next seq ({@code > } any previously returned value for this run)
     */
    long next(String runId, ToLongFunction<String> seedLoader);

    /**
     * Return the current seq for {@code runId} WITHOUT bumping it. Must not create
     * or leak any per-run state for a {@code runId} this backend has never bumped
     * (REST {@code /state} calls this for arbitrary, possibly-404 runIds). Falls
     * back to {@code seedLoader} when no value is known.
     */
    long current(String runId, ToLongFunction<String> seedLoader);

    /**
     * Return the last value THIS pod emitted for {@code runId}, if any, without
     * any DB / Redis seed lookup. Used by {@code cleanupRun} to flush the
     * high-water mark to the DB before purging - empty when this pod never
     * touched the run.
     */
    OptionalLong peek(String runId);

    /** Purge all state for {@code runId} (called when the run/epoch closes). */
    void remove(String runId);

    /** Number of runs this backend currently tracks locally (cache metric). */
    int size();
}
