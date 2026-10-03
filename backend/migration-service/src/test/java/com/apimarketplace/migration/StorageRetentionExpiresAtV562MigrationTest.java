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
 * V562 adds {@code storage.storage.retention_expires_at}, the restricted-data retention deadline
 * (CASA LC-011), in a column of its own so that a previous release, which enforces
 * {@code expires_at} by itself, can never act on it during a rollout or after a rollback. It also
 * builds the two RESTRICTED-only partial indexes the sweep and the backfill catch-up read, outside
 * Flyway's transaction (CREATE INDEX CONCURRENTLY, same pattern as V536).
 *
 * <p>Replayed by Flyway against a real Postgres with pre-existing rows, so "nullable, no default,
 * existing rows and their expires_at untouched, a previous release's INSERT still works" is
 * exercised, not read off the SQL.
 */
@DisplayName("V562 storage.storage.retention_expires_at and its partial indexes")
class StorageRetentionExpiresAtV562MigrationTest {

    private static final String DB = "storage_retention_expires_at_v562";
    private static final String MIGRATION = "V562__storage_retention_expires_at.sql";

    private static final String SCHEMA_AND_ROWS = """
            CREATE SCHEMA storage;
            CREATE TABLE storage.storage (
                id UUID PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL,
                data_sensitivity VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
                expires_at TIMESTAMPTZ,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            INSERT INTO storage.storage (id, tenant_id, data_sensitivity, expires_at) VALUES
                ('11111111-1111-1111-1111-111111111111', 't1', 'RESTRICTED', NULL),
                ('22222222-2222-2222-2222-222222222222', 't1', 'RESTRICTED', '2030-01-01T00:00:00Z');
            """;

    @Test
    @DisplayName("adds a nullable column with no default; existing rows read NULL and keep their expires_at")
    void addsNullableColumnLeavingExpiresAtAlone(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);
        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB);
            assertThatCode(() -> postgres.runFlyway(DB, tempDir)).doesNotThrowAnyException();

            assertThat(scalar(postgres, DB, "SELECT is_nullable || '/' || COALESCE(column_default, '<none>') "
                    + "FROM information_schema.columns WHERE table_schema = 'storage' "
                    + "AND table_name = 'storage' AND column_name = 'retention_expires_at'"))
                    .isEqualTo("YES/<none>");
            assertThat(scalar(postgres, DB, "SELECT count(*)::text FROM storage.storage "
                    + "WHERE retention_expires_at IS NULL")).isEqualTo("2");
            // expires_at, which every earlier release enforces, is not touched by the migration.
            assertThat(scalar(postgres, DB, "SELECT count(*)::text FROM storage.storage WHERE expires_at IS NULL"))
                    .isEqualTo("1");
            assertThat(scalar(postgres, DB, "SELECT to_char(expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') "
                    + "FROM storage.storage WHERE id = '22222222-2222-2222-2222-222222222222'"))
                    .isEqualTo("2030-01-01");

            // A pod of the previous release INSERTs without the column: still accepted.
            execute(postgres, DB, "INSERT INTO storage.storage (id, tenant_id, data_sensitivity) "
                    + "VALUES ('33333333-3333-3333-3333-333333333333', 't1', 'RESTRICTED')");
            assertThat(scalar(postgres, DB, "SELECT retention_expires_at::text FROM storage.storage "
                    + "WHERE id = '33333333-3333-3333-3333-333333333333'")).isNull();
        }
    }

    @Test
    @DisplayName("builds the RESTRICTED-only partial indexes for the sweep deadline and the catch-up, both valid")
    void buildsValidPartialIndexes(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);
        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_idx");
            postgres.runFlyway(DB + "_idx", tempDir);

            String deadline = scalar(postgres, DB + "_idx",
                    "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_storage_restricted_deadline'");
            assertThat(deadline).contains("LEAST(expires_at, retention_expires_at)").contains("'RESTRICTED'::");
            String unset = scalar(postgres, DB + "_idx",
                    "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_storage_restricted_retention_unset'");
            assertThat(unset).contains("'RESTRICTED'::").contains("retention_expires_at IS NULL");
            // Not left INVALID by a partial/failed CONCURRENTLY build.
            assertThat(scalar(postgres, DB + "_idx",
                    "SELECT count(*)::text FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                            + "WHERE c.relname IN ('idx_storage_restricted_deadline', "
                            + "'idx_storage_restricted_retention_unset') AND i.indisvalid"))
                    .isEqualTo("2");
        }
    }

    @Test
    @DisplayName("replaying V562 is harmless (IF NOT EXISTS) and keeps deadlines already written")
    void replayIsIdempotent(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);
        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_replay");
            postgres.runFlyway(DB + "_replay", tempDir);
            execute(postgres, DB + "_replay", "UPDATE storage.storage SET retention_expires_at = '2026-11-01T00:00:00Z'");

            // One statement at a time, in autocommit, as Flyway runs a non-transactional migration:
            // CREATE INDEX CONCURRENTLY refuses an implicit multi-statement transaction block.
            String withoutComments = String.join("\n", Files.readAllLines(
                    Path.of("src/main/resources/db/migration/" + MIGRATION)).stream()
                    .filter(line -> !line.trim().startsWith("--")).toList());
            for (String statement : withoutComments.split(";")) {
                if (!statement.isBlank()) {
                    assertThatCode(() -> execute(postgres, DB + "_replay", statement))
                            .as(statement.trim()).doesNotThrowAnyException();
                }
            }

            assertThat(scalar(postgres, DB + "_replay",
                    "SELECT count(*)::text FROM storage.storage WHERE retention_expires_at IS NOT NULL"))
                    .isEqualTo("2");
        }
    }

    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__production_shapes.sql"), SCHEMA_AND_ROWS);
        Files.copy(Path.of("src/main/resources/db/migration/" + MIGRATION), directory.resolve(MIGRATION));
    }

    private static String scalar(FlywayTestSupport.PostgresTarget p, String db, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(p.jdbcUrl(db), p.username(), p.password());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out.isEmpty() ? null : out.get(0);
        }
    }

    private static void execute(FlywayTestSupport.PostgresTarget p, String db, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(p.jdbcUrl(db), p.username(), p.password());
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
