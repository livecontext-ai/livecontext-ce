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
 * Replays the REAL V488 against the generate row as V429 leaves it.
 *
 * <p>An agent writing a generate node reads {@code parameters} to know a parameter exists at all,
 * so a file slot the platform accepts and this row omits is a slot no agent will ever fill: the
 * failure is silence, not an error. V488 adds the three slots a model can take AT ONCE, and the
 * assertions below are about what it must NOT do while it is there: replace the parameters already
 * documented, rewrite {@code input_image} (which every saved workflow still writes), or reach
 * another node's row.
 */
@DisplayName("V488 documents the generate node's file slots")
class GenerateNodeFileSlotsDocumentationMigrationTest {

    private static final String DB = "generate_file_slots_v488";

    private static final String MIGRATION = "V488__generate_node_documents_its_file_slots.sql";

    /** What V429 leaves behind, trimmed to the keys this migration reasons about. */
    private static final String SCHEMA_AND_ROWS = """
            CREATE SCHEMA orchestrator;

            CREATE TABLE orchestrator.node_type_documentation (
                type        VARCHAR(128) PRIMARY KEY,
                description TEXT NOT NULL DEFAULT '',
                parameters  JSONB NOT NULL DEFAULT '{}'::jsonb,
                outputs     JSONB NOT NULL DEFAULT '{}'::jsonb,
                updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
            );

            INSERT INTO orchestrator.node_type_documentation (type, parameters) VALUES (
                'generate',
                '{"model": {"type": "string", "required": true, "description": "REQUIRED. A generation model id."},
                  "prompt": {"type": "string", "required": false, "description": "What to generate."},
                  "input_image": {"type": "string|object", "required": false, "description": "A reference image or first frame: the WHOLE FileRef output of an upstream node."}}'::jsonb
            );

            -- A row the migration must not touch: it takes files too, and its own
            -- documentation is not this one's business.
            INSERT INTO orchestrator.node_type_documentation (type, parameters) VALUES (
                'media',
                '{"operation": {"type": "string", "required": true, "description": "What to do with the files."}}'::jsonb
            );
            """;

    @Test
    @DisplayName("the three slots a model can take at once are documented, with what each file IS")
    void documentsTheThreeSlots(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB);

            assertThatCode(() -> postgres.runFlyway(DB, tempDir)).doesNotThrowAnyException();

            // Each one says what the file becomes, not only that a file goes there: the three are
            // not interchangeable, and filling the wrong one is a different video, paid for.
            assertThat(param(postgres, DB, "first_frame_image")).contains("OPENS on");
            assertThat(param(postgres, DB, "last_frame_image")).contains("LANDS on");
            assertThat(param(postgres, DB, "reference_image")).contains("borrows");
        }
    }

    @Test
    @DisplayName("the closing frame says it can need the opening one, since that call is refused")
    void warnsThatTheClosingFrameMayNeedItsPair(@TempDir Path tempDir) throws Exception {
        // Seedance takes two images or none. Without this the agent learns it from a refusal, and
        // the only reason that is cheap is that the refusal happens before the charge.
        //
        // Read out of the DATABASE, not out of the file: asserting on the .sql text would pass
        // with a wrong WHERE clause, a wrong column, or a migration Flyway never applies.
        writeFixture(tempDir);

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_pair");

            postgres.runFlyway(DB + "_pair", tempDir);

            assertThat(param(postgres, DB + "_pair", "last_frame_image"))
                    .contains("TOGETHER with first_frame_image")
                    .contains("before anything is charged");
        }
    }

    @Test
    @DisplayName("what was already documented survives, input_image included")
    void keepsWhatWasAlreadyThere(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_keeps");

            postgres.runFlyway(DB + "_keeps", tempDir);

            // A wholesale rewrite of `parameters` would drop the node's required param, and an
            // agent reading the result would stop sending it.
            assertThat(param(postgres, DB + "_keeps", "model")).contains("REQUIRED");
            assertThat(param(postgres, DB + "_keeps", "prompt")).isEqualTo("What to generate.");
            // input_image is the slot for a model that takes ONE image and is what every saved
            // workflow already writes: renaming or redefining it turns those runs into refusals.
            assertThat(param(postgres, DB + "_keeps", "input_image"))
                    .isEqualTo("A reference image or first frame: the WHOLE FileRef output of an upstream node.");
        }
    }

    @Test
    @DisplayName("no other node's row is given generation slots it does not have")
    void touchesNoOtherRow(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_scope");

            postgres.runFlyway(DB + "_scope", tempDir);

            assertThat(parameters(postgres, DB + "_scope", "media"))
                    .doesNotContain("first_frame_image")
                    .contains("operation");
        }
    }

    @Test
    @DisplayName("applying it twice leaves the same row")
    void isIdempotent(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);
        Files.writeString(tempDir.resolve("V489__reapply.sql"),
                Files.readString(Path.of("src/main/resources/db/migration/" + MIGRATION)));

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_idem");

            assertThatCode(() -> postgres.runFlyway(DB + "_idem", tempDir)).doesNotThrowAnyException();

            assertThat(param(postgres, DB + "_idem", "last_frame_image")).contains("LANDS on");
            assertThat(param(postgres, DB + "_idem", "prompt")).isEqualTo("What to generate.");
        }
    }

    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__seed_node_type_documentation.sql"), SCHEMA_AND_ROWS);
        // The harness reads only this directory, so the real migration is copied in: a test that
        // forgot it would assert against an untouched row and fail loudly rather than pass.
        Files.copy(Path.of("src/main/resources/db/migration/" + MIGRATION),
                directory.resolve(MIGRATION));
    }

    private static String param(FlywayTestSupport.PostgresTarget postgres, String db, String name)
            throws Exception {
        return query(postgres, db, "SELECT parameters -> '" + name + "' ->> 'description' "
                + "FROM orchestrator.node_type_documentation WHERE type = 'generate'");
    }

    private static String parameters(FlywayTestSupport.PostgresTarget postgres, String db, String type)
            throws Exception {
        return query(postgres, db, "SELECT parameters::text "
                + "FROM orchestrator.node_type_documentation WHERE type = '" + type + "'");
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
