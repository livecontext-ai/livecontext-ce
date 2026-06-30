package com.apimarketplace.datasource.persistence.nested;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds SQL queries for nested array data extraction.
 *
 * Extracted from DataSourceNestedRepositories for Single Responsibility Principle.
 *
 * @see DataSourceNestedRepositories
 */
@Component
public class NestedArraySqlBuilder {

    private final JsonPathBuilder jsonPathBuilder;

    public NestedArraySqlBuilder(JsonPathBuilder jsonPathBuilder) {
        this.jsonPathBuilder = jsonPathBuilder;
    }

    /**
     * Build SELECT SQL for nested array data using jsonb_array_elements.
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
        sql.append("  dsi.id, ");
        sql.append("  dsi.data_source_id, ");
        sql.append("  dsi.tenant_id, ");
        sql.append("  dsi.priority, ");
        sql.append("  dsi.created_at, ");
        sql.append("  (elem.ordinality - 1)::int as array_index, ");
        sql.append("  elem.value as array_element ");
        sql.append("FROM filtered_items dsi, ");
        sql.append("  LATERAL jsonb_array_elements(")
           .append(jsonPathBuilder.buildJsonbPath("dsi.data", jsonPath, params))
           .append(") WITH ORDINALITY AS elem(value, ordinality) ");
        sql.append("WHERE ").append(jsonPathBuilder.buildJsonbPath("dsi.data", jsonPath, params)).append(" IS NOT NULL ");
        sql.append("  AND jsonb_typeof(").append(jsonPathBuilder.buildJsonbPath("dsi.data", jsonPath, params)).append(") = 'array' ");

        // Sort
        appendArraySortClause(sql, sortBy, sortOrder, params);

        // Pagination
        int offset = (page - 1) * limit;
        sql.append("LIMIT ? OFFSET ? ");
        params.add(limit);
        params.add(offset);

        return sql.toString();
    }

    /**
     * Build COUNT SQL for nested array data.
     *
     * @param jsonPath JSON path to count
     * @param params Output list for query parameters
     * @return SQL query string
     */
    public String buildCountSql(String jsonPath, List<Object> params) {
        StringBuilder sql = new StringBuilder();
        appendFilteredItemsCte(sql);
        sql.append("SELECT COALESCE(SUM(jsonb_array_length(")
           .append(jsonPathBuilder.buildJsonbPath(jsonPath, params))
           .append(")), 0) ");
        sql.append("FROM filtered_items ");
        sql.append("WHERE ").append(jsonPathBuilder.buildJsonbPath(jsonPath, params)).append(" IS NOT NULL ");
        sql.append("  AND jsonb_typeof(").append(jsonPathBuilder.buildJsonbPath(jsonPath, params)).append(") = 'array' ");
        return sql.toString();
    }

    /**
     * Build SELECT SQL for array column definition analysis.
     *
     * @param jsonPath JSON path to analyze
     * @param params Output list for query parameters
     * @return SQL query string
     */
    public String buildColumnAnalysisSql(String jsonPath, List<Object> params) {
        StringBuilder sql = new StringBuilder();
        appendFilteredItemsCte(sql);
        sql.append("SELECT DISTINCT jsonb_array_elements(")
           .append(jsonPathBuilder.buildJsonbPath(jsonPath, params))
           .append(") as array_element ");
        sql.append("FROM filtered_items ");
        sql.append("WHERE ").append(jsonPathBuilder.buildJsonbPath(jsonPath, params)).append(" IS NOT NULL ");
        sql.append("  AND jsonb_typeof(").append(jsonPathBuilder.buildJsonbPath(jsonPath, params)).append(") = 'array' ");
        sql.append("LIMIT ? ");
        return sql.toString();
    }

    /**
     * Append array-specific sort clause to SQL.
     */
    private void appendArraySortClause(StringBuilder sql, String sortBy, String sortOrder, List<Object> params) {
        if (sortBy != null && !sortBy.trim().isEmpty()) {
            // Always group by parent ID first, then apply the requested sort
            if (sortBy.equals("array_index")) {
                sql.append("ORDER BY dsi.id ASC, array_index ");
                appendSortDirection(sql, sortOrder);
            } else if (sortBy.equals("id")) {
                sql.append("ORDER BY dsi.id ");
                if (sortOrder != null && sortOrder.equalsIgnoreCase("desc")) {
                    sql.append("DESC, array_index DESC ");
                } else {
                    sql.append("ASC, array_index ASC ");
                }
            } else if (sortBy.equals("priority")) {
                sql.append("ORDER BY dsi.priority ");
                if (sortOrder != null && sortOrder.equalsIgnoreCase("desc")) {
                    sql.append("DESC, dsi.id ASC, array_index ASC ");
                } else {
                    sql.append("ASC, dsi.id ASC, array_index ASC ");
                }
            } else if (sortBy.equals("created_at")) {
                sql.append("ORDER BY dsi.created_at ");
                if (sortOrder != null && sortOrder.equalsIgnoreCase("desc")) {
                    sql.append("DESC, dsi.id ASC, array_index ASC ");
                } else {
                    sql.append("ASC, dsi.id ASC, array_index ASC ");
                }
            } else if (sortBy.equals("value")) {
                // For primitive arrays, sort by elem.value with appropriate cast
                sql.append("ORDER BY dsi.id ASC, elem.value::text ");
                appendSortDirection(sql, sortOrder);
            } else {
                // For object arrays, sort by a bound object field name.
                sql.append("ORDER BY dsi.id ASC, CASE WHEN jsonb_typeof(elem.value) = 'object' THEN ")
                   .append(jsonPathBuilder.buildJsonbTextPath("elem.value", sortBy, params))
                   .append(" ELSE NULL END ");
                if (sortOrder != null && sortOrder.equalsIgnoreCase("desc")) {
                    sql.append("DESC NULLS LAST ");
                } else {
                    sql.append("ASC NULLS FIRST ");
                }
            }
        } else {
            // Default sort: group by parent (priority DESC, id ASC) then by array index
            sql.append("ORDER BY dsi.priority DESC, dsi.id ASC, array_index ASC ");
        }
    }

    private void appendFilteredItemsCte(StringBuilder sql) {
        sql.append("WITH filtered_items AS (");
        sql.append("  SELECT id, data_source_id, tenant_id, data, priority, created_at ");
        sql.append("  FROM data_source_items ");
        sql.append("  WHERE tenant_id = ? AND data_source_id = ? ");
        sql.append(") ");
    }

    /**
     * Append sort direction.
     */
    private void appendSortDirection(StringBuilder sql, String sortOrder) {
        if (sortOrder != null && sortOrder.equalsIgnoreCase("desc")) {
            sql.append("DESC ");
        } else {
            sql.append("ASC ");
        }
    }
}
