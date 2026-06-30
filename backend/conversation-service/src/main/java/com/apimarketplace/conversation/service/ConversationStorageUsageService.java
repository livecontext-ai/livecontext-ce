package com.apimarketplace.conversation.service;

import com.apimarketplace.common.storage.StorageUsageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Calculates storage usage for conversation-related data in the conversation schema.
 */
@Service
public class ConversationStorageUsageService {

    private static final Logger log = LoggerFactory.getLogger(ConversationStorageUsageService.class);

    private final JdbcTemplate jdbcTemplate;

    public ConversationStorageUsageService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public StorageUsageDto getStorageUsage(String tenantId) {
        try {
            String sql = """
                SELECT COALESCE((
                    SELECT SUM(
                        COALESCE(octet_length(m.content), 0) +
                        COALESCE(octet_length(m.tool_calls), 0)
                    )
                    FROM messages m
                    JOIN conversations c ON m.conversation_id = c.id
                    WHERE c.user_id = ?
                ), 0) +
                COALESCE((
                    SELECT SUM(
                        COALESCE(octet_length(tr.content_full), 0) +
                        COALESCE(pg_column_size(tr.metadata), 0)
                    )
                    FROM tool_results tr
                    WHERE tr.tenant_id = ?
                ), 0),
                COALESCE((
                    SELECT COUNT(*) FROM conversations WHERE user_id = ?
                ), 0)
                """;
            // RowMapper handles the 2-column projection. queryForObject(sql, Class<T>) routes
            // via SingleColumnRowMapper which rejects multi-column results (was silently broken
            // for every tenant nightly - audit 2026-05-13).
            StorageUsageDto result = jdbcTemplate.queryForObject(sql, STORAGE_USAGE_MAPPER,
                tenantId, tenantId, tenantId);
            return result != null ? result : StorageUsageDto.zero();
        } catch (Exception e) {
            log.warn("Failed to query conversation storage for tenant {}: {}", tenantId, e.getMessage());
            return StorageUsageDto.zero();
        }
    }

    private static final org.springframework.jdbc.core.RowMapper<StorageUsageDto> STORAGE_USAGE_MAPPER =
        (rs, rowNum) -> {
            long bytes = rs.getLong(1);
            int count = rs.getInt(2);
            return new StorageUsageDto(Math.max(0L, bytes), Math.max(0, count));
        };
}
