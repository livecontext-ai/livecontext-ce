package com.apimarketplace.catalog.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApiVisibilityService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * List all APIs with tool counts and is_active status.
     * Joins with credentials metadata to get icon_slug, auth_type, and credential_name.
     */
    public List<Map<String, Object>> listIntegrations() {
        String sql = """
            SELECT
                a.id AS api_id,
                a.api_name,
                a.icon_slug,
                a.auth_type,
                a.platform_credential_name AS credential_name,
                a.is_active,
                a.category_id,
                tc.name AS category_name,
                c.properties AS credential_fields,
                COUNT(t.id) AS tool_count,
                COUNT(t.id) FILTER (WHERE t.is_active = true) AS active_tool_count
            FROM catalog.apis a
            LEFT JOIN catalog.api_tools t ON t.api_id = a.id
            LEFT JOIN catalog.tool_categories tc ON tc.id = a.category_id
            LEFT JOIN catalog.credentials c ON c.credential_name = a.platform_credential_name
            GROUP BY a.id, a.api_name, a.icon_slug, a.auth_type,
                     a.platform_credential_name, a.is_active, a.category_id, tc.name,
                     c.properties
            ORDER BY a.api_name ASC
            """;

        return jdbcTemplate.queryForList(sql).stream()
                .map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("apiId", Objects.toString(row.get("api_id"), null));
                    item.put("apiName", row.get("api_name"));
                    item.put("iconSlug", row.get("icon_slug"));
                    item.put("authType", row.get("auth_type"));
                    item.put("credentialName", row.get("credential_name"));
                    item.put("isActive", Boolean.TRUE.equals(row.get("is_active")));
                    item.put("toolCount", ((Number) row.getOrDefault("tool_count", 0)).intValue());
                    item.put("activeToolCount", ((Number) row.getOrDefault("active_tool_count", 0)).intValue());
                    item.put("category", row.get("category_name"));
                    // Credential fields from catalog.credentials.properties (JSON array)
                    Object credentialFields = row.get("credential_fields");
                    item.put("credentialFields", credentialFields != null ? credentialFields.toString() : null);
                    return item;
                })
                .toList();
    }

    /**
     * Toggle an API's is_active flag. When enabling, bulk-enable all its tools.
     * When disabling, bulk-disable all tools.
     */
    @Transactional
    public void toggleApi(UUID apiId, boolean isActive) {
        int updated = jdbcTemplate.update(
                "UPDATE catalog.apis SET is_active = ?, updated_at = EXTRACT(EPOCH FROM NOW()) * 1000 WHERE id = ?",
                isActive, apiId);

        if (updated == 0) {
            throw new NoSuchElementException("API not found: " + apiId);
        }

        int toolsUpdated = jdbcTemplate.update(
                "UPDATE catalog.api_tools SET is_active = ?, updated_at = EXTRACT(EPOCH FROM NOW()) * 1000 WHERE api_id = ?",
                isActive, apiId);

        log.info("Toggled API {} to is_active={}, cascaded to {} tools", apiId, isActive, toolsUpdated);
    }

    /**
     * List tools for a specific API with their is_active status.
     */
    public List<Map<String, Object>> listApiTools(UUID apiId) {
        String sql = """
            SELECT id, tool_name_id AS tool_name, tool_slug, description, is_active, method
            FROM catalog.api_tools
            WHERE api_id = ?
            ORDER BY tool_slug ASC
            """;

        return jdbcTemplate.queryForList(sql, apiId).stream()
                .map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("toolId", Objects.toString(row.get("id"), null));
                    item.put("toolName", row.get("tool_name"));
                    item.put("toolSlug", row.get("tool_slug"));
                    item.put("description", row.get("description"));
                    item.put("isActive", Boolean.TRUE.equals(row.get("is_active")));
                    item.put("method", row.get("method"));
                    return item;
                })
                .toList();
    }

    /**
     * Toggle a single tool's is_active flag.
     */
    @Transactional
    public void toggleTool(UUID toolId, boolean isActive) {
        int updated = jdbcTemplate.update(
                "UPDATE catalog.api_tools SET is_active = ?, updated_at = EXTRACT(EPOCH FROM NOW()) * 1000 WHERE id = ?",
                isActive, toolId);

        if (updated == 0) {
            throw new NoSuchElementException("Tool not found: " + toolId);
        }

        log.info("Toggled tool {} to is_active={}", toolId, isActive);
    }
}
