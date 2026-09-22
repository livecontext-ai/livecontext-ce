package com.apimarketplace.common.security.token;

import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.common.security.token.PlaintextTokenBackfill.TableSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backfill against a real (in-memory) database: rows written before the change move to
 * encrypted + hashed, nothing else on the row moves, and a second run is a no-op.
 */
@DisplayName("PlaintextTokenBackfill (H2)")
class PlaintextTokenBackfillTest {

    static final CredentialEncryptionService SERVICE =
            new CredentialEncryptionService("test-password-123", "0123456789abcdef");
    static final TableSpec SPEC = new TableSpec("t_tokens", "id", "token", "token_hash");
    static final TableSpec UUID_SPEC = new TableSpec("t_uuid_tokens", "id", "token", "token_hash");

    private JdbcTemplate jdbc;
    private PlaintextTokenBackfill backfill;

    @BeforeAll
    static void install() {
        TokenAtRest.install(SERVICE);
    }

    @BeforeEach
    void schema() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE t_tokens (id BIGINT PRIMARY KEY, token VARCHAR(255), token_hash VARCHAR(64), name VARCHAR(50), updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE t_uuid_tokens (id UUID PRIMARY KEY, token VARCHAR(255), token_hash VARCHAR(64))");
        backfill = new PlaintextTokenBackfill(jdbc);
    }

    private void plaintextRow(long id, String token) {
        jdbc.update("INSERT INTO t_tokens (id, token, token_hash, name, updated_at) VALUES (?, ?, NULL, 'n', '2026-01-01 00:00:00')", id, token);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT token, token_hash, name, updated_at FROM t_tokens WHERE id = ?", id);
    }

    @Test
    @DisplayName("moves every plaintext row to ENC: + hash, touching no other column")
    void migratesPlaintextRows() {
        plaintextRow(1, "wh_one");
        plaintextRow(2, "wh_two");

        int n = backfill.migrateAll(List.of(SPEC));

        assertThat(n).isEqualTo(2);
        Map<String, Object> r1 = row(1);
        assertThat((String) r1.get("TOKEN")).startsWith("ENC:");
        assertThat(TokenAtRest.decrypt((String) r1.get("TOKEN"))).isEqualTo("wh_one");
        assertThat(r1.get("TOKEN_HASH")).isEqualTo(TokenAtRest.hash("wh_one"));
        assertThat(r1.get("NAME")).isEqualTo("n");
        assertThat(r1.get("UPDATED_AT").toString()).startsWith("2026-01-01");
        assertThat(row(2).get("TOKEN_HASH")).isEqualTo(TokenAtRest.hash("wh_two"));
    }

    @Test
    @DisplayName("a second run rewrites nothing (idempotent), and a hashed row is never re-encrypted")
    void idempotent() {
        plaintextRow(1, "wh_one");
        backfill.migrateAll(List.of(SPEC));
        String storedOnce = (String) row(1).get("TOKEN");

        int n = backfill.migrateAll(List.of(SPEC));

        assertThat(n).isZero();
        assertThat(row(1).get("TOKEN")).isEqualTo(storedOnce);
    }

    @Test
    @DisplayName("a row already encrypted but with no hash (written in the deploy window) gets the hash of its PLAINTEXT")
    void encryptedRowWithoutHash() {
        jdbc.update("INSERT INTO t_tokens (id, token, token_hash, name) VALUES (?, ?, NULL, 'n')", 1L, TokenAtRest.encrypt("wh_x"));

        backfill.migrateAll(List.of(SPEC));

        assertThat(row(1).get("TOKEN_HASH")).isEqualTo(TokenAtRest.hash("wh_x"));
        assertThat(TokenAtRest.decrypt((String) row(1).get("TOKEN"))).isEqualTo("wh_x");
    }

    @Test
    @DisplayName("null tokens are skipped (a conversation never shared keeps a null hash)")
    void nullTokensSkipped() {
        jdbc.update("INSERT INTO t_tokens (id, token, token_hash, name) VALUES (1, NULL, NULL, 'n')");

        assertThat(backfill.migrateAll(List.of(SPEC))).isZero();
        assertThat(row(1).get("TOKEN_HASH")).isNull();
    }

    @Test
    @DisplayName("pages through more rows than one page holds")
    void pagesThroughLargeTables() {
        for (long i = 1; i <= PlaintextTokenBackfill.PAGE_SIZE + 7; i++) {
            plaintextRow(i, "wh_" + i);
        }

        PlaintextTokenBackfill.Result r = backfill.migrate(SPEC);
        assertThat(r.migrated()).isEqualTo(PlaintextTokenBackfill.PAGE_SIZE + 7);
        assertThat(r.drained()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_tokens WHERE token_hash IS NULL", Long.class)).isZero();
    }

    @Test
    @DisplayName("heals exactly the row that still holds the given plaintext, and reports whether it did")
    void healByPlaintext() {
        plaintextRow(1, "wh_one");
        plaintextRow(2, "wh_two");

        assertThat(backfill.migrateByPlaintext(SPEC, "wh_two")).isTrue();
        assertThat(backfill.migrateByPlaintext(SPEC, "wh_two")).isFalse();   // already migrated
        assertThat(backfill.migrateByPlaintext(SPEC, "wh_nope")).isFalse();  // unknown
        assertThat(backfill.migrateByPlaintext(SPEC, "  ")).isFalse();

        assertThat(row(2).get("TOKEN_HASH")).isEqualTo(TokenAtRest.hash("wh_two"));
        assertThat(row(1).get("TOKEN_HASH")).isNull();
        assertThat(row(1).get("TOKEN")).isEqualTo("wh_one");
    }

    @Test
    @DisplayName("works on a UUID primary key (shared_links, standalone endpoints)")
    void uuidPrimaryKey() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO t_uuid_tokens (id, token) VALUES (?, ?)", id, "sl_a");

        assertThat(backfill.migrate(UUID_SPEC).migrated()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM t_uuid_tokens WHERE id = ?", String.class, id))
                .isEqualTo(TokenAtRest.hash("sl_a"));
    }

    @Test
    @DisplayName("a row encrypted under ANOTHER key is skipped with a WARN, the rest of the table still migrates, and the table is not reported drained")
    void foreignCiphertextDoesNotAbortTheTable() {
        String foreign = new CredentialEncryptionService("other-password-456", "fedcba9876543210").encrypt("wh_x");
        jdbc.update("INSERT INTO t_tokens (id, token, token_hash, name) VALUES (1, ?, NULL, 'n')", foreign);
        plaintextRow(2, "wh_two");
        plaintextRow(3, "wh_three");

        PlaintextTokenBackfill.Result r = backfill.migrate(SPEC);

        assertThat(r.migrated()).isEqualTo(2);
        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.drained()).isFalse();
        assertThat(backfill.mayHaveLegacyRows(SPEC)).isTrue();
        assertThat(row(1).get("TOKEN")).isEqualTo(foreign);
        assertThat(row(3).get("TOKEN_HASH")).isEqualTo(TokenAtRest.hash("wh_three"));
    }

    @Test
    @DisplayName("blank tokens never stall the pass: a page of them is skipped in SQL and the real rows behind still migrate")
    void blankTokensDoNotStall() {
        for (long i = 1; i <= PlaintextTokenBackfill.PAGE_SIZE + 3; i++) {
            jdbc.update("INSERT INTO t_tokens (id, token, token_hash, name) VALUES (?, '', NULL, 'n')", i);
        }
        plaintextRow(PlaintextTokenBackfill.PAGE_SIZE + 10, "wh_real");

        PlaintextTokenBackfill.Result r = backfill.migrate(SPEC);

        assertThat(r.migrated()).isEqualTo(1);
        assertThat(r.drained()).isTrue();
        assertThat(row(PlaintextTokenBackfill.PAGE_SIZE + 10).get("TOKEN_HASH")).isEqualTo(TokenAtRest.hash("wh_real"));
    }

    @Test
    @DisplayName("mayHaveLegacyRows is true until a pass drains the table, then false, so the fallback query stops")
    void legacyFlagFlipsOnceDrained() {
        plaintextRow(1, "wh_one");
        assertThat(backfill.mayHaveLegacyRows(SPEC)).isTrue();

        backfill.migrateAll(List.of(SPEC));

        assertThat(backfill.mayHaveLegacyRows(SPEC)).isFalse();
        assertThat(backfill.mayHaveLegacyRows(UUID_SPEC)).isTrue();   // per table
        // a heal on a drained table is a no-op without a query
        assertThat(backfill.migrateByPlaintext(SPEC, "wh_one")).isFalse();
    }

    @Test
    @DisplayName("scheduleMigrateAll runs inline for a zero delay and defers (leaving rows in clear) for a positive one")
    void scheduleInlineOrDeferred() throws Exception {
        plaintextRow(1, "wh_one");
        backfill.scheduleMigrateAll(List.of(SPEC), java.time.Duration.ZERO);
        assertThat(row(1).get("TOKEN_HASH")).isNotNull();

        plaintextRow(2, "wh_two");
        backfill = new PlaintextTokenBackfill(jdbc);
        backfill.scheduleMigrateAll(List.of(SPEC), java.time.Duration.ofMillis(400));
        assertThat(row(2).get("TOKEN_HASH")).as("still in clear inside the delay (rollout window)").isNull();
        Thread.sleep(1500);
        assertThat(row(2).get("TOKEN_HASH")).isEqualTo(TokenAtRest.hash("wh_two"));
    }

    @Test
    @DisplayName("on ephemeral material (no key configured) nothing is rewritten: encrypting with a per-process key would be data loss")
    void ephemeralMaterialRefusesToRewrite() {
        plaintextRow(1, "wh_one");
        TokenAtRest.install(new CredentialEncryptionService("", ""));   // blank -> ephemeral, dev-laptop mode
        try {
            assertThat(TokenAtRest.isUsingEphemeralMaterial()).isTrue();
            assertThat(backfill.migrateAll(List.of(SPEC))).isZero();
            assertThat(backfill.migrateByPlaintext(SPEC, "wh_one")).isFalse();
            assertThat(row(1).get("TOKEN")).isEqualTo("wh_one");
            assertThat(row(1).get("TOKEN_HASH")).isNull();
        } finally {
            TokenAtRest.install(SERVICE);
        }
        assertThat(TokenAtRest.isUsingEphemeralMaterial()).isFalse();
    }

    @Test
    @DisplayName("findLegacy is the ONE gate: it runs the query while legacy rows may remain and stops once drained")
    void findLegacyGatesTheQuery() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Function<String, java.util.Optional<String>> query =
                t -> { calls.incrementAndGet(); return java.util.Optional.of("row:" + t); };
        plaintextRow(1, "wh_one");

        assertThat(backfill.findLegacy(SPEC, "wh_one", query)).contains("row:wh_one");
        assertThat(calls.get()).isEqualTo(1);
        // null / blank never reach the query
        assertThat(backfill.findLegacy(SPEC, null, query)).isEmpty();
        assertThat(backfill.findLegacy(SPEC, "  ", query)).isEmpty();
        assertThat(calls.get()).isEqualTo(1);

        backfill.migrateAll(List.of(SPEC));

        assertThat(backfill.findLegacy(SPEC, "wh_one", query)).as("drained: no second query").isEmpty();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("cancelPending stops a scheduled pass, so a closing context leaves no thread to wake on a dead pool")
    void cancelPendingStopsTheScheduledPass() throws Exception {
        plaintextRow(1, "wh_one");
        backfill.scheduleMigrateAll(List.of(SPEC), java.time.Duration.ofMillis(300));

        backfill.cancelPending();
        Thread.sleep(900);

        assertThat(row(1).get("TOKEN_HASH")).as("cancelled before it ran").isNull();
        assertThat(row(1).get("TOKEN")).isEqualTo("wh_one");
        backfill.cancelPending();   // idempotent, nothing pending
    }

    @Test
    @DisplayName("a missing table is a WARN, not an exception, and the other tables still run")
    void missingTableDoesNotAbort() {
        plaintextRow(1, "wh_one");
        TableSpec missing = new TableSpec("t_does_not_exist", "id", "token", "token_hash");

        int n = backfill.migrateAll(List.of(missing, SPEC));

        assertThat(n).isEqualTo(1);
    }
}
