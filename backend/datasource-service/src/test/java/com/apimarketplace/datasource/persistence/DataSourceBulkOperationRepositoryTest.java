package com.apimarketplace.datasource.persistence;

import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.DataSourceItemRow;
import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.JsonPatchOperation;
import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.PatchOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DataSourceBulkOperationRepository")
class DataSourceBulkOperationRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private DataSourceBulkOperationRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new DataSourceBulkOperationRepository(jdbcTemplate);
    }

    @Test
    @DisplayName("Converts RFC6902 leading-slash paths to valid PostgreSQL jsonb paths")
    void leadingSlashPatchPathBuildsValidJsonbPath() {
        DataSourceItemRow row = new DataSourceItemRow(
                7L,
                3L,
                "tenant-1",
                Map.of("status", "done"),
                1,
                Instant.parse("2026-05-16T10:00:00Z"),
                Instant.parse("2026-05-16T10:01:00Z"));
        when(jdbcTemplate.queryForObject(
                anyString(),
                any(RowMapper.class),
                any(),
                any(),
                any()))
                .thenReturn(row);

        repository.applyJsonPatch(
                3L,
                "tenant-1",
                7L,
                List.of(new JsonPatchOperation(PatchOperation.REPLACE, "/status", "done", null)));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sql.capture(), any(RowMapper.class), any(), any(), any());
        assertThat(sql.getValue()).contains("jsonb_set(data, '{\"status\"}', '\"done\"'::jsonb, true)");
        assertThat(sql.getValue()).doesNotContain("{,status}");
    }
}
