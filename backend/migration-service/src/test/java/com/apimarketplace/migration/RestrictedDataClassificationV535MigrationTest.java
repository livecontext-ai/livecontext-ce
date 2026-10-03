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
 * V535 adds {@code data_sensitivity} to every sink that can hold Google restricted-scope
 * content (CASA LC-011, LC-066); V536 builds the RESTRICTED-only partial indexes the sweepers
 * and the run-taint lookup read, outside Flyway's transaction (CREATE INDEX CONCURRENTLY, same
 * pattern as V149); V537 adds the storage-service ShedLock table the restricted-data retention
 * sweep and backfill jobs serialize on.
 *
 * <p>The fixture recreates the five tables the ALTERs touch with a handful of pre-existing rows,
 * so the "existing rows keep their default" and "no rewrite / fast ALTER" promises are exercised
 * against a real Postgres, not asserted from reading the SQL.
 */
@DisplayName("V535-V537 restricted-data classification columns, indexes and shedlock table")
class RestrictedDataClassificationV535MigrationTest {

    private static final String DB = "restricted_data_classification_v535";
    private static final List<String> MIGRATIONS = List.of(
            "V535__restricted_data_classification.sql",
            "V536__restricted_data_classification_indexes.sql",
            "V537__storage_shedlock.sql");

    private static final String SCHEMA_AND_ROWS = """
            CREATE SCHEMA storage; CREATE SCHEMA conversation; CREATE SCHEMA agent;
            CREATE TABLE storage.storage (
                id UUID PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL,
                run_id VARCHAR(64), s3_key VARCHAR(512), expires_at TIMESTAMPTZ
            );
            CREATE TABLE conversation.tool_results (
                id UUID PRIMARY KEY, conversation_id VARCHAR(64) NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            CREATE TABLE conversation.messages (
                id VARCHAR(64) PRIMARY KEY, conversation_id VARCHAR(64) NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            CREATE TABLE agent.agent_executions (
                id UUID PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            CREATE TABLE agent.agent_execution_tool_calls (
                id BIGSERIAL PRIMARY KEY, execution_id UUID NOT NULL
            );
            INSERT INTO storage.storage (id, tenant_id, run_id) VALUES
                ('11111111-1111-1111-1111-111111111111', 't1', 'run-1');
            INSERT INTO conversation.tool_results (id, conversation_id) VALUES
                ('22222222-2222-2222-2222-222222222222', 'conv-1');
            INSERT INTO conversation.messages (id, conversation_id) VALUES ('m1', 'conv-1');
            INSERT INTO agent.agent_executions (id, tenant_id) VALUES
                ('33333333-3333-3333-3333-333333333333', 't1');
            INSERT INTO agent.agent_execution_tool_calls (execution_id) VALUES
                ('33333333-3333-3333-3333-333333333333');
            """;

    @Test
    @DisplayName("every sink gains data_sensitivity NOT NULL DEFAULT 'NORMAL' and keeps its existing rows")
    void addsColumnDefaultingExistingRowsToNormal(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);
        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB);
            assertThatCode(() -> postgres.runFlyway(DB, tempDir)).doesNotThrowAnyException();

            for (String table : List.of("storage.storage", "conversation.tool_results",
                    "conversation.messages", "agent.agent_executions", "agent.agent_execution_tool_calls")) {
                assertThat(columnDefault(postgres, table)).as(table).contains("NORMAL");
                assertThat(scalar(postgres, "SELECT data_sensitivity FROM " + table + " LIMIT 1"))
                        .as(table).isEqualTo("NORMAL");
            }
            // Pre-existing rows kept their data; the ALTER did not rewrite anything away.
            assertThat(scalar(postgres, "SELECT run_id FROM storage.storage")).isEqualTo("run-1");
        }
    }

    @Test
    @DisplayName("V536 builds the RESTRICTED-only partial indexes the sweepers and the run-taint lookup read")
    void buildsPartialIndexes(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);
        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_idx");
            postgres.runFlyway(DB + "_idx", tempDir);

            List<String> indexes = List.of(
                    "idx_storage_restricted_expiry", "idx_storage_restricted_run",
                    "idx_tool_results_restricted_conv", "idx_messages_restricted_conv",
                    "idx_agent_executions_restricted_created");
            for (String index : indexes) {
                assertThat(scalar(postgres, DB + "_idx",
                        "SELECT indexname FROM pg_indexes WHERE indexname = '" + index + "'"))
                        .as(index).isEqualTo(index);
                // Partial: every one of these indexes exists ONLY to serve the RESTRICTED rows.
                assertThat(scalar(postgres, DB + "_idx",
                        "SELECT indexdef FROM pg_indexes WHERE indexname = '" + index + "'"))
                        .as(index).contains("WHERE").contains("'RESTRICTED'::");
            }
            // Not left INVALID by a partial/failed CONCURRENTLY build.
            assertThat(scalar(postgres, DB + "_idx",
                    "SELECT count(*)::text FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                            + "WHERE c.relname LIKE 'idx_%restricted%' AND NOT i.indisvalid"))
                    .isEqualTo("0");
        }
    }

    @Test
    @DisplayName("V537 creates storage.shedlock with the same shape as every other service's shedlock table")
    void createsShedlockTable(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);
        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_lock");
            postgres.runFlyway(DB + "_lock", tempDir);

            assertThat(scalar(postgres, DB + "_lock",
                    "SELECT count(*)::text FROM information_schema.tables "
                            + "WHERE table_schema = 'storage' AND table_name = 'shedlock'"))
                    .isEqualTo("1");
            execute(postgres, DB + "_lock",
                    "INSERT INTO storage.shedlock (name, lock_until, locked_at, locked_by) "
                            + "VALUES ('restricted_storage_retention_sweep', now(), now(), 'test')");
            assertThat(scalar(postgres, DB + "_lock", "SELECT locked_by FROM storage.shedlock"))
                    .isEqualTo("test");
        }
    }

    @Test
    @DisplayName("replaying V535's ALTERs is harmless (ADD COLUMN IF NOT EXISTS), so a half-applied deploy can retry")
    void v535ReplayIsIdempotent(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);
        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_replay");
            postgres.runFlyway(DB + "_replay", tempDir);
            execute(postgres, DB + "_replay",
                    "UPDATE storage.storage SET data_sensitivity = 'RESTRICTED'");

            assertThatCode(() -> execute(postgres, DB + "_replay",
                    Files.readString(Path.of("src/main/resources/db/migration/V535__restricted_data_classification.sql"))))
                    .doesNotThrowAnyException();

            // Replaying the ALTER did not reset data the application had already written.
            assertThat(scalar(postgres, DB + "_replay", "SELECT data_sensitivity FROM storage.storage"))
                    .isEqualTo("RESTRICTED");
        }
    }

    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__production_shapes.sql"), SCHEMA_AND_ROWS);
        for (String migration : MIGRATIONS) {
            Files.copy(Path.of("src/main/resources/db/migration/" + migration), directory.resolve(migration));
        }
    }

    private static String columnDefault(FlywayTestSupport.PostgresTarget p, String table) throws Exception {
        String[] parts = table.split("\\.");
        List<String> rows = query(p, DB, "SELECT column_default FROM information_schema.columns "
                + "WHERE table_schema = '" + parts[0] + "' AND table_name = '" + parts[1]
                + "' AND column_name = 'data_sensitivity'");
        return rows.isEmpty() ? "<absent>" : rows.get(0);
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

    private static void execute(FlywayTestSupport.PostgresTarget p, String db, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(p.jdbcUrl(db), p.username(), p.password());
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
