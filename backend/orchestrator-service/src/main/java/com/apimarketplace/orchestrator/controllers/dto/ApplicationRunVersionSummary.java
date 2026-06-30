package com.apimarketplace.orchestrator.controllers.dto;

import java.time.Instant;

/**
 * Batched per-workflow metadata for one card on the Applications page: the application-dedicated run
 * id ({@code applicationRunId}, drives the live preview), its last-executed timestamp
 * ({@code lastExecutedAt} = lastFireAt of the most recent epoch, else the run's startedAt - drives the
 * execution sort), and the workflow's pinned version ({@code pinnedVersion}, drives the Live/Active
 * badge).
 *
 * <p>Returned in the {@code Map<workflowId, ApplicationRunVersionSummary>} of
 * {@code POST /api/workflows/applications/run-version-batch}, which replaces the per-card
 * {@code /runs/application} + {@code /versions} N+1 (two HTTP calls PER application, ~200 cards). Any
 * field may be null: {@code applicationRunId}/{@code lastExecutedAt} are null when the workflow has no
 * application run yet; {@code pinnedVersion} is null when the workflow is unpinned (Inactive). A
 * workflow id absent from the response map reads as "load failed / no data" on the client.
 */
public record ApplicationRunVersionSummary(
        String applicationRunId,
        Instant lastExecutedAt,
        Integer pinnedVersion
) {}
