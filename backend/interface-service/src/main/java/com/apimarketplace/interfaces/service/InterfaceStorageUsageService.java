package com.apimarketplace.interfaces.service;

import com.apimarketplace.common.storage.StorageUsageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
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
            Object[] result = jdbcTemplate.queryForObject(sql, Object[].class, tenantId, tenantId, tenantId);
            if (result == null) return StorageUsageDto.zero();
            long bytes = result[0] instanceof Number n ? n.longValue() : 0;
            int count = result[1] instanceof Number n ? n.intValue() : 0;
            return new StorageUsageDto(Math.max(0, bytes), Math.max(0, count));
        } catch (Exception e) {
            log.warn("Failed to query interface storage for tenant {}: {}", tenantId, e.getMessage());
            return StorageUsageDto.zero();
        }
    }
}
