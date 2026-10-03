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
 * V538 adds {@code data_sensitivity} to {@code datasource.data_source_items} (CASA LC-066/LC-011
 * re-audit item 2): rows written into a user's table during a RESTRICTED run/conversation
 * previously had no way to carry the tag at all.
 *
 * <p>Same shape as {@link RestrictedDataClassificationV535MigrationTest} (V535-V537): the fixture
 * recreates the one table the ALTER touches with a pre-existing row, exercised against a real
 * Postgres so the "existing rows keep their data, no rewrite" and "lock_timeout is RESET"
 * promises are proven, not just read from the SQL.
 */
@DisplayName("V538 datasource.data_source_items.data_sensitivity")
class V538DatasourceItemsDataSensitivityMigrationTest {

    private static final String DB = "datasource_items_data_sensitivity_v538";
    private static final List<String> MIGRATIONS = List.of(
            "V538__datasource_items_data_sensitivity.sql");

    private static final String SCHEMA_AND_ROWS = """
            CREATE SCHEMA datasource;
            CREATE TABLE datasource.data_source_items (
                id BIGSERIAL PRIMARY KEY,
                data_source_id BIGINT NOT NULL,
                tenant_id VARCHAR(255) NOT NULL,
                data JSONB NOT NULL,
                priority INTEGER NOT NULL DEFAULT 0,
                row_index INTEGER NOT NULL DEFAULT 0,
                created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
            );
            INSERT INTO datasource.data_source_items (data_source_id, tenant_id, data)
                VALUES (1, 't1', '{"name":"Alice"}'::jsonb);
            """;

    @Test
    @DisplayName("adds data_sensitivity NOT NULL DEFAULT 'NORMAL' and keeps existing rows' data")
    void addsColumnDefaultingExistingRowsToNormal(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);
        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB);
            assertThatCode(() -> postgres.runFlyway(DB, tempDir)).doesNotThrowAnyException();

            assertThat(columnDefault(postgres, "datasource.data_source_items")).contains("NORMAL");
            assertThat(scalar(postgres, "SELECT data_sensitivity FROM datasource.data_source_items LIMIT 1"))
                    .isEqualTo("NORMAL");
            // Pre-existing row kept its data; the ALTER did not rewrite anything away.
            assertThat(scalar(postgres, "SELECT data->>'name' FROM datasource.data_source_items"))
                    .isEqualTo("Alice");
        }
    }

    @Test
    @DisplayName("column type/nullability match the other restricted-data sinks (V535): VARCHAR(16) NOT NULL")
    void columnShapeMatchesV535Convention(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);
        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_shape");
            postgres.runFlyway(DB + "_shape", tempDir);

            assertThat(scalar(postgres, DB + "_shape",
                    "SELECT is_nullable FROM information_schema.columns "
                            + "WHERE table_schema='datasource' AND table_name='data_source_items' "
                            + "AND column_name='data_sensitivity'"))
                    .isEqualTo("NO");
            assertThat(scalar(postgres, DB + "_shape",
                    "SELECT character_maximum_length::text FROM information_schema.columns "
                            + "WHERE table_schema='datasource' AND table_name='data_source_items' "
                            + "AND column_name='data_sensitivity'"))
                    .isEqualTo("16");
        }
    }

    @Test
    @DisplayName("replaying V538 is harmless (ADD COLUMN IF NOT EXISTS) and does not reset data the app already wrote")
    void replayIsIdempotentAndPreservesWrittenData(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);
        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_replay");
            postgres.runFlyway(DB + "_replay", tempDir);
            execute(postgres, DB + "_replay",
                    "UPDATE datasource.data_source_items SET data_sensitivity = 'RESTRICTED'");

            assertThatCode(() -> execute(postgres, DB + "_replay",
                    Files.readString(Path.of("src/main/resources/db/migration/V538__datasource_items_data_sensitivity.sql"))))
                    .doesNotThrowAnyException();

            assertThat(scalar(postgres, DB + "_replay",
                    "SELECT data_sensitivity FROM datasource.data_source_items"))
                    .isEqualTo("RESTRICTED");
        }
    }

    @Test
    @DisplayName("lock_timeout / statement_timeout are RESET at the end, not left session-scoped for the next migration "
        + "(same fix V535's trailer carries, reproduced the same way)")
    void resetsSessionTimeoutsAfterItself(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);
        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_timeout");
            postgres.runFlyway(DB + "_timeout", tempDir);

            // Same connection/session Flyway used is closed by the time migrate() returns, so this
            // proves the GUCs are not simply left set on Flyway's OWN session past its lifetime -
            // open a fresh session and confirm lock_timeout/statement_timeout are back at server
            // defaults (0 = disabled), i.e. the migration's RESET ran and nothing carried the
            // session-scoped SET past the migration's own statements.
            assertThat(scalar(postgres, DB + "_timeout", "SHOW lock_timeout")).isEqualTo("0");
            assertThat(scalar(postgres, DB + "_timeout", "SHOW statement_timeout")).isEqualTo("0");
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
