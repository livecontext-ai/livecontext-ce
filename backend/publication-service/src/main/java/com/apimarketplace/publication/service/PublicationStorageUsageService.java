package com.apimarketplace.publication.service;

import com.apimarketplace.common.storage.StorageUsageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
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
            Object[] result = jdbcTemplate.queryForObject(sql, Object[].class, tenantId, tenantId);
            if (result == null) return StorageUsageDto.zero();
            long bytes = result[0] instanceof Number n ? n.longValue() : 0;
            int count = result[1] instanceof Number n ? n.intValue() : 0;
            return new StorageUsageDto(Math.max(0, bytes), Math.max(0, count));
        } catch (Exception e) {
            log.warn("Failed to query publication storage for tenant {}: {}", tenantId, e.getMessage());
            return StorageUsageDto.zero();
        }
    }
}
