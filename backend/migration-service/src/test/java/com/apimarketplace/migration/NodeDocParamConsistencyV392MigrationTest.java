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
 * V392 completes the node-doc consistency pass: limit examples show the required
 * input, date_time examples are flat (no dateTime wrapper the creator ignores),
 * option drops the unread fallback param, and compression/convert_to_file/response
 * concepts+examples stop referencing fields/params the backend never emits/reads.
 * Each fixture row seeds the exact pre-fix shape.
 */
@DisplayName("node_type_documentation param consistency v2 (V392)")
class NodeDocParamConsistencyV392MigrationTest {

    @Test
    @DisplayName("V392 corrects limit/date_time/option/compression/convert_to_file/response docs")
    void v392CompletesNodeDocAlignment(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();
        writeFixture(tempDir);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "node_doc_v392");
            String db = "node_doc_v392";

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, db, tempDir))
                    .doesNotThrowAnyException();

            // 1. limit: required input now shown in ALL THREE examples
            assertThat(text(postgres, db, "limit", "examples::text"))
                    .contains("input: '{{core:sort_results.output.items}}'")
                    .contains("input: '{{core:fetch_data.output.items}}'")
                    .contains("input: '{{core:get_all_items.output.items}}'");

            // 2. date_time: flat examples, no nested dateTime wrapper
            assertThat(text(postgres, db, "date_time", "examples::text"))
                    .doesNotContain("dateTime: {").contains("operation: 'format'");

            // 3. option: fallback param removed AND the nonexistent fallback port claim gone
            assertThat(has(postgres, db, "option", "parameters", "fallback")).isFalse();
            assertThat(text(postgres, db, "option", "edge_ports::text"))
                    .doesNotContain("fallback").contains("Creates 1 port per choice.");

            // 4. compression: file_url -> file
            assertThat(text(postgres, db, "compression", "concepts::text"))
                    .doesNotContain("output.file_url").contains("output.file}}");

            // 5. convert_to_file: real output fields only
            assertThat(text(postgres, db, "convert_to_file", "concepts::text"))
                    .doesNotContain("output.filename")
                    .doesNotContain("output.rowCount")
                    .doesNotContain("output.file_url")
                    .contains("output.row_count");

            // 6. response: ignored format param removed from example
            assertThat(text(postgres, db, "response", "examples::text"))
                    .doesNotContain("format: 'markdown'").contains("message:");
        }
    }

    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__seed.sql"), """
                CREATE SCHEMA orchestrator;
                CREATE TABLE orchestrator.node_type_documentation (
                    type        VARCHAR(128) PRIMARY KEY,
                    description TEXT NOT NULL DEFAULT '',
                    parameters  JSONB,
                    concepts    JSONB,
                    examples    JSONB,
                    edge_ports  JSONB,
                    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );

                INSERT INTO orchestrator.node_type_documentation (type, parameters, edge_ports) VALUES
                ('option',
                 '{"choices": {"type": "array", "required": true}, "fallback": {"type": "string", "description": "skip or error"}}'::jsonb,
                 '{"note": "N = array index (0-based). Creates 1 port per choice + optional fallback.", "example": "choices: [A, B, C] -> choice_0, choice_1, choice_2, fallback", "pattern": "core:label:choice_N"}'::jsonb);

                INSERT INTO orchestrator.node_type_documentation (type, parameters, concepts, examples) VALUES
                ('limit',
                 '{"count": {"type": "number", "required": true}, "input": {"type": "string", "required": true}}'::jsonb,
                 NULL,
                 '["workflow(action=''add_node'', type=''limit'', label=''Top 5'', params={count: 5, from: ''first''}, connect_after=''Sort Results'')", "workflow(action=''add_node'', type=''limit'', label=''Last 3'', params={count: 3, from: ''last''}, connect_after=''Fetch Data'')", "workflow(action=''add_node'', type=''limit'', label=''Page 2'', params={count: 10, from: ''first'', offset: 10}, connect_after=''Get All Items'')"]'::jsonb),
                ('date_time',
                 '{"operation": {"type": "string"}}'::jsonb,
                 NULL,
                 '["workflow(action=''add_node'', type=''date_time'', label=''Format Date'', params={dateTime: {operation: ''format'', value: ''{{trigger:start.output.created_at}}'', outputFormat: ''dd MMM yyyy''}}, connect_after=''Start'')"]'::jsonb),
                ('compression',
                 '{"value": {"type": "string"}}'::jsonb,
                 '["Access result: {{core:label.output.result}}", "Compression uploads to S3. Access file URL: {{core:label.output.file_url}}. Decompress does not produce a file."]'::jsonb,
                 '[]'::jsonb),
                ('convert_to_file',
                 '{"value": {"type": "string"}}'::jsonb,
                 '["Access result: {{core:label.output.result}}", "Access filename: {{core:label.output.filename}}", "Access row count: {{core:label.output.rowCount}}", "ConvertToFile uploads to S3. Access the file URL: {{core:label.output.file_url}}. Downstream nodes can accept the FileRef."]'::jsonb,
                 '[]'::jsonb),
                ('response',
                 '{"message": {"type": "string", "required": true}}'::jsonb,
                 NULL,
                 '["workflow(action=''add_node'', type=''response'', label=''Reply'', params={message: ''Order {{mcp:create_order.output.id}} created!'', format: ''markdown''}, connect_after=''Create Order'')"]'::jsonb);
                """);

        String v392 = Files.readString(Path.of("src/main/resources/db/migration/"
                + "V392__fix_more_node_doc_param_inconsistencies.sql"));
        Files.writeString(directory.resolve("V2__fix.sql"), v392);
    }

    private static boolean has(PostgreSQLContainer<?> pg, String db, String type, String col, String key)
            throws Exception {
        return "true".equals(scalar(pg, db,
                "SELECT jsonb_exists(" + col + ", '" + key + "')::text FROM orchestrator.node_type_documentation WHERE type = '"
                        + type + "'"));
    }

    private static String text(PostgreSQLContainer<?> pg, String db, String type, String expr) throws Exception {
        return scalar(pg, db, "SELECT " + expr + " FROM orchestrator.node_type_documentation WHERE type = '"
                + type + "'");
    }

    private static String scalar(PostgreSQLContainer<?> pg, String db, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                FlywayTestSupport.jdbcUrl(pg, db), pg.getUsername(), pg.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
