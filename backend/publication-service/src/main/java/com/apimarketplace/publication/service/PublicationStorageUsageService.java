package com.apimarketplace.publication.service;

import com.apimarketplace.common.storage.StorageUsageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * Calculates storage usage for publication-related data in the publication schema.
 */
@Service
public class PublicationStorageUsageService {

    private static final Logger log = LoggerFactory.getLogger(PublicationStorageUsageService.class);

    private final JdbcTemplate jdbcTemplate;

    public PublicationStorageUsageService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public StorageUsageDto getStorageUsage(String tenantId) {
        try {
            String sql = """
                SELECT COALESCE((
                    SELECT SUM(
                        COALESCE(pg_column_size(wp.plan_snapshot), 0) +
                        COALESCE(pg_column_size(wp.node_icons), 0)
                    )
                    FROM workflow_publications wp
                    WHERE wp.publisher_id = ?
                ), 0),
                COALESCE((
                    SELECT COUNT(*) FROM workflow_publications WHERE publisher_id = ?
                ), 0)
                """;
            StorageUsageDto result = jdbcTemplate.queryForObject(sql, STORAGE_USAGE_MAPPER, tenantId, tenantId);
            return result != null ? result : StorageUsageDto.zero();
        } catch (RuntimeException e) {
            // Do NOT degrade to zero here. The only consumer of this endpoint is
            // StorageReconciliationService, which writes the answer through setUsage, an
            // ABSOLUTE set: a swallowed failure does not report "this tenant stores
            // nothing", it ERASES the stored figure. Propagating makes the internal
            // endpoint answer 5xx, the client degrade to an empty result, and the
            // reconciler skip the category and keep the last good value until the next
            // nightly run. The log line stays because it names the tenant.
            log.warn("Failed to query publication storage for tenant {}: {}", tenantId, e.getMessage());
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
    // set, so the first nightly run after this ships moves PUBLICATIONS from 0 to its real
    // value for every tenant at once. Measured on prod 2026-09-21: 1823 kB in total across
    // the 86 tenants, and 822 kB for the single largest publisher, against 21.8 GB of
    // FILES. No tenant can cross a quota on a correction that small. Re-measure before
    // assuming that still holds.
    private static final RowMapper<StorageUsageDto> STORAGE_USAGE_MAPPER =
        (rs, rowNum) -> {
            long bytes = rs.getLong(1);
            int count = rs.getInt(2);
            return new StorageUsageDto(Math.max(0L, bytes), Math.max(0, count));
        };
}
