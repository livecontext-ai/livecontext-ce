package com.apimarketplace.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Replays the REAL V516 against Postgres. The partial unique index is the whole guarantee
 * of SSO discovery (a domain routes to ONE workspace), and H2 cannot run a partial index,
 * so this is the only place it is proven.
 */
@DisplayName("V516 organization_sso_domain: a domain is VERIFIED by at most one workspace")
class OrganizationSsoDomainV516MigrationTest {

    private static final String DB = "org_sso_domain_v516";
    private static final String MIGRATION = "V516__organization_sso_domain.sql";

    private static final String ORG_A = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String ORG_B = "bbbbbbbb-0000-0000-0000-000000000002";

    /** auth.organization trimmed to the key V516 references. */
    private static final String SCHEMA = """
            CREATE SCHEMA auth;
            CREATE TABLE auth.organization (id UUID PRIMARY KEY);
            INSERT INTO auth.organization (id) VALUES
            ('aaaaaaaa-0000-0000-0000-000000000001'),
            ('bbbbbbbb-0000-0000-0000-000000000002');
            """;

    @Test
    @DisplayName("two workspaces may both CLAIM a domain, but only one may hold it VERIFIED")
    void verifiedIsUniquePendingIsNot(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);
        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB);
            assertThatCode(() -> postgres.runFlyway(DB, tempDir)).doesNotThrowAnyException();

            // Typing a domain proves nothing, so a pending claim never blocks another workspace.
            execute(postgres, DB, insert(ORG_A, "acme.com", false));
            execute(postgres, DB, insert(ORG_B, "acme.com", false));
            execute(postgres, DB, "UPDATE auth.organization_sso_domain SET verified_at = NOW() "
                    + "WHERE organization_id = '" + ORG_A + "'");

            assertThatThrownBy(() -> execute(postgres, DB, "UPDATE auth.organization_sso_domain "
                    + "SET verified_at = NOW() WHERE organization_id = '" + ORG_B + "'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_org_sso_domain_verified");
            assertThat(query(postgres, DB, "SELECT COUNT(*) FROM auth.organization_sso_domain "
                    + "WHERE domain = 'acme.com' AND verified_at IS NOT NULL")).isEqualTo("1");
        }
    }

    @Test
    @DisplayName("a workspace cannot list the same domain twice, and domains are stored lower-case")
    void perWorkspaceUniqueAndLowercase(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);
        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_uq");
            postgres.runFlyway(DB + "_uq", tempDir);

            execute(postgres, DB + "_uq", insert(ORG_A, "acme.com", false));
            assertThatThrownBy(() -> execute(postgres, DB + "_uq", insert(ORG_A, "acme.com", false)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_org_sso_domain_org_domain");
            // Lower-case is what makes lookups by email domain exact; a mixed-case row would be
            // a verified domain that discovery never finds.
            assertThatThrownBy(() -> execute(postgres, DB + "_uq", insert(ORG_B, "Acme.com", false)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_org_sso_domain_lowercase");
        }
    }

    @Test
    @DisplayName("deleting the workspace row deletes its domains")
    void cascadesWithWorkspace(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);
        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_fk");
            postgres.runFlyway(DB + "_fk", tempDir);

            execute(postgres, DB + "_fk", insert(ORG_A, "acme.com", true));
            execute(postgres, DB + "_fk", "DELETE FROM auth.organization WHERE id = '" + ORG_A + "'");

            assertThat(query(postgres, DB + "_fk", "SELECT COUNT(*) FROM auth.organization_sso_domain"))
                    .isEqualTo("0");
        }
    }

    private static String insert(String orgId, String domain, boolean verified) {
        return "INSERT INTO auth.organization_sso_domain (organization_id, domain, verification_token, verified_at) "
                + "VALUES ('" + orgId + "', '" + domain + "', 'tok', " + (verified ? "NOW()" : "NULL") + ")";
    }

    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__seed_auth_organization.sql"), SCHEMA);
        Files.copy(Path.of("src/main/resources/db/migration/" + MIGRATION), directory.resolve(MIGRATION));
    }

    private static void execute(FlywayTestSupport.PostgresTarget postgres, String db, String sql)
            throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.jdbcUrl(db), postgres.username(), postgres.password());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String query(FlywayTestSupport.PostgresTarget postgres, String db, String sql)
            throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.jdbcUrl(db), postgres.username(), postgres.password());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
