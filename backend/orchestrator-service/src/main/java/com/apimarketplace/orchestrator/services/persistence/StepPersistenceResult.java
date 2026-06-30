package com.apimarketplace.orchestrator.services.persistence;

import java.util.UUID;

/**
 * Result of step persistence operation.
 * Contains success status and the storage ID if persistence succeeded.
 *
 * @param persisted true if the step was persisted, false if duplicate or error
 * @param storageId the UUID of the stored output, or null if not persisted
 */
public record StepPersistenceResult(
    boolean persisted,
    UUID storageId
) {
    /**
     * Creates a successful persistence result.
     */
    public static StepPersistenceResult success(UUID storageId) {
        return new StepPersistenceResult(true, storageId);
    }

    /**
     * Creates a failed or duplicate persistence result.
     */
    public static StepPersistenceResult notPersisted() {
        return new StepPersistenceResult(false, null);
    }
}
