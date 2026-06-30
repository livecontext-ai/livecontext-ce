package com.apimarketplace.auth.credential.repository;

import com.apimarketplace.common.security.CredentialEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Postgres integration test for {@link CredentialRepository#touchLastUsed(Long)}. Proves the
 * real single-column write against a live engine - the exact behaviour mocked-repository unit
 * tests cannot see:
 *
 * <ul>
 *   <li>{@code last_used} is set to ~now on a row that had it NULL (the Twilio / Basic-auth case
 *       that previously showed "never used" because only the OAuth2-refresh path stamped it),
 *   <li>{@code updated_at} is NOT moved - a use must not look like an edit,
 *   <li>{@code credential_data} is left byte-for-byte intact - the targeted UPDATE never goes
 *       through {@code save()} and so never re-encrypts the secret,
 *   <li>an existing {@code last_used} is advanced forward,
 *   <li>a touch on an unknown id is a no-op returning 0 (never throws).
 * </ul>
 *
 * <p>Catches column-name / extra-column-write regressions that compile fine in Java.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("CredentialRepository.touchLastUsed - Postgres integration")
class CredentialRepositoryTouchLastUsedIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("auth_test")
            .withUsername("test")
            .withPassword("test");

    private JdbcTemplate jdbc;
    private CredentialRepository repository;
    private ObjectMapper objectMapper;

    @BeforeAll
    void setUpSchema() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName(POSTGRES.getDriverClassName());
        this.jdbc = new JdbcTemplate(ds);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(ds);
        this.objectMapper = new ObjectMapper();

        // Encryption is a pass-through stub - touchLastUsed never decrypts/encrypts, and we
        // assert credential_data is left untouched, so identity encryption is enough.
        CredentialEncryptionService enc = mock(CredentialEncryptionService.class);
        when(enc.decrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(enc.encrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS auth");
        jdbc.execute("""
                CREATE TABLE auth.credentials (
                    id BIGSERIAL PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    organization_id VARCHAR(255),
                    name VARCHAR(255) NOT NULL,
                    integration VARCHAR(255),
                    type VARCHAR(50) NOT NULL,
                    environment VARCHAR(50) NOT NULL DEFAULT 'Production',
                    status VARCHAR(50) NOT NULL DEFAULT 'active',
                    description TEXT,
                    credential_data JSONB NOT NULL DEFAULT '{}',
                    scopes TEXT[],
                    tags TEXT[],
                    owner VARCHAR(255),
                    icon_url VARCHAR(500),
                    is_default BOOLEAN NOT NULL DEFAULT FALSE,
                    last_used TIMESTAMPTZ,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);

        this.repository = new CredentialRepository(jdbc, namedJdbc, objectMapper, enc);
    }

    @BeforeEach
    void truncate() {
        jdbc.execute("TRUNCATE auth.credentials RESTART IDENTITY");
    }

    @Test
    @DisplayName("stamps last_used on a NULL row without moving updated_at or altering credential_data")
    void stampsLastUsedLeavingUpdatedAtAndDataIntact() {
        long id = insertBasicAuth("5", "Twilio",
                Map.of("username", "AC3cb1ac", "password", "authtoken"));
        // Baseline: a fresh row has NULL last_used and a fixed updated_at + credential_data.
        assertThat(getTimestamp(id, "last_used")).isNull();
        Timestamp updatedAtBefore = getTimestamp(id, "updated_at");
        String dataBefore = getCredentialDataText(id);

        int rows = repository.touchLastUsed(id);

        assertThat(rows).isEqualTo(1);
        Timestamp lastUsedAfter = getTimestamp(id, "last_used");
        assertThat(lastUsedAfter).isNotNull();
        assertThat(lastUsedAfter.toInstant())
                .isCloseTo(Instant.now(), within(30, ChronoUnit.SECONDS));
        // The whole point: a use is not an edit. updated_at and the encrypted secret must not move.
        assertThat(getTimestamp(id, "updated_at")).isEqualTo(updatedAtBefore);
        assertThat(getCredentialDataText(id)).isEqualTo(dataBefore);
    }

    @Test
    @DisplayName("advances an existing last_used forward")
    void advancesExistingLastUsed() {
        long id = insertBasicAuth("5", "Twilio", Map.of("password", "t"));
        Instant old = Instant.now().minus(2, ChronoUnit.DAYS);
        jdbc.update("UPDATE auth.credentials SET last_used = ? WHERE id = ?", Timestamp.from(old), id);

        repository.touchLastUsed(id);

        Timestamp after = getTimestamp(id, "last_used");
        assertThat(after).isNotNull();
        assertThat(after.toInstant()).isAfter(old.plus(1, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("touch on an unknown id is a no-op returning 0 (never throws)")
    void unknownIdIsNoOp() {
        int rows = repository.touchLastUsed(999_999L);
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("null id is a no-op returning 0")
    void nullIdIsNoOp() {
        assertThat(repository.touchLastUsed(null)).isZero();
    }

    // ────────────────────────── helpers ──────────────────────────

    private long insertBasicAuth(String tenantId, String name, Map<String, Object> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            return jdbc.queryForObject("""
                    INSERT INTO auth.credentials (tenant_id, name, integration, type, status, credential_data)
                    VALUES (?, ?, ?, 'Basic Auth', 'active', ?::jsonb)
                    RETURNING id
                    """, Long.class, tenantId, name, "twilio", json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Timestamp getTimestamp(long id, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM auth.credentials WHERE id = ?", Timestamp.class, id);
    }

    private String getCredentialDataText(long id) {
        return jdbc.queryForObject(
                "SELECT credential_data::text FROM auth.credentials WHERE id = ?", String.class, id);
    }

    private static org.assertj.core.data.TemporalUnitOffset within(long amount, ChronoUnit unit) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(amount, unit);
    }
}
