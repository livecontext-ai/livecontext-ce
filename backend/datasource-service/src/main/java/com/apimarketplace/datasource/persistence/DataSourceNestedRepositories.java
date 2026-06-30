package com.apimarketplace.datasource.persistence;

import com.apimarketplace.datasource.domain.DataSourceNestedModels.*;
import com.apimarketplace.datasource.persistence.nested.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

/**
 * Repository for nested JSON data extraction in DataSource items.
 * Supports hierarchical navigation through JSON structures using PostgreSQL JSONB.
 *
 * <h2>Architecture</h2>
 * This repository delegates to specialized components:
 * <ul>
 *   <li>{@link JsonPathBuilder} - Builds PostgreSQL JSONB paths</li>
 *   <li>{@link NestedDataAnalyzer} - Analyzes JSON structure (object vs array)</li>
 *   <li>{@link NestedObjectSqlBuilder} - Builds SQL for object data</li>
 *   <li>{@link NestedArraySqlBuilder} - Builds SQL for array data</li>
 *   <li>{@link NestedColumnDefinitionBuilder} - Builds column definitions</li>
 *   <li>{@link NestedRowMapper} - Maps object rows</li>
 *   <li>{@link NestedArrayRowMapper} - Maps array rows</li>
 * </ul>
 */
@Repository
public class DataSourceNestedRepositories {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final JsonPathBuilder jsonPathBuilder;
    private final NestedDataAnalyzer dataAnalyzer;
    private final NestedObjectSqlBuilder objectSqlBuilder;
    private final NestedArraySqlBuilder arraySqlBuilder;
    private final NestedColumnDefinitionBuilder columnDefinitionBuilder;

    public DataSourceNestedRepositories(
            JdbcTemplate jdbcTemplate,
            JsonPathBuilder jsonPathBuilder,
            NestedDataAnalyzer dataAnalyzer,
            NestedObjectSqlBuilder objectSqlBuilder,
            NestedArraySqlBuilder arraySqlBuilder,
            NestedColumnDefinitionBuilder columnDefinitionBuilder) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = new ObjectMapper();
        this.jsonPathBuilder = jsonPathBuilder;
        this.dataAnalyzer = dataAnalyzer;
        this.objectSqlBuilder = objectSqlBuilder;
        this.arraySqlBuilder = arraySqlBuilder;
        this.columnDefinitionBuilder = columnDefinitionBuilder;
    }

    /**
     * Extract nested data from items at a specific JSON path.
     * Supports both objects and arrays automatically.
     *
     * @param dataSourceId ID of the data source
     * @param tenantId Tenant ID for isolation
     * @param jsonPath JSON path to extract (e.g., "metadata.user.profile" or "tags")
     * @param page Page number (1-based)
     * @param limit Page size
     * @param sortBy Sort column (optional)
     * @param sortOrder Sort direction (asc/desc, optional)
     * @return List of nested rows
     */
    public List<DataSourceNestedRow> findNestedData(
            Long dataSourceId,
            String tenantId,
            String jsonPath,
            Integer page,
            Integer limit,
            String sortBy,
            String sortOrder) {

        // Detect path type (object or array)
        String pathType = dataAnalyzer.detectPathType(dataSourceId, tenantId, jsonPath);

        // If array, use array query
        if ("array".equals(pathType)) {
            return findNestedArrayData(dataSourceId, tenantId, jsonPath, page, limit, sortBy, sortOrder);
        }

        // Object query
        return findNestedObjectData(dataSourceId, tenantId, jsonPath, page, limit, sortBy, sortOrder);
    }

    /**
     * Find nested object data.
     */
    private List<DataSourceNestedRow> findNestedObjectData(
            Long dataSourceId,
            String tenantId,
            String jsonPath,
            Integer page,
            Integer limit,
            String sortBy,
            String sortOrder) {

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(dataSourceId);

        String sql = objectSqlBuilder.buildSelectSql(jsonPath, sortBy, sortOrder, page, limit, params);

        return jdbcTemplate.query(sql,
            new NestedRowMapper(jsonPath, objectMapper),
            params.toArray());
    }

    /**
     * Find nested array data.
     */
    private List<DataSourceNestedRow> findNestedArrayData(
            Long dataSourceId,
            String tenantId,
            String jsonPath,
            Integer page,
            Integer limit,
            String sortBy,
            String sortOrder) {

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(dataSourceId);

        String sql = arraySqlBuilder.buildSelectSql(jsonPath, sortBy, sortOrder, page, limit, params);

        return jdbcTemplate.query(sql,
            new NestedArrayRowMapper(jsonPath, objectMapper),
            params.toArray());
    }

    /**
     * Count nested data rows at a specific JSON path.
     * Supports both objects and arrays automatically.
     *
     * @param dataSourceId ID of the data source
     * @param tenantId Tenant ID for isolation
     * @param jsonPath JSON path to extract
     * @return Total count
     */
    public int countNestedData(Long dataSourceId, String tenantId, String jsonPath) {
        // Detect path type
        String pathType = dataAnalyzer.detectPathType(dataSourceId, tenantId, jsonPath);

        // If array, count array elements
        if ("array".equals(pathType)) {
            List<Object> params = new ArrayList<>();
            params.add(tenantId);
            params.add(dataSourceId);
            String sql = arraySqlBuilder.buildCountSql(jsonPath, params);
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, params.toArray());
            return count != null ? count : 0;
        }

        // Count objects
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(dataSourceId);
        String sql = objectSqlBuilder.buildCountSql(jsonPath, params);
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, params.toArray());
        return count != null ? count : 0;
    }

    /**
     * Get column definitions for nested data at a specific JSON path.
     * Analyzes the JSON structure to determine columns dynamically.
     * Supports both objects and arrays automatically.
     *
     * @param dataSourceId ID of the data source
     * @param tenantId Tenant ID for isolation
     * @param jsonPath JSON path to analyze
     * @param limit Maximum number of items to analyze
     * @return List of column definitions
     */
    public List<NestedColumnDefinition> getNestedColumnDefinitions(
            Long dataSourceId,
            String tenantId,
            String jsonPath,
            int limit) {

        // Detect path type
        String pathType = dataAnalyzer.detectPathType(dataSourceId, tenantId, jsonPath);

        // If array, analyze array elements
        if ("array".equals(pathType)) {
            return getArrayColumnDefinitions(dataSourceId, tenantId, jsonPath, limit);
        }

        // Analyze object data
        return getObjectColumnDefinitions(dataSourceId, tenantId, jsonPath, limit);
    }

    /**
     * Get column definitions for object data.
     */
    private List<NestedColumnDefinition> getObjectColumnDefinitions(
            Long dataSourceId,
            String tenantId,
            String jsonPath,
            int limit) {

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(dataSourceId);
        String sql = objectSqlBuilder.buildColumnAnalysisSql(jsonPath, params);
        params.add(limit);

        List<Map<String, Object>> sampleData = jdbcTemplate.query(
            sql,
            (rs, rowNum) -> {
                try {
                    String jsonStr = rs.getString("nested_data");
                    if (jsonStr != null) {
                        return objectMapper.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
                    }
                    return new HashMap<String, Object>();
                } catch (Exception e) {
                    return new HashMap<String, Object>();
                }
            },
            params.toArray()
        );

        return columnDefinitionBuilder.buildFromObjectData(sampleData);
    }

    /**
     * Get column definitions for array data.
     */
    private List<NestedColumnDefinition> getArrayColumnDefinitions(
            Long dataSourceId,
            String tenantId,
            String jsonPath,
            int limit) {

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(dataSourceId);
        String sql = arraySqlBuilder.buildColumnAnalysisSql(jsonPath, params);
        params.add(limit);

        List<Object> sampleElements = jdbcTemplate.queryForList(
            sql,
            Object.class,
            params.toArray()
        );

        return columnDefinitionBuilder.buildFromArrayData(sampleElements);
    }
}
