package com.apimarketplace.datasource.client.dto;

import java.util.List;
import java.util.Map;

/**
 * DTO for paginated datasource data results.
 */
public record DataSourceDataDto(
        List<Map<String, Object>> items,
        int totalCount,
        boolean hasMore
) {}
