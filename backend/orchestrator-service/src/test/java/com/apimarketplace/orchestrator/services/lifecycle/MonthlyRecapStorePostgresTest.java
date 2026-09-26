package com.apimarketplace.orchestrator.services.lifecycle;

import com.apimarketplace.testsupport.ScratchPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The recap's selection and its once-per-month ledger are SQL (a jsonb success predicate, a
 * month window, an ON CONFLICT claim), so they run on a real Postgres. The ledger's DDL is read
 * from V529 itself; the run tables carry only the columns the query reads.
 */
@DisplayName("MonthlyRecapStore (real Postgres)")
class MonthlyRecapStorePostgresTest {

    private static final ScratchPostgres DB = ScratchPostgres.forPrefix(
            "ORCHESTRATOR_TEST_PG",
            "it is the only executable proof that the monthly recap picks the right people and "
                    + "is sent once per person and month, across passes and pods");

    private static final YearMonth AUGUST = YearMonth.of(2026, 8);
    private static final String SUCCESS = "{\"failedNodeIds\":[],\"completedNodeIds\":[\"trigger:t\",\"mcp:send\"]}";
    private static final String FAILED = "{\"failedNodeIds\":[\"mcp:send\"],\"completedNodeIds\":[\"trigger:t\"]}";
    private static final String TRIGGER_ONLY = "{\"failedNodeIds\":[],\"completedNodeIds\":[\"trigger:t\"]}";

    static JdbcTemplate jdbc;
    private MonthlyRecapStore store;

    @BeforeAll
    static void schema() throws Exception {
        DB.require();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(DB.url(), DB.user(), DB.password()));
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS orchestrator");
        jdbc.execute("DROP TABLE IF EXISTS orchestrator.lifecycle_monthly_recaps, orchestrator.workflow_epochs, "
                + "orchestrator.workflow_runs, orchestrator.user_badges CASCADE");
        jdbc.execute("CREATE TABLE orchestrator.workflow_runs (id UUID PRIMARY KEY, workflow_id UUID NOT NULL, "
                + "tenant_id VARCHAR(255) NOT NULL, run_id_public VARCHAR(255) NOT NULL, status VARCHAR(32) NOT NULL, "
                + "ended_at TIMESTAMPTZ)");
        jdbc.execute("CREATE TABLE orchestrator.workflow_epochs (id BIGSERIAL PRIMARY KEY, run_id VARCHAR(255) NOT NULL, "
                + "entry_type VARCHAR(20) NOT NULL, epoch_state JSONB, is_active BOOLEAN DEFAULT TRUE, started_at TIMESTAMPTZ)");
        jdbc.execute("CREATE TABLE orchestrator.user_badges (tenant_id VARCHAR(255) NOT NULL, badge_code VARCHAR(64) NOT NULL, "
                + "unlocked_at TIMESTAMPTZ NOT NULL)");
        String v529 = Files.readString(Path.of("..", "migration-service", "src", "main", "resources", "db", "migration",
                "V529__lifecycle_trophies_recap_checkout_throttle.sql"), StandardCharsets.UTF_8)
                .replaceAll("(?m)^\\s*--.*$", "");
        String ddl = null;
        for (String s : v529.split(";")) {
            if (s.contains("CREATE TABLE IF NOT EXISTS orchestrator.lifecycle_monthly_recaps")) ddl = s.trim();
        }
        assertThat(ddl).as("V529 declares orchestrator.lifecycle_monthly_recaps").isNotNull();
        jdbc.execute(ddl);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE orchestrator.lifecycle_monthly_recaps, orchestrator.workflow_epochs, "
                + "orchestrator.workflow_runs, orchestrator.user_badges RESTART IDENTITY");
        store = new MonthlyRecapStore(jdbc);
    }

    /** A reusable-trigger run: one row forever, never COMPLETED, one epoch per fire. */
    private String triggerRun(String tenant, UUID workflow) {
        String publicId = "run-" + UUID.randomUUID();
        jdbc.update("INSERT INTO orchestrator.workflow_runs (id, workflow_id, tenant_id, run_id_public, status) "
                + "VALUES (?, ?, ?, ?, 'WAITING_TRIGGER')", UUID.randomUUID(), workflow, tenant, publicId);
        return publicId;
    }

    private void epoch(String runPublicId, String state, boolean active, String startedAt) {
        jdbc.update("INSERT INTO orchestrator.workflow_epochs (run_id, entry_type, epoch_state, is_active, started_at) "
                        + "VALUES (?, 'EPOCH_HEADER', CAST(? AS jsonb), ?, ?)",
                runPublicId, state, active, Timestamp.from(Instant.parse(startedAt)));
    }

    private void oneShotCompleted(String tenant, UUID workflow, String endedAt) {
        jdbc.update("INSERT INTO orchestrator.workflow_runs (id, workflow_id, tenant_id, run_id_public, status, ended_at) "
                        + "VALUES (?, ?, ?, ?, 'COMPLETED', ?)",
                UUID.randomUUID(), workflow, tenant, "run-" + UUID.randomUUID(), Timestamp.from(Instant.parse(endedAt)));
    }

    private void badge(String tenant, String code, String at) {
        jdbc.update("INSERT INTO orchestrator.user_badges (tenant_id, badge_code, unlocked_at) VALUES (?, ?, ?)",
                tenant, code, Timestamp.from(Instant.parse(at)));
    }

    @Test
    @DisplayName("counts successful epochs + one-shot completed runs in the month, distinct workflows, the month's badges")
    void countsTheMonth() {
        UUID wfA = UUID.randomUUID();
        UUID wfB = UUID.randomUUID();
        String run = triggerRun("7", wfA);
        epoch(run, SUCCESS, false, "2026-08-01T00:00:00Z");       // first instant of the month: in
        epoch(run, SUCCESS, false, "2026-08-31T23:59:59Z");       // last second: in
        epoch(run, FAILED, false, "2026-08-10T10:00:00Z");        // failed: out
        epoch(run, TRIGGER_ONLY, false, "2026-08-11T10:00:00Z");  // only the trigger ran: out
        epoch(run, SUCCESS, true, "2026-08-12T10:00:00Z");        // still open: out
        epoch(run, SUCCESS, false, "2026-09-01T00:00:00Z");       // next month: out
        epoch(run, SUCCESS, false, "2026-07-31T23:59:59Z");       // previous month: out
        oneShotCompleted("7", wfB, "2026-08-20T08:00:00Z");
        badge("7", "builder_1", "2026-08-05T00:00:00Z");
        badge("7", "builder_5", "2026-08-06T00:00:00Z");
        badge("7", "tenure_30", "2026-07-06T00:00:00Z");          // previous month: out

        List<MonthlyRecapStore.Recap> recaps = store.candidates(AUGUST);

        assertThat(recaps).containsExactly(new MonthlyRecapStore.Recap("7", 3, 2, 2));
    }

    @Test
    @DisplayName("only people with at least one successful run that month are candidates")
    void onlyActivePeople() {
        String failing = triggerRun("8", UUID.randomUUID());
        epoch(failing, FAILED, false, "2026-08-10T10:00:00Z");
        badge("8", "builder_1", "2026-08-05T00:00:00Z");           // a badge alone does not qualify
        oneShotCompleted("9", UUID.randomUUID(), "2026-07-20T08:00:00Z"); // last month only
        oneShotCompleted("10", UUID.randomUUID(), "2026-08-02T08:00:00Z");

        assertThat(store.candidates(AUGUST)).extracting(MonthlyRecapStore.Recap::tenantId).containsExactly("10");
    }

    @Test
    @DisplayName("a COMPLETED run that also has epochs is counted by its epochs, never twice")
    void completedRunWithEpochsCountedOnce() {
        UUID wf = UUID.randomUUID();
        String publicId = "run-" + UUID.randomUUID();
        jdbc.update("INSERT INTO orchestrator.workflow_runs (id, workflow_id, tenant_id, run_id_public, status, ended_at) "
                + "VALUES (?, ?, '11', ?, 'COMPLETED', ?)", UUID.randomUUID(), wf, publicId,
                Timestamp.from(Instant.parse("2026-08-03T08:00:00Z")));
        epoch(publicId, SUCCESS, false, "2026-08-03T07:59:00Z");

        assertThat(store.candidates(AUGUST)).containsExactly(new MonthlyRecapStore.Recap("11", 1, 1, 0));
    }

    @Test
    @DisplayName("Regression (spam): a claim holds once per person and month, across passes and pods")
    void claimOncePerMonth() {
        oneShotCompleted("7", UUID.randomUUID(), "2026-08-02T08:00:00Z");
        MonthlyRecapStore otherPod = new MonthlyRecapStore(jdbc);

        assertThat(store.claim("7", AUGUST)).isTrue();
        assertThat(otherPod.claim("7", AUGUST)).isFalse();
        // Claimed people drop out of the next pass's candidates.
        assertThat(store.candidates(AUGUST)).isEmpty();
        // Another month is its own recap.
        assertThat(store.claim("7", AUGUST.plusMonths(1))).isTrue();
    }

    @Test
    @DisplayName("a released claim makes the person a candidate again, for a later pass to retry")
    void releaseMakesCandidateAgain() {
        oneShotCompleted("7", UUID.randomUUID(), "2026-08-02T08:00:00Z");
        store.claim("7", AUGUST);

        store.release("7", AUGUST);

        assertThat(store.candidates(AUGUST)).extracting(MonthlyRecapStore.Recap::tenantId).containsExactly("7");
        assertThat(store.claim("7", AUGUST)).isTrue();
    }
}
