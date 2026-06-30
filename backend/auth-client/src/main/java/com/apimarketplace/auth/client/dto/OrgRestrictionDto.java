package com.apimarketplace.auth.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for org resource restrictions transferred via HTTP.
 * Maps snake_case SQL column names from queryForList() results.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrgRestrictionDto(
        Long id,
        @JsonProperty("organization_id") String organizationId,
        @JsonProperty("member_user_id") String memberUserId,
        @JsonProperty("resource_type") String resourceType,
        @JsonProperty("resource_id") String resourceId,
        @JsonProperty("restricted_by") String restrictedBy,
        @JsonProperty("permission") String permission,
        @JsonProperty("created_at") String createdAt
) {}
