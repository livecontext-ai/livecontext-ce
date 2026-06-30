package com.apimarketplace.datasource.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Domain models for nested JSON navigation in DataSource tables
 * Supports hierarchical navigation through JSON structures
 */
public final class DataSourceNestedModels {
    private DataSourceNestedModels() {}

    /**
     * Nested DataSource item row with JSON path information
     */
    public record DataSourceNestedRow(
            Long id, // ID of the parent item
            @JsonProperty("data_source_id") Long dataSourceId,
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("json_path") String jsonPath, // Full JSON path (e.g., "metadata.user.profile")
            @JsonProperty("parent_path") String parentPath, // Parent path (e.g., "metadata.user")
            Map<String, Object> data, // Extracted data at the path
            Integer priority,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt
    ) {
        public DataSourceNestedRow {
            Objects.requireNonNull(id, "id cannot be null");
            Objects.requireNonNull(dataSourceId, "dataSourceId cannot be null");
            Objects.requireNonNull(tenantId, "tenantId cannot be null");
            Objects.requireNonNull(data, "data cannot be null");
            Objects.requireNonNull(priority, "priority cannot be null");
            Objects.requireNonNull(createdAt, "createdAt cannot be null");
            // jsonPath, parentPath, and updatedAt can be null
        }
    }

    /**
     * Nested pagination response with path information
     */
    public record NestedPaginationResponse<T>(
            List<T> rowData,
            @JsonProperty("row_count") Integer rowCount,
            @JsonProperty("next_cursor") String nextCursor,
            @JsonProperty("has_more") Boolean hasMore,
            @JsonProperty("total_pages") Integer totalPages,
            @JsonProperty("current_path") String currentPath, // Current JSON path
            @JsonProperty("parent_path") String parentPath, // Parent path for breadcrumb
            @JsonProperty("path_segments") List<String> pathSegments // Path segments for breadcrumb
    ) {
        public NestedPaginationResponse {
            Objects.requireNonNull(rowData, "rowData cannot be null");
            if (rowCount == null) rowCount = rowData.size();
            if (hasMore == null) hasMore = nextCursor != null;
            if (totalPages == null) totalPages = 1;
            // currentPath, parentPath, and pathSegments can be null
        }
    }

    /**
     * Column definition with navigation support
     */
    public record NestedColumnDefinition(
            @JsonProperty("col_id") String colId,
            @JsonProperty("header_name") String headerName,
            @JsonProperty("field") String field,
            @JsonProperty("type") String type,
            @JsonProperty("editable") Boolean editable,
            @JsonProperty("sortable") Boolean sortable,
            @JsonProperty("filterable") Boolean filterable,
            @JsonProperty("is_navigable") Boolean isNavigable, // True if JSON object can be navigated
            @JsonProperty("width") Integer width,
            @JsonProperty("flex") Integer flex
    ) {
        public NestedColumnDefinition {
            Objects.requireNonNull(colId, "colId cannot be null");
            Objects.requireNonNull(headerName, "headerName cannot be null");
            Objects.requireNonNull(field, "field cannot be null");
            Objects.requireNonNull(type, "type cannot be null");
            if (editable == null) editable = true;
            if (sortable == null) sortable = true;
            if (filterable == null) filterable = true;
            if (isNavigable == null) isNavigable = false;
        }
    }

    /**
     * Request for nested data extraction
     */
    public record NestedDataRequest(
            @JsonProperty("json_path") String jsonPath, // JSON path to extract (e.g., "metadata.user")
            @JsonProperty("page") Integer page,
            @JsonProperty("limit") Integer limit,
            @JsonProperty("sort_by") String sortBy,
            @JsonProperty("sort_order") String sortOrder,
            @JsonProperty("cursor") String cursor
    ) {
        public NestedDataRequest {
            if (page == null) page = 1;
            if (limit == null) limit = 20;
            if (limit > 500) limit = 500; // Max 500 rows
        }
    }

    /**
     * Update request for nested JSON value
     */
    public record NestedUpdateRequest(
            @JsonProperty("json_path") String jsonPath, // JSON path to the nested object
            @JsonProperty("field_path") String fieldPath, // Field path within the nested object
            @JsonProperty("value") Object value // New value
    ) {
        public NestedUpdateRequest {
            Objects.requireNonNull(jsonPath, "jsonPath cannot be null");
            Objects.requireNonNull(fieldPath, "fieldPath cannot be null");
            Objects.requireNonNull(value, "value cannot be null");
        }
    }
}

