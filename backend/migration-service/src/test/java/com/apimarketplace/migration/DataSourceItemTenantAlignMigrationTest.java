package com.apimarketplace.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Replay test for V333: items (and vectors) written by a workflow executor /
 * org teammate carried the CALLER's tenant_id instead of the parent
 * datasource owner's, making them invisible to every owner-tenant-scoped read
 * (prod example: datasource 38 "processed_emails" - owner tenant 1, rows
 * stamped tenant 5, empty UI grid). V333 realigns item/vector tenant_id with
 * data_sources.tenant_id.
 */
@DisplayName("data_source_items tenant realignment (V333)")
class DataSourceItemTenantAlignMigrationTest {

    @Test
    @DisplayName("V333 realigns drifted item and vector rows to the parent DS tenant, leaving aligned rows untouched")
    void v332RealignsDriftedRowsToParentTenant(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();

        writeFixture(tempDir, false);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "ds_tenant_align_replay");

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, "ds_tenant_align_replay", tempDir))
                    .doesNotThrowAnyException();

            // The executor-stamped row (tenant 5) now carries the owner's tenant (1)...
            assertThat(itemTenant(postgres, "ds_tenant_align_replay", 100)).isEqualTo("1");
            // ...the already-aligned row is untouched...
            assertThat(itemTenant(postgres, "ds_tenant_align_replay", 101)).isEqualTo("1");
            // ...and no item of DS 38 is left outside the owner scope (the empty-grid condition).
            assertThat(driftedCount(postgres, "ds_tenant_align_replay")).isZero();
            // Vectors follow the same invariant.
            assertThat(vectorTenant(postgres, "ds_tenant_align_replay", 500)).isEqualTo("1");
        }
    }

    @Test
    @DisplayName("V333 is idempotent: re-applying it on aligned data touches nothing")
    void v332IsIdempotent(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();

        writeFixture(tempDir, true);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "ds_tenant_align_idem");

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, "ds_tenant_align_idem", tempDir))
                    .doesNotThrowAnyException();

            assertThat(driftedCount(postgres, "ds_tenant_align_idem")).isZero();
            assertThat(itemTenant(postgres, "ds_tenant_align_idem", 100)).isEqualTo("1");
        }
    }

    /**
     * Minimal datasource-schema fixture mirroring the prod shape that matters
     * to V333 (parent/child link + tenant columns): one DS owned by tenant 1,
     * one drifted item (tenant 5 - the Gmail-workflow shape), one aligned item,
     * one drifted vector. The REAL V333 is replayed on top; when
     * {@code reapply} is set it is applied a second time as V3.
     */
    private static void writeFixture(Path directory, boolean reapply) throws Exception {
        Files.writeString(directory.resolve("V1__seed_datasource_schema.sql"), """
                CREATE SCHEMA datasource;

                CREATE TABLE datasource.data_sources (
                    id        BIGINT PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    name      VARCHAR(255) NOT NULL
                );

                CREATE TABLE datasource.data_source_items (
                    id             BIGINT PRIMARY KEY,
                    data_source_id BIGINT NOT NULL REFERENCES datasource.data_sources(id),
                    tenant_id      VARCHAR(255) NOT NULL,
                    data           JSONB NOT NULL DEFAULT '{}'::jsonb
                );

                CREATE TABLE datasource.data_source_vectors (
                    id             BIGINT PRIMARY KEY,
                    data_source_id BIGINT NOT NULL REFERENCES datasource.data_sources(id),
                    tenant_id      VARCHAR(255) NOT NULL
                );

                INSERT INTO datasource.data_sources (id, tenant_id, name)
                VALUES (38, '1', 'processed_emails');

                -- Drifted: written by the workflow executor (tenant 5).
                INSERT INTO datasource.data_source_items (id, data_source_id, tenant_id, data)
                VALUES (100, 38, '5', '{"label": "Finance"}'::jsonb);

                -- Aligned: written through the UI path (owner tenant).
                INSERT INTO datasource.data_source_items (id, data_source_id, tenant_id, data)
                VALUES (101, 38, '1', '{"label": "Urgent"}'::jsonb);

                INSERT INTO datasource.data_source_vectors (id, data_source_id, tenant_id)
                VALUES (500, 38, '5');
                """);

        String v332 = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V333__align_data_source_item_tenant_with_parent.sql"));
        Files.writeString(directory.resolve("V2__align_item_tenant.sql"), v332);
        if (reapply) {
            Files.writeString(directory.resolve("V3__reapply_align_item_tenant.sql"), v332);
        }
    }

    private static String itemTenant(PostgreSQLContainer<?> postgres, String databaseName, long itemId)
            throws Exception {
        return scalar(postgres, databaseName,
                "SELECT tenant_id FROM datasource.data_source_items WHERE id = " + itemId);
    }

    private static String vectorTenant(PostgreSQLContainer<?> postgres, String databaseName, long vectorId)
            throws Exception {
        return scalar(postgres, databaseName,
                "SELECT tenant_id FROM datasource.data_source_vectors WHERE id = " + vectorId);
    }

    private static int driftedCount(PostgreSQLContainer<?> postgres, String databaseName) throws Exception {
        return Integer.parseInt(scalar(postgres, databaseName, """
                SELECT COUNT(*)
                  FROM datasource.data_source_items i
                  JOIN datasource.data_sources ds ON ds.id = i.data_source_id
                 WHERE i.tenant_id IS DISTINCT FROM ds.tenant_id
                """));
    }

    private static String scalar(PostgreSQLContainer<?> postgres, String databaseName, String sql)
            throws Exception {
        try (var connection = DriverManager.getConnection(
                FlywayTestSupport.jdbcUrl(postgres, databaseName), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }
}
