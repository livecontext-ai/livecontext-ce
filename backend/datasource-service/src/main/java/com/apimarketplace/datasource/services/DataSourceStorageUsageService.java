package com.apimarketplace.datasource.services;

import com.apimarketplace.common.storage.StorageUsageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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
            StorageUsageDto result = jdbcTemplate.queryForObject(sql, STORAGE_USAGE_MAPPER,
                tenantId, tenantId, tenantId);
            return result != null ? result : StorageUsageDto.zero();
        } catch (RuntimeException e) {
            // Do NOT degrade to zero here. The only consumer of this endpoint is
            // StorageReconciliationService, which writes the answer through setUsage, an
            // ABSOLUTE set: a swallowed failure does not report "this tenant stores
            // nothing", it ERASES the stored figure. Propagating makes the internal
            // endpoint answer 5xx, the client degrade to an empty result, and the
            // reconciler skip the category and keep the last good value until the next
            // nightly run. The log line stays because it names the tenant.
            log.warn("Failed to query datasource storage for tenant {}: {}", tenantId, e.getMessage());
            throw e;
        }
    }

    // The SELECT returns TWO columns (byte sum, row count). queryForObject(sql, Object[].class, ...)
    // routes through SingleColumnRowMapper which throws IncorrectResultSetColumnCountException(1,2);
    // the catch then swallowed it and every tenant reconciled to zero. An explicit 2-column RowMapper
    // fixes it, mirroring AgentStorageUsageService / ConversationStorageUsageService /
    // InterfaceStorageUsageService.
    //
    // Rollout note: StorageReconciliationService writes this through setUsage, an ABSOLUTE
    // set, so the first nightly run after this ships moves DATATABLES from 0 to its real
    // value for every tenant at once. Measured on prod 2026-09-21 over BOTH tables this
    // query sums: 2117 kB in total across the 86 tenants, and 1428 kB for the single
    // largest tenant, against 21.8 GB of FILES. No tenant can cross a quota on a
    // correction that small. Re-measure before assuming that still holds.
    private static final RowMapper<StorageUsageDto> STORAGE_USAGE_MAPPER =
        (rs, rowNum) -> {
            long bytes = rs.getLong(1);
            int count = rs.getInt(2);
            return new StorageUsageDto(Math.max(0L, bytes), Math.max(0, count));
        };
}
