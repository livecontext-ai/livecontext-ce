package com.apimarketplace.auth.migration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres regression for {@code V548__user_saml_idp_alias.sql}: the column is added, and
 * the backfill gives a SAML account the alias of its EARLIEST audited join, leaves every other
 * account (a password account that joined through SAML, a SAML account with no join event)
 * NULL, never overwrites an existing value, and the migration is idempotent.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("V548 users.saml_idp_alias + backfill - real Postgres")
class UserSamlIdpAliasV548MigrationPostgresTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String FIRST = "org-aaaaaaaabbbbccccddddeeeeeeeeeeee-saml";
    private static final String LATER = "org-11111111222233334444555555555555-saml";

    static JdbcTemplate jdbc;

    @BeforeAll
    static void setUpClass() {
        String v548 = loadMigration("V548__user_saml_idp_alias.sql");
        String beforeEach = loadMigration("beforeEachMigrate.sql");
        Assumptions.assumeTrue(v548 != null && beforeEach != null, "migration files not found from module cwd - skipped");

        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.execute("CREATE SCHEMA orchestrator");
        jdbc.execute("CREATE SCHEMA auth");
        jdbc.execute("CREATE TABLE auth.users (id BIGSERIAL PRIMARY KEY, email VARCHAR(100), auth_provider VARCHAR(255))");
        jdbc.execute("""
                CREATE TABLE auth.organization_audit_event (
                    id BIGSERIAL PRIMARY KEY,
                    org_id UUID NOT NULL,
                    actor_user_id BIGINT,
                    event_type VARCHAR(64) NOT NULL,
                    event_data JSONB NOT NULL DEFAULT '{}'::jsonb,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )""");
        // 1: SAML account, joined through FIRST, then (later) through LATER.
        jdbc.update("INSERT INTO auth.users (id, email, auth_provider) VALUES (1, 'a@acme.com', 'SAML')");
        join(1L, FIRST, "2026-09-01T10:00:00Z");
        join(1L, LATER, "2026-09-02T10:00:00Z");
        // 2: password account whose login went through a workspace IdP: must stay NULL.
        jdbc.update("INSERT INTO auth.users (id, email, auth_provider) VALUES (2, 'b@acme.com', 'KEYCLOAK')");
        join(2L, FIRST, "2026-09-01T10:00:00Z");
        // 3: SAML account with no join event: stays NULL (refused at its next SAML login).
        jdbc.update("INSERT INTO auth.users (id, email, auth_provider) VALUES (3, 'c@acme.com', 'SAML')");

        jdbc.execute(beforeEach + "\n" + v548);
        // Idempotent: a second run is a no-op, not an error.
        jdbc.execute(beforeEach + "\n" + v548);
    }

    @Test
    @DisplayName("a SAML account gets the alias of its EARLIEST audited join")
    void samlAccountGetsEarliestJoinAlias() {
        assertThat(alias(1L)).isEqualTo(FIRST);
    }

    @Test
    @DisplayName("a non-SAML account is never backfilled, even if a SAML join was audited for it")
    void nonSamlAccountStaysNull() {
        assertThat(alias(2L)).isNull();
    }

    @Test
    @DisplayName("a SAML account with no join event stays NULL")
    void samlAccountWithoutJoinStaysNull() {
        assertThat(alias(3L)).isNull();
    }

    @Test
    @DisplayName("the backfill never overwrites a value already set")
    void existingValueIsKept() {
        jdbc.update("INSERT INTO auth.users (id, email, auth_provider, saml_idp_alias) VALUES (4, 'd@acme.com', 'SAML', ?)", LATER);
        join(4L, FIRST, "2026-09-01T09:00:00Z");

        jdbc.execute(loadMigration("beforeEachMigrate.sql") + "\n" + loadMigration("V548__user_saml_idp_alias.sql"));

        assertThat(alias(4L)).isEqualTo(LATER);
    }

    private static void join(Long userId, String alias, String at) {
        jdbc.update("INSERT INTO auth.organization_audit_event (org_id, actor_user_id, event_type, event_data, created_at) "
                        + "VALUES (?, ?, 'ORG_SAML_SSO_MEMBER_JOINED', ?::jsonb, ?::timestamptz)",
                UUID.randomUUID(), userId, "{\"idpAlias\":\"" + alias + "\",\"role\":\"MEMBER\"}", at);
    }

    private static String alias(Long userId) {
        return jdbc.queryForObject("SELECT saml_idp_alias FROM auth.users WHERE id = ?", String.class, userId);
    }

    private static String loadMigration(String name) {
        for (String prefix : new String[]{"", "../", "../../"}) {
            for (Path candidate : new Path[]{
                    Path.of(prefix + "backend/migration-service/src/main/resources/db/migration/" + name),
                    Path.of(prefix + "migration-service/src/main/resources/db/migration/" + name)}) {
                if (Files.exists(candidate)) {
                    try {
                        return Files.readString(candidate);
                    } catch (Exception e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }
}
