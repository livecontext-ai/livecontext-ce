package com.apimarketplace.datasource.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/**
 * DTO representing a DataSource item for inter-service communication.
 */
public record DataSourceItemDto(
        Long id,
        @JsonProperty("data_source_id") Long dataSourceId,
        @JsonProperty("tenant_id") String tenantId,
        Map<String, Object> data,
        Integer priority,
        @JsonProperty("created_at") Instant createdAt
) {}
