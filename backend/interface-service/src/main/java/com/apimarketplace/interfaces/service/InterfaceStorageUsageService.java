package com.apimarketplace.interfaces.service;

import com.apimarketplace.common.storage.StorageUsageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * Calculates storage usage for interface-related data in the interface schema.
 */
@Service
public class InterfaceStorageUsageService {

    private static final Logger log = LoggerFactory.getLogger(InterfaceStorageUsageService.class);

    private final JdbcTemplate jdbcTemplate;

    public InterfaceStorageUsageService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public StorageUsageDto getStorageUsage(String tenantId) {
        try {
            String sql = """
                SELECT COALESCE(SUM(
                    COALESCE(octet_length(i.html_template), 0) +
                    COALESCE(octet_length(i.css_template), 0) +
                    COALESCE(octet_length(i.js_template), 0) +
                    COALESCE(pg_column_size(i.data), 0)
                ), 0) +
                COALESCE((
                    SELECT SUM(
                        COALESCE(octet_length(irs.html_template), 0) +
                        COALESCE(octet_length(irs.css_template), 0) +
                        COALESCE(octet_length(irs.js_template), 0)
                    )
                    FROM interface_run_snapshots irs
                    WHERE irs.tenant_id = ?
                ), 0),
                (SELECT COUNT(*) FROM interfaces WHERE tenant_id = ?)
                FROM interfaces i
                WHERE i.tenant_id = ?
                """;
            StorageUsageDto result = jdbcTemplate.queryForObject(sql, STORAGE_USAGE_MAPPER, tenantId, tenantId, tenantId);
            return result != null ? result : StorageUsageDto.zero();
        } catch (Exception e) {
            log.warn("Failed to query interface storage for tenant {}: {}", tenantId, e.getMessage());
            return StorageUsageDto.zero();
        }
    }

    // The SELECT returns TWO columns (byte sum, row count). queryForObject(sql, Object[].class, ...)
    // routes through SingleColumnRowMapper which throws IncorrectResultSetColumnCountException(1,2);
    // the catch then swallowed it and every tenant reconciled to zero. An explicit 2-column RowMapper
    // fixes it, mirroring AgentStorageUsageService / ConversationStorageUsageService.
    private static final RowMapper<StorageUsageDto> STORAGE_USAGE_MAPPER =
        (rs, rowNum) -> {
            long bytes = rs.getLong(1);
            int count = rs.getInt(2);
            return new StorageUsageDto(Math.max(0L, bytes), Math.max(0, count));
        };
}
