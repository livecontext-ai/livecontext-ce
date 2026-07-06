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
 * V391 aligns agent-facing node_type_documentation with what the backend
 * creators actually parse. Each fixture row below seeds the exact PRE-FIX shape
 * (the misleading doc an agent would copy) and the assertions prove V391
 * corrects it. The em-dash glyphs in the {@code code} fixture are the
 * intentional banned glyphs V391 must strip.
 */
@DisplayName("node_type_documentation param consistency (V391)")
class NodeDocParamConsistencyMigrationTest {

    @Test
    @DisplayName("V391 corrects every documented param/example that the backend does not parse")
    void v391AlignsNodeDocsToBackend(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();
        writeFixture(tempDir);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "node_doc_params");

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, "node_doc_params", tempDir))
                    .doesNotThrowAnyException();

            String db = "node_doc_params";
            // 1. exit: status values the creator accepts (no 'stopped')
            assertThat(jsonb(postgres, db, "exit", "parameters #> '{status,enum}'"))
                    .isEqualTo("[\"exited\", \"cancelled\", \"failed\", \"completed\"]");
            assertThat(jsonb(postgres, db, "exit", "parameters #>> '{status,default}'")).isEqualTo("exited");

            // 2. approval: message -> contextTemplate (param + example)
            assertThat(has(postgres, db, "approval", "parameters", "message")).isFalse();
            assertThat(has(postgres, db, "approval", "parameters", "contextTemplate")).isTrue();
            assertThat(text(postgres, db, "approval", "examples::text")).contains("contextTemplate:")
                    .doesNotContain("message:");

            // 3. get_rows: order_by removed
            assertThat(has(postgres, db, "get_rows", "parameters", "order_by")).isFalse();

            // 4. workflow trigger: trigger_on / input_mapping removed
            assertThat(has(postgres, db, "workflow", "parameters", "trigger_on")).isFalse();
            assertThat(has(postgres, db, "workflow", "parameters", "input_mapping")).isFalse();

            // 5. webhook: real auth model
            assertThat(has(postgres, db, "webhook", "parameters", "auth_config")).isFalse();
            assertThat(jsonb(postgres, db, "webhook", "parameters #> '{auth_type,enum}'"))
                    .isEqualTo("[\"none\", \"basic\", \"header\", \"jwt\"]");
            for (String k : new String[] {"basicUsername", "basicPassword", "authHeaderName",
                    "authHeaderValue", "jwtSecretKey", "jwtAlgorithm"}) {
                assertThat(has(postgres, db, "webhook", "parameters", k)).as(k).isTrue();
            }

            // 6. chat: chatMatch documented
            assertThat(has(postgres, db, "chat", "parameters", "chatMatch")).isTrue();

            // 7. merge: BOTH the example and the parameters description use sourceStep,
            //    not the no-op label sub-key
            assertThat(text(postgres, db, "merge", "examples::text")).contains("sourceStep")
                    .doesNotContain("{label: 'Email'}");
            assertThat(text(postgres, db, "merge", "parameters::text")).contains("sourceStep")
                    .doesNotContain("[{label}] to name the expected inputs");

            // 8. email_inbox: invalid {{split:...}} prefix fixed
            assertThat(text(postgres, db, "email_inbox", "examples::text"))
                    .contains("{{item.uid}}").doesNotContain("{{split:item");

            // 9. code: no banned em/en dash survives
            assertThat(text(postgres, db, "code", "description")).doesNotContainPattern("[--―]");
            assertThat(text(postgres, db, "code", "concepts::text")).doesNotContainPattern("[--―]");

            // 10. dead tool name gone from every node
            assertThat(text(postgres, db, "code", "examples::text")).contains("workflow(")
                    .doesNotContain("workflow_builder(");
            assertThat(text(postgres, db, "exit", "examples::text")).doesNotContain("workflow_builder(");
        }
    }

    /**
     * Minimal fixture carrying the PRE-FIX rows for each node V391 corrects.
     * The em-dash (U+2014) glyphs in the 'code' row are intentional banned input.
     */
    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__seed.sql"), """
                CREATE SCHEMA orchestrator;
                CREATE TABLE orchestrator.node_type_documentation (
                    type             VARCHAR(128) PRIMARY KEY,
                    label            VARCHAR(128) NOT NULL DEFAULT '',
                    category         VARCHAR(64) NOT NULL DEFAULT '',
                    description      TEXT NOT NULL DEFAULT '',
                    parameters       JSONB,
                    outputs          JSONB,
                    global_variables JSONB,
                    examples         JSONB,
                    concepts         JSONB,
                    keywords         JSONB,
                    edge_ports       JSONB,
                    comparison       JSONB,
                    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );

                INSERT INTO orchestrator.node_type_documentation (type, description, parameters, examples) VALUES
                ('exit', 'End a branch.',
                 '{"status": {"enum": ["stopped","cancelled","failed","completed"], "type": "string", "default": "stopped"}}'::jsonb,
                 '["workflow_builder(action=''add_node'', type=''exit'', label=''Exit'')"]'::jsonb),
                ('approval', 'User approval.',
                 '{"message": {"type": "string", "required": false, "description": "Message shown to approver."}}'::jsonb,
                 '["workflow(action=''add_node'', type=''approval'', label=''Review'', params={approver_roles: [''manager''], message: ''Review #1''})"]'::jsonb),
                ('get_rows', 'Read rows.',
                 '{"where": {"type": "object"}, "order_by": {"type": "object", "description": "{column, direction}"}, "table_id": {"type": "integer", "required": true}}'::jsonb,
                 '["workflow(action=''read_rows'', table_id=1)"]'::jsonb),
                ('workflow', 'Workflow trigger.',
                 '{"trigger_on": {"enum": ["success","failure"], "type": "string"}, "workflow_id": {"type": "uuid", "required": true}, "input_mapping": {"type": "object"}}'::jsonb,
                 '["workflow(action=''add_node'', type=''workflow'')"]'::jsonb),
                ('webhook', 'Webhook trigger.',
                 '{"method": {"type": "string", "default": "POST"}, "auth_type": {"enum": ["none","api_key","basic","bearer","hmac"], "type": "string", "default": "none"}, "auth_config": {"type": "object", "description": "Auth config"}}'::jsonb,
                 '["workflow(action=''add_node'', type=''webhook'')"]'::jsonb),
                ('chat', 'Chat trigger.', '{}'::jsonb,
                 '["workflow(action=''add_node'', type=''chat'')"]'::jsonb),
                ('merge', 'Merge.',
                 '{"mergeInputs": {"type": "array", "required": false, "description": "Optional: [{label}] to name the expected inputs. Also accepts: inputs. Usually not needed - merge auto-detects predecessors from edges"}}'::jsonb,
                 '["workflow(action=''add_node'', type=''merge'', label=''Wait All'')", "workflow(action=''add_node'', type=''merge'', label=''Sync'', params={mergeInputs: [{label: ''Email''}, {label: ''SMS''}]})"]'::jsonb),
                ('email_inbox', 'Email inbox.',
                 '{"action": {"type": "string"}}'::jsonb,
                 '["workflow_builder(action=''add_node'', type=''email_inbox'', label=''Archive'', params={action: ''move'', messageUid: ''{{split:item.output.uid}}''})"]'::jsonb);

                INSERT INTO orchestrator.node_type_documentation (type, description, parameters, examples, concepts) VALUES
                ('code', 'Access fields directly: $input.my_step.field - NO .output wrapper needed.',
                 '{"code": {"type": "string"}}'::jsonb,
                 '["workflow_builder(action=''add_node'', type=''code'', label=''Transform'')"]'::jsonb,
                 '["ACCESS PATTERN - $input contains all predecessor outputs", "each predecessor has TWO keys - the alias and the full ID"]'::jsonb);
                """);

        String v391 = Files.readString(Path.of("src/main/resources/db/migration/"
                + "V391__fix_node_doc_param_inconsistencies.sql"));
        Files.writeString(directory.resolve("V2__fix.sql"), v391);
    }

    private static boolean has(PostgreSQLContainer<?> pg, String db, String type, String col, String key)
            throws Exception {
        // jsonb_exists(), not the `?` operator: pgjdbc mis-parses `?` as a bind placeholder.
        // boolean::text renders as 'true'/'false', not 't'/'f'.
        return "true".equals(scalar(pg, db,
                "SELECT jsonb_exists(" + col + ", '" + key + "')::text FROM orchestrator.node_type_documentation WHERE type = '"
                        + type + "'"));
    }

    private static String jsonb(PostgreSQLContainer<?> pg, String db, String type, String expr) throws Exception {
        return scalar(pg, db, "SELECT " + expr + " FROM orchestrator.node_type_documentation WHERE type = '"
                + type + "'");
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
