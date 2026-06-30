package com.apimarketplace.orchestrator.controllers.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * One row in the home-page "Triggers" tab strip.
 *
 * <p>Unifies workflows, applications, and agents per trigger kind. Each row is
 * tagged with one of 8 {@link TriggerType} values matching the workflow node
 * registry. The frontend renders one pill per (resource, kind).
 *
 * <p><b>Emission rules</b>:
 * <ul>
 *   <li><b>schedule</b>: armed - emitted iff an enabled schedule row exists.</li>
 *   <li><b>webhook</b>: armed - emitted iff at least one webhook token exists.</li>
 *   <li><b>manual / chat / form / datasource / workflow / error</b>: declared
 *       - emitted iff the pinned workflow plan declares the trigger node
 *       (read from {@code WorkflowEntity.nodeIcons}).</li>
 *   <li><b>agent</b> resources stay on schedule + webhook only (agents have a
 *       separate trigger model).</li>
 * </ul>
 *
 * <p><b>Sort (2-tier)</b>:
 * <ol>
 *   <li>Tier 1: SCHEDULE rows by {@code nextFireAt ASC NULLS LAST}.</li>
 *   <li>Tier 2: every other row (including WEBHOOK and the 6 new kinds) by
 *       {@code lastRunAt DESC NULLS LAST}.</li>
 *   <li>Tiebreak: by {@code name} case-insensitive.</li>
 * </ol>
 *
 * <p><b>Invariant on {@code schedule}/{@code webhook} fields</b>:
 * <ul>
 *   <li>SCHEDULE rows: {@code schedule} non-null, {@code webhook} null.</li>
 *   <li>WEBHOOK rows: {@code webhook} non-null, {@code schedule} null.</li>
 *   <li>The 6 new kinds: BOTH null - the frontend renders the kind's NodeIcon
 *       and a relative-time label from {@code lastRunAt} only.</li>
 * </ul>
 *
 * <p><b>Note on {@code lastRunAt} granularity for the 6 new kinds</b>: same-workflow
 * rows of different new kinds share {@code WorkflowEntity.lastExecutedAt} (the
 * workflow's most recent fire time, regardless of which trigger fired). Per-kind
 * precision is intentionally traded for implementation simplicity - every fire
 * stamps this field via {@code ReusableTriggerService.executeTriggerInternal}.
 *
 * <p>{@code resourceType} drives the click target on the frontend
 * (workflows → /app/workflow/{id}, applications → /app/applications/{publicationId},
 * agents → /app/agent/{id}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActiveAutomationDto(
        ResourceType resourceType,
        UUID resourceId,
        String name,
        String avatarUrl,
        TriggerType triggerType,
        ScheduleInfo schedule,
        WebhookInfo webhook,
        Instant lastRunAt,
        Boolean isPinned,
        /**
         * The pinned workflow's / application's current production run public id
         * ({@code workflow_runs.run_id_public}) - only set for WORKFLOW and
         * APPLICATION rows that resolve to a trusted production run via
         * {@code WorkflowRunRepository.findProductionRunsBatch}.
         *
         * <p>The frontend uses this to route the bell row to run mode
         * ({@code /app/workflow/{id}/run/{productionRunIdPublic}}), matching
         * the click target of the workflow board card. When absent, the
         * frontend falls back to edit mode.
         */
        String productionRunIdPublic,
        /**
         * For APPLICATION rows only: the {@code source_publication_id} of the
         * application workflow. The frontend application page is keyed by
         * publication id ({@code /app/applications/[publicationId]}), NOT by
         * workflow id - so bell rows for APPLICATION must route on this field
         * to avoid a 404. WORKFLOW rows leave this null (they route on
         * {@code resourceId}). Legacy APPLICATION rows with no source_publication_id
         * also leave this null; the frontend falls back to {@code /app/workflow/{id}}.
         *
         * <p>Added v5 for the F4 PUB-HIJACK observability bundle.
         */
        String publicationId
) {

    public enum ResourceType { WORKFLOW, APPLICATION, AGENT }

    /**
     * Trigger kind, one-to-one with the 8 trigger node types in the workflow
     * registry. Mirrored on the frontend as {@code TriggerKind} (lowercased
     * string union) in {@code dashboard.service.ts} - kept in lockstep with
     * {@code WorkflowIconExtractor.TRIGGER_TYPE_TO_NODE_ID} keys (Java side)
     * and {@code KIND_TO_NODE_ICON_KEY} (TS side).
     */
    public enum TriggerType { SCHEDULE, WEBHOOK, MANUAL, CHAT, FORM, DATASOURCE, WORKFLOW, ERROR }

    /**
     * Schedule details. {@code nextFireAt} is the precomputed
     * {@code scheduled_executions.next_execution_at} (indexed in trigger schema)
     * - the frontend uses it directly to render the countdown.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScheduleInfo(
            String cronExpression,
            String timezone,
            Instant nextFireAt,
            int executionCount
    ) {}

    /**
     * Webhook details. Only present on items where at least one active token
     * exists; inactive rows are dropped upstream by the service. {@code httpMethod}
     * is set for agents (one method per agent webhook) and null for workflows
     * (which may carry multiple per-trigger tokens with mixed methods).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WebhookInfo(
            String httpMethod
    ) {}
}
