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

    // Was "returns zero (not throws) - preserves admin-counter resilience". That
    // rationale no longer has a consumer: StorageReconciliationService is the ONLY caller
    // of every /api/internal/*/storage/usage endpoint repo-wide, and the admin counters
    // read storage.tenant_storage_breakdown instead. Against that one caller the
    // resilience was harmful, not defensive: the reconciler writes this answer through
    // setUsage, an ABSOLUTE set, so a swallowed failure erased the tenant's stored figure
    // rather than degrading it. Propagating lets the internal endpoint answer 5xx and the
    // reconciler skip the category.
    @Test
    @DisplayName("omits every category it could not measure, rather than reporting them as zero")
    @SuppressWarnings("unchecked")
    void omitsUnmeasuredCategories() {
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
            .thenThrow(new IncorrectResultSetColumnCountException(1, 2));

        Map<String, StorageUsageDto> result = service.getStorageUsage("tenant-1");

        // Empty, not three zeros. Three zeros is what StorageReconciliationService would
        // write absolutely over the tenant's real AGENTS and CONFIGURATION figures.
        assertThat(result).isEmpty();
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
    @Test
    @DisplayName("reports long-term memory under its own MEMORIES key, from the memory query and not the skills one")
    @SuppressWarnings("unchecked")
    void reportsMemoriesSeparatelyFromSkills() throws Exception {
        // Each category gets a DIFFERENT row, keyed off the table its SQL reads. With
        // one shared stub every category returns the same numbers, and a MEMORIES value
        // wired to the skills query - or to no query at all - would still look right.
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
            .thenAnswer(inv -> {
                String sql = inv.getArgument(0);
                long bytes = sql.contains("agent_memories") ? 55L
                           : sql.contains("skills") ? 22L
                           : 11L;
                int count = sql.contains("agent_memories") ? 5
                          : sql.contains("skills") ? 2
                          : 1;
                ResultSet rs = mock(ResultSet.class);
                when(rs.getLong(1)).thenReturn(bytes);
                when(rs.getInt(2)).thenReturn(count);
                RowMapper<StorageUsageDto> mapper = inv.getArgument(1);
                return mapper.mapRow(rs, 0);
            });

        Map<String, StorageUsageDto> result = service.getStorageUsage("tenant-1");

        assertThat(result).containsKey("MEMORIES");
        assertThat(result.get("MEMORIES").usedBytes()).isEqualTo(55L);
        assertThat(result.get("MEMORIES").itemCount()).isEqualTo(5);
        assertThat(result.get("SKILLS").usedBytes()).isEqualTo(22L);
    }

    @Test
    @DisplayName("a failing memory query costs only the memory line, not the whole usage report")
    @SuppressWarnings("unchecked")
    void memoryQueryFailureDoesNotSinkTheOtherCategories() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
            .thenAnswer(inv -> {
                String sql = inv.getArgument(0);
                if (sql.contains("agent_memories")) {
                    throw new IncorrectResultSetColumnCountException(1, 2);
                }
                ResultSet rs = mock(ResultSet.class);
                when(rs.getLong(1)).thenReturn(11L);
                when(rs.getInt(2)).thenReturn(1);
                RowMapper<StorageUsageDto> mapper = inv.getArgument(1);
                return mapper.mapRow(rs, 0);
            });

        Map<String, StorageUsageDto> result = service.getStorageUsage("tenant-1");

        // ABSENT, not zero. The reader sums SKILLS and MEMORIES into CONFIGURATION through
        // an absolute set, so a zero here would erase the tenant's memory bytes for the
        // night; an absent key makes it skip the category and keep the stored figure.
        assertThat(result).doesNotContainKey("MEMORIES");
        // And the resilience this test was written for still holds: one failing query
        // costs one line, not the whole report.
        assertThat(result.get("AGENTS").usedBytes()).isEqualTo(11L);
        assertThat(result.get("SKILLS").usedBytes()).isEqualTo(11L);
    }
}
