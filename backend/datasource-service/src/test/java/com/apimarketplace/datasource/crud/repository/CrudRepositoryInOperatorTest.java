package com.apimarketplace.datasource.crud.repository;

import com.apimarketplace.datasource.crud.domain.WhereCondition;
import com.apimarketplace.datasource.crud.service.SqlSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for the 2026-05-21 prod fire:
 * {@code bad SQL grammar [... id::text = ANY(?, ?)]} when an agent issued a
 * {@code crud-delete} with {@code where={column:'id', operator:'IN', value:[a,b]}}.
 *
 * <p>Pre-fix the {@code IN} branch of {@code buildWhereClause} emitted
 * {@code columnAccess + " = ANY(:where_val)"} and bound a {@code List<String>}.
 * Spring's {@link NamedParameterJdbcTemplate} unwraps a {@code Collection}
 * parameter into comma-separated positional placeholders - fine for
 * {@code IN (:list)} → {@code IN (?, ?)} but catastrophic for
 * {@code ANY(:list)} → {@code ANY(?, ?)}: PostgreSQL's {@code ANY()} takes
 * a single array argument, not a positional list. Every multi-id IN delete
 * on the {@code id} column (and every multi-value IN on any JSONB column)
 * failed at parse time with no rows touched.
 *
 * <p>The fix swaps {@code ANY} for {@code IN} so the unwrapping shape matches
 * the SQL operator. Also closes the latent empty-list bug (pre-fix
 * {@code ANY()} / post-fix {@code IN ()} are both invalid SQL - now caught
 * up-front with a clear agent-facing error).
 */
class CrudRepositoryInOperatorTest {

    private NamedParameterJdbcTemplate jdbcTemplate;
    private CrudRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        SqlSanitizer sqlSanitizer = new SqlSanitizer();
        ObjectMapper objectMapper = new ObjectMapper();
        repository = new CrudRepository(jdbcTemplate, sqlSanitizer, objectMapper);
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(2);
        when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class)))
            .thenReturn(List.of());
        when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
            .thenReturn(List.of());
    }

    @Test
    @DisplayName("deleteRows with IN operator on id column emits id::text IN (:where_val) - NOT id::text = ANY(:where_val) (prod fire 2026-05-21)")
    void deleteRowsWithInOperatorOnIdColumnEmitsValidSql() {
        WhereCondition where = new WhereCondition("id", "IN", List.of(67L, 68L));

        repository.deleteRows(1L, "tenant-1", where);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), any(MapSqlParameterSource.class));
        String sql = sqlCaptor.getValue();

        assertThat(sql)
            .as("MUST emit IN (:where_val) so Spring's collection-unwrapping produces valid SQL. "
                + "Pre-fix the SQL contained `= ANY(:where_val)` → `= ANY(?, ?)` → PostgreSQL parse error.")
            .contains("id::text IN (:where_val)")
            .doesNotContain("= ANY");
    }

    @Test
    @DisplayName("readRows with IN operator on JSONB column emits jsonb_extract_path_text(...) IN (:where_val) - same bug shape, same fix")
    void readRowsWithInOperatorOnJsonbColumnEmitsValidSql() {
        WhereCondition where = new WhereCondition("status", "IN", List.of("open", "pending"));

        repository.readRows(1L, "tenant-1", where, 10, 0);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), any(MapSqlParameterSource.class));
        String sql = sqlCaptor.getValue();

        assertThat(sql)
            .as("JSONB columns hit the same buildWhereClause IN branch - must emit IN (:where_val), not = ANY(:where_val)")
            .contains("jsonb_extract_path_text(data, :where_col) IN (:where_val)")
            .doesNotContain("= ANY");
    }

    @Test
    @DisplayName("updateRows with IN operator emits valid IN (:where_val) clause - every mutating path shares buildWhereClause")
    void updateRowsWithInOperatorEmitsValidSql() {
        WhereCondition where = new WhereCondition("id", "IN", List.of(67L, 68L));

        repository.updateRows(1L, "tenant-1", where, java.util.Map.of("status", "closed"));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), any(MapSqlParameterSource.class));
        String sql = sqlCaptor.getValue();

        assertThat(sql).contains("id::text IN (:where_val)").doesNotContain("= ANY");
    }

    @Test
    @DisplayName("findIdsMatching with IN operator emits valid IN (:where_val) - pre-mutation id capture for event emission shares buildWhereClause")
    void findIdsMatchingWithInOperatorEmitsValidSql() {
        WhereCondition where = new WhereCondition("id", "IN", List.of(67L, 68L));

        repository.findIdsMatching(1L, "tenant-1", where);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), any(MapSqlParameterSource.class), eq(Long.class));
        String sql = sqlCaptor.getValue();

        assertThat(sql)
            .as("CrudExecutorService runs findIdsMatching before update/delete to snapshot affected ids "
                + "for the row-event publisher - that path also hits buildWhereClause and must emit valid SQL")
            .contains("id::text IN (:where_val)")
            .doesNotContain("= ANY");
    }

    @Test
    @DisplayName("IN with empty list throws IllegalArgumentException - IN () is invalid SQL, fail fast with an agent-readable message")
    void inWithEmptyListThrowsClearError() {
        WhereCondition where = new WhereCondition("id", "IN", List.of());

        assertThatThrownBy(() -> repository.deleteRows(1L, "tenant-1", where))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("IN operator requires a non-empty list value");
    }

    @Test
    @DisplayName("Bound where_val parameter is the raw List, NOT a comma-joined String - Spring's collection-unwrapping depends on the type")
    void boundWhereValIsListNotString() {
        WhereCondition where = new WhereCondition("id", "IN", List.of(67L, 68L));

        repository.deleteRows(1L, "tenant-1", where);

        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).update(anyString(), paramsCaptor.capture());

        Object boundValue = paramsCaptor.getValue().getValue("where_val");
        assertThat(boundValue)
            .as("Spring's NamedParameterJdbcTemplate unwraps a Collection into (?, ?, ...); "
                + "binding a CSV String would silently produce a single placeholder treated as one literal value.")
            .isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Object> boundList = (List<Object>) boundValue;
        assertThat(boundList).containsExactly("67", "68");
    }

    private static <T> T any(Class<T> clazz) {
        return org.mockito.ArgumentMatchers.any(clazz);
    }
}
