package com.apimarketplace.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Replays the REAL V499 against rows shaped like production.
 *
 * <p>V499 repairs {@code agent.agent_executions.started_at}, which was stamped at persist
 * time and therefore landed AFTER {@code ended_at} on all 7,953 production rows. It walks
 * the start back by the duration the writer measured - the same derivation
 * {@code AgentObservabilityService.startedAtFrom} now applies at write time.
 *
 * <p>The two halves are one contract written twice, in two languages, so the cases below
 * are deliberately the same cases the Java helper's unit test pins. They must agree on
 * the epoch boundary to the millisecond, because a row that disagrees is a row whose
 * stored start does not match what the service would have written for it.
 *
 * <p>The expensive case is the one a pure-Java test cannot see: {@code duration_ms} is
 * BIGINT fed from a Java long, and Postgres does NOT fail softly on a corrupt one.
 * {@code bigint * INTERVAL '1 millisecond'} raises {@code interval out of range}, and the
 * subtraction raises {@code timestamp out of range}. Either aborts the migration, and
 * migration-service is the only Flyway runner for every schema, so one absurd row would
 * stop the whole deploy. The first draft of V499 read as though it guarded against that
 * and did not: it built the interval INSIDE its own guard.
 *
 * <p>Lives in migration-service, on {@link FlywayTestSupport}, so it runs against CI's
 * {@code services: postgres} rather than a container: the ARC runners have no Docker
 * socket, and a container-only migration test would SKIP there and report green having
 * replayed nothing.
 */
@DisplayName("V499 makes agent_executions.started_at a real start")
class ExecutionStartedAtV499MigrationTest {

    private static final String DB = "execution_started_at_v499";

    private static final String MIGRATION = "V499__agent_execution_started_at_is_a_real_start.sql";

    /** The epoch-millisecond value of the instant every fixture row ends at. */
    private static final long ENDED_AT_EPOCH_MS = 1789678797611L;

    /**
     * agent_executions trimmed to the columns V499 reasons about, seeded with every row
     * shape the table can hold. Every start below is the inverted one production had: a
     * few milliseconds AFTER the end.
     */
    private static final String SCHEMA_AND_ROWS = """
            CREATE SCHEMA agent;

            CREATE TABLE agent.agent_executions (
                id          UUID PRIMARY KEY,
                label       TEXT NOT NULL,
                started_at  TIMESTAMPTZ NOT NULL,
                ended_at    TIMESTAMPTZ,
                duration_ms BIGINT
            );

            INSERT INTO agent.agent_executions (id, label, started_at, ended_at, duration_ms) VALUES
                ('00000000-0000-0000-0000-000000000001','real-turn',
                 '2026-09-17 20:59:57.615410+00', '2026-09-17 20:59:57.611410+00', 215416),
                ('00000000-0000-0000-0000-000000000002','unmeasured',
                 '2026-09-17 20:59:57.615410+00', '2026-09-17 20:59:57.611410+00', 0),
                ('00000000-0000-0000-0000-000000000003','null-duration',
                 '2026-09-17 20:59:57.615410+00', '2026-09-17 20:59:57.611410+00', NULL),
                ('00000000-0000-0000-0000-000000000004','negative-duration',
                 '2026-09-17 20:59:57.615410+00', '2026-09-17 20:59:57.611410+00', -5000),
                ('00000000-0000-0000-0000-000000000005','long-max-duration',
                 '2026-09-17 20:59:57.615410+00', '2026-09-17 20:59:57.611410+00', 9223372036854775807),
                ('00000000-0000-0000-0000-000000000006','pre-epoch-duration',
                 '2026-09-17 20:59:57.615410+00', '2026-09-17 20:59:57.611410+00', 300000000000000),
                ('00000000-0000-0000-0000-000000000007','still-running',
                 '2026-09-17 20:59:57.615410+00', NULL, 1000),
                ('00000000-0000-0000-0000-000000000008','already-correct',
                 '2026-09-17 20:56:22.199410+00', '2026-09-17 20:59:57.615410+00', 215416),
                ('00000000-0000-0000-0000-000000000009','start-earlier-than-duration',
                 '2026-09-17 20:00:00.000000+00', '2026-09-17 20:59:57.615410+00', 1000),
                ('00000000-0000-0000-0000-00000000000a','duration-exactly-epoch',
                 '2026-09-17 20:59:57.615410+00', '2026-09-17 20:59:57.611410+00', 1789678797611),
                ('00000000-0000-0000-0000-00000000000b','duration-one-past-epoch',
                 '2026-09-17 20:59:57.615410+00', '2026-09-17 20:59:57.611410+00', 1789678797612),
                ('00000000-0000-0000-0000-00000000000c','equal-timestamps-with-duration',
                 '2026-09-17 20:59:57.611410+00', '2026-09-17 20:59:57.611410+00', 4000),
                ('00000000-0000-0000-0000-00000000000d','negative-duration-not-inverted',
                 '2026-09-17 20:50:00.000000+00', '2026-09-17 20:59:57.611410+00', -5000);
            """;

    @Test
    @DisplayName("every row shape the table can hold is repaired, collapsed or left alone as its own rule says")
    void repairsEveryShape(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB);

            // A corrupt duration must not abort the migration - see the class javadoc.
            assertThatCode(() -> postgres.runFlyway(DB, tempDir)).doesNotThrowAnyException();

            Instant endedAt = Instant.parse("2026-09-17T20:59:57.611410Z");

            // The turn that exposed the bug: the start walks back by the measured duration.
            assertThat(startedAt(postgres, "real-turn"))
                    .isEqualTo(Instant.parse("2026-09-17T20:56:22.195410Z"));
            assertThat(spanMillis(postgres, "real-turn")).isEqualTo(215_416L);

            // Nothing measured, nothing to derive: a zero-length window at the end, which
            // is honest, rather than a stamp that lands after it.
            assertThat(startedAt(postgres, "unmeasured")).isEqualTo(endedAt);
            assertThat(startedAt(postgres, "null-duration")).isEqualTo(endedAt);
            assertThat(startedAt(postgres, "negative-duration")).isEqualTo(endedAt);

            // A duration no clock could have produced collapses too - and, crucially, gets
            // there without raising. These are the two rows that abort an unguarded V499.
            assertThat(startedAt(postgres, "long-max-duration")).isEqualTo(endedAt);
            assertThat(startedAt(postgres, "pre-epoch-duration")).isEqualTo(endedAt);

            // No end: there is nothing to derive from, and inventing one is worse than
            // leaving the row as it is.
            assertThat(startedAt(postgres, "still-running"))
                    .isEqualTo(Instant.parse("2026-09-17T20:59:57.615410Z"));

            // A row already stamped at its true start is not touched...
            assertThat(startedAt(postgres, "already-correct"))
                    .isEqualTo(Instant.parse("2026-09-17T20:56:22.199410Z"));
            // ...and neither is one whose start is EARLIER than the duration accounts for.
            // Only a start that sits too LATE is wrong; moving this one forward would
            // destroy the only evidence that the two disagree.
            assertThat(startedAt(postgres, "start-earlier-than-duration"))
                    .isEqualTo(Instant.parse("2026-09-17T20:00:00Z"));

            // The epoch boundary, to the millisecond, matching the Java helper exactly:
            // duration == the end's epoch-millis is still derived (it lands ON the epoch,
            // keeping the sub-millisecond remainder), one past it is not.
            assertThat(ENDED_AT_EPOCH_MS).isEqualTo(endedAt.toEpochMilli());
            assertThat(startedAt(postgres, "duration-exactly-epoch"))
                    .isEqualTo(Instant.EPOCH.plusNanos(410_000));
            assertThat(startedAt(postgres, "duration-one-past-epoch")).isEqualTo(endedAt);

            // Equal timestamps with a real duration are REPAIRED, not left alone. The
            // start is not "already correct" there: it is the zero-length window this
            // very statement writes when nothing was measured, and this row DID measure
            // 4 s. Leaving it would freeze an artefact of the bug into the repaired table.
            assertThat(spanMillis(postgres, "equal-timestamps-with-duration")).isEqualTo(4_000L);

            // A negative duration on a row that is NOT inverted is left completely alone.
            // The rule is about a start that sits too LATE; a broken duration is not a
            // reason to move a start that is already plausible.
            assertThat(startedAt(postgres, "negative-duration-not-inverted"))
                    .isEqualTo(Instant.parse("2026-09-17T20:50:00Z"));

            // The invariant the whole change exists for, asserted over the table rather
            // than row by row, so a shape added to the fixture later cannot slip past.
            assertThat(invertedRows(postgres)).isZero();
        }
    }

    @Test
    @DisplayName("a replay updates nothing, which is what makes the repair safe to re-run after a rollout")
    void replayUpdatesNothing(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);
        String db = DB + "_replay";

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(db);
            postgres.runFlyway(db, tempDir);

            // Flyway will not replay an applied version, so the statement is re-run
            // directly - the property under test is the SQL's idempotence, not Flyway's
            // bookkeeping. It matters beyond tidiness: migrations run BEFORE the app pods
            // roll, so old pods keep writing inverted rows for the length of the rollout
            // and this statement is the sweep for them.
            String migration = Files.readString(Path.of("src/main/resources/db/migration/" + MIGRATION));
            assertThat(updatedRowCount(postgres, db, migration)).isZero();

            // A row written by a not-yet-rolled pod after the migration is still repaired
            // by that same replay.
            execute(postgres, db,
                    "INSERT INTO agent.agent_executions (id, label, started_at, ended_at, duration_ms) VALUES "
                    + "('00000000-0000-0000-0000-0000000000ff','written-mid-rollout',"
                    + "'2026-09-17 21:04:00.004000+00','2026-09-17 21:04:00.000000+00', 8000)");
            assertThat(updatedRowCount(postgres, db, migration)).isEqualTo(1);
            assertThat(spanMillis(postgres, db, "written-mid-rollout")).isEqualTo(8_000L);
        }
    }

    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__seed_executions.sql"), SCHEMA_AND_ROWS);
        // The harness reads only this directory, so the real migration is copied in: a test
        // that forgot it would assert against untouched rows and fail loudly, not pass.
        Files.copy(Path.of("src/main/resources/db/migration/" + MIGRATION),
                directory.resolve(MIGRATION));
    }

    private static Instant startedAt(FlywayTestSupport.PostgresTarget p, String label) throws Exception {
        return startedAt(p, DB, label);
    }

    private static Instant startedAt(FlywayTestSupport.PostgresTarget p, String db, String label)
            throws Exception {
        try (var connection = DriverManager.getConnection(p.jdbcUrl(db), p.username(), p.password());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT started_at FROM agent.agent_executions WHERE label = '" + label + "'")) {
            if (!rs.next()) return null;
            Timestamp value = rs.getTimestamp(1, java.util.Calendar.getInstance(
                    java.util.TimeZone.getTimeZone("UTC")));
            return value == null ? null : value.toInstant();
        }
    }

    private static long spanMillis(FlywayTestSupport.PostgresTarget p, String label) throws Exception {
        return spanMillis(p, DB, label);
    }

    private static long spanMillis(FlywayTestSupport.PostgresTarget p, String db, String label)
            throws Exception {
        return scalar(p, db,
                "SELECT ROUND(EXTRACT(EPOCH FROM (ended_at - started_at)) * 1000) "
                + "FROM agent.agent_executions WHERE label = '" + label + "'");
    }

    private static long invertedRows(FlywayTestSupport.PostgresTarget p) throws Exception {
        return scalar(p, DB,
                "SELECT COUNT(*) FROM agent.agent_executions "
                + "WHERE ended_at IS NOT NULL AND started_at > ended_at");
    }

    private static long scalar(FlywayTestSupport.PostgresTarget p, String db, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(p.jdbcUrl(db), p.username(), p.password());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    /** Runs the migration body again and reports how many rows it actually moved. */
    private static int updatedRowCount(FlywayTestSupport.PostgresTarget p, String db, String sql)
            throws Exception {
        try (var connection = DriverManager.getConnection(p.jdbcUrl(db), p.username(), p.password());
             Statement statement = connection.createStatement()) {
            // The file is several statements (a SET, then the UPDATE), so every result in
            // the chain is walked. The terminating condition is the JDBC contract's own:
            // no more results AND an update count of -1.
            boolean isResultSet = statement.execute(sql);
            int total = 0;
            while (true) {
                if (!isResultSet) {
                    int count = statement.getUpdateCount();
                    if (count == -1) break;
                    total += count;
                }
                isResultSet = statement.getMoreResults();
                if (!isResultSet && statement.getUpdateCount() == -1) break;
            }
            return total;
        }
    }

    private static void execute(FlywayTestSupport.PostgresTarget p, String db, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(p.jdbcUrl(db), p.username(), p.password());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
