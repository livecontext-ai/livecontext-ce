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
 * Replays the REAL V409 (media node doc insert) followed by the REAL V410 (video-suite
 * refresh: concat/frame/overlay) against a minimal node_type_documentation fixture and
 * asserts the row ends up with the v2 contract: the new typed params (inputs array,
 * image/input string|object), the new timestamp_seconds output, all-string examples
 * (the V330 List&lt;String&gt; boot invariant) and a description covering the seven
 * operations. Also proves V410 is idempotent (a re-apply changes nothing).
 */
@DisplayName("V410 media video-suite documentation refresh (concat / frame / overlay)")
class MediaVideoSuiteDocumentationMigrationTest {

    @Test
    @DisplayName("V409 insert then V410 refresh yields the v2 media row; a V410 re-apply is a no-op")
    void v410RefreshesMediaDocumentationAndStaysIdempotent(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();

        writeFixture(tempDir);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "media_v410_replay");

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, "media_v410_replay", tempDir))
                    .doesNotThrowAnyException();

            // New params are documented with the exact contract types.
            assertThat(paramField(postgres, "inputs", "type")).isEqualTo("array");
            assertThat(paramField(postgres, "image", "type")).isEqualTo("string|object");
            assertThat(paramField(postgres, "input", "type")).isEqualTo("string|object");
            assertThat(paramField(postgres, "video", "type")).isEqualTo("string|object");
            assertThat(paramField(postgres, "transition", "default")).isEqualTo("cut");
            assertThat(paramField(postgres, "position", "default")).isEqualTo("bottom_right");
            assertThat(paramField(postgres, "at_seconds", "description"))
                    .as("frame's default-middle + clamp must be documented on at_seconds")
                    .containsIgnoringCase("middle")
                    .containsIgnoringCase("clamp");

            // New output: timestamp_seconds, typed number, frame-only.
            assertThat(outputField(postgres, "timestamp_seconds", "type")).isEqualTo("number");
            assertThat(outputField(postgres, "timestamp_seconds", "description"))
                    .containsIgnoringCase("frame only");
            // v1 outputs survive the refresh.
            assertThat(outputField(postgres, "file", "type")).isEqualTo("fileRef");
            assertThat(outputField(postgres, "has_audio", "type")).isEqualTo("boolean");

            // Description now covers the seven operations.
            String description = scalar(postgres,
                    "SELECT description FROM orchestrator.node_type_documentation WHERE type = 'media'");
            assertThat(description).contains("concat").contains("frame").contains("overlay");

            // The V330 boot invariant: every examples element MUST be a JSON string
            // (an object element crash-loops orchestrator's List<String> mapping).
            assertThat(Integer.parseInt(scalar(postgres, """
                    SELECT COUNT(*)
                      FROM orchestrator.node_type_documentation,
                           jsonb_array_elements(examples) AS elem
                     WHERE type = 'media' AND jsonb_typeof(elem) <> 'string'
                    """))).isZero();
            assertThat(Integer.parseInt(scalar(postgres, """
                    SELECT jsonb_array_length(examples)
                      FROM orchestrator.node_type_documentation WHERE type = 'media'
                    """)))
                    .as("V410 ships the three new worked examples")
                    .isEqualTo(3);

            // Idempotency: the fixture applied V410 TWICE (V3 + V4) - a second
            // application must leave a single, still-valid media row.
            assertThat(Integer.parseInt(scalar(postgres,
                    "SELECT COUNT(*) FROM orchestrator.node_type_documentation WHERE type = 'media'")))
                    .isEqualTo(1);
        }
    }

    /**
     * Minimal fixture: the node_type_documentation table with the columns V409/V410
     * touch, then the REAL V409 (insert) and the REAL V410 applied twice (refresh +
     * idempotency re-apply). Mirrors the production chain without the full
     * multi-hundred-migration schema.
     */
    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__seed_node_type_documentation.sql"), """
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
                """);

        String v409 = Files.readString(Path.of(
                "src/main/resources/db/migration/V409__add_media_node_documentation.sql"));
        Files.writeString(directory.resolve("V2__add_media_node_documentation.sql"), v409);

        String v410 = Files.readString(Path.of(
                "src/main/resources/db/migration/V410__media_video_suite_documentation.sql"));
        Files.writeString(directory.resolve("V3__media_video_suite_documentation.sql"), v410);
        Files.writeString(directory.resolve("V4__media_video_suite_reapply.sql"), v410);
    }

    private static String paramField(PostgreSQLContainer<?> postgres, String param, String field) throws Exception {
        return scalar(postgres,
                "SELECT parameters -> '" + param + "' ->> '" + field
                        + "' FROM orchestrator.node_type_documentation WHERE type = 'media'");
    }

    private static String outputField(PostgreSQLContainer<?> postgres, String output, String field) throws Exception {
        return scalar(postgres,
                "SELECT outputs -> '" + output + "' ->> '" + field
                        + "' FROM orchestrator.node_type_documentation WHERE type = 'media'");
    }

    private static String scalar(PostgreSQLContainer<?> postgres, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                FlywayTestSupport.jdbcUrl(postgres, "media_v410_replay"),
                postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).as("query must return a row: " + sql).isTrue();
            return resultSet.getString(1);
        }
    }
}
