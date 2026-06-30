package com.apimarketplace.orchestrator.persistence;

import com.apimarketplace.orchestrator.domain.StorageNestedModels.StorageNestedRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StorageNestedRepositoriesTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private StorageNestedRepositories repository;

    @BeforeEach
    void setUp() {
        repository = new StorageNestedRepositories(jdbcTemplate);
    }

    @Test
    void findNestedDataBindsObjectPathAndSortField() {
        UUID storageId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String path = "output.evil') OR 1=1 --";
        String sortBy = "name') DESC; DROP TABLE storage_storage; --";

        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object[].class)))
            .thenReturn(List.of("object"));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
            .thenReturn(List.<StorageNestedRow>of());

        repository.findNestedData(storageId, "tenant-1", path, 2, 25, sortBy, "desc");

        ArgumentCaptor<String> querySql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> queryParams = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(querySql.capture(), any(RowMapper.class), queryParams.capture());

        assertThat(querySql.getValue())
            .contains("jsonb_extract_path(data, ?, ?)")
            .contains("jsonb_extract_path_text(jsonb_extract_path(data, ?, ?), ?)")
            .doesNotContain("evil') OR 1=1 --")
            .doesNotContain(sortBy)
            .doesNotContain("->'")
            .doesNotContain("->>'");
        assertThat(queryParams.getValue()).containsExactly(
            "output", "evil') OR 1=1 --",
            storageId, "tenant-1",
            "output", "evil') OR 1=1 --",
            "output", "evil') OR 1=1 --",
            "output", "evil') OR 1=1 --", sortBy,
            25, 25);
    }

    @Test
    void findNestedDataBindsArraySortField() {
        UUID storageId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        String sortBy = "name') DESC; DROP TABLE storage_storage; --";

        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object[].class)))
            .thenReturn(List.of("array"));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
            .thenReturn(List.<StorageNestedRow>of());

        repository.findNestedData(storageId, "tenant-1", "items", 1, 10, sortBy, "asc");

        ArgumentCaptor<String> querySql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> queryParams = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(querySql.capture(), any(RowMapper.class), queryParams.capture());

        assertThat(querySql.getValue())
            .contains("jsonb_array_elements(jsonb_extract_path(s.data, ?))")
            .contains("jsonb_extract_path_text(elem.value, ?)")
            .doesNotContain(sortBy)
            .doesNotContain("->>'");
        assertThat(queryParams.getValue()).containsExactly(
            "items",
            storageId, "tenant-1",
            "items",
            "items",
            sortBy,
            10, 0);
    }

    @Test
    void countNestedDataBindsArrayPathSegments() {
        UUID storageId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        String path = "items.evil') OR 1=1 --";

        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object[].class)))
            .thenReturn(List.of("array"));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
            .thenReturn(3);

        int count = repository.countNestedData(storageId, "tenant-1", path);

        assertThat(count).isEqualTo(3);
        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> countParams = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(countSql.capture(), eq(Integer.class), countParams.capture());

        assertThat(countSql.getValue())
            .contains("jsonb_array_length(jsonb_extract_path(data, ?, ?))")
            .doesNotContain("evil') OR 1=1 --")
            .doesNotContain("->'");
        assertThat(countParams.getValue()).containsExactly(
            "items", "evil') OR 1=1 --",
            storageId, "tenant-1",
            "items", "evil') OR 1=1 --",
            "items", "evil') OR 1=1 --");
    }
}
