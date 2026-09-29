package com.apimarketplace.orchestrator.integration.storage;

import com.apimarketplace.common.storage.repository.StorageRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executes the EXACT native SQL of {@link StorageRepository#existsStepOutputFileRef} (read from its
 * {@link Query} annotation) against a real Postgres.
 *
 * <p>Why a real database: the first version of this query shipped with its string literals stripped
 * ({@code s.status = ACTIVE}, {@code COALESCE(s.data_text, )}). Every mock-based test stayed green
 * while the statement did not even parse, so the share viewer's media check silently failed closed.
 * Only running the statement catches that class of bug.
 *
 * <p>Also pins the hardening: a share viewer can put text into a shared run (trigger payload, form
 * submission, signal), so only a real FileRef in a non-trigger, non-interface node OUTPUT counts.
 *
 * <p><b>How it runs.</b> Plain JDBC against the scratch database named by
 * {@code ORCHESTRATOR_TEST_PG_URL}, like {@code AggregatedStepsQueryPostgresTest}: the CI runners
 * expose no Docker socket, so a Testcontainers class would SKIP there. With {@code CI} set and no URL
 * the class FAILS rather than skipping. Locally, point the variable at any throwaway Postgres whose
 * database name contains "test" (the class drops and recreates {@code storage.storage}).
 */
@DisplayName("StorageRepository.existsStepOutputFileRef - real Postgres, the shipped SQL")
class StorageRepositoryShareFileRefPostgresTest {

    private static final String URL = System.getenv("ORCHESTRATOR_TEST_PG_URL");
    private static final String USER = System.getenv().getOrDefault("ORCHESTRATOR_TEST_PG_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("ORCHESTRATOR_TEST_PG_PASSWORD", "postgres");

    private static final String APP_RUN = "run_app_1";
    private static final String OTHER_RUN = "run_other";

    static NamedParameterJdbcTemplate jdbc;
    static String sql;

    @BeforeAll
    static void setUpClass() throws Exception {
        requireDatabaseOnCi();
        String database = URL.substring(URL.lastIndexOf('/') + 1).split("\\?")[0];
        if (!database.toLowerCase(Locale.ROOT).contains("test")) {
            throw new IllegalStateException("ORCHESTRATOR_TEST_PG_URL must point at a scratch database whose "
                    + "name contains 'test' (this class drops storage.storage), got: " + database);
        }
        awaitDatabase();
        DriverManagerDataSource ds = new DriverManagerDataSource(URL, USER, PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new NamedParameterJdbcTemplate(ds);
        jdbc.getJdbcTemplate().execute("CREATE SCHEMA IF NOT EXISTS storage");
        jdbc.getJdbcTemplate().execute("DROP TABLE IF EXISTS storage.storage");
        // Only the columns the query reads.
        jdbc.getJdbcTemplate().execute(
                "CREATE TABLE storage.storage ("
                        + "  id UUID PRIMARY KEY,"
                        + "  run_id VARCHAR(255),"
                        + "  step_key VARCHAR(255),"
                        + "  status VARCHAR(50),"
                        + "  source_type VARCHAR(50),"
                        + "  data JSONB,"
                        + "  data_text TEXT"
                        + ")");

        Query annotation = StorageRepository.class
                .getMethod("existsStepOutputFileRef", Collection.class, String.class, String.class)
                .getAnnotation(Query.class);
        if (annotation == null || !annotation.nativeQuery()) {
            throw new IllegalStateException("existsStepOutputFileRef no longer carries a native @Query; "
                    + "move this test with the SQL, it is the only place it runs against a real engine.");
        }
        sql = annotation.value();
    }

    private static void requireDatabaseOnCi() {
        if (URL != null && !URL.isBlank()) {
            return;
        }
        if (System.getenv("CI") != null && !System.getenv("CI").isBlank()) {
            throw new IllegalStateException("ORCHESTRATOR_TEST_PG_URL is unset on CI. This class must execute "
                    + "there: it is the only test that runs the share-link file SQL against a real engine. "
                    + "Restore the env block on the workflow step that runs it.");
        }
        Assumptions.abort("no scratch Postgres: set ORCHESTRATOR_TEST_PG_URL to run this locally (CI always sets it)");
    }

    private static void awaitDatabase() throws InterruptedException {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try (Connection ignored = DriverManager.getConnection(URL, USER, PASSWORD)) {
                return;
            } catch (Exception e) {
                last = new IllegalStateException("ORCHESTRATOR_TEST_PG_URL is set but unreachable: " + URL, e);
                Thread.sleep(1_000);
            }
        }
        throw last;
    }

    @BeforeEach
    void clean() {
        jdbc.getJdbcTemplate().execute("TRUNCATE storage.storage");
    }


    private static String fileRef(UUID id) {
        return "{\"_type\":\"file\",\"id\":\"" + id + "\",\"path\":\"t/x.png\",\"name\":\"x.png\","
                + "\"mimeType\":\"image/png\",\"size\":42}";
    }

    private static void row(String runId, String stepKey, String status, String sourceType, String json) {
        jdbc.update("INSERT INTO storage.storage (id, run_id, step_key, status, source_type, data) "
                        + "VALUES (:id, :run, :step, :status, :src, CAST(:data AS jsonb))",
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("run", runId)
                        .addValue("step", stepKey)
                        .addValue("status", status)
                        .addValue("src", sourceType)
                        .addValue("data", json));
    }

    private static boolean referenced(UUID fileId) {
        Boolean result = jdbc.queryForObject(sql, new MapSqlParameterSource()
                .addValue("runIds", List.of(APP_RUN, "run_app_2"))
                .addValue("fileId", fileId.toString())
                .addValue("fileRefPath", StorageRepository.FILE_REF_BY_ID_JSONPATH), Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    @Test
    @DisplayName("allowed: a catalog tool's FileRef (nested in an array) in an application run's node output")
    void catalogFileRefInStepOutputIsReferenced() {
        UUID file = UUID.randomUUID();
        row(APP_RUN, "mcp:generate_image", "ACTIVE", "STEP_OUTPUT",
                "{\"output\":{\"images\":[" + fileRef(file) + "]}}");

        assertThat(referenced(file)).isTrue();
    }

    @Test
    @DisplayName("refused: a foreign file id no application output references")
    void foreignIdIsRefused() {
        row(APP_RUN, "mcp:generate_image", "ACTIVE", "STEP_OUTPUT",
                "{\"output\":" + fileRef(UUID.randomUUID()) + "}");

        assertThat(referenced(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("refused: the FileRef only appears in the trigger output (visitor-supplied payload)")
    void triggerOutputDoesNotCount() {
        UUID file = UUID.randomUUID();
        row(APP_RUN, "trigger:form", "ACTIVE", "STEP_OUTPUT", "{\"output\":{\"doc\":" + fileRef(file) + "}}");

        assertThat(referenced(file)).isFalse();
    }

    @Test
    @DisplayName("refused: the FileRef only appears in an interface node output (form inputs)")
    void interfaceOutputDoesNotCount() {
        UUID file = UUID.randomUUID();
        row(APP_RUN, "interface:page", "ACTIVE", "STEP_OUTPUT", "{\"output\":{\"doc\":" + fileRef(file) + "}}");

        assertThat(referenced(file)).isFalse();
    }

    @Test
    @DisplayName("refused: the FileRef only appears in SIGNAL / INTERFACE_ACTION rows (what a visitor submitted)")
    void signalAndInterfaceActionRowsDoNotCount() {
        UUID file = UUID.randomUUID();
        row(APP_RUN, "core:gate", "ACTIVE", "SIGNAL", "{\"approved\":true,\"attachment\":" + fileRef(file) + "}");
        row(APP_RUN, "core:page", "ACTIVE", "INTERFACE_ACTION", "{\"output\":{\"upload\":" + fileRef(file) + "}}");

        assertThat(referenced(file)).isFalse();
    }

    @Test
    @DisplayName("refused: a bare id mentioned in a node output without the FileRef shape")
    void bareIdWithoutFileRefShapeDoesNotCount() {
        UUID file = UUID.randomUUID();
        row(APP_RUN, "core:transform", "ACTIVE", "STEP_OUTPUT",
                "{\"output\":{\"note\":\"see " + file + "\",\"ref\":{\"id\":\"" + file + "\"}}}");

        assertThat(referenced(file)).isFalse();
    }

    @Test
    @DisplayName("refused: the FileRef belongs to another run, or to a deleted row")
    void otherRunOrDeletedRowDoesNotCount() {
        UUID file = UUID.randomUUID();
        row(OTHER_RUN, "mcp:generate_image", "ACTIVE", "STEP_OUTPUT", "{\"output\":" + fileRef(file) + "}");
        row(APP_RUN, "mcp:generate_image", "DELETED", "STEP_OUTPUT", "{\"output\":" + fileRef(file) + "}");

        assertThat(referenced(file)).isFalse();
    }
}
