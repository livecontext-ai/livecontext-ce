package com.apimarketplace.common.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Startup sweep that encrypts, in place, every sensitive value still stored in clear inside a
 * JSONB credential map ({@code auth.credentials.credential_data},
 * {@code datasource.data_sources.source_config}).
 *
 * <p>Why it exists: {@link CredentialEncryptionService#decrypt} deliberately passes a value
 * without the {@code ENC:} prefix through unchanged, so a row written under the old 15-name
 * allow-list keeps WORKING after the detector widened, but it also keeps its AWS secret key in
 * clear until something rewrites it. Waiting for the user's next edit is not a plan; this pass
 * runs once per start, touches only rows where {@link CredentialEncryptionService#hasPlaintextSensitiveField}
 * is true, and leaves {@code updated_at} alone (the user did not change anything).
 *
 * <p>Write safety: the UPDATE is a compare-and-set on the row's own JSON text
 * ({@code WHERE <json>::text = <what we read>}), so a concurrent edit by the user wins and the
 * sweep simply skips that row. Already-encrypted values are never re-encrypted (the service
 * refuses to double-encrypt), and a value the current key cannot read is not a concern here:
 * only plaintext is touched. Never throws; a failure is a WARN and the next start retries.
 *
 * <p>Like the token backfill it runs AFTER a delay ({@link #scheduleMigrateAll}): a replica on
 * the previous image decrypts only the 15 legacy names, so a freshly encrypted
 * {@code secret_access_key} would reach it as ciphertext during a rolling deploy. The pass is
 * a full scan of the table (parse + probe every row) on every start, which is fine for the
 * dozens to thousands of credential rows a workspace holds; it needs a BIGINT id column
 * (pages on {@code id > last}) and says so loudly if given anything else.
 */
public final class SensitiveJsonbBackfill {

    private static final Logger log = LoggerFactory.getLogger(SensitiveJsonbBackfill.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    static final int PAGE_SIZE = 200;
    static final int MAX_PAGES = 5_000;

    /**
     * @param table      fully-qualified table
     * @param idColumn   a column with a total order (the pass keys pages on {@code id > last})
     * @param jsonColumn the JSONB column holding the credential map
     */
    public record TableSpec(String table, String idColumn, String jsonColumn) {
        public TableSpec {
            Objects.requireNonNull(table);
            Objects.requireNonNull(idColumn);
            Objects.requireNonNull(jsonColumn);
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CredentialEncryptionService encryptionService;
    /** The pending delayed sweep, so a closing context can cancel it instead of leaking it. */
    private final AtomicReference<Thread> pending = new AtomicReference<>();

    public SensitiveJsonbBackfill(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                  CredentialEncryptionService encryptionService) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.encryptionService = Objects.requireNonNull(encryptionService);
    }

    /** Run {@link #migrateAll} on a daemon thread after {@code delay}; zero or negative runs inline. */
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
        }, "sensitive-field-sweep");
        t.setDaemon(true);
        pending.set(t);
        t.start();
        log.info("Sensitive-field sweep of {} table(s) scheduled in {} (rollout window)", specs.size(), delay);
    }

    /** Cancel a sweep that has not started yet (context close); a running one is left alone. */
    public void cancelPending() {
        Thread t = pending.getAndSet(null);
        if (t != null && t.isAlive()) {
            t.interrupt();
        }
    }

    /** Sweep every spec. Returns rows rewritten. Never throws. */
    public int migrateAll(List<TableSpec> specs) {
        if (encryptionService.isUsingEphemeralMaterial()) {
            log.warn("Sensitive-field sweep skipped: no credential encryption key is configured (ephemeral material); "
                    + "rewriting rows with it would make them unreadable after the next restart");
            return 0;
        }
        int total = 0;
        for (TableSpec spec : specs) {
            try {
                int n = migrate(spec);
                total += n;
                if (n > 0) {
                    log.info("Sensitive-field sweep: {} row(s) of {} had a secret in clear and are now encrypted",
                            n, spec.table());
                }
            } catch (RuntimeException e) {
                log.warn("Sensitive-field sweep of {} failed; plaintext rows stay readable and the next start "
                        + "retries (cause: {})", spec.table(), e.toString());
            }
        }
        return total;
    }

    /** One table, paged by id. Throws on a database error so {@link #migrateAll} can report it. */
    public int migrate(TableSpec spec) {
        String select = "SELECT " + spec.idColumn() + " AS id, " + spec.jsonColumn() + "::text AS js FROM " + spec.table()
                + " WHERE " + spec.idColumn() + " > ? ORDER BY " + spec.idColumn() + " LIMIT " + PAGE_SIZE;
        String update = "UPDATE " + spec.table() + " SET " + spec.jsonColumn() + " = CAST(? AS jsonb)"
                + " WHERE " + spec.idColumn() + " = ? AND " + spec.jsonColumn() + "::text = ?";
        int migrated = 0;
        long lastId = Long.MIN_VALUE;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<Map<String, Object>> rows = jdbc.queryForList(select, lastId);
            if (rows.isEmpty()) {
                break;
            }
            for (Map<String, Object> row : rows) {
                if (!(row.get("id") instanceof Number idNumber)) {
                    throw new IllegalStateException(spec.table() + "." + spec.idColumn() + " is not a numeric id; "
                            + "this sweep pages on BIGINT ids only");
                }
                lastId = idNumber.longValue();
                String js = (String) row.get("js");
                if (js == null || js.isBlank()) {
                    continue;
                }
                Map<String, Object> raw;
                try {
                    raw = objectMapper.readValue(js, MAP);
                } catch (Exception parse) {
                    log.warn("Sensitive-field sweep: {} id={} holds unparseable JSON, skipped", spec.table(), lastId);
                    continue;
                }
                if (!encryptionService.hasPlaintextSensitiveField(raw)) {
                    continue;
                }
                String encrypted;
                try {
                    encrypted = objectMapper.writeValueAsString(encryptionService.encryptSensitiveFields(raw));
                } catch (Exception ser) {
                    throw new IllegalStateException("cannot serialise re-encrypted map for " + spec.table() + " id=" + lastId, ser);
                }
                migrated += jdbc.update(update, encrypted, lastId, js);
            }
        }
        return migrated;
    }
}
