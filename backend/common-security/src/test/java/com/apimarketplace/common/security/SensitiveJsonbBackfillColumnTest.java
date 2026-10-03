package com.apimarketplace.common.security;

import com.apimarketplace.common.security.SensitiveJsonbBackfill.ColumnSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LC-024 re-encryption job for single-value encrypted columns (platform credentials, workflow
 * variables): v1 to v2 once write-version is 2, idempotent, nothing done at write-version 1 or on
 * ephemeral material, and an unreadable value is skipped rather than aborting the pass.
 */
@DisplayName("SensitiveJsonbBackfill column re-encryption (H2)")
class SensitiveJsonbBackfillColumnTest {

    private static final String PASSWORD = "test-password-123";
    private static final String SALT = "0123456789abcdef";
    private static final ColumnSpec SPEC = new ColumnSpec("t_secrets", "id", "secret");

    private final CredentialEncryptionService v1 = new CredentialEncryptionService(PASSWORD, SALT);
    private final CredentialEncryptionService v2 =
            new CredentialEncryptionService(PASSWORD, SALT, "", "", false, false, "2", "allow", "");
    private JdbcTemplate jdbc;

    @BeforeEach
    void schema() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", ""));
        jdbc.execute("CREATE TABLE t_secrets (id BIGINT PRIMARY KEY, secret VARCHAR(1000), name VARCHAR(20))");
    }

    private void row(long id, String secret) {
        jdbc.update("INSERT INTO t_secrets (id, secret, name) VALUES (?, ?, 'n')", id, secret);
    }

    private String secret(long id) {
        return jdbc.queryForObject("SELECT secret FROM t_secrets WHERE id = ?", String.class, id);
    }

    private SensitiveJsonbBackfill sweep(CredentialEncryptionService service) {
        return new SensitiveJsonbBackfill(jdbc, new ObjectMapper(), service);
    }

    @Test
    @DisplayName("at write-version 2: v1 and plaintext values become v2, v2 and null are untouched, second run is a no-op")
    void upgradesToV2() {
        row(1, v1.encrypt("legacy"));
        row(2, "clear");
        String alreadyV2 = v2.encrypt("modern");
        row(3, alreadyV2);
        row(4, null);

        assertThat(sweep(v2).migrateAll(List.of(), List.of(SPEC))).isEqualTo(2);

        assertThat(secret(1)).startsWith("ENC:v2.");
        assertThat(v2.decrypt(secret(1))).isEqualTo("legacy");
        assertThat(v1.decrypt(secret(1))).isEqualTo("legacy");
        assertThat(secret(2)).startsWith("ENC:v2.");
        assertThat(v2.decrypt(secret(2))).isEqualTo("clear");
        assertThat(secret(3)).isEqualTo(alreadyV2);
        assertThat(secret(4)).isNull();
        assertThat(sweep(v2).migrateColumn(SPEC)).isZero();
    }

    @Test
    @DisplayName("at write-version 1 (rollout default) a v1 value is left alone; only plaintext is encrypted (as v1)")
    void v1ModeDoesNotUpgrade() {
        String legacy = v1.encrypt("legacy");
        row(1, legacy);
        row(2, "clear");

        assertThat(sweep(v1).migrateColumn(SPEC)).isEqualTo(1);
        assertThat(secret(1)).isEqualTo(legacy);
        assertThat(secret(2)).startsWith("ENC:").doesNotStartWith("ENC:v2.");
    }

    @Test
    @DisplayName("a value under an unknown key is skipped, the rest of the column still migrates")
    void unreadableValueSkipped() {
        String foreign = new CredentialEncryptionService("other-password-456", "fedcba9876543210").encrypt("x");
        row(1, foreign);
        row(2, v1.encrypt("ok"));

        assertThat(sweep(v2).migrateColumn(SPEC)).isEqualTo(1);
        assertThat(secret(1)).isEqualTo(foreign);
        assertThat(secret(2)).startsWith("ENC:v2.");
    }

    @Test
    @DisplayName("ephemeral material rewrites nothing")
    void ephemeralRefuses() {
        row(1, "clear");
        CredentialEncryptionService ephemeral =
                new CredentialEncryptionService("", "", "", "", false, false, "2", "allow", "");

        assertThat(sweep(ephemeral).migrateAll(List.of(), List.of(SPEC))).isZero();
        assertThat(secret(1)).isEqualTo("clear");
    }

    @Test
    @DisplayName("rotation: a v2 value sealed under the previous kid is re-sealed under the current one")
    void rotationResealsPreviousKid() {
        String oldV2 = v2.encrypt("rotating");
        row(1, oldV2);
        CredentialEncryptionService rotated = new CredentialEncryptionService("brand-new-password-456",
                "fedcba9876543210", PASSWORD, SALT, false, false, "2", "allow", "");

        assertThat(sweep(rotated).migrateColumn(SPEC)).isEqualTo(1);

        assertThat(secret(1)).contains("." + rotated.currentKeyId() + ".");
        assertThat(rotated.decrypt(secret(1))).isEqualTo("rotating");
        assertThat(sweep(rotated).migrateColumn(SPEC)).isZero();
    }

    @Test
    @DisplayName("resealOnly: existing ciphertext is re-sealed, a value stored in clear is left as it is")
    void resealOnlyLeavesPlaintext() {
        row(1, v1.encrypt("legacy"));
        row(2, "seeded-clear-value");

        assertThat(sweep(v2).migrateColumn(new ColumnSpec("t_secrets", "id", "secret", true))).isEqualTo(1);

        assertThat(secret(1)).startsWith("ENC:v2.");
        assertThat(secret(2)).isEqualTo("seeded-clear-value");
    }

    @Test
    @DisplayName("keyset paging works on a non-numeric (UUID/text) id, across more than one page")
    void textIdsArePaged() {
        jdbc.execute("CREATE TABLE t_uuid_secrets (id VARCHAR(36) PRIMARY KEY, secret VARCHAR(1000))");
        int n = SensitiveJsonbBackfill.PAGE_SIZE + 5;
        for (int i = 0; i < n; i++) {
            jdbc.update("INSERT INTO t_uuid_secrets (id, secret) VALUES (?, ?)", UUID.randomUUID().toString(), v1.encrypt("s" + i));
        }

        assertThat(sweep(v2).migrateColumn(new ColumnSpec("t_uuid_secrets", "id", "secret"))).isEqualTo(n);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_uuid_secrets WHERE secret LIKE 'ENC:v2.%'", Integer.class))
                .isEqualTo(n);
    }
}
