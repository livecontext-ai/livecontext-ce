package com.apimarketplace.orchestrator.controllers.dto;

import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto.ResourceType;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto.TriggerType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the agenda page draws for one time window.
 *
 * <p>The window holds two different kinds of dated thing plus a trigger catalogue:
 * lets the page be honest:
 * <ul>
 *   <li>{@link Occurrence} with {@code kind = PLANNED} - a fire that WILL happen,
 *       projected from a schedule. It can still be moved or cancelled.</li>
 *   <li>{@link Occurrence} with {@code kind = PAST} - a fire that DID happen, read from
 *       the epoch headers. It is history: nothing about it can be changed.</li>
 *   <li>{@link Marker} - one catalogue entry per production trigger. Schedule markers
 *       coexist with their projected occurrences; undated triggers such as manual and
 *       webhook remain searchable without being invented onto a day.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgendaDto(
        Instant from,
        Instant to,
        List<Occurrence> occurrences,
        List<Marker> markers,
        /**
         * Schedule ids whose expansion hit the per-schedule cap inside this window, so
         * the calendar is showing a PREFIX of their occurrences. A once-a-minute cron
         * over a month is 43 200 fires; drawing them all is useless, but silently
         * drawing the first 200 would show the schedule stopping mid-month. The page
         * must tell the user which schedules are abbreviated.
         */
        List<UUID> truncatedScheduleIds,
        /**
         * True when the past-fire lookup hit its row cap, so older fires inside the
         * window are missing. Same contract as {@code truncatedScheduleIds}: say it
         * rather than let the user read an incomplete day as a quiet one.
         */
        boolean pastTruncated,
        /**
         * The earliest instant the history in this window is COMPLETE from, or null when
         * nothing was truncated.
         *
         * <p>Without it the honesty contract has a hole that only shows on a browsed PAST
         * month. A capped scan keeps the NEWEST rows, which is the right end to drop when
         * the window ends at "now" - the days being looked at are the recent ones. Page
         * back to September in October and the window ends in the past too, so the rows
         * kept are the END of September and the first three weeks are drawn EMPTY. The
         * banner said "some older runs are not shown" while the grid said "nothing ran on
         * the 3rd", and a user reads the grid.
         *
         * <p>It is the LATEST of the truncated sources' oldest rows, because a date is
         * only safe to call complete once every source covers it.
         */
        Instant pastCoveredFrom,
        /**
         * True when the AGENT half of the history could not be read at all.
         *
         * <p>Separate from {@code pastTruncated} because the two are different
         * sentences. Truncation means "older runs are missing"; this means "every agent
         * run in this window is missing, including today's, and I do not know how many".
         * Collapsing it into truncation made the page say "some older runs are not
         * shown" about a source that was entirely absent, which sends a user looking
         * back in time for rows that are missing from the day in front of them.
         *
         * <p>It carries no boundary on purpose: nothing was read, so no instant can be
         * called covered.
         */
        boolean agentHistoryUnavailable
) {
    /**
     * The empty / nothing-to-report shape, for a caller with no history to describe.
     * Not a compatibility shim: this record is orchestrator-internal and
     * {@code AgendaService.empty} is its only user.
     */
    public AgendaDto(Instant from, Instant to, List<Occurrence> occurrences,
                     List<Marker> markers, List<UUID> truncatedScheduleIds,
                     boolean pastTruncated) {
        this(from, to, occurrences, markers, truncatedScheduleIds, pastTruncated, null, false);
    }


    /** Whether an occurrence is a projection of the future or a record of the past. */
    public enum OccurrenceKind { PLANNED, PAST }

    /**
     * How an AGENT run was launched.
     *
     * <p>A deliberately SEPARATE vocabulary from {@link TriggerType}, which is the eight
     * trigger NODE kinds of a workflow plan and is pinned to a node-icon map in four
     * artifacts (see {@code WorkflowIconExtractorParityTest}). An agent has no trigger
     * nodes: it is launched by a conversation turn, a schedule, a webhook, a workflow
     * node, another agent, a task or the embedded widget. Four of those names coincide
     * with a trigger kind and mean the same thing, which is why the page can filter both
     * with one control - but forcing the other three into that enum would add kinds no
     * workflow can ever declare and no icon exists for.
     *
     * <p>Mapped from {@code agent_executions.source} by
     * {@code AgendaService#launchSourceOf}. A value that map does not recognise resolves
     * to null: an unknown launch reported as "chat" is worse than one reported as
     * nothing, because the page draws it as a fact.
     */
    public enum LaunchSource {
        /** A conversation turn: someone (or something) talked to the agent. */
        CHAT,
        /** An agent schedule fired it. */
        SCHEDULE,
        /** An inbound call on the agent's own webhook. */
        WEBHOOK,
        /** An {@code agent:} node inside a workflow run. */
        WORKFLOW,
        /** Another agent spawned it as a sub-agent. */
        SUB_AGENT,
        /** A delegated task, or the review of one. */
        TASK,
        /** The embedded chat widget on a published surface. */
        WIDGET
    }

    /**
     * A dated entry on the calendar.
     *
     * @param id           stable within a window and across refetches, so the page can
     *                     key rows and track a drag without the identity changing under
     *                     it. {@code <resourceId>:<scheduleId>@<epochMillis>} for a
     *                     projection, {@code <runId>#<triggerId>#<epoch>} for a past fire.
     *                     Both carry a component the obvious form omits, and both were
     *                     added to fix a real collision: one standalone schedule can be
     *                     declared by two workflows and is emitted under each, and one run
     *                     can fire from several triggers inside one epoch. Without them
     *                     React keyed two distinct rows identically and dropped one.
     * @param startAt      when it fires / fired
     * @param endAt        when the past fire's epoch closed; null for a projection and
     *                     for an epoch still open
     * @param scheduleId   the row to address for a move, a run-now or a pause. Null on
     *                     a past fire whose schedule no longer exists.
     * @param triggerId    the plan-level trigger label ({@code trigger:daily}). Present
     *                     on past fires too, so a resource with several triggers can be
     *                     told apart on the same day.
     * @param armed        will THIS occurrence actually fire? False only on a
     *                     PLANNED one whose workflow is over its spending cap at that
     *                     moment: the schedule itself is still armed and still has a
     *                     cron, so it keeps producing occurrences, but the fires inside
     *                     the block are refused. Occurrences after the allowance resets
     *                     are armed again, which is why this is per-occurrence and not
     *                     per-schedule. A paused or exhausted SCHEDULE is a different
     *                     thing: it produces no occurrence at all and reaches the page
     *                     as a {@link Marker}. Always true on a PAST fire, which has
     *                     already happened and cannot be predicted.
     * @param isNextFire   true for the ONE occurrence the schedule is actually pointing at
     *                     ({@code next_execution_at}). Only this one can be moved on its
     *                     own: the row holds a single pending fire, so writing a later
     *                     occurrence's time into it would skip every run in between. The
     *                     page uses this to offer "this occurrence only" where it is
     *                     truthful and explain itself where it is not.
     * @param overridden   true when this occurrence sits somewhere the cron would not
     *                     have put it, i.e. the user moved this one fire. Lets the page
     *                     badge it and offer "reset to schedule".
     * @param moveAllSupported whether the cron has a single, unambiguous time of day to
     *                     rewrite. False means only "this occurrence" can be offered -
     *                     see {@code CronShifter}.
     * @param status       PLANNED, or the past fire's outcome: RUNNING while its epoch
     *                     is open, then COMPLETED / FAILED, or FIRED when the epoch
     *                     closed without executing anything beyond the trigger. An AGENT
     *                     run adds CANCELLED, which its own execution record can report
     *                     and a workflow epoch cannot.
     * @param runIdPublic  click target: the production run this occurrence belongs to.
     * @param publicationId APPLICATION rows only - the frontend application route is
     *                     keyed by publication id, not by workflow id.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Occurrence(
            String id,
            OccurrenceKind kind,
            Instant startAt,
            Instant endAt,
            ResourceType resourceType,
            UUID resourceId,
            String name,
            String avatarUrl,
            UUID scheduleId,
            String triggerId,
            TriggerType triggerType,
            String cronExpression,
            String timezone,
            boolean armed,
            boolean isNextFire,
            boolean overridden,
            boolean moveAllSupported,
            String status,
            String runIdPublic,
            boolean resourcePaused,
            /**
             * Whether a spending cap is refusing this resource fires RIGHT NOW.
             *
             * <p>Not the same question as {@link #armed}, and the difference is what the
             * menu needs. {@code armed} is about THIS occurrence: a monthly cap that lifts
             * on the 1st leaves the occurrence dated the 5th armed, because that fire will
             * happen. "Run early" does not run that fire, it runs the schedule NOW, and now
             * the cap still holds - so a menu keyed on {@code armed} offers an action whose
             * only outcome is a failure toast.
             *
             * <p>Resource-level, like {@code resourcePaused} above, and read the same way.
             */
            boolean budgetBlocked,
            /**
             * Which fire of the run this was. Present on a PAST occurrence, null on a
             * projection - a run that has not happened has no epoch.
             *
             * <p>Carried so a click can open the run ON this fire. A run is a sequence of
             * fires and its surfaces default to the cumulative view of all of them, which
             * is right when you open a run and wrong when you clicked one dot on a
             * calendar: the user pointed at Tuesday 09:00 and got every Tuesday at once.
             * The id already embeds it, but parsing an id back apart is not an API.
             */
            Integer epoch,
            String publicationId,
            /**
             * How an AGENT run was launched. Null on every workflow and application
             * entry, whose launch is described by {@code triggerType} instead - the two
             * are never both set, so a reader takes whichever is present.
             */
            LaunchSource launchSource,
            /**
             * The conversation an agent run happened in, when it had one. It is the
             * click target: a run is READ in its conversation, whereas the agent page
             * only says the agent exists.
             *
             * <p>Absent on every non-agent entry, and on the agent runs that genuinely
             * have none: a CLI-provider agent node inside a workflow records no
             * conversation (verified against a real install, where every such row is
             * null), so those fall back to the agent panel. An ordinary agent node DOES
             * open or reuse one, so "it ran in a workflow" is not the same as "it has no
             * conversation".
             */
            String conversationId
    ) {
        /**
         * Everything that is not an agent run: every component except the two an agent
         * run alone carries. Keeps the long call sites of the workflow and application
         * paths (and their tests) readable, and makes those two impossible to fill in by
         * accident from a path that has nothing to put in them.
         */
        public Occurrence(String id, OccurrenceKind kind, Instant startAt, Instant endAt,
                          ResourceType resourceType, UUID resourceId, String name, String avatarUrl,
                          UUID scheduleId, String triggerId, TriggerType triggerType,
                          String cronExpression, String timezone, boolean armed, boolean isNextFire,
                          boolean overridden, boolean moveAllSupported, String status,
                          String runIdPublic, boolean resourcePaused, boolean budgetBlocked,
                          Integer epoch, String publicationId) {
            this(id, kind, startAt, endAt, resourceType, resourceId, name, avatarUrl, scheduleId,
                    triggerId, triggerType, cronExpression, timezone, armed, isNextFire, overridden,
                    moveAllSupported, status, runIdPublic, resourcePaused, budgetBlocked, epoch,
                    publicationId, null, null);
        }
    }

    /**
     * One searchable production trigger. Scheduled triggers carry their schedule metadata;
     * webhook, chat, form, table and manual triggers deliberately carry no date.
     *
     * <p>These are the trigger catalogue behind search and kind filters. They deliberately
     * carry no {@code startAt}: dated use belongs to {@link Occurrence}, while placing a
     * webhook on a day would state a falsehood about when it runs.
     *
     * @param nextFireAt for a PAUSED schedule marker only: the fire time it would resume
     *                   at. Null for every trigger kind that has no schedule.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Marker(
            ResourceType resourceType,
            UUID resourceId,
            String name,
            String avatarUrl,
            TriggerType triggerType,
            /** Exact normalized plan key, for example {@code trigger:daily_report}. */
            String triggerId,
            /** Human-readable label from the published plan. */
            String triggerLabel,
            UUID scheduleId,
            String cronExpression,
            String timezone,
            Instant nextFireAt,
            /**
             * When this trigger last fired, straight from {@code ActiveAutomationDto.lastRunAt}.
             *
             * <p>For a SCHEDULE marker that is the schedule's own fire. For the other kinds it is
             * the production run's most recent fire whatever trigger caused it, falling back to
             * the workflow's {@code lastExecutedAt} - the same per-workflow granularity those
             * rows have always carried.
             */
            Instant lastRunAt,
            boolean armed,
            boolean resourcePaused,
            /**
             * Why this schedule is paused, or null when it is armed or has no schedule.
             *
             * <p>Only {@code USER} carries a Resume action. Offering it on the other two
             * produced a success toast for a call that wrote nothing, and withholding it
             * without a reason would leave a row sitting there unexplained - which is the
             * question the rail exists to answer.
             */
            ActiveAutomationDto.PausedReason pausedReason,
            String runIdPublic,
            String publicationId
    ) {
        /** Backward-compatible constructor for callers that predate exact trigger identity. */
        public Marker(ResourceType resourceType, UUID resourceId, String name, String avatarUrl,
                      TriggerType triggerType, UUID scheduleId, String cronExpression, String timezone,
                      Instant nextFireAt, Instant lastRunAt, boolean armed, boolean resourcePaused,
                      ActiveAutomationDto.PausedReason pausedReason, String runIdPublic,
                      String publicationId) {
            this(resourceType, resourceId, name, avatarUrl, triggerType, null, null, scheduleId,
                    cronExpression, timezone, nextFireAt, lastRunAt, armed, resourcePaused,
                    pausedReason, runIdPublic, publicationId);
        }
    }
}
