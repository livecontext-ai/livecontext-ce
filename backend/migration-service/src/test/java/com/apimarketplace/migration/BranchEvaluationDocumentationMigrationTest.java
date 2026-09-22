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
 * Replays the REAL V492 against the branching rows as earlier migrations leave them.
 *
 * <p>An agent reads {@code node_type_documentation.outputs} to learn what a node returns.
 * For the three branching types it read "Detailed evaluation results", which names no
 * field, and for {@code option} it named four fields that have since been renamed. An
 * agent following a stale description writes templates that resolve to nothing, and the
 * run reports COMPLETED either way: the failure is silence.
 *
 * <p>The assertion that matters most here is that the migration is not a silent no-op.
 * It is guarded by {@code WHERE outputs ? 'evaluations'}, so a row whose key is spelled
 * differently would be skipped with no error and no log, leaving the old text in place.
 * This test reads the DATABASE after Flyway runs, never the .sql text, which is the only
 * way to see that.
 */
@DisplayName("V492 documents the one branch-evaluation shape")
class BranchEvaluationDocumentationMigrationTest {

    private static final String DB = "branch_evaluations_v492";

    private static final String MIGRATION = "V492__branch_evaluations_report_one_shape.sql";

    /** The branching rows as V422 and V167 leave them, trimmed to what V492 reasons about. */
    private static final String SCHEMA_AND_ROWS = """
            CREATE SCHEMA orchestrator;

            CREATE TABLE orchestrator.node_type_documentation (
                type        VARCHAR(128) PRIMARY KEY,
                description TEXT NOT NULL DEFAULT '',
                parameters  JSONB NOT NULL DEFAULT '{}'::jsonb,
                outputs     JSONB NOT NULL DEFAULT '{}'::jsonb,
                updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
            );

            INSERT INTO orchestrator.node_type_documentation (type, outputs) VALUES
            ('decision', '{
               "selected_branch": {"type": "string", "description": "The branch that was selected (if/else/elseif_N)"},
               "evaluations": {"type": "array", "description": "Evaluation details for each condition"}
             }'::jsonb),
            ('switch', '{
               "selected_case_index": {"type": "number", "description": "Index of the selected case"},
               "evaluations": {"type": "array", "description": "Evaluation details for each case"}
             }'::jsonb),
            ('option', '{
               "selected_label": {"type": "string", "description": "Label of the selected choice"},
               "evaluations": {"type": "array", "description": "Detailed evaluation results per choice: {choice_id, choice_label, expression, resolved_expression, result, error?}"}
             }'::jsonb),
            ('transform', '{
               "evaluations": {"type": "array", "description": "Array of mapping evaluations, each with field, expression, resolved_expression, and value"}
             }'::jsonb);
            """;

    @Test
    @DisplayName("each branching type names the keys an entry really carries")
    void namesTheCanonicalKeys(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB);

            assertThatCode(() -> postgres.runFlyway(DB, tempDir)).doesNotThrowAnyException();

            // `selected` is the field whose absence made a skipped else read exactly like a
            // matched if, so naming it is the whole point of the row.
            assertThat(evaluations(postgres, DB, "decision"))
                    .contains("selected")
                    .contains("elseif_N")
                    .contains("null when the branch has no condition");
            assertThat(evaluations(postgres, DB, "switch"))
                    .contains("case_N")
                    .contains("the comparison that was made");
            assertThat(evaluations(postgres, DB, "option"))
                    .contains("choice_N")
                    .contains("resolved");
        }
    }

    @Test
    @DisplayName("the option row drops the field names the code no longer emits")
    void replacesTheStaleOptionShape(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_option");

            postgres.runFlyway(DB + "_option", tempDir);

            // An agent writing {{core:pick.output.evaluations[0].resolved_expression}} from
            // the old text gets nothing back, silently.
            assertThat(evaluations(postgres, DB + "_option", "option"))
                    .doesNotContain("resolved_expression")
                    .doesNotContain("expression, resolved_expression");
        }
    }

    @Test
    @DisplayName("it actually wrote something: the guard did not skip every row")
    void isNotASilentNoOp(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_noop");

            postgres.runFlyway(DB + "_noop", tempDir);

            // WHERE outputs ? 'evaluations' makes a no-op indistinguishable from success:
            // Flyway records the migration as applied either way.
            for (String type : new String[] {"decision", "switch", "option"}) {
                assertThat(evaluations(postgres, DB + "_noop", type))
                        .as("%s still carries its pre-V492 description", type)
                        .doesNotContain("Evaluation details for each")
                        .doesNotContain("Detailed evaluation results per choice");
            }
        }
    }

    @Test
    @DisplayName("a transform's evaluations are a different idea and are left alone")
    void touchesNoOtherRow(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_scope");

            postgres.runFlyway(DB + "_scope", tempDir);

            // A transform's `evaluations` are its MAPPINGS, not branches. It shares the key
            // name and nothing else, and rewriting it would document a shape it never emits.
            assertThat(evaluations(postgres, DB + "_scope", "transform"))
                    .contains("mapping evaluations")
                    .doesNotContain("selected");
        }
    }

    @Test
    @DisplayName("keeps every other output key on the rows it does touch")
    void keepsSiblingKeys(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_keeps");

            postgres.runFlyway(DB + "_keeps", tempDir);

            // jsonb_set on one path, not a wholesale rewrite: restating the object would
            // revert any description a later migration refined.
            assertThat(outputs(postgres, DB + "_keeps", "decision")).contains("selected_branch");
            assertThat(outputs(postgres, DB + "_keeps", "switch")).contains("selected_case_index");
            assertThat(outputs(postgres, DB + "_keeps", "option")).contains("selected_label");
        }
    }

    @Test
    @DisplayName("applying it twice leaves the same row")
    void isIdempotent(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);
        Files.writeString(tempDir.resolve("V493__reapply.sql"),
                Files.readString(Path.of("src/main/resources/db/migration/" + MIGRATION)));

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_idem");

            assertThatCode(() -> postgres.runFlyway(DB + "_idem", tempDir)).doesNotThrowAnyException();

            assertThat(evaluations(postgres, DB + "_idem", "decision")).contains("selected");
            assertThat(outputs(postgres, DB + "_idem", "decision")).contains("selected_branch");
        }
    }

    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__seed_node_type_documentation.sql"), SCHEMA_AND_ROWS);
        // The harness reads only this directory, so the real migration is copied in: a test
        // that forgot it would assert against an untouched row and fail loudly, not pass.
        Files.copy(Path.of("src/main/resources/db/migration/" + MIGRATION),
                directory.resolve(MIGRATION));
    }

    private static String evaluations(FlywayTestSupport.PostgresTarget postgres, String db, String type)
            throws Exception {
        return query(postgres, db, "SELECT outputs -> 'evaluations' ->> 'description' "
                + "FROM orchestrator.node_type_documentation WHERE type = '" + type + "'");
    }

    private static String outputs(FlywayTestSupport.PostgresTarget postgres, String db, String type)
            throws Exception {
        return query(postgres, db, "SELECT outputs::text "
                + "FROM orchestrator.node_type_documentation WHERE type = '" + type + "'");
    }

    private static String query(FlywayTestSupport.PostgresTarget postgres, String db, String sql)
            throws Exception {
        try (var connection = DriverManager.getConnection(
                        postgres.jdbcUrl(db), postgres.username(), postgres.password());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }
}
