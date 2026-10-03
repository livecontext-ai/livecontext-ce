package com.apimarketplace.common.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Startup re-encryption sweep for values written through {@link CredentialEncryptionService}:
 * JSONB credential maps ({@link TableSpec}) and single-value encrypted text columns
 * ({@link ColumnSpec}). Each owning service registers the tables of ITS OWN schema (auth:
 * credentials, platform credentials, workflow variables; datasource: source_config; catalog: API
 * auth header values; agent and trigger: webhook auth configs). Capability-token columns are
 * handled by {@code PlaintextTokenBackfill}, which must also re-hash them.
 *
 * <p>A value is rewritten when {@link CredentialEncryptionService#needsReencryption(String)} says
 * so, and only then:
 * <ul>
 *   <li>it is stored in clear (a row written before encryption, or under the old 15-name
 *       allow-list, e.g. an AWS {@code secret_access_key});</li>
 *   <li>at {@code credential.encryption.write-version=2}: it is still in the legacy CBC envelope
 *       ({@code ENC:<hex>}), or it is a v2 envelope sealed under a key id other than the current
 *       one (the previous generation during a rotation). This is what lets a v1 population drain
 *       and a rotation finish. At write-version 1 neither of these is touched.</li>
 * </ul>
 * A value the loaded keys cannot decrypt is left exactly as it is (logged), never rewritten.
 * {@code updated_at} is not touched: the user did not change anything.
 *
 * <p>Write safety: every UPDATE is a compare-and-set on the value that was read, so a concurrent
 * edit wins and the sweep skips that row; the next start sees it again. Paging is keyset on the
 * id column, which may be BIGINT, UUID or text (anything with a total order). Never throws from
 * {@link #migrateAll}; a failure is a WARN and the next start retries. Refuses to run on ephemeral
 * material (no key configured): rewriting stored rows with a key that dies with the process would
 * be data loss.
 *
 * <p>Runs AFTER a delay ({@link #scheduleMigrateAll}) that outlasts a rolling deploy, so a replica
 * on the previous image never meets a value it cannot read.
 */
public final class SensitiveJsonbBackfill {

    private static final Logger log = LoggerFactory.getLogger(SensitiveJsonbBackfill.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    static final int PAGE_SIZE = 200;
    static final int MAX_PAGES = 5_000;

    /**
     * A JSONB column holding a credential map whose sensitive keys are encrypted one by one.
     *
     * @param table      fully-qualified table
     * @param idColumn   a column with a total order (keyset paging on {@code id > last})
     * @param jsonColumn the JSONB column holding the credential map
     * @param fields     the exact keys the owning service encrypts and decrypts, or null for
     *                   "every key {@link CredentialEncryptionService#isSensitiveField} flags". A
     *                   service whose reader only decrypts a fixed list MUST pass that list: the
     *                   sweep must never encrypt a key its reader would hand back as ciphertext.
     */
    public record TableSpec(String table, String idColumn, String jsonColumn, Set<String> fields) {
        public TableSpec {
            Objects.requireNonNull(table);
            Objects.requireNonNull(idColumn);
            Objects.requireNonNull(jsonColumn);
            fields = fields == null ? null : Set.copyOf(fields);
        }

        public TableSpec(String table, String idColumn, String jsonColumn) {
            this(table, idColumn, jsonColumn, null);
        }
    }

    /**
     * A plain text column holding one encrypted value per row
     * ({@code auth.platform_credentials.client_secret}, {@code catalog.apis.auth_header_value}).
     *
     * @param table    fully-qualified table
     * @param idColumn a column with a total order (keyset paging on {@code id > last})
     * @param column   the encrypted text column
     * @param resealOnly true when a value stored in CLEAR must be left alone and only existing
     *                 ciphertext is re-sealed (a column whose plaintext rows are legitimate, e.g.
     *                 catalog seed values that are exported verbatim in API catalog bundles)
     */
    public record ColumnSpec(String table, String idColumn, String column, boolean resealOnly) {
        public ColumnSpec {
            Objects.requireNonNull(table);
            Objects.requireNonNull(idColumn);
            Objects.requireNonNull(column);
        }

        public ColumnSpec(String table, String idColumn, String column) {
            this(table, idColumn, column, false);
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

    /** Run {@link #migrateAll(List)} after {@code delay}; zero or negative runs inline. */
    public void scheduleMigrateAll(List<TableSpec> specs, Duration delay) {
        scheduleMigrateAll(specs, List.of(), delay);
    }

    /** Run {@link #migrateAll(List, List)} on a daemon thread after {@code delay}; zero or negative runs inline. */
    public void scheduleMigrateAll(List<TableSpec> specs, List<ColumnSpec> columns, Duration delay) {
        if (delay == null || delay.isZero() || delay.isNegative()) {
            migrateAll(specs, columns);
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            migrateAll(specs, columns);
        }, "sensitive-field-sweep");
        t.setDaemon(true);
        pending.set(t);
        t.start();
        log.info("Sensitive-field sweep of {} table(s) and {} column(s) scheduled in {} (rollout window)",
                specs.size(), columns == null ? 0 : columns.size(), delay);
    }

    /** Cancel a sweep that has not started yet (context close); a running one is left alone. */
    public void cancelPending() {
        Thread t = pending.getAndSet(null);
        if (t != null && t.isAlive()) {
            t.interrupt();
        }
    }

    /** Sweep every JSONB spec. Returns rows rewritten. Never throws. */
    public int migrateAll(List<TableSpec> specs) {
        return migrateAll(specs, List.of());
    }

    /** Sweep every JSONB spec and every column spec. Returns rows rewritten. Never throws. */
    public int migrateAll(List<TableSpec> specs, List<ColumnSpec> columns) {
        if (encryptionService.isUsingEphemeralMaterial()) {
            log.warn("Sensitive-field sweep skipped: no credential encryption key is configured (ephemeral material); "
                    + "rewriting rows with it would make them unreadable after the next restart");
            return 0;
        }
        int total = 0;
        for (TableSpec spec : specs == null ? List.<TableSpec>of() : specs) {
            try {
                int n = migrate(spec);
                total += n;
                if (n > 0) {
                    log.info("Sensitive-field sweep: {} row(s) of {} re-encrypted", n, spec.table());
                }
            } catch (RuntimeException e) {
                log.warn("Sensitive-field sweep of {} failed; rows stay readable and the next start retries (cause: {})",
                        spec.table(), e.toString());
            }
        }
        for (ColumnSpec spec : columns == null ? List.<ColumnSpec>of() : columns) {
            try {
                int n = migrateColumn(spec);
                total += n;
                if (n > 0) {
                    log.info("Sensitive-field sweep: {} value(s) of {}.{} re-encrypted", n, spec.table(), spec.column());
                }
            } catch (RuntimeException e) {
                log.warn("Sensitive-field sweep of {}.{} failed; rows stay readable and the next start retries "
                        + "(cause: {})", spec.table(), spec.column(), e.toString());
            }
        }
        return total;
    }

    /** One JSONB table. Throws on a database error so {@link #migrateAll} can report it. */
    public int migrate(TableSpec spec) {
        String columns = spec.idColumn() + " AS id, " + spec.jsonColumn() + "::text AS js";
        String update = "UPDATE " + spec.table() + " SET " + spec.jsonColumn() + " = CAST(? AS jsonb)"
                + " WHERE " + spec.idColumn() + " = ? AND " + spec.jsonColumn() + "::text = ?";
        int migrated = 0;
        Object lastId = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<Map<String, Object>> rows = page(spec.table(), spec.idColumn(), columns, lastId);
            if (rows.isEmpty()) {
                break;
            }
            for (Map<String, Object> row : rows) {
                lastId = row.get("id");
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
                if (raw == null || !needsReencryption(spec, raw)) {
                    continue;
                }
                Map<String, Object> rewritten = reencrypt(spec, raw);
                if (rewritten.equals(raw)) {
                    // Every candidate value was unreadable under the loaded keys: leave the row alone.
                    log.warn("Sensitive-field sweep: {} id={} holds a value the loaded keys cannot decrypt, left as-is",
                            spec.table(), lastId);
                    continue;
                }
                String encrypted;
                try {
                    encrypted = objectMapper.writeValueAsString(rewritten);
                } catch (Exception ser) {
                    throw new IllegalStateException("cannot serialise re-encrypted map for " + spec.table() + " id=" + lastId, ser);
                }
                migrated += jdbc.update(update, encrypted, lastId, js);
            }
        }
        return migrated;
    }

    /**
     * One scalar column: rewrites every value {@link CredentialEncryptionService#needsReencryption(String)}
     * flags. Compare-and-set on the value read, so a concurrent save wins. A value the loaded keys
     * cannot decrypt is logged and skipped, never aborts the pass.
     */
    public int migrateColumn(ColumnSpec spec) {
        String columns = spec.idColumn() + " AS id, " + spec.column() + " AS v";
        String update = "UPDATE " + spec.table() + " SET " + spec.column() + " = ?"
                + " WHERE " + spec.idColumn() + " = ? AND " + spec.column() + " = ?";
        int migrated = 0;
        Object lastId = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<Map<String, Object>> rows = page(spec.table(), spec.idColumn(), columns, lastId);
            if (rows.isEmpty()) {
                break;
            }
            for (Map<String, Object> row : rows) {
                lastId = row.get("id");
                String stored = (String) row.get("v");
                if (!encryptionService.needsReencryption(stored)
                        || (spec.resealOnly() && !encryptionService.isEncrypted(stored))) {
                    continue;
                }
                String rewritten;
                try {
                    rewritten = encryptionService.encrypt(stored);
                } catch (RuntimeException e) {
                    log.warn("Sensitive-field sweep: {}.{} id={} left as-is (cause: {})",
                            spec.table(), spec.column(), lastId, e.toString());
                    continue;
                }
                if (rewritten != null && !rewritten.equals(stored)) {
                    migrated += jdbc.update(update, rewritten, lastId, stored);
                } else {
                    log.warn("Sensitive-field sweep: {}.{} id={} holds a value the loaded keys cannot decrypt, left as-is",
                            spec.table(), spec.column(), lastId);
                }
            }
        }
        return migrated;
    }

    private boolean needsReencryption(TableSpec spec, Map<String, Object> raw) {
        if (spec.fields() == null) {
            return encryptionService.needsReencryption(raw);
        }
        for (String field : spec.fields()) {
            if (raw.get(field) instanceof String s && encryptionService.needsReencryption(s)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> reencrypt(TableSpec spec, Map<String, Object> raw) {
        if (spec.fields() == null) {
            return encryptionService.encryptSensitiveFields(raw);
        }
        Map<String, Object> result = new LinkedHashMap<>(raw);
        for (String field : spec.fields()) {
            if (result.get(field) instanceof String s) {
                result.put(field, encryptionService.encrypt(s));
            }
        }
        return result;
    }

    /** Keyset page: the first page has no lower bound, later ones continue after the last id seen. */
    private List<Map<String, Object>> page(String table, String idColumn, String columns, Object lastId) {
        String base = "SELECT " + columns + " FROM " + table;
        String order = " ORDER BY " + idColumn + " LIMIT " + PAGE_SIZE;
        return lastId == null
                ? jdbc.queryForList(base + order)
                : jdbc.queryForList(base + " WHERE " + idColumn + " > ?" + order, lastId);
    }
}
