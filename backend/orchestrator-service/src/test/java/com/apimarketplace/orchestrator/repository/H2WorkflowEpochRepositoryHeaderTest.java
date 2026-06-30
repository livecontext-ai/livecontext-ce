package com.apimarketplace.orchestrator.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for {@link H2WorkflowEpochRepository#upsertHeader} -
 * pins the {@code started_at} preservation contract that
 * {@link WorkflowEpochRepository#getLatestEpochStartedAtByRunIds(java.util.List)}
 * relies on.
 *
 * <p>Bug context: the original H2 implementation used
 * {@code MERGE INTO ... KEY (...) VALUES (..., CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)}.
 * H2 MERGE rewrites every listed value column on conflict, so every subsequent
 * {@code upsertHeader} for the same composite key re-stamped {@code started_at}
 * to NOW. PostgreSQL's {@code ON CONFLICT DO UPDATE SET epoch_state = …,
 * updated_at = NOW()} only touches those two columns, leaving {@code started_at}
 * pinned to the first INSERT. The new {@code MAX(started_at) GROUP BY run_id}
 * query now exposes that divergence - tests that exercise live SQL on H2 would
 * have observed the latest state-rewrite time, not the first-fire time.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("H2WorkflowEpochRepository.upsertHeader - started_at preservation")
class H2WorkflowEpochRepositoryHeaderTest {

    @Mock private JdbcTemplate jdbcTemplate;
    private H2WorkflowEpochRepository repo;

    private static final String RUN_ID = "run-1";
    private static final String TRIGGER_ID = "trigger:webhook";
    private static final int EPOCH = 0;
    private static final String STATE_JSON = "{\"completedNodeIds\":[]}";

    private static final String UPDATE_SQL =
            "UPDATE workflow_epochs\n" +
            "SET epoch_state = ?, updated_at = CURRENT_TIMESTAMP\n" +
            "WHERE run_id = ? AND trigger_id = ? AND epoch = ? AND entry_type = 'EPOCH_HEADER'\n";

    private static final String INSERT_SQL =
            "INSERT INTO workflow_epochs (run_id, trigger_id, epoch, entry_type, entry_key, status, epoch_state, is_active, started_at, updated_at)\n" +
            "VALUES (?, ?, ?, 'EPOCH_HEADER', '_', '_', ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)\n";

    @BeforeEach
    void setUp() {
        repo = new H2WorkflowEpochRepository(jdbcTemplate);
    }

    @Test
    @DisplayName("Row absent: UPDATE returns 0 → INSERT lands (sets started_at = NOW)")
    void firstCallInserts() {
        when(jdbcTemplate.update(eq(UPDATE_SQL), any(), any(), any(), any())).thenReturn(0);
        when(jdbcTemplate.update(eq(INSERT_SQL), any(), any(), any(), any())).thenReturn(1);

        repo.upsertHeader(RUN_ID, TRIGGER_ID, EPOCH, STATE_JSON);

        InOrder order = org.mockito.Mockito.inOrder(jdbcTemplate);
        order.verify(jdbcTemplate).update(eq(UPDATE_SQL), any(), any(), any(), any());
        order.verify(jdbcTemplate).update(eq(INSERT_SQL), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Repeat upsertHeader for SAME (runId,triggerId,epoch) MUST go through UPDATE - never re-INSERT (would re-stamp started_at)")
    void repeatCallsTakeUpdatePath() {
        // Simulate row exists from a prior openEpoch - UPDATE-leg always matches.
        when(jdbcTemplate.update(eq(UPDATE_SQL), any(), any(), any(), any())).thenReturn(1);

        // Three subsequent state-rewrites for the same epoch (typical of a workflow
        // that updates EpochState as nodes complete).
        repo.upsertHeader(RUN_ID, TRIGGER_ID, EPOCH, "{\"completedNodeIds\":[\"n1\"]}");
        repo.upsertHeader(RUN_ID, TRIGGER_ID, EPOCH, "{\"completedNodeIds\":[\"n1\",\"n2\"]}");
        repo.upsertHeader(RUN_ID, TRIGGER_ID, EPOCH, "{\"completedNodeIds\":[\"n1\",\"n2\",\"n3\"]}");

        // Critical contract: zero INSERT calls. INSERT would re-stamp started_at to NOW,
        // breaking MAX(started_at) per run for getLatestEpochStartedAtByRunIds.
        verify(jdbcTemplate, never()).update(eq(INSERT_SQL), any(), any(), any(), any());
        verify(jdbcTemplate, times(3)).update(eq(UPDATE_SQL), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Race fallback: UPDATE returns 0 then INSERT throws (concurrent insert) → falls back to UPDATE so caller's epoch_state still lands")
    void raceFallbackUpdates() {
        when(jdbcTemplate.update(eq(UPDATE_SQL), any(), any(), any(), any()))
                .thenReturn(0)   // first attempt: row not yet present
                .thenReturn(1);  // fallback: someone else's insert won the race
        when(jdbcTemplate.update(eq(INSERT_SQL), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("composite key collision"));

        repo.upsertHeader(RUN_ID, TRIGGER_ID, EPOCH, STATE_JSON);

        InOrder order = org.mockito.Mockito.inOrder(jdbcTemplate);
        order.verify(jdbcTemplate).update(eq(UPDATE_SQL), any(), any(), any(), any());
        order.verify(jdbcTemplate).update(eq(INSERT_SQL), any(), any(), any(), any());
        order.verify(jdbcTemplate).update(eq(UPDATE_SQL), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Non-race INSERT failure (NOT NULL / FK / schema drift) MUST surface - narrowed catch shouldn't silently swallow")
    void nonRaceInsertFailureBubbles() {
        when(jdbcTemplate.update(eq(UPDATE_SQL), any(), any(), any(), any())).thenReturn(0);
        // Generic RuntimeException is NOT a DuplicateKeyException / DataIntegrityViolationException -
        // narrowed catch (was Exception) lets this propagate.
        when(jdbcTemplate.update(eq(INSERT_SQL), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("schema drift: column missing"));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> repo.upsertHeader(RUN_ID, TRIGGER_ID, EPOCH, STATE_JSON))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("schema drift");
        // Critical: no fallback UPDATE attempted on non-race failure (would mask the bug).
        verify(jdbcTemplate, times(1)).update(eq(UPDATE_SQL), any(), any(), any(), any());
    }
}
