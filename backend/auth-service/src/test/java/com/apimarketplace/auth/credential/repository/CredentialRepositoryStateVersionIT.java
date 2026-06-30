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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Postgres integration test for {@link CredentialRepository#computeStateVersion(String, String)}
 * - the opaque version that catalog-service embeds in its agent response-cache key.
 *
 * <p>Regression companion of the 2026-06-11 prod bug ("set as default" ignored by the
 * chat agent for up to 5 minutes): the cache key carried no credential dimension, so a
 * cached tool response survived a credential switch. These tests prove against a live
 * engine that every resolution-affecting mutation moves the version:
 *
 * <ul>
 *   <li><b>set-as-default</b> ({@code setAsDefaultInScope}) - the exact prod trigger,</li>
 *   <li>connect (INSERT) and delete (count moves),</li>
 *   <li>scope union is (own rows anywhere) ∪ (active workspace rows) - a workspace-shared
 *       credential owned by ANOTHER member is covered, an unrelated tenant's is not,</li>
 *   <li>null org degrades to tenant-only scope,</li>
 *   <li>an unrelated tenant/org mutation does NOT move the version (no false busting).</li>
 * </ul>
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("CredentialRepository.computeStateVersion - Postgres integration")
class CredentialRepositoryStateVersionIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("auth_test")
            .withUsername("test")
            .withPassword("test");

    private static final String TENANT = "5";
    private static final String OTHER_MEMBER = "1";
    private static final String UNRELATED_TENANT = "9";
    private static final String ORG = "org-workspace";
    private static final String OTHER_ORG = "org-other";

    private JdbcTemplate jdbc;
    private CredentialRepository repository;

    @BeforeAll
    void setUpSchema() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName(POSTGRES.getDriverClassName());
        this.jdbc = new JdbcTemplate(ds);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(ds);

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS auth");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS auth.credentials (
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

        this.repository = new CredentialRepository(
                jdbc, namedJdbc, new ObjectMapper(), mock(CredentialEncryptionService.class));
    }

    private long ownInOrg;
    private long sharedByOtherMember;
    private long ownInOtherOrg;
    private long unrelated;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM auth.credentials");
        ownInOrg = insert(TENANT, ORG, "Gmail Credential", true);
        sharedByOtherMember = insert(OTHER_MEMBER, ORG, "Gmail Credential", false);
        ownInOtherOrg = insert(TENANT, OTHER_ORG, "Gmail Credential", true);
        unrelated = insert(UNRELATED_TENANT, OTHER_ORG, "Gmail Credential", true);
        // Backdate updated_at so a same-millisecond mutation can never hide
        // behind MAX(updated_at) staying put.
        jdbc.update("UPDATE auth.credentials SET updated_at = now() - interval '1 hour', " +
                "created_at = now() - interval '1 hour'");
    }

    private long insert(String tenant, String org, String name, boolean isDefault) {
        return jdbc.queryForObject("""
                INSERT INTO auth.credentials (tenant_id, organization_id, name, integration, type, is_default)
                VALUES (?, ?, ?, 'gmail', 'OAuth2', ?) RETURNING id
                """, Long.class, tenant, org, name, isDefault);
    }

    @Test
    @DisplayName("REGRESSION 2026-06-11: set-as-default moves the version (the prod mutation the cache never saw)")
    void setAsDefaultMovesVersion() {
        String before = repository.computeStateVersion(TENANT, ORG);

        repository.setAsDefaultInScope(OTHER_MEMBER, ORG, sharedByOtherMember);

        assertThat(repository.computeStateVersion(TENANT, ORG))
                .as("set-as-default stamps updated_at on the touched rows → version must move")
                .isNotEqualTo(before);
    }

    @Test
    @DisplayName("connect (INSERT) and delete move the version")
    void connectAndDeleteMoveVersion() {
        String v0 = repository.computeStateVersion(TENANT, ORG);

        long connected = insert(TENANT, ORG, "Gmail Credential 2", false);
        String v1 = repository.computeStateVersion(TENANT, ORG);
        assertThat(v1).isNotEqualTo(v0);

        jdbc.update("DELETE FROM auth.credentials WHERE id = ?", connected);
        String v2 = repository.computeStateVersion(TENANT, ORG);
        assertThat(v2).isNotEqualTo(v1);
    }

    @Test
    @DisplayName("scope union = own rows (any workspace) + active-workspace rows, excluding unrelated tenants")
    void scopeUnionCountsOwnAndWorkspaceRows() {
        // own-in-org + shared-by-other-member + own-in-other-org = 3; unrelated excluded.
        assertThat(repository.computeStateVersion(TENANT, ORG)).startsWith("3:");
        // Null org → tenant-only: own-in-org + own-in-other-org = 2.
        assertThat(repository.computeStateVersion(TENANT, null)).startsWith("2:");
    }

    @Test
    @DisplayName("a workspace-shared credential owned by another member moves the version too")
    void otherMembersWorkspaceRowIsCovered() {
        String before = repository.computeStateVersion(TENANT, ORG);

        jdbc.update("UPDATE auth.credentials SET updated_at = now() WHERE id = ?", sharedByOtherMember);

        assertThat(repository.computeStateVersion(TENANT, ORG)).isNotEqualTo(before);
    }

    @Test
    @DisplayName("an unrelated tenant's mutation does NOT move the version (no false cache busting)")
    void unrelatedMutationDoesNotMoveVersion() {
        String before = repository.computeStateVersion(TENANT, ORG);

        jdbc.update("UPDATE auth.credentials SET updated_at = now() WHERE id = ?", unrelated);
        insert(UNRELATED_TENANT, "org-elsewhere", "Slack Credential", true);

        assertThat(repository.computeStateVersion(TENANT, ORG)).isEqualTo(before);
    }

    @Test
    @DisplayName("empty scope yields the stable zero version")
    void emptyScopeYieldsZeroVersion() {
        assertThat(repository.computeStateVersion("nobody", null)).isEqualTo("0:0");
    }
}
