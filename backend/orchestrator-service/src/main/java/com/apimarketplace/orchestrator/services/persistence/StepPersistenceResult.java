package com.apimarketplace.orchestrator.services.persistence;

import java.util.UUID;

/**
 * Result of a step persistence operation.
 *
 * <p>Historically a binary {@code success/notPersisted} pair, which conflated
 * two very different "not persisted" cases (benign duplicate vs insert error)
 * AND had no way to express the third state that motivated this redesign:
 * <b>the row landed but its output payload was lost</b> (storage quota,
 * transient outage after retries, serialization failure). That state used to
 * masquerade as {@code success(null)} - a step could lose its entire output
 * blob and still report COMPLETED. It is now first-class:
 * {@link Disposition#PAYLOAD_LOST} carries the {@link PayloadFailureCause} so
 * {@code StepCompletionOrchestrator} can rewrite the in-memory result to
 * FAILED (traversal truth) on top of the FAILED row (row truth).
 *
 * @param disposition       what actually happened (see {@link Disposition})
 * @param storageId         the stored output UUID (only for {@code PERSISTED})
 * @param payloadLossCause  the discriminated loss cause (only for {@code PAYLOAD_LOST})
 */
public record StepPersistenceResult(
    Disposition disposition,
    UUID storageId,
    PayloadFailureCause payloadLossCause
) {

    /** The four distinct outcomes of a step-row persistence attempt. */
    public enum Disposition {
        /** Row inserted, output payload durably stored. */
        PERSISTED,
        /** Row NOT inserted: the v6 unique index already holds this logical row (benign). */
        DUPLICATE,
        /** Row NOT inserted: an error prevented the insert (run unresolvable, entity build, DB error). */
        ERROR,
        /**
         * Row inserted as FAILED because the output payload could not be
         * stored - the step's result was rewritten from success to failure
         * (row truth, tier 1 of the payload-loss contract).
         */
        PAYLOAD_LOST
    }

    /**
     * Creates a successful persistence result (row inserted, payload stored).
     */
    public static StepPersistenceResult success(UUID storageId) {
        return new StepPersistenceResult(Disposition.PERSISTED, storageId, null);
    }

    /** Row skipped by the v6 unique index - a benign duplicate. */
    public static StepPersistenceResult duplicate() {
        return new StepPersistenceResult(Disposition.DUPLICATE, null, null);
    }

    /** Row not inserted because of an error (NOT a duplicate). */
    public static StepPersistenceResult error() {
        return new StepPersistenceResult(Disposition.ERROR, null, null);
    }

    /**
     * Row inserted, but the output payload is NOT durable: the row was flipped
     * to FAILED with an error_message naming {@code cause}.
     */
    public static StepPersistenceResult payloadLost(PayloadFailureCause cause) {
        return new StepPersistenceResult(Disposition.PAYLOAD_LOST, null, cause);
    }

    /**
     * True when a row actually landed (PERSISTED or PAYLOAD_LOST). Existing
     * callers key NodeCounts / billing / merge recording off this - a
     * payload-lost row must count (as FAILED) exactly like any other row.
     */
    public boolean persisted() {
        return disposition == Disposition.PERSISTED || disposition == Disposition.PAYLOAD_LOST;
    }

    /** True when the row landed but its output payload was lost. */
    public boolean payloadLost() {
        return disposition == Disposition.PAYLOAD_LOST;
    }
}
