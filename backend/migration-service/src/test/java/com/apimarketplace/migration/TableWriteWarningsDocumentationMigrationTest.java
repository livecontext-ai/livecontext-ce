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

/**
 * Replays the REAL V481 against the two node rows shaped as earlier migrations leave them, and
 * asserts it EXTENDS them rather than replacing what V253 wrote - a `||` on a null column would
 * blank the documentation instead, and a wholesale rewrite would silently drop the output fields an
 * agent already relies on. Re-applying it changes nothing.
 */
@DisplayName("V481 table write nodes report warnings")
class TableWriteWarningsDocumentationMigrationTest {

    private static final String DB = "table_write_warnings_v481";

    private static final String SCHEMA_AND_ROWS = """
            CREATE SCHEMA orchestrator;

            CREATE TABLE orchestrator.node_type_documentation (
                type             VARCHAR(128) PRIMARY KEY,
                label            VARCHAR(255),
                category         VARCHAR(64),
                variable_prefix  VARCHAR(64),
                description      TEXT NOT NULL DEFAULT '',
                parameters       JSONB NOT NULL DEFAULT '{}'::jsonb,
                outputs          JSONB NOT NULL DEFAULT '{}'::jsonb,
                global_variables JSONB,
                edge_ports       JSONB,
                concepts         JSONB,
                examples         JSONB NOT NULL DEFAULT '[]'::jsonb,
                keywords         JSONB,
                enabled          BOOLEAN NOT NULL DEFAULT true,
                created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
            );

            -- insert_row as V253 leaves it, with a NULL concepts column: that null is what makes the
            -- COALESCE in V481 load-bearing rather than decorative.
            INSERT INTO orchestrator.node_type_documentation (type, outputs, concepts) VALUES (
                'insert_row',
                '{"row_id": {"type": "string", "description": "ID of the inserted row"},
                  "inserted_count": {"type": "number", "description": "Number of rows inserted"}}'::jsonb,
                NULL
            );

            INSERT INTO orchestrator.node_type_documentation (type, outputs, concepts) VALUES (
                'update_row',
                '{"updated_count": {"type": "number", "description": "Number of rows updated"}}'::jsonb,
                '["Update rows matching a condition"]'::jsonb
            );

            -- A row the migration must not touch.
            INSERT INTO orchestrator.node_type_documentation (type, outputs, concepts) VALUES (
                'delete_row',
                '{"deleted_count": {"type": "number", "description": "Number of rows deleted"}}'::jsonb,
                NULL
            );
            """;

    @Test
    @DisplayName("V481 documents warnings on both write nodes, keeps what was there, and re-applies cleanly")
    void v481DocumentsWarningsAndStaysIdempotent(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB);

            assertThatCode(() -> postgres.runFlyway(DB, tempDir)).doesNotThrowAnyException();

            for (String type : new String[]{"insert_row", "update_row"}) {
                String warnings = outputField(postgres, type, "warnings", "description");
                assertThat(warnings)
                        .as("%s must describe what a warning means", type)
                        .contains("cannot be displayed")
                        .contains("Converted date format to ISO");
                assertThat(outputField(postgres, type, "warnings", "type")).isEqualTo("array");
                assertThat(concepts(postgres, type))
                        .as("%s concepts must tell the agent to read them", type)
                        .contains("read output.warnings");
            }

            // What earlier migrations wrote is still there.
            assertThat(outputField(postgres, "insert_row", "row_id", "description"))
                    .isEqualTo("ID of the inserted row");
            assertThat(outputField(postgres, "update_row", "updated_count", "description"))
                    .isEqualTo("Number of rows updated");
            assertThat(concepts(postgres, "update_row")).contains("Update rows matching a condition");

            // A node the migration does not name keeps its documentation untouched.
            assertThat(scalar(postgres,
                    "SELECT outputs::text FROM orchestrator.node_type_documentation WHERE type = 'delete_row'"))
                    .doesNotContain("warnings");

            // Idempotence: the second copy ran as V3 and must not have duplicated the concept.
            assertThat(countOccurrences(concepts(postgres, "insert_row"), "read output.warnings"))
                    .as("re-applying must not duplicate a concept")
                    .isEqualTo(1);
        }
    }

    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__seed_node_type_documentation.sql"), SCHEMA_AND_ROWS);
        copyMigration(directory, "V481__table_write_nodes_report_warnings.sql",
                "V2__table_write_nodes_report_warnings.sql");
        copyMigration(directory, "V481__table_write_nodes_report_warnings.sql",
                "V3__table_write_nodes_report_warnings_reapply.sql");
    }

    private static void copyMigration(Path directory, String source, String target) throws Exception {
        Files.writeString(directory.resolve(target),
                Files.readString(Path.of("src/main/resources/db/migration/" + source)));
    }

    private static String outputField(FlywayTestSupport.PostgresTarget postgres, String type,
                                      String output, String field) throws Exception {
        return scalar(postgres,
                "SELECT outputs -> '" + output + "' ->> '" + field
                        + "' FROM orchestrator.node_type_documentation WHERE type = '" + type + "'");
    }

    private static String concepts(FlywayTestSupport.PostgresTarget postgres, String type) throws Exception {
        return scalar(postgres,
                "SELECT concepts::text FROM orchestrator.node_type_documentation WHERE type = '" + type + "'");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static String scalar(FlywayTestSupport.PostgresTarget postgres, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.jdbcUrl(DB), postgres.username(), postgres.password());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).as("query must return a row: " + sql).isTrue();
            return resultSet.getString(1);
        }
    }
}
