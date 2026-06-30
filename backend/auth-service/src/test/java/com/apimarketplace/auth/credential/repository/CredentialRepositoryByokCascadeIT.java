package com.apimarketplace.auth.credential.repository;

import com.apimarketplace.auth.credential.domain.CredentialModels.Credential;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Postgres integration test for the BYOK cascade-revoke pair on
 * {@link CredentialRepository}: {@link CredentialRepository#findActiveByTenantIdAndIntegrationNormalized}
 * and {@link CredentialRepository#countActiveByTenantIdAndIntegrationNormalized}.
 *
 * <p>Pins the asymmetry: {@code auth.platform_credentials.integration_name} is stored in
 * normalized form ({@code "audittracking"}, {@code "azuretranslator"}), but
 * {@code auth.credentials.integration} is stored RAW ({@code "audit-tracking"},
 * {@code "azure_translator"}, capitals from displayName fallback). The SQL must strip
 * non-alphanumeric and lowercase the stored value to match. Without this, the cascade
 * would silently miss every dependent row for any iconSlug containing a separator.
 *
 * <p>Also pins the active/expiring filter - already-terminal rows are NOT counted /
 * returned because re-scrubbing them would lose original failure diagnostics and
 * the displayed delete-impact would over-report.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("CredentialRepository - BYOK cascade-revoke normalized integration match (Postgres integration)")
class CredentialRepositoryByokCascadeIT {

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
    @DisplayName("matches a hyphenated raw iconSlug ('audit-tracking') against a normalized BYOK key ('audittracking') - regression for the silent-zombie bug raised in audit")
    void matchesHyphenatedRawSlug() {
        insertActive("tenant-A", "credA", "audit-tracking");
        insertActive("tenant-A", "credB", "audit-tracking");
        // Negative control: a different tenant's row with the same raw slug
        // must not leak across the tenant_id boundary.
        insertActive("tenant-B", "credC", "audit-tracking");

        List<Credential> result =
                repository.findActiveByTenantIdAndIntegrationNormalized("tenant-A", "audittracking");

        assertThat(result).extracting(Credential::name)
                .as("both tenant-A's hyphenated slug rows must match the normalized BYOK key")
                .containsExactlyInAnyOrder("credA", "credB");
    }

    @Test
    @DisplayName("matches an underscored raw iconSlug ('azure_translator') against a normalized BYOK key ('azuretranslator')")
    void matchesUnderscoredRawSlug() {
        insertActive("tenant-A", "credA", "azure_translator");

        List<Credential> result =
                repository.findActiveByTenantIdAndIntegrationNormalized("tenant-A", "azuretranslator");

        assertThat(result).extracting(Credential::name).containsExactly("credA");
    }

    @Test
    @DisplayName("matches a mixed-case raw integration string ('Google Cloud Platform') against the normalized BYOK key ('googlecloudplatform')")
    void matchesMixedCaseRawSlug() {
        insertActive("tenant-A", "credA", "Google Cloud Platform");

        List<Credential> result =
                repository.findActiveByTenantIdAndIntegrationNormalized(
                        "tenant-A", "googlecloudplatform");

        assertThat(result).extracting(Credential::name).containsExactly("credA");
    }

    @Test
    @DisplayName("excludes already-terminal rows - error and needs_reauth are NOT in the active filter")
    void excludesTerminal() {
        insertWithStatus("tenant-A", "active1", "gmail", "active");
        insertWithStatus("tenant-A", "expiring1", "gmail", "expiring");
        insertWithStatus("tenant-A", "errorRow", "gmail", "error");
        insertWithStatus("tenant-A", "reauthRow", "gmail", "needs_reauth");

        List<Credential> result =
                repository.findActiveByTenantIdAndIntegrationNormalized("tenant-A", "gmail");

        assertThat(result).extracting(Credential::name)
                .as("terminal rows must be excluded so cascade is idempotent and "
                        + "delete-impact does not over-report")
                .containsExactlyInAnyOrder("active1", "expiring1");
    }

    @Test
    @DisplayName("count mirrors find: same filter semantics for delete-impact preview")
    void countMatchesFind() {
        insertActive("tenant-A", "a1", "audit-tracking");
        insertActive("tenant-A", "a2", "audit-tracking");
        insertWithStatus("tenant-A", "terminal", "audit-tracking", "needs_reauth");
        insertActive("tenant-B", "other", "audit-tracking");

        int count = repository.countActiveByTenantIdAndIntegrationNormalized(
                "tenant-A", "audittracking");

        assertThat(count)
                .as("counts only tenant-A's active rows, not tenant-B's and not terminal")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("findActiveIntegrationsByTenantId - lowercase 'active' filter + LOWER(integration) projection (regression for PR4 uppercase typo that returned empty set for every tenant)")
    void findActiveIntegrationsByTenantIdLowercasesAndFiltersActive() {
        // Active rows with mixed-case integration values - must be lowercased in the result.
        insertWithStatus("tenant-A", "c1", "Gmail",   "active");
        insertWithStatus("tenant-A", "c2", "SerpAPI", "active");
        insertWithStatus("tenant-A", "c3", "apify",   "active");
        // Non-active statuses - must NOT appear in the configured set.
        insertWithStatus("tenant-A", "c4", "Slack",   "expiring");
        insertWithStatus("tenant-A", "c5", "Notion",  "needs_reauth");
        insertWithStatus("tenant-A", "c6", "Asana",   "error");
        // Other-tenant row - must NOT cross the tenant boundary.
        insertWithStatus("tenant-B", "c7", "Gmail",   "active");

        java.util.Set<String> result = repository.findActiveIntegrationsByTenantId("tenant-A");

        assertThat(result)
                .as("only active rows of tenant-A, integration names lowercased")
                .containsExactlyInAnyOrder("gmail", "serpapi", "apify");
    }

    @Test
    @DisplayName("returns zero when no row matches - no fallback, no platform-wide leak")
    void zeroForUnknownIntegration() {
        insertActive("tenant-A", "a1", "gmail");

        List<Credential> result =
                repository.findActiveByTenantIdAndIntegrationNormalized(
                        "tenant-A", "completely-unrelated");

        assertThat(result).isEmpty();
        assertThat(repository.countActiveByTenantIdAndIntegrationNormalized(
                "tenant-A", "completely-unrelated")).isZero();
    }

    // ────────────────────────── helpers ──────────────────────────

    private void insertActive(String tenantId, String name, String integration) {
        insertWithStatus(tenantId, name, integration, "active");
    }

    private void insertWithStatus(String tenantId, String name, String integration, String status) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("access_token", "at"));
            jdbc.update("""
                    INSERT INTO auth.credentials (tenant_id, name, integration, type, status, credential_data)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb)
                    """, tenantId, name, integration, "OAuth2", status, json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<String, Object> anyMapOf() {
        return org.mockito.ArgumentMatchers.anyMap();
    }
}
