package com.apimarketplace.auth.credential.repository;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.CategoryInfo;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that exercises every SQL query in {@link PlatformCredentialRepository}
 * against a real PostgreSQL database. Catches SQL grammar regressions that unit tests
 * with mocked repositories cannot see - in particular the Java-text-block
 * trailing-whitespace-stripping bug that broke prod in commit 602c525c5
 * (queries like {@code AND """ + TENANT_FILTER + """} became {@code AND(tenant_id...}
 * because text blocks strip trailing whitespace on every content line per JEP 378).
 *
 * <p>The test is deliberately minimal: it creates just the {@code auth.platform_credentials}
 * table (not the full Flyway set), instantiates the repository directly, and runs each
 * public method. The assertion is that every query <em>parses and executes</em> - the
 * returned data is secondary.
 *
 * <p>Schema mirrors {@code V24__move_credentials_to_auth_schema.sql} +
 * {@code V82__custom_api_registration.sql} (tenant_id column) +
 * {@code V101__platform_credential_markup.sql} (default_markup_credits, max_calls_per_run) +
 * {@code V103__multi_variant_credentials.sql} (variant column + UNIQUE(integration_name, variant)) +
 * {@code V297__platform_credential_unverified_warning.sql} (show_unverified_app_warning).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("PlatformCredentialRepository - Postgres integration")
class PlatformCredentialRepositoryPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("auth_test")
            .withUsername("test")
            .withPassword("test");

    private JdbcTemplate jdbc;
    private PlatformCredentialRepository repository;

    @BeforeAll
    void setUpSchema() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName(POSTGRES.getDriverClassName());
        this.jdbc = new JdbcTemplate(ds);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(ds);

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS auth");
        jdbc.execute("""
                CREATE TABLE auth.platform_credentials (
                    id BIGSERIAL PRIMARY KEY,
                    integration_name VARCHAR(100) NOT NULL,
                    display_name VARCHAR(255) NOT NULL,
                    auth_type VARCHAR(50) NOT NULL DEFAULT 'oauth2',
                    client_id VARCHAR(500),
                    client_secret TEXT,
                    api_key TEXT,
                    username VARCHAR(255),
                    password TEXT,
                    auth_url VARCHAR(500),
                    token_url VARCHAR(500),
                    default_scopes TEXT,
                    icon_slug VARCHAR(100),
                    category VARCHAR(100),
                    description TEXT,
                    custom_fields JSONB DEFAULT '{}',
                    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    created_by VARCHAR(255),
                    tenant_id VARCHAR(255) DEFAULT NULL,
                    default_markup_credits DECIMAL(10,6) NOT NULL DEFAULT 0,
                    max_calls_per_run INTEGER NOT NULL DEFAULT 500,
                    variant VARCHAR(50) NOT NULL DEFAULT 'primary',
                    show_unverified_app_warning BOOLEAN NOT NULL DEFAULT TRUE,
                    organization_id VARCHAR(255) DEFAULT NULL
                )
                """);
        // Mirrors V362: org dimension added to the uniqueness index.
        jdbc.execute("""
                CREATE UNIQUE INDEX idx_platform_cred_integration_tenant
                  ON auth.platform_credentials(integration_name, COALESCE(tenant_id, '__PLATFORM__'),
                                               COALESCE(organization_id, '__PERSONAL__'), variant)
                """);
        // Mirrors V103's obsolete UNIQUE(integration_name, variant) constraint
        // then V364 which drops it. Recreating + dropping here reproduces the
        // exact prod schema sequence so uniqueIndexAllowsSameIntegrationAcross-
        // Workspaces genuinely proves the fix: with this constraint present (and
        // every tenant BYOK using variant='primary') a second workspace's insert
        // collides with a duplicate-key error - exactly the prod 500. Removing
        // the V364 drop below makes that test fail, guarding the regression.
        jdbc.execute("ALTER TABLE auth.platform_credentials "
                + "ADD CONSTRAINT platform_credentials_integration_variant_key UNIQUE (integration_name, variant)");
        jdbc.execute("ALTER TABLE auth.platform_credentials "
                + "DROP CONSTRAINT IF EXISTS platform_credentials_integration_variant_key");
        jdbc.execute("""
                CREATE TABLE auth.platform_credential_endpoints (
                    id BIGSERIAL PRIMARY KEY,
                    platform_credential_id BIGINT NOT NULL
                        REFERENCES auth.platform_credentials(id) ON DELETE CASCADE,
                    tool_id VARCHAR(255) NOT NULL,
                    tool_name VARCHAR(255),
                    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT uk_platform_credential_endpoints UNIQUE (platform_credential_id, tool_id)
                )
                """);

        CredentialEncryptionService encryption = new CredentialEncryptionService(
                "integration-test-password", "0123456789abcdef");
        this.repository = new PlatformCredentialRepository(
                jdbc, namedJdbc, encryption, new ObjectMapper());
    }

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM auth.platform_credential_endpoints");
        jdbc.update("DELETE FROM auth.platform_credentials");
    }

    @AfterAll
    void tearDown() {
        cleanTables();
    }

    // ============================================================================
    //  The 8 tenant-aware queries - these are the ones that broke in prod. Each
    //  must execute without a PSQLException for the fix to be validated.
    // ============================================================================

    @Test
    @DisplayName("Reproduces prod 500 from commit 602c525c5: save Gmail tenant cred, read back without SQL grammar error")
    void reproducesProdFiveHundredFromTextBlockConcatBug() {
        // Exact call sequence the Gmail-save UI makes: POST /api/credentials/oauth2/initiate
        // triggers `PlatformCredentialService` which calls `findByIntegrationName(name, tenantId)`.
        // Pre-fix, this threw BadSqlGrammarException → 500 at the filter chain.
        repository.save(tenantScoped("gmail", "tenant-prod"));

        Optional<PlatformCredential> found = repository.findByIntegrationName("gmail", "tenant-prod");

        assertThat(found)
                .as("the exact read path that returned 500 in prod")
                .isPresent();
    }

    @Test
    @DisplayName("findByIntegrationName(name, tenantId) runs without SQL grammar error and returns tenant-scoped priority")
    void findByIntegrationNameTenantAware() {
        repository.save(platformWide("gmail"));
        PlatformCredential tenantCred = tenantScoped("gmail", "tenant-A");
        repository.save(tenantCred);

        Optional<PlatformCredential> found = repository.findByIntegrationName("gmail", "tenant-A");

        assertThat(found).isPresent();
        assertThat(found.get().tenantId()).isEqualTo("tenant-A");
    }

    @Test
    @DisplayName("findByIntegrationName(name, null) falls back to platform-wide row when no tenant match")
    void findByIntegrationNamePlatformFallback() {
        repository.save(platformWide("slack"));

        Optional<PlatformCredential> found = repository.findByIntegrationName("slack", "tenant-X");

        assertThat(found).isPresent();
        assertThat(found.get().tenantId()).isNull();
    }

    @Test
    @DisplayName("findAll(tenantId) runs without SQL grammar error and returns tenant-visible rows")
    void findAllTenantAware() {
        repository.save(platformWide("gmail"));
        repository.save(tenantScoped("slack", "tenant-A"));
        repository.save(tenantScoped("notion", "tenant-B"));

        List<PlatformCredential> visible = repository.findAll("tenant-A");

        assertThat(visible).extracting(PlatformCredential::integrationName)
                .containsExactlyInAnyOrder("gmail", "slack");
    }

    @Test
    @DisplayName("findAllEnabled(tenantId) runs without SQL grammar error and excludes disabled")
    void findAllEnabledTenantAware() {
        repository.save(platformWide("gmail"));
        repository.save(tenantScoped("slack", "tenant-A"));
        repository.setEnabled("slack", false, "tenant-A");

        List<PlatformCredential> enabled = repository.findAllEnabled("tenant-A");

        assertThat(enabled).extracting(PlatformCredential::integrationName)
                .containsExactly("gmail");
    }

    @Test
    @DisplayName("findByCategory(category, tenantId) runs without SQL grammar error")
    void findByCategoryTenantAware() {
        repository.save(withCategory(platformWide("gmail"), "mail"));
        repository.save(withCategory(tenantScoped("slack", "tenant-A"), "chat"));

        List<PlatformCredential> chat = repository.findByCategory("chat", "tenant-A");

        assertThat(chat).extracting(PlatformCredential::integrationName).containsExactly("slack");
    }

    @Test
    @DisplayName("findByAuthType(authType, tenantId) runs without SQL grammar error")
    void findByAuthTypeTenantAware() {
        repository.save(platformWide("gmail"));
        repository.save(withAuthType(tenantScoped("stripe", "tenant-A"), AuthType.API_KEY));

        List<PlatformCredential> apiKeyCreds = repository.findByAuthType(AuthType.API_KEY, "tenant-A");

        assertThat(apiKeyCreds).extracting(PlatformCredential::integrationName).containsExactly("stripe");
    }

    @Test
    @DisplayName("findDistinctCategories(tenantId) runs without SQL grammar error")
    void findDistinctCategoriesTenantAware() {
        repository.save(withCategory(platformWide("gmail"), "mail"));
        repository.save(withCategory(tenantScoped("slack", "tenant-A"), "chat"));

        List<String> categories = repository.findDistinctCategories("tenant-A");

        assertThat(categories).containsExactlyInAnyOrder("mail", "chat");
    }

    @Test
    @DisplayName("countByCategory(tenantId) runs without SQL grammar error")
    void countByCategoryTenantAware() {
        repository.save(withCategory(platformWide("gmail"), "mail"));
        repository.save(withCategory(tenantScoped("outlook", "tenant-A"), "mail"));
        repository.save(withCategory(tenantScoped("slack", "tenant-A"), "chat"));

        List<CategoryInfo> counts = repository.countByCategory("tenant-A");

        assertThat(counts).extracting(CategoryInfo::name)
                .containsExactlyInAnyOrder("mail", "chat");
    }

    // ============================================================================
    //  The platform-wide variants (were not broken, but we still want regression
    //  coverage so future edits can't silently break them either).
    // ============================================================================

    @Test
    @DisplayName("All platform-wide (no tenant) variants execute successfully")
    void platformWideVariantsAllExecute() {
        repository.save(withCategory(platformWide("gmail"), "mail"));
        repository.save(withCategory(platformWide("notion"), "docs"));

        assertThat(repository.findByIntegrationName("gmail")).isPresent();
        assertThat(repository.findAll()).hasSize(2);
        assertThat(repository.findAllEnabled()).hasSize(2);
        assertThat(repository.findByCategory("mail")).hasSize(1);
        assertThat(repository.findByAuthType(AuthType.OAUTH2)).hasSize(2);
        assertThat(repository.findOAuth2ByIntegrationName("gmail")).isPresent();
        assertThat(repository.findDistinctCategories()).containsExactlyInAnyOrder("mail", "docs");
        assertThat(repository.countByCategory()).hasSize(2);
        assertThat(repository.existsByIntegrationName("gmail")).isTrue();
    }

    // ============================================================================
    //  Mutations - insert, update, delete, toggle
    // ============================================================================

    @Test
    @DisplayName("save inserts then update round-trips all fields including encrypted ones")
    void saveInsertAndUpdateRoundtrip() {
        PlatformCredential inserted = repository.save(platformWide("gmail"));
        assertThat(inserted.id()).isNotNull();

        PlatformCredential modified = new PlatformCredential(
                inserted.id(), inserted.integrationName(), "Gmail v2",
                AuthType.API_KEY, "new-client-id", "new-secret",
                "new-api-key", "user", "pwd",
                "https://auth", "https://token", "scope1 scope2",
                "gmail", "mail", "updated", false, false,
                Map.of("foo", "bar"),
                inserted.defaultMarkupCredits(), inserted.maxCallsPerRun(),
                inserted.createdAt(), inserted.updatedAt(),
                inserted.createdBy(), inserted.tenantId(), inserted.variant());
        repository.save(modified);

        PlatformCredential fetched = repository.findByIntegrationName("gmail").orElseThrow();
        assertThat(fetched.displayName()).isEqualTo("Gmail v2");
        assertThat(fetched.apiKey()).isEqualTo("new-api-key");
        assertThat(fetched.isEnabled()).isFalse();
        assertThat(fetched.showUnverifiedAppWarning()).isFalse();

        // Verify ciphertext-at-rest: the column must not store the plaintext.
        // The row mapper decrypts on read, so `fetched.apiKey()` is plaintext, but
        // a direct SELECT of the raw column reveals whether encryption actually ran.
        String rawApiKey = jdbc.queryForObject(
                "SELECT api_key FROM auth.platform_credentials WHERE id = ?",
                String.class, inserted.id());
        assertThat(rawApiKey)
                .as("api_key must be encrypted at rest (ENC: prefix)")
                .isNotEqualTo("new-api-key")
                .startsWith("ENC:");
    }

    @Test
    @DisplayName("deleteByIntegrationName tenant-scoped only removes tenant row, not platform-wide")
    void deleteByIntegrationNameTenantOnly() {
        repository.save(platformWide("gmail"));
        repository.save(tenantScoped("gmail", "tenant-A"));

        boolean deleted = repository.deleteByIntegrationName("gmail", "tenant-A");

        assertThat(deleted).isTrue();
        assertThat(repository.findByIntegrationName("gmail", "tenant-A")).isPresent()
                .get().extracting(PlatformCredential::tenantId).isNull();
    }

    @Test
    @DisplayName("existsByIntegrationName tenant-aware returns true for platform-wide fallback")
    void existsByIntegrationNameTenantAware() {
        repository.save(platformWide("gmail"));

        assertThat(repository.existsByIntegrationName("gmail", "tenant-A")).isTrue();
        assertThat(repository.existsByIntegrationName("nonexistent", "tenant-A")).isFalse();
    }

    @Test
    @DisplayName("toggleEndpoint and isEndpointEnabled round-trip")
    void endpointRoundtrip() {
        PlatformCredential cred = repository.save(platformWide("gmail"));

        repository.toggleEndpoint(cred.id(), "send_email", false);

        assertThat(repository.isEndpointEnabled(cred.id(), "send_email")).isFalse();
        assertThat(repository.findEndpointsByCredentialId(cred.id())).hasSize(1);
    }

    @Test
    @DisplayName("setEnabledForVariant flips exactly one row when two variants share the same integration_name")
    void setEnabledForVariantFlipsOnlyMatchedRow() {
        // Two rows for gmail - one OAuth2, one API-key. This is the Phase 2d
        // invariant: the UNIQUE(integration_name, variant) key lets both
        // coexist, and `setEnabledForVariant` must flip exactly the one row
        // matching the (name, variant) pair.
        repository.save(withVariant(platformWide("gmail"), "oauth2"));
        repository.save(withVariant(platformWide("gmail"), "api_key"));

        boolean flipped = repository.setEnabledForVariant("gmail", "oauth2", false);

        assertThat(flipped).as("oauth2 row should have flipped").isTrue();

        // Assert state directly in DB so we don't depend on findByIntegrationName's
        // ordering / fallback semantics - this is the invariant the admin UI leans on.
        Boolean oauthEnabled = jdbc.queryForObject(
                "SELECT is_enabled FROM auth.platform_credentials WHERE integration_name = ? AND variant = ?",
                Boolean.class, "gmail", "oauth2");
        Boolean apiKeyEnabled = jdbc.queryForObject(
                "SELECT is_enabled FROM auth.platform_credentials WHERE integration_name = ? AND variant = ?",
                Boolean.class, "gmail", "api_key");
        assertThat(oauthEnabled).as("oauth2 row flipped to disabled").isFalse();
        assertThat(apiKeyEnabled).as("api_key row untouched").isTrue();
    }

    @Test
    @DisplayName("setEnabledForVariant inserts a missing sibling placeholder when another platform-wide variant already exists")
    void setEnabledForVariantUpsertsPlaceholderWhenSiblingVariantExists() {
        repository.save(withAuthType(withVariant(platformWide("airtable"), "api_key"), AuthType.API_KEY));

        boolean result = repository.setEnabledForVariant("airtable", "bearer_token", false);

        assertThat(result)
                .as("V82's old (integration_name, tenant) index must not reject the second platform-wide variant")
                .isTrue();
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth.platform_credentials WHERE integration_name = ?",
                Integer.class, "airtable");
        assertThat(count).as("api_key plus bearer_token rows must coexist").isEqualTo(2);
        Map<String, Object> bearerRow = jdbc.queryForMap(
                "SELECT auth_type, is_enabled, tenant_id FROM auth.platform_credentials WHERE integration_name = ? AND variant = ?",
                "airtable", "bearer_token");
        assertThat(bearerRow.get("auth_type")).isEqualTo("bearer");
        assertThat(bearerRow.get("is_enabled")).isEqualTo(false);
        assertThat(bearerRow.get("tenant_id")).isNull();
    }

    @Test
    @DisplayName("setEnabledForVariant inserts a placeholder when no (name, variant) row exists so admin can disable a variant before any secret has been saved")
    void setEnabledForVariantUpsertsPlaceholderWhenMissing() {
        boolean result = repository.setEnabledForVariant("airtable", "bearer_token", false);

        assertThat(result).as("upsert succeeded").isTrue();

        // Placeholder row carries the (name, variant) pair and is_enabled=false;
        // every secret column stays NULL so `hasOAuth2Credentials()` and friends
        // still report false - the runtime OAuth2 gate keeps blocking just like
        // "no row" did, and the existing platform-key-fed-to-users flow is
        // untouched the moment the admin fills in secrets via the edit dialog.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT is_enabled, auth_type, client_id, client_secret, api_key, default_markup_credits, max_calls_per_run, tenant_id "
                        + "FROM auth.platform_credentials WHERE integration_name = ? AND variant = ?",
                "airtable", "bearer_token");
        assertThat(row.get("is_enabled")).isEqualTo(false);
        assertThat(row.get("auth_type")).isEqualTo("bearer");
        assertThat(row.get("client_id")).isNull();
        assertThat(row.get("client_secret")).isNull();
        assertThat(row.get("api_key")).isNull();
        assertThat(row.get("tenant_id")).isNull();
        assertThat(((java.math.BigDecimal) row.get("default_markup_credits")).signum()).isZero();
        assertThat(((Number) row.get("max_calls_per_run")).intValue()).isEqualTo(500);
    }

    @Test
    @DisplayName("setEnabledForVariant placeholder uses max_calls_per_run=0 for oauth2 variant to satisfy platform_credentials_oauth2_no_markup CHECK")
    void setEnabledForVariantOauth2PlaceholderClearsCallCap() {
        boolean result = repository.setEnabledForVariant("airtable", "oauth2", false);

        assertThat(result).isTrue();
        Integer maxCalls = jdbc.queryForObject(
                "SELECT max_calls_per_run FROM auth.platform_credentials WHERE integration_name = ? AND variant = ?",
                Integer.class, "airtable", "oauth2");
        assertThat(maxCalls).as("oauth2 placeholder must carry 0 calls/run to pass the prod CHECK").isZero();
    }

    @Test
    @DisplayName("setEnabledForVariant UPDATE branch preserves client_id/client_secret byte-for-byte so admin-saved platform keys survive a toggle")
    void setEnabledForVariantPreservesSecretsOnUpdate() {
        // Seed a real OAuth2 row with secrets (the "platform key fed to users" case).
        repository.save(withVariant(platformWide("gmail"), "oauth2"));
        Map<String, Object> before = jdbc.queryForMap(
                "SELECT client_id, client_secret FROM auth.platform_credentials "
                        + "WHERE integration_name = ? AND variant = ?",
                "gmail", "oauth2");

        boolean flipped = repository.setEnabledForVariant("gmail", "oauth2", false);

        assertThat(flipped).isTrue();
        Map<String, Object> after = jdbc.queryForMap(
                "SELECT is_enabled, client_id, client_secret FROM auth.platform_credentials "
                        + "WHERE integration_name = ? AND variant = ?",
                "gmail", "oauth2");
        assertThat(after.get("is_enabled")).isEqualTo(false);
        // Pin the exact ciphertext so a future refactor that re-encrypts or
        // rewrites the secret on toggle is caught - "not null" alone would
        // silently pass through a regression that rotates the stored value.
        assertThat(after.get("client_id"))
                .as("plaintext client_id unchanged by toggle")
                .isEqualTo(before.get("client_id"));
        assertThat(after.get("client_secret"))
                .as("encrypted client_secret byte-for-byte unchanged by toggle")
                .isEqualTo(before.get("client_secret"));
    }

    @Test
    @DisplayName("setEnabledForVariant rejects unknown variant names so typos can't silently create CUSTOM placeholders")
    void setEnabledForVariantRejectsUnknownVariant() {
        boolean result = repository.setEnabledForVariant("airtable", "bogus_gibberish", false);

        assertThat(result).as("unknown variant returns false → controller 404").isFalse();
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth.platform_credentials WHERE integration_name = ?",
                Integer.class, "airtable");
        assertThat(count).as("no garbage placeholder row inserted").isZero();
    }


    @Test
    @DisplayName("findByIntegrationName prefers secret-bearing row even when it is disabled and the placeholder is enabled - locks secrets-first ahead of is_enabled")
    void findByIntegrationNameSecretsFirstBeatsEnabledPlaceholder() {
        // The inverse of the happy path: admin has a saved oauth2 row but
        // flipped it OFF, and ALSO enabled a bearer_token placeholder. Without
        // `secrets present DESC` as the first tiebreaker, `is_enabled DESC`
        // alone would pick the placeholder and `getOAuth2Credentials` would
        // return empty even though a real (just-disabled) row exists.
        PlatformCredential oauth2Disabled = withEnabled(withVariant(platformWide("airtable"), "oauth2"), false);
        repository.save(oauth2Disabled);
        repository.setEnabledForVariant("airtable", "bearer_token", true);

        Optional<PlatformCredential> found = repository.findByIntegrationName("airtable");

        assertThat(found).isPresent();
        assertThat(found.get().variant())
                .as("row with secrets must win even when it is the disabled one - "
                        + "custom_fields defaulting to '{}' must not leak into SECRETS_PRESENT")
                .isEqualTo("oauth2");
        assertThat(found.get().clientSecret()).isNotNull();
    }

    @Test
    @DisplayName("findByIntegrationName prefers a real secret-bearing row over a disabled placeholder so getOAuth2Credentials survives the per-variant upsert")
    void findByIntegrationNamePrefersSecretBearingRowOverPlaceholder() {
        // Non-regression: before the ORDER BY fix, the admin disabling
        // bearer_token for an integration that ALSO had a legit enabled
        // oauth2 row could silently break OAuth2 at runtime, because the
        // variant-less lookup would pick the placeholder (is_enabled=false,
        // no secrets) and `getOAuth2Credentials` would fail the
        // `hasOAuth2Credentials()` gate.
        repository.save(withVariant(platformWide("airtable"), "oauth2"));
        repository.setEnabledForVariant("airtable", "bearer_token", false);

        Optional<PlatformCredential> found = repository.findByIntegrationName("airtable");

        assertThat(found).isPresent();
        assertThat(found.get().variant())
                .as("must pick the oauth2 row (secrets present, enabled) over the bearer_token placeholder")
                .isEqualTo("oauth2");
        assertThat(found.get().clientSecret())
                .as("the selected row must carry the admin-supplied secret so OAuth2 keeps working")
                .isNotNull();
        assertThat(found.get().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("findOAuth2ByIntegrationName ignores configured non-OAuth sibling variants")
    void findOAuth2ByIntegrationNameIgnoresNonOauthSiblingVariants() {
        repository.save(withAuthType(withVariant(platformWide("airtable"), "api_key"), AuthType.API_KEY));
        repository.save(withVariant(platformWide("airtable"), "oauth2"));

        Optional<PlatformCredential> found = repository.findOAuth2ByIntegrationName("airtable");

        assertThat(found).isPresent();
        assertThat(found.get().authType()).isEqualTo(AuthType.OAUTH2);
        assertThat(found.get().variant()).isEqualTo("oauth2");
        assertThat(found.get().hasOAuth2Credentials()).isTrue();
    }

    // ============================================================================
    //  findOwnedByTenant / countByTenantId - Part 2C/2D regression guards.
    //  These prove the SQL semantics that mocked unit tests can only assume:
    //  `WHERE tenant_id = ?` excludes platform-wide rows because in PostgreSQL
    //  3-valued logic, `NULL = 'tenant-A'` is UNKNOWN, never TRUE. A future
    //  refactor to `tenant_id IS NOT DISTINCT FROM ?` (which DOES match NULLs)
    //  would silently leak the global LiveContext OAuth apps to a regular user
    //  - these tests are that regression's tripwire.
    // ============================================================================

    @Test
    @DisplayName("findOwnedByTenant excludes platform-wide rows - tenant_id IS NULL row from another setup must NOT leak into a tenant's My OAuth Apps list")
    void findOwnedByTenantExcludesPlatformWide() {
        // Mixed fixture: one platform-wide LiveContext OAuth app + two tenant-A
        // BYOK rows + one tenant-B BYOK row. The query for tenant-A must return
        // exactly the two tenant-A rows.
        repository.save(platformWide("gmail"));
        repository.save(tenantScoped("notion", "tenant-A"));
        repository.save(tenantScoped("airtable", "tenant-A"));
        repository.save(tenantScoped("slack", "tenant-B"));

        List<PlatformCredential> tenantAOwn = repository.findOwnedByTenant("tenant-A");

        assertThat(tenantAOwn).extracting(PlatformCredential::integrationName)
                .as("only tenant-A's own rows; gmail (platform-wide) and slack (tenant-B) must NOT appear")
                .containsExactlyInAnyOrder("notion", "airtable");
        assertThat(tenantAOwn).allSatisfy(row ->
                assertThat(row.tenantId()).as("every returned row carries tenant-A").isEqualTo("tenant-A"));
    }

    @Test
    @DisplayName("findOwnedByTenant returns empty list for a tenant with no BYOK rows - must not silently fall back to platform-wide")
    void findOwnedByTenantEmptyForTenantWithoutByok() {
        // Only platform-wide rows exist. A tenant with zero BYOK gets [].
        repository.save(platformWide("gmail"));
        repository.save(platformWide("slack"));

        List<PlatformCredential> result = repository.findOwnedByTenant("tenant-with-no-byok");

        assertThat(result).as("never silently leak platform-wide as a 'fallback'").isEmpty();
    }

    @Test
    @DisplayName("countByTenantId counts only tenant-owned rows - platform-wide rows do not inflate the per-tenant cap")
    void countByTenantIdExcludesPlatformWide() {
        repository.save(platformWide("gmail"));
        repository.save(platformWide("slack"));
        repository.save(tenantScoped("notion", "tenant-A"));
        repository.save(tenantScoped("airtable", "tenant-A"));
        repository.save(tenantScoped("dropbox", "tenant-B"));

        assertThat(repository.countByTenantId("tenant-A"))
                .as("two BYOK rows for tenant-A; platform-wide rows must not count")
                .isEqualTo(2);
        assertThat(repository.countByTenantId("tenant-B"))
                .as("one BYOK row for tenant-B")
                .isEqualTo(1);
        assertThat(repository.countByTenantId("tenant-with-nothing"))
                .as("zero for an unrelated tenant - platform-wide rows must not bleed in")
                .isZero();
    }

    @Test
    @DisplayName("setEnabledForVariant tenant-scoped overload still returns false on miss - tenant placeholders are not created by the admin-platform-wide path")
    void setEnabledForVariantTenantScopedReturnsFalseOnMiss() {
        // The tenant overload is used by per-user credential overrides, not by
        // the admin toggle. Inserting a placeholder there would require guessing
        // the right tenant ownership, so we keep it UPDATE-only.
        boolean result = repository.setEnabledForVariant("airtable", "bearer_token", false, "tenant-x");

        assertThat(result).isFalse();
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth.platform_credentials WHERE integration_name = ? AND tenant_id = ?",
                Integer.class, "airtable", "tenant-x");
        assertThat(count).as("no tenant placeholder was created").isZero();
    }

    // ============================================================================
    //  V362 - org (workspace) scoping. BYOK rows are scoped by (tenant, org),
    //  mirroring auth.credentials strict isolation. These pin the SQL semantics.
    // ============================================================================

    @Test
    @DisplayName("V362: findOwnedByTenant(tenant, org) lists ONLY the rows for that exact workspace - strict isolation")
    void findOwnedByTenant_strictWorkspaceIsolation() {
        repository.save(orgScoped("gmail", "tenant-A", "org-1"));
        repository.save(orgScoped("slack", "tenant-A", "org-2"));
        repository.save(tenantScoped("notion", "tenant-A")); // personal (org null)

        assertThat(repository.findOwnedByTenant("tenant-A", "org-1"))
                .extracting(PlatformCredential::integrationName)
                .containsExactly("gmail");
        assertThat(repository.findOwnedByTenant("tenant-A", "org-2"))
                .extracting(PlatformCredential::integrationName)
                .containsExactly("slack");
        assertThat(repository.findOwnedByTenant("tenant-A", null))
                .as("personal scope (org null) lists only org-null rows")
                .extracting(PlatformCredential::integrationName)
                .containsExactly("notion");
    }

    @Test
    @DisplayName("V362: resolution prefers the workspace BYOK over the platform-wide row")
    void findByIntegrationNameOrgAware_workspaceWinsOverPlatform() {
        repository.save(platformWide("gmail"));
        repository.save(orgScoped("gmail", "tenant-A", "org-1"));

        Optional<PlatformCredential> found = repository.findByIntegrationName("gmail", "tenant-A", "org-1");

        assertThat(found).isPresent();
        assertThat(found.get().tenantId()).isEqualTo("tenant-A");
        assertThat(found.get().organizationId()).isEqualTo("org-1");
    }

    @Test
    @DisplayName("V362: a BYOK tagged to a DIFFERENT workspace is never resolved - falls back to platform-wide")
    void findByIntegrationNameOrgAware_otherWorkspaceExcluded() {
        repository.save(platformWide("gmail"));
        repository.save(orgScoped("gmail", "tenant-A", "org-1"));

        // Same tenant, but resolving from a different workspace (org-2): the org-1
        // BYOK must NOT leak; the platform-wide row is the only valid fallback.
        Optional<PlatformCredential> found = repository.findByIntegrationName("gmail", "tenant-A", "org-2");

        assertThat(found).isPresent();
        assertThat(found.get().tenantId()).as("the org-1 BYOK is excluded for org-2").isNull();
    }

    @Test
    @DisplayName("V362: findOwnedRow matches the exact (tenant, workspace) scope and nothing else")
    void findOwnedRow_exactScopeOnly() {
        repository.save(orgScoped("gmail", "tenant-A", "org-1"));

        assertThat(repository.findOwnedRow("gmail", "tenant-A", "org-1")).isPresent();
        assertThat(repository.findOwnedRow("gmail", "tenant-A", "org-2"))
                .as("different workspace -> no match (never falls back)").isEmpty();
        assertThat(repository.findOwnedRow("gmail", "tenant-A", null))
                .as("personal scope -> no match for an org-tagged row").isEmpty();
    }

    @Test
    @DisplayName("V362: per-(tenant, workspace) cap count is isolated by org")
    void countByTenantIdAndOrganizationId_perWorkspace() {
        repository.save(orgScoped("gmail", "tenant-A", "org-1"));
        repository.save(orgScoped("slack", "tenant-A", "org-1"));
        repository.save(orgScoped("notion", "tenant-A", "org-2"));
        repository.save(tenantScoped("figma", "tenant-A")); // personal

        assertThat(repository.countByTenantIdAndOrganizationId("tenant-A", "org-1")).isEqualTo(2);
        assertThat(repository.countByTenantIdAndOrganizationId("tenant-A", "org-2")).isEqualTo(1);
        assertThat(repository.countByTenantIdAndOrganizationId("tenant-A", null)).isEqualTo(1);
    }

    @Test
    @DisplayName("V362: scoped delete removes only the BYOK row for that workspace")
    void deleteByIntegrationName_scopedToWorkspace() {
        repository.save(orgScoped("gmail", "tenant-A", "org-1"));
        repository.save(orgScoped("gmail", "tenant-A", "org-2"));

        boolean deleted = repository.deleteByIntegrationName("gmail", "tenant-A", "org-1");

        assertThat(deleted).isTrue();
        assertThat(repository.findOwnedRow("gmail", "tenant-A", "org-1")).isEmpty();
        assertThat(repository.findOwnedRow("gmail", "tenant-A", "org-2"))
                .as("the other workspace's row is untouched").isPresent();
    }

    @Test
    @DisplayName("V362: the org-dimension unique index allows the same (integration, tenant, variant) in two workspaces - would have collided pre-V362")
    void uniqueIndex_allowsSameIntegrationAcrossWorkspaces() {
        repository.save(orgScoped("gmail", "tenant-A", "org-1"));

        // Pre-V362 the unique index was (integration, tenant, variant) and this
        // second insert threw a duplicate-key DataAccessException. Post-V362 the
        // org dimension makes it a distinct row.
        repository.save(orgScoped("gmail", "tenant-A", "org-2"));

        assertThat(repository.findOwnedByTenant("tenant-A", "org-1")).hasSize(1);
        assertThat(repository.findOwnedByTenant("tenant-A", "org-2")).hasSize(1);
    }

    // ============================================================================
    //  Test fixtures
    // ============================================================================

    private static PlatformCredential platformWide(String name) {
        return new PlatformCredential(
                null, name, name + " Display", AuthType.OAUTH2,
                "client-id", "client-secret", null, null, null,
                "https://auth.example.com", "https://token.example.com",
                "scope1", name, "default", "desc", true,
                Map.of(), java.math.BigDecimal.ZERO, 0, null, null, "test", null);
    }

    private static PlatformCredential tenantScoped(String name, String tenantId) {
        return platformWide(name).withTenantId(tenantId);
    }

    private static PlatformCredential orgScoped(String name, String tenantId, String organizationId) {
        return platformWide(name).withTenantId(tenantId).withOrganizationId(organizationId);
    }

    private static PlatformCredential withCategory(PlatformCredential c, String category) {
        return new PlatformCredential(
                c.id(), c.integrationName(), c.displayName(), c.authType(),
                c.clientId(), c.clientSecret(), c.apiKey(), c.username(), c.password(),
                c.authUrl(), c.tokenUrl(), c.defaultScopes(),
                c.iconSlug(), category, c.description(), c.showUnverifiedAppWarning(), c.isEnabled(),
                c.customFields(), c.defaultMarkupCredits(), c.maxCallsPerRun(),
                c.createdAt(), c.updatedAt(), c.createdBy(), c.tenantId(), c.variant());
    }

    private static PlatformCredential withAuthType(PlatformCredential c, AuthType authType) {
        return new PlatformCredential(
                c.id(), c.integrationName(), c.displayName(), authType,
                c.clientId(), c.clientSecret(), c.apiKey(), c.username(), c.password(),
                c.authUrl(), c.tokenUrl(), c.defaultScopes(),
                c.iconSlug(), c.category(), c.description(), c.showUnverifiedAppWarning(), c.isEnabled(),
                c.customFields(), c.defaultMarkupCredits(), c.maxCallsPerRun(),
                c.createdAt(), c.updatedAt(), c.createdBy(), c.tenantId(), c.variant());
    }

    private static PlatformCredential withVariant(PlatformCredential c, String variant) {
        return new PlatformCredential(
                c.id(), c.integrationName(), c.displayName(), c.authType(),
                c.clientId(), c.clientSecret(), c.apiKey(), c.username(), c.password(),
                c.authUrl(), c.tokenUrl(), c.defaultScopes(),
                c.iconSlug(), c.category(), c.description(), c.showUnverifiedAppWarning(), c.isEnabled(),
                c.customFields(), c.defaultMarkupCredits(), c.maxCallsPerRun(),
                c.createdAt(), c.updatedAt(), c.createdBy(), c.tenantId(), variant);
    }

    private static PlatformCredential withEnabled(PlatformCredential c, boolean isEnabled) {
        return new PlatformCredential(
                c.id(), c.integrationName(), c.displayName(), c.authType(),
                c.clientId(), c.clientSecret(), c.apiKey(), c.username(), c.password(),
                c.authUrl(), c.tokenUrl(), c.defaultScopes(),
                c.iconSlug(), c.category(), c.description(), c.showUnverifiedAppWarning(), isEnabled,
                c.customFields(), c.defaultMarkupCredits(), c.maxCallsPerRun(),
                c.createdAt(), c.updatedAt(), c.createdBy(), c.tenantId(), c.variant());
    }
}
