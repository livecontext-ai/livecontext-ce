package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.dto.ApiResponse;
import com.apimarketplace.catalog.dto.ToolUpdateDto;
import com.apimarketplace.catalog.util.AllowedValuesParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Service for managing API tool parameters.
 * Handles CRUD operations for path, query, header, and body parameters.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiToolParameterService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Get all parameters for a tool.
     */
    public List<ApiResponse.ParameterResponse> getToolParameters(UUID toolId) {
        String sql = "SELECT id, name, data_type, description, is_required, default_value, example_value, parameter_type, allowed_values FROM api_tool_parameters WHERE api_tool_id = ?";

        return jdbcTemplate.query(sql, (rs, rowNum) -> new ApiResponse.ParameterResponse(
                UUID.fromString(rs.getString("id")),
                rs.getString("name"),
                rs.getString("data_type"),
                rs.getString("description"),
                rs.getBoolean("is_required"),
                rs.getString("default_value"),
                rs.getString("example_value"),
                rs.getString("parameter_type"),
                AllowedValuesParser.parseString(rs.getString("allowed_values"))
        ), toolId);
    }

    /**
     * Update tool parameters from DTO.
     */
    @Transactional
    public void updateParametersFromDto(UUID toolId, ToolUpdateDto dto) {
        if (dto.getPathParameters() != null) {
            updateParametersByType(toolId, dto.getPathParameters(), "path");
        }
        if (dto.getQueryParameters() != null) {
            updateParametersByType(toolId, dto.getQueryParameters(), "query");
        }
        if (dto.getHeaders() != null) {
            updateParametersByType(toolId, dto.getHeaders(), "header");
        }
        if (dto.getBodyParams() != null) {
            updateParametersByType(toolId, dto.getBodyParams(), "body");
        }
    }

    /**
     * Update parameters by type (path, query, header, body).
     */
    @Transactional
    public void updateParametersByType(UUID toolId, List<Map<String, Object>> parameters, String parameterType) {
        log.debug("Updating {} parameters for tool {}", parameterType, toolId);

        List<UUID> existingIds = jdbcTemplate.queryForList(
                "SELECT id FROM api_tool_parameters WHERE api_tool_id = ? AND parameter_type = ?",
                UUID.class, toolId, parameterType
        );

        Set<UUID> usedIds = new HashSet<>();

        for (Map<String, Object> param : parameters) {
            String paramIdStr = (String) param.get("id");

            if (paramIdStr != null && !paramIdStr.isEmpty()) {
                if (paramIdStr.startsWith("temp-")) {
                    insertParameter(toolId, param, parameterType);
                } else {
                    try {
                        UUID paramId = UUID.fromString(paramIdStr);
                        usedIds.add(paramId);
                        updateParameter(paramId, param);
                    } catch (IllegalArgumentException e) {
                        insertParameter(toolId, param, parameterType);
                    }
                }
            } else {
                insertParameter(toolId, param, parameterType);
            }
        }

        // Delete unused parameters
        for (UUID existingId : existingIds) {
            if (!usedIds.contains(existingId)) {
                jdbcTemplate.update("DELETE FROM api_tool_parameters WHERE id = ?", existingId);
            }
        }
    }

    /**
     * Delete all parameters for a tool.
     */
    @Transactional
    public void deleteAllParameters(UUID toolId) {
        jdbcTemplate.update("DELETE FROM api_tool_parameters WHERE api_tool_id = ?", toolId);
    }

    private void updateParameter(UUID paramId, Map<String, Object> param) {
        String sql = "UPDATE api_tool_parameters SET name = ?, data_type = ?, description = ?, is_required = ?, example_value = ? WHERE id = ?";
        jdbcTemplate.update(sql,
                param.get("name"),
                param.get("type"),
                param.get("description"),
                param.get("required"),
                param.get("example"),
                paramId
        );
    }

    private void insertParameter(UUID toolId, Map<String, Object> param, String parameterType) {
        String sql = "INSERT INTO api_tool_parameters (api_tool_id, name, data_type, description, is_required, example_value, parameter_type, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        String name = Objects.requireNonNullElse((String) param.get("name"), "unnamed_parameter");
        String dataType = Objects.requireNonNullElse((String) param.get("type"), "string");
        String description = Objects.requireNonNullElse((String) param.get("description"), "");
        Boolean isRequired = Objects.requireNonNullElse((Boolean) param.get("required"), false);
        String exampleValue = Objects.requireNonNullElse((String) param.get("example"), "");

        jdbcTemplate.update(sql, toolId, name, dataType, description, isRequired, exampleValue, parameterType, System.currentTimeMillis());
    }
}
