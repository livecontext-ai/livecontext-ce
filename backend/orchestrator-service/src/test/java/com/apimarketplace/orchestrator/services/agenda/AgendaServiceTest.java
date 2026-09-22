package com.apimarketplace.orchestrator.services.agenda;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentRunWindowDto;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto.ResourceType;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto.ScheduleInfo;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto.TriggerType;
import com.apimarketplace.orchestrator.controllers.dto.AgendaDto;
import com.apimarketplace.orchestrator.controllers.dto.AgendaDto.Occurrence;
import com.apimarketplace.orchestrator.controllers.dto.AgendaDto.OccurrenceKind;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository;
import com.apimarketplace.orchestrator.services.ActiveAutomationsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * How the calendar decides what to draw.
 *
 * <p>The assertions that matter most are about the seam between the two sources of the
 * future. A schedule carries a cron AND a pending fire time, and they disagree by design
 * as soon as a user moves one occurrence. The daemon fires on the pending time, so the
 * calendar has to as well - and it must not ALSO draw the cron slot that pending time
 * replaced, or the user sees the run they just moved still sitting in its old place.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgendaService")
class AgendaServiceTest {

    @Mock private ActiveAutomationsService activeAutomationsService;
    @Mock private WorkflowEpochRepository epochRepository;
    @Mock private AgentClient agentClient;

    private AgendaService service;

    private static final String TENANT = "user-1";
    private static final String ORG = "org-1";
    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final UUID SCHEDULE_ID = UUID.randomUUID();

    // A window entirely in the future, so nothing here depends on the wall clock.
    private static final Instant WINDOW_FROM = Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
    private static final Instant WINDOW_TO = WINDOW_FROM.plusSeconds(7 * 24 * 3600);

    @BeforeEach
    void setUp() {
        service = new AgendaService(activeAutomationsService, epochRepository, agentClient, new ObjectMapper());
        ReflectionTestUtils.setField(service, "maxOccurrencesPerSchedule", 200);
        ReflectionTestUtils.setField(service, "maxPastFires", 2000);
        ReflectionTestUtils.setField(service, "maxPastAgentRuns", 1000);
        // This file is about the WORKFLOW side of the calendar. A bare mock answers null
        // for a record, which the service reads as "could not ask" - correct in
        // production, wrong here: it would mark every window in this file truncated.
        when(agentClient.getWorkspaceAgentRuns(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(AgentRunWindowDto.of(java.util.List.of(), false, null));
        ReflectionTestUtils.setField(service, "maxWindowDays", 120);
    }

    private void givenAutomations(ActiveAutomationDto... automations) {
        when(activeAutomationsService.getAgendaAutomations(eq(TENANT), eq(ORG), any()))
                .thenReturn(List.of(automations));
    }

    private static ActiveAutomationDto scheduleAutomation(String cron, String timezone,
                                                          Instant nextFireAt, boolean armed) {
        return scheduleAutomation(cron, timezone, nextFireAt, armed, false, null);
    }

    /**
     * @param budgetBlocked      the workflow's SPENDING cap is refusing its fires
     * @param budgetBlockedUntil when that lifts, or null while blocked to mean
     *                           "not on its own" (a cap that never resets)
     */
    private static ActiveAutomationDto scheduleAutomation(String cron, String timezone,
                                                          Instant nextFireAt, boolean armed,
                                                          boolean budgetBlocked, Instant budgetBlockedUntil) {
        return new ActiveAutomationDto(ResourceType.WORKFLOW, WORKFLOW_ID, "Daily report", null,
                TriggerType.SCHEDULE,
                new ScheduleInfo(cron, timezone, nextFireAt, 3, SCHEDULE_ID, armed,
                        armed ? null : ActiveAutomationDto.PausedReason.USER,
                        budgetBlocked, budgetBlockedUntil),
                null, null, true, false, "run-public-1", null, null,
                "trigger:daily", "Daily schedule");
    }

    private AgendaDto agenda(Instant from, Instant to, boolean includePast) {
        return service.getAgenda(TENANT, ORG, "MEMBER", from, to, includePast);
    }

    @Nested
    @DisplayName("projected occurrences")
    class Projections {

        // ─── A workflow stopped by its SPENDING cap ───
        //
        // Different in kind from the three paused reasons, and the agenda has to
        // treat it differently. Those are states of the SCHEDULE and are durable:
        // the schedule is un-armed and gets no occurrences at all. A spending
        // block is a state of the WORKFLOW and is temporary: the schedule is
        // still armed, still has a cron, and its fires resume on their own. So
        // the occurrences are still DRAWN - the calendar would otherwise go
        // blank and imply the automation was deleted - but the ones that will be
        // refused say so, and the ones after the reset do not.

        @Test
        @DisplayName("occurrences before the allowance resets are marked as not going to fire")
        void blockedOccurrencesAreNotArmed() {
            Instant nine = nextUtcTimeAfter(WINDOW_FROM, 9, 0);
            Instant resetsAt = WINDOW_FROM.plusSeconds(3 * 24 * 3600);
            givenAutomations(scheduleAutomation("0 9 * * *", "UTC", nine, true, true, resetsAt));

            List<Occurrence> planned = plannedOf(agenda(WINDOW_FROM, WINDOW_TO, false));

            assertThat(planned).isNotEmpty();
            assertThat(planned.stream().filter(o -> o.startAt().isBefore(resetsAt)))
                    .allMatch(o -> !o.armed());
        }

        @Test
        @DisplayName("every projection carries the RESOURCE verdict, including the ones drawn armed")
        void blockedNowTravelsOnEveryProjection() {
            // armed is per-occurrence and answers "will THIS fire happen". budgetBlocked is
            // per-resource and answers "is the cap refusing right now", which is the question
            // running early depends on: it runs the schedule at the moment of the click, not
            // the fire being looked at. An occurrence after the reset is armed AND blocked-now,
            // and a menu that read only armed offered an action whose one outcome was a toast.
            Instant nine = nextUtcTimeAfter(WINDOW_FROM, 9, 0);
            Instant resetsAt = WINDOW_FROM.plusSeconds(3 * 24 * 3600);
            givenAutomations(scheduleAutomation("0 9 * * *", "UTC", nine, true, true, resetsAt));

            List<Occurrence> planned = plannedOf(agenda(WINDOW_FROM, WINDOW_TO, false));

            assertThat(planned).isNotEmpty().allMatch(Occurrence::budgetBlocked);
            assertThat(planned.stream().filter(o -> !o.startAt().isBefore(resetsAt)))
                    .isNotEmpty()
                    .allMatch(o -> o.armed() && o.budgetBlocked());
        }

        @Test
        @DisplayName("an unblocked schedule carries a false verdict, not a missing one")
        void anUnblockedScheduleIsNotMarked() {
            Instant nine = nextUtcTimeAfter(WINDOW_FROM, 9, 0);
            givenAutomations(scheduleAutomation("0 9 * * *", "UTC", nine, true, false, null));

            assertThat(plannedOf(agenda(WINDOW_FROM, WINDOW_TO, false)))
                    .isNotEmpty().allMatch(o -> !o.budgetBlocked());
        }

        @Test
        @DisplayName("occurrences AFTER the reset are drawn normally, because they will happen")
        void occurrencesAfterTheResetAreArmed() {
            // The half that makes this worth doing. Greying the whole future
            // would say the automation is finished, when in fact it restarts on
            // a known date.
            Instant nine = nextUtcTimeAfter(WINDOW_FROM, 9, 0);
            Instant resetsAt = WINDOW_FROM.plusSeconds(3 * 24 * 3600);
            givenAutomations(scheduleAutomation("0 9 * * *", "UTC", nine, true, true, resetsAt));

            List<Occurrence> planned = plannedOf(agenda(WINDOW_FROM, WINDOW_TO, false));

            assertThat(planned.stream().filter(o -> !o.startAt().isBefore(resetsAt)))
                    .isNotEmpty()
                    .allMatch(Occurrence::armed);
        }

        @Test
        @DisplayName("a cap that never resets refuses the whole projected future")
        void aCapThatNeverResetsBlocksEverything() {
            // A null "until" while blocked means the block does not lift on its
            // own. Reading that null as "not blocked" would draw a week of fires
            // that cannot happen.
            Instant nine = nextUtcTimeAfter(WINDOW_FROM, 9, 0);
            givenAutomations(scheduleAutomation("0 9 * * *", "UTC", nine, true, true, null));

            List<Occurrence> planned = plannedOf(agenda(WINDOW_FROM, WINDOW_TO, false));

            assertThat(planned).isNotEmpty().allMatch(o -> !o.armed());
        }

        @Test
        @DisplayName("an unblocked workflow is unaffected: every occurrence stays armed")
        void unblockedWorkflowIsUnchanged() {
            Instant nine = nextUtcTimeAfter(WINDOW_FROM, 9, 0);
            givenAutomations(scheduleAutomation("0 9 * * *", "UTC", nine, true));

            assertThat(plannedOf(agenda(WINDOW_FROM, WINDOW_TO, false)))
                    .isNotEmpty()
                    .allMatch(Occurrence::armed);
        }

        @Test
        @DisplayName("a budget-blocked schedule still gets its occurrences, unlike a PAUSED one")
        void blockedStillProjectsUnlikePaused() {
            // The distinction, stated as a test: paused draws nothing (the
            // schedule is un-armed and becomes a marker), blocked draws a
            // greyed-out future that comes back.
            Instant nine = nextUtcTimeAfter(WINDOW_FROM, 9, 0);

            givenAutomations(scheduleAutomation("0 9 * * *", "UTC", nine, false));
            assertThat(plannedOf(agenda(WINDOW_FROM, WINDOW_TO, false))).isEmpty();

            givenAutomations(scheduleAutomation("0 9 * * *", "UTC", nine, true, true, null));
            assertThat(plannedOf(agenda(WINDOW_FROM, WINDOW_TO, false))).isNotEmpty();
        }

        @Test
        @DisplayName("draws the pending fire from the schedule row, then the cron after it")
        void pendingFireFirstThenCron() {
            // The pending fire is a natural 09:00 slot here, so this is the ordinary case:
            // the calendar must show it once, not twice, and continue on the cron.
            Instant nine = nextUtcTimeAfter(WINDOW_FROM, 9, 0);
            givenAutomations(scheduleAutomation("0 9 * * *", "UTC", nine, true));

            List<Occurrence> planned = plannedOf(agenda(WINDOW_FROM, WINDOW_TO, false));

            assertThat(planned).extracting(Occurrence::startAt).contains(nine);
            assertThat(planned).extracting(Occurrence::startAt).doesNotHaveDuplicates();
            assertThat(planned).allSatisfy(o -> {
                assertThat(o.kind()).isEqualTo(OccurrenceKind.PLANNED);
                assertThat(o.scheduleId()).isEqualTo(SCHEDULE_ID);
                assertThat(o.status()).isEqualTo("PLANNED");
            });
        }

        @Test
        @DisplayName("a moved occurrence is drawn where it was moved TO, flagged overridden")
        void movedOccurrenceIsDrawnAtItsNewTime() {
            // "This occurrence only" wrote 14:30 into the row and left the 09:00 cron alone.
            Instant movedTo = nextUtcTimeAfter(WINDOW_FROM, 14, 30);
            givenAutomations(scheduleAutomation("0 9 * * *", "UTC", movedTo, true));

            List<Occurrence> planned = plannedOf(agenda(WINDOW_FROM, WINDOW_TO, false));

            Occurrence first = planned.get(0);
            assertThat(first.startAt()).isEqualTo(movedTo);
            assertThat(first.overridden()).isTrue();
        }

        @Test
        @DisplayName("does NOT also draw the cron slot a later-moved occurrence replaced")
        void movedLaterDoesNotLeaveItsOldSlotBehind() {
            // Moving tomorrow's 09:00 run to tomorrow 14:30 must REMOVE it from 09:00.
            // Expanding the cron from the window start instead of from the pending fire is
            // exactly how that bug appears: the run shows up twice and the user cannot tell
            // which one is real.
            Instant nine = nextUtcTimeAfter(WINDOW_FROM, 9, 0);
            Instant movedTo = nine.plusSeconds(5 * 3600 + 1800);   // same day, 14:30
            givenAutomations(scheduleAutomation("0 9 * * *", "UTC", movedTo, true));

            List<Occurrence> planned = plannedOf(agenda(WINDOW_FROM, WINDOW_TO, false));

            assertThat(planned).extracting(Occurrence::startAt).doesNotContain(nine);
            assertThat(planned.get(0).startAt()).isEqualTo(movedTo);
            // The day after is back on schedule - a single-occurrence move is not permanent.
            assertThat(planned.get(1).startAt()).isEqualTo(nine.plusSeconds(24 * 3600));
        }

        @Test
        @DisplayName("an overdue pending fire is still drawn, at the time it is stored")
        void overduePendingFireIsDrawn() {
            // The daemon treats an overdue next_execution_at as due right now, so hiding it
            // would tell the user nothing is coming when a run is about to happen.
            Instant overdue = Instant.now().minusSeconds(600);
            givenAutomations(scheduleAutomation("0 9 * * *", "UTC", overdue, true));

            AgendaDto result = agenda(overdue.minusSeconds(3600), WINDOW_TO, false);

            assertThat(plannedOf(result)).extracting(Occurrence::startAt).contains(overdue);
        }

        @Test
        @DisplayName("marks EXACTLY the pending fire as the one that can be moved on its own")
        void onlyThePendingFireIsMovable() {
            // The whole NOT_THE_NEXT_OCCURRENCE design rests on this flag: a schedule holds
            // one pending fire, so "move this occurrence" is truthful for the first chip and
            // a silent cancellation of every run before it for any other. Mutating this to a
            // constant true would let the UI offer that move on all 200 chips.
            Instant nine = nextUtcTimeAfter(WINDOW_FROM, 9, 0);
            givenAutomations(scheduleAutomation("0 9 * * *", "UTC", nine, true));

            List<Occurrence> planned = plannedOf(agenda(WINDOW_FROM, WINDOW_TO, false));

            assertThat(planned).hasSizeGreaterThan(2);
            assertThat(planned.get(0).isNextFire()).isTrue();
            assertThat(planned.subList(1, planned.size()))
                    .allSatisfy(o -> assertThat(o.isNextFire()).isFalse());
        }

        @Test
        @DisplayName("gives one schedule declared by two workflows distinct occurrence ids")
        void oneScheduleUnderTwoResourcesDoesNotCollide() {
            // A standalone schedule can be declared by two pinned workflows, and the upstream
            // de-dup is per-workflow, so it legitimately arrives twice. Identical ids make
            // React warn on duplicate keys and make dnd-kit register two draggables under one
            // id, so a drag resolves to the wrong chip.
            Instant nine = nextUtcTimeAfter(WINDOW_FROM, 9, 0);
            UUID otherWorkflow = UUID.randomUUID();
            ActiveAutomationDto second = new ActiveAutomationDto(ResourceType.WORKFLOW, otherWorkflow,
                    "Second workflow", null, TriggerType.SCHEDULE,
                    new ScheduleInfo("0 9 * * *", "UTC", nine, 3, SCHEDULE_ID, true, null, false, null),
                    null, null, true, "run-public-2", null, null);
            givenAutomations(scheduleAutomation("0 9 * * *", "UTC", nine, true), second);

            List<Occurrence> planned = plannedOf(agenda(WINDOW_FROM, WINDOW_TO, false));

            assertThat(planned).extracting(Occurrence::id).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("marks whether the cron can be rewritten, so the page knows to offer 'all'")
        void reportsMoveAllSupport() {
            givenAutomations(scheduleAutomation("0 9 * * *", "UTC",
                    nextUtcTimeAfter(WINDOW_FROM, 9, 0), true));
            assertThat(plannedOf(agenda(WINDOW_FROM, WINDOW_TO, false)).get(0).moveAllSupported()).isTrue();

            givenAutomations(scheduleAutomation("*/15 * * * *", "UTC",
                    WINDOW_FROM.plusSeconds(60), true));
            assertThat(plannedOf(agenda(WINDOW_FROM, WINDOW_TO, false)).get(0).moveAllSupported()).isFalse();
        }

        @Test
        @DisplayName("reports the schedules it had to abbreviate instead of implying they stop")
        void reportsTruncatedSchedules() {
            ReflectionTestUtils.setField(service, "maxOccurrencesPerSchedule", 5);
            givenAutomations(scheduleAutomation("* * * * *", "UTC",
                    WINDOW_FROM.plusSeconds(60), true));

            AgendaDto result = agenda(WINDOW_FROM, WINDOW_TO, false);

            assertThat(result.truncatedScheduleIds()).containsExactly(SCHEDULE_ID);
            assertThat(plannedOf(result)).hasSize(5);
        }

        @Test
        @DisplayName("clamps an absurdly long window instead of expanding a decade of crons")
        void clampsWindow() {
            ReflectionTestUtils.setField(service, "maxWindowDays", 7);
            givenAutomations(scheduleAutomation("0 9 * * *", "UTC",
                    nextUtcTimeAfter(WINDOW_FROM, 9, 0), true));

            AgendaDto result = agenda(WINDOW_FROM, WINDOW_FROM.plusSeconds(3650L * 24 * 3600), false);

            assertThat(result.to()).isEqualTo(WINDOW_FROM.plus(java.time.Duration.ofDays(7)));
            assertThat(plannedOf(result)).hasSizeLessThanOrEqualTo(8);
        }
    }

    @Nested
    @DisplayName("DST")
    class DaylightSaving {

        @Test
        @DisplayName("a natural fire on a spring-forward day is NOT badged as moved")
        void springForwardFireIsNotOverridden() {
            // "Moved" is a claim about what the user did. On the day the clocks go forward a
            // 02:30 slot does not exist, so the daemon arms 03:30 local - and the check that
            // asks "is this instant on the cron" cursors in local time, could not see it, and
            // told the user their schedule had been dragged when nobody touched it.
            Instant naturalFire = Instant.parse("2026-03-29T01:30:00Z");   // 03:30 Paris
            givenAutomations(scheduleAutomation("30 2 * * *", "Europe/Paris", naturalFire, true));

            List<Occurrence> planned = plannedOf(agenda(
                    Instant.parse("2026-03-29T00:00:00Z"), Instant.parse("2026-03-30T00:00:00Z"), false));

            assertThat(planned).isNotEmpty();
            assertThat(planned.get(0).overridden())
                    .as("nobody moved this fire; the zone did")
                    .isFalse();
        }

        @Test
        @DisplayName("a genuinely moved fire is still badged as moved")
        void aRealMoveIsStillReported() {
            // The other half: the wider re-check must not start excusing every off-cron fire,
            // or the badge stops meaning anything.
            Instant moved = Instant.parse("2026-06-15T14:30:00Z");
            givenAutomations(scheduleAutomation("0 9 * * *", "Europe/Paris", moved, true));

            List<Occurrence> planned = plannedOf(agenda(
                    Instant.parse("2026-06-15T00:00:00Z"), Instant.parse("2026-06-16T00:00:00Z"), false));

            assertThat(planned.get(0).overridden()).isTrue();
        }
    }

    @Nested
    @DisplayName("window edges")
    class WindowEdges {

        @Test
        @DisplayName("a pending fire beyond the window end draws nothing inside it")
        void pendingFireAfterTheWindowDrawsNothing() {
            // The pending fire is emitted verbatim and the cron expanded strictly AFTER it.
            // When that fire sits past the window, the expansion starts past the window too,
            // so the honest answer for these days is "nothing" - not the cron's own slots,
            // which this schedule is not going to use.
            Instant farAhead = WINDOW_TO.plus(30, java.time.temporal.ChronoUnit.DAYS);
            givenAutomations(scheduleAutomation("0 9 * * *", "UTC", farAhead, true));

            assertThat(plannedOf(agenda(WINDOW_FROM, WINDOW_TO, false))).isEmpty();
        }

        @Test
        @DisplayName("history is skipped entirely outside an organization workspace")
        void personalWorkspaceHasNoWorkspaceHistory() {
            // The history query is org-scoped by construction, so a null org has nothing to
            // scope to. Running it anyway would either read another workspace's fires or
            // fail; both are worse than drawing the projections alone.
            givenAutomations(scheduleAutomation("0 9 * * *", "UTC",
                    nextUtcTimeAfter(WINDOW_FROM, 9, 0), true));

            AgendaDto result = service.getAgenda(TENANT, null, "MEMBER", WINDOW_FROM, WINDOW_TO, true);

            assertThat(result.occurrences()).noneMatch(o -> o.kind() == OccurrenceKind.PAST);
            assertThat(result.pastTruncated()).isFalse();
        }
    }

    @Nested
    @DisplayName("markers - things with no date")
    class Markers {

        @Test
        @DisplayName("an active schedule remains searchable in the trigger catalogue")
        void activeScheduleIsAlsoAMarker() {
            givenAutomations(scheduleAutomation("0 9 * * *", "UTC",
                    nextUtcTimeAfter(WINDOW_FROM, 9, 0), true));

            AgendaDto result = agenda(WINDOW_FROM, WINDOW_TO, false);

            assertThat(plannedOf(result)).isNotEmpty()
                    .allMatch(o -> o.triggerType() == TriggerType.SCHEDULE);
            assertThat(result.markers()).singleElement().satisfies(marker -> {
                assertThat(marker.triggerType()).isEqualTo(TriggerType.SCHEDULE);
                assertThat(marker.scheduleId()).isEqualTo(SCHEDULE_ID);
                assertThat(marker.armed()).isTrue();
            });
        }

        @Test
        @DisplayName("planned occurrences retain the exact published schedule trigger identity")
        void plannedOccurrencesCarryExactTriggerIdentity() {
            givenAutomations(scheduleAutomation("0 9 * * *", "UTC",
                    nextUtcTimeAfter(WINDOW_FROM, 9, 0), true));

            AgendaDto result = agenda(WINDOW_FROM, WINDOW_TO, false);

            assertThat(plannedOf(result)).isNotEmpty()
                    .allSatisfy(occurrence -> assertThat(occurrence.triggerId())
                            .isEqualTo("trigger:daily"));
            assertThat(result.markers()).singleElement()
                    .satisfies(marker -> {
                        assertThat(marker.triggerId()).isEqualTo("trigger:daily");
                        assertThat(marker.triggerLabel()).isEqualTo("Daily schedule");
                    });
        }

        @Test
        @DisplayName("a paused schedule draws no occurrence at all, only a greyed marker")
        void pausedScheduleProducesNoOccurrences() {
            // Projecting a paused schedule would draw runs that are never going to happen -
            // the calendar would be actively misleading, not merely incomplete.
            givenAutomations(scheduleAutomation("0 9 * * *", "UTC",
                    nextUtcTimeAfter(WINDOW_FROM, 9, 0), false));

            AgendaDto result = agenda(WINDOW_FROM, WINDOW_TO, false);

            assertThat(plannedOf(result)).isEmpty();
            assertThat(result.markers()).hasSize(1);
            assertThat(result.markers().get(0).armed()).isFalse();
            assertThat(result.markers().get(0).scheduleId()).isEqualTo(SCHEDULE_ID);
            assertThat(result.markers().get(0).cronExpression()).isEqualTo("0 9 * * *");
        }

        @Test
        @DisplayName("a webhook never lands on a day - it has no fire time to claim")
        void webhookBecomesAMarker() {
            ActiveAutomationDto webhook = new ActiveAutomationDto(ResourceType.WORKFLOW, WORKFLOW_ID,
                    "Inbound hook", null, TriggerType.WEBHOOK, null,
                    new ActiveAutomationDto.WebhookInfo("POST"), Instant.now(), true, "run-public-1", null, null);
            givenAutomations(webhook);

            AgendaDto result = agenda(WINDOW_FROM, WINDOW_TO, false);

            assertThat(result.occurrences()).isEmpty();
            assertThat(result.markers()).hasSize(1);
            assertThat(result.markers().get(0).triggerType()).isEqualTo(TriggerType.WEBHOOK);
            assertThat(result.markers().get(0).armed()).isTrue();
            assertThat(result.markers().get(0).nextFireAt()).isNull();
        }
    }

    @Nested
    @DisplayName("past fires")
    class Past {

        private WorkflowEpochRepository.WorkspaceFireRow fireRow(
                Instant started, Instant closed, boolean active, String stateJson) {
            return fireRow(started, closed, active, stateJson, null);
        }

        /**
         * A fire of the SAME run at a different epoch. Two rows built by the helper
         * below are one occurrence, not two: the identity is runId#triggerId#epoch and
         * the service de-duplicates on it. A cap probe therefore has to be a distinct
         * fire, or the test measures de-duplication and calls it trimming.
         */
        private WorkflowEpochRepository.WorkspaceFireRow fireRowAtEpoch(int epoch, Instant started) {
            return new WorkflowEpochRepository.WorkspaceFireRow(
                    new WorkflowEpochRepository.EpochFireRow(
                            "run-public-1", "trigger:daily", epoch, started,
                            started.plusSeconds(5), false, null),
                    WORKFLOW_ID, "Daily report", "STANDARD", null, null);
        }

        private WorkflowEpochRepository.WorkspaceFireRow fireRow(
                Instant started, Instant closed, boolean active, String stateJson, String triggersJson) {
            return new WorkflowEpochRepository.WorkspaceFireRow(
                    new WorkflowEpochRepository.EpochFireRow(
                            "run-public-1", "trigger:daily", 4, started, closed, active, stateJson),
                    WORKFLOW_ID, "Daily report", "STANDARD", triggersJson, null);
        }

        @Test
        @DisplayName("carries a manual trigger kind so the agenda can find its past uses")
        void pastManualFireCarriesItsTriggerType() {
            Instant ran = Instant.now().minusSeconds(3600);
            givenAutomations();
            givenFires(fireRow(ran, ran.plusSeconds(5), false, null,
                    "[{\"id\":\"manual-1\",\"label\":\"Daily\",\"type\":\"manual\"}]"));

            AgendaDto result = agenda(ran.minusSeconds(60), Instant.now(), true);

            assertThat(result.occurrences()).singleElement()
                    .extracting(Occurrence::triggerType)
                    .isEqualTo(TriggerType.MANUAL);
        }

        @Test
        @DisplayName("carries the epoch it was, so a click can open the run ON that fire")
        void pastFireCarriesItsEpoch() {
            // Without it a click can only open the run, and a run's surfaces show the
            // CUMULATIVE view of every fire it ever had - so the user points at one dot on a
            // calendar and gets every Tuesday at once. The id embeds the epoch, but parsing
            // an id back apart is not an API.
            // History is bounded at now, so the fire has to be in the past for the window
            // to contain it at all.
            Instant ran = Instant.now().minusSeconds(24 * 3600);
            givenAutomations();
            givenFires(fireRow(ran, ran.plusSeconds(30), false, null));

            List<Occurrence> past = agenda(ran.minusSeconds(3600), Instant.now().plusSeconds(60), true)
                    .occurrences().stream()
                    .filter(o -> o.kind() == OccurrenceKind.PAST)
                    .toList();

            assertThat(past).isNotEmpty();
            assertThat(past).allSatisfy(o -> assertThat(o.epoch()).isEqualTo(4));
        }

        @Test
        @DisplayName("a projection carries none - it has not fired")
        void projectionHasNoEpoch() {
            givenAutomations(scheduleAutomation("0 9 * * *", "UTC",
                    nextUtcTimeAfter(WINDOW_FROM, 9, 0), true));

            assertThat(plannedOf(agenda(WINDOW_FROM, WINDOW_TO, false)))
                    .allSatisfy(o -> assertThat(o.epoch()).isNull());
        }

        private void givenFires(WorkflowEpochRepository.WorkspaceFireRow... rows) {
            when(epochRepository.findWorkspaceFiresBetween(anyString(), any(), any(), anyInt()))
                    .thenReturn(List.of(rows));
        }

        @Test
        @DisplayName("draws what actually ran, with its outcome and a link to the run")
        void drawsPastFires() {
            Instant from = Instant.now().minusSeconds(48 * 3600);
            Instant ran = Instant.now().minusSeconds(24 * 3600);
            givenAutomations();
            givenFires(fireRow(ran, ran.plusSeconds(30), false, null));

            AgendaDto result = agenda(from, Instant.now().plusSeconds(3600), true);

            List<Occurrence> past = result.occurrences().stream()
                    .filter(o -> o.kind() == OccurrenceKind.PAST).toList();
            assertThat(past).hasSize(1);
            assertThat(past.get(0).startAt()).isEqualTo(ran);
            assertThat(past.get(0).endAt()).isEqualTo(ran.plusSeconds(30));
            assertThat(past.get(0).runIdPublic()).isEqualTo("run-public-1");
            assertThat(past.get(0).triggerId()).isEqualTo("trigger:daily");
            assertThat(past.get(0).name()).isEqualTo("Daily report");
            // History is not actionable: it already happened.
            assertThat(past.get(0).isNextFire()).isFalse();
            assertThat(past.get(0).scheduleId()).isNull();
        }

        @Test
        @DisplayName("reads history through a query with NO pin predicate, so unpinning cannot erase it")
        void historyIsIndependentOfPinning() {
            // The previous implementation resolved runs via findProductionRunsBatch, whose
            // SQL requires pinned_version IS NOT NULL AND plan_version = pinned_version -
            // so unpinning erased every past run and a re-pin erased everything before it.
            // Asserting the SEAM (which repository call is made) is the only way a unit test
            // can pin that, because the pin filter lives inside the SQL of the other query.
            Instant ran = Instant.now().minusSeconds(24 * 3600);
            givenAutomations();
            givenFires(fireRow(ran, ran.plusSeconds(9), false, null));

            AgendaDto result = agenda(ran.minusSeconds(3600), Instant.now().plusSeconds(60), true);

            assertThat(result.occurrences()).hasSize(1);
            verify(epochRepository).findWorkspaceFiresBetween(eq(ORG), any(), any(), anyInt());
        }

        @Test
        @DisplayName("an epoch still open reads RUNNING, not an invented outcome")
        void openEpochIsRunning() {
            Instant ran = Instant.now().minusSeconds(120);
            givenAutomations();
            givenFires(fireRow(ran, null, true, null));

            AgendaDto result = agenda(ran.minusSeconds(3600), Instant.now().plusSeconds(60), true);

            assertThat(result.occurrences().get(0).status()).isEqualTo("RUNNING");
        }

        @Test
        @DisplayName("a closed epoch with no readable state reads FIRED - it did happen")
        void unreadableStateStillCountsAsAFire() {
            // "Nothing executed past the trigger" is not "nothing happened": the trigger
            // fired. Reporting it as a failure, or dropping it, would both be wrong.
            Instant ran = Instant.now().minusSeconds(3600);
            givenAutomations();
            givenFires(fireRow(ran, ran.plusSeconds(5), false, "{ not json"));

            AgendaDto result = agenda(ran.minusSeconds(3600), Instant.now().plusSeconds(60), true);

            assertThat(result.occurrences().get(0).status()).isEqualTo("FIRED");
        }

        @Test
        @DisplayName("an APPLICATION fire routes on its publication id, not its workflow id")
        void applicationFireCarriesItsPublicationId() {
            // The application route is keyed by publication id; sending the workflow id is a 404.
            Instant ran = Instant.now().minusSeconds(3600);
            givenAutomations();
            when(epochRepository.findWorkspaceFiresBetween(anyString(), any(), any(), anyInt()))
                    .thenReturn(List.of(new WorkflowEpochRepository.WorkspaceFireRow(
                            new WorkflowEpochRepository.EpochFireRow(
                                    "run-public-1", "trigger:daily", 1, ran, ran.plusSeconds(2), false, null),
                            WORKFLOW_ID, "Shop app", "APPLICATION", "pub-42")));

            AgendaDto result = agenda(ran.minusSeconds(3600), Instant.now().plusSeconds(60), true);

            assertThat(result.occurrences().get(0).resourceType()).isEqualTo(ResourceType.APPLICATION);
            assertThat(result.occurrences().get(0).publicationId()).isEqualTo("pub-42");
        }

        @Test
        @DisplayName("does not touch the epoch table for a window that is entirely in the future")
        void skipsHistoryForAFutureWindow() {
            givenAutomations(scheduleAutomation("0 9 * * *", "UTC",
                    nextUtcTimeAfter(WINDOW_FROM, 9, 0), true));

            agenda(WINDOW_FROM, WINDOW_TO, true);

            verifyNoInteractions(epochRepository);
        }

        @Test
        @DisplayName("does not query history at all when the caller opts out")
        void respectsIncludePastFalse() {
            givenAutomations();

            agenda(Instant.now().minusSeconds(86400), Instant.now(), false);

            verify(epochRepository, never()).findWorkspaceFiresBetween(any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("reports a truncated history rather than passing a partial day off as complete")
        void reportsPastTruncation() {
            // The scan asks for one row past the cap, so it takes TWO rows on a cap of
            // one to prove there is more. That extra row is a detector, never content.
            ReflectionTestUtils.setField(service, "maxPastFires", 1);
            Instant ran = Instant.now().minusSeconds(3600);
            givenAutomations();
            givenFires(fireRowAtEpoch(4, ran), fireRowAtEpoch(3, ran.minusSeconds(60)));

            AgendaDto result = agenda(ran.minusSeconds(3600), Instant.now().plusSeconds(60), true);

            assertThat(result.pastTruncated()).isTrue();
            // And the probe row is not drawn: one occurrence, not two.
            assertThat(result.occurrences()).hasSize(1);
        }

        @Test
        @DisplayName("a window holding EXACTLY the cap is complete, not truncated")
        void exactlyTheCapIsNotTruncated() {
            // `size() >= cap` called this truncated, and now that a truncated window also
            // names where coverage starts, that reads as "history is complete from 09:12,
            // some earlier runs are not shown" about a window that is whole. The agent
            // source answers this the same way; the two must not disagree.
            ReflectionTestUtils.setField(service, "maxPastFires", 2);
            Instant ran = Instant.now().minusSeconds(3600);
            givenAutomations();
            givenFires(fireRowAtEpoch(4, ran), fireRowAtEpoch(3, ran.minusSeconds(60)));

            AgendaDto result = agenda(ran.minusSeconds(3600), Instant.now().plusSeconds(60), true);

            assertThat(result.pastTruncated()).isFalse();
            assertThat(result.pastCoveredFrom()).isNull();
            assertThat(result.occurrences()).hasSize(2);
        }
    }

    @Test
    @DisplayName("returns occurrences in chronological order across both sources")
    void sortsChronologically() {
        Instant ran = Instant.now().minusSeconds(3600);
        Instant nine = nextUtcTimeAfter(Instant.now(), 9, 0);
        givenAutomations(scheduleAutomation("0 9 * * *", "UTC", nine, true));
        when(epochRepository.findWorkspaceFiresBetween(anyString(), any(), any(), anyInt()))
                .thenReturn(List.of(new WorkflowEpochRepository.WorkspaceFireRow(
                        new WorkflowEpochRepository.EpochFireRow(
                                "run-public-1", "trigger:daily", 4, ran, ran.plusSeconds(5), false, null),
                        WORKFLOW_ID, "Daily report", "STANDARD", null)));

        AgendaDto result = agenda(ran.minusSeconds(3600), nine.plusSeconds(3600), true);

        assertThat(result.occurrences()).extracting(Occurrence::startAt).isSorted();
        assertThat(result.occurrences().get(0).kind()).isEqualTo(OccurrenceKind.PAST);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static List<Occurrence> plannedOf(AgendaDto agenda) {
        return agenda.occurrences().stream()
                .filter(o -> o.kind() == OccurrenceKind.PLANNED)
                .toList();
    }

    /** The first UTC HH:mm strictly after {@code after}. */
    private static Instant nextUtcTimeAfter(Instant after, int hour, int minute) {
        return com.apimarketplace.common.schedule.CronOccurrences
                .between(minute + " " + hour + " * * *", "UTC", after.plusSeconds(1), null, 1)
                .occurrences().get(0);
    }
}
