package com.apimarketplace.agent.service;

import com.apimarketplace.common.storage.StorageUsageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Calculates storage usage for agent-related data in the agent schema.
 * Returns per-category bytes and item counts for reconciliation.
 */
@Service
public class AgentStorageUsageService {

    private static final Logger log = LoggerFactory.getLogger(AgentStorageUsageService.class);

    private final JdbcTemplate jdbcTemplate;

    public AgentStorageUsageService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Get storage usage for AGENTS and SKILLS categories.
     */
    public Map<String, StorageUsageDto> getStorageUsage(String tenantId) {
        StorageUsageDto agents = queryAgentsUsage(tenantId);
        StorageUsageDto skills = querySkillsUsage(tenantId);
        return Map.of("AGENTS", agents, "SKILLS", skills);
    }

    private StorageUsageDto queryAgentsUsage(String tenantId) {
        try {
            String sql = """
                SELECT COALESCE(SUM(
                    COALESCE(octet_length(a.system_prompt), 0) +
                    COALESCE(pg_column_size(a.tools_config), 0) +
                    COALESCE(pg_column_size(a.config), 0)
                ), 0) +
                COALESCE((
                    SELECT SUM(COALESCE(octet_length(aem.content), 0))
                    FROM agent_execution_messages aem
                    WHERE aem.tenant_id = ?
                ), 0) +
                COALESCE((
                    SELECT SUM(
                        COALESCE(pg_column_size(aetc.arguments), 0) +
                        COALESCE(octet_length(aetc.content), 0)
                    )
                    FROM agent_execution_tool_calls aetc
                    WHERE aetc.tenant_id = ?
                ), 0),
                (SELECT COUNT(*) FROM agents WHERE tenant_id = ?)
                FROM agents a
                WHERE a.tenant_id = ?
                """;
            // RowMapper handles the 2-column projection. queryForObject(sql, Class<T>) routes
            // via SingleColumnRowMapper which rejects multi-column results (was silently broken
            // for every tenant nightly - audit 2026-05-13).
            StorageUsageDto result = jdbcTemplate.queryForObject(sql, STORAGE_USAGE_MAPPER,
                tenantId, tenantId, tenantId, tenantId);
            return result != null ? result : StorageUsageDto.zero();
        } catch (Exception e) {
            log.warn("Failed to query agents storage for tenant {}: {}", tenantId, e.getMessage());
            return StorageUsageDto.zero();
        }
    }

    private StorageUsageDto querySkillsUsage(String tenantId) {
        try {
            String sql = """
                SELECT COALESCE(SUM(octet_length(sk.instructions)), 0),
                       COUNT(*)
                FROM skills sk
                WHERE sk.tenant_id = ?
                """;
            StorageUsageDto result = jdbcTemplate.queryForObject(sql, STORAGE_USAGE_MAPPER, tenantId);
            return result != null ? result : StorageUsageDto.zero();
        } catch (Exception e) {
            log.warn("Failed to query skills storage for tenant {}: {}", tenantId, e.getMessage());
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
