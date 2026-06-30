package com.apimarketplace.auth.credential.repository;

import com.apimarketplace.auth.credential.domain.CredentialModels.Credential;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialEnvironment;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialStatus;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialType;
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

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Postgres integration test for
 * {@link CredentialRepository#findOAuth2CredentialsExpiringBefore(Instant)}. Proves that:
 *
 * <ul>
 *   <li>the JSONB predicates parse and execute against a real engine,
 *   <li>rows without {@code expires_at} or without {@code refresh_token} are excluded,
 *   <li>non-OAuth2 rows are excluded even if they happen to carry an {@code expires_at} field,
 *   <li>{@code active} and {@code expiring} rows are both returned (both are non-terminal),
 *   <li>{@code error} and {@code needs_reauth} rows are skipped - retrying a terminal bucket
 *       before human intervention burns provider quota,
 *   <li>rows whose {@code refresh_cooldown_until} is in the future are skipped - the previous
 *       transient failure's jitter window must be honored,
 *   <li>results come back ordered by soonest-to-expire.
 * </ul>
 *
 * <p>Catches SQL grammar regressions that unit tests with mocked repos cannot see -
 * especially around the JSONB containment / timestamp-cast idioms, which are easy to
 * mis-type and still compile fine in Java.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("CredentialRepository.findOAuth2CredentialsExpiringBefore - Postgres integration")
class CredentialRepositoryExpiringOAuth2IT {

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

        // Encryption is a pass-through stub - we only care about JSONB query correctness,
        // not secret handling.
        CredentialEncryptionService enc = mock(CredentialEncryptionService.class);
        when(enc.encryptSensitiveFields(anyMapOf())).thenAnswer(inv -> inv.getArgument(0));
        when(enc.decryptSensitiveFields(anyMapOf())).thenAnswer(inv -> inv.getArgument(0));
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
    @DisplayName("returns active + expiring rows with refresh_token + expires_at inside the window")
    void basicFilter() {
        Instant now = Instant.now();
        // Expires in 5 min - should be picked up
        insertOAuth2("tenant-1", "a", "active",
                Map.of("refresh_token", "r", "expires_at", now.plusSeconds(300).toString()));
        // Expires in 15 min - outside a 10-min look-ahead window
        insertOAuth2("tenant-1", "b", "active",
                Map.of("refresh_token", "r", "expires_at", now.plusSeconds(900).toString()));
        // Active but has no refresh_token - can't refresh, must be skipped
        insertOAuth2("tenant-1", "c", "active",
                Map.of("expires_at", now.plusSeconds(60).toString()));
        // Active but has no expires_at - we cannot know when to refresh
        insertOAuth2("tenant-1", "d", "active",
                Map.of("refresh_token", "r"));
        // status=expiring is non-terminal and IS eligible - scheduler should pick it up.
        insertOAuth2("tenant-1", "e", "expiring",
                Map.of("refresh_token", "r", "expires_at", now.plusSeconds(60).toString()));

        List<Credential> result =
                repository.findOAuth2CredentialsExpiringBefore(now.plusSeconds(600));

        // Ordered by soonest-to-expire: e (60s) before a (300s).
        assertThat(result).extracting(Credential::name).containsExactly("e", "a");
    }

    @Test
    @DisplayName("terminal statuses (error, needs_reauth) are excluded - scheduler must not retry them")
    void terminalStatusesExcluded() {
        Instant now = Instant.now();
        insertOAuth2("tenant-1", "revoked", "needs_reauth",
                Map.of("refresh_token", "r", "expires_at", now.plusSeconds(60).toString()));
        insertOAuth2("tenant-1", "misconfigured", "error",
                Map.of("refresh_token", "r", "expires_at", now.plusSeconds(60).toString()));
        // Control row: ensures the query isn't a no-op.
        insertOAuth2("tenant-1", "alive", "active",
                Map.of("refresh_token", "r", "expires_at", now.plusSeconds(60).toString()));

        List<Credential> result =
                repository.findOAuth2CredentialsExpiringBefore(now.plusSeconds(600));

        assertThat(result).extracting(Credential::name).containsExactly("alive");
    }

    @Test
    @DisplayName("refresh_cooldown_until in the future excludes the row; past cooldown allows it")
    void cooldownPredicate() {
        Instant now = Instant.now();
        // Cooldown still active - previous transient failure's jitter window has not elapsed.
        insertOAuth2("tenant-1", "in_cooldown", "active",
                Map.of("refresh_token", "r",
                        "expires_at", now.plusSeconds(60).toString(),
                        "refresh_cooldown_until", now.plusSeconds(300).toString()));
        // Cooldown expired 1 hour ago - back in play.
        insertOAuth2("tenant-1", "cooldown_expired", "active",
                Map.of("refresh_token", "r",
                        "expires_at", now.plusSeconds(60).toString(),
                        "refresh_cooldown_until", now.minusSeconds(3600).toString()));
        // No cooldown field at all - coalesce with epoch keeps it eligible.
        insertOAuth2("tenant-1", "no_cooldown_field", "active",
                Map.of("refresh_token", "r",
                        "expires_at", now.plusSeconds(60).toString()));

        List<Credential> result =
                repository.findOAuth2CredentialsExpiringBefore(now.plusSeconds(600));

        assertThat(result)
                .extracting(Credential::name)
                .containsExactlyInAnyOrder("cooldown_expired", "no_cooldown_field");
    }

    @Test
    @DisplayName("non-OAuth2 rows never match, even if they carry expires_at")
    void nonOAuth2Excluded() {
        Instant now = Instant.now();
        insertRow("tenant-1", "api-key-row", "ApiKey", "active",
                Map.of("refresh_token", "r", "expires_at", now.plusSeconds(60).toString()));

        List<Credential> result =
                repository.findOAuth2CredentialsExpiringBefore(now.plusSeconds(600));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("results are ordered by expires_at ascending (soonest first)")
    void ordering() {
        Instant now = Instant.now();
        insertOAuth2("tenant-1", "later", "active",
                Map.of("refresh_token", "r", "expires_at", now.plusSeconds(500).toString()));
        insertOAuth2("tenant-2", "soonest", "active",
                Map.of("refresh_token", "r", "expires_at", now.plusSeconds(30).toString()));
        insertOAuth2("tenant-3", "middle", "active",
                Map.of("refresh_token", "r", "expires_at", now.plusSeconds(200).toString()));

        List<Credential> result =
                repository.findOAuth2CredentialsExpiringBefore(now.plusSeconds(600));

        assertThat(result)
                .extracting(Credential::name)
                .containsExactly("soonest", "middle", "later");
    }

    // ────────────────────────── helpers ──────────────────────────

    private void insertOAuth2(String tenantId, String name, String status, Map<String, Object> data) {
        insertRow(tenantId, name, "OAuth2", status, data);
    }

    private void insertRow(String tenantId, String name, String type, String status, Map<String, Object> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            jdbc.update("""
                    INSERT INTO auth.credentials (tenant_id, name, integration, type, status, credential_data)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb)
                    """, tenantId, name, "gmail", type, status, json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<String, Object> anyMapOf() {
        return org.mockito.ArgumentMatchers.anyMap();
    }
}
