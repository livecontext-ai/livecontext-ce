package com.apimarketplace.datasource.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DTO representing a DataSource, mirroring the domain model for inter-service communication.
 */
public record DataSourceDto(
        Long id,
        @JsonProperty("tenant_id") String tenantId,
        String name,
        String description,
        @JsonProperty("source_type") DataSourceTypeDto sourceType,
        @JsonProperty("source_config") Map<String, Object> sourceConfig,
        DataSourceStatusDto status,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("created_by") String createdBy,
        @JsonProperty("column_order") List<Map<String, Object>> columnOrder,
        @JsonProperty("mapping_spec") Map<String, ColumnMappingSpecDto> mappingSpec,
        @JsonProperty("source_workflow_id") UUID sourceWorkflowId,
        @JsonProperty("source_publication_id") UUID sourcePublicationId,
        @JsonProperty("project_id") UUID projectId,
        @JsonProperty("organization_id") String organizationId
) {}
