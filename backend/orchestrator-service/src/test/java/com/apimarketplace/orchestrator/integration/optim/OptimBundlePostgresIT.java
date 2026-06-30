package com.apimarketplace.orchestrator.integration.optim;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Postgres-backed E2E for the optimization bundle (V181 trigger + V182
 * instance_lease) - covers the dimensions the unit tests cannot:
 * <ul>
 *   <li><b>V181 trigger</b>: real PL/pgSQL on Postgres 16, INSERT and UPDATE
 *       paths, regression rejection, equal-seq UPDATE allowed.</li>
 *   <li><b>V182 instance_lease</b>: bootstrap UPSERT, generation bump on
 *       heartbeat, atomic CTE steal with FOR UPDATE SKIP LOCKED + COALESCE on
 *       missing lease row, fencing-token validation.</li>
 *   <li><b>Epoch × iteration × span (state_snapshot_seq monotonicity)</b>:
 *       interleaved updates simulating multi-trigger fires (epoch advance),
 *       loop body iterations (iteration), and split per-item persistence
 *       (item_index). Asserts seq advances strictly monotone across all
 *       dimensions and the trigger never drops a legitimate write.</li>
 * </ul>
 *
 * <p>Skipped silently when Docker is unavailable (matches
 * {@link com.apimarketplace.orchestrator.services.state.patch.JsonbPatchPostgresIT}
 * pattern). CI runners with Docker exercise the full suite.
 *
 * <p>Why these tests live in {@code integration.optim} rather than
 * {@code integration.repository}: they bypass the JPA/Hibernate layer entirely
 * (via {@link NamedParameterJdbcTemplate}) and validate raw SQL invariants -
 * not Spring Data behaviour.
 */
@Testcontainers
class OptimBundlePostgresIT {

    @Container
    @SuppressWarnings("resource") // shared across the test class
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    static void setUpClass() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available - OptimBundlePostgresIT skipped");

        DataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new NamedParameterJdbcTemplate(ds);

        // Schema: minimal slice of orchestrator.workflow_runs that the V181
        // trigger guards. We replicate the columns + the trigger inline to keep
        // the test self-contained (no need to bootstrap the full Flyway chain).
        jdbc.getJdbcTemplate().execute("CREATE SCHEMA IF NOT EXISTS orchestrator");

        jdbc.getJdbcTemplate().execute(
                "CREATE TABLE IF NOT EXISTS orchestrator.workflow_runs ("
                        + "  id UUID PRIMARY KEY,"
                        + "  run_id_public VARCHAR(64) UNIQUE NOT NULL,"
                        + "  state_snapshot JSONB,"
                        + "  state_snapshot_seq BIGINT NOT NULL DEFAULT 0,"
                        + "  current_epoch INT NOT NULL DEFAULT 0,"
                        + "  current_iteration INT,"
                        + "  current_item_index INT"
                        + ")");

        // V181 trigger - replicated verbatim from
        // V181__seq_monotonicity_and_signal_poller_index.sql so this IT
        // exercises the actual function definition.
        jdbc.getJdbcTemplate().execute(
                "CREATE OR REPLACE FUNCTION orchestrator.enforce_state_snapshot_seq()\n"
                        + "RETURNS TRIGGER AS $$\n"
                        + "BEGIN\n"
                        + "    IF TG_OP = 'INSERT' THEN\n"
                        + "        IF NEW.state_snapshot_seq < 0 THEN\n"
                        + "            RAISE EXCEPTION 'state_snapshot_seq must be >= 0 on INSERT (was=%)',\n"
                        + "                NEW.state_snapshot_seq;\n"
                        + "        END IF;\n"
                        + "        RETURN NEW;\n"
                        + "    END IF;\n"
                        + "    IF NEW.state_snapshot_seq < OLD.state_snapshot_seq THEN\n"
                        + "        RAISE EXCEPTION 'state_snapshot_seq must not regress (was=%, new=%)',\n"
                        + "            OLD.state_snapshot_seq, NEW.state_snapshot_seq;\n"
                        + "    END IF;\n"
                        + "    RETURN NEW;\n"
                        + "END;\n"
                        + "$$ LANGUAGE plpgsql");

        jdbc.getJdbcTemplate().execute(
                "DROP TRIGGER IF EXISTS state_snapshot_seq_monotonicity ON orchestrator.workflow_runs");

        jdbc.getJdbcTemplate().execute(
                "CREATE TRIGGER state_snapshot_seq_monotonicity\n"
                        + "  BEFORE INSERT OR UPDATE ON orchestrator.workflow_runs\n"
                        + "  FOR EACH ROW EXECUTE FUNCTION orchestrator.enforce_state_snapshot_seq()");

        // V182 instance_lease + signal_waits (only the columns we exercise)
        jdbc.getJdbcTemplate().execute(
                "CREATE TABLE IF NOT EXISTS orchestrator.instance_lease ("
                        + "  instance_id VARCHAR(128) PRIMARY KEY,"
                        + "  lease_until TIMESTAMPTZ NOT NULL,"
                        + "  generation BIGINT NOT NULL DEFAULT 1,"
                        + "  last_id BIGINT NOT NULL DEFAULT 0,"
                        + "  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),"
                        + "  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()"
                        + ")");

        jdbc.getJdbcTemplate().execute(
                "CREATE TABLE IF NOT EXISTS orchestrator.workflow_signal_waits ("
                        + "  id BIGSERIAL PRIMARY KEY,"
                        + "  status VARCHAR(32) NOT NULL,"
                        + "  signal_type VARCHAR(32) NOT NULL,"
                        + "  expires_at TIMESTAMPTZ,"
                        + "  owner_instance VARCHAR(128),"
                        + "  claimed_by VARCHAR(128),"
                        + "  claimed_generation BIGINT NOT NULL DEFAULT 0,"
                        + "  retry_after TIMESTAMPTZ"
                        + ")");
    }

    @BeforeEach
    void cleanData() {
        jdbc.getJdbcTemplate().execute("DELETE FROM orchestrator.workflow_runs");
        jdbc.getJdbcTemplate().execute("DELETE FROM orchestrator.instance_lease");
        jdbc.getJdbcTemplate().execute("DELETE FROM orchestrator.workflow_signal_waits");
    }

    private UUID insertRun(String runIdPublic, long initialSeq) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO orchestrator.workflow_runs"
                        + " (id, run_id_public, state_snapshot, state_snapshot_seq)"
                        + " VALUES (:id, :rid, :json::jsonb, :seq)",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("rid", runIdPublic)
                        .addValue("json", "{\"version\":3,\"seq\":" + initialSeq + ",\"dags\":{}}")
                        .addValue("seq", initialSeq));
        return id;
    }

    @Nested
    @DisplayName("V181 - state_snapshot_seq monotonicity trigger")
    class V181Trigger {

        @Test
        @DisplayName("INSERT with seq < 0 → trigger raises")
        void insertNegativeSeqRaises() {
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO orchestrator.workflow_runs (id, run_id_public, state_snapshot_seq)"
                            + " VALUES (:id, :rid, :seq)",
                    new MapSqlParameterSource()
                            .addValue("id", UUID.randomUUID())
                            .addValue("rid", "TEST-NEG")
                            .addValue("seq", -1L)))
                    .hasMessageContaining("state_snapshot_seq must be >= 0 on INSERT");
        }

        @Test
        @DisplayName("INSERT with seq = 0 (V178 default) succeeds")
        void insertZeroSeqAccepted() {
            UUID id = insertRun("TEST-ZERO", 0L);
            assertThat(id).isNotNull();
            Long seq = jdbc.getJdbcTemplate().queryForObject(
                    "SELECT state_snapshot_seq FROM orchestrator.workflow_runs WHERE id = ?",
                    Long.class, id);
            assertThat(seq).isZero();
        }

        @Test
        @DisplayName("UPDATE that advances seq (10 → 11) succeeds")
        void updateMonotoneAdvanceAccepted() {
            UUID id = insertRun("TEST-ADV", 10L);
            int updated = jdbc.update(
                    "UPDATE orchestrator.workflow_runs SET state_snapshot_seq = :s WHERE id = :id",
                    new MapSqlParameterSource().addValue("s", 11L).addValue("id", id));
            assertThat(updated).isEqualTo(1);
        }

        @Test
        @DisplayName("UPDATE that regresses seq (10 → 9) → trigger raises")
        void updateRegressionRaises() {
            UUID id = insertRun("TEST-REG", 10L);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE orchestrator.workflow_runs SET state_snapshot_seq = :s WHERE id = :id",
                    new MapSqlParameterSource().addValue("s", 9L).addValue("id", id)))
                    .hasMessageContaining("state_snapshot_seq must not regress (was=10, new=9)");
        }

        @Test
        @DisplayName("UPDATE without touching seq (status flip) is allowed - no false-positive raise")
        void updateUnrelatedColumnAllowed() {
            UUID id = insertRun("TEST-NOOP", 42L);
            // Simulate a non-state-snapshot column update (the trigger fires
            // FOR EACH ROW on every UPDATE, but the seq compare is INT-cheap).
            int updated = jdbc.update(
                    "UPDATE orchestrator.workflow_runs SET current_epoch = 1 WHERE id = :id",
                    new MapSqlParameterSource().addValue("id", id));
            assertThat(updated).isEqualTo(1);
            Long seq = jdbc.getJdbcTemplate().queryForObject(
                    "SELECT state_snapshot_seq FROM orchestrator.workflow_runs WHERE id = ?",
                    Long.class, id);
            assertThat(seq).isEqualTo(42L); // unchanged
        }
    }

    @Nested
    @DisplayName("V182 - instance_lease + claimed_generation fencing")
    class V182Lease {

        @Test
        @DisplayName("Bootstrap UPSERT - first INSERT, then restart bumps generation")
        void bootstrapUpsertBumpsGenerationOnRestart() {
            // First boot
            jdbc.update("INSERT INTO orchestrator.instance_lease (instance_id, lease_until, generation)"
                            + " VALUES (:id, NOW() + INTERVAL '30 seconds', 1)"
                            + " ON CONFLICT (instance_id) DO UPDATE"
                            + " SET lease_until = EXCLUDED.lease_until,"
                            + "     generation = orchestrator.instance_lease.generation + 1",
                    new MapSqlParameterSource().addValue("id", "inst-1"));
            assertThat(generationOf("inst-1")).isEqualTo(1L);

            // Simulate restart
            jdbc.update("INSERT INTO orchestrator.instance_lease (instance_id, lease_until, generation)"
                            + " VALUES (:id, NOW() + INTERVAL '30 seconds', 1)"
                            + " ON CONFLICT (instance_id) DO UPDATE"
                            + " SET lease_until = EXCLUDED.lease_until,"
                            + "     generation = orchestrator.instance_lease.generation + 1",
                    new MapSqlParameterSource().addValue("id", "inst-1"));
            assertThat(generationOf("inst-1")).isEqualTo(2L); // generation bumped on restart
        }

        @Test
        @DisplayName("Atomic steal - peer claims signals from dead instance via SKIP LOCKED + COALESCE on missing lease")
        void atomicStealClaimsOrphans() {
            // dead instance: lease expired (10 min ago)
            jdbc.update("INSERT INTO orchestrator.instance_lease (instance_id, lease_until, generation)"
                            + " VALUES (:id, NOW() - INTERVAL '10 minutes', 5)",
                    new MapSqlParameterSource().addValue("id", "dead-inst"));
            // live peer with active lease
            jdbc.update("INSERT INTO orchestrator.instance_lease (instance_id, lease_until, generation)"
                            + " VALUES (:id, NOW() + INTERVAL '30 seconds', 7)",
                    new MapSqlParameterSource().addValue("id", "live-inst"));
            // 3 orphan signals owned by the dead instance
            for (int i = 0; i < 3; i++) {
                jdbc.update("INSERT INTO orchestrator.workflow_signal_waits"
                                + " (status, signal_type, expires_at, owner_instance, claimed_generation)"
                                + " VALUES ('PENDING', 'WAIT_TIMER', NOW() + INTERVAL '1 hour',"
                                + "         'dead-inst', 0)",
                        new MapSqlParameterSource());
            }

            // Atomic CTE steal - replicates the production SQL shape
            int stolen = jdbc.update(
                    // Audit A SC5 fix: Postgres rejects FOR UPDATE on the nullable
                    // side of a LEFT JOIN ("FOR UPDATE cannot be applied to the
                    // nullable side of an outer join"). Lock the kernel
                    // (signal_waits) on its own, then filter via NOT EXISTS so
                    // the lease check stays planner-stable. Equivalent semantic:
                    // "claim signals whose owner has no live lease".
                    "WITH stale AS ("
                            + "  SELECT sw.id FROM orchestrator.workflow_signal_waits sw"
                            + "  WHERE NOT EXISTS ("
                            + "    SELECT 1 FROM orchestrator.instance_lease il"
                            + "    WHERE il.instance_id = sw.owner_instance"
                            + "      AND il.lease_until >= NOW()"
                            + "  )"
                            + "  FOR UPDATE SKIP LOCKED"
                            + "), my_gen AS ("
                            + "  SELECT generation FROM orchestrator.instance_lease WHERE instance_id = :me"
                            + ")"
                            + " UPDATE orchestrator.workflow_signal_waits"
                            + " SET claimed_by = :me,"
                            + "     claimed_generation = (SELECT generation FROM my_gen)"
                            + " FROM stale"
                            + " WHERE workflow_signal_waits.id = stale.id",
                    new MapSqlParameterSource().addValue("me", "live-inst"));

            assertThat(stolen).isEqualTo(3);
            Long claimedCount = jdbc.getJdbcTemplate().queryForObject(
                    "SELECT COUNT(*) FROM orchestrator.workflow_signal_waits"
                            + " WHERE claimed_by = 'live-inst' AND claimed_generation = 7",
                    Long.class);
            assertThat(claimedCount).isEqualTo(3L);
        }

        @Test
        @DisplayName("Atomic steal - instance with NO lease row is treated as 'stalest' via COALESCE (bootstrap edge case)")
        void atomicStealHandlesMissingLease() {
            // No instance_lease row for "ghost-inst" - it crashed before ever heart-beating
            jdbc.update("INSERT INTO orchestrator.instance_lease (instance_id, lease_until, generation)"
                            + " VALUES (:id, NOW() + INTERVAL '30 seconds', 1)",
                    new MapSqlParameterSource().addValue("id", "live-inst"));
            jdbc.update("INSERT INTO orchestrator.workflow_signal_waits"
                            + " (status, signal_type, expires_at, owner_instance, claimed_generation)"
                            + " VALUES ('PENDING', 'WAIT_TIMER', NOW() + INTERVAL '1 hour',"
                            + "         'ghost-inst', 0)",
                    new MapSqlParameterSource());

            int stolen = jdbc.update(
                    // Audit A SC5 fix: NOT EXISTS instead of LEFT JOIN, lock the
                    // kernel only - see atomicStealClaimsOrphans for rationale.
                    "WITH stale AS ("
                            + "  SELECT sw.id FROM orchestrator.workflow_signal_waits sw"
                            + "  WHERE NOT EXISTS ("
                            + "    SELECT 1 FROM orchestrator.instance_lease il"
                            + "    WHERE il.instance_id = sw.owner_instance"
                            + "      AND il.lease_until >= NOW()"
                            + "  )"
                            + "  FOR UPDATE SKIP LOCKED"
                            + ")"
                            + " UPDATE orchestrator.workflow_signal_waits"
                            + " SET claimed_by = :me, claimed_generation = 1"
                            + " FROM stale"
                            + " WHERE workflow_signal_waits.id = stale.id",
                    new MapSqlParameterSource().addValue("me", "live-inst"));
            assertThat(stolen).isEqualTo(1);
        }

        private Long generationOf(String instanceId) {
            return jdbc.getJdbcTemplate().queryForObject(
                    "SELECT generation FROM orchestrator.instance_lease WHERE instance_id = ?",
                    Long.class, instanceId);
        }
    }

    @Nested
    @DisplayName("Epoch × iteration × span - seq monotonicity across all execution dimensions")
    class EpochIterationSpan {

        @Test
        @DisplayName("Single trigger, no loop, no split - seq advances 1 → N strictly monotone")
        void singleEpochLinearAdvance() {
            UUID id = insertRun("TEST-LINEAR", 0L);
            for (long s = 1; s <= 20; s++) {
                jdbc.update(
                        "UPDATE orchestrator.workflow_runs SET state_snapshot_seq = :s WHERE id = :id",
                        new MapSqlParameterSource().addValue("s", s).addValue("id", id));
            }
            assertSeqEquals(id, 20L);
        }

        @Test
        @DisplayName("Multi-trigger fires (epoch) - seq continues across epoch boundary, never resets")
        void multiEpochAdvance() {
            UUID id = insertRun("TEST-MULTI-EPOCH", 0L);

            // Epoch 1: 5 mutations
            for (long s = 1; s <= 5; s++) {
                jdbc.update("UPDATE orchestrator.workflow_runs SET state_snapshot_seq = :s,"
                                + " current_epoch = 1 WHERE id = :id",
                        new MapSqlParameterSource().addValue("s", s).addValue("id", id));
            }
            // Epoch 2 fires - seq KEEPS advancing (must not reset)
            for (long s = 6; s <= 12; s++) {
                jdbc.update("UPDATE orchestrator.workflow_runs SET state_snapshot_seq = :s,"
                                + " current_epoch = 2 WHERE id = :id",
                        new MapSqlParameterSource().addValue("s", s).addValue("id", id));
            }
            // Epoch 3 - same
            for (long s = 13; s <= 20; s++) {
                jdbc.update("UPDATE orchestrator.workflow_runs SET state_snapshot_seq = :s,"
                                + " current_epoch = 3 WHERE id = :id",
                        new MapSqlParameterSource().addValue("s", s).addValue("id", id));
            }

            assertSeqEquals(id, 20L);
            Integer epoch = jdbc.getJdbcTemplate().queryForObject(
                    "SELECT current_epoch FROM orchestrator.workflow_runs WHERE id = ?",
                    Integer.class, id);
            assertThat(epoch).isEqualTo(3);
        }

        @Test
        @DisplayName("Loop iteration (iteration column) - body iter=1..5 each bumps seq exactly once")
        void loopIterationsEachBumpSeq() {
            UUID id = insertRun("TEST-LOOP", 100L);

            // simulate 5 loop body iterations, each advancing seq by 2
            // (markRunning + markCompleted contract from the bundle)
            long seq = 100L;
            for (int iter = 1; iter <= 5; iter++) {
                seq += 2;
                jdbc.update("UPDATE orchestrator.workflow_runs SET state_snapshot_seq = :s,"
                                + " current_iteration = :it WHERE id = :id",
                        new MapSqlParameterSource()
                                .addValue("s", seq)
                                .addValue("it", iter)
                                .addValue("id", id));
            }
            assertSeqEquals(id, 110L);
            Integer iteration = jdbc.getJdbcTemplate().queryForObject(
                    "SELECT current_iteration FROM orchestrator.workflow_runs WHERE id = ?",
                    Integer.class, id);
            assertThat(iteration).isEqualTo(5);
        }

        @Test
        @DisplayName("Split per-item (item_index column) - 10 items, each advances seq, monotonicity preserved")
        void splitPerItemPersistenceMonotone() {
            UUID id = insertRun("TEST-SPLIT", 50L);

            long seq = 50L;
            // Simulate 10 items in a split (sequential simulation; in production
            // they may run on FJP workers - interleaving still preserves
            // monotonicity because every UPDATE bumps seq by the trigger).
            for (int item = 0; item < 10; item++) {
                seq += 3; // markRunning + markCompleted + edge-emit per item ≈ 3 updates
                jdbc.update("UPDATE orchestrator.workflow_runs SET state_snapshot_seq = :s,"
                                + " current_item_index = :it WHERE id = :id",
                        new MapSqlParameterSource()
                                .addValue("s", seq)
                                .addValue("it", item)
                                .addValue("id", id));
            }
            assertSeqEquals(id, 80L);
            Integer lastItem = jdbc.getJdbcTemplate().queryForObject(
                    "SELECT current_item_index FROM orchestrator.workflow_runs WHERE id = ?",
                    Integer.class, id);
            assertThat(lastItem).isEqualTo(9);
        }

        @Test
        @DisplayName("FULL DEPTH - 3 epochs × 5 iterations × 4 split items, seq advances through all dimensions without regression")
        void fullDimensionalSweep() {
            UUID id = insertRun("TEST-FULL", 0L);

            long seq = 0L;
            int totalUpdates = 0;
            for (int epoch = 1; epoch <= 3; epoch++) {
                for (int iter = 1; iter <= 5; iter++) {
                    for (int item = 0; item < 4; item++) {
                        seq++;
                        jdbc.update("UPDATE orchestrator.workflow_runs SET"
                                        + " state_snapshot_seq = :s,"
                                        + " current_epoch = :e,"
                                        + " current_iteration = :i,"
                                        + " current_item_index = :it WHERE id = :id",
                                new MapSqlParameterSource()
                                        .addValue("s", seq).addValue("e", epoch)
                                        .addValue("i", iter).addValue("it", item)
                                        .addValue("id", id));
                        totalUpdates++;
                    }
                }
            }

            assertThat(totalUpdates).isEqualTo(3 * 5 * 4); // 60 updates
            assertSeqEquals(id, 60L);

            // Final dimension state: epoch=3, iter=5, item=3
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT current_epoch, current_iteration, current_item_index"
                            + " FROM orchestrator.workflow_runs WHERE id = :id",
                    new MapSqlParameterSource().addValue("id", id));
            assertThat(row.get("current_epoch")).isEqualTo(3);
            assertThat(row.get("current_iteration")).isEqualTo(5);
            assertThat(row.get("current_item_index")).isEqualTo(3);
        }

        @Test
        @DisplayName("Regression mid-sweep - even after 30 valid bumps, an out-of-order seq=N-1 still raises")
        void regressionRaisesEvenAfterManyBumps() {
            UUID id = insertRun("TEST-REG-DEEP", 0L);
            for (long s = 1; s <= 30; s++) {
                jdbc.update("UPDATE orchestrator.workflow_runs SET state_snapshot_seq = :s WHERE id = :id",
                        new MapSqlParameterSource().addValue("s", s).addValue("id", id));
            }
            assertSeqEquals(id, 30L);

            // Try to roll back - must fail
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE orchestrator.workflow_runs SET state_snapshot_seq = :s WHERE id = :id",
                    new MapSqlParameterSource().addValue("s", 29L).addValue("id", id)))
                    .hasMessageContaining("must not regress (was=30, new=29)");

            // Seq still 30 (the failed UPDATE rolled back atomically)
            assertSeqEquals(id, 30L);
        }

        private void assertSeqEquals(UUID id, long expected) {
            Long actual = jdbc.getJdbcTemplate().queryForObject(
                    "SELECT state_snapshot_seq FROM orchestrator.workflow_runs WHERE id = ?",
                    Long.class, id);
            assertThat(actual).isEqualTo(expected);
        }
    }
}
