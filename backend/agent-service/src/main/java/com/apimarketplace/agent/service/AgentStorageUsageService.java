package com.apimarketplace.agent.service;

import com.apimarketplace.common.storage.StorageUsageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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
     * Get storage usage for the AGENTS, SKILLS and MEMORIES categories.
     */
    public Map<String, StorageUsageDto> getStorageUsage(String tenantId) {
        // A category that could not be measured is OMITTED, never reported as zero.
        // Both halves of that sentence matter, and they pull in opposite directions:
        //
        //  - Omitting rather than zeroing, because the consumer
        //    (StorageReconciliationService) writes these through setUsage, an ABSOLUTE
        //    set. A zero does not degrade the stored figure, it erases it. Both readers
        //    already test for the key's presence, so an absent key makes them skip.
        //  - Per category rather than for the whole call, because these are three
        //    independent queries and a failing memory query must still cost only the
        //    memory line. That resilience is deliberate and tested; propagating out of
        //    here would throw away two good measurements to report one bad one.
        Map<String, StorageUsageDto> usage = new LinkedHashMap<>();
        queryAgentsUsage(tenantId).ifPresent(v -> usage.put("AGENTS", v));
        querySkillsUsage(tenantId).ifPresent(v -> usage.put("SKILLS", v));
        queryMemoriesUsage(tenantId).ifPresent(v -> usage.put("MEMORIES", v));
        return usage;
    }

    private Optional<StorageUsageDto> queryAgentsUsage(String tenantId) {
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
            return Optional.ofNullable(result);
        } catch (RuntimeException e) {
            // Omit this category rather than reporting zero for it: see getStorageUsage.
            // Returning here instead of rethrowing keeps the other two categories, which
            // are separate queries and may well have succeeded.
            log.warn("Failed to query agents storage for tenant {}: {}", tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<StorageUsageDto> querySkillsUsage(String tenantId) {
        try {
            String sql = """
                SELECT COALESCE(SUM(octet_length(sk.instructions)), 0),
                       COUNT(*)
                FROM skills sk
                WHERE sk.tenant_id = ?
                """;
            StorageUsageDto result = jdbcTemplate.queryForObject(sql, STORAGE_USAGE_MAPPER, tenantId);
            return Optional.ofNullable(result);
        } catch (RuntimeException e) {
            // Omit this category rather than reporting zero for it: see getStorageUsage.
            // Returning here instead of rethrowing keeps the other two categories, which
            // are separate queries and may well have succeeded.
            log.warn("Failed to query skills storage for tenant {}: {}", tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Long-term memory bytes. Counted with skills under CONFIGURATION by the
     * reconciler: both are small authored text that an account accumulates
     * deliberately, and splitting them into their own quota line would give a
     * user two numbers to reason about for one behaviour.
     */
    private Optional<StorageUsageDto> queryMemoriesUsage(String tenantId) {
        try {
            String sql = """
                SELECT COALESCE(SUM(
                           COALESCE(octet_length(m.content), 0) +
                           COALESCE(octet_length(m.summary), 0) +
                           COALESCE(octet_length(m.title), 0)
                       ), 0),
                       COUNT(*)
                FROM agent_memories m
                WHERE m.tenant_id = ?
                """;
            StorageUsageDto result = jdbcTemplate.queryForObject(sql, STORAGE_USAGE_MAPPER, tenantId);
            return Optional.ofNullable(result);
        } catch (RuntimeException e) {
            // Omit this category rather than reporting zero for it: see getStorageUsage.
            // Returning here instead of rethrowing keeps the other two categories, which
            // are separate queries and may well have succeeded.
            log.warn("Failed to query memories storage for tenant {}: {}", tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    private static final org.springframework.jdbc.core.RowMapper<StorageUsageDto> STORAGE_USAGE_MAPPER =
        (rs, rowNum) -> {
            long bytes = rs.getLong(1);
            int count = rs.getInt(2);
            return new StorageUsageDto(Math.max(0L, bytes), Math.max(0, count));
        };
}
