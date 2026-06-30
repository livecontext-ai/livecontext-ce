package com.apimarketplace.agent.service;

import com.apimarketplace.common.storage.StorageUsageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.IncorrectResultSetColumnCountException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the multi-column projection bug introduced when
 * queryAgentsUsage / querySkillsUsage routed a 2-column SELECT through
 * jdbcTemplate.queryForObject(sql, Object[].class, ...) - which Spring resolves
 * via SingleColumnRowMapper, throwing IncorrectResultSetColumnCountException
 * for every tenant nightly. Fix routes via an explicit RowMapper&lt;StorageUsageDto&gt;.
 */
class AgentStorageUsageServiceTest {

    private JdbcTemplate jdbcTemplate;
    private AgentStorageUsageService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new AgentStorageUsageService(jdbcTemplate);
    }

    @Test
    @DisplayName("queryForObject is invoked with a RowMapper, NOT Object[].class - guards the pre-fix bug shape")
    @SuppressWarnings("unchecked")
    void usesRowMapperRatherThanSingleColumnMapper() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong(1)).thenReturn(123L);
        when(rs.getInt(2)).thenReturn(4);

        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class),
                any(Object[].class)))
            .thenAnswer(inv -> {
                RowMapper<StorageUsageDto> mapper = inv.getArgument(1);
                return mapper.mapRow(rs, 0);
            });

        Map<String, StorageUsageDto> result = service.getStorageUsage("tenant-1");

        assertThat(result).containsKeys("AGENTS", "SKILLS");
        assertThat(result.get("AGENTS").usedBytes()).isEqualTo(123L);
        assertThat(result.get("AGENTS").itemCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("returns zero (not throws) when the underlying SQL fails - preserves admin-counter resilience")
    @SuppressWarnings("unchecked")
    void resilientToSqlFailure() {
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
            .thenThrow(new IncorrectResultSetColumnCountException(1, 2));

        Map<String, StorageUsageDto> result = service.getStorageUsage("tenant-1");

        assertThat(result.get("AGENTS")).isEqualTo(StorageUsageDto.zero());
        assertThat(result.get("SKILLS")).isEqualTo(StorageUsageDto.zero());
    }

    @Test
    @DisplayName("clamps negative aggregated bytes to 0 - defensive against pg_column_size returning -1 placeholder")
    @SuppressWarnings("unchecked")
    void clampsNegativeBytesToZero() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong(1)).thenReturn(-7L);
        when(rs.getInt(2)).thenReturn(-2);

        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
            .thenAnswer(inv -> {
                RowMapper<StorageUsageDto> mapper = inv.getArgument(1);
                return mapper.mapRow(rs, 0);
            });

        Map<String, StorageUsageDto> result = service.getStorageUsage("tenant-1");

        assertThat(result.get("AGENTS").usedBytes()).isZero();
        assertThat(result.get("AGENTS").itemCount()).isZero();
    }
}
