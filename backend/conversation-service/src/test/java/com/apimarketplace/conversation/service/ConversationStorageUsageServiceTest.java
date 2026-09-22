package com.apimarketplace.conversation.service;

import com.apimarketplace.common.storage.StorageUsageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.IncorrectResultSetColumnCountException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the multi-column projection bug:
 * pre-fix code called jdbcTemplate.queryForObject(sql, Object[].class, ...) which
 * Spring routes through SingleColumnRowMapper → IncorrectResultSetColumnCountException
 * for every tenant nightly. Fix routes via RowMapper&lt;StorageUsageDto&gt;.
 */
class ConversationStorageUsageServiceTest {

    private JdbcTemplate jdbcTemplate;
    private ConversationStorageUsageService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new ConversationStorageUsageService(jdbcTemplate);
    }

    @Test
    @DisplayName("queryForObject is invoked with a RowMapper for the 2-column projection")
    @SuppressWarnings("unchecked")
    void usesRowMapperForTwoColumnProjection() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong(1)).thenReturn(987_654L);
        when(rs.getInt(2)).thenReturn(12);

        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
            .thenAnswer(inv -> {
                RowMapper<StorageUsageDto> mapper = inv.getArgument(1);
                return mapper.mapRow(rs, 0);
            });

        StorageUsageDto result = service.getStorageUsage("tenant-1");

        assertThat(result.usedBytes()).isEqualTo(987_654L);
        assertThat(result.itemCount()).isEqualTo(12);
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
    @DisplayName("propagates a SQL failure instead of reporting an erasing zero")
    @SuppressWarnings("unchecked")
    void propagatesSqlFailure() {
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
            .thenThrow(new IncorrectResultSetColumnCountException(1, 2));

        assertThatThrownBy(() -> service.getStorageUsage("tenant-1"))
            .isInstanceOf(IncorrectResultSetColumnCountException.class);
    }
}
