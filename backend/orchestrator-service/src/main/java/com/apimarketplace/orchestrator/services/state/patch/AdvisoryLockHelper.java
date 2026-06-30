package com.apimarketplace.orchestrator.services.state.patch;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Plan v4 §1.6 + §26 - central helper for acquiring the per-run
 * {@code pg_advisory_xact_lock} on methods annotated
 * {@link AdvisoryLockHolding}.
 *
 * <p>Namespace allocation (plan §26): top byte {@code 0x01} reserved for
 * state-snapshot coordination locks. Lock key =
 * {@code 0x0100000000000000L | (hashtextextended(runId, 0) & 0x00FFFFFFFFFFFFFFFFL)}
 * - collision-free in the 56-bit hash space at 10K runs (per plan §26).
 *
 * <p>Lock is {@code xact_lock} - automatically released at transaction
 * commit/rollback. Callers MUST be in a Spring-managed {@code @Transactional}
 * boundary; without one, the lock would leak to subsequent operations on
 * the same connection.
 *
 * <p>{@code @ConditionalOnProperty} for graceful degradation: when the
 * feature flag is OFF the helper logs a warning and skips the acquire
 * (caller's pessimistic row-lock or CAS path still provides correctness).
 * Default ON to match plan §1.6 spec.
 *
 * <p>Why centralize: 8 methods need the same SQL incantation; copy-paste
 * is fragile (hashtextextended PG version, type cast nuances). One helper
 * + ArchUnit rule "@AdvisoryLockHolding methods call this exactly once".
 */
@Component
public class AdvisoryLockHelper {

    private static final Logger log = LoggerFactory.getLogger(AdvisoryLockHelper.class);

    /** Plan v4 §26 - top byte 0x01 reserved for state-snapshot coordination. */
    static final long NAMESPACE_PREFIX = 0x0100000000000000L;
    /**
     * 56-bit mask preserving low 7 bytes of the hash. Initial 64-bit literal
     * (0x00FFFFFFFFFFFFFFFFL = -1L Java long all-ones) was wrong - it OR'd
     * with prefix gave 0xFFFF…L for any hash with top byte set, breaking the
     * "top byte 0x01 reserved" contract. Fixed audit A M1 / B M2.
     */
    static final long NAMESPACE_MASK = 0x00FFFFFFFFFFFFFFL;

    private final NamedParameterJdbcTemplate jdbc;
    private final boolean enabled;
    private final Counter acquireCounter;
    private final Counter skipDisabledCounter;
    private final Counter errorCounter;
    private final Timer acquireTimer;

    public AdvisoryLockHelper(
            NamedParameterJdbcTemplate jdbc,
            MeterRegistry meterRegistry,
            @Value("${orchestrator.optim.advisory-lock:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.acquireCounter = Counter.builder("orchestrator.advisory_lock.acquire_count")
                .description("Plan v4 §1.6 - successful pg_advisory_xact_lock acquisitions per @AdvisoryLockHolding entry")
                .register(meterRegistry);
        this.skipDisabledCounter = Counter.builder("orchestrator.advisory_lock.skip_disabled_count")
                .description("Plan v4 §1.6 - acquireForRun calls skipped because the feature flag is OFF")
                .register(meterRegistry);
        this.errorCounter = Counter.builder("orchestrator.advisory_lock.error_count")
                .description("Plan v4 §1.6 - pg_advisory_xact_lock acquire SQL errors (transient or perm-denied)")
                .register(meterRegistry);
        this.acquireTimer = Timer.builder("orchestrator.advisory_lock.acquire_duration")
                .description("Plan v4 §1.6 - wall-clock time to acquire the advisory lock; spikes indicate contention")
                .register(meterRegistry);
        log.info("[AdvisoryLockHelper] advisory-lock {} (namespace prefix=0x{})",
                enabled ? "ENABLED" : "DISABLED",
                Long.toHexString(NAMESPACE_PREFIX));
    }

    /**
     * Acquire the per-run advisory lock. Held until the surrounding
     * {@code @Transactional} commits or rolls back.
     *
     * <p>Caller contract:
     * <ol>
     *   <li>MUST be inside an active Spring tx (otherwise the lock leaks
     *       on the connection).</li>
     *   <li>MUST NOT call this from a hot-path thread under high
     *       throughput unless the runId is the same - concurrent
     *       different-runId acquires are independent and fine; concurrent
     *       same-runId acquires SERIALIZE (the point).</li>
     *   <li>MUST NOT issue HTTP/external calls while the lock is held -
     *       enforced by the {@code advisoryLockNoHttp} ArchUnit rule.</li>
     * </ol>
     *
     * <p>Failures (DB blip, perm denied) are swallowed + metric+log. The
     * caller's existing pessimistic row lock or CAS path provides the
     * correctness backstop - losing the advisory lock degrades to the
     * pre-§1.6 behavior, not to corruption.
     *
     * @param runId the run public ID; lock key derived via hashtextextended
     */
    public void acquireForRun(String runId) {
        if (!enabled) {
            skipDisabledCounter.increment();
            return;
        }
        if (runId == null || runId.isBlank()) {
            return;
        }
        long t0 = System.nanoTime();
        try {
            // pg_advisory_xact_lock takes a bigint key. We compute the key
            // in SQL (not Java) so the hash uses Postgres's own
            // hashtextextended(text, bigint) -- guaranteed available on
            // PG11+ (plan §32 requirement). 0x0100... namespace prefix is
            // OR'd into the low 56 bits.
            //
            // NB: hashtextextended returns bigint (signed 64-bit); we
            // mask + OR in SQL to avoid Java/Postgres signed-bit drift.
            //
            // jdbc.update() on a SELECT is a no-go: PG JDBC's executeUpdate()
            // aborts the transaction with a result-returned error when the
            // statement is a query. pg_advisory_xact_lock is a void function
            // but it's still wrapped in `SELECT ...`, returning one row with
            // one NULL column. We use a RowCallbackHandler that consumes
            // (and discards) the row - no allocation, no abort.
            jdbc.query(
                    "SELECT pg_advisory_xact_lock(("
                            + ":nsPrefix::bigint) | "
                            + "(hashtextextended(:runId, 0) & :nsMask::bigint))",
                    new MapSqlParameterSource("runId", runId)
                            .addValue("nsPrefix", NAMESPACE_PREFIX)
                            .addValue("nsMask", NAMESPACE_MASK),
                    (java.sql.ResultSet rs) -> { /* discard */ });
            acquireCounter.increment();
        } catch (DataAccessException ex) {
            errorCounter.increment();
            log.warn("[AdvisoryLockHelper] pg_advisory_xact_lock failed for runId={}: {} "
                            + "(degrading to row-lock-only; correctness preserved)",
                    runId, ex.getMessage());
        } finally {
            acquireTimer.record(System.nanoTime() - t0,
                    java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    boolean isEnabled() {
        return enabled;
    }
}
