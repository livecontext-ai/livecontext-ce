package com.apimarketplace.agent.catalog.bundle;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres regression for the V381 payload-persistence migration,
 * named for the prod deploy failure it pins (2026-07-03): the first cut of
 * V381 referenced {@code catalog_bundles} UNQUALIFIED, and because
 * migration-service's {@code beforeEachMigrate.sql} resets
 * {@code search_path TO orchestrator, public} before every file, the ALTER
 * hit the orchestrator schema and failed with "relation does not exist" -
 * the pre-upgrade helm hook failed and the release rolled back.
 *
 * <p>The V374 sibling IT ({@link com.apimarketplace.agent.skill.bundle.SkillBundleMigrationPostgresIT})
 * runs the migration file alone, which misses this failure mode entirely (a
 * fresh connection's default search_path is permissive). This test therefore
 * reproduces the REAL runner environment: both schemas exist, and
 * {@code beforeEachMigrate}'s search_path reset executes on the SAME
 * connection right before the migration file - exactly what Flyway does.
 * The unqualified pre-fix SQL deterministically fails here; the
 * schema-qualified fix passes.
 */
@Testcontainers
@DisplayName("V381 catalog-bundle payload migration - runs under the real migration-service search_path")
class CatalogBundleV381MigrationPostgresIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void setUpClass() throws Exception {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available - CatalogBundleV381MigrationPostgresIT skipped");

        String v381 = loadMigration("V381__catalog_bundle_payload_persistence.sql");
        String beforeEach = loadMigration("beforeEachMigrate.sql");
        Assumptions.assumeTrue(v381 != null && beforeEach != null,
                "migration files not found from module cwd - skipped");

        DataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);

        // The real runner's world: BOTH schemas exist (so an unqualified
        // reference resolves to the WRONG one instead of erroring early),
        // plus the minimal agent-side slices V381 ALTERs.
        jdbc.execute("CREATE SCHEMA orchestrator");
        jdbc.execute("CREATE SCHEMA agent");
        jdbc.execute("CREATE TABLE agent.catalog_bundles (id BIGSERIAL PRIMARY KEY)");
        jdbc.execute("CREATE TABLE agent.model_config_overrides (id BIGSERIAL PRIMARY KEY)");

        // Same-connection sequence Flyway executes: beforeEachMigrate (which
        // resets search_path to orchestrator, public) THEN the migration file.
        jdbc.execute(beforeEach + "\n" + v381);
    }

    private static String loadMigration(String fileName) {
        String[] candidates = {
                "../migration-service/src/main/resources/db/migration/" + fileName,
                "backend/migration-service/src/main/resources/db/migration/" + fileName,
        };
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.exists(p)) {
                try {
                    return Files.readString(p);
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("payload lands on agent.catalog_bundles, not the orchestrator schema")
    void payloadColumnOnAgentSchema() {
        assertThat(columnExists("agent", "catalog_bundles", "payload")).isTrue();
        assertThat(columnExists("orchestrator", "catalog_bundles", "payload"))
                .as("nothing may leak into the orchestrator schema")
                .isFalse();
    }

    @Test
    @DisplayName("bundle_enabled lands on agent.model_config_overrides as BOOLEAN")
    void bundleEnabledColumnOnAgentSchema() {
        String type = jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                + "WHERE table_schema='agent' AND table_name='model_config_overrides' "
                + "AND column_name='bundle_enabled'",
                String.class);
        assertThat(type).isEqualTo("boolean");
    }

    @Test
    @DisplayName("re-running V381 is a no-op (IF NOT EXISTS - repairThenMigrate replays after the failed prod attempt)")
    void rerunIsIdempotent() throws Exception {
        String v381 = loadMigration("V381__catalog_bundle_payload_persistence.sql");
        jdbc.execute(v381); // must not throw
        assertThat(columnExists("agent", "catalog_bundles", "payload")).isTrue();
    }

    private boolean columnExists(String schema, String table, String column) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=? AND table_name=? AND column_name=?",
                Integer.class, schema, table, column);
        return n != null && n > 0;
    }
}
