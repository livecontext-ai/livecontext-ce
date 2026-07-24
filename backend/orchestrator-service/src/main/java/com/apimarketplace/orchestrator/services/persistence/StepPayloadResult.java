package com.apimarketplace.orchestrator.services.persistence;

import java.util.UUID;

/**
 * Discriminated outcome of a step-payload storage write.
 *
 * <p>Replaces the old contract where {@link StepPayloadService} returned a
 * bare {@code UUID} and flattened EVERY failure (quota, serialization,
 * transient outage) into {@code null} - which nobody consumed
 * programmatically, so a step could lose its entire output blob and still
 * report COMPLETED. Callers now branch on {@link #stored()} and, on failure,
 * read the {@link #cause()} to write an honest FAILED row.
 *
 * @param storageId the storage UUID when the write succeeded, else null
 * @param cause     the discriminated failure cause when the write failed,
 *                  else null (exactly one of storageId/cause is non-null)
 */
public record StepPayloadResult(UUID storageId, PayloadFailureCause cause) {

    public static StepPayloadResult stored(UUID storageId) {
        if (storageId == null) {
            throw new IllegalArgumentException("stored() requires a non-null storageId");
        }
        return new StepPayloadResult(storageId, null);
    }

    public static StepPayloadResult failed(PayloadFailureCause cause) {
        if (cause == null) {
            throw new IllegalArgumentException("failed() requires a non-null cause");
        }
        return new StepPayloadResult(null, cause);
    }

    public boolean stored() {
        return storageId != null;
    }
}
