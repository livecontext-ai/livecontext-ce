package com.apimarketplace.orchestrator.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.Temporal;

/**
 * Projection interface for optimized aggregated step queries.
 * Returns pre-aggregated data directly from SQL, avoiding full entity loading.
 *
 * Each row represents one (stepAlias, status) combination with:
 * - count of steps with that status
 * - representative toolId (first non-null)
 * - earliest startTime and latest endTime across all items
 *
 * Note: Timestamp fields use Object return type for database portability.
 * PostgreSQL returns Instant, H2 returns OffsetDateTime. Use {@link #toInstant(Object)}
 * to convert safely.
 */
public interface AggregatedStepProjection {

    /**
     * The step alias (e.g., "mcp:api_call", "core:decision")
     */
    String getStepAlias();

    /**
     * The status (e.g., "COMPLETED", "FAILED", "SKIPPED", "RUNNING")
     */
    String getStatus();

    /**
     * The count of steps with this alias and status
     */
    Long getCount();

    /**
     * Representative tool ID for this step alias
     */
    String getToolId();

    /**
     * Earliest start time across all items for this alias.
     * Returns Object for DB portability (PostgreSQL=Instant, H2=OffsetDateTime).
     */
    Object getMinStartTime();

    /**
     * Latest end time across all items for this alias.
     * Returns Object for DB portability (PostgreSQL=Instant, H2=OffsetDateTime).
     */
    Object getMaxEndTime();

    /**
     * Sum of individual execution times in milliseconds (SUM of endTime - startTime).
     * Represents actual cumulative execution time, not the span from first start to last end.
     */
    Long getSumExecutionTimeMs();

    /**
     * Converts a timestamp value from the projection to Instant.
     * Handles both PostgreSQL (Instant) and H2 (OffsetDateTime) return types.
     */
    static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant inst) return inst;
        if (value instanceof OffsetDateTime odt) return odt.toInstant();
        if (value instanceof Temporal temporal) return Instant.from(temporal);
        return null;
    }
}
