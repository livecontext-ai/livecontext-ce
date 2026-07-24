package com.apimarketplace.common.storage.exception;

/**
 * Thrown when a payload cannot be serialized to JSON for storage.
 *
 * <p>Replaces the old silent {@code toString()} fallback in
 * {@code StorageEntity.serializeToJson}, which wrote non-JSON garbage into a
 * JSONB column on Jackson failure (a silent-corruption vector: the row looked
 * persisted but its data column was unreadable). Callers now get an honest,
 * discriminable failure instead: the step-persistence layer maps this to a
 * non-retryable SERIALIZATION cause (retrying a data-shaped failure cannot
 * succeed) and flips the step row to FAILED with a message naming the cause.
 */
public class StorageSerializationException extends RuntimeException {

    public StorageSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
