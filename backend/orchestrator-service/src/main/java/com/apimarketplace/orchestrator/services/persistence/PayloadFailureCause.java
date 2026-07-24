package com.apimarketplace.orchestrator.services.persistence;

/**
 * Discriminated cause of a step-output payload persist failure.
 *
 * <p>Produced by {@link StepPayloadService} (which used to flatten every
 * failure to a silent {@code null} storageId) and consumed by
 * {@link StepDataPersistenceService} to write an honest FAILED row whose
 * {@code error_message} names the actual cause - the operator-facing half of
 * the "a step whose output is not durable is NOT COMPLETED" contract.
 */
public enum PayloadFailureCause {

    /**
     * Storage quota hard limit reached ({@code QuotaExceededException}).
     * Tenant action required; retrying cannot succeed, so it is never retried.
     */
    QUOTA_EXCEEDED("storage quota exceeded - free space or raise the limit"),

    /**
     * The payload itself cannot be stored: JSON serialization failed
     * ({@code StorageSerializationException}) or the database rejected the
     * data shape (SQLSTATE class 22, e.g. 22P05). Data-shaped, so never
     * retried - the same bytes fail the same way every time.
     */
    SERIALIZATION("step output could not be serialized for storage"),

    /**
     * A transient storage error (I/O, connection, deadlock, ...) persisted
     * across the bounded retry. Infra action required.
     */
    TRANSIENT_EXHAUSTED("storage write failed after retries");

    private final String userMessage;

    PayloadFailureCause(String userMessage) {
        this.userMessage = userMessage;
    }

    /**
     * Human-readable cause fragment for the step row's {@code error_message}
     * (and the rewritten in-memory failure message).
     */
    public String userMessage() {
        return userMessage;
    }
}
