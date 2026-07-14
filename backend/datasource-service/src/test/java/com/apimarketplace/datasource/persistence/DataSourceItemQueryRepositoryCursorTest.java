package com.apimarketplace.datasource.persistence;

import com.apimarketplace.datasource.domain.DataSourceEnhancedModels;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the keyset seek predicate built by
 * {@link DataSourceItemQueryRepository#appendCursorCondition}.
 *
 * <p>The list query sorts {@code ORDER BY created_at DESC, id ASC}. Forward keyset
 * pagination must therefore advance WITHIN a created_at tie-group using {@code id > ?}.
 * The pre-fix predicate used {@code id < ?}, which re-selected already-seen smaller ids
 * and never moved past the cursor on a tie, so bulk-inserted rows (all sharing one
 * created_at) were silently dropped. It also compared against a millisecond-truncated
 * timestamp, which never equalled the microsecond-precision column for such rows.
 */
@DisplayName("DataSourceItemQueryRepository.appendCursorCondition")
class DataSourceItemQueryRepositoryCursorTest {

    // appendCursorCondition never touches the JdbcTemplate, so null is fine here.
    private final DataSourceItemQueryRepository repo = new DataSourceItemQueryRepository(null);

    @Test
    @DisplayName("tie-break advances FORWARD with `id > ?` (pre-fix `id < ?` dropped/duplicated rows)")
    void tieBreakUsesGreaterThan() {
        Instant createdAt = Instant.ofEpochSecond(1_700_000_000L, 123_456_000L);
        String cursor = DataSourceEnhancedModels.KeysetCursor.of(createdAt, 500L).encode();

        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();
        repo.appendCursorCondition(sql, params, cursor);

        String out = sql.toString();
        assertThat(out).contains("created_at = ? AND id > ?");
        assertThat(out).doesNotContain("id < ?");
    }

    @Test
    @DisplayName("binds the microsecond-precision cursor timestamp twice, then the id")
    void bindsMicrosecondTimestampAndId() {
        Instant createdAt = Instant.ofEpochSecond(1_700_000_000L, 123_456_000L);
        String cursor = DataSourceEnhancedModels.KeysetCursor.of(createdAt, 500L).encode();

        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();
        repo.appendCursorCondition(sql, params, cursor);

        assertThat(params).hasSize(3);
        Timestamp expected = Timestamp.from(createdAt); // micros preserved, not millis-truncated
        assertThat(params.get(0)).isEqualTo(expected);
        assertThat(params.get(1)).isEqualTo(expected);
        assertThat(params.get(2)).isEqualTo(500L);
        // Regression on the millis-truncation bug: the bound value must carry the microseconds.
        assertThat(((Timestamp) params.get(0)).getNanos()).isEqualTo(123_456_000);
    }

    @Test
    @DisplayName("an invalid cursor is ignored (no predicate, no params)")
    void invalidCursorIgnored() {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();
        repo.appendCursorCondition(sql, params, "not-a-valid-cursor");

        assertThat(sql.toString()).isEmpty();
        assertThat(params).isEmpty();
    }
}
