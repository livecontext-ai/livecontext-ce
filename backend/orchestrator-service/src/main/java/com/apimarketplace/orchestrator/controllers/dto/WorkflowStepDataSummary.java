package com.apimarketplace.orchestrator.controllers.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Summary DTO for workflow step data responses.
 * Includes all node-type-specific columns from the database.
 */
public record WorkflowStepDataSummary(
    Long id,
    UUID workflowRunId,
    String runId,
    String stepAlias,
    String toolId,
    String status,
    Instant startTime,
    Instant endTime,
    Integer httpStatus,
    UUID outputStorageId,
    Integer iteration,
    Integer itemIndex,
    Integer epoch,
    Integer spawn,
    String errorMessage,
    Map<String, Object> inputData,
    Map<String, Object> metadata,
    @JsonIgnore String tenantId,
    // Node type identification
    String nodeType,
    String normalizedKey,
    // Decision node fields
    String conditionExpression,
    Boolean conditionResult,
    String selectedBranch,
    // Loop node fields
    String loopId,
    Integer loopIteration,
    String loopExitReason,
    // Merge node fields
    String mergeStrategy,
    List<String> mergeReceivedBranches,
    List<String> mergeSkippedBranches,
    // Skip tracking fields
    String skipReason,
    String skipSourceNode,
    // Item tracking fields
    String triggerId,
    String itemId,
    Integer itemNumber
) {}
