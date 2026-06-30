package com.apimarketplace.orchestrator.services.state.patch;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.services.state.elide.TenantElideFlagResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test: validate that the SQL composed by
 * {@link JsonbPatchExecutor} runs on a real Postgres instance and produces
 * the expected JSONB output for each of the 13 wired patch builders.
 *
 * <p>Why: the unit tests mock {@link jakarta.persistence.EntityManager} and
 * verify the SQL string shape; they do NOT prove that the {@code jsonb_set}
 * cascade composes a valid Postgres expression that yields the intended
 * snapshot. Two latent bugs were found and fixed during E2E earlier (Hibernate
 * named-parameter parser misreading {@code :p::text[]} as {@code p::text}, and
 * the {@code state_snapshot} column being TEXT not jsonb so we must
 * {@code CAST(... AS jsonb)} in/out). This test guards against future
 * regressions of either class on every CI run.
 *
 * <p>Setup: a single Postgres 16 container is shared across the test class.
 * A minimal {@code orchestrator.workflow_runs} schema is created (only the
 * columns the patch path touches: {@code run_id_public}, {@code state_snapshot}).
 * Each test inserts a known {@code before} snapshot, runs the builder to
 * produce patches, applies them via the same SQL JsonbPatchExecutor would
 * (named-param NamedParameterJdbcTemplate, CAST in/out), reads the row back,
 * parses the JSON, and asserts the post-state.
 *
 * <p>Run requirements: Docker available locally and in CI. Skipped silently
 * (Testcontainers throws) on environments without Docker - the unit-test
 * suite still validates the same builders without DB.
 */
@Testcontainers
class JsonbPatchPostgresIT {

    private static final String TRIGGER = "trigger:webhook";   // colon in trigger key - defensive coverage
    private static final int EPOCH = 5;
    private static final String NODE = "n1";
    private static final String TENANT = "tenant-a";

    @Container
    @SuppressWarnings("resource") // shared across all tests, closed by Testcontainers framework
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static NamedParameterJdbcTemplate jdbc;
    static ObjectMapper mapper;

    private final TenantElideFlagResolver elideOff = t -> false;
    private final TenantElideFlagResolver elideOn = t -> true;

    @BeforeAll
    static void setUpClass() {
        // Skip the entire IT class cleanly when Docker is not available
        // (e.g. local dev without Docker, certain CI runners). The unit-test
        // suite still validates the same builders without DB.
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available - JsonbPatchPostgresIT skipped (unit tests still cover the builders)");

        DataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new NamedParameterJdbcTemplate(ds);
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        // Minimal schema - only the columns the patch path needs. Real prod has
        // ~30 columns but jsonb_set only touches state_snapshot.
        jdbc.getJdbcTemplate().execute("CREATE SCHEMA orchestrator");
        jdbc.getJdbcTemplate().execute(
                "CREATE TABLE orchestrator.workflow_runs ("
                        + "id BIGSERIAL PRIMARY KEY, "
                        + "run_id_public TEXT UNIQUE NOT NULL, "
                        + "tenant_id TEXT, "
                        + "state_snapshot TEXT)");
    }

    @BeforeEach
    void cleanRows() {
        jdbc.getJdbcTemplate().execute("TRUNCATE orchestrator.workflow_runs RESTART IDENTITY");
    }

    // ------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------

    /** Inserts a row with {@code before} serialized as JSON. Returns the runIdPublic used. */
    private String insertRun(StateSnapshot before) throws Exception {
        String runId = "run-test-" + System.nanoTime();
        jdbc.update("INSERT INTO orchestrator.workflow_runs (run_id_public, tenant_id, state_snapshot) "
                        + "VALUES (:r, :t, :s)",
                new MapSqlParameterSource()
                        .addValue("r", runId)
                        .addValue("t", TENANT)
                        .addValue("s", mapper.writeValueAsString(before)));
        return runId;
    }

    /**
     * Apply patches via the SAME SQL composition as
     * {@link JsonbPatchExecutor#applyPatches(String, List)}. Calls the
     * package-private static {@code JsonbPatchExecutor.composeUpdateSql(...)}
     * helper directly - single source of truth, eliminates silent drift if
     * the executor evolves.
     *
     * <p>Spring {@link NamedParameterJdbcTemplate} parses the same {@code :name}
     * syntax as Hibernate's named-parameter parser, so the SQL goes through
     * an equivalent text→positional substitution.
     */
    private void applyPatches(String runId, List<JsonbPatch> patches) {
        String sql = JsonbPatchExecutor.composeUpdateSql(patches.size());

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("runIdPublic", runId);
        for (int i = 0; i < patches.size(); i++) {
            params.addValue("p" + i, patches.get(i).toPostgresArrayLiteral());
            params.addValue("v" + i, patches.get(i).jsonValue());
        }
        int updated = jdbc.update(sql, params);
        assertThat(updated).as("UPDATE should affect exactly one row").isEqualTo(1);
    }

    /** Reads the state_snapshot text back and parses it as a {@link StateSnapshot}. */
    private StateSnapshot readBack(String runId) throws Exception {
        String json = jdbc.queryForObject(
                "SELECT state_snapshot FROM orchestrator.workflow_runs WHERE run_id_public = :r",
                new MapSqlParameterSource("r", runId), String.class);
        assertThat(json).as("state_snapshot must not be null after patch").isNotNull();
        return mapper.readValue(json, StateSnapshot.class);
    }

    /** Reads the raw state_snapshot text (for byte-level / shape assertions). */
    private String readBackRaw(String runId) {
        return jdbc.queryForObject(
                "SELECT state_snapshot FROM orchestrator.workflow_runs WHERE run_id_public = :r",
                new MapSqlParameterSource("r", runId), String.class);
    }

    private StateSnapshot withNodeRunning() {
        return StateSnapshot.empty()
                .ensureDagInitialized(TRIGGER, EPOCH)
                .addRunningNode(TRIGGER, NODE, EPOCH);
    }

    // ------------------------------------------------------------
    // Per-builder integration tests
    // ------------------------------------------------------------

    @Test
    @DisplayName("MarkNodeCompleted (élide OFF): 5 patches → completed set + NodeCounts.completed=1")
    void markNodeCompleted_appliesOnRealPostgres() throws Exception {
        var builder = new MarkNodeCompletedPatchBuilder(mapper, elideOff);
        StateSnapshot before = withNodeRunning();
        StateSnapshot after = before.markNodeCompleted(TRIGGER, NODE, EPOCH).withIncrementedSeq();
        var result = builder.build(before, after, TRIGGER, EPOCH, NODE, TENANT);

        assertThat(result.isPatch()).isTrue();
        String runId = insertRun(before);
        applyPatches(runId, result.patches().orElseThrow());

        StateSnapshot loaded = readBack(runId);
        assertThat(loaded.getDagState(TRIGGER).getEpochState(EPOCH).getCompletedNodeIds())
                .contains(NODE);
        assertThat(loaded.getDagState(TRIGGER).getEpochState(EPOCH).getRunningNodeIds())
                .doesNotContain(NODE);
        assertThat(loaded.getNodes().get(NODE).completed()).isEqualTo(1);
        assertThat(loaded.getSeq()).isEqualTo(after.getSeq());
    }

    @Test
    @DisplayName("MarkNodeCompleted (élide ON): patch list omits runningNodeIds (4 patches vs 5 OFF)")
    void markNodeCompleted_elideOn_omitsRunningNodeIdsPatch() throws Exception {
        var builderOff = new MarkNodeCompletedPatchBuilder(mapper, elideOff);
        var builderOn = new MarkNodeCompletedPatchBuilder(mapper, elideOn);
        StateSnapshot before = withNodeRunning();
        StateSnapshot after = before.markNodeCompleted(TRIGGER, NODE, EPOCH).withIncrementedSeq();

        // élide-OFF: 5 patches (seq + completed + ready + running + nodeCounts)
        var resultOff = builderOff.build(before, after, TRIGGER, EPOCH, NODE, TENANT);
        assertThat(resultOff.patches().orElseThrow()).hasSize(5);
        assertThat(resultOff.patches().get())
                .anyMatch(p -> p.path()[p.path().length - 1].equals(PatchPaths.RUNNING_NODE_IDS));

        // élide-ON: 4 patches (no runningNodeIds patch - symmetric with the
        // EpochStateRunningElideSerializer on the full-rewrite path)
        var resultOn = builderOn.build(before, after, TRIGGER, EPOCH, NODE, TENANT);
        assertThat(resultOn.patches().orElseThrow()).hasSize(4);
        assertThat(resultOn.patches().get())
                .noneMatch(p -> p.path()[p.path().length - 1].equals(PatchPaths.RUNNING_NODE_IDS));

        // The (smaller) élide-ON patch list still applies cleanly on real Postgres
        String runId = insertRun(before);
        applyPatches(runId, resultOn.patches().orElseThrow());
        StateSnapshot loaded = readBack(runId);
        assertThat(loaded.getDagState(TRIGGER).getEpochState(EPOCH).getCompletedNodeIds())
                .contains(NODE);
        assertThat(loaded.getNodes().get(NODE).completed()).isEqualTo(1);
    }

    @Test
    @DisplayName("MarkNodeFailed: failed set populated + counts updated")
    void markNodeFailed_appliesOnRealPostgres() throws Exception {
        var builder = new MarkNodeFailedPatchBuilder(mapper, elideOff);
        StateSnapshot before = withNodeRunning();
        StateSnapshot after = before.markNodeFailed(TRIGGER, NODE, EPOCH).withIncrementedSeq();
        var result = builder.build(before, after, TRIGGER, EPOCH, NODE, TENANT);

        String runId = insertRun(before);
        applyPatches(runId, result.patches().orElseThrow());

        StateSnapshot loaded = readBack(runId);
        assertThat(loaded.getDagState(TRIGGER).getEpochState(EPOCH).getFailedNodeIds())
                .contains(NODE);
        assertThat(loaded.getNodes().get(NODE).failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("MarkNodeAwaitingSignal (set-pure, no NodeCounts touch)")
    void markNodeAwaitingSignal_appliesOnRealPostgres() throws Exception {
        var builder = new MarkNodeAwaitingSignalPatchBuilder(mapper, elideOff);
        StateSnapshot before = withNodeRunning();
        StateSnapshot after = before.markNodeAwaitingSignal(TRIGGER, NODE, EPOCH).withIncrementedSeq();
        var result = builder.build(before, after, TRIGGER, EPOCH, NODE, TENANT);

        String runId = insertRun(before);
        applyPatches(runId, result.patches().orElseThrow());

        StateSnapshot loaded = readBack(runId);
        assertThat(loaded.getDagState(TRIGGER).getEpochState(EPOCH).getAwaitingSignalNodeIds())
                .contains(NODE);
        // No NodeCounts mutation
        assertThat(loaded.getNodes()).doesNotContainKey(NODE);
    }

    @Test
    @DisplayName("ResolveAwaitingSignal: awaiting → completed + counts.completed bump")
    void resolveAwaitingSignal_appliesOnRealPostgres() throws Exception {
        var builder = new ResolveAwaitingSignalPatchBuilder(mapper);
        StateSnapshot before = withNodeRunning().markNodeAwaitingSignal(TRIGGER, NODE, EPOCH);
        StateSnapshot after = before.resolveAwaitingSignal(TRIGGER, NODE, EPOCH).withIncrementedSeq();
        var result = builder.build(before, after, TRIGGER, EPOCH, NODE);

        String runId = insertRun(before);
        applyPatches(runId, result.patches().orElseThrow());

        StateSnapshot loaded = readBack(runId);
        assertThat(loaded.getDagState(TRIGGER).getEpochState(EPOCH).getAwaitingSignalNodeIds())
                .doesNotContain(NODE);
        assertThat(loaded.getDagState(TRIGGER).getEpochState(EPOCH).getCompletedNodeIds())
                .contains(NODE);
        assertThat(loaded.getNodes().get(NODE).completed()).isEqualTo(1);
    }

    @Test
    @DisplayName("MarkNodeCompletedEpochOnly: set populated, NodeCounts UNCHANGED (split-async barrier)")
    void markNodeCompletedEpochOnly_appliesOnRealPostgres() throws Exception {
        var builder = new MarkNodeCompletedEpochOnlyPatchBuilder(mapper, elideOff);
        StateSnapshot before = withNodeRunning();
        StateSnapshot after = before.markNodeCompletedEpochOnly(TRIGGER, NODE, EPOCH).withIncrementedSeq();
        var result = builder.build(before, after, TRIGGER, EPOCH, NODE, TENANT);

        String runId = insertRun(before);
        applyPatches(runId, result.patches().orElseThrow());

        StateSnapshot loaded = readBack(runId);
        assertThat(loaded.getDagState(TRIGGER).getEpochState(EPOCH).getCompletedNodeIds())
                .contains(NODE);
        // Critical: NO NodeCounts entry for NODE (split-async barrier seal does NOT bump counts -
        // per-item incrementNodeCountsOnly already did).
        assertThat(loaded.getNodes()).doesNotContainKey(NODE);
    }

    @Test
    @DisplayName("MarkNodePartialFailure: only +partialFailedNodeIds, no other set/counts touch")
    void markNodePartialFailure_appliesOnRealPostgres() throws Exception {
        var builder = new MarkNodePartialFailurePatchBuilder(mapper);
        StateSnapshot before = withNodeRunning();
        StateSnapshot after = before.markNodePartialFailure(TRIGGER, NODE, EPOCH).withIncrementedSeq();
        var result = builder.build(before, after, TRIGGER, EPOCH, NODE);

        String runId = insertRun(before);
        applyPatches(runId, result.patches().orElseThrow());

        StateSnapshot loaded = readBack(runId);
        assertThat(loaded.getDagState(TRIGGER).getEpochState(EPOCH).getPartialFailedNodeIds())
                .contains(NODE);
        assertThat(loaded.getNodes()).doesNotContainKey(NODE);
    }

    @Test
    @DisplayName("IncrementNodeCountsOnly: counts updated, no EpochState touch")
    void incrementNodeCountsOnly_appliesOnRealPostgres() throws Exception {
        var builder = new IncrementNodeCountsOnlyPatchBuilder(mapper);
        // Path-init prerequisite: nodes Map must already have NODE
        StateSnapshot before = withNodeRunning().incrementNodeCountsOnly(NODE, "COMPLETED", 3);
        StateSnapshot after = before.incrementNodeCountsOnly(NODE, "COMPLETED", 2).withIncrementedSeq();
        var result = builder.build(before, after, NODE);

        String runId = insertRun(before);
        applyPatches(runId, result.patches().orElseThrow());

        StateSnapshot loaded = readBack(runId);
        assertThat(loaded.getNodes().get(NODE).completed()).isEqualTo(5); // 3 + 2
    }

    @Test
    @DisplayName("AddReadyNode: readyNodeIds populated")
    void addReadyNode_appliesOnRealPostgres() throws Exception {
        var builder = new AddReadyNodePatchBuilder(mapper);
        StateSnapshot before = withNodeRunning();
        StateSnapshot after = before.addReadyNode(TRIGGER, NODE, EPOCH).withIncrementedSeq();
        var result = builder.build(before, after, TRIGGER, EPOCH, NODE);

        String runId = insertRun(before);
        applyPatches(runId, result.patches().orElseThrow());

        StateSnapshot loaded = readBack(runId);
        assertThat(loaded.getDagState(TRIGGER).getEpochState(EPOCH).getReadyNodeIds())
                .contains(NODE);
    }

    @Test
    @DisplayName("RemoveReadyNode: readyNodeIds set has nodeId removed")
    void removeReadyNode_appliesOnRealPostgres() throws Exception {
        var builder = new RemoveReadyNodePatchBuilder(mapper);
        StateSnapshot before = withNodeRunning().addReadyNode(TRIGGER, NODE, EPOCH);
        StateSnapshot after = before.removeReadyNode(TRIGGER, NODE, EPOCH).withIncrementedSeq();
        var result = builder.build(before, after, TRIGGER, EPOCH, NODE);

        String runId = insertRun(before);
        applyPatches(runId, result.patches().orElseThrow());

        StateSnapshot loaded = readBack(runId);
        assertThat(loaded.getDagState(TRIGGER).getEpochState(EPOCH).getReadyNodeIds())
                .doesNotContain(NODE);
    }

    @Test
    @DisplayName("RecordEdgeStatus: edges Map updated at edges[from->to]")
    void recordEdgeStatus_appliesOnRealPostgres() throws Exception {
        var builder = new RecordEdgeStatusPatchBuilder(mapper);
        StateSnapshot before = StateSnapshot.empty().incrementEdge("a", "b", "COMPLETED");
        StateSnapshot after = before.incrementEdge("a", "b", "COMPLETED").withIncrementedSeq();
        var result = builder.build(before, after, "a", "b");

        String runId = insertRun(before);
        applyPatches(runId, result.patches().orElseThrow());

        StateSnapshot loaded = readBack(runId);
        StateSnapshot.EdgeCounts edge = loaded.getEdges().get("a->b");
        assertThat(edge).isNotNull();
        assertThat(edge.completed()).isEqualTo(2); // 1 + 1
    }

    @Test
    @DisplayName("RecordDecisionBranch: whole decisionBranches Map replaced, contains nodeId→{branch}")
    void recordDecisionBranch_appliesOnRealPostgres() throws Exception {
        var builder = new RecordDecisionBranchPatchBuilder(mapper);
        StateSnapshot before = withNodeRunning(); // epoch initialized
        StateSnapshot after = before.recordDecisionBranch(TRIGGER, NODE, EPOCH, "if").withIncrementedSeq();
        var result = builder.build(before, after, TRIGGER, EPOCH, NODE, "if");

        String runId = insertRun(before);
        applyPatches(runId, result.patches().orElseThrow());

        StateSnapshot loaded = readBack(runId);
        var branches = loaded.getDagState(TRIGGER).getEpochState(EPOCH).getDecisionBranchesMap();
        assertThat(branches).containsKey(NODE);
        assertThat(branches.get(NODE)).contains("if");
    }

    @Test
    @DisplayName("Multi-patch chained in same run (mimics same-tx 2 mutators): both effects persist")
    void chainedPatches_bothPersist() throws Exception {
        // 1st mutator: markNodeCompleted (3 sets + counts)
        var b1 = new MarkNodeCompletedPatchBuilder(mapper, elideOff);
        StateSnapshot before = withNodeRunning();
        StateSnapshot afterStep1 = before.markNodeCompleted(TRIGGER, NODE, EPOCH).withIncrementedSeq();
        var r1 = b1.build(before, afterStep1, TRIGGER, EPOCH, NODE, TENANT);

        String runId = insertRun(before);
        applyPatches(runId, r1.patches().orElseThrow());

        // 2nd mutator: addReadyNode (different node - readyNodeIds add)
        var b2 = new AddReadyNodePatchBuilder(mapper);
        StateSnapshot afterStep2In = afterStep1; // we replay state from in-memory chain
        StateSnapshot afterStep2 = afterStep2In.addReadyNode(TRIGGER, "n_other", EPOCH).withIncrementedSeq();
        var r2 = b2.build(afterStep2In, afterStep2, TRIGGER, EPOCH, "n_other");
        applyPatches(runId, r2.patches().orElseThrow());

        StateSnapshot loaded = readBack(runId);
        // Both mutations land:
        assertThat(loaded.getDagState(TRIGGER).getEpochState(EPOCH).getCompletedNodeIds())
                .contains(NODE);
        assertThat(loaded.getDagState(TRIGGER).getEpochState(EPOCH).getReadyNodeIds())
                .contains("n_other");
        assertThat(loaded.getSeq()).isEqualTo(afterStep2.getSeq());
    }

    @Test
    @DisplayName("MarkNodeFailedEpochOnly: failed set populated, NodeCounts UNCHANGED (split-async barrier failure)")
    void markNodeFailedEpochOnly_appliesOnRealPostgres() throws Exception {
        var builder = new MarkNodeFailedEpochOnlyPatchBuilder(mapper, elideOff);
        StateSnapshot before = withNodeRunning();
        StateSnapshot after = before.markNodeFailedEpochOnly(TRIGGER, NODE, EPOCH).withIncrementedSeq();
        var result = builder.build(before, after, TRIGGER, EPOCH, NODE, TENANT);

        String runId = insertRun(before);
        applyPatches(runId, result.patches().orElseThrow());

        StateSnapshot loaded = readBack(runId);
        assertThat(loaded.getDagState(TRIGGER).getEpochState(EPOCH).getFailedNodeIds())
                .contains(NODE);
        // Critical: NO NodeCounts entry for NODE - epoch-only failure does NOT bump counts
        assertThat(loaded.getNodes()).doesNotContainKey(NODE);
    }

    @Test
    @DisplayName("MarkNodeSkipped: -readyNodeIds, +skippedNodeIds, NodeCounts.skipped++ (no running touch)")
    void markNodeSkipped_appliesOnRealPostgres() throws Exception {
        var builder = new MarkNodeSkippedPatchBuilder(mapper);
        // markNodeSkipped reads from readyNodeIds and bumps counts.skipped - set up that prereq
        StateSnapshot before = withNodeRunning().addReadyNode(TRIGGER, NODE, EPOCH);
        StateSnapshot after = before.markNodeSkipped(TRIGGER, NODE, EPOCH).withIncrementedSeq();
        var result = builder.build(before, after, TRIGGER, EPOCH, NODE);

        String runId = insertRun(before);
        applyPatches(runId, result.patches().orElseThrow());

        StateSnapshot loaded = readBack(runId);
        assertThat(loaded.getDagState(TRIGGER).getEpochState(EPOCH).getSkippedNodeIds())
                .contains(NODE);
        assertThat(loaded.getDagState(TRIGGER).getEpochState(EPOCH).getReadyNodeIds())
                .doesNotContain(NODE);
        // markNodeSkipped does NOT touch runningNodeIds - node remains in running set
        assertThat(loaded.getDagState(TRIGGER).getEpochState(EPOCH).getRunningNodeIds())
                .contains(NODE);
        assertThat(loaded.getNodes().get(NODE).skipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("composeUpdateSql contract: helper used in test == executor SQL (single-source guard)")
    void composeUpdateSql_singleSourceOfTruth() {
        // Direct call to the production static helper. If JsonbPatchExecutor's
        // SQL evolves, this test asserts the shape we depend on.
        String sqlFor1 = JsonbPatchExecutor.composeUpdateSql(1);
        assertThat(sqlFor1).contains("UPDATE orchestrator.workflow_runs");
        assertThat(sqlFor1).contains("CAST(state_snapshot AS jsonb)");
        assertThat(sqlFor1).contains("CAST(:p0 AS text[])");
        assertThat(sqlFor1).contains("CAST(:v0 AS jsonb)");
        assertThat(sqlFor1).contains("WHERE run_id_public = :runIdPublic");
        assertThat(sqlFor1).contains("AS text)");

        String sqlFor3 = JsonbPatchExecutor.composeUpdateSql(3);
        // Three nested jsonb_set
        int wraps = (sqlFor3.length() - sqlFor3.replace("jsonb_set(", "").length()) / "jsonb_set(".length();
        assertThat(wraps).isEqualTo(3);
        // Innermost = p0 (right after CAST(state_snapshot AS jsonb))
        assertThat(sqlFor3).contains("jsonb_set(CAST(state_snapshot AS jsonb), CAST(:p0 AS text[])");
    }

    @Test
    @DisplayName("Trigger key with colon (`trigger:webhook`): path accepts colon on Postgres text[]")
    void colonInTriggerKey_realPostgresAcceptsPath() throws Exception {
        // The `:` in "trigger:webhook" is the canonical case - this IT validates that
        // Postgres accepts the resulting text[] literal containing colons. The Hibernate
        // `::TYPE` parser bug (which prompted the CAST(...) refactor in JsonbPatchExecutor)
        // is guarded at a different layer - by the composeUpdateSql helper which uses
        // CAST(...) exclusively, exercised here via the same shared method.
        var builder = new MarkNodeCompletedPatchBuilder(mapper, elideOff);
        StateSnapshot before = withNodeRunning();
        StateSnapshot after = before.markNodeCompleted(TRIGGER, NODE, EPOCH).withIncrementedSeq();
        var result = builder.build(before, after, TRIGGER, EPOCH, NODE, TENANT);

        // Inspect the path literal - must contain "trigger:webhook" as an opaque key
        String pathLiteral = result.patches().orElseThrow().get(1).toPostgresArrayLiteral();
        assertThat(pathLiteral).contains("\"trigger:webhook\"");

        // And the actual UPDATE must succeed (i.e. Postgres parses the text[] literal)
        String runId = insertRun(before);
        applyPatches(runId, result.patches().orElseThrow());
        StateSnapshot loaded = readBack(runId);
        assertThat(loaded.getDagState(TRIGGER).getEpochState(EPOCH).getCompletedNodeIds())
                .contains(NODE);
    }
}
