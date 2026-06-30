package com.apimarketplace.orchestrator.controllers.dto;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Summary DTO for workflow list responses.
 */
public record WorkflowSummary(
    UUID id,
    String name,
    String description,
    String tenantId,
    WorkflowEntity.WorkflowStatus status,
    Instant createdAt,
    Instant updatedAt,
    Instant lastExecutedAt,
    long runCount,
    Map<String, Object> metadata,
    Map<String, Object> plan,
    Map<String, Object> schedule,
    Map<String, String> webhookTokens,
    List<Map<String, Object>> nodeIcons,
    UUID sourcePublicationId,
    Instant acquiredAt,
    boolean isPublished,
    /**
     * Moderation state of this workflow's publication, when one exists and is
     * still shared: {@code "ACTIVE"} | {@code "PENDING_REVIEW"} | {@code "REJECTED"}.
     * {@code null} when the workflow is not shared (or its publication is
     * INACTIVE). The frontend list renders a distinct badge per state - e.g.
     * an orange "shared · in review" chip for {@code PENDING_REVIEW}. Note
     * {@code isPublished} stays the ACTIVE-only boolean for backward compat.
     */
    String publicationStatus,
    UUID projectId,
    WorkflowEntity.WorkflowType workflowType,
    Integer pinnedVersion,
    boolean hasActiveRun,
    String boardColumn
) {}
