package com.apimarketplace.datasource.crud.repository;

import com.apimarketplace.datasource.crud.domain.WhereCondition;
import com.apimarketplace.datasource.crud.service.SqlSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the SQL rendering of {@code buildWhereClause} for every supported operator
 * plus the UPDATE set-clause path. The agent-facing table CRUD contract documents
 * these semantics (textual comparison via jsonb_extract_path_text / id::text,
 * bare column names with the data. prefix stripped at runtime) - this test keeps
 * the rendered SQL honest so a refactor cannot silently change an operator's shape.
 *
 * <p>Same construction pattern as {@link CrudRepositoryInOperatorTest}: mocked
 * NamedParameterJdbcTemplate, real SqlSanitizer, SQL captured via ArgumentCaptor.
 */
@DisplayName("CrudRepository WHERE/SET operator rendering")
class CrudRepositoryWhereOperatorsTest {

    private NamedParameterJdbcTemplate jdbcTemplate;
    private CrudRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        repository = new CrudRepository(jdbcTemplate, new SqlSanitizer(), new ObjectMapper());
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class))).thenReturn(List.of());
    }

    // ─── comparison operators on a JSONB column (default branch) ───────────────

    @ParameterizedTest(name = "operator {0} renders as jsonb text comparison")
    @CsvSource({"=", "!=", ">", "<", ">=", "<="})
    @DisplayName("Comparison operators render against jsonb_extract_path_text with a text-bound value")
    void comparisonOperatorsRenderAsTextComparison(String operator) {
        repository.deleteRows(7L, "tenant-1", new WhereCondition("amount", operator, "9"));

        String sql = capturedUpdateSql();
        assertThat(sql).contains("jsonb_extract_path_text(data, :where_col) " + operator + " :where_val");
        MapSqlParameterSource params = capturedUpdateParams();
        assertThat(params.getValue("where_col")).isEqualTo("amount");
        assertThat(params.getValue("where_val")).isEqualTo("9");
    }

    @Test
    @DisplayName("Value is bound via toString - a numeric value compares as text (documented lexicographic semantics)")
    void numericValueIsBoundAsText() {
        repository.deleteRows(7L, "tenant-1", new WhereCondition("amount", ">", 100));

        assertThat(capturedUpdateParams().getValue("where_val"))
            .isInstanceOf(String.class)
            .isEqualTo("100");
    }

    // ─── LIKE ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LIKE renders with the caller-supplied pattern bound verbatim")
    void likeRendersWithBoundPattern() {
        repository.deleteRows(7L, "tenant-1", new WhereCondition("email", "LIKE", "%@example.com"));

        assertThat(capturedUpdateSql())
            .contains("jsonb_extract_path_text(data, :where_col) LIKE :where_val");
        assertThat(capturedUpdateParams().getValue("where_val")).isEqualTo("%@example.com");
    }

    @Test
    @DisplayName("contains alias normalizes to LIKE")
    void containsAliasNormalizesToLike() {
        repository.deleteRows(7L, "tenant-1", new WhereCondition("email", "contains", "%bob%"));

        assertThat(capturedUpdateSql()).contains(" LIKE :where_val");
    }

    // ─── IS NULL / IS NOT NULL ─────────────────────────────────────────────────

    @Test
    @DisplayName("IS NULL renders without binding a value parameter")
    void isNullRendersWithoutValue() {
        repository.deleteRows(7L, "tenant-1", new WhereCondition("optional_field", "IS NULL", null));

        String sql = capturedUpdateSql();
        assertThat(sql).contains("jsonb_extract_path_text(data, :where_col) IS NULL");
        assertThat(sql).doesNotContain(":where_val");
        assertThat(capturedUpdateParams().hasValue("where_val")).isFalse();
    }

    @Test
    @DisplayName("IS NOT NULL renders without binding a value parameter (the documented wipe-table idiom)")
    void isNotNullRendersWithoutValue() {
        repository.deleteRows(7L, "tenant-1", new WhereCondition("id", "IS NOT NULL", null));

        String sql = capturedUpdateSql();
        assertThat(sql).contains("id::text IS NOT NULL");
        assertThat(capturedUpdateParams().hasValue("where_val")).isFalse();
    }

    // ─── id::text special case ─────────────────────────────────────────────────

    @Test
    @DisplayName("Reserved column id targets the primary key as id::text (no where_col bound)")
    void idColumnTargetsPrimaryKeyAsText() {
        repository.deleteRows(7L, "tenant-1", new WhereCondition("id", "=", 42));

        String sql = capturedUpdateSql();
        assertThat(sql).contains("id::text = :where_val");
        assertThat(sql).doesNotContain("jsonb_extract_path_text");
        MapSqlParameterSource params = capturedUpdateParams();
        assertThat(params.hasValue("where_col")).isFalse();
        assertThat(params.getValue("where_val")).isEqualTo("42");
    }

    // ─── data. prefix strip (runtime layer) ────────────────────────────────────

    @Test
    @DisplayName("A data.-prefixed WHERE column is stripped to the bare name at runtime")
    void dataPrefixedWhereColumnIsStripped() {
        repository.deleteRows(7L, "tenant-1", new WhereCondition("data.message_id", "=", "m-1"));

        assertThat(capturedUpdateParams().getValue("where_col")).isEqualTo("message_id");
    }

    @Test
    @DisplayName("UPDATE set-keys strip the data. prefix and bind through jsonb_build_object")
    void updateSetKeysStripDataPrefix() {
        repository.updateRows(7L, "tenant-1", new WhereCondition("id", "=", 1), Map.of("data.label", "done"));

        String sql = capturedUpdateSql();
        assertThat(sql).contains("jsonb_build_object(:set_key_0, :set_val_0)");
        MapSqlParameterSource params = capturedUpdateParams();
        assertThat(params.getValue("set_key_0")).isEqualTo("label");
        assertThat(params.getValue("set_val_0")).isEqualTo("done");
    }

    @Test
    @DisplayName("UPDATE serializes complex set-values to JSON and floors null to empty string")
    void updateSerializesComplexValuesAndFloorsNull() {
        java.util.Map<String, Object> setColumns = new java.util.LinkedHashMap<>();
        setColumns.put("meta", Map.of("k", "v"));
        setColumns.put("cleared", null);
        repository.updateRows(7L, "tenant-1", new WhereCondition("id", "=", 1), setColumns);

        MapSqlParameterSource params = capturedUpdateParams();
        assertThat(params.getValue("set_val_0")).isEqualTo("{\"k\":\"v\"}");
        assertThat(params.getValue("set_val_1")).isEqualTo("");
    }

    // ─── read path parity ──────────────────────────────────────────────────────

    @Test
    @DisplayName("readRows renders the same WHERE shape as the write paths")
    void readRowsRendersSameWhereShape() {
        repository.readRows(7L, "tenant-1", new WhereCondition("status", "!=", "archived"), 10, 0);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), any(MapSqlParameterSource.class));
        assertThat(sqlCaptor.getValue())
            .contains("jsonb_extract_path_text(data, :where_col) != :where_val");
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private String capturedUpdateSql() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(captor.capture(), any(MapSqlParameterSource.class));
        return captor.getValue();
    }

    private MapSqlParameterSource capturedUpdateParams() {
        ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).update(anyString(), captor.capture());
        return captor.getValue();
    }

    /** Shadow Mockito's any(Class) to sidestep the ambiguous-import trap (same as CrudRepositoryInOperatorTest). */
    private static <T> T any(Class<T> clazz) {
        return org.mockito.ArgumentMatchers.any(clazz);
    }
}
