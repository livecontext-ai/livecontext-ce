package com.apimarketplace.datasource.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for clone datasource requests.
 */
public record CloneRequestDto(
        @JsonProperty("source_id") Long sourceId,
        @JsonProperty("tenant_id") String tenantId
) {}
