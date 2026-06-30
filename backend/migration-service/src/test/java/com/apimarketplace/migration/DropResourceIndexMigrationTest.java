package com.apimarketplace.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Codifies the prod-cleanup half of the {@code resource_index} feature removal:
 * {@code V304__drop_resource_index.sql} must drop the 10 per-tenant
 * {@code resource_index} columns and the {@code auth.tenant_resource_counters}
 * counter table, and must be idempotent (re-runnable when the objects are already
 * gone) thanks to its {@code IF EXISTS} guards.
 *
 * <p>Mirrors {@link StoredFilesSchemaMigrationTest}: a minimal fixture migration
 * recreates just the objects V304 touches, then the production V304 is copied in
 * and applied via Flyway against a throwaway Postgres.
 */
@DisplayName("V304 drop_resource_index migration invariants")
class DropResourceIndexMigrationTest {

    /** (schema, table) pairs that carry the dropped {@code resource_index} column. */
    private static final List<String[]> RESOURCE_INDEX_TABLES = List.of(
            new String[] {"orchestrator", "workflows"},
            new String[] {"agent", "agents"},
            new String[] {"agent", "skills"},
            new String[] {"trigger", "scheduled_executions"},
            new String[] {"trigger", "standalone_webhooks"},
            new String[] {"trigger", "standalone_chat_endpoints"},
            new String[] {"trigger", "standalone_form_endpoints"},
            new String[] {"interface", "interfaces"},
            new String[] {"datasource", "data_sources"},
            new String[] {"auth", "credentials"});

    @Test
    @DisplayName("Drops every resource_index column and the tenant_resource_counters table")
    void dropsColumnsAndCounterTable(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();
        writeFixtureWithResourceIndex(tempDir);
        copyProductionV304(tempDir);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "drop_resource_index");

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, "drop_resource_index", tempDir))
                    .doesNotThrowAnyException();

            for (String[] table : RESOURCE_INDEX_TABLES) {
                assertThat(columnExists(postgres, "drop_resource_index", table[0], table[1], "resource_index"))
                        .as("%s.%s.resource_index should be dropped", table[0], table[1])
                        .isFalse();
            }
            assertThat(tableExists(postgres, "drop_resource_index", "auth", "tenant_resource_counters"))
                    .as("auth.tenant_resource_counters should be dropped")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Is idempotent - re-runnable when the columns/table are already absent (IF EXISTS guards)")
    void idempotentWhenObjectsAlreadyAbsent(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();
        // Fixture WITHOUT any resource_index column and WITHOUT the counter table:
        // V304's IF EXISTS guards must make every statement a no-op rather than error.
        writeFixtureWithoutResourceIndex(tempDir);
        copyProductionV304(tempDir);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "drop_resource_index_idem");

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, "drop_resource_index_idem", tempDir))
                    .as("V304 must not throw when its targets are already gone")
                    .doesNotThrowAnyException();
        }
    }

    private static void writeFixtureWithResourceIndex(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__fixture_resource_index.sql"), """
                CREATE SCHEMA orchestrator;
                CREATE SCHEMA agent;
                CREATE SCHEMA "trigger";
                CREATE SCHEMA interface;
                CREATE SCHEMA datasource;
                CREATE SCHEMA auth;

                CREATE TABLE orchestrator.workflows            (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), resource_index INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE agent.agents                       (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), resource_index INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE agent.skills                       (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), resource_index INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE "trigger".scheduled_executions      (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), resource_index INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE "trigger".standalone_webhooks       (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), resource_index INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE "trigger".standalone_chat_endpoints (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), resource_index INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE "trigger".standalone_form_endpoints (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), resource_index INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE interface.interfaces               (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), resource_index INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE datasource.data_sources            (id BIGSERIAL PRIMARY KEY, resource_index INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE auth.credentials                   (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), resource_index INTEGER NOT NULL DEFAULT 0);

                CREATE TABLE auth.tenant_resource_counters (
                    tenant_id     VARCHAR(255) NOT NULL,
                    resource_type VARCHAR(50)  NOT NULL,
                    next_index    INTEGER      NOT NULL DEFAULT 1,
                    PRIMARY KEY (tenant_id, resource_type)
                );
                """);
    }

    private static void writeFixtureWithoutResourceIndex(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__fixture_no_resource_index.sql"), """
                CREATE SCHEMA orchestrator;
                CREATE SCHEMA agent;
                CREATE SCHEMA "trigger";
                CREATE SCHEMA interface;
                CREATE SCHEMA datasource;
                CREATE SCHEMA auth;

                CREATE TABLE orchestrator.workflows            (id UUID PRIMARY KEY DEFAULT gen_random_uuid());
                CREATE TABLE agent.agents                       (id UUID PRIMARY KEY DEFAULT gen_random_uuid());
                CREATE TABLE agent.skills                       (id UUID PRIMARY KEY DEFAULT gen_random_uuid());
                CREATE TABLE "trigger".scheduled_executions      (id UUID PRIMARY KEY DEFAULT gen_random_uuid());
                CREATE TABLE "trigger".standalone_webhooks       (id UUID PRIMARY KEY DEFAULT gen_random_uuid());
                CREATE TABLE "trigger".standalone_chat_endpoints (id UUID PRIMARY KEY DEFAULT gen_random_uuid());
                CREATE TABLE "trigger".standalone_form_endpoints (id UUID PRIMARY KEY DEFAULT gen_random_uuid());
                CREATE TABLE interface.interfaces               (id UUID PRIMARY KEY DEFAULT gen_random_uuid());
                CREATE TABLE datasource.data_sources            (id BIGSERIAL PRIMARY KEY);
                CREATE TABLE auth.credentials                   (id UUID PRIMARY KEY DEFAULT gen_random_uuid());
                -- auth.tenant_resource_counters intentionally NOT created.
                """);
    }

    private static void copyProductionV304(Path directory) throws Exception {
        String productionV304 = Files.readString(Path.of(
                "src/main/resources/db/migration/V304__drop_resource_index.sql"));
        Files.writeString(directory.resolve("V2__drop_resource_index.sql"), productionV304);
    }

    private static boolean columnExists(
            PostgreSQLContainer<?> postgres,
            String databaseName,
            String schemaName,
            String tableName,
            String columnName) throws Exception {
        try (var connection = DriverManager.getConnection(
                FlywayTestSupport.jdbcUrl(postgres, databaseName), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT EXISTS (
                         SELECT 1
                           FROM information_schema.columns
                          WHERE table_schema = ?
                            AND table_name = ?
                            AND column_name = ?
                     )
                     """)) {
            statement.setString(1, schemaName);
            statement.setString(2, tableName);
            statement.setString(3, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBoolean(1);
            }
        }
    }

    private static boolean tableExists(
            PostgreSQLContainer<?> postgres,
            String databaseName,
            String schemaName,
            String tableName) throws Exception {
        try (var connection = DriverManager.getConnection(
                FlywayTestSupport.jdbcUrl(postgres, databaseName), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT EXISTS (
                         SELECT 1
                           FROM information_schema.tables
                          WHERE table_schema = ?
                            AND table_name = ?
                     )
                     """)) {
            statement.setString(1, schemaName);
            statement.setString(2, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBoolean(1);
            }
        }
    }
}
