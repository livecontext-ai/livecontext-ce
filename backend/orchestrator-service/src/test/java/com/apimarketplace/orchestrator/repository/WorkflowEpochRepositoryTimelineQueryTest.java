package com.apimarketplace.orchestrator.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the SQL contract of {@link WorkflowEpochRepository#listEpochTimestamps}.
 *
 * <p>The query gained {@code is_active} and {@code epoch_state}: the epoch's outcome is
 * read from the state stored on the SAME header row, so the timeline pays ONE query
 * rather than a second aggregate on the snapshot hot path. A column dropped from the
 * projection, or a mapper reading the wrong one, would leave every epoch badge blank -
 * which looks exactly like "this run had no failures".
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowEpochRepository.listEpochTimestamps")
class WorkflowEpochRepositoryTimelineQueryTest {

    @Mock private JdbcTemplate jdbcTemplate;
    private WorkflowEpochRepository repo;

    @BeforeEach
    void setUp() {
        repo = new WorkflowEpochRepository(jdbcTemplate);
    }

    @Test
    @DisplayName("Selects the state + activity of the header rows, ordered, scoped to the run")
    void sqlProjectsTheOutcomeSourceFromTheHeaderRows() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.query(sql.capture(), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of());

        repo.listEpochTimestamps("run-A");

        String q = sql.getValue().toLowerCase();
        assertThat(q).contains("epoch_state").contains("is_active")
                .contains("started_at").contains("closed_at");
        // The outcome must come from the epoch's OWN header, not from another run's rows
        // and not from the NODE counter rows (additive, and they count a continue-anyway
        // split failure the cycle verdict excludes).
        assertThat(q).contains("entry_type = 'epoch_header'").contains("run_id = ?");
        assertThat(q).contains("order by epoch asc");
    }

    @Test
    @DisplayName("Maps an open epoch: no close timestamp, active, state carried through")
    void mapsAnOpenEpoch() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt("epoch")).thenReturn(7);
        when(rs.getTimestamp("started_at")).thenReturn(Timestamp.from(Instant.parse("2026-08-02T09:00:00Z")));
        when(rs.getTimestamp("closed_at")).thenReturn(null);
        when(rs.getBoolean("is_active")).thenReturn(true);
        when(rs.getString("epoch_state")).thenReturn("{\"failedNodeIds\":[]}");

        WorkflowEpochRepository.EpochTimelineRow row = captureMapper().mapRow(rs, 0);

        assertThat(row.epoch()).isEqualTo(7);
        assertThat(row.startedAt()).isEqualTo("2026-08-02T09:00:00Z");
        assertThat(row.endedAt()).isNull();
        assertThat(row.isActive()).isTrue();
        assertThat(row.epochStateJson()).isEqualTo("{\"failedNodeIds\":[]}");
    }

    @Test
    @DisplayName("Maps a closed epoch without exploding on a null state")
    void mapsAClosedEpochWithoutState() throws Exception {
        // Reachable on rows written before the header carried a state.
        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt("epoch")).thenReturn(1);
        when(rs.getTimestamp("started_at")).thenReturn(Timestamp.from(Instant.parse("2026-08-02T09:00:00Z")));
        when(rs.getTimestamp("closed_at")).thenReturn(Timestamp.from(Instant.parse("2026-08-02T09:00:30Z")));
        when(rs.getBoolean("is_active")).thenReturn(false);
        when(rs.getString("epoch_state")).thenReturn(null);

        WorkflowEpochRepository.EpochTimelineRow row = captureMapper().mapRow(rs, 0);

        assertThat(row.endedAt()).isEqualTo("2026-08-02T09:00:30Z");
        assertThat(row.isActive()).isFalse();
        assertThat(row.epochStateJson()).isNull();
    }

    @Test
    @DisplayName("The windowed listing reads the N most recent header rows only, with the same projection")
    void latestListingIsBoundedNewestFirst() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        when(jdbcTemplate.query(sql.capture(), any(RowMapper.class), args.capture(), args.capture()))
                .thenReturn(List.of());

        repo.listLatestEpochTimestamps("run-A", 60);

        String q = sql.getValue().toLowerCase();
        assertThat(q).contains("epoch_state").contains("is_active").contains("started_at").contains("closed_at");
        assertThat(q).contains("entry_type = 'epoch_header'").contains("run_id = ?");
        // Newest first and LIMITED in SQL: a long-lived schedule must not load every epoch.
        assertThat(q).contains("order by epoch desc").contains("limit ?");
        assertThat(args.getAllValues()).containsExactly("run-A", 60);
    }

    @SuppressWarnings("unchecked")
    private RowMapper<WorkflowEpochRepository.EpochTimelineRow> captureMapper() {
        ArgumentCaptor<RowMapper<WorkflowEpochRepository.EpochTimelineRow>> mapper =
                ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.query(anyString(), mapper.capture(), any(Object.class))).thenReturn(List.of());
        repo.listEpochTimestamps("run-A");
        return mapper.getValue();
    }
}
