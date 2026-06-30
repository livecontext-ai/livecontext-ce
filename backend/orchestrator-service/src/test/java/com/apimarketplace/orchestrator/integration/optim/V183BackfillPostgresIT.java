package com.apimarketplace.orchestrator.integration.optim;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for V183 jsonb-cast bug fix (commit {@code 84db3884f}).
 *
 * <p>Pre-fix V183 SQL used {@code state_snapshot ? 'seq'} which fails with
 * <em>operator does not exist: text ? unknown</em> because {@code state_snapshot}
 * is declared TEXT (see V1__create_orchestrator_schema.sql:66), not JSONB.
 * The fix wraps the column in {@code CAST(state_snapshot AS jsonb)} before
 * any JSONB operator.
 *
 * <p>This test loads V183's actual SQL against real Postgres + a TEXT
 * column containing embedded {@code seq} JSON, and asserts:
 * <ol>
 *   <li>Pre-fix SQL ({@code state_snapshot ? 'seq'}) FAILS as expected (operator error)</li>
 *   <li>Post-fix SQL ({@code jsonb_exists(CAST(state_snapshot AS jsonb), 'seq')}) succeeds</li>
 *   <li>Post-fix backfill correctly lifts {@code state_snapshot.seq} JSONB value into the
 *       {@code state_snapshot_seq} BIGINT column</li>
 *   <li>Backfill is selective - rows already non-zero are NOT touched</li>
 *   <li>Backfill is idempotent - running it twice = same result</li>
 * </ol>
 *
 * <p>Skipped without Docker (Testcontainers).
 */
@Testcontainers
@DisplayName("V183 jsonb-cast regression test (commit 84db3884f)")
class V183BackfillPostgresIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    static void setUpClass() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available - V183BackfillPostgresIT skipped");

        DataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new NamedParameterJdbcTemplate(ds);

        // Replicate the relevant slice of V1 + V178 + V181 - workflow_runs with
        // state_snapshot TEXT and state_snapshot_seq BIGINT.
        jdbc.getJdbcTemplate().execute("CREATE SCHEMA IF NOT EXISTS orchestrator");
        jdbc.getJdbcTemplate().execute(
                "CREATE TABLE IF NOT EXISTS orchestrator.workflow_runs ("
                        + "  id UUID PRIMARY KEY,"
                        + "  run_id_public VARCHAR(64) UNIQUE NOT NULL,"
                        + "  state_snapshot TEXT,"  // Critical: TEXT not JSONB - the bug's root cause
                        + "  state_snapshot_seq BIGINT NOT NULL DEFAULT 0"
                        + ")");
    }

    @Test
    @DisplayName("Pre-fix V183 SQL - state_snapshot ? 'seq' on TEXT column → SQL error")
    void preFixSqlFails() {
        jdbc.getJdbcTemplate().execute("DELETE FROM orchestrator.workflow_runs");
        jdbc.update("INSERT INTO orchestrator.workflow_runs (id, run_id_public, state_snapshot, state_snapshot_seq) "
                        + "VALUES (:id, :rid, '{\"seq\": 5}', 0)",
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("rid", "run-1"));

        // The pre-fix SQL: directly use jsonb_exists on TEXT column - should throw
        // (jsonb_exists requires jsonb first arg; TEXT will fail "function does not exist" or "operator")
        // We use jsonb_exists (function form) here because the `?` operator triggers JDBC
        // parameter-placeholder substitution. Both forms call the same internal jsonb_exists.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.update("UPDATE orchestrator.workflow_runs SET state_snapshot_seq = 99 "
                                + "WHERE state_snapshot_seq = 0 AND jsonb_exists(state_snapshot, 'seq')",
                        new MapSqlParameterSource())
        ).isInstanceOf(org.springframework.jdbc.BadSqlGrammarException.class)
         .hasMessageContaining("UPDATE orchestrator.workflow_runs");
    }

    @Test
    @DisplayName("Post-fix V183 SQL - jsonb_exists(CAST(state_snapshot AS jsonb), 'seq') on TEXT column → works")
    void postFixSqlWorks() {
        jdbc.getJdbcTemplate().execute("DELETE FROM orchestrator.workflow_runs");
        // Row A: seq=5 in JSONB, column=0 → should be backfilled
        jdbc.update("INSERT INTO orchestrator.workflow_runs (id, run_id_public, state_snapshot, state_snapshot_seq) "
                        + "VALUES (:id, :rid, '{\"seq\": 5}', 0)",
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("rid", "run-A"));
        // Row B: no seq in JSONB, column=0 → untouched
        jdbc.update("INSERT INTO orchestrator.workflow_runs (id, run_id_public, state_snapshot, state_snapshot_seq) "
                        + "VALUES (:id, :rid, '{\"other\": 1}', 0)",
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("rid", "run-B"));
        // Row C: seq=7 in JSONB, column=3 → untouched (already non-zero)
        jdbc.update("INSERT INTO orchestrator.workflow_runs (id, run_id_public, state_snapshot, state_snapshot_seq) "
                        + "VALUES (:id, :rid, '{\"seq\": 7}', 3)",
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("rid", "run-C"));
        // Row D: seq is non-numeric string → untouched (regex guard)
        jdbc.update("INSERT INTO orchestrator.workflow_runs (id, run_id_public, state_snapshot, state_snapshot_seq) "
                        + "VALUES (:id, :rid, '{\"seq\": \"abc\"}', 0)",
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("rid", "run-D"));

        // V183 fixed SQL - matches the migration file verbatim
        int updated = jdbc.update(
                "UPDATE orchestrator.workflow_runs "
                        + "SET state_snapshot_seq = COALESCE((CAST(state_snapshot AS jsonb)->>'seq')::bigint, 0) "
                        + "WHERE state_snapshot_seq = 0 "
                        + "  AND state_snapshot IS NOT NULL "
                        + "  AND state_snapshot <> '' "
                        + "  AND jsonb_exists(CAST(state_snapshot AS jsonb), 'seq') "
                        + "  AND (CAST(state_snapshot AS jsonb)->>'seq') ~ '^[0-9]+$'",
                new MapSqlParameterSource());

        assertThat(updated).as("only run-A has seq=int in JSONB + column=0").isEqualTo(1);

        Long seqA = jdbc.queryForObject(
                "SELECT state_snapshot_seq FROM orchestrator.workflow_runs WHERE run_id_public = 'run-A'",
                new MapSqlParameterSource(), Long.class);
        Long seqB = jdbc.queryForObject(
                "SELECT state_snapshot_seq FROM orchestrator.workflow_runs WHERE run_id_public = 'run-B'",
                new MapSqlParameterSource(), Long.class);
        Long seqC = jdbc.queryForObject(
                "SELECT state_snapshot_seq FROM orchestrator.workflow_runs WHERE run_id_public = 'run-C'",
                new MapSqlParameterSource(), Long.class);
        Long seqD = jdbc.queryForObject(
                "SELECT state_snapshot_seq FROM orchestrator.workflow_runs WHERE run_id_public = 'run-D'",
                new MapSqlParameterSource(), Long.class);

        assertThat(seqA).as("Row A: seq=5 JSONB lifted into column").isEqualTo(5L);
        assertThat(seqB).as("Row B: no seq in JSONB → untouched").isEqualTo(0L);
        assertThat(seqC).as("Row C: column already non-zero → preserved (selective WHERE)").isEqualTo(3L);
        assertThat(seqD).as("Row D: non-numeric seq → regex guard skipped").isEqualTo(0L);
    }

    @Test
    @DisplayName("V183 idempotent - re-running the backfill yields the same column values")
    void idempotent() {
        jdbc.getJdbcTemplate().execute("DELETE FROM orchestrator.workflow_runs");
        jdbc.update("INSERT INTO orchestrator.workflow_runs (id, run_id_public, state_snapshot, state_snapshot_seq) "
                        + "VALUES (:id, :rid, '{\"seq\": 42}', 0)",
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("rid", "run-1"));

        String backfillSql = "UPDATE orchestrator.workflow_runs "
                + "SET state_snapshot_seq = COALESCE((CAST(state_snapshot AS jsonb)->>'seq')::bigint, 0) "
                + "WHERE state_snapshot_seq = 0 "
                + "  AND state_snapshot IS NOT NULL "
                + "  AND state_snapshot <> '' "
                + "  AND jsonb_exists(CAST(state_snapshot AS jsonb), 'seq') "
                + "  AND (CAST(state_snapshot AS jsonb)->>'seq') ~ '^[0-9]+$'";

        int first = jdbc.update(backfillSql, new MapSqlParameterSource());
        int second = jdbc.update(backfillSql, new MapSqlParameterSource());

        assertThat(first).isEqualTo(1);
        assertThat(second).as("idempotent - second run finds zero rows to update").isZero();

        Long seq = jdbc.queryForObject(
                "SELECT state_snapshot_seq FROM orchestrator.workflow_runs WHERE run_id_public = 'run-1'",
                new MapSqlParameterSource(), Long.class);
        assertThat(seq).isEqualTo(42L);
    }
}
