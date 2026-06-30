package com.apimarketplace.catalog.dto;

import java.util.List;

/**
 * Request DTO for batch tool fetching
 * Used to fetch multiple tools in a single request for workflow import optimization
 */
public record ToolBatchRequest(
    List<String> toolSlugs
) {}
