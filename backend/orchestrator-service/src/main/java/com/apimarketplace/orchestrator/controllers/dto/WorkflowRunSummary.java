package com.apimarketplace.orchestrator.controllers.dto;

import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Summary DTO for workflow run responses.
 *
 * <p>{@code lastFireAt} is the {@code started_at} of the most recent epoch
 * header in {@code workflow_epochs} for this run (null if no epoch has fired
 * yet). For reusable trigger runs whose {@code startedAt} reflects run birth
 * (often days old), this is the field the run history panel should display
 * as "last execution". Callers without epoch data fall back to {@code startedAt}.
 */
public record WorkflowRunSummary(
    UUID id,
    String runId,
    @JsonIgnore String tenantId,
    RunStatus status,
    String executionMode,
    Instant startedAt,
    Instant endedAt,
    Long durationMs,
    Integer totalNodes,
    Map<String, Object> triggerPayload,
    Map<String, Object> metadata,
    Map<String, Object> plan,
    Integer planVersion,
    Integer currentEpoch,
    Instant lastFireAt
) {}
