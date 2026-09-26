package com.apimarketplace.orchestrator.persistence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres contract for the two aggregated-steps queries, the whole content of the Logs modal.
 *
 * <p>They are native SQL and, before this class, nothing asserted their ANSWER: every existing test
 * around this feature mocks the repository. The e2e suite does reach the endpoint on every
 * {@code ExecutionWaiter} poll, but {@code StatusCountsVerifier} swallows a failure into an empty
 * list behind a {@code logger.warn}, so that path could never have reported a wrong result either.
 * (Both SQL forms do run on H2 in {@code MODE=PostgreSQL}, so this is not a dialect gap and H2
 * compatibility is not free to break: it is a coverage gap.) That is uncomfortable for a query whose
 * whole subtlety is silent: keeping only the latest {@code spawn} per coordinate is what stops a
 * branch a rerun deactivated from still reporting "completed", and getting that wrong produces a
 * plausible wrong answer, never an error.
 *
 * <p><b>What this class is for.</b> The per-coordinate max was a correlated subquery re-evaluated
 * once per row, each evaluation scanning every epoch of that node: cost quadratic in the number of
 * epochs, seconds of latency on a long-lived run (measured 10.7 s for 1 000 epochs). It is now a
 * single-pass {@code MAX(...) OVER (PARTITION BY ...)}. The rewrite must be a PURE one, so the
 * pre-rewrite SQL is kept below as an oracle and every case asserts the two forms agree, including
 * on the coordinates whose NULLs the old form coalesced.
 *
 * <p><b>How it runs.</b> Plain JDBC against a scratch database named by
 * {@code ORCHESTRATOR_TEST_PG_URL}, mirroring {@link SubWorkflowEdgeQueryPostgresTest} (the
 * {@code arc-build} runners expose no Docker socket, so a Testcontainers class SKIPS there, which
 * looks like coverage and is not). With {@code CI} set and no URL the class FAILS rather than
 * skipping, so it cannot be silently disabled by dropping the env block from its workflow step.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("aggregated steps (Logs modal) - real Postgres, the shipped SQL")
class AggregatedStepsQueryPostgresTest {

    private static final String URL = System.getenv("ORCHESTRATOR_TEST_PG_URL");
    private static final String USER = System.getenv().getOrDefault("ORCHESTRATOR_TEST_PG_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("ORCHESTRATOR_TEST_PG_PASSWORD", "postgres");

    private static final String RUN = "run-under-test";
    private static final String OTHER_RUN = "run-next-door";
    private static final String TRIGGER = "trigger:default";

    /**
     * The whole-run query as it stood before the window rewrite, kept verbatim as an oracle.
     *
     * <p>It is deliberately NOT read from the repository: its only job is to state what the answer
     * used to be, so that "faster" can be proven to also mean "identical". If a future change means
     * to alter the ANSWER and not just the plan, this constant is what has to be updated, on
     * purpose, with the reason written down.
     */
    private static final String LEGACY_WHOLE_RUN_SQL = """
        SELECT w.step_alias as "stepAlias", w.status as status, COUNT(*) as count,
               MIN(w.tool_id) as "toolId", MIN(w.start_time) as "minStartTime", MAX(w.end_time) as "maxEndTime",
               CAST(GREATEST(COALESCE(SUM(GREATEST((EXTRACT(EPOCH FROM w.end_time) - EXTRACT(EPOCH FROM w.start_time)) * 1000, 0)), 0), 0) AS BIGINT) as "sumExecutionTimeMs"
        FROM workflow_step_data w
        WHERE w.run_id = :runId AND w.step_alias IS NOT NULL
          AND COALESCE(w.spawn, 0) = (
              SELECT MAX(COALESCE(w2.spawn, 0)) FROM workflow_step_data w2
              WHERE w2.run_id = w.run_id AND w2.step_alias = w.step_alias
                AND COALESCE(w2.trigger_id, '') = COALESCE(w.trigger_id, '')
                AND COALESCE(w2.epoch, 0) = COALESCE(w.epoch, 0)
                AND COALESCE(w2.iteration, 0) = COALESCE(w.iteration, 0)
                AND COALESCE(w2.item_index, 0) = COALESCE(w.item_index, 0))
        GROUP BY w.step_alias, w.status
        """;

    /** The per-epoch query as it stood before the window rewrite. Same role as the constant above. */
    private static final String LEGACY_EPOCH_SQL = """
        SELECT w.step_alias as "stepAlias", w.status as status, COUNT(*) as count,
               MIN(w.tool_id) as "toolId", MIN(w.start_time) as "minStartTime", MAX(w.end_time) as "maxEndTime",
               CAST(GREATEST(COALESCE(SUM(GREATEST((EXTRACT(EPOCH FROM w.end_time) - EXTRACT(EPOCH FROM w.start_time)) * 1000, 0)), 0), 0) AS BIGINT) as "sumExecutionTimeMs"
        FROM workflow_step_data w
        WHERE w.run_id = :runId AND w.step_alias IS NOT NULL AND w.epoch = :epoch
          AND COALESCE(w.spawn, 0) = (
              SELECT MAX(COALESCE(w2.spawn, 0)) FROM workflow_step_data w2
              WHERE w2.run_id = w.run_id AND w2.step_alias = w.step_alias
                AND COALESCE(w2.trigger_id, '') = COALESCE(w.trigger_id, '')
                AND COALESCE(w2.epoch, 0) = COALESCE(w.epoch, 0)
                AND COALESCE(w2.iteration, 0) = COALESCE(w.iteration, 0)
                AND COALESCE(w2.item_index, 0) = COALESCE(w.item_index, 0))
        GROUP BY w.step_alias, w.status
        """;

    private String wholeRunSql;
    private String epochSql;
    private String rangeSql;
    private String elapsedSql;
    private JdbcTemplate jdbc;

    @BeforeAll
    void setUpSchema() throws Exception {
        requireDatabaseOnCi();

        String database = URL.substring(URL.lastIndexOf('/') + 1).split("\\?")[0];
        if (!database.toLowerCase(Locale.ROOT).contains("test")) {
            throw new IllegalStateException(
                    "ORCHESTRATOR_TEST_PG_URL must point at a scratch database whose name contains "
                            + "'test' (this test drops a table named 'workflow_step_data'), got: " + database);
        }

        wholeRunSql = shippedSql("getAggregatedStepsByRunId", String.class);
        epochSql = shippedSql("getAggregatedStepsByRunIdAndEpoch", String.class, int.class);
        rangeSql = shippedSql("getAggregatedStepsByRunIdAndEpochRange", String.class, int.class, int.class);
        elapsedSql = shippedSql("getElapsedByRunIdAndEpochRange", String.class, int.class, int.class);

        awaitDatabase();
        DriverManagerDataSource ds = new DriverManagerDataSource(URL, USER, PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);

        // Only the columns the two queries read. A fuller mirror of the real table would drift;
        // what this pins is the SQL's behaviour, not the schema. The coordinate columns are left
        // NULLABLE on purpose - the COALESCE wrappers exist for legacy rows that carry NULLs.
        //
        // The drop is CASCADE, and it runs again in @AfterAll: this scratch database is SHARED with
        // the other Postgres-backed classes in the same CI step, so a table left behind - or any
        // object that ended up depending on it - would turn their setup into an error, not a skip.
        jdbc.execute("DROP TABLE IF EXISTS workflow_step_data CASCADE");
        jdbc.execute("""
                CREATE TABLE workflow_step_data (
                    id          BIGSERIAL PRIMARY KEY,
                    run_id      VARCHAR(255) NOT NULL,
                    step_alias  VARCHAR(2000),
                    tool_id     VARCHAR(2000),
                    status      VARCHAR(32) NOT NULL,
                    start_time  TIMESTAMPTZ,
                    end_time    TIMESTAMPTZ,
                    epoch       INTEGER,
                    spawn       INTEGER,
                    iteration   INTEGER,
                    item_index  INTEGER,
                    trigger_id  VARCHAR(2000),
                    error_message TEXT
                )
                """);
    }

    // Non-static because the lifecycle is PER_CLASS and `jdbc` is an instance field.
    @AfterAll
    void dropScratchTable() {
        if (jdbc != null) {
            jdbc.execute("DROP TABLE IF EXISTS workflow_step_data CASCADE");
        }
    }

    @BeforeEach
    void truncate() {
        jdbc.execute("TRUNCATE workflow_step_data");
    }

    @Test
    @DisplayName("one row per (alias, status), counted across every epoch of the run")
    void accumulatesAcrossEpochs() {
        for (int epoch = 0; epoch < 5; epoch++) {
            insert("fetch", "COMPLETED", epoch, 0, 0, 0);
        }
        insert("fetch", "FAILED", 5, 0, 0, 0);

        assertThat(wholeRun()).containsExactlyInAnyOrder(
                row("fetch", "COMPLETED", 5),
                row("fetch", "FAILED", 1));
        assertMatchesLegacyWholeRun();
    }

    @Test
    @DisplayName("a rerun supersedes its own coordinate: only the latest spawn counts")
    void latestSpawnSupersedesTheEarlierOne() {
        // The invariant the whole query exists for. Pre-rerun the branch completed; the rerun
        // deactivated it and wrote SKIPPED at spawn 1. Counting both would let "completed" win.
        insert("branch", "COMPLETED", 0, 0, 0, 0);
        insert("branch", "SKIPPED", 0, 1, 0, 0);

        assertThat(wholeRun()).containsExactly(row("branch", "SKIPPED", 1));
        assertMatchesLegacyWholeRun();
    }

    @Test
    @DisplayName("supersession is per coordinate - a rerun in one epoch leaves the others counted")
    void supersessionIsScopedToItsCoordinate() {
        insert("branch", "COMPLETED", 0, 0, 0, 0);
        insert("branch", "COMPLETED", 1, 0, 0, 0);
        insert("branch", "COMPLETED", 2, 0, 0, 0);
        insert("branch", "SKIPPED", 1, 1, 0, 0); // rerun of epoch 1 only

        assertThat(wholeRun()).containsExactlyInAnyOrder(
                row("branch", "COMPLETED", 2),
                row("branch", "SKIPPED", 1));
        assertMatchesLegacyWholeRun();
    }

    @Test
    @DisplayName("each coordinate axis separates spawns: trigger, iteration and item are distinct rows")
    void everyCoordinateAxisIsPartOfTheKey() {
        // Same alias, same epoch, spawn 1 written on ONE of the four axes each time. If an axis
        // were missing from the partition, that spawn-1 row would supersede the others' spawn-0
        // rows and the count would collapse.
        insert("node", "COMPLETED", 0, 0, 0, 0, TRIGGER);
        insert("node", "COMPLETED", 0, 0, 0, 1, TRIGGER);          // other item
        insert("node", "COMPLETED", 0, 0, 1, 0, TRIGGER);          // other iteration
        insert("node", "COMPLETED", 0, 0, 0, 0, "trigger:other");  // other trigger
        insert("node", "FAILED", 0, 1, 0, 0, TRIGGER);             // rerun of the first only

        assertThat(wholeRun()).containsExactlyInAnyOrder(
                row("node", "COMPLETED", 3),
                row("node", "FAILED", 1));
        assertMatchesLegacyWholeRun();
    }

    @Test
    @DisplayName("NULL coordinates are read as 0 / empty, exactly as the correlated form read them")
    void nullCoordinatesCoalesce() {
        // Legacy rows predate the column defaults. A NULL spawn is spawn 0, so the explicit spawn-1
        // row supersedes it - and a NULL epoch/iteration/item shares a coordinate with a plain 0.
        insert("legacy", "COMPLETED", null, null, null, null, null);
        insert("legacy", "SKIPPED", 0, 1, 0, 0, "");

        assertThat(wholeRun()).containsExactly(row("legacy", "SKIPPED", 1));
        assertMatchesLegacyWholeRun();
    }

    @Test
    @DisplayName("rows with no alias, and other runs, contribute nothing")
    void ignoresAliaslessRowsAndOtherRuns() {
        insert("kept", "COMPLETED", 0, 0, 0, 0);
        jdbc.update("INSERT INTO workflow_step_data (run_id, step_alias, tool_id, status, epoch, spawn, iteration, item_index, trigger_id) "
                + "VALUES (?, NULL, 'tool', 'COMPLETED', 0, 0, 0, 0, ?)", RUN, TRIGGER);
        jdbc.update("INSERT INTO workflow_step_data (run_id, step_alias, tool_id, status, epoch, spawn, iteration, item_index, trigger_id) "
                + "VALUES (?, 'foreign', 'tool', 'COMPLETED', 0, 0, 0, 0, ?)", OTHER_RUN, TRIGGER);

        assertThat(wholeRun()).containsExactly(row("kept", "COMPLETED", 1));
        assertMatchesLegacyWholeRun();
    }

    @Test
    @DisplayName("timing aggregates: earliest start, latest end, summed durations")
    void aggregatesTiming() {
        insertTimed("timed", "COMPLETED", 0, "2026-01-01 10:00:00+00", "2026-01-01 10:00:02+00");
        insertTimed("timed", "COMPLETED", 1, "2026-01-01 09:00:00+00", "2026-01-01 09:00:03+00");

        Map<String, Object> only = rawWholeRun().get(0);
        // Compared as instants: the driver hands back a Timestamp rendered in the JVM's zone, so a
        // string assertion here would pass on the runner and fail on a laptop, or the reverse.
        assertThat(((Timestamp) only.get("minStartTime")).toInstant()).isEqualTo(Instant.parse("2026-01-01T09:00:00Z"));
        assertThat(((Timestamp) only.get("maxEndTime")).toInstant()).isEqualTo(Instant.parse("2026-01-01T10:00:02Z"));
        // 2 s + 3 s, summed per row rather than taken as the span between the extremes.
        assertThat(((Number) only.get("sumExecutionTimeMs")).longValue()).isEqualTo(5_000L);
        assertMatchesLegacyWholeRun();
    }

    @Test
    @DisplayName("a step still running has no end time and contributes no duration")
    void toleratesAMissingEndTime() {
        insertTimed("running", "RUNNING", 0, "2026-01-01 10:00:00+00", null);

        Map<String, Object> only = rawWholeRun().get(0);
        assertThat(only.get("maxEndTime")).isNull();
        assertThat(((Number) only.get("sumExecutionTimeMs")).longValue()).isZero();
        assertMatchesLegacyWholeRun();
    }

    @Test
    @DisplayName("the per-epoch query returns that epoch only, with its own spawn supersession")
    void perEpochIsScopedAndSupersedes() {
        insert("node", "COMPLETED", 0, 0, 0, 0);
        insert("node", "COMPLETED", 1, 0, 0, 0);
        insert("node", "SKIPPED", 1, 1, 0, 0);

        assertThat(forEpoch(0)).containsExactly(row("node", "COMPLETED", 1));
        assertThat(forEpoch(1)).containsExactly(row("node", "SKIPPED", 1));
        assertThat(forEpoch(2)).isEmpty();
        assertMatchesLegacyEpoch(0);
        assertMatchesLegacyEpoch(1);
        assertMatchesLegacyEpoch(2);
    }

    @Test
    @DisplayName("epoch 0 keeps its legacy NULL-epoch neighbours out of the answer but not out of the max")
    void perEpochTreatsNullEpochAsTheCorrelatedFormDid() {
        // The one place where the two predicates differ on purpose: the old outer filter was
        // `w.epoch = :epoch` (a NULL never satisfies it) while its subquery took the max over
        // COALESCE(epoch, 0) - so a NULL-epoch row could raise the max and suppress an epoch-0 row
        // without ever appearing itself. The rewrite has to reproduce both halves, which is why its
        // inner filter and its outer filter are written differently.
        insert("node", "COMPLETED", null, 0, 0, 0, TRIGGER);
        insert("node", "COMPLETED", 0, 0, 0, 0, TRIGGER);
        insert("node", "SKIPPED", null, 1, 0, 0, TRIGGER);

        assertThat(forEpoch(0)).isEmpty();
        assertMatchesLegacyEpoch(0);
    }

    @Test
    @DisplayName("neither query re-evaluates a subquery per row - that is what made the modal slow")
    void neitherQueryCarriesACorrelatedSubplan() {
        // The regression this change is about is a PLAN shape, and a wall-clock assertion would be
        // flaky on a shared runner. A correlated max shows up as "SubPlan" in the plan; the window
        // form has none. Asserting the legacy oracle DOES carry one keeps this from passing
        // vacuously (e.g. against a Postgres that decorrelates it on its own).
        for (int epoch = 0; epoch < 20; epoch++) {
            insert("node", "COMPLETED", epoch, 0, 0, 0);
        }

        assertThat(explain(bind(wholeRunSql, RUN, null))).doesNotContain("SubPlan");
        assertThat(explain(bind(epochSql, RUN, 0))).doesNotContain("SubPlan");
        assertThat(explain(bind(LEGACY_WHOLE_RUN_SQL, RUN, null)))
                .as("the legacy oracle no longer plans as a correlated subquery, so this case can no "
                        + "longer tell the two shapes apart - re-derive it before trusting it")
                .contains("SubPlan");
    }

    @Test
    @DisplayName("the epoch-range query answers, epoch by epoch, exactly what the per-epoch query answers")
    void rangeMatchesThePerEpochQueryEpochByEpoch() {
        insert("fetch", "COMPLETED", 1, 0, 0, 0);
        insert("fetch", "COMPLETED", 2, 0, 0, 0);
        insert("fetch", "SKIPPED", 2, 1, 0, 0);          // rerun of epoch 2 supersedes its COMPLETED
        insert("fetch", "COMPLETED", 3, 0, 0, 1);        // two items in epoch 3
        insert("fetch", "FAILED", 3, 0, 0, 0);
        insert("branch", "COMPLETED", 3, 0, 1, 0);
        insert("fetch", "COMPLETED", 4, 0, 0, 0);        // outside the range asked below

        List<Map<String, Object>> range = jdbc.queryForList(bindRange(rangeSql, 1, 3));

        for (int epoch = 1; epoch <= 3; epoch++) {
            final int e = epoch;
            List<Map<String, Object>> sliced = range.stream()
                    .filter(r -> ((Number) r.get("epoch")).intValue() == e)
                    .map(AggregatedStepsQueryPostgresTest::withoutRangeColumns)
                    .toList();
            assertThat(sliced)
                    .as("epoch %d of the range must equal the per-epoch answer", e)
                    .containsExactlyInAnyOrderElementsOf(jdbc.queryForList(bind(epochSql, RUN, e)));
        }
        assertThat(range).noneMatch(r -> ((Number) r.get("epoch")).intValue() == 4);
    }

    @Test
    @DisplayName("the epoch-range query treats legacy NULL-epoch rows as the per-epoch query does")
    void rangeTreatsNullEpochAsThePerEpochQueryDoes() {
        insert("node", "COMPLETED", null, 0, 0, 0, TRIGGER);
        insert("node", "COMPLETED", 0, 0, 0, 0, TRIGGER);
        insert("node", "SKIPPED", null, 1, 0, 0, TRIGGER);
        insert("node", "COMPLETED", 1, 0, 0, 0, TRIGGER);

        List<Map<String, Object>> range = jdbc.queryForList(bindRange(rangeSql, 0, 1));

        assertThat(range).as("a NULL epoch never appears as an epoch of its own")
                .allMatch(r -> r.get("epoch") != null);
        assertThat(range.stream().filter(r -> ((Number) r.get("epoch")).intValue() == 0).toList())
                .as("the NULL-epoch spawn-1 row supersedes epoch 0, exactly as in the per-epoch query")
                .isEmpty();
        assertThat(jdbc.queryForList(bind(epochSql, RUN, 0))).isEmpty();
        assertThat(range.stream().filter(r -> ((Number) r.get("epoch")).intValue() == 1).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("the epoch-range query carries a failed node's error message")
    void rangeCarriesTheErrorMessage() {
        jdbc.update("INSERT INTO workflow_step_data "
                        + "(run_id, step_alias, tool_id, status, start_time, end_time, epoch, spawn, iteration, item_index, trigger_id, error_message) "
                        + "VALUES (?, 'fetch', 'tool', 'FAILED', now(), now(), 5, 0, 0, 0, ?, 'HTTP 429')", RUN, TRIGGER);

        Map<String, Object> only = jdbc.queryForList(bindRange(rangeSql, 5, 5)).get(0);

        assertThat(only.get("errorMessage")).isEqualTo("HTTP 429");
        assertThat(only.get("status")).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("the epoch-range query carries no correlated subquery either")
    void rangeQueryCarriesNoSubplan() {
        for (int epoch = 0; epoch < 20; epoch++) {
            insert("node", "COMPLETED", epoch, 0, 0, 0);
        }

        assertThat(explain(bindRange(rangeSql, 1, 10))).doesNotContain("SubPlan");
    }

    @Test
    @DisplayName("elapsed: a loop body adds up its iterations, not the span across the whole loop")
    void elapsedOfALoopBodyIsTheSumOfItsIterations() {
        // A runs 1 s per iteration, B runs 1 s after it: A's first start to last end is 5 s,
        // but A itself only held the epoch 3 s.
        for (int it = 0; it < 3; it++) {
            timed("a", 1, it, 0, 0, TRIGGER, "10:00:0" + (2 * it), "10:00:0" + (2 * it + 1));
            timed("b", 1, it, 0, 0, TRIGGER, "10:00:0" + (2 * it + 1), "10:00:0" + (2 * it + 2));
        }

        assertThat(elapsed(1, 1)).containsEntry("1|a", 3_000L).containsEntry("1|b", 3_000L);
    }

    @Test
    @DisplayName("elapsed: parallel split items in one iteration count once (their span), not summed")
    void elapsedOfParallelItemsIsTheirSpan() {
        timed("split", 1, 0, 0, 0, TRIGGER, "10:00:00", "10:00:05");
        timed("split", 1, 0, 1, 0, TRIGGER, "10:00:01", "10:00:05");
        timed("split", 1, 0, 2, 0, TRIGGER, "10:00:00", "10:00:04");

        assertThat(elapsed(1, 1)).containsEntry("1|split", 5_000L);
    }

    @Test
    @DisplayName("elapsed: a split inside a loop is the span of each iteration's items, summed over iterations")
    void elapsedOfASplitInsideALoop() {
        timed("s", 1, 0, 0, 0, TRIGGER, "10:00:00", "10:00:02");
        timed("s", 1, 0, 1, 0, TRIGGER, "10:00:00", "10:00:03");
        timed("s", 1, 1, 0, 0, TRIGGER, "10:00:10", "10:00:12");
        timed("s", 1, 1, 1, 0, TRIGGER, "10:00:10", "10:00:11");

        assertThat(elapsed(1, 1)).containsEntry("1|s", 5_000L);
    }

    @Test
    @DisplayName("elapsed: a superseded spawn, a still-running row and other epochs do not count; two triggers add up")
    void elapsedIgnoresSupersededRunningAndOutOfRangeRows() {
        timed("n", 2, 0, 0, 0, TRIGGER, "10:00:00", "10:00:09");         // superseded by the rerun below
        timed("n", 2, 0, 0, 1, TRIGGER, "10:01:00", "10:01:02");         // rerun: 2 s
        timed("n", 2, 0, 1, 1, TRIGGER, "10:01:00", null);               // still running: nothing
        timed("n", 2, 0, 0, 0, "trigger:other", "10:02:00", "10:02:01"); // another trigger: +1 s
        timed("n", 3, 0, 0, 0, TRIGGER, "10:03:00", "10:03:30");         // outside the range

        Map<String, Long> result = elapsed(1, 2);
        assertThat(result).containsEntry("2|n", 3_000L);
        assertThat(result).doesNotContainKey("3|n");
    }

    @Test
    @DisplayName("elapsed: a rerun with fewer items or iterations is timed on its own rows, not stretched by the old attempt")
    void elapsedOfAShrunkRerunIgnoresTheOldAttempt() {
        // First attempt at 09:00: 4 items in iteration 0, plus iteration 1. The rerun at 10:00
        // (spawn 1) redoes items 0-1 of iteration 0 only. The old items 2-3 and the old iteration 1
        // are still the latest rows of THEIR coordinate, but they are not this attempt.
        for (int item = 0; item < 4; item++) {
            timed("split", 1, 0, item, 0, TRIGGER, "09:00:00", "09:00:05");
        }
        timed("split", 1, 1, 0, 0, TRIGGER, "09:00:10", "09:00:15");
        timed("split", 1, 0, 0, 1, TRIGGER, "10:00:00", "10:00:03");
        timed("split", 1, 0, 1, 1, TRIGGER, "10:00:00", "10:00:02");

        assertThat(elapsed(1, 1)).containsEntry("1|split", 3_000L);
    }

    @Test
    @DisplayName("elapsed: legacy NULL-epoch rows read as epoch 0, and never leak into epochs 1 and up")
    void elapsedTreatsNullEpochAsEpochZero() {
        jdbc.update("INSERT INTO workflow_step_data "
                        + "(run_id, step_alias, tool_id, status, start_time, end_time, epoch, spawn, iteration, item_index, trigger_id) "
                        + "VALUES (?, 'legacy', 'tool', 'COMPLETED', '2026-01-01 10:00:00+00'::timestamptz, '2026-01-01 10:00:04+00'::timestamptz, NULL, 0, 0, 0, ?)",
                RUN, TRIGGER);
        timed("legacy", 1, 0, 0, 0, TRIGGER, "10:01:00", "10:01:01");

        // A NULL epoch is never an epoch of its own, so it is filtered out of the answer...
        assertThat(elapsed(0, 1)).doesNotContainKey("0|legacy").doesNotContainKey("null|legacy").containsEntry("1|legacy", 1_000L);
        // ...and a range starting at 1 does not read it at all.
        assertThat(elapsed(1, 1)).containsEntry("1|legacy", 1_000L);
    }

    @Test
    @DisplayName("elapsed: a node whose only row is still running is absent (no figure, never a zero)")
    void elapsedOfAnUntimedNodeIsAbsent() {
        timed("running", 1, 0, 0, 0, TRIGGER, "10:00:00", null);

        assertThat(elapsed(1, 1)).doesNotContainKey("1|running");
    }

    // -- helpers --

    /** "epoch|alias" -> elapsedMs of the shipped elapsed query. */
    private Map<String, Long> elapsed(int fromEpoch, int toEpoch) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(bindRange(elapsedSql, fromEpoch, toEpoch))) {
            out.put(r.get("epoch") + "|" + r.get("stepAlias"), ((Number) r.get("elapsedMs")).longValue());
        }
        return out;
    }

    private void timed(String alias, int epoch, int iteration, int item, int spawn, String triggerId, String start, String end) {
        jdbc.update("INSERT INTO workflow_step_data "
                        + "(run_id, step_alias, tool_id, status, start_time, end_time, epoch, spawn, iteration, item_index, trigger_id) "
                        + "VALUES (?, ?, 'tool', 'COMPLETED', ?::timestamptz, ?::timestamptz, ?, ?, ?, ?, ?)",
                RUN, alias, "2026-01-01 " + start + "+00", end == null ? null : "2026-01-01 " + end + "+00",
                epoch, spawn, iteration, item, triggerId);
    }

    private static String bindRange(String sql, int fromEpoch, int toEpoch) {
        return sql.replace(":runId", "'" + RUN + "'")
                .replace(":fromEpoch", String.valueOf(fromEpoch))
                .replace(":toEpoch", String.valueOf(toEpoch));
    }

    /** A range row minus the two columns the per-epoch query does not select. */
    private static Map<String, Object> withoutRangeColumns(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.remove("epoch");
        copy.remove("errorMessage");
        return copy;
    }

    /** Rows of the SHIPPED whole-run query, as (alias, status, count) for readable assertions. */
    private List<Map<String, Object>> wholeRun() {
        return project(rawWholeRun());
    }

    private List<Map<String, Object>> rawWholeRun() {
        return jdbc.queryForList(bind(wholeRunSql, RUN, null));
    }

    private List<Map<String, Object>> forEpoch(int epoch) {
        return project(jdbc.queryForList(bind(epochSql, RUN, epoch)));
    }

    /** The shipped whole-run query must answer exactly what the pre-rewrite one answered. */
    private void assertMatchesLegacyWholeRun() {
        assertThat(rawWholeRun())
                .as("the window rewrite must be a pure one - same rows as the correlated form")
                .containsExactlyInAnyOrderElementsOf(jdbc.queryForList(bind(LEGACY_WHOLE_RUN_SQL, RUN, null)));
    }

    private void assertMatchesLegacyEpoch(int epoch) {
        assertThat(jdbc.queryForList(bind(epochSql, RUN, epoch)))
                .as("epoch %d: the window rewrite must be a pure one", epoch)
                .containsExactlyInAnyOrderElementsOf(jdbc.queryForList(bind(LEGACY_EPOCH_SQL, RUN, epoch)));
    }

    private static List<Map<String, Object>> project(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            out.add(row((String) r.get("stepAlias"), (String) r.get("status"), ((Number) r.get("count")).intValue()));
        }
        return out;
    }

    private static Map<String, Object> row(String alias, String status, int count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stepAlias", alias);
        m.put("status", status);
        m.put("count", count);
        return m;
    }

    private String explain(String sql) {
        return String.join("\n", jdbc.queryForList("EXPLAIN (COSTS OFF) " + sql, String.class));
    }

    /**
     * Inlines the two named parameters as literals. Positional binding would depend on the order the
     * parameters happen to appear in each query's text, which is exactly the kind of coupling this
     * test should not have to a query it reads at runtime.
     */
    private static String bind(String sql, String runId, Integer epoch) {
        String bound = sql.replace(":runId", "'" + runId.replace("'", "''") + "'");
        return epoch == null ? bound : bound.replace(":epoch", String.valueOf(epoch));
    }

    private void insert(String alias, String status, Integer epoch, Integer spawn, Integer iteration, Integer itemIndex) {
        insert(alias, status, epoch, spawn, iteration, itemIndex, TRIGGER);
    }

    private void insert(String alias, String status, Integer epoch, Integer spawn, Integer iteration,
                        Integer itemIndex, String triggerId) {
        jdbc.update("INSERT INTO workflow_step_data "
                        + "(run_id, step_alias, tool_id, status, start_time, end_time, epoch, spawn, iteration, item_index, trigger_id) "
                        + "VALUES (?, ?, ?, ?, now(), now() + interval '1 second', ?, ?, ?, ?, ?)",
                RUN, alias, "tool-" + alias, status, epoch, spawn, iteration, itemIndex, triggerId);
    }

    private void insertTimed(String alias, String status, int epoch, String startTime, String endTime) {
        jdbc.update("INSERT INTO workflow_step_data "
                        + "(run_id, step_alias, tool_id, status, start_time, end_time, epoch, spawn, iteration, item_index, trigger_id) "
                        + "VALUES (?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?, 0, 0, 0, ?)",
                RUN, alias, "tool-" + alias, status, startTime, endTime, epoch, TRIGGER);
    }

    /**
     * The SQL as the application ships it, read off the repository method's {@code @Query}. Copying
     * the query into this file would let it pass against a string nothing runs.
     */
    private static String shippedSql(String method, Class<?>... params) throws NoSuchMethodException {
        Query query = WorkflowStepDataRepository.class.getMethod(method, params).getAnnotation(Query.class);
        if (query == null || !query.nativeQuery()) {
            throw new IllegalStateException(
                    method + " no longer carries a native @Query. If the aggregation moved, move this "
                            + "test with it - it is the only place this SQL runs against a real engine.");
        }
        return query.value();
    }

    private static void requireDatabaseOnCi() {
        if (URL != null && !URL.isBlank()) {
            return;
        }
        boolean onCi = System.getenv("CI") != null && !System.getenv("CI").isBlank();
        if (onCi) {
            throw new IllegalStateException(
                    "ORCHESTRATOR_TEST_PG_URL is unset on CI. This class must execute there: it is the "
                            + "only test that runs the aggregated-steps SQL against a real engine, and "
                            + "its subject - spawn supersession - fails as a plausible wrong answer, not "
                            + "an error. Restore the env block on the workflow step that runs it, and "
                            + "keep that step in a job carrying the postgres service.");
        }
        Assumptions.abort(
                "no scratch Postgres: set ORCHESTRATOR_TEST_PG_URL to run this locally "
                        + "(CI always sets it)");
    }

    private static void awaitDatabase() {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try (Connection ignored = DriverManager.getConnection(URL, USER, PASSWORD)) {
                return;
            } catch (Exception e) {
                last = new IllegalStateException(
                        "ORCHESTRATOR_TEST_PG_URL is set but the database is unreachable: " + URL, e);
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
        throw last;
    }
}
