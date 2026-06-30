package com.apimarketplace.datasource.domain;

import com.apimarketplace.datasource.domain.ColumnStructure;
import com.apimarketplace.datasource.domain.ColumnType;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Enhanced domain models for DataSource functionality with AG Grid support
 * Includes DTOs for server-side pagination, filtering, sorting, and JSON patch operations
 */
public final class DataSourceEnhancedModels {
    private DataSourceEnhancedModels() {}

    // Enhanced DataSourceItem with additional fields for grid display
    public record DataSourceItemRow(
            Long id,
            @JsonProperty("data_source_id") Long dataSourceId,
            @JsonProperty("tenant_id") String tenantId,
            Map<String, Object> data,
            Integer priority,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt
    ) {
        public DataSourceItemRow {
            Objects.requireNonNull(id, "id cannot be null");
            Objects.requireNonNull(dataSourceId, "dataSourceId cannot be null");
            Objects.requireNonNull(tenantId, "tenantId cannot be null");
            Objects.requireNonNull(data, "data cannot be null");
            Objects.requireNonNull(priority, "priority cannot be null");
            Objects.requireNonNull(createdAt, "createdAt cannot be null");
            // updatedAt can be null since the column doesn't exist in the database
        }
    }

    // Server-side pagination request
    public record PaginationRequest(
            @JsonProperty("start_row") Integer startRow,
            @JsonProperty("end_row") Integer endRow,
            @JsonProperty("limit") Integer limit,
            @JsonProperty("cursor") String cursor,
            @JsonProperty("sort") List<SortRequest> sort,
            @JsonProperty("filter") Map<String, Object> filter,
            @JsonProperty("q") String query
    ) {
        public PaginationRequest {
            if (startRow == null) startRow = 0;
            if (endRow == null) endRow = 100;
            if (limit == null) limit = Math.min(endRow - startRow, 500); // Max 500 rows
            if (limit > 500) limit = 500;
        }
    }

    // Sort request for individual columns
    public record SortRequest(
            @JsonProperty("col_id") String colId,
            @JsonProperty("sort") SortDirection sort
    ) {
        public SortRequest {
            Objects.requireNonNull(colId, "colId cannot be null");
            Objects.requireNonNull(sort, "sort cannot be null");
        }
    }

    // Sort direction enum
    public enum SortDirection {
        ASC("asc"),
        DESC("desc");

        private final String value;

        SortDirection(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @JsonCreator
        public static SortDirection fromValue(String value) {
            for (SortDirection direction : values()) {
                if (direction.value.equalsIgnoreCase(value)) {
                    return direction;
                }
            }
            throw new IllegalArgumentException("Invalid sort direction: " + value);
        }
    }

    // Server-side pagination response
    public record PaginationResponse<T>(
            List<T> rowData,
            @JsonProperty("row_count") Integer rowCount,
            @JsonProperty("next_cursor") String nextCursor,
            @JsonProperty("has_more") Boolean hasMore,
            @JsonProperty("total_pages") Integer totalPages
    ) {
        public PaginationResponse {
            Objects.requireNonNull(rowData, "rowData cannot be null");
            if (rowCount == null) rowCount = rowData.size();
            if (hasMore == null) hasMore = nextCursor != null;
            if (totalPages == null) totalPages = 1; // Default to 1 page
        }
    }

    // JSON Patch operation
    public record JsonPatchOperation(
            @JsonProperty("op") PatchOperation op,
            @JsonProperty("path") String path,
            @JsonProperty("value") Object value,
            @JsonProperty("from") String from
    ) {
        public JsonPatchOperation {
            Objects.requireNonNull(op, "op cannot be null");
            Objects.requireNonNull(path, "path cannot be null");
        }
    }

    // Patch operation types
    public enum PatchOperation {
        ADD("add"),
        REMOVE("remove"),
        REPLACE("replace"),
        MOVE("move"),
        COPY("copy"),
        TEST("test");

        private final String value;

        PatchOperation(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @JsonCreator
        public static PatchOperation fromValue(String value) {
            for (PatchOperation op : values()) {
                if (op.value.equals(value)) {
                    return op;
                }
            }
            throw new IllegalArgumentException("Invalid patch operation: " + value);
        }
    }

    // Bulk operation request
    public record BulkOperationRequest(
            @JsonProperty("op") BulkOperationType op,
            @JsonProperty("ids") List<Long> ids,
            @JsonProperty("patch") List<JsonPatchOperation> patch
    ) {
        public BulkOperationRequest {
            Objects.requireNonNull(op, "op cannot be null");
            Objects.requireNonNull(ids, "ids cannot be null");
            if (ids.isEmpty()) {
                throw new IllegalArgumentException("ids cannot be empty");
            }
        }
    }

    // Bulk operation types
    public enum BulkOperationType {
        DELETE("delete"),
        PATCH("patch");

        private final String value;

        BulkOperationType(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @JsonCreator
        public static BulkOperationType fromValue(String value) {
            for (BulkOperationType type : values()) {
                if (type.value.equals(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Invalid bulk operation type: " + value);
        }
    }

    // Bulk operation result
    public record BulkOperationResult(
            @JsonProperty("success") Boolean success,
            @JsonProperty("processed_count") Integer processedCount,
            @JsonProperty("failed_count") Integer failedCount,
            @JsonProperty("errors") List<String> errors,
            @JsonProperty("results") List<ItemOperationResult> results
    ) {
        public BulkOperationResult {
            Objects.requireNonNull(success, "success cannot be null");
            if (processedCount == null) processedCount = 0;
            if (failedCount == null) failedCount = 0;
            if (errors == null) errors = List.of();
            if (results == null) results = List.of();
        }
    }

    // Individual item operation result
    public record ItemOperationResult(
            @JsonProperty("id") Long id,
            @JsonProperty("success") Boolean success,
            @JsonProperty("error") String error
    ) {
        public ItemOperationResult {
            Objects.requireNonNull(id, "id cannot be null");
            Objects.requireNonNull(success, "success cannot be null");
        }
    }

    // Column management request
    public record ColumnManagementRequest(
            @JsonProperty("op") ColumnOperation op,
            @JsonProperty("key") String key,
            @JsonProperty("new_key") String newKey,
            @JsonProperty("default_value") Object defaultValue,
            @JsonProperty("display") Map<String, Object> display
    ) {
        public ColumnManagementRequest {
            Objects.requireNonNull(op, "op cannot be null");
            Objects.requireNonNull(key, "key cannot be null");
        }
    }

    // Column operation types
    public enum ColumnOperation {
        DROP("drop"),
        RENAME("rename"),
        SET_DEFAULT("set_default"),
        UPDATE_DISPLAY("update_display");

        private final String value;

        ColumnOperation(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @JsonCreator
        public static ColumnOperation fromValue(String value) {
            for (ColumnOperation op : values()) {
                if (op.value.equals(value)) {
                    return op;
                }
            }
            throw new IllegalArgumentException("Invalid column operation: " + value);
        }
    }

    // Column management result
    public record ColumnManagementResult(
            @JsonProperty("success") Boolean success,
            @JsonProperty("affected_rows") Integer affectedRows,
            @JsonProperty("error") String error
    ) {
        public ColumnManagementResult {
            Objects.requireNonNull(success, "success cannot be null");
            if (affectedRows == null) affectedRows = 0;
        }
    }

    // Filter request for complex filtering
    public record FilterRequest(
            @JsonProperty("field") String field,
            @JsonProperty("operator") FilterOperator operator,
            @JsonProperty("value") Object value,
            @JsonProperty("values") List<Object> values
    ) {
        public FilterRequest {
            Objects.requireNonNull(field, "field cannot be null");
            Objects.requireNonNull(operator, "operator cannot be null");
        }
    }

    // Filter operators
    public enum FilterOperator {
        EQUALS("="),
        NOT_EQUALS("!="),
        GREATER_THAN(">"),
        GREATER_THAN_OR_EQUAL(">="),
        LESS_THAN("<"),
        LESS_THAN_OR_EQUAL("<="),
        IN("IN"),
        NOT_IN("NOT IN"),
        LIKE("LIKE"),
        ILIKE("ILIKE"),
        CONTAINS("CONTAINS"),
        EXISTS("EXISTS"),
        IS_NULL("IS NULL"),
        IS_NOT_NULL("IS NOT NULL");

        private final String value;

        FilterOperator(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @JsonCreator
        public static FilterOperator fromValue(String value) {
            for (FilterOperator op : values()) {
                if (op.value.equals(value)) {
                    return op;
                }
            }
            throw new IllegalArgumentException("Invalid filter operator: " + value);
        }
    }

    // Keyset pagination cursor
    public record KeysetCursor(
            @JsonProperty("created_at_ms") Long createdAtMs,
            @JsonProperty("id") Long id
    ) {
        public KeysetCursor {
            Objects.requireNonNull(createdAtMs, "createdAtMs cannot be null");
            Objects.requireNonNull(id, "id cannot be null");
        }

        public String encode() {
            return createdAtMs + "_" + id;
        }

        public static KeysetCursor decode(String cursor) {
            if (cursor == null || cursor.isEmpty()) {
                return null;
            }
            String[] parts = cursor.split("_");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid cursor format: " + cursor);
            }
            try {
                return new KeysetCursor(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid cursor format: " + cursor, e);
            }
        }
    }

    // Column definition for dynamic columns
    public record ColumnDefinition(
            @JsonProperty("col_id") String colId,
            @JsonProperty("header_name") String headerName,
            @JsonProperty("field") String field,
            @JsonProperty("type") ColumnType type,
            @JsonProperty("editable") Boolean editable,
            @JsonProperty("sortable") Boolean sortable,
            @JsonProperty("filterable") Boolean filterable,
            @JsonProperty("width") Integer width,
            @JsonProperty("flex") Integer flex,
            @JsonProperty("pinned") String pinned,
            @JsonProperty("structure") ColumnStructure structure,
            @JsonProperty("display") Map<String, Object> display
    ) {
        public ColumnDefinition {
            Objects.requireNonNull(colId, "colId cannot be null");
            Objects.requireNonNull(headerName, "headerName cannot be null");
            Objects.requireNonNull(field, "field cannot be null");
            Objects.requireNonNull(type, "type cannot be null");
            if (editable == null) editable = true;
            if (sortable == null) sortable = true;
            if (filterable == null) filterable = true;
            if (structure == null) structure = ColumnStructure.SCALAR;
            display = display == null ? Map.of() : Map.copyOf(display);
        }
    }

    // Error response
    public record ErrorResponse(
            @JsonProperty("error") String error,
            @JsonProperty("message") String message,
            @JsonProperty("details") Map<String, Object> details,
            @JsonProperty("timestamp") Instant timestamp
    ) {
        public ErrorResponse {
            Objects.requireNonNull(error, "error cannot be null");
            Objects.requireNonNull(message, "message cannot be null");
            if (timestamp == null) timestamp = Instant.now();
        }
    }
}
