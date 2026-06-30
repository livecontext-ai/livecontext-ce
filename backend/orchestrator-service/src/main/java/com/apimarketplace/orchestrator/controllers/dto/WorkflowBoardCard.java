package com.apimarketplace.orchestrator.controllers.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lightweight card DTO for the workflow board.
 * Contains only fields needed for board display + actions.
 */
public record WorkflowBoardCard(
    UUID workflowId,
    String name,
    String description,
    List<Map<String, Object>> nodeIcons,
    Integer pinnedVersion,
    String productionRunId,
    String productionRunStatus,
    /**
     * Number of epochs fired by the production run (count of EPOCH_HEADER rows
     * in {@code workflow_epochs}). Null when there is no production run yet.
     * Frontend surfaces this with a calendar icon next to the run count.
     */
    Long productionRunEpochCount,
    Instant lastExecutedAt,
    Instant updatedAt,
    long runCount,
    String column,
    /**
     * For application rows (applications board): the publication this app resolves to, so the card
     * can open the application surface ({@code /app/applications/{publicationId}}) instead of the
     * workflow builder. Set for acquired APPLICATION-type rows (from the entity) AND for a
     * publisher's OWN published-as-application WORKFLOW-type rows (resolved via publication-service,
     * since their entity carries no source publication). Null for regular workflows.
     */
    UUID sourcePublicationId,
    /**
     * Workflows board: whether the source workflow has an ACTIVE publication. Mirrors the
     * {@code /workflows} list ({@code "ACTIVE".equals(publicationStatus)}) so the board card shows
     * the same "shared" marker. Null/false for unpublished workflows and acquired app rows.
     */
    Boolean isPublished,
    /**
     * Workflows board: full publication moderation state (ACTIVE / PENDING_REVIEW / REJECTED) of
     * the source workflow's own publication, so the card can show "shared" / "in review" /
     * "rejected" exactly like the {@code /workflows} list. Null when not shared.
     */
    String publicationStatus,
    /**
     * Applications board, OWN published-as-application rows only: the publication's showcase
     * interface + run ids, so the card renders the preview via the authenticated per-run path
     * ({@code /interfaces/{id}/render?runId=...}) - valid at ANY publication visibility (the run is
     * the caller's own), exactly like {@code /app/applications}. Null for acquired rows (whose run
     * belongs to the publisher → cross-tenant) and regular workflows; those keep the public showcase
     * render via {@code sourcePublicationId}.
     */
    UUID showcaseInterfaceId,
    String showcaseRunId,
    /**
     * Marketplace visibility (PUBLIC / PRIVATE / UNLISTED) of the card's OWN publication, so the
     * board can show a public / private indicator and filter on it, mirroring
     * {@code /app/applications}. Set for own published workflows/apps (resolved via
     * publication-service); null for acquired rows (whose visibility is the publisher's, not the
     * viewer's) and for unpublished workflows (no visibility to surface).
     */
    String visibility,
    /**
     * Applications board, ACQUIRED rows only: {@code true} when the source publication is absent
     * from the LOCAL catalog, i.e. a cloud-sourced (remote) acquisition on a cloud-linked CE. The
     * card then renders its showcase through the cloud proxy instead of the local public render
     * (which 404s on a cloud-only publication id, leaving the card on the node-icon cover tile).
     * {@code false} for LOCAL acquired apps (rendered via the receipt-gated authenticated showcase)
     * and for own published apps / regular workflows. Mirrors {@code /app/applications}, which marks
     * the same cloud-sourced acquisitions {@code remote=true}.
     */
    Boolean remote
) {}
