package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Lightweight projection for workflow run listings.
 * Excludes heavy JSONB columns (triggerPayload, plan) for better performance.
 * Includes metadata (small JSONB) for lastCycleResult display on WAITING_TRIGGER runs.
 */
public interface WorkflowRunSummaryProjection {
    UUID getId();
    String getRunIdPublic();
    String getTenantId();
    RunStatus getStatus();
    ExecutionMode getExecutionMode();
    Instant getStartedAt();
    Instant getEndedAt();
    Long getDurationMs();
    Integer getTotalNodes();
    Integer getPlanVersion();
    Map<String, Object> getMetadata();
}
