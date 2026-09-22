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
 * <p><b>{@code lastRunStatus}</b> badges that time with the outcome of the SAME
 * fire, so a row says not only when the automation last ran but whether it worked.
 * It is resolved from the production run's last epoch, and it is null wherever that
 * epoch cannot be shown to be the fire {@code lastRunAt} names: rows with no
 * production run to read (agents, and pinned workflows whose run has not been
 * provisioned yet), and schedule rows whose own trigger is not the one that fired.</p>
 *
 * <p><b>{@code lastRunAt} answers "when did a fire END UP RUNNING", not "when was one
 * dispatched"</b>, wherever an epoch can be attributed to the row. That is a change users
 * see: a schedule tick refused by a budget gate advances
 * {@code scheduled_executions.last_execution_at} without opening an epoch, and a builder Play
 * stamps {@code WorkflowEntity.lastExecutedAt} without touching the production run - both used
 * to move this field and no longer do. The upside is that the timestamp and the badge beside
 * it always describe one execution; the visible cost is that two rows of the same workflow can
 * now report different "last" times, and Tier-2 sorting follows them.
 *
 * <p><b>Note on {@code lastRunAt} granularity for the 6 new kinds</b>: same-workflow
 * rows of different new kinds share one timestamp - the production run's most recent
 * fire, regardless of which trigger caused it, falling back to
 * {@code WorkflowEntity.lastExecutedAt} when no epoch answers. Per-kind precision is
 * intentionally traded for implementation simplicity. SCHEDULE rows do NOT make that
 * trade: each one reports its own trigger's fire, so a workflow with a daily and a
 * weekly schedule never prints one's fire on the other's row.
 *
 * <p>{@code resourceType} drives the click target on the frontend
 * (workflows → /app/workflow/{id}, applications → /app/applications/{publicationId},
 * agents → /app/agent?openAgent={id} - agents have no page of their own, they open in the
 * right-side panel, and /app/agent/{id} is a 404).
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
        /** Whether the production resource itself is paused, independently of its trigger. */
        Boolean resourcePaused,
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
        String publicationId,
        /**
         * How the most recent fire ENDED, upper-case, for the status badge the
         * frontend draws next to {@code lastRunAt}: {@code COMPLETED},
         * {@code FAILED}, {@code RUNNING}, or a terminal run status
         * ({@code CANCELLED} / {@code TIMEOUT} / ...) when the fire was killed
         * mid-flight. Null means "nothing honest to say" - the row never fired,
         * has no production run to read, or its last epoch ran nothing but its
         * trigger - and the frontend then renders the time with an empty badge
         * slot rather than inventing a verdict.
         *
         * <p>Read from the last epoch of the row's production run, so it
         * describes the same event as {@code lastRunAt}. Absent on AGENT rows:
         * agents have no pinned production run.
         */
        String lastRunStatus,
        /** Exact normalized plan key, for example {@code trigger:daily_report}. */
        String triggerId,
        /** Human-readable label of this trigger in the published plan. */
        String triggerLabel
) {

    /** Backward-compatible constructor for callers that predate exact trigger identity. */
    public ActiveAutomationDto(ResourceType resourceType, UUID resourceId, String name, String avatarUrl,
                               TriggerType triggerType, ScheduleInfo schedule, WebhookInfo webhook,
                               Instant lastRunAt, Boolean isPinned, Boolean resourcePaused,
                               String productionRunIdPublic, String publicationId, String lastRunStatus) {
        this(resourceType, resourceId, name, avatarUrl, triggerType, schedule, webhook, lastRunAt,
                isPinned, resourcePaused, productionRunIdPublic, publicationId, lastRunStatus, null, null);
    }

    /** Backward-compatible constructor for callers that predate resource pause state. */
    public ActiveAutomationDto(ResourceType resourceType, UUID resourceId, String name, String avatarUrl,
                               TriggerType triggerType, ScheduleInfo schedule, WebhookInfo webhook,
                               Instant lastRunAt, Boolean isPinned, String productionRunIdPublic,
                               String publicationId, String lastRunStatus) {
        this(resourceType, resourceId, name, avatarUrl, triggerType, schedule, webhook, lastRunAt,
                isPinned, false, productionRunIdPublic, publicationId, lastRunStatus, null, null);
    }

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
     *
     * @param scheduleId the {@code scheduled_executions} row id. The bell only ever
     *                   navigated to the resource, so it never needed one; the agenda
     *                   ACTS on the schedule (move an occurrence, run it early, pause
     *                   it) and every one of those calls is addressed by this id, not
     *                   by the workflow's.
     * @param armed      whether this schedule will fire again: enabled AND under its
     *                   max-executions cap. The agenda greys out rows where this is
     *                   false, so it deliberately answers "will it run" rather than
     *                   mirroring the raw {@code enabled} column - a schedule that has
     *                   exhausted its cap is enabled and still never fires again.
     *                   Always true on rows served to the bell, which filters disabled
     *                   and exhausted schedules out upstream.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScheduleInfo(
            String cronExpression,
            String timezone,
            Instant nextFireAt,
            int executionCount,
            UUID scheduleId,
            boolean armed,
            /**
             * Why this schedule is not armed, or {@code null} when it is.
             *
             * <p>Carried because "paused" alone cannot decide whether a Resume action is
             * honest, and because withholding the action without saying why just moves the
             * confusion. Only {@link PausedReason#USER} is resumable:
             * {@code CAP_REACHED} makes {@code armSchedule} a no-op it still reports as a
             * success, and {@code PLATFORM} means the cause is upstream in the plan, so
             * re-arming rebuilds the orphan the suspension sweep exists to retire.
             */
            PausedReason pausedReason,
            /**
             * Is the workflow's SPENDING cap currently refusing its fires?
             *
             * <p>Deliberately separate from {@code armed}. The three paused
             * reasons are states of the SCHEDULE and are durable: someone must
             * act before it fires again. A spending block is a state of the
             * WORKFLOW and is temporary, so the schedule is still armed and
             * still has a cron - the fires simply get refused for a while. The
             * agenda draws that difference: occurrences inside the block are
             * shown as not-going-to-happen, and the ones after it are drawn
             * normally, because they will.
             */
            boolean budgetBlocked,
            /**
             * When the block lifts, or {@code null} when it does not lift on its
             * own (a cap whose cadence never resets). Null therefore means
             * "indefinitely", NOT "not blocked" - read it with
             * {@link #budgetBlocked}, never on its own.
             */
            Instant budgetBlockedUntil
    ) {}

    /**
     * Why a schedule is not armed. Deliberately a small UI-facing set rather than the
     * lifecycle manager's internal reason codes, which are an audit vocabulary and change
     * for reasons the calendar should not have to track.
     */
    public enum PausedReason {
        /** The user paused it. The only one a Resume action can undo. */
        USER,
        /** It ran its configured number of times. Resuming changes nothing. */
        CAP_REACHED,
        /** The platform suspended it: its trigger left the pinned plan, or the workflow
         *  was unpinned or deleted. Fixing it means fixing the plan. */
        PLATFORM
    }

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
