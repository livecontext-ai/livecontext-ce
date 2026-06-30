package com.apimarketplace.orchestrator.services.state.patch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan v4 §1.6 - pin the CAS SQL composer output shape. The runtime
 * Postgres execution lives in {@code JsonbPatchPostgresIT}; this test
 * pins the string form so unit tests catch silent drift before the
 * integration test runs (Docker-gated, slow).
 */
@DisplayName("Plan v4 §1.6 - JsonbPatchExecutor.composeCasUpdateSql")
class JsonbPatchExecutorCasComposeTest {

    @Test
    @DisplayName("1-patch CAS SQL: chained jsonb_set + state_snapshot_seq update + CAS predicate")
    void singlePatchCasSqlShape() {
        String sql = JsonbPatchExecutor.composeCasUpdateSql(1);

        assertThat(sql)
                .contains("UPDATE orchestrator.workflow_runs")
                .contains("state_snapshot = CAST(jsonb_set(CAST(state_snapshot AS jsonb), CAST(:p0 AS text[]), CAST(:v0 AS jsonb), true) AS text)")
                .contains("state_snapshot_seq = :newSeq")
                .contains("WHERE run_id_public = :runIdPublic AND state_snapshot_seq = :expectedSeq");
    }

    @Test
    @DisplayName("2-patch CAS SQL: nested jsonb_set composition + CAS predicate")
    void multiPatchCasSqlShape() {
        String sql = JsonbPatchExecutor.composeCasUpdateSql(2);

        // The inner jsonb_set comes from the FIRST patch (p0); outer wraps with p1.
        assertThat(sql)
                .contains("jsonb_set(jsonb_set(CAST(state_snapshot AS jsonb), CAST(:p0 AS text[])")
                .contains(", CAST(:p1 AS text[])")
                .contains("state_snapshot_seq = :newSeq")
                .endsWith("AND state_snapshot_seq = :expectedSeq");
    }

    @Test
    @DisplayName("Zero patches → IllegalArgumentException (CAS UPDATE with no SET is non-sense)")
    void zeroPatchesRejected() {
        assertThatThrownBy(() -> JsonbPatchExecutor.composeCasUpdateSql(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("patchCount must be > 0");
    }

    @Test
    @DisplayName("CAS SQL differs from non-CAS by adding the AND state_snapshot_seq predicate")
    void casSqlAddsExpectedSeqPredicate() {
        String casSql = JsonbPatchExecutor.composeCasUpdateSql(1);
        String legacySql = JsonbPatchExecutor.composeUpdateSql(1, true);

        // Both write the seq column.
        assertThat(casSql).contains("state_snapshot_seq = :newSeq");
        assertThat(legacySql).contains("state_snapshot_seq = :newSeq");

        // Only CAS has the WHERE predicate on expectedSeq.
        assertThat(casSql).contains("AND state_snapshot_seq = :expectedSeq");
        assertThat(legacySql).doesNotContain(":expectedSeq");
    }
}
