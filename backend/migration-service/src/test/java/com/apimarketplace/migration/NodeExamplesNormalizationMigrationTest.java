package com.apimarketplace.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

@DisplayName("node_type_documentation.examples normalization (V326 object -> V330 string)")
class NodeExamplesNormalizationMigrationTest {

    @Test
    @DisplayName("V326 inserts an object element; V330 normalizes it to a string while preserving content")
    void v330NormalizesObjectExampleToStringPreservingBody(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();

        writeFixture(tempDir);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "node_examples_replay");

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, "node_examples_replay", tempDir))
                    .doesNotThrowAnyException();

            // After V330 every element of the task node's examples must be a JSON
            // string - an object element is exactly what crashes the orchestrator's
            // List<String> mapping on boot.
            assertThat(nonStringExampleCount(postgres, "node_examples_replay", "task")).isZero();

            // The pre-existing string example is preserved...
            List<String> examples = taskExamples(postgres, "node_examples_replay");
            assertThat(examples).contains("existing string example");
            // ...and the V326 object's "body" text survived as a string element.
            assertThat(examples).anyMatch(e -> e.contains("taskContext key/values are attached to the task"));
        }
    }

    @Test
    @DisplayName("V330 is idempotent: a second application is a no-op on already-clean data")
    void v330IsIdempotent(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();

        writeFixture(tempDir);
        // V4 = V330 applied a second time (Flyway treats it as a new version, but
        // the WHERE guard means it must touch nothing once the array is clean).
        Files.writeString(tempDir.resolve("V4__reapply_normalization.sql"),
                Files.readString(Path.of(
                        "src/main/resources/db/migration/"
                                + "V330__normalize_node_documentation_examples_to_strings.sql")));

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "node_examples_idempotent");

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, "node_examples_idempotent", tempDir))
                    .doesNotThrowAnyException();

            assertThat(nonStringExampleCount(postgres, "node_examples_idempotent", "task")).isZero();
            assertThat(taskExamples(postgres, "node_examples_idempotent")).hasSize(2);
        }
    }

    /**
     * Minimal fixture: a node_type_documentation table carrying a task row whose
     * examples is an all-string array, then the REAL V326 (which appends the
     * offending object) and the REAL V330 (the fix). Mirrors the production
     * chain without needing the full multi-hundred-migration schema.
     */
    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__seed_node_type_documentation.sql"), """
                CREATE SCHEMA orchestrator;

                CREATE TABLE orchestrator.node_type_documentation (
                    type        VARCHAR(128) PRIMARY KEY,
                    description TEXT NOT NULL DEFAULT '',
                    parameters  JSONB NOT NULL DEFAULT '{}'::jsonb,
                    examples    JSONB NOT NULL DEFAULT '[]'::jsonb,
                    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );

                INSERT INTO orchestrator.node_type_documentation (type, description, parameters, examples)
                VALUES (
                    'task',
                    'task node. ''task'': { operation, taskId, title, instructions, priority, agentId, reviewerAgentId, status, search, limit }',
                    '{}'::jsonb,
                    '["existing string example"]'::jsonb
                );
                """);

        String v326 = Files.readString(Path.of(
                "src/main/resources/db/migration/V326__document_task_node_task_context.sql"));
        Files.writeString(directory.resolve("V2__document_task_node_task_context.sql"), v326);

        String v330 = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V330__normalize_node_documentation_examples_to_strings.sql"));
        Files.writeString(directory.resolve("V3__normalize_examples.sql"), v330);
    }

    private static int nonStringExampleCount(
            PostgreSQLContainer<?> postgres, String databaseName, String nodeType) throws Exception {
        try (var connection = DriverManager.getConnection(
                FlywayTestSupport.jdbcUrl(postgres, databaseName), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                       FROM orchestrator.node_type_documentation,
                            jsonb_array_elements(examples) AS elem
                      WHERE type = ?
                        AND jsonb_typeof(elem) <> 'string'
                     """)) {
            statement.setString(1, nodeType);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static List<String> taskExamples(PostgreSQLContainer<?> postgres, String databaseName) throws Exception {
        try (var connection = DriverManager.getConnection(
                FlywayTestSupport.jdbcUrl(postgres, databaseName), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT elem #>> '{}'
                       FROM orchestrator.node_type_documentation,
                            jsonb_array_elements(examples) AS elem
                      WHERE type = 'task'
                     """)) {
            List<String> out = new ArrayList<>();
            while (resultSet.next()) {
                out.add(resultSet.getString(1));
            }
            return out;
        }
    }
}
