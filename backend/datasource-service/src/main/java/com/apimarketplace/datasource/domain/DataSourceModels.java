package com.apimarketplace.datasource.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Domain models for DataSources
public final class DataSourceModels {
    private DataSourceModels() {}

    // Main DataSource
    public record DataSource(
            Long id,
            @JsonProperty("tenant_id") String tenantId,
            String name,
            String description,
            @JsonProperty("source_type") DataSourceType sourceType,
            @JsonProperty("source_config") Map<String, Object> sourceConfig,
            DataSourceStatus status,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt,
            @JsonProperty("created_by") String createdBy,
            @JsonProperty("column_order") List<Map<String, Object>> columnOrder,
            @JsonProperty("mapping_spec") Map<String, ColumnMappingSpec> mappingSpec,
            @JsonProperty("source_workflow_id") UUID sourceWorkflowId,
            @JsonProperty("source_publication_id") UUID sourcePublicationId,
            @JsonProperty("project_id") UUID projectId,
            @JsonProperty("organization_id") String organizationId
    ) {
        public DataSource withId(Long newId) {
            return new DataSource(newId, tenantId, name, description, sourceType, sourceConfig, status, createdAt, updatedAt, createdBy, columnOrder, mappingSpec, sourceWorkflowId, sourcePublicationId, projectId, organizationId);
        }

        public DataSource withColumnOrder(List<Map<String, Object>> newColumnOrder) {
            return new DataSource(id, tenantId, name, description, sourceType, sourceConfig, status, createdAt, updatedAt, createdBy, newColumnOrder, mappingSpec, sourceWorkflowId, sourcePublicationId, projectId, organizationId);
        }

        public DataSource withMappingSpec(Map<String, ColumnMappingSpec> newMappingSpec) {
            return new DataSource(id, tenantId, name, description, sourceType, sourceConfig, status, createdAt, updatedAt, createdBy, columnOrder, newMappingSpec, sourceWorkflowId, sourcePublicationId, projectId, organizationId);
        }

        public DataSource withSourceWorkflowId(UUID newSourceWorkflowId) {
            return new DataSource(id, tenantId, name, description, sourceType, sourceConfig, status, createdAt, updatedAt, createdBy, columnOrder, mappingSpec, newSourceWorkflowId, sourcePublicationId, projectId, organizationId);
        }

        public DataSource withSourcePublicationId(UUID newSourcePublicationId) {
            return new DataSource(id, tenantId, name, description, sourceType, sourceConfig, status, createdAt, updatedAt, createdBy, columnOrder, mappingSpec, sourceWorkflowId, newSourcePublicationId, projectId, organizationId);
        }

        public DataSource withProjectId(UUID newProjectId) {
            return new DataSource(id, tenantId, name, description, sourceType, sourceConfig, status, createdAt, updatedAt, createdBy, columnOrder, mappingSpec, sourceWorkflowId, sourcePublicationId, newProjectId, organizationId);
        }

        public DataSource withOrganizationId(String newOrganizationId) {
            return new DataSource(id, tenantId, name, description, sourceType, sourceConfig, status, createdAt, updatedAt, createdBy, columnOrder, mappingSpec, sourceWorkflowId, sourcePublicationId, projectId, newOrganizationId);
        }
    }

    // DataSource item
    public record DataSourceItem(
            Long id,
            @JsonProperty("data_source_id") Long dataSourceId,
            @JsonProperty("tenant_id") String tenantId,
            Map<String, Object> data,
            Integer priority,
            @JsonProperty("created_at") Instant createdAt
    ) {}

    // Source types
    public enum DataSourceType {
        INLINE,     // Data provided directly
        DATABASE,   // Query on a table
        API,        // Call to an external API
        FILE        // Reading from a file
    }

    // DataSource statuses
    public enum DataSourceStatus {
        ACTIVE, INACTIVE, ARCHIVED
    }

    // Type-specific configuration
    public sealed interface SourceConfig 
        permits InlineConfig, DatabaseConfig, ApiConfig, FileConfig {}

    @JsonTypeName("inline")
    public record InlineConfig(
            @JsonProperty("key_field") String keyField,
            Map<String, Object> schema
    ) implements SourceConfig {}

    @JsonTypeName("database")
    public record DatabaseConfig(
            String connection,
            String table,
            List<String> projection,
            String predicate
    ) implements SourceConfig {}

    @JsonTypeName("api")
    public record ApiConfig(
            String baseUrl,
            String endpoint,
            Map<String, String> headers,
            Map<String, Object> queryParams,
            @JsonProperty("auth_type") String authType,
            @JsonProperty("auth_config") Map<String, String> authConfig
    ) implements SourceConfig {}

    @JsonTypeName("file")
    public record FileConfig(
            @JsonProperty("file_path") String filePath,
            @JsonProperty("file_type") String fileType,
            @JsonProperty("parser_config") Map<String, Object> parserConfig
    ) implements SourceConfig {}
    // Mapping specification for each column exposed to the DataTable
    public record ColumnMappingSpec(
            @JsonProperty("path") String path,
            @JsonProperty("type") ColumnType type,
            @JsonProperty("structure") ColumnStructure structure,
            @JsonProperty("children") Map<String, ColumnMappingSpec> children,
            @JsonProperty("display") Map<String, Object> display
    ) {
        public ColumnMappingSpec {
            path = path == null ? "" : path;
            type = type == null ? ColumnType.TEXT : type;
            structure = structure == null ? ColumnStructure.SCALAR : structure;
            children = children == null ? Map.of() : Map.copyOf(children);
            display = display == null ? Map.of() : Map.copyOf(display);
        }

        public static ColumnMappingSpec fromLegacyPath(String path) {
            return new ColumnMappingSpec(path, ColumnType.TEXT, ColumnStructure.SCALAR, Map.of(), Map.of());
        }
    }
}
