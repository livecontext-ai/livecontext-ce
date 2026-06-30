package com.apimarketplace.orchestrator.controllers.dto;

import java.util.List;
import java.util.Map;

/**
 * Response DTO for the workflow board endpoint.
 * Workflows are pre-classified into columns by the backend.
 *
 * `totalCount` is the total number of workflows for the tenant (before pagination).
 * `columns` only contains the cards for the requested page.
 */
public record WorkflowBoardResponse(
    Map<String, List<WorkflowBoardCard>> columns,
    int totalCount,
    int page,
    int size
) {}
