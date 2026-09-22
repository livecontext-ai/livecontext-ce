package com.apimarketplace.auth.credential.repository;

import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.testsupport.ScratchPostgres;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A catalog re-import must never destroy a user's secret.
 *
 * <p>It used to. When a seed's auth type changed, the importer called an endpoint that ran
 * {@code DELETE FROM auth.credentials WHERE integration = ?} across every tenant, with no backup and
 * no confirmation, and a user lost a credential to it. Most of what is stored here cannot be
 * re-obtained by the person who lost it: an OAuth refresh token is revoked the moment it is deleted,
 * and an API key is usually displayed exactly once, at creation.
 *
 * <p>These run against a real Postgres because the guarantee is a property of the SQL: that the
 * statement TRANSITIONS rows instead of removing them, and leaves {@code credential_data} byte for
 * byte where it was. A mocked JdbcTemplate would assert the string we wrote, not what it does.
 *
 * <p><b>How it runs.</b> It talks to a plain Postgres over JDBC rather than starting one. The first
 * version of this class used Testcontainers and was named {@code ...IT}, and it executed nowhere
 * for the plainest reason: no {@code -Dtest} list named it, and every backend step in the workflow
 * is {@code -Dtest}-filtered. (An explicit {@code -Dtest=<FQN>} does run an {@code *IT} class; the
 * suffix only puts it outside the DEFAULT surefire includes.) Naming it would not have been enough
 * either: the {@code arc-build} runners expose no Docker socket and the class did not set
 * {@code disabledWithoutDocker}, so Testcontainers would have ERRORED there rather than skipped.
 * A guarantee whose only proof never executes is the green-by-absence shape this file exists to
 * close. CI provides a {@code pgvector/pgvector:pg16} service container on the "Auth ownership
 * tests" step and sets {@code CREDENTIAL_TEST_PG_URL}, the same way
 * {@link CredentialRepositoryNameSqlTest} runs.
 *
 * <p>{@link ScratchPostgres} owns that decision and documents it: skipped on a laptop with no
 * scratch database, a hard failure on CI, and a refusal for any URL that does not visibly name a
 * scratch database. This class TRUNCATEs {@code auth.credentials}, which is why that last check
 * matters. The other half of the promise lives in the workflow: using that helper is what puts a
 * class in scope for the test-wiring gate, so dropping its name from a {@code -Dtest} list is a
 * red build rather than a silently smaller one. Locally:
 * {@code createdb lc_auth_test && CREDENTIAL_TEST_PG_URL=jdbc:postgresql://localhost:5432/lc_auth_test
 * mvn -pl auth-service test -Dtest=CredentialRepositoryNeverDestroysPostgresTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("CredentialRepository - an import takes a credential out of service, never away")
class CredentialRepositoryNeverDestroysPostgresTest {

    private static final ScratchPostgres DB = ScratchPostgres.forPrefix(
            "CREDENTIAL_TEST_PG",
            "it is the only executable proof that a catalog re-import takes a credential out of "
                    + "service instead of deleting it, and a user has already lost a secret to the "
                    + "statement it replaced");

    private JdbcTemplate jdbc;
    private CredentialRepository repository;
    private ObjectMapper objectMapper;

    @BeforeAll
    void setUpSchema() {
        DB.require();

        DriverManagerDataSource ds = new DriverManagerDataSource(DB.url(), DB.user(), DB.password());
        ds.setDriverClassName("org.postgresql.Driver");
        this.jdbc = new JdbcTemplate(ds);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(ds);
        this.objectMapper = new ObjectMapper();

        CredentialEncryptionService enc = mock(CredentialEncryptionService.class);
        when(enc.encryptSensitiveFields(anyMap())).thenAnswer(inv -> inv.getArgument(0));
        when(enc.decryptSensitiveFields(anyMap())).thenAnswer(inv -> inv.getArgument(0));
        when(enc.decrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(enc.encrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS auth");
        jdbc.execute("DROP TABLE IF EXISTS auth.credentials CASCADE");
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
    @DisplayName("THE guarantee: the secret is still there afterwards, byte for byte")
    void theStoredSecretSurvives() {
        insert("tenant-A", "my gmail", "gmail", "active",
                Map.of("access_token", "ya29.the-only-copy", "refresh_token", "1//revoked-if-deleted"));

        int marked = repository.markNeedsReauthByIntegration("gmail");

        assertThat(marked).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT credential_data->>'refresh_token' FROM auth.credentials WHERE name = 'my gmail'",
                String.class))
                .as("a deleted refresh token is revoked; the owner cannot get this value back")
                .isEqualTo("1//revoked-if-deleted");
        assertThat(jdbc.queryForObject(
                "SELECT credential_data->>'access_token' FROM auth.credentials WHERE name = 'my gmail'",
                String.class))
                .isEqualTo("ya29.the-only-copy");
    }

    @Test
    @DisplayName("nothing is removed: the row count is identical before and after")
    void noRowIsDeleted() {
        insert("tenant-A", "a", "gmail", "active", Map.of("access_token", "x"));
        insert("tenant-B", "b", "gmail", "active", Map.of("access_token", "y"));
        insert("tenant-C", "c", "slack", "active", Map.of("access_token", "z"));
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM auth.credentials", Integer.class);

        repository.markNeedsReauthByIntegration("gmail");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth.credentials", Integer.class))
                .as("the statement this replaced removed every gmail row of every tenant")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("the credential stops being USED, which is the whole legitimate goal")
    void theCredentialIsTakenOutOfService() {
        insert("tenant-A", "a", "gmail", "active", Map.of("access_token", "x"));

        repository.markNeedsReauthByIntegration("gmail");

        // Every read path filters on status = 'active' / IN ('active','expiring'), so this one
        // transition is what stops the credential being handed to a provider.
        assertThat(repository.findActiveIntegrationsByTenantId("tenant-A"))
                .as("an unusable credential must not be offered as an active integration")
                .doesNotContain("gmail");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM auth.credentials WHERE name = 'a'", String.class))
                .isEqualTo("needs_reauth");
    }

    @Test
    @DisplayName("it reaches every tenant, because an auth-type change affects all of them")
    void itSpansTenants() {
        insert("tenant-A", "a", "gmail", "active", Map.of("access_token", "x"));
        insert("tenant-B", "b", "gmail", "expiring", Map.of("access_token", "y"));

        assertThat(repository.markNeedsReauthByIntegration("gmail")).isEqualTo(2);
    }

    @Test
    @DisplayName("another integration is untouched, so one seed's change cannot disarm the rest")
    void otherIntegrationsAreUntouched() {
        insert("tenant-A", "keep", "slack", "active", Map.of("access_token", "keep-me"));

        repository.markNeedsReauthByIntegration("gmail");

        assertThat(jdbc.queryForObject(
                "SELECT status FROM auth.credentials WHERE name = 'keep'", String.class))
                .isEqualTo("active");
    }

    @Test
    @DisplayName("already-terminal rows are left alone, so the count is what actually changed")
    void terminalRowsAreNotRewritten() {
        insert("tenant-A", "already", "gmail", "needs_reauth", Map.of("access_token", "x"));
        insert("tenant-A", "broken", "gmail", "error", Map.of("access_token", "y"));
        insert("tenant-A", "live", "gmail", "active", Map.of("access_token", "z"));

        assertThat(repository.markNeedsReauthByIntegration("gmail"))
                .as("only the live row transitions; rewriting the others would lose why they failed")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM auth.credentials WHERE name = 'broken'", String.class))
                .isEqualTo("error");
    }

    @Test
    @DisplayName("running it twice changes nothing the second time")
    void itIsIdempotent() {
        insert("tenant-A", "a", "gmail", "active", Map.of("access_token", "x"));

        assertThat(repository.markNeedsReauthByIntegration("gmail")).isEqualTo(1);
        assertThat(repository.markNeedsReauthByIntegration("gmail")).isZero();
    }

    private void insert(String tenantId, String name, String integration, String status,
                        Map<String, String> data) {
        try {
            jdbc.update("""
                    INSERT INTO auth.credentials (tenant_id, name, integration, type, status, credential_data)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb)
                    """, tenantId, name, integration, "OAuth2", status,
                    objectMapper.writeValueAsString(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
