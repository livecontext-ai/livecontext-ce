package com.apimarketplace.datasource.crud.repository;

import com.apimarketplace.datasource.crud.domain.WhereCondition;
import com.apimarketplace.datasource.crud.dto.CreateRowRequest;
import com.apimarketplace.datasource.crud.service.SqlSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LC-066/LC-011 CASA re-audit item 2: {@code data_sensitivity} on {@code data_source_items}
 * (migration V538).
 *
 * <p>Pre-fix, a row written into a user's table during a RESTRICTED run/conversation lost the
 * tag entirely: {@link CrudRepository#createRows} and {@link CrudRepository#updateRows} had no
 * sensitivity parameter, and {@link CrudRepository#readRows} never selected the column, so
 * neither the orchestrator's CRUD tool nor the MCP {@code table} tool could tell a caller the row
 * had ever touched Gmail/Drive content.
 */
class CrudRepositoryDataSensitivityTest {

    private NamedParameterJdbcTemplate jdbcTemplate;
    private CrudRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        SqlSanitizer sqlSanitizer = new SqlSanitizer();
        ObjectMapper objectMapper = new ObjectMapper();
        repository = new CrudRepository(jdbcTemplate, sqlSanitizer, objectMapper);
    }

    @Test
    @DisplayName("createRows(dataSensitivity): binds the caller's tag as the data_sensitivity parameter")
    void createRowsBindsRestrictedTag() {
        repository.createRows(1L, "tenant-1",
                List.of(new CreateRowRequest.RowData(null, Map.of("subject", "wire transfer"))),
                "RESTRICTED");

        ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).update(anyString(), captor.capture(), any(), any(String[].class));
        assertThat(captor.getValue().getValue("data_sensitivity")).isEqualTo("RESTRICTED");
    }

    @Test
    @DisplayName("createRows(dataSensitivity): a garbage/unknown tag normalizes to NORMAL, never NULL or an arbitrary string")
    void createRowsNormalizesUnknownTag() {
        repository.createRows(1L, "tenant-1",
                List.of(new CreateRowRequest.RowData(null, Map.of("x", "y"))),
                "not-a-real-value");

        ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).update(anyString(), captor.capture(), any(), any(String[].class));
        assertThat(captor.getValue().getValue("data_sensitivity")).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("createRows: the 3-arg back-compat overload (no runId context) writes NORMAL")
    void legacyThreeArgOverloadWritesNormal() {
        repository.createRows(1L, "tenant-1",
                List.of(new CreateRowRequest.RowData(null, Map.of("x", "y"))));

        ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).update(anyString(), captor.capture(), any(), any(String[].class));
        assertThat(captor.getValue().getValue("data_sensitivity")).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("updateRows(restricted=true): the SET clause ratchets data_sensitivity to RESTRICTED")
    void updateRowsRatchetsToRestricted() {
        WhereCondition where = new WhereCondition("id", "=", "1");

        repository.updateRows(1L, "tenant-1", where, Map.of("status", "closed"), true);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), paramsCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("data_sensitivity = :data_sensitivity");
        assertThat(paramsCaptor.getValue().getValue("data_sensitivity")).isEqualTo("RESTRICTED");
    }

    @Test
    @DisplayName("updateRows(restricted=false): the SET clause does NOT touch data_sensitivity at all - "
        + "an ordinary edit never downgrades a row a prior restricted write tagged")
    void updateRowsNeverDowngrades() {
        WhereCondition where = new WhereCondition("id", "=", "1");

        repository.updateRows(1L, "tenant-1", where, Map.of("status", "closed"), false);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), paramsCaptor.capture());
        assertThat(sqlCaptor.getValue()).doesNotContain("data_sensitivity");
        assertThat(paramsCaptor.getValue().hasValue("data_sensitivity")).isFalse();
    }

    @Test
    @DisplayName("updateRows: the 4-arg back-compat overload (no runId context) never touches data_sensitivity")
    void legacyFourArgUpdateOverloadNeverTouchesSensitivity() {
        WhereCondition where = new WhereCondition("id", "=", "1");

        repository.updateRows(1L, "tenant-1", where, Map.of("status", "closed"));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), any(MapSqlParameterSource.class));
        assertThat(sqlCaptor.getValue()).doesNotContain("data_sensitivity");
    }

    @Test
    @DisplayName("readRows: selects data_sensitivity alongside id/data/priority/created_at")
    void readRowsSelectsDataSensitivity() {
        when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class))).thenReturn(List.of());

        repository.readRows(1L, "tenant-1", null, 20, 0);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), any(MapSqlParameterSource.class));
        assertThat(sqlCaptor.getValue()).contains("data_sensitivity");
    }

    @Test
    @DisplayName("findRowsByIds: selects data_sensitivity alongside id/data/priority/created_at "
        + "(LC-066 re-audit item 1 - snapshotRowsById feeds table-trigger payloads from this call)")
    void findRowsByIdsSelectsDataSensitivity() {
        when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class))).thenReturn(List.of());

        repository.findRowsByIds(1L, "tenant-1", List.of(42L));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), any(MapSqlParameterSource.class));
        assertThat(sqlCaptor.getValue())
            .as("pre-fix this SELECT omitted data_sensitivity entirely, so a row that fired a "
                + "datasource trigger while RESTRICTED reached CrudExecutorService.snapshotRowsById "
                + "with no way to classify the event")
            .contains("data_sensitivity");
    }

    @Test
    @DisplayName("createRows never leaves data_sensitivity unbound - createRows(3-arg) delegates to the 4-arg, never a raw call site")
    void createRowsAlwaysBindsSensitivity() {
        repository.createRows(1L, "tenant-1", List.of(new CreateRowRequest.RowData(null, Map.of("a", 1))));
        repository.createRows(1L, "tenant-1", List.of(new CreateRowRequest.RowData(null, Map.of("a", 1))), "RESTRICTED");

        ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(2))
                .update(anyString(), captor.capture(), any(), any(String[].class));
        List<MapSqlParameterSource> all = captor.getAllValues();
        assertThat(all).allSatisfy(p -> assertThat(p.hasValue("data_sensitivity")).isTrue());
    }
}
