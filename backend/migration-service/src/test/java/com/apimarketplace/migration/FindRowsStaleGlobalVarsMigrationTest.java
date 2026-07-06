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
 * V390 clears the stale {@code global_variables} on the {@code find_rows}
 * node_type_documentation row. The original V11 seed described find_rows as
 * split-like and advertised {@code {"item": ..., "index": ...}} parallel-context
 * runtime vars. V20/V77 corrected the description/outputs to "does NOT
 * split/spawn" but never touched {@code global_variables}, so the misleading
 * item/index vars survived and reached agents through the node help.
 *
 * <p>The fixture seeds that exact stale shape (the intentional bug input) plus a
 * {@code split} control row that legitimately keeps its runtime vars, proving
 * V390 targets find_rows only.
 */
@DisplayName("find_rows stale global_variables clear (V390)")
class FindRowsStaleGlobalVarsMigrationTest {

    @Test
    @DisplayName("V390 nulls find_rows.global_variables while leaving split's runtime vars untouched")
    void v390ClearsFindRowsGlobalVariablesOnly(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();
        writeFixture(tempDir);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "find_rows_gv");

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, "find_rows_gv", tempDir))
                    .doesNotThrowAnyException();

            // find_rows no longer advertises any per-row runtime variables.
            assertThat(globalVariables(postgres, "find_rows_gv", "find_rows")).isNull();
            // The split control row keeps its legitimate current_item/current_index vars.
            assertThat(globalVariables(postgres, "find_rows_gv", "split"))
                    .contains("current_item")
                    .contains("current_index");
        }
    }

    @Test
    @DisplayName("V390 is idempotent: re-applying it on already-cleared data is a no-op")
    void v390IsIdempotent(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();
        writeFixture(tempDir);
        // V3 = V390 applied a second time. The IS NOT NULL guard must touch nothing.
        Files.writeString(tempDir.resolve("V3__reapply_find_rows_gv_clear.sql"),
                Files.readString(Path.of("src/main/resources/db/migration/"
                        + "V390__clear_find_rows_stale_global_variables.sql")));

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "find_rows_gv_idem");

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, "find_rows_gv_idem", tempDir))
                    .doesNotThrowAnyException();

            assertThat(globalVariables(postgres, "find_rows_gv_idem", "find_rows")).isNull();
            assertThat(globalVariables(postgres, "find_rows_gv_idem", "split"))
                    .contains("current_item")
                    .contains("current_index");
        }
    }

    /**
     * Minimal fixture: the node_type_documentation table with a 'find_rows' row
     * carrying the stale item/index parallel-context global_variables (the bug
     * input V390 must clear) and a 'split' control row whose runtime vars must
     * survive.
     */
    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__seed_node_type_documentation.sql"), """
                CREATE SCHEMA orchestrator;

                CREATE TABLE orchestrator.node_type_documentation (
                    type             VARCHAR(128) PRIMARY KEY,
                    description      TEXT NOT NULL DEFAULT '',
                    parameters       JSONB,
                    outputs          JSONB,
                    global_variables JSONB,
                    examples         JSONB,
                    keywords         JSONB,
                    edge_ports       JSONB,
                    concepts         JSONB,
                    comparison       JSONB,
                    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );

                INSERT INTO orchestrator.node_type_documentation (type, description, global_variables)
                VALUES (
                    'find_rows',
                    'Query a data table and return matching rows as an items[] array. Does NOT split/spawn.',
                    '{"item": "Current row in parallel context (access fields: {{item.column_name}})",
                      "index": "Current row index (0-based) in parallel context"}'::jsonb
                );

                INSERT INTO orchestrator.node_type_documentation (type, description, global_variables)
                VALUES (
                    'split',
                    'Fan out an items[] array into N parallel per-item contexts.',
                    '{"current_item": "Current item in the parallel context",
                      "current_index": "Current item index (0-based) in the parallel context"}'::jsonb
                );
                """);

        String v390 = Files.readString(Path.of("src/main/resources/db/migration/"
                + "V390__clear_find_rows_stale_global_variables.sql"));
        Files.writeString(directory.resolve("V2__clear_find_rows_gv.sql"), v390);
    }

    private static String globalVariables(PostgreSQLContainer<?> postgres, String db, String type) throws Exception {
        try (var connection = DriverManager.getConnection(
                FlywayTestSupport.jdbcUrl(postgres, db), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT global_variables::text FROM orchestrator.node_type_documentation WHERE type = '"
                             + type + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
