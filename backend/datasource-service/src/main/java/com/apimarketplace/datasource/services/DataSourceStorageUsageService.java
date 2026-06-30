package com.apimarketplace.datasource.services;

import com.apimarketplace.common.storage.StorageUsageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Calculates storage usage for datasource-related data in the datasource schema.
 */
@Service
public class DataSourceStorageUsageService {

    private static final Logger log = LoggerFactory.getLogger(DataSourceStorageUsageService.class);

    private final JdbcTemplate jdbcTemplate;

    public DataSourceStorageUsageService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public StorageUsageDto getStorageUsage(String tenantId) {
        try {
            String sql = """
                SELECT COALESCE((
                    SELECT SUM(pg_column_size(dsi.data))
                    FROM data_source_items dsi
                    WHERE dsi.tenant_id = ?
                ), 0) +
                COALESCE((
                    SELECT SUM(
                        COALESCE(pg_column_size(ds.source_config), 0) +
                        COALESCE(pg_column_size(ds.column_order), 0) +
                        COALESCE(pg_column_size(ds.mapping_spec), 0)
                    )
                    FROM data_sources ds
                    WHERE ds.tenant_id = ?
                ), 0),
                COALESCE((
                    SELECT COUNT(*) FROM data_source_items WHERE tenant_id = ?
                ), 0)
                """;
            Object[] result = jdbcTemplate.queryForObject(sql, Object[].class, tenantId, tenantId, tenantId);
            if (result == null) return StorageUsageDto.zero();
            long bytes = result[0] instanceof Number n ? n.longValue() : 0;
            int count = result[1] instanceof Number n ? n.intValue() : 0;
            return new StorageUsageDto(Math.max(0, bytes), Math.max(0, count));
        } catch (Exception e) {
            log.warn("Failed to query datasource storage for tenant {}: {}", tenantId, e.getMessage());
            return StorageUsageDto.zero();
        }
    }
}
