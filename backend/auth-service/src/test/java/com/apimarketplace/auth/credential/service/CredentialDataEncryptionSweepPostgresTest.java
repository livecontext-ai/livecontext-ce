package com.apimarketplace.auth.credential.service;

import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.common.security.SensitiveJsonbBackfill;
import com.apimarketplace.common.security.SensitiveJsonbBackfill.TableSpec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The startup sweep against a real Postgres JSONB column: the {@code ::text} compare-and-set
 * and {@code CAST(? AS jsonb)} are Postgres syntax, so H2 cannot stand in. Reproduces the
 * 2026-09-17 finding, a custom-auth credential (AWS) whose secret landed in clear under the old
 * 15-name allow-list, and proves the sweep encrypts it in place without touching anything else
 * or re-encrypting rows that are already fine.
 *
 * <p>Where it runs: on CI against the job's scratch Postgres ({@code CREDENTIAL_TEST_PG_URL}, the
 * same service {@code CredentialRepositoryNameSqlTest} uses; CI runners have no Docker socket),
 * on a laptop against a Testcontainers Postgres when Docker is up, otherwise skipped. On CI with
 * no URL it FAILS rather than skips: a Postgres-only statement that never executes anywhere is
 * verified by nothing. It works on its OWN table ({@code auth.credentials_sweep_test}) so it
 * never collides with the other class's schema or truncation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("CredentialDataEncryptionSweep - Postgres")
class CredentialDataEncryptionSweepPostgresTest {

    static final TableSpec SCRATCH = new TableSpec("auth.credentials_sweep_test", "id", "credential_data");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private PostgreSQLContainer<?> container;
    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;
    private CredentialEncryptionService encryption;
    private SensitiveJsonbBackfill sweep;

    @BeforeAll
    void connect() {
        String url = System.getenv("CREDENTIAL_TEST_PG_URL");
        String user;
        String password;
        if (url != null && !url.isBlank()) {
            if (!url.toLowerCase().contains("test")) {
                throw new IllegalStateException("CREDENTIAL_TEST_PG_URL must point at a scratch database whose name contains 'test'");
            }
            user = System.getenv().getOrDefault("CREDENTIAL_TEST_PG_USER", "postgres");
            password = System.getenv().getOrDefault("CREDENTIAL_TEST_PG_PASSWORD", "postgres");
        } else {
            boolean onCi = System.getenv("CI") != null && !System.getenv("CI").isBlank();
            if (onCi) {
                throw new IllegalStateException("CREDENTIAL_TEST_PG_URL is unset on CI: this class must run in the job "
                        + "that carries the scratch Postgres, or its Postgres-only SQL is verified by nothing");
            }
            boolean docker;
            try {
                docker = DockerClientFactory.instance().isDockerAvailable();
            } catch (RuntimeException e) {
                docker = false;
            }
            assumeTrue(docker, "no scratch Postgres: set CREDENTIAL_TEST_PG_URL or start Docker to run this locally");
            container = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("auth_test").withUsername("test").withPassword("test");
            container.start();
            url = container.getJdbcUrl();
            user = container.getUsername();
            password = container.getPassword();
        }
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS auth");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS auth.credentials_sweep_test (
                    id BIGSERIAL PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    credential_data JSONB NOT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT '2026-01-01 00:00:00'
                )""");
        objectMapper = new ObjectMapper();
        encryption = new CredentialEncryptionService("test-password-123", "0123456789abcdef");
        sweep = new SensitiveJsonbBackfill(jdbc, objectMapper, encryption);
    }

    @AfterAll
    void disconnect() {
        if (jdbc != null) {
            jdbc.execute("DROP TABLE IF EXISTS auth.credentials_sweep_test");
        }
        if (container != null) {
            container.stop();
        }
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE auth.credentials_sweep_test");
    }

    private long insert(String json) {
        return jdbc.queryForObject(
                "INSERT INTO auth.credentials_sweep_test (tenant_id, credential_data) VALUES ('1', CAST(? AS jsonb)) RETURNING id",
                Long.class, json);
    }

    private String storedText(long id) {
        return jdbc.queryForObject("SELECT credential_data::text FROM auth.credentials_sweep_test WHERE id = ?", String.class, id);
    }

    private Map<String, Object> stored(long id) throws Exception {
        return objectMapper.readValue(storedText(id), MAP);
    }

    @Test
    @DisplayName("an AWS secret_access_key stored in clear is encrypted in place; the id, region and updated_at stay as they were")
    void encryptsPlaintextSecretInPlace() throws Exception {
        long id = insert("{\"access_key_id\":\"AKIAEXAMPLE\",\"secret_access_key\":\"wJalrXUtnFEMI\",\"region\":\"eu-west-3\"}");

        int n = sweep.migrateAll(List.of(SCRATCH));

        assertThat(n).isEqualTo(1);
        Map<String, Object> row = stored(id);
        assertThat(row.get("access_key_id")).isEqualTo("AKIAEXAMPLE");
        assertThat(row.get("region")).isEqualTo("eu-west-3");
        assertThat((String) row.get("secret_access_key")).startsWith("ENC:");
        assertThat(encryption.decryptSensitiveFields(row).get("secret_access_key")).isEqualTo("wJalrXUtnFEMI");
        assertThat(jdbc.queryForObject("SELECT updated_at::text FROM auth.credentials_sweep_test WHERE id = ?", String.class, id))
                .startsWith("2026-01-01");
    }

    @Test
    @DisplayName("a row whose secrets are already encrypted is left byte-for-byte unchanged")
    void alreadyEncryptedRowUntouched() throws Exception {
        long id = insert(objectMapper.writeValueAsString(
                encryption.encryptSensitiveFields(Map.of("api_key", "sk-live", "client_id", "abc"))));
        String before = storedText(id);

        assertThat(sweep.migrateAll(List.of(SCRATCH))).isZero();
        assertThat(storedText(id)).isEqualTo(before);
    }

    @Test
    @DisplayName("a row with only metadata (client_id, expires_at, template keys) is never rewritten")
    void metadataOnlyRowUntouched() {
        insert("{\"client_id\":\"abc\",\"expires_at\":\"2026-12-01T00:00:00Z\",\"credential_template_key\":\"gmail\",\"token_type\":\"Bearer\"}");

        assertThat(sweep.migrateAll(List.of(SCRATCH))).isZero();
    }

    @Test
    @DisplayName("the SQL-compared keys survive a sweep of a mixed row (the refresh scheduler keeps working)")
    void sqlReadKeysStayComparable() throws Exception {
        long id = insert("{\"refresh_token\":\"rt\",\"expires_at\":\"2026-12-01T00:00:00Z\",\"refresh_mode\":\"auto\",\"private_key\":\"-----BEGIN\"}");

        sweep.migrateAll(List.of(SCRATCH));

        assertThat(jdbc.queryForObject("SELECT credential_data->>'expires_at' FROM auth.credentials_sweep_test WHERE id = ?", String.class, id))
                .isEqualTo("2026-12-01T00:00:00Z");
        assertThat(jdbc.queryForObject("SELECT credential_data->>'refresh_mode' FROM auth.credentials_sweep_test WHERE id = ?", String.class, id))
                .isEqualTo("auto");
        Map<String, Object> row = stored(id);
        assertThat((String) row.get("refresh_token")).startsWith("ENC:");
        assertThat((String) row.get("private_key")).startsWith("ENC:");
    }

    @Test
    @DisplayName("a concurrent edit wins: a row changed between read and write is skipped, never overwritten")
    void concurrentEditIsNotClobbered() throws Exception {
        long id = insert("{\"api_secret\":\"s1\"}");
        // Simulate the user saving a new secret while the sweep holds the old text: the sweep's
        // compare-and-set must miss. Done by rewriting the row through a second sweep instance
        // whose read happened first is not observable here, so exercise the CAS directly.
        String stale = storedText(id);
        jdbc.update("UPDATE auth.credentials_sweep_test SET credential_data = CAST(? AS jsonb) WHERE id = ?",
                "{\"api_secret\":\"s2-user-edit\"}", id);
        int affected = jdbc.update(
                "UPDATE auth.credentials_sweep_test SET credential_data = CAST(? AS jsonb) WHERE id = ? AND credential_data::text = ?",
                "{\"api_secret\":\"ENC:would-be-stale\"}", id, stale);

        assertThat(affected).isZero();
        assertThat(stored(id).get("api_secret")).isEqualTo("s2-user-edit");
        // and the sweep then encrypts the user's value, not the stale one
        sweep.migrateAll(List.of(SCRATCH));
        assertThat(encryption.decryptSensitiveFields(stored(id)).get("api_secret")).isEqualTo("s2-user-edit");
    }

    @Test
    @DisplayName("a second run is a no-op and the pass pages past one page of rows")
    void idempotentAndPaged() {
        for (int i = 0; i < 205; i++) {
            insert("{\"api_secret\":\"s" + i + "\"}");
        }

        assertThat(sweep.migrateAll(List.of(SCRATCH))).isEqualTo(205);
        assertThat(sweep.migrateAll(List.of(SCRATCH))).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth.credentials_sweep_test WHERE credential_data->>'api_secret' NOT LIKE 'ENC:%'", Long.class))
                .isZero();
    }
}
