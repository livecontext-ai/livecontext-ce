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
 * V349 strips the em-dash (U+2014), en-dash (U+2013) and horizontal-bar (U+2015)
 * glyphs from the agent-facing node_type_documentation prose, replacing each with
 * a plain hyphen, while leaving JSON structure and dash-free rows untouched.
 *
 * <p>The fixture below deliberately seeds rows that CONTAIN those glyphs - that is
 * the input the migration must clean, so the glyphs here are intentional test data.
 */
@DisplayName("node_type_documentation em-dash strip (V349)")
class NodeDocsEmDashStripMigrationTest {

    @Test
    @DisplayName("V349 replaces em/en-dash glyphs with hyphens in prose and JSONB, preserving content and structure")
    void v349StripsDashGlyphsPreservingContent(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();
        writeFixture(tempDir);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "node_docs_emdash");

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, "node_docs_emdash", tempDir))
                    .doesNotThrowAnyException();

            // No dash glyph survives anywhere in the dashed row...
            assertThat(dashGlyphCount(postgres, "node_docs_emdash", "agent")).isZero();
            // ...and the prose is preserved with a plain hyphen in place of the em-dash.
            assertThat(description(postgres, "node_docs_emdash", "agent"))
                    .isEqualTo("Autonomous AI agent - create with agent first.");
            // JSONB stays valid and the KEY is preserved; only the value's glyph changed.
            assertThat(paramDesc(postgres, "node_docs_emdash", "agent"))
                    .isEqualTo("Reference data using {{syntax}} - nothing is automatic");
            assertThat(firstExample(postgres, "node_docs_emdash", "agent"))
                    .isEqualTo("step one - then step two");

            // A row that never contained a dash glyph is left exactly as-is.
            assertThat(description(postgres, "node_docs_emdash", "clean"))
                    .isEqualTo("No dashes here, just hyphens like kebab-case.");
        }
    }

    @Test
    @DisplayName("V349 is idempotent: a second application is a no-op on already-clean data")
    void v349IsIdempotent(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();
        writeFixture(tempDir);
        // V3 = V349 applied a second time. The WHERE guard must make it touch nothing.
        Files.writeString(tempDir.resolve("V3__reapply_emdash_strip.sql"),
                Files.readString(Path.of("src/main/resources/db/migration/"
                        + "V349__strip_emdashes_from_node_documentation.sql")));

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "node_docs_emdash_idem");

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, "node_docs_emdash_idem", tempDir))
                    .doesNotThrowAnyException();

            assertThat(dashGlyphCount(postgres, "node_docs_emdash_idem", "agent")).isZero();
            assertThat(description(postgres, "node_docs_emdash_idem", "agent"))
                    .isEqualTo("Autonomous AI agent - create with agent first.");
            assertThat(firstExample(postgres, "node_docs_emdash_idem", "agent"))
                    .isEqualTo("step one - then step two");
        }
    }

    /**
     * Minimal fixture: a node_type_documentation table carrying the columns V349
     * touches, an 'agent' row whose description, a parameter description and an
     * example all contain dash glyphs, and a dash-free 'clean' row that the WHERE
     * guard must leave untouched.
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

                INSERT INTO orchestrator.node_type_documentation (type, description, parameters, examples)
                VALUES (
                    'agent',
                    'Autonomous AI agent - create with agent first.',
                    '{"prompt": {"description": "Reference data using {{syntax}} - nothing is automatic"}}'::jsonb,
                    '["step one - then step two"]'::jsonb
                );

                INSERT INTO orchestrator.node_type_documentation (type, description)
                VALUES (
                    'clean',
                    'No dashes here, just hyphens like kebab-case.'
                );
                """);

        String v349 = Files.readString(Path.of("src/main/resources/db/migration/"
                + "V349__strip_emdashes_from_node_documentation.sql"));
        Files.writeString(directory.resolve("V2__strip_emdashes.sql"), v349);
    }

    private static int dashGlyphCount(PostgreSQLContainer<?> postgres, String db, String type) throws Exception {
        // Counts any em/en/bar glyph remaining across every text + JSONB column of the row.
        try (var connection = DriverManager.getConnection(
                FlywayTestSupport.jdbcUrl(postgres, db), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                       FROM orchestrator.node_type_documentation
                      WHERE type = ?
                        AND (description ~ '[--―]'
                          OR parameters::text ~ '[--―]'
                          OR outputs::text ~ '[--―]'
                          OR global_variables::text ~ '[--―]'
                          OR examples::text ~ '[--―]'
                          OR keywords::text ~ '[--―]'
                          OR edge_ports::text ~ '[--―]'
                          OR concepts::text ~ '[--―]'
                          OR comparison::text ~ '[--―]')
                     """)) {
            statement.setString(1, type);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static String description(PostgreSQLContainer<?> postgres, String db, String type) throws Exception {
        return scalar(postgres, db,
                "SELECT description FROM orchestrator.node_type_documentation WHERE type = '" + type + "'");
    }

    private static String paramDesc(PostgreSQLContainer<?> postgres, String db, String type) throws Exception {
        return scalar(postgres, db,
                "SELECT parameters #>> '{prompt,description}' FROM orchestrator.node_type_documentation WHERE type = '"
                        + type + "'");
    }

    private static String firstExample(PostgreSQLContainer<?> postgres, String db, String type) throws Exception {
        return scalar(postgres, db,
                "SELECT examples #>> '{0}' FROM orchestrator.node_type_documentation WHERE type = '" + type + "'");
    }

    private static String scalar(PostgreSQLContainer<?> postgres, String db, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                FlywayTestSupport.jdbcUrl(postgres, db), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
