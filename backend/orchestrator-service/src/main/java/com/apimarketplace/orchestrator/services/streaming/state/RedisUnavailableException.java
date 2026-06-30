package com.apimarketplace.orchestrator.services.streaming.state;

/**
 * Thrown by the fail-CLOSED read variant {@code getRunningCountsOrThrow} when
 * Redis is unreachable / the read fails (P2.1.4).
 *
 * <p>The default {@code getRunningCounts} swallows Redis exceptions and returns
 * an empty map (fail-OPEN - sufficient for SSE/observability where stale-empty
 * is tolerable). The fail-CLOSED variant exists for the deferred-reset gate at
 * {@code ReusableTriggerService:1586}: if Redis cannot answer "is this epoch
 * still running?", we MUST defer the close (fail-closed = treat as "may still
 * have running") rather than risk closing prematurely while a node is actually
 * in flight.
 *
 * <p>Unchecked because callers either (a) actively defer on this signal - the
 * gate path catches and returns, OR (b) propagate up and abort the operation
 * - letting the runtime treat the absence-of-information as a degraded state.
 * Forcing a checked exception would over-burden the API surface; the fail-OPEN
 * default already handles the routine case.
 */
public class RedisUnavailableException extends RuntimeException {

    public RedisUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
