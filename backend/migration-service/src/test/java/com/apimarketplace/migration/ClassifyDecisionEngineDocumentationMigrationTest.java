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
 * Replays the REAL V505 against the classify row shaped as V422 leaves it.
 *
 * <p>This is the leg of the mandated 3-way output alignment that the authoring agent
 * actually reads, and it is four statements of hand-written JSONB surgery: one {@code ||}
 * merge, two {@code jsonb_set} rewrites into nested paths, and one array append. Each has
 * its own way of failing quietly. A {@code jsonb_set} against a path that does not exist
 * returns the document UNCHANGED rather than erroring, so a typo in
 * {@code '{reasoning,description}'} would leave the agent reading the old text with
 * nothing to show for it; and {@code concepts || to_jsonb(ARRAY[...])} appends only
 * because the column really holds a JSON array, which nothing else in this change set
 * checks.
 *
 * <p>The re-applied copy is what proves the guards: the additive statement is conditioned
 * on the key's absence and the concepts append on its own text, so a replay must add
 * nothing a second time.
 */
@DisplayName("V505 classify documents the decision engine")
class ClassifyDecisionEngineDocumentationMigrationTest {

    private static final String DB = "classify_decision_engine_v505";

    /** The classify row as V422 leaves it: outputs and parameters, concepts a real array. */
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

            INSERT INTO orchestrator.node_type_documentation (type, parameters, outputs, concepts) VALUES (
                'classify',
                '{"provider": {"type": "string", "description": "AI provider to use"},
                  "categories": {"type": "array", "minItems": 2, "description": "Array of {label, description} objects"},
                  "temperature": {"type": "number", "description": "Lower = more consistent"}}'::jsonb,
                '{"selected_category": {"type": "string", "description": "The category selected by the classifier"},
                  "reasoning": {"type": "string", "description": "Explanation for the classification decision"},
                  "confidence": {"type": "number", "description": "Confidence score of the classification"}}'::jsonb,
                '["SEMANTIC ROUTING: AI understands meaning, not just values"]'::jsonb
            );

            -- A node the migration must not touch.
            INSERT INTO orchestrator.node_type_documentation (type, parameters, outputs, concepts) VALUES (
                'guardrail',
                '{"provider": {"type": "string", "description": "AI provider to use"}}'::jsonb,
                '{"passed": {"type": "boolean", "description": "Whether the content passed"}}'::jsonb,
                '["Guardrail validates content"]'::jsonb
            );
            """;

    @Test
    @DisplayName("V505 documents the second engine, keeps what was there, and re-applies cleanly")
    void v505DocumentsTheDecisionEngine(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB);

            assertThatCode(() -> postgres.runFlyway(DB, tempDir)).doesNotThrowAnyException();

            // The new output, which is the whole point of the 3-way alignment.
            assertThat(outputField(postgres, "classify", "probabilities", "type")).isEqualTo("object");
            assertThat(outputField(postgres, "classify", "probabilities", "description"))
                    .contains("decision model")
                    .contains("absent on a chat model");

            // reasoning no longer always holds prose, and the agent has to be told, or it
            // writes a prompt-style consumer for what is now a numeric string.
            assertThat(outputField(postgres, "classify", "reasoning", "description"))
                    .as("jsonb_set into a nested path returns the document unchanged on a typo")
                    .contains("THREE highest-scoring")
                    .doesNotContain("Explanation for the classification decision");

            // The parameter an agent reads when it chooses an engine.
            assertThat(parameterField(postgres, "classify", "provider", "description"))
                    .contains("typesafe")
                    .contains("jev-latest")
                    .contains("ignores `temperature`");

            // Distinct labels are a requirement, and this is where the agent learns it.
            assertThat(parameterField(postgres, "classify", "categories", "description"))
                    .contains("LABELS MUST BE DISTINCT")
                    .contains("unreachable");

            assertThat(concepts(postgres, "classify"))
                    .contains("TWO ENGINES")
                    .contains("CLOSE CALLS")
                    // The append must EXTEND the array, not replace it.
                    .contains("SEMANTIC ROUTING");

            // What earlier migrations wrote is untouched.
            assertThat(outputField(postgres, "classify", "selected_category", "description"))
                    .isEqualTo("The category selected by the classifier");
            assertThat(parameterField(postgres, "classify", "temperature", "description"))
                    .isEqualTo("Lower = more consistent");

            // A node the migration does not name keeps its documentation.
            assertThat(scalar(postgres,
                    "SELECT outputs::text FROM orchestrator.node_type_documentation WHERE type = 'guardrail'"))
                    .doesNotContain("probabilities");
            assertThat(concepts(postgres, "guardrail")).doesNotContain("TWO ENGINES");

            // Idempotence: the second copy ran as V3 and must have added nothing.
            assertThat(countOccurrences(concepts(postgres, "classify"), "TWO ENGINES"))
                    .as("re-applying must not duplicate a concept")
                    .isEqualTo(1);
            assertThat(countOccurrences(concepts(postgres, "classify"), "CLOSE CALLS")).isEqualTo(1);
        }
    }

    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__seed_node_type_documentation.sql"), SCHEMA_AND_ROWS);
        copyMigration(directory, "V505__classify_probabilities_output_doc.sql",
                "V2__classify_probabilities_output_doc.sql");
        copyMigration(directory, "V505__classify_probabilities_output_doc.sql",
                "V3__classify_probabilities_output_doc_reapply.sql");
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

    private static String parameterField(FlywayTestSupport.PostgresTarget postgres, String type,
                                         String parameter, String field) throws Exception {
        return scalar(postgres,
                "SELECT parameters -> '" + parameter + "' ->> '" + field
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
