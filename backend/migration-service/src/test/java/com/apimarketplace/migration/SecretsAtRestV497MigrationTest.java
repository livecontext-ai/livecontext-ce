package com.apimarketplace.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * V497 adds a lookup-hash column beside every capability-token column, widens the token
 * columns so a 132-char ciphertext fits, and drops the dead plaintext {@code auth.users.api_key}.
 * The fixture recreates the tables with the column widths production had on 2026-09-17, so the
 * width change is exercised for real, and holds live rows so nothing is lost across the ALTERs.
 */
@DisplayName("V497 stores capability tokens encrypted + hashed")
class SecretsAtRestV497MigrationTest {

    private static final String DB = "secrets_at_rest_v497";
    private static final String MIGRATION = "V497__secrets_at_rest_token_hashes.sql";

    /** Production shapes (information_schema on 2026-09-17), reduced to the columns the migration touches. */
    private static final String SCHEMA_AND_ROWS = """
            CREATE SCHEMA agent; CREATE SCHEMA publication; CREATE SCHEMA conversation;
            CREATE SCHEMA trigger; CREATE SCHEMA auth; CREATE SCHEMA orchestrator;
            CREATE TABLE agent.agent_webhook_tokens (id BIGSERIAL PRIMARY KEY, token VARCHAR(64) NOT NULL UNIQUE);
            CREATE TABLE agent.agent_widget_configs (id BIGSERIAL PRIMARY KEY, widget_token VARCHAR(40) UNIQUE);
            CREATE TABLE publication.shared_links (id UUID PRIMARY KEY, token VARCHAR(64) NOT NULL UNIQUE, resource_token VARCHAR(64) NOT NULL);
            CREATE TABLE conversation.conversations (id VARCHAR(64) PRIMARY KEY, share_token VARCHAR(64) UNIQUE);
            CREATE TABLE trigger.standalone_webhooks (id UUID PRIMARY KEY, token VARCHAR(64) NOT NULL UNIQUE);
            CREATE TABLE trigger.standalone_chat_endpoints (id UUID PRIMARY KEY, token VARCHAR(64) NOT NULL UNIQUE);
            CREATE TABLE trigger.standalone_form_endpoints (id UUID PRIMARY KEY, token VARCHAR(64) NOT NULL UNIQUE);
            CREATE TABLE trigger.webhook_tokens (id BIGSERIAL PRIMARY KEY, token TEXT NOT NULL UNIQUE);
            CREATE TABLE auth.organization_invitation (id BIGSERIAL PRIMARY KEY, token VARCHAR(255) NOT NULL UNIQUE);
            CREATE TABLE auth.users (id BIGSERIAL PRIMARY KEY, email VARCHAR(255), api_key VARCHAR(255), api_key_hash VARCHAR(64));
            CREATE TABLE orchestrator.approval_channel_deliveries (id BIGSERIAL PRIMARY KEY, callback_token VARCHAR(64) NOT NULL UNIQUE);
            INSERT INTO agent.agent_webhook_tokens (token) VALUES ('ag_live_token');
            INSERT INTO agent.agent_widget_configs (widget_token) VALUES ('wg_live_token');
            INSERT INTO publication.shared_links (id, token, resource_token) VALUES ('11111111-1111-1111-1111-111111111111', 'sl_live', 'ch_live');
            INSERT INTO conversation.conversations (id, share_token) VALUES ('c1', 'cs_live'), ('c2', NULL);
            INSERT INTO trigger.standalone_webhooks (id, token) VALUES ('22222222-2222-2222-2222-222222222222', 'wh_live');
            INSERT INTO trigger.standalone_chat_endpoints (id, token) VALUES ('33333333-3333-3333-3333-333333333333', 'ch_live');
            INSERT INTO trigger.standalone_form_endpoints (id, token) VALUES ('44444444-4444-4444-4444-444444444444', 'fm_live');
            INSERT INTO trigger.webhook_tokens (token) VALUES ('wh_wf_live');
            INSERT INTO auth.organization_invitation (token) VALUES ('inv-live');
            INSERT INTO auth.users (email, api_key_hash) VALUES ('a@b.c', 'hash');
            INSERT INTO orchestrator.approval_channel_deliveries (callback_token) VALUES ('cb_live');
            """;

    private record Col(String table, String column) {}

    private static final List<Col> HASH_COLUMNS = List.of(
            new Col("agent.agent_webhook_tokens", "token_hash"),
            new Col("agent.agent_widget_configs", "widget_token_hash"),
            new Col("publication.shared_links", "token_hash"),
            new Col("publication.shared_links", "resource_token_hash"),
            new Col("conversation.conversations", "share_token_hash"),
            new Col("trigger.standalone_webhooks", "token_hash"),
            new Col("trigger.standalone_chat_endpoints", "token_hash"),
            new Col("trigger.standalone_form_endpoints", "token_hash"),
            new Col("trigger.webhook_tokens", "token_hash"),
            new Col("auth.organization_invitation", "token_hash"),
            new Col("orchestrator.approval_channel_deliveries", "callback_token_hash"));

    private static final List<Col> WIDENED_COLUMNS = List.of(
            new Col("agent.agent_webhook_tokens", "token"),
            new Col("agent.agent_widget_configs", "widget_token"),
            new Col("publication.shared_links", "token"),
            new Col("publication.shared_links", "resource_token"),
            new Col("conversation.conversations", "share_token"),
            new Col("trigger.standalone_webhooks", "token"),
            new Col("trigger.standalone_chat_endpoints", "token"),
            new Col("trigger.standalone_form_endpoints", "token"),
            new Col("orchestrator.approval_channel_deliveries", "callback_token"));

    @Test
    @DisplayName("every token column gains a nullable 64-char hash, is widened to 255, keeps its rows, and users.api_key is gone")
    void addsHashColumnsAndWidensTokens(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);
        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB);
            assertThatCode(() -> postgres.runFlyway(DB, tempDir)).doesNotThrowAnyException();

            for (Col c : HASH_COLUMNS) {
                assertThat(columnType(postgres, c)).as("%s.%s", c.table(), c.column()).isEqualTo("character varying(64) NULL");
            }
            for (Col c : WIDENED_COLUMNS) {
                assertThat(columnType(postgres, c)).as("%s.%s", c.table(), c.column()).startsWith("character varying(255)");
            }
            // A 132-char ciphertext (16-byte IV + 3 AES blocks, hex, ENC: prefix) now fits where 64 did not.
            String ciphertext = "ENC:" + "ab".repeat(64);
            execute(postgres, "UPDATE trigger.standalone_webhooks SET token = '" + ciphertext + "'");
            assertThat(scalar(postgres, "SELECT token FROM trigger.standalone_webhooks")).isEqualTo(ciphertext);
            // Rows survived the ALTERs, hashes start NULL (the application computes them).
            assertThat(scalar(postgres, "SELECT token FROM agent.agent_webhook_tokens")).isEqualTo("ag_live_token");
            assertThat(scalar(postgres, "SELECT count(*)::text FROM conversation.conversations WHERE share_token_hash IS NULL")).isEqualTo("2");
            assertThat(scalar(postgres, "SELECT token FROM auth.organization_invitation")).isEqualTo("inv-live");
            // The plaintext predecessor of api_key_hash is dropped; the hash column stays.
            assertThat(columnExists(postgres, new Col("auth.users", "api_key"))).isFalse();
            assertThat(columnExists(postgres, new Col("auth.users", "api_key_hash"))).isTrue();
            // Every hash column is indexed (the lookups go through it).
            assertThat(scalar(postgres, "SELECT count(*)::text FROM pg_indexes WHERE indexname LIKE '%token_hash'")).isEqualTo("11");
        }
    }

    @Test
    @DisplayName("replaying the statements is harmless (IF NOT EXISTS / IF EXISTS), so a half-applied run can be re-run")
    void replayIsIdempotent(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);
        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_replay");
            postgres.runFlyway(DB + "_replay", tempDir);
            execute(postgres, DB + "_replay", "UPDATE trigger.standalone_webhooks SET token_hash = 'h'");

            assertThatCode(() -> execute(postgres, DB + "_replay",
                    Files.readString(Path.of("src/main/resources/db/migration/" + MIGRATION))))
                    .doesNotThrowAnyException();

            assertThat(scalar(postgres, DB + "_replay", "SELECT token_hash FROM trigger.standalone_webhooks")).isEqualTo("h");
        }
    }

    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__production_shapes.sql"), SCHEMA_AND_ROWS);
        Files.copy(Path.of("src/main/resources/db/migration/" + MIGRATION), directory.resolve(MIGRATION));
    }

    private static String columnType(FlywayTestSupport.PostgresTarget p, Col c) throws Exception {
        String[] parts = c.table().split("\\.");
        List<String> rows = query(p, DB, "SELECT data_type || COALESCE('(' || character_maximum_length || ')', '') || ' ' || is_nullable"
                + " FROM information_schema.columns WHERE table_schema = '" + parts[0] + "' AND table_name = '" + parts[1]
                + "' AND column_name = '" + c.column() + "'");
        return rows.isEmpty() ? "<absent>" : rows.get(0).replace(" YES", " NULL").replace(" NO", " NOT NULL");
    }

    private static boolean columnExists(FlywayTestSupport.PostgresTarget p, Col c) throws Exception {
        return !"<absent>".equals(columnType(p, c));
    }

    private static String scalar(FlywayTestSupport.PostgresTarget p, String sql) throws Exception {
        return scalar(p, DB, sql);
    }

    private static String scalar(FlywayTestSupport.PostgresTarget p, String db, String sql) throws Exception {
        List<String> rows = query(p, db, sql);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static List<String> query(FlywayTestSupport.PostgresTarget p, String db, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(p.jdbcUrl(db), p.username(), p.password());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        }
    }

    private static void execute(FlywayTestSupport.PostgresTarget p, String sql) throws Exception {
        execute(p, DB, sql);
    }

    private static void execute(FlywayTestSupport.PostgresTarget p, String db, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(p.jdbcUrl(db), p.username(), p.password());
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
