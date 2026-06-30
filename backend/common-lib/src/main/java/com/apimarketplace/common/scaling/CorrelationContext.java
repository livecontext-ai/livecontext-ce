package com.apimarketplace.common.scaling;

import java.util.Optional;

/**
 * Thread-local utility for propagating correlation IDs across execution boundaries.
 * Used to trace requests through the distributed priority queue and execution pipeline.
 */
public final class CorrelationContext {

    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();

    private CorrelationContext() {
        // Utility class
    }

    /**
     * Set the correlation ID for the current thread.
     *
     * @param correlationId the correlation ID to propagate
     */
    public static void set(String correlationId) {
        CORRELATION_ID.set(correlationId);
    }

    /**
     * Get the correlation ID for the current thread.
     *
     * @return the correlation ID, or empty if not set
     */
    public static Optional<String> get() {
        return Optional.ofNullable(CORRELATION_ID.get());
    }

    /**
     * Clear the correlation ID from the current thread.
     * Should be called in finally blocks to prevent memory leaks.
     */
    public static void clear() {
        CORRELATION_ID.remove();
    }
}
