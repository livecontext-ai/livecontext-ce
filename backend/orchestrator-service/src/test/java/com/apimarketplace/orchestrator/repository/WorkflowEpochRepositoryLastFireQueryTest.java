package com.apimarketplace.orchestrator.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WorkflowEpochRepository#getLatestEpochStartedAtByRunIds}.
 *
 * <p>Pins the SQL contract the new {@code lastFireAt} feature relies on:
 * filter on {@code entry_type='EPOCH_HEADER'}, group by {@code run_id},
 * project {@code MAX(started_at)}, NULL-safe row mapping, empty-input
 * short-circuit. Symmetric with the existing {@code getMaxEpochByRunIds}
 * helper (which had no dedicated test either, but the pattern was exercised
 * indirectly).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowEpochRepository.getLatestEpochStartedAtByRunIds")
class WorkflowEpochRepositoryLastFireQueryTest {

    @Mock private JdbcTemplate jdbcTemplate;
    private WorkflowEpochRepository repo;

    @BeforeEach
    void setUp() {
        repo = new WorkflowEpochRepository(jdbcTemplate);
    }

    @Test
    @DisplayName("Empty input: returns empty map without hitting the database")
    void emptyInputShortCircuits() {
        Map<String, Instant> result = repo.getLatestEpochStartedAtByRunIds(List.of());
        assertThat(result).isEmpty();
        verify(jdbcTemplate, never()).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }

    @Test
    @DisplayName("Null input: returns empty map without NPE")
    void nullInputShortCircuits() {
        Map<String, Instant> result = repo.getLatestEpochStartedAtByRunIds(null);
        assertThat(result).isEmpty();
        verify(jdbcTemplate, never()).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }

    @Test
    @DisplayName("SQL selects MAX(started_at), filters EPOCH_HEADER, groups by run_id, binds runIds in order")
    void sqlShapeIsCorrect() {
        repo.getLatestEpochStartedAtByRunIds(List.of("run-A", "run-B", "run-C"));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowCallbackHandler.class), argsCaptor.capture());

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("MAX(started_at)");
        assertThat(sql).contains("entry_type = 'EPOCH_HEADER'");
        assertThat(sql).contains("GROUP BY run_id");
        assertThat(sql).contains("run_id IN (?, ?, ?)");

        // Args bound in the same order the caller passed.
        assertThat(argsCaptor.getValue()).containsExactly("run-A", "run-B", "run-C");
    }

    @Test
    @DisplayName("NULL started_at row is skipped - does NOT land null Instant in the map (would NPE downstream)")
    void nullTimestampSkipped() throws Exception {
        ResultSet rs = mockResultSet("run-A", null);
        doAnswer(invocation -> {
            RowCallbackHandler rch = invocation.getArgument(1);
            rch.processRow(rs);
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        Map<String, Instant> result = repo.getLatestEpochStartedAtByRunIds(List.of("run-A"));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Populated row: started_at lands in map keyed by run_id")
    void populatedRowMapped() throws Exception {
        Instant when = Instant.parse("2026-05-10T10:00:00Z");
        ResultSet rs = mockResultSet("run-A", Timestamp.from(when));
        doAnswer(invocation -> {
            RowCallbackHandler rch = invocation.getArgument(1);
            rch.processRow(rs);
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        Map<String, Instant> result = repo.getLatestEpochStartedAtByRunIds(List.of("run-A"));

        assertThat(result).containsEntry("run-A", when);
    }

    private static ResultSet mockResultSet(String runId, Timestamp ts) throws Exception {
        ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
        // lenient: when ts is null the row-callback short-circuits before reading run_id;
        // strict-stubbing would flag the getString stub as unnecessary in that path.
        org.mockito.Mockito.lenient().when(rs.getString("run_id")).thenReturn(runId);
        when(rs.getTimestamp("latest_started_at")).thenReturn(ts);
        return rs;
    }
}
