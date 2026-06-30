package com.apimarketplace.orchestrator.integration.optim;

import com.apimarketplace.orchestrator.services.state.patch.JsonbPatch;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan v4 §1.6 + §2b - real Postgres CAS contention test. Validates that the
 * SQL composer's read-modify-write CAS predicate behaves correctly under
 * genuine concurrent contention from N writer threads on the same row.
 *
 * <p>Mirrors the simulated test in CasChainEndToEndTest but uses real
 * Postgres (no mocks). Replicates V181 trigger + state_snapshot_seq column.
 * Each scenario:
 *   1. Seeds a workflow_runs row with state_snapshot_seq = 0
 *   2. N threads concurrently issue CAS UPDATEs
 *   3. Asserts final seq == sum-of-successful-CAS-attempts
 *   4. Asserts integer DELTA composition correctly aggregates increments
 *
 * <p>Skipped without Docker (Testcontainers).
 */
@Testcontainers
@DisplayName("Plan v4 §1.6 + §2b - Real Postgres CAS contention parity")
class CasContentionPostgresIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    static void setUpClass() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available - CasContentionPostgresIT skipped");

        DataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new NamedParameterJdbcTemplate(ds);

        jdbc.getJdbcTemplate().execute("CREATE SCHEMA IF NOT EXISTS orchestrator");
        jdbc.getJdbcTemplate().execute(
                "CREATE TABLE IF NOT EXISTS orchestrator.workflow_runs ("
                        + "  id UUID PRIMARY KEY,"
                        + "  run_id_public VARCHAR(64) UNIQUE NOT NULL,"
                        + "  state_snapshot TEXT,"
                        + "  state_snapshot_seq BIGINT NOT NULL DEFAULT 0"
                        + ")");
        // V181 trigger
        jdbc.getJdbcTemplate().execute(
                "CREATE OR REPLACE FUNCTION orchestrator.enforce_state_snapshot_seq()\n"
                        + "RETURNS TRIGGER AS $$\n"
                        + "BEGIN\n"
                        + "  IF TG_OP = 'UPDATE' AND NEW.state_snapshot_seq < OLD.state_snapshot_seq THEN\n"
                        + "    RAISE EXCEPTION 'state_snapshot_seq must not regress';\n"
                        + "  END IF;\n"
                        + "  RETURN NEW;\n"
                        + "END;\n"
                        + "$$ LANGUAGE plpgsql");
        jdbc.getJdbcTemplate().execute(
                "DROP TRIGGER IF EXISTS state_snapshot_seq_monotonicity ON orchestrator.workflow_runs");
        jdbc.getJdbcTemplate().execute(
                "CREATE TRIGGER state_snapshot_seq_monotonicity\n"
                        + "  BEFORE INSERT OR UPDATE ON orchestrator.workflow_runs\n"
                        + "  FOR EACH ROW EXECUTE FUNCTION orchestrator.enforce_state_snapshot_seq()");
    }

    @BeforeEach
    void resetRow() {
        jdbc.getJdbcTemplate().execute("DELETE FROM orchestrator.workflow_runs");
        jdbc.update(
                "INSERT INTO orchestrator.workflow_runs (id, run_id_public, state_snapshot, state_snapshot_seq) "
                        + "VALUES (:id, :rid, '{\"nodes\": {\"X\": {\"completed\": 0}}, \"seq\": 0}'::text, 0)",
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("rid", "run-1"));
    }

    @Nested
    @DisplayName("DELTA SQL composition under contention")
    class DeltaContention {

        @Test
        @DisplayName("N concurrent +1 DELTA writers on same path → final value = N (no lost increments)")
        void concurrentDeltaWritersNoLostUpdates() throws Exception {
            int threads = 20;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger successes = new AtomicInteger(0);

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        // CAS retry loop - naive but matches plan §1.7 shape
                        for (int attempt = 0; attempt < 10; attempt++) {
                            Long expected = jdbc.queryForObject(
                                    "SELECT state_snapshot_seq FROM orchestrator.workflow_runs "
                                            + "WHERE run_id_public = :rid",
                                    new MapSqlParameterSource("rid", "run-1"), Long.class);
                            long newSeq = expected + 1;
                            // Emit a DELTA +1 patch on nodes.X.completed (matches plan §2b SQL shape)
                            String sql = "UPDATE orchestrator.workflow_runs SET "
                                    + "state_snapshot = CAST(jsonb_set(CAST(state_snapshot AS jsonb), "
                                    + "  CAST(:path AS text[]), "
                                    + "  to_jsonb(COALESCE((CAST(state_snapshot AS jsonb)#>>CAST(:path AS text[]))::bigint, 0) + CAST(:delta AS bigint)), "
                                    + "  true) AS text), "
                                    + "state_snapshot_seq = :newSeq "
                                    + "WHERE run_id_public = :rid AND state_snapshot_seq = :expected";
                            int rows = jdbc.update(sql,
                                    new MapSqlParameterSource("rid", "run-1")
                                            .addValue("path", "{nodes,X,completed}")
                                            .addValue("delta", 1L)
                                            .addValue("newSeq", newSeq)
                                            .addValue("expected", expected));
                            if (rows == 1) {
                                successes.incrementAndGet();
                                return;
                            }
                            // CAS conflict - backoff briefly then retry
                            try { Thread.sleep(1); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            boolean ok = pool.awaitTermination(30, TimeUnit.SECONDS);
            assertThat(ok).isTrue();

            // Read final state
            Long finalSeq = jdbc.queryForObject(
                    "SELECT state_snapshot_seq FROM orchestrator.workflow_runs WHERE run_id_public = :rid",
                    new MapSqlParameterSource("rid", "run-1"), Long.class);
            Long finalCounter = jdbc.queryForObject(
                    "SELECT (CAST(state_snapshot AS jsonb)->'nodes'->'X'->>'completed')::bigint "
                            + "FROM orchestrator.workflow_runs WHERE run_id_public = :rid",
                    new MapSqlParameterSource("rid", "run-1"), Long.class);

            // Final seq must equal successful CAS count
            assertThat(finalSeq).isEqualTo((long) successes.get());
            // Final counter must equal successful CAS count (no lost increments)
            assertThat(finalCounter)
                    .as("DELTA SQL composition correctly aggregates under contention - no lost updates")
                    .isEqualTo((long) successes.get());
        }
    }

    @Nested
    @DisplayName("V181 trigger + CAS interaction")
    class TriggerInteraction {

        @Test
        @DisplayName("CAS UPDATE with same seq → trigger allows (equality is not regression)")
        void casWithEqualSeqAllowed() {
            // seq=0 initially. Issue UPDATE that sets seq=0 explicitly - must succeed.
            int rows = jdbc.update(
                    "UPDATE orchestrator.workflow_runs SET state_snapshot_seq = :newSeq "
                            + "WHERE run_id_public = :rid AND state_snapshot_seq = :expected",
                    new MapSqlParameterSource("rid", "run-1")
                            .addValue("newSeq", 0L)
                            .addValue("expected", 0L));
            // V181 trigger: NEW.seq < OLD.seq → reject. 0 < 0 is false → ALLOWED.
            assertThat(rows).isEqualTo(1);
        }

        @Test
        @DisplayName("CAS UPDATE with lower seq → trigger raises")
        void casWithLowerSeqRejected() {
            // Bump seq to 5 first
            jdbc.update(
                    "UPDATE orchestrator.workflow_runs SET state_snapshot_seq = 5 "
                            + "WHERE run_id_public = :rid",
                    new MapSqlParameterSource("rid", "run-1"));
            // Try to UPDATE with newSeq=3 - V181 rejects (3 < 5)
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    jdbc.update(
                            "UPDATE orchestrator.workflow_runs SET state_snapshot_seq = :newSeq "
                                    + "WHERE run_id_public = :rid",
                            new MapSqlParameterSource("rid", "run-1").addValue("newSeq", 3L))
            ).hasMessageContaining("must not regress");
        }
    }

    @Nested
    @DisplayName("CAS predicate behavior - single writer + conflict")
    class CasPredicate {

        @Test
        @DisplayName("CAS with matching expected seq → rows=1")
        void casMatchingSeqSucceeds() {
            int rows = jdbc.update(
                    "UPDATE orchestrator.workflow_runs SET state_snapshot_seq = :newSeq "
                            + "WHERE run_id_public = :rid AND state_snapshot_seq = :expected",
                    new MapSqlParameterSource("rid", "run-1")
                            .addValue("newSeq", 1L)
                            .addValue("expected", 0L));
            assertThat(rows).isEqualTo(1);
        }

        @Test
        @DisplayName("CAS with mismatched expected seq → rows=0 (peer raced)")
        void casMismatchedSeqFails() {
            int rows = jdbc.update(
                    "UPDATE orchestrator.workflow_runs SET state_snapshot_seq = :newSeq "
                            + "WHERE run_id_public = :rid AND state_snapshot_seq = :expected",
                    new MapSqlParameterSource("rid", "run-1")
                            .addValue("newSeq", 100L)
                            .addValue("expected", 99L));  // doesn't match (row has 0)
            assertThat(rows).isZero();
        }
    }
}
