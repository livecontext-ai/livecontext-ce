package com.apimarketplace.agent.catalog.bundle;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres proof of the V533 backstop triggers: whatever writes the row, a migration's
 * {@code ON CONFLICT DO UPDATE SET enabled = TRUE} included, a retired model stays disabled and
 * its row cannot be deleted. The triggers live only in the database, so nothing else can show it.
 *
 * <p>Where it runs: on CI against the job's scratch Postgres ({@code AGENT_TEST_PG_URL}; the
 * runners have no Docker socket, so a Testcontainers-only class would SKIP there and prove
 * nothing), locally against Testcontainers. With {@code CI} set and no URL it FAILS rather than
 * skipping, so dropping the env block cannot silently disable it.
 *
 * <p>It works in a schema of its own ({@value #SCHEMA}), as every Postgres test sharing that CI
 * database must: the migration text is run with its {@code agent.} qualifier rewritten to that
 * schema. That also keeps the V381 lesson: the file runs after {@code beforeEachMigrate.sql} on
 * the same connection, and an UNQUALIFIED reference in it would hit the wrong schema and fail.
 */
@DisplayName("V533 model retirement - triggers on a real Postgres")
class ModelRetirementV533MigrationPostgresIT {

    private static final String SCHEMA = "v533_it";
    private static final String URL = System.getenv("AGENT_TEST_PG_URL");
    private static final String USER = System.getenv().getOrDefault("AGENT_TEST_PG_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("AGENT_TEST_PG_PASSWORD", "postgres");

    private static PostgreSQLContainer<?> container;
    private static SingleConnectionDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    @SuppressWarnings("resource")
    static void setUpClass() {
        String v533 = loadMigration("V533__model_retirement.sql");
        String beforeEach = loadMigration("beforeEachMigrate.sql");
        if (v533 == null || beforeEach == null) {
            throw new IllegalStateException("migration files not found from the module directory");
        }

        String url;
        String user;
        String password;
        if (URL != null && !URL.isBlank()) {
            String database = URL.substring(URL.lastIndexOf('/') + 1).split("\\?")[0];
            if (!database.toLowerCase(Locale.ROOT).contains("test")) {
                throw new IllegalStateException("AGENT_TEST_PG_URL must point at a scratch database whose "
                        + "name contains 'test' (this test drops a schema), got: " + database);
            }
            url = URL;
            user = USER;
            password = PASSWORD;
        } else if (System.getenv("CI") != null && !System.getenv("CI").isBlank()) {
            throw new IllegalStateException("AGENT_TEST_PG_URL is unset on CI. This class must execute "
                    + "there: it is the only proof that a retired model cannot be re-enabled or deleted by "
                    + "SQL. Restore the env block on the workflow step that runs it.");
        } else {
            Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                    "no scratch Postgres: set AGENT_TEST_PG_URL or start Docker to run this locally");
            container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();
            url = container.getJdbcUrl();
            user = container.getUsername();
            password = container.getPassword();
        }

        // One connection, so the search_path reset really precedes the migration on it.
        dataSource = new SingleConnectionDataSource(url, user, password, true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        jdbc.execute("CREATE SCHEMA " + SCHEMA);
        // The columns V533 and its triggers touch, as the table has them before V533.
        jdbc.execute("CREATE TABLE " + SCHEMA + ".model_config_overrides ("
                + " id BIGSERIAL PRIMARY KEY,"
                + " provider VARCHAR(50) NOT NULL,"
                + " model_id VARCHAR(150) NOT NULL,"
                + " enabled BOOLEAN,"
                + " bundle_enabled BOOLEAN,"
                + " UNIQUE (provider, model_id))");
        jdbc.execute(beforeEach);
        jdbc.execute(v533.replace("agent.", SCHEMA + "."));
        jdbc.execute("SET search_path TO public");
    }

    @AfterAll
    static void tearDown() {
        if (jdbc != null) {
            jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
        if (dataSource != null) {
            dataSource.destroy();
        }
        if (container != null) {
            container.stop();
        }
    }

    @BeforeEach
    void clean() {
        // Restore first, or the delete trigger would keep the retired rows of the last test.
        jdbc.update("UPDATE " + SCHEMA + ".model_config_overrides SET retired_at = NULL");
        jdbc.update("DELETE FROM " + SCHEMA + ".model_config_overrides");
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

    private static String table() {
        return SCHEMA + ".model_config_overrides";
    }

    private Boolean enabled(String modelId) {
        return jdbc.queryForObject("SELECT enabled FROM " + table() + " WHERE model_id = ?", Boolean.class, modelId);
    }

    private int count(String modelId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table() + " WHERE model_id = ?", Integer.class, modelId);
    }

    @Test
    @DisplayName("the migration's triggers are installed on the table it qualifies")
    void triggersInstalled() {
        Integer triggers = jdbc.queryForObject(
                "SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid"
                        + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = ? AND c.relname = 'model_config_overrides'"
                        + " AND t.tgname IN ('trg_keep_retired_model_disabled', 'trg_keep_retired_model_row')",
                Integer.class, SCHEMA);
        assertThat(triggers).isEqualTo(2);
    }

    @Test
    @DisplayName("a migration-style upsert that forces enabled = TRUE cannot re-enable a retired model")
    void upsertCannotReEnableRetired() {
        jdbc.update("INSERT INTO " + table() + " (provider, model_id, enabled, retired_at)"
                + " VALUES ('claude-code', 'claude-opus-4-6', false, now())");

        // The exact shape of the bridge seed migrations (V120, V128, V378, V399, V484).
        jdbc.update("INSERT INTO " + table() + " (provider, model_id, enabled, bundle_enabled)"
                + " VALUES ('claude-code', 'claude-opus-4-6', TRUE, TRUE)"
                + " ON CONFLICT (provider, model_id) DO UPDATE SET enabled = TRUE, bundle_enabled = TRUE");

        assertThat(enabled("claude-opus-4-6")).isFalse();
        assertThat(jdbc.queryForObject("SELECT bundle_enabled FROM " + table()
                + " WHERE model_id = 'claude-opus-4-6'", Boolean.class)).isFalse();
    }

    @Test
    @DisplayName("a retired row cannot be deleted, a live row can")
    void retiredRowSurvivesDelete() {
        jdbc.update("INSERT INTO " + table() + " (provider, model_id, enabled, retired_at)"
                + " VALUES ('openai', 'gpt-4o', false, now())");
        jdbc.update("INSERT INTO " + table() + " (provider, model_id, enabled) VALUES ('openai', 'gpt-6-sol', true)");

        int deleted = jdbc.update("DELETE FROM " + table() + " WHERE provider = 'openai'");

        assertThat(deleted).isEqualTo(1);
        assertThat(count("gpt-4o")).isEqualTo(1);
        assertThat(count("gpt-6-sol")).isZero();
    }

    @Test
    @DisplayName("restoring (retired_at back to NULL) lifts both guards")
    void restoreLiftsTheGuards() {
        jdbc.update("INSERT INTO " + table() + " (provider, model_id, enabled, retired_at)"
                + " VALUES ('openai', 'gpt-4o', false, now())");

        jdbc.update("UPDATE " + table() + " SET retired_at = NULL, enabled = TRUE WHERE model_id = 'gpt-4o'");
        assertThat(enabled("gpt-4o")).isTrue();

        jdbc.update("DELETE FROM " + table() + " WHERE model_id = 'gpt-4o'");
        assertThat(count("gpt-4o")).isZero();
    }

    @Test
    @DisplayName("a row inserted already retired is stored disabled, whatever the insert says")
    void insertRetiredIsDisabled() {
        jdbc.update("INSERT INTO " + table() + " (provider, model_id, enabled, bundle_enabled, retired_at)"
                + " VALUES ('xai', 'grok-3-beta', true, true, now())");

        assertThat(enabled("grok-3-beta")).isFalse();
    }
}
