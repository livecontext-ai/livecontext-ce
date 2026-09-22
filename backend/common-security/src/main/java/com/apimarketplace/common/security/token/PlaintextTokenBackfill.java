package com.apimarketplace.common.security.token;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Moves capability-token rows written before 2026-09-17 (plaintext token, no hash) to the
 * encrypted + hashed form, in place, without touching any other column.
 *
 * <p><b>The cutover is one-way, so it is deliberately not immediate.</b> A row rewritten to
 * {@code ENC:} can no longer be resolved by a replica running the previous image, which
 * compares the column to the plaintext. During a rolling deploy old and new replicas serve the
 * same traffic for minutes, so the startup pass runs after a delay
 * ({@link #scheduleMigrateAll}, {@code token-at-rest.backfill.delay-seconds}, 10 minutes by
 * default) that outlasts a rollout; meanwhile the new code resolves legacy rows through a
 * read-only plaintext fallback ({@link TokenAtRest#lookup}), which rewrites nothing. Rolling the
 * image back inside that window costs only the rows the new code happened to SAVE meanwhile: no
 * entity is {@code @DynamicUpdate}, so an ordinary JPA update writes the whole row and encrypts
 * that token early. After the pass has run, the previous image reads no token at all and the
 * only way back is a database restore.
 *
 * <p>Entry points:
 * <ul>
 *   <li>{@link #scheduleMigrateAll} / {@link #migrateAll}: pages every row whose hash is still
 *       null, in primary-key order. Idempotent and replica-safe: the UPDATE is guarded by
 *       {@code AND <hash> IS NULL}, so two pods racing on one row both succeed and the second
 *       changes nothing. A row the current key cannot read (ciphertext under another key) is
 *       logged and skipped, never aborts the table. Never throws.</li>
 *   <li>{@link #migrateByPlaintext}: heals the one row still holding a plaintext, for WRITE
 *       paths that must reach a legacy row by hash (deactivating a share link).</li>
 *   <li>{@link #mayHaveLegacyRows}: false once a pass has drained a table with no failure, so
 *       the plaintext fallback stops costing a second query on every public miss.</li>
 * </ul>
 * Both rewriting entry points refuse to run on ephemeral encryption material (no key
 * configured): encrypting stored rows with a key that dies with the process would be data loss.
 *
 * <p>The SQL is assembled from {@link TableSpec} constants declared by the owning service, so
 * this class names no schema and each service keeps writing only its own.
 */
public final class PlaintextTokenBackfill {

    private static final Logger log = LoggerFactory.getLogger(PlaintextTokenBackfill.class);

    /** Rows per page; small on purpose, the population is dozens in cloud and hundreds in CE. */
    static final int PAGE_SIZE = 200;
    /** Hard stop so a table nothing can update does not spin a startup thread forever. */
    static final int MAX_PAGES = 5_000;

    /**
     * @param table       fully-qualified table ({@code trigger.standalone_webhooks})
     * @param idColumn    primary key column: bigint, uuid or varchar, anything with a total order
     * @param tokenColumn the (now encrypted) token column
     * @param hashColumn  the lookup hash column added beside it
     */
    public record TableSpec(String table, String idColumn, String tokenColumn, String hashColumn) {
        public TableSpec {
            Objects.requireNonNull(table);
            Objects.requireNonNull(idColumn);
            Objects.requireNonNull(tokenColumn);
            Objects.requireNonNull(hashColumn);
        }

        String key() {
            return table + "." + tokenColumn;
        }
    }

    /** Outcome of one table pass. */
    public record Result(int migrated, int failed, boolean drained) {}

    private final JdbcTemplate jdbc;
    /** Tables proven free of legacy rows in this process; absent = unknown = assume some remain. */
    private final ConcurrentHashMap<String, Boolean> drained = new ConcurrentHashMap<>();
    /** The pending delayed pass, so a closing context can cancel it instead of leaking it. */
    private final AtomicReference<Thread> pending = new AtomicReference<>();

    public PlaintextTokenBackfill(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * Run {@link #migrateAll} on a daemon thread after {@code delay}. A zero or negative delay
     * runs inline (tests, single-container CE where no other replica exists).
     */
    public void scheduleMigrateAll(List<TableSpec> specs, Duration delay) {
        if (delay == null || delay.isZero() || delay.isNegative()) {
            migrateAll(specs);
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            migrateAll(specs);
        }, "token-at-rest-backfill");
        t.setDaemon(true);
        pending.set(t);
        t.start();
        log.info("Token-at-rest backfill of {} table(s) scheduled in {} (rollout window)", specs.size(), delay);
    }

    /**
     * Cancel a pass that has not started yet. Called when the Spring context closes: without it
     * every cached test context leaks a thread that wakes minutes later and runs JDBC against a
     * closed pool, and a container stopped inside the delay leaves the thread nothing to do.
     * A pass already running is left alone (it is idempotent and guarded by {@code IS NULL}).
     */
    public void cancelPending() {
        Thread t = pending.getAndSet(null);
        if (t != null && t.isAlive()) {
            t.interrupt();
        }
    }

    /** Pass over every spec. Returns the number of rows rewritten. Never throws. */
    public int migrateAll(List<TableSpec> specs) {
        if (TokenAtRest.isUsingEphemeralMaterial()) {
            log.warn("Token-at-rest backfill skipped: no credential encryption key is configured, so this process "
                    + "runs on ephemeral material and rewriting stored tokens with it would make them unreadable "
                    + "after the next restart. Set CREDENTIAL_ENCRYPTION_PASSWORD/SALT.");
            return 0;
        }
        int total = 0;
        for (TableSpec spec : specs) {
            try {
                Result r = migrate(spec);
                total += r.migrated();
                if (r.migrated() > 0 || r.failed() > 0) {
                    log.info("Token-at-rest backfill of {}: {} row(s) moved to encrypted+hashed storage, {} skipped, drained={}",
                            spec.key(), r.migrated(), r.failed(), r.drained());
                }
            } catch (RuntimeException e) {
                log.warn("Token-at-rest backfill of {} failed; its plaintext rows stay readable through the "
                        + "legacy fallback and the next start retries (cause: {})", spec.key(), e.toString());
            }
        }
        return total;
    }

    /** One table, paged in primary-key order. Throws on a database error so {@link #migrateAll} can report it. */
    public Result migrate(TableSpec spec) {
        String where = " WHERE " + spec.hashColumn() + " IS NULL AND " + spec.tokenColumn() + " IS NOT NULL AND "
                + spec.tokenColumn() + " <> ''";
        String firstPage = "SELECT " + spec.idColumn() + " AS id, " + spec.tokenColumn() + " AS tok FROM " + spec.table()
                + where + " ORDER BY " + spec.idColumn() + " LIMIT " + PAGE_SIZE;
        String nextPage = "SELECT " + spec.idColumn() + " AS id, " + spec.tokenColumn() + " AS tok FROM " + spec.table()
                + where + " AND " + spec.idColumn() + " > ? ORDER BY " + spec.idColumn() + " LIMIT " + PAGE_SIZE;
        int migrated = 0;
        int failed = 0;
        Object lastId = null;
        boolean drainedNow = false;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<Map<String, Object>> rows = lastId == null
                    ? jdbc.queryForList(firstPage)
                    : jdbc.queryForList(nextPage, lastId);
            if (rows.isEmpty()) {
                drainedNow = true;
                break;
            }
            for (Map<String, Object> row : rows) {
                lastId = row.get("id");
                try {
                    migrated += rewrite(spec, lastId, (String) row.get("tok"));
                } catch (RuntimeException e) {
                    // Ciphertext under another key, or a row-level database error: skip it, keep
                    // going. The row stays readable by whatever wrote it; it is counted so the
                    // table is never reported as drained.
                    failed++;
                    log.warn("Token-at-rest backfill of {}: row id={} left as-is (cause: {})", spec.key(), lastId, e.toString());
                }
            }
        }
        boolean fullyDrained = drainedNow && failed == 0;
        if (fullyDrained) {
            drained.put(spec.key(), Boolean.TRUE);
        }
        return new Result(migrated, failed, fullyDrained);
    }

    /**
     * True until a pass in this process has proven the table holds no legacy row. Services gate
     * the plaintext fallback on it, so a public miss costs one query once the migration is done.
     */
    public boolean mayHaveLegacyRows(TableSpec spec) {
        return !drained.getOrDefault(spec.key(), Boolean.FALSE);
    }

    /**
     * The read-only plaintext fallback of a lookup, gate included: runs {@code query} only while
     * this table may still hold a legacy row, so a public miss costs one query once the pass has
     * drained it. This is the ONE place the gate lives; the per-service components delegate here
     * rather than each re-implementing {@code mayHaveLegacyRows ? ... : empty}.
     */
    public <T> Optional<T> findLegacy(TableSpec spec, String plaintext, Function<String, Optional<T>> query) {
        if (plaintext == null || plaintext.isBlank() || !mayHaveLegacyRows(spec)) {
            return Optional.empty();
        }
        return query.apply(plaintext);
    }

    /**
     * Heal the one row still holding {@code plaintext} in clear. For write paths that address a
     * legacy row by hash; returns true when a row was rewritten and the hash query should be retried.
     */
    public boolean migrateByPlaintext(TableSpec spec, String plaintext) {
        if (plaintext == null || plaintext.isBlank() || TokenAtRest.isUsingEphemeralMaterial()
                || !mayHaveLegacyRows(spec)) {
            return false;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT " + spec.idColumn() + " AS id FROM " + spec.table()
                            + " WHERE " + spec.tokenColumn() + " = ? AND " + spec.hashColumn() + " IS NULL LIMIT 1",
                    plaintext);
            if (rows.isEmpty()) {
                return false;
            }
            return rewrite(spec, rows.get(0).get("id"), plaintext) > 0;
        } catch (RuntimeException e) {
            log.warn("Token-at-rest heal on {} failed (cause: {})", spec.key(), e.toString());
            return false;
        }
    }

    private int rewrite(TableSpec spec, Object id, String stored) {
        if (stored == null || stored.isBlank()) {
            return 0;
        }
        // A row may already carry ciphertext with a null hash (written by the new code in the same
        // second the column was added); hash the PLAINTEXT in that case too.
        String plaintext = TokenAtRest.decrypt(stored);
        String encrypted = TokenAtRest.encrypt(plaintext);
        String hash = TokenAtRest.hash(plaintext);
        return jdbc.update(
                "UPDATE " + spec.table() + " SET " + spec.tokenColumn() + " = ?, " + spec.hashColumn() + " = ?"
                        + " WHERE " + spec.idColumn() + " = ? AND " + spec.hashColumn() + " IS NULL",
                encrypted, hash, id);
    }
}
