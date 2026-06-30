package com.apimarketplace.datasource.persistence.nested;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds SQL queries for nested object data extraction.
 *
 * Extracted from DataSourceNestedRepositories for Single Responsibility Principle.
 *
 * @see DataSourceNestedRepositories
 */
@Component
public class NestedObjectSqlBuilder {

    private final JsonPathBuilder jsonPathBuilder;

    public NestedObjectSqlBuilder(JsonPathBuilder jsonPathBuilder) {
        this.jsonPathBuilder = jsonPathBuilder;
    }

    /**
     * Build SELECT SQL for nested object data.
     *
     * @param jsonPath JSON path to extract
     * @param sortBy Sort column (optional)
     * @param sortOrder Sort direction (asc/desc)
     * @param page Page number (1-based)
     * @param limit Page size
     * @param params Output list for query parameters
     * @return SQL query string
     */
    public String buildSelectSql(
            String jsonPath,
            String sortBy,
            String sortOrder,
            Integer page,
            Integer limit,
            List<Object> params) {

        StringBuilder sql = new StringBuilder();
        appendFilteredItemsCte(sql);
        sql.append("SELECT ");
        sql.append("  id, ");
        sql.append("  data_source_id, ");
        sql.append("  tenant_id, ");
        sql.append("  ").append(jsonPathBuilder.buildJsonbPath(jsonPath, params)).append(" as nested_data, ");
        sql.append("  priority, ");
        sql.append("  created_at ");
        sql.append("FROM filtered_items ");
        sql.append("WHERE ").append(jsonPathBuilder.buildJsonbPath(jsonPath, params)).append(" IS NOT NULL ");
        sql.append("  AND jsonb_typeof(").append(jsonPathBuilder.buildJsonbPath(jsonPath, params)).append(") = 'object' ");

        // Sort
        appendSortClause(sql, jsonPath, sortBy, sortOrder, params);

        // Pagination
        int offset = (page - 1) * limit;
        sql.append("LIMIT ? OFFSET ? ");
        params.add(limit);
        params.add(offset);

        return sql.toString();
    }

    /**
     * Build COUNT SQL for nested object data.
     *
     * @param jsonPath JSON path to count
     * @param params Output list for query parameters
     * @return SQL query string
     */
    public String buildCountSql(String jsonPath, List<Object> params) {
        StringBuilder sql = new StringBuilder();
        appendFilteredItemsCte(sql);
        sql.append("SELECT COUNT(*) ");
        sql.append("FROM filtered_items ");
        sql.append("WHERE ").append(jsonPathBuilder.buildJsonbPath(jsonPath, params)).append(" IS NOT NULL ");
        sql.append("  AND jsonb_typeof(").append(jsonPathBuilder.buildJsonbPath(jsonPath, params)).append(") = 'object' ");
        return sql.toString();
    }

    /**
     * Build SELECT SQL for column definition analysis.
     *
     * @param jsonPath JSON path to analyze
     * @param params Output list for query parameters
     * @return SQL query string
     */
    public String buildColumnAnalysisSql(String jsonPath, List<Object> params) {
        StringBuilder sql = new StringBuilder();
        appendFilteredItemsCte(sql);
        sql.append("SELECT ").append(jsonPathBuilder.buildJsonbPath(jsonPath, params)).append(" as nested_data ");
        sql.append("FROM filtered_items ");
        sql.append("WHERE ").append(jsonPathBuilder.buildJsonbPath(jsonPath, params)).append(" IS NOT NULL ");
        sql.append("  AND jsonb_typeof(").append(jsonPathBuilder.buildJsonbPath(jsonPath, params)).append(") = 'object' ");
        sql.append("LIMIT ? ");
        return sql.toString();
    }

    /**
     * Append sort clause to SQL.
     */
    private void appendSortClause(
            StringBuilder sql,
            String jsonPath,
            String sortBy,
            String sortOrder,
            List<Object> params) {
        if (sortBy != null && !sortBy.trim().isEmpty()) {
            String sortSource = jsonPathBuilder.buildJsonbPath(jsonPath, params);
            String sortPath = jsonPathBuilder.buildJsonbTextPath(sortSource, sortBy, params);
            sql.append("ORDER BY ").append(sortPath).append(" ");
            if (sortOrder != null && sortOrder.equalsIgnoreCase("desc")) {
                sql.append("DESC ");
            } else {
                sql.append("ASC ");
            }
        } else {
            sql.append("ORDER BY priority DESC, id ASC ");
        }
    }

    private void appendFilteredItemsCte(StringBuilder sql) {
        sql.append("WITH filtered_items AS (");
        sql.append("  SELECT id, data_source_id, tenant_id, data, priority, created_at ");
        sql.append("  FROM data_source_items ");
        sql.append("  WHERE tenant_id = ? AND data_source_id = ? ");
        sql.append(") ");
    }
}
