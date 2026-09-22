package com.apimarketplace.orchestrator.services.agenda;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentRunFireDto;
import com.apimarketplace.agent.client.dto.AgentRunWindowDto;
import com.apimarketplace.common.schedule.CronOccurrences;
import com.apimarketplace.common.schedule.CronShifter;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto.ResourceType;
import com.apimarketplace.orchestrator.controllers.dto.AgendaDto;
import com.apimarketplace.orchestrator.controllers.dto.AgendaDto.Marker;
import com.apimarketplace.orchestrator.controllers.dto.AgendaDto.Occurrence;
import com.apimarketplace.orchestrator.controllers.dto.AgendaDto.OccurrenceKind;
import com.apimarketplace.orchestrator.domain.WorkflowEntity.WorkflowType;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository;
import com.apimarketplace.orchestrator.services.ActiveAutomationsService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the agenda page's view of a time window: what is going to run, what already
 * ran, and the complete production-trigger catalogue used to filter and search it.
 *
 * <p><b>Where the future comes from, and why it is not just the cron.</b> A schedule row
 * carries both a cron expression AND a {@code next_execution_at} column, and they can
 * legitimately disagree: moving a single occurrence writes the new time into that column
 * and leaves the cron alone (that is exactly what "this occurrence only" means). The
 * daemon fires on the column, so the FIRST projected occurrence is read from it and the
 * cron is only expanded for the ones AFTER it. Expanding the cron from the window start
 * instead would redraw the moved occurrence back in its old slot - the calendar would
 * contradict the move the user just made, and then contradict the daemon when the fire
 * happened somewhere else.
 *
 * <p><b>Where the past comes from.</b> Not from the schedule row, which remembers only
 * its last fire and a running total. One {@code EPOCH_HEADER} is written per trigger
 * fire, so the epoch table is the only thing that can answer "what ran on the 12th". It
 * is read once for the whole window, batched over the workspace's runs.
 *
 * <p><b>The past has TWO sources, because the two kinds of resource record their work in
 * different places.</b> A workflow or an application runs as a run with epochs. An agent
 * does not: a schedule, a webhook, a workflow node, a chat turn or another agent each
 * produce an {@code agent_executions} row and no epoch. Agents were therefore absent from
 * the calendar's history entirely, and the page could not say how any agent run had been
 * launched. They are now read in ONE batched call to agent-service (the {@code agent}
 * schema belongs to that service), merged into the same occurrence list, and each one
 * carries its {@link AgendaDto.LaunchSource}.
 *
 * <p>That second source is an ENRICHMENT and fails open: a slow or unavailable
 * agent-service costs the calendar its agent rows and nothing else. It does NOT cost the
 * page the ability to say so: the read answers {@link AgentRunWindowDto#available()},
 * which travels to the client as {@code agentHistoryUnavailable}, so the banner names a
 * missing source instead of drawing a quiet month. The budget stays short (2s/3s)
 * because the rows are worth waiting for only briefly.
 */
@Service
public class AgendaService {

    private static final Logger logger = LoggerFactory.getLogger(AgendaService.class);

    private final ActiveAutomationsService activeAutomationsService;
    private final WorkflowEpochRepository epochRepository;
    private final AgentClient agentClient;
    private final ObjectMapper objectMapper;

    /**
     * Occurrences drawn per schedule per window. A once-a-minute cron over a month is
     * 43 200 fires: past a couple of hundred the calendar is unreadable and the payload
     * is waste, so the expansion stops here and the schedule is reported as truncated.
     */
    @Value("${agenda.max-occurrences-per-schedule:200}")
    private int maxOccurrencesPerSchedule;

    /** Past fires read per window, across all runs. Same honesty contract as above. */
    @Value("${agenda.max-past-fires:2000}")
    private int maxPastFires;

    /**
     * Agent runs read per window. Lower than {@link #maxPastFires} on purpose: an agent
     * run is one CHAT TURN, so a talkative workspace produces them an order of magnitude
     * faster than trigger fires, and the two caps bound two independent scans. Hitting it
     * reports the window as truncated exactly like the epoch cap does.
     */
    @Value("${agenda.max-past-agent-runs:1000}")
    private int maxPastAgentRuns;

    /**
     * Longest window the page may ask for. Bounds both the cron expansion and the epoch
     * scan; a request for a decade is clamped rather than refused, so the page still
     * renders something correct for the start of what it asked for.
     */
    @Value("${agenda.max-window-days:120}")
    private int maxWindowDays;

    public AgendaService(ActiveAutomationsService activeAutomationsService,
                         WorkflowEpochRepository epochRepository,
                         AgentClient agentClient,
                         ObjectMapper objectMapper) {
        this.activeAutomationsService = activeAutomationsService;
        this.epochRepository = epochRepository;
        this.agentClient = agentClient;
        this.objectMapper = objectMapper;
    }

    /**
     * @param includePast when false, only projections are returned. The page turns this
     *                    off for a window entirely in the future, where the epoch scan
     *                    could only ever return nothing.
     */
    public AgendaDto getAgenda(String tenantId, String orgId, String orgRole,
                               Instant from, Instant to, boolean includePast) {
        Instant windowStart = from;
        Instant windowEnd = clampWindowEnd(from, to);
        Instant now = Instant.now();

        // Disabled schedules are pulled in deliberately: they become greyed markers, so a
        // user whose job stopped running finds it paused instead of finding a hole.
        List<ActiveAutomationDto> automations =
                activeAutomationsService.getAgendaAutomations(tenantId, orgId, orgRole);

        List<Occurrence> occurrences = new ArrayList<>();
        List<Marker> markers = new ArrayList<>();
        List<UUID> truncatedScheduleIds = new ArrayList<>();

        for (ActiveAutomationDto automation : automations) {
            ActiveAutomationDto.ScheduleInfo schedule = automation.schedule();
            // The markers are the trigger catalogue used by search and kind filters. A
            // schedule therefore stays in this list even while its dated occurrences are
            // projected below; non-schedule triggers (including manual) have no honest date.
            markers.add(toMarker(automation, schedule));
            if (schedule == null) {
                // Webhook, chat, form, table, workflow or manual: armed, but with no date
                // it can be drawn at. It stays searchable without being placed on a day.
                continue;
            }
            if (!schedule.armed()) {
                // Paused or capped out: it will not fire, so projecting occurrences for it
                // would draw runs that are never going to happen.
                continue;
            }
            boolean truncated = appendProjectedOccurrences(
                    automation, schedule, windowStart, windowEnd, now, occurrences);
            if (truncated) {
                truncatedScheduleIds.add(schedule.scheduleId());
            }
        }

        Instant pastCoveredFrom = null;
        boolean pastTruncated = false;
        boolean agentHistoryUnavailable = false;
        if (includePast && windowStart.isBefore(now)) {
            Instant pastEnd = min(windowEnd, now);
            // Two independent scans over the same past window, each with its own cap,
            // because the two sources live in different schemas and grow at different
            // rates. Either of them hitting its cap truncates the window, and the page
            // has one flag to say so - which is the right granularity: the user needs to
            // know the history is incomplete, not which table ran out first.
            //
            // A truncated scan reports the OLDEST row it did return, and the page is told
            // the LATEST of those: a day is only complete once every source reaches it.
            // Without that boundary a capped scan on a browsed PAST month draws its first
            // weeks empty, because "keep the newest" only points at the days being looked
            // at when the window ends now.
            PastScan epochScan = appendPastFires(orgId, windowStart, pastEnd, occurrences);
            AgentRunWindowDto agentWindow =
                    appendAgentRuns(tenantId, orgId, orgRole, windowStart, pastEnd, occurrences);
            // ONLY when both halves were read. `laterOrNull` reads a null as "this source
            // was complete", and an unread agent window has a null boundary meaning the
            // opposite: nothing is known about it anywhere in the window. Merging the two
            // would publish the EPOCH boundary as the whole page's, and the banner would
            // say "history is complete from 18:32" while every agent run in the month was
            // missing - the exact false claim this field exists to prevent, re-created by
            // the one input the merge was not given.
            agentHistoryUnavailable = !agentWindow.available();
            // A boundary may be published only when EVERY source can name one. Three
            // shapes forbid it, and they are the same mistake wearing different hats: an
            // agent half that was never read, and either scan that was cut short without
            // being able to say from where. `laterOrNull` reads a null as "this source
            // was complete", so handing it any of the three would publish the OTHER
            // source's boundary as the whole page's and claim completeness from an
            // instant nothing reached. Only the third is unreachable today; it is closed
            // here because the first one was not, and looked just as unreachable.
            boolean someSourceCannotSayWhere = agentHistoryUnavailable
                    || (epochScan.truncated() && epochScan.coveredFrom() == null)
                    || (agentWindow.truncated() && agentWindow.coveredFrom() == null);
            pastCoveredFrom = someSourceCannotSayWhere
                    ? null
                    : laterOrNull(epochScan.coveredFrom(), agentWindow.coveredFrom());
            // Each source SAYS whether it was cut short; neither is re-derived from a
            // nullable boundary. Deriving it would hide a truncation whose oldest row has
            // no start time - unreachable through today's queries, but the kind of hole
            // that opens the day a query changes, and it fails in the silent direction.
            //
            // An agent read that did not come back is also an INCOMPLETE history, not a
            // quiet one: no boundary to name, because nothing was read, but the page must
            // still say so rather than draw a month with no agent runs and call it true.
            pastTruncated = epochScan.truncated() || agentWindow.truncated()
                    || agentHistoryUnavailable;
        }

        occurrences.sort((a, b) -> {
            int byTime = a.startAt().compareTo(b.startAt());
            if (byTime != 0) return byTime;
            // Same instant: keep the order stable across refetches so a re-render does not
            // reshuffle a day cell under the user's cursor.
            return a.id().compareTo(b.id());
        });

        return new AgendaDto(windowStart, windowEnd, occurrences, markers,
                // Deduplicated: a standalone schedule can be declared by two workflows and
                // is emitted under each, so the raw list counted one schedule twice and the
                // banner said "2 schedules fire too often" - sending the user to look for a
                // second job that does not exist.
                truncatedScheduleIds.stream().distinct().toList(),
                pastTruncated, pastCoveredFrom, agentHistoryUnavailable);
    }

    /**
     * The later of two coverage boundaries, either of which may be null for "this source
     * was complete". Null only when BOTH were.
     *
     * <p>Only safe once the caller has excluded the other meaning of null - "truncated,
     * and cannot say from where" - which is what {@code someSourceCannotSayWhere} above
     * is for. Do not call this with a raw pair of boundaries.
     */
    private static Instant laterOrNull(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Projections
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Project one armed schedule onto the window.
     *
     * <p>Order matters here. The pending fire from {@code next_execution_at} is emitted
     * FIRST and verbatim, because it is what the daemon will actually do - including when
     * the user has moved it away from its cron slot. Only then is the cron expanded, and
     * only strictly after that pending fire, so the two sources can never produce the
     * same occurrence twice or contradict each other.
     *
     * @return true when the per-schedule cap cut the expansion short
     */
    private boolean appendProjectedOccurrences(ActiveAutomationDto automation,
                                               ActiveAutomationDto.ScheduleInfo schedule,
                                               Instant windowStart, Instant windowEnd, Instant now,
                                               List<Occurrence> out) {
        String cron = schedule.cronExpression();
        String timezone = schedule.timezone();
        boolean moveAllSupported = CronShifter.supportsShiftAll(cron);
        Instant pendingFire = schedule.nextFireAt();
        int emitted = 0;

        Instant expandFrom = maxInstant(windowStart, now);
        if (pendingFire != null) {
            // An overdue pending fire (daemon behind, or the user moved it into the past)
            // still belongs on the calendar at its stored time: it is what happens next.
            boolean insideWindow = !pendingFire.isBefore(windowStart) && !pendingFire.isAfter(windowEnd);
            if (insideWindow) {
                out.add(toPlannedOccurrence(automation, schedule, pendingFire, cron, timezone,
                        true, isOffCronSlot(cron, timezone, pendingFire), moveAllSupported));
                emitted++;
            }
            // Whether or not it was inside the window, the cron walk must resume after it,
            // or a pending fire moved LATER would be shadowed by the cron slot it skipped.
            expandFrom = maxInstant(expandFrom, pendingFire.plusSeconds(1));
        }

        if (!expandFrom.isAfter(windowEnd)) {
            CronOccurrences.Window window = CronOccurrences.between(
                    cron, timezone, expandFrom, windowEnd, maxOccurrencesPerSchedule - emitted);
            for (Instant fire : window.occurrences()) {
                out.add(toPlannedOccurrence(automation, schedule, fire, cron, timezone,
                        false, false, moveAllSupported));
            }
            return window.truncated();
        }
        return false;
    }

    /**
     * Whether an instant is NOT a slot this cron would produce, i.e. the user moved this
     * single occurrence. Asked by expanding the cron over the zero-width window
     * {@code [fire, fire]}: a hit means the instant is a natural slot.
     */
    private static boolean isOffCronSlot(String cron, String timezone, Instant fire) {
        // Cheap check first: does the expression produce this exact instant?
        if (!CronOccurrences.between(cron, timezone, fire, fire, 1).occurrences().isEmpty()) {
            return false;
        }
        // It said no, which on ONE kind of day is a lie. The walk cursors in local time, so
        // on a spring-forward day a slot whose wall-clock time does not exist comes back an
        // hour later than the cursor believes, and a one-instant window cannot see it. The
        // fire is natural; badging it "moved" would tell the user their schedule had been
        // dragged when nobody touched it.
        //
        // So re-ask over a window wide enough to contain any zone's transition (two hours
        // covers every current one) and look for the instant itself rather than for
        // emptiness. Only reached when the cheap check already said "moved", so an ordinary
        // day pays nothing, and the cap bounds the walk for a frequent cron.
        return !CronOccurrences.between(cron, timezone, fire.minusSeconds(7200), fire, 200)
                .occurrences().contains(fire);
    }

    private Occurrence toPlannedOccurrence(ActiveAutomationDto automation,
                                           ActiveAutomationDto.ScheduleInfo schedule,
                                           Instant startAt, String cron, String timezone,
                                           boolean isNextFire, boolean overridden,
                                           boolean moveAllSupported) {
        return new Occurrence(
                // Keyed by RESOURCE as well as schedule: one standalone schedule can be
                // declared by two pinned workflows, and ActiveAutomationsService emits it
                // under each (its de-dup is per-workflow). Without the resource id the two
                // projections collide, React sees duplicate keys and dnd-kit registers two
                // draggables under one id, so a drag lands on the wrong chip.
                automation.resourceId() + ":" + schedule.scheduleId() + "@" + startAt.toEpochMilli(),
                OccurrenceKind.PLANNED,
                startAt,
                null,
                automation.resourceType(),
                automation.resourceId(),
                automation.name(),
                automation.avatarUrl(),
                schedule.scheduleId(),
                automation.triggerId(),
                ActiveAutomationDto.TriggerType.SCHEDULE,
                cron,
                timezone,
                // Not a constant any more. A workflow over its SPENDING cap keeps
                // an armed schedule with a live cron - the fires are simply
                // refused until the allowance starts again - so the occurrences
                // inside that window are drawn as not-going-to-happen and the
                // ones after it are drawn normally, because they will happen. A
                // null "until" while blocked means the cap never resets, so the
                // whole projected future is refused.
                !Boolean.TRUE.equals(automation.resourcePaused()) && !(schedule.budgetBlocked()
                        && (schedule.budgetBlockedUntil() == null
                            || startAt.isBefore(schedule.budgetBlockedUntil()))),
                isNextFire,
                overridden,
                moveAllSupported,
                "PLANNED",
                automation.productionRunIdPublic(),
                Boolean.TRUE.equals(automation.resourcePaused()),
                // NOW, not at startAt: this drives "run early", which runs the schedule at
                // the moment of the click and not the fire being looked at.
                schedule.budgetBlocked(),
                null,   // a projection has not fired, so it has no epoch
                automation.publicationId());
    }

    private Marker toMarker(ActiveAutomationDto automation, ActiveAutomationDto.ScheduleInfo schedule) {
        return new Marker(
                automation.resourceType(),
                automation.resourceId(),
                automation.name(),
                automation.avatarUrl(),
                automation.triggerType(),
                automation.triggerId(),
                automation.triggerLabel(),
                schedule != null ? schedule.scheduleId() : null,
                schedule != null ? schedule.cronExpression() : null,
                schedule != null ? schedule.timezone() : null,
                schedule != null ? schedule.nextFireAt() : null,
                automation.lastRunAt(),
                !Boolean.TRUE.equals(automation.resourcePaused()) && (schedule == null || schedule.armed()),
                Boolean.TRUE.equals(automation.resourcePaused()),
                schedule != null ? schedule.pausedReason() : null,
                automation.productionRunIdPublic(),
                automation.publicationId());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // History
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Add every trigger fire that actually happened inside the window.
     *
     * <p>Read straight from the epoch headers, joined to their run and workflow, with NO pin
     * predicate: an automation row exists only while a workflow is pinned, but a run that
     * happened last Tuesday happened whether or not the workflow is pinned today. Resolving
     * history through the production-run lookup (as this first did) made past runs disappear
     * on unpin and on every re-pin - the calendar quietly rewriting history.
     *
     * @return whether the cap cut this scan short, and the oldest fire it reached when
     *         it did - "history before this instant is missing". The two are separate
     *         because a truncated scan whose oldest row carries no start time still
     *         truncated, and collapsing them into a nullable instant would report that
     *         window as complete.
     */
    private PastScan appendPastFires(String orgId, Instant from, Instant to, List<Occurrence> out) {
        if (orgId == null || !from.isBefore(to)) return PastScan.complete();

        // Newest-first from the query so a cap keeps the days the user is looking at; the
        // caller sorts the whole window chronologically afterwards.
        // One row PAST the cap, the same probe the agent window uses. `size() >= cap`
        // calls a window holding EXACTLY the cap truncated, and now that a truncated
        // window also publishes a boundary, that reads on screen as "history is complete
        // from 09:12, some earlier runs are not shown" about a window that is whole. The
        // two sources have to answer this question the same way or the page contradicts
        // itself depending on which one filled up.
        List<WorkflowEpochRepository.WorkspaceFireRow> page = maxPastFires > 0
                ? epochRepository.findWorkspaceFiresBetween(orgId, from, to, maxPastFires + 1)
                : List.of();
        boolean capHit = page.size() > maxPastFires;
        List<WorkflowEpochRepository.WorkspaceFireRow> fires =
                capHit ? page.subList(0, maxPastFires) : page;

        Set<String> seen = new HashSet<>();
        for (WorkflowEpochRepository.WorkspaceFireRow row : fires) {
            WorkflowEpochRepository.EpochFireRow fire = row.fire();
            if (fire.startedAt() == null || row.workflowId() == null) continue;
            // A run can carry several trigger DAGs, each with its own epoch numbering, so
            // the identity has to include the trigger or two same-numbered epochs collide.
            String id = fire.runId() + "#" + fire.triggerId() + "#" + fire.epoch();
            if (!seen.add(id)) continue;

            ResourceType type = WorkflowType.APPLICATION.name().equals(row.workflowType())
                    ? ResourceType.APPLICATION
                    : ResourceType.WORKFLOW;
            out.add(new Occurrence(
                    id,
                    OccurrenceKind.PAST,
                    fire.startedAt(),
                    fire.closedAt(),
                    type,
                    row.workflowId(),
                    row.workflowName(),
                    null,
                    null,
                    fire.triggerId(),
                    triggerType(row.triggersJson(), fire.triggerId()),
                    null,
                    null,
                    true,
                    false,
                    false,
                    false,
                    pastStatus(fire),
                    fire.runId(),
                    false,
                    // A fire that already happened cannot be blocked by a cap: the question
                    // only applies to something that has yet to run.
                    false,
                    fire.epoch(),
                    type == ResourceType.APPLICATION ? row.sourcePublicationId() : null));
        }
        // Newest-first from the query, so the last row KEPT is the oldest fire this page
        // covers. Read off the query RESULT rather than the emitted occurrences: the
        // skips above are unreachable through this query today (it joins the workflow and
        // filters on started_at), but a row that ever were skipped would still have
        // consumed a slot in the page, and the boundary must describe the scan.
        if (!capHit || fires.isEmpty()) {
            return PastScan.complete();
        }
        return new PastScan(true, fires.get(fires.size() - 1).fire().startedAt());
    }

    /**
     * What one past-history scan covered.
     *
     * @param truncated   the cap cut it short
     * @param coveredFrom the oldest row it reached, or null when it is complete OR when a
     *                    truncated scan could not name one. Never read on its own: a null
     *                    boundary with {@code truncated} true means "incomplete, and I
     *                    cannot say from where", which the page states rather than hides.
     */
    private record PastScan(boolean truncated, Instant coveredFrom) {
        static PastScan complete() {
            return new PastScan(false, null);
        }
    }

    /**
     * Add every AGENT run that happened inside the window, each tagged with how it was
     * launched.
     *
     * <p>Agents do not produce epochs, so none of this can come from the epoch table. It
     * is one batched call to agent-service, which owns the {@code agent} schema, for the
     * whole window - never one call per agent and never one per day.
     *
     * <p><b>Fails open, and says so.</b> {@code AgentClient} turns any error into
     * {@link AgentRunWindowDto#unavailable()}, so an agent-service that is down costs the
     * calendar its agent rows and leaves the rest of the month intact - while the page
     * still reports that half of its history could not be read. Failing the whole page
     * because one of two history sources is unavailable would be worse on a surface
     * people open to check whether their automations are running; drawing a quiet month
     * and calling it complete would be worse still.
     *
     * <p>Runs are kept even for an agent that is now inactive or has no trigger left, on
     * the same reasoning as the epoch scan: what ran, ran. A run whose AGENT has been
     * deleted is the one exception, and it is dropped upstream by the query, because it
     * could be neither named nor opened.
     *
     * @return the window as agent-service described it: the rows appended, whether the
     *         cap cut the scan short, where coverage starts, and whether it could be read
     *         at all. Completeness is NOT re-derived from the row count here: the
     *         endpoint applies a per-member deny-list after its cap, so a short list does
     *         not mean a complete window.
     */
    private AgentRunWindowDto appendAgentRuns(String tenantId, String orgId, String orgRole,
                                              Instant from, Instant to, List<Occurrence> out) {
        if (orgId == null || orgId.isBlank() || !from.isBefore(to)) {
            return AgentRunWindowDto.of(List.of(), false, null);
        }

        // Clamped to the ceiling the endpoint enforces, so the truncation test below
        // compares against a number the server can actually honour. Configure
        // agenda.max-past-agent-runs above it and the page would receive a full page,
        // measure it against the larger figure and report the window complete.
        int limit = Math.min(maxPastAgentRuns, AgentClient.WORKSPACE_RUNS_MAX_LIMIT);
        AgentRunWindowDto window = agentClient.getWorkspaceAgentRuns(
                tenantId, orgId, orgRole, from, to, limit);
        // The client never returns null by construction; if one ever did, "we could not
        // read it" is the honest reading, and it is certainly better than an NPE that
        // takes the whole calendar down over its second history source.
        if (window == null) return AgentRunWindowDto.unavailable();
        List<AgentRunFireDto> runs = window.runs();

        for (AgentRunFireDto run : runs) {
            if (run == null || run.startedAt() == null || run.agentId() == null) continue;
            out.add(new Occurrence(
                    // The execution id alone. Unlike an epoch, which is only unique
                    // within its run, this IS the row's primary key.
                    "agent-run:" + run.executionId(),
                    OccurrenceKind.PAST,
                    run.startedAt(),
                    run.endedAt(),
                    ResourceType.AGENT,
                    run.agentId(),
                    run.agentName(),
                    null,
                    null,   // no schedule row: the launch may not have been a schedule at all
                    null,   // and no plan-level trigger key: an agent has no trigger nodes
                    null,   // so no workflow TriggerType either - launchSource says it instead
                    null,
                    null,
                    true,   // it already happened; "will it fire" is not a question
                    false,
                    false,
                    false,
                    agentRunStatus(run.status()),
                    null,   // an agent run is not a workflow run: there is no runIdPublic
                    false,
                    false,  // no spending cap can refuse a run that already happened
                    null,   // and no epoch
                    null,
                    launchSourceOf(run.source()),
                    run.conversationId()));
        }
        return window;
    }

    /**
     * {@code agent_executions.source} to the calendar's vocabulary.
     *
     * <p>Returns null for anything unrecognised, deliberately. The column is written by
     * six services and its values are not a shared enum, so a value that arrives here
     * unknown is a value nobody taught this map - and drawing it as the nearest match
     * would state something false about how a run started. The page falls back to the
     * agent icon, which says nothing rather than the wrong thing.
     */
    private static AgendaDto.LaunchSource launchSourceOf(String source) {
        if (source == null) return null;
        return switch (source.toUpperCase(Locale.ROOT)) {
            case "CHAT" -> AgendaDto.LaunchSource.CHAT;
            case "SCHEDULE" -> AgendaDto.LaunchSource.SCHEDULE;
            case "WEBHOOK" -> AgendaDto.LaunchSource.WEBHOOK;
            case "WORKFLOW" -> AgendaDto.LaunchSource.WORKFLOW;
            case "SUB_AGENT" -> AgendaDto.LaunchSource.SUB_AGENT;
            // A delegated task and the review of that task are one thing on a calendar:
            // both are "the task system ran this agent", and the pair would add a filter
            // chip most users have no way to act on differently.
            case "TASK", "TASK_REVIEW" -> AgendaDto.LaunchSource.TASK;
            case "WIDGET" -> AgendaDto.LaunchSource.WIDGET;
            default -> null;
        };
    }

    /**
     * What an agent run achieved, in the same four-plus-one words the rest of the
     * calendar uses.
     *
     * <p>{@code agent_executions.status} is already a resolved outcome, so this maps
     * rather than derives. Only three values reach it today: the row is written ONCE, at
     * the end of the execution, so it is always COMPLETED, FAILED or CANCELLED. RUNNING
     * is carried anyway because the column defaults to it and four repository finders
     * still select on it; it describes no row this query can currently return, and is
     * kept so that changing the writer does not silently paint live runs grey.
     *
     * <p>An unrecognised value becomes FIRED - "it happened, the outcome is not something
     * this page knows how to name" - which is the same word the epoch path uses for a
     * fire with no verdict, and never FAILED: inventing a failure from an unknown string
     * would put red on a calendar for a run that worked.
     */
    private static String agentRunStatus(String status) {
        if (status == null) return "FIRED";
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "COMPLETED" -> "COMPLETED";
            case "FAILED" -> "FAILED";
            case "CANCELLED" -> "CANCELLED";
            case "RUNNING" -> "RUNNING";
            default -> "FIRED";
        };
    }

    /** Resolve the epoch's normalized trigger key against the immutable plan snapshot it ran. */
    private ActiveAutomationDto.TriggerType triggerType(String triggersJson, String triggerId) {
        if (triggersJson == null || triggerId == null) return null;
        try {
            JsonNode triggers = objectMapper.readTree(triggersJson);
            if (triggers == null || !triggers.isArray()) return null;
            for (JsonNode trigger : triggers) {
                String label = trigger.path("label").asText(null);
                String id = trigger.path("id").asText(null);
                String normalized = LabelNormalizer.normalizeLabel(
                        label != null && !label.isBlank() ? label : id);
                if (normalized != null && triggerId.equals("trigger:" + normalized)) {
                    return triggerType(trigger.path("type").asText(null));
                }
            }
        } catch (Exception ignored) {
            // Legacy or malformed run plan: keep the history row, only omit its kind.
        }
        return null;
    }

    private static ActiveAutomationDto.TriggerType triggerType(String value) {
        if (value == null) return null;
        try {
            return ActiveAutomationDto.TriggerType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * What a past fire achieved.
     *
     * <p>An open epoch is RUNNING. A closed one is asked of
     * {@link WorkflowEpochService#deriveEpochOutcome}, the same function the run history
     * badge uses, so the calendar cannot contradict the run page for the same cycle. That
     * function answers null when the epoch closed without executing anything past the
     * trigger, which is not "nothing happened": the trigger did fire. That case is FIRED.
     */
    private String pastStatus(WorkflowEpochRepository.EpochFireRow fire) {
        if (fire.isActive()) return "RUNNING";
        EpochState state = deserializeEpochState(fire.epochStateJson());
        String outcome = WorkflowEpochService.deriveEpochOutcome(state, false);
        return outcome != null ? outcome : "FIRED";
    }

    /** Parse a stored epoch state; null (never a throw) when absent or unreadable. */
    private EpochState deserializeEpochState(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, EpochState.class);
        } catch (Exception e) {
            // One unreadable epoch must not take the month down; it degrades to FIRED.
            logger.debug("[Agenda] Unreadable epoch_state, falling back to FIRED: {}", e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Window helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private Instant clampWindowEnd(Instant from, Instant to) {
        Instant maxEnd = from.plus(Duration.ofDays(maxWindowDays));
        if (to == null || to.isBefore(from)) return maxEnd;
        return to.isAfter(maxEnd) ? maxEnd : to;
    }

    private static Instant maxInstant(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    /** Unmodifiable empty agenda, for callers with no workspace resolved. */
    public static AgendaDto empty(Instant from, Instant to) {
        return new AgendaDto(from, to, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), false);
    }
}
