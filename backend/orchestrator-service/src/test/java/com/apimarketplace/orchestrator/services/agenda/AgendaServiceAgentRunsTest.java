package com.apimarketplace.orchestrator.services.agenda;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentRunFireDto;
import com.apimarketplace.agent.client.dto.AgentRunWindowDto;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto.ResourceType;
import com.apimarketplace.orchestrator.controllers.dto.AgendaDto;
import com.apimarketplace.orchestrator.controllers.dto.AgendaDto.LaunchSource;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Agent runs on the calendar.
 *
 * <p>Agents used to be invisible in the agenda's history: they produce no epoch, and the
 * epoch table was the only source of the past. These tests pin the second source and the
 * two translations it needs - {@code agent_executions.source} into a launch kind, and
 * {@code agent_executions.status} into the calendar's outcome vocabulary.
 *
 * <p>The cases that matter most are the ones about NOT knowing: an unrecognised source
 * must produce no launch kind rather than the nearest one, and an unrecognised status
 * must not be drawn as a failure.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgendaService - agent runs")
class AgendaServiceAgentRunsTest {

    @Mock private ActiveAutomationsService activeAutomationsService;
    @Mock private WorkflowEpochRepository epochRepository;
    @Mock private AgentClient agentClient;

    private AgendaService service;

    private static final String TENANT = "user-1";
    private static final String ORG = "org-1";
    private static final UUID AGENT_ID = UUID.randomUUID();

    /** A window that STARTED in the past, so the history branch is reached. */
    private static final Instant WINDOW_FROM =
            Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
    private static final Instant WINDOW_TO = WINDOW_FROM.plus(7, ChronoUnit.DAYS);
    private static final Instant RAN_AT = WINDOW_FROM.plus(3, ChronoUnit.HOURS);

    @BeforeEach
    void setUp() {
        service = new AgendaService(activeAutomationsService, epochRepository, agentClient, new ObjectMapper());
        ReflectionTestUtils.setField(service, "maxOccurrencesPerSchedule", 200);
        ReflectionTestUtils.setField(service, "maxPastFires", 2000);
        ReflectionTestUtils.setField(service, "maxPastAgentRuns", 1000);
        ReflectionTestUtils.setField(service, "maxWindowDays", 120);
        when(activeAutomationsService.getAgendaAutomations(anyString(), anyString(), any()))
                .thenReturn(List.of());
        when(epochRepository.findWorkspaceFiresBetween(anyString(), any(), any(), anyInt()))
                .thenReturn(List.of());
    }

    private static AgentRunFireDto runAt(Instant startedAt) {
        return new AgentRunFireDto(UUID.randomUUID(), AGENT_ID, "Support agent",
                startedAt, startedAt.plusSeconds(3), "COMPLETED", "CHAT", "conv-9");
    }

    private static AgentRunFireDto run(String source, String status) {
        return new AgentRunFireDto(UUID.randomUUID(), AGENT_ID, "Support agent",
                RAN_AT, RAN_AT.plusSeconds(12), status, source, "conv-9");
    }

    private void givenRuns(AgentRunFireDto... runs) {
        givenWindow(AgentRunWindowDto.of(List.of(runs), false, null));
    }

    private void givenWindow(AgentRunWindowDto window) {
        when(agentClient.getWorkspaceAgentRuns(eq(TENANT), eq(ORG), any(), any(), any(), anyInt()))
                .thenReturn(window);
    }

    private List<Occurrence> agendaOccurrences() {
        return service.getAgenda(TENANT, ORG, "OWNER", WINDOW_FROM, WINDOW_TO, true).occurrences();
    }

    @Nested
    @DisplayName("what an agent run becomes on the calendar")
    class Shape {

        @Test
        @DisplayName("an agent run is a PAST occurrence on its agent, with the conversation it happened in")
        void agentRunBecomesPastOccurrence() {
            givenRuns(run("CHAT", "COMPLETED"));

            List<Occurrence> occurrences = agendaOccurrences();

            assertThat(occurrences).hasSize(1);
            Occurrence occurrence = occurrences.get(0);
            assertThat(occurrence.kind()).isEqualTo(OccurrenceKind.PAST);
            assertThat(occurrence.resourceType()).isEqualTo(ResourceType.AGENT);
            assertThat(occurrence.resourceId()).isEqualTo(AGENT_ID);
            assertThat(occurrence.name()).isEqualTo("Support agent");
            assertThat(occurrence.startAt()).isEqualTo(RAN_AT);
            assertThat(occurrence.endAt()).isEqualTo(RAN_AT.plusSeconds(12));
            assertThat(occurrence.conversationId()).isEqualTo("conv-9");
            assertThat(occurrence.launchSource()).isEqualTo(LaunchSource.CHAT);
        }

        @Test
        @DisplayName("it carries no workflow trigger identity: an agent declares no trigger nodes")
        void carriesNoWorkflowTriggerIdentity() {
            givenRuns(run("SCHEDULE", "COMPLETED"));

            Occurrence occurrence = agendaOccurrences().get(0);

            // triggerType is the workflow node vocabulary. Filling it here would put an
            // agent run under a workflow trigger kind that never declared it, and the
            // page reads launchSource FIRST precisely because the two must not overlap.
            assertThat(occurrence.triggerType()).isNull();
            assertThat(occurrence.triggerId()).isNull();
            assertThat(occurrence.scheduleId()).isNull();
            assertThat(occurrence.runIdPublic()).isNull();
            assertThat(occurrence.epoch()).isNull();
            assertThat(occurrence.launchSource()).isEqualTo(LaunchSource.SCHEDULE);
        }

        @Test
        @DisplayName("its id is unique per execution, so two runs of one agent do not collide")
        void idIsPerExecution() {
            givenRuns(run("CHAT", "COMPLETED"), run("CHAT", "COMPLETED"));

            List<Occurrence> occurrences = agendaOccurrences();

            assertThat(occurrences).hasSize(2);
            assertThat(occurrences.get(0).id()).isNotEqualTo(occurrences.get(1).id());
        }

        @Test
        @DisplayName("an agent run is never treated as actionable: it has already happened")
        void neverActionable() {
            givenRuns(run("WEBHOOK", "COMPLETED"));

            Occurrence occurrence = agendaOccurrences().get(0);

            assertThat(occurrence.armed()).isTrue();
            assertThat(occurrence.isNextFire()).isFalse();
            assertThat(occurrence.moveAllSupported()).isFalse();
            assertThat(occurrence.overridden()).isFalse();
        }
    }

    @Nested
    @DisplayName("how it was launched")
    class Launch {

        @Test
        @DisplayName("each source this map knows resolves to its own kind")
        void mapsEveryKnownSource() {
            givenRuns(run("CHAT", "COMPLETED"), run("SCHEDULE", "COMPLETED"),
                    run("WEBHOOK", "COMPLETED"), run("WORKFLOW", "COMPLETED"),
                    run("SUB_AGENT", "COMPLETED"), run("WIDGET", "COMPLETED"));

            assertThat(agendaOccurrences()).extracting(Occurrence::launchSource)
                    .containsExactlyInAnyOrder(LaunchSource.CHAT, LaunchSource.SCHEDULE,
                            LaunchSource.WEBHOOK, LaunchSource.WORKFLOW,
                            LaunchSource.SUB_AGENT, LaunchSource.WIDGET);
        }

        @Test
        @DisplayName("a task and its review are one kind: both are the task system running the agent")
        void taskAndItsReviewShareOneKind() {
            givenRuns(run("TASK", "COMPLETED"), run("TASK_REVIEW", "COMPLETED"));

            assertThat(agendaOccurrences()).extracting(Occurrence::launchSource)
                    .containsExactly(LaunchSource.TASK, LaunchSource.TASK);
        }

        @Test
        @DisplayName("an unrecognised source leaves the launch kind EMPTY rather than guessing one")
        void unknownSourceIsNotGuessed() {
            givenRuns(run("SOME_FUTURE_SOURCE", "COMPLETED"), run(null, "COMPLETED"));

            // The alternative - defaulting to CHAT, or to the resource kind - would state
            // on screen that a run started in a way it did not. The page draws the agent
            // icon instead, which says what ran without claiming to know what started it.
            assertThat(agendaOccurrences()).extracting(Occurrence::launchSource)
                    .containsExactly(null, null);
        }

        @Test
        @DisplayName("the source is read case-insensitively, since six services write that column")
        void sourceIsCaseInsensitive() {
            givenRuns(run("sub_agent", "COMPLETED"));

            assertThat(agendaOccurrences().get(0).launchSource()).isEqualTo(LaunchSource.SUB_AGENT);
        }
    }

    @Nested
    @DisplayName("how it ended")
    class Outcome {

        @Test
        @DisplayName("the three terminal outcomes and RUNNING keep their own word")
        void mapsKnownStatuses() {
            givenRuns(run("CHAT", "COMPLETED"), run("CHAT", "FAILED"),
                    run("CHAT", "CANCELLED"), run("CHAT", "RUNNING"));

            assertThat(agendaOccurrences()).extracting(Occurrence::status)
                    .containsExactlyInAnyOrder("COMPLETED", "FAILED", "CANCELLED", "RUNNING");
        }

        @Test
        @DisplayName("an unrecognised status is FIRED, never FAILED: it must not paint a working run red")
        void unknownStatusIsNotAFailure() {
            givenRuns(run("CHAT", "SOMETHING_NEW"), run("CHAT", null));

            assertThat(agendaOccurrences()).extracting(Occurrence::status)
                    .containsExactly("FIRED", "FIRED");
        }
    }

    @Nested
    @DisplayName("when the agent source is asked at all")
    class WhenAsked {

        @Test
        @DisplayName("a window entirely in the future never asks: no run can have happened there")
        void futureWindowDoesNotAsk() {
            Instant from = Instant.now().plus(1, ChronoUnit.DAYS);
            service.getAgenda(TENANT, ORG, "OWNER", from, from.plus(7, ChronoUnit.DAYS), true);

            verifyNoInteractions(agentClient);
        }

        @Test
        @DisplayName("includePast=false never asks, matching the epoch scan it sits beside")
        void includePastFalseDoesNotAsk() {
            service.getAgenda(TENANT, ORG, "OWNER", WINDOW_FROM, WINDOW_TO, false);

            verifyNoInteractions(agentClient);
        }

        @Test
        @DisplayName("a workspace-less caller never asks: the query is org-scoped")
        void noOrgDoesNotAsk() {
            service.getAgenda(TENANT, null, "OWNER", WINDOW_FROM, WINDOW_TO, true);

            verifyNoInteractions(agentClient);
        }

        @Test
        @DisplayName("the window ends at NOW, never at the end of a window that runs into the future")
        void windowIsClippedToNow() {
            givenRuns();
            Instant before = Instant.now();

            service.getAgenda(TENANT, ORG, "OWNER", WINDOW_FROM, WINDOW_TO, true);

            // WINDOW_TO is five days ahead. Asking for it would be asking the past to
            // contain the future - harmless but wasteful, and it would make a cap count
            // rows that cannot exist.
            verify(agentClient).getWorkspaceAgentRuns(eq(TENANT), eq(ORG), eq("OWNER"),
                    eq(WINDOW_FROM),
                    org.mockito.ArgumentMatchers.argThat(to ->
                            !to.isBefore(before) && !to.isAfter(Instant.now())),
                    eq(1000));
        }
    }

    @Nested
    @DisplayName("honesty about what is missing")
    class Truncation {

        @Test
        @DisplayName("the SERVER decides truncation; the page never re-derives it from the row count")
        void truncationComesFromTheServer() {
            // The endpoint filters its rows through the per-member deny-list AFTER capping
            // them, so a short list does not mean a complete window. Counting rows here
            // would tell a restricted member their history was complete and draw the
            // uncovered days as quiet ones.
            Instant boundary = RAN_AT.minusSeconds(900);
            givenWindow(AgentRunWindowDto.of(List.of(run("CHAT", "COMPLETED")), true, boundary));

            AgendaDto agenda = service.getAgenda(TENANT, ORG, "OWNER", WINDOW_FROM, WINDOW_TO, true);

            assertThat(agenda.pastTruncated())
                    .as("an incomplete history must not read as a quiet week")
                    .isTrue();
            assertThat(agenda.pastCoveredFrom()).isEqualTo(boundary);
        }

        @Test
        @DisplayName("an UNAVAILABLE agent read is reported as an incomplete history, not a quiet one")
        void unavailableIsNotQuiet() {
            // agent-service down, or slower than the 3 s budget. The page cannot show the
            // runs, but it must not state that there were none: the banner says the
            // history is partial, with no boundary to name because nothing was read.
            givenWindow(AgentRunWindowDto.unavailable());

            AgendaDto agenda = service.getAgenda(TENANT, ORG, "OWNER", WINDOW_FROM, WINDOW_TO, true);

            assertThat(agenda.pastTruncated()).isTrue();
            assertThat(agenda.agentHistoryUnavailable()).isTrue();
            assertThat(agenda.pastCoveredFrom()).isNull();
            assertThat(agenda.occurrences()).isEmpty();
        }

        @Test
        @DisplayName("a configured cap above the wire ceiling is clamped, and a full page still truncates")
        void capAboveTheWireCeilingStillTruncates() {
            // The endpoint clamps at AgentClient.WORKSPACE_RUNS_MAX_LIMIT. Measuring a full
            // page against a LARGER configured number would report the window complete
            // while the server had silently cut it - the exact wrong answer this page
            // exists to avoid. Both sides therefore read one constant.
            ReflectionTestUtils.setField(service, "maxPastAgentRuns",
                    AgentClient.WORKSPACE_RUNS_MAX_LIMIT + 3000);
            givenWindow(AgentRunWindowDto.of(List.of(run("CHAT", "COMPLETED")), true, RAN_AT));

            AgendaDto agenda = service.getAgenda(TENANT, ORG, "OWNER", WINDOW_FROM, WINDOW_TO, true);

            verify(agentClient).getWorkspaceAgentRuns(anyString(), anyString(), any(), any(), any(),
                    eq(AgentClient.WORKSPACE_RUNS_MAX_LIMIT));
            assertThat(agenda.pastTruncated()).isTrue();
        }

        @Test
        @DisplayName("a partial page does not")
        void partialPageDoesNotTruncate() {
            ReflectionTestUtils.setField(service, "maxPastAgentRuns", 5);
            givenRuns(run("CHAT", "COMPLETED"));

            AgendaDto agenda = service.getAgenda(TENANT, ORG, "OWNER", WINDOW_FROM, WINDOW_TO, true);
            assertThat(agenda.pastTruncated()).isFalse();
            assertThat(agenda.pastCoveredFrom()).isNull();
        }

        @Test
        @DisplayName("a cap of zero switches the source off WITHOUT claiming a partial history")
        void zeroCapIsNotTruncation() {
            // The client short-circuits a limit below 1 and answers an AVAILABLE, complete,
            // empty window - deliberately not `unavailable()`, which would warn forever
            // about a gap the operator created on purpose.
            givenWindow(AgentRunWindowDto.of(List.of(), false, null));
            // The one operator-facing way to turn this source off. A naive
            // `size() >= limit` reads 0 >= 0 and makes the page say, forever, that its
            // history is incomplete while showing nothing - a warning about a gap that
            // the operator created on purpose, and that no action can clear.
            ReflectionTestUtils.setField(service, "maxPastAgentRuns", 0);
            givenRuns();

            AgendaDto agenda = service.getAgenda(TENANT, ORG, "OWNER", WINDOW_FROM, WINDOW_TO, true);

            assertThat(agenda.pastTruncated()).isFalse();
            assertThat(agenda.pastCoveredFrom()).isNull();
        }

        @Test
        @DisplayName("names the instant coverage starts at, so an empty first week is not read as quiet")
        void reportsTheCoverageBoundary() {
            // "Keep the newest" points at the days being read only while the window ends
            // at now. On a browsed PAST month the kept rows are its END, so the earlier
            // weeks are drawn empty. Naming the boundary is what stops that reading as
            // "nothing ran then".
            Instant oldest = RAN_AT.minusSeconds(3600);
            givenWindow(AgentRunWindowDto.of(
                    List.of(runAt(RAN_AT), runAt(oldest)), true, oldest));

            AgendaDto agenda = service.getAgenda(TENANT, ORG, "OWNER", WINDOW_FROM, WINDOW_TO, true);

            assertThat(agenda.pastTruncated()).isTrue();
            assertThat(agenda.pastCoveredFrom()).isEqualTo(oldest);
        }

        @Test
        @DisplayName("an UNREADABLE agent half cancels the epoch boundary: no completeness may be claimed")
        void unavailableAgentsCancelTheBoundary() {
            // The combination neither of the other two cases covers, and the one that
            // makes the page lie: agent-service down WHILE the epoch scan truncated.
            // `laterOrNull` reads a null boundary as "this source was complete", and an
            // unread window's null means the opposite - so merging them published the
            // EPOCH boundary as the whole page's, and the banner said "history is
            // complete from 18:32" with every agent run in the month missing.
            ReflectionTestUtils.setField(service, "maxPastFires", 1);
            when(epochRepository.findWorkspaceFiresBetween(anyString(), any(), any(), anyInt()))
                    .thenReturn(List.of(fireRowAt(RAN_AT.minusSeconds(7200)),
                            fireRowAt(RAN_AT.minusSeconds(9000))));
            givenWindow(AgentRunWindowDto.unavailable());

            AgendaDto agenda = service.getAgenda(TENANT, ORG, "OWNER", WINDOW_FROM, WINDOW_TO, true);

            assertThat(agenda.pastTruncated()).isTrue();
            assertThat(agenda.agentHistoryUnavailable()).isTrue();
            assertThat(agenda.pastCoveredFrom())
                    .as("nothing may be called covered while half the history was never read")
                    .isNull();
        }

        @Test
        @DisplayName("a truncated scan that cannot name its boundary blocks the claim for BOTH")
        void aBoundarylessTruncationBlocksTheClaim() {
            // `laterOrNull` reads a null as "this source was complete", and a scan that
            // was cut short without being able to say from where has a null meaning the
            // opposite. Publishing the other source's boundary would claim completeness
            // from an instant nothing reached. Unreachable through today's queries - so
            // was the agent-unavailable case, right up until it was not.
            givenWindow(new AgentRunWindowDto(List.of(run("CHAT", "COMPLETED")), true, null, true));
            ReflectionTestUtils.setField(service, "maxPastFires", 1);
            when(epochRepository.findWorkspaceFiresBetween(anyString(), any(), any(), anyInt()))
                    .thenReturn(List.of(fireRowAt(RAN_AT.minusSeconds(3600)),
                            fireRowAt(RAN_AT.minusSeconds(5400))));

            AgendaDto agenda = service.getAgenda(TENANT, ORG, "OWNER", WINDOW_FROM, WINDOW_TO, true);

            assertThat(agenda.pastTruncated()).isTrue();
            assertThat(agenda.pastCoveredFrom()).isNull();
        }

        @Test
        @DisplayName("a readable agent half leaves agentHistoryUnavailable false, truncated or not")
        void readableAgentsAreNotUnavailable() {
            givenWindow(AgentRunWindowDto.of(List.of(run("CHAT", "COMPLETED")), true, RAN_AT));

            AgendaDto agenda = service.getAgenda(TENANT, ORG, "OWNER", WINDOW_FROM, WINDOW_TO, true);

            // Otherwise the page would swap a truthful "runs are shown from X" for
            // "agent runs could not be loaded" on every capped window.
            assertThat(agenda.agentHistoryUnavailable()).isFalse();
            assertThat(agenda.pastCoveredFrom()).isEqualTo(RAN_AT);
        }

        @Test
        @DisplayName("takes the LATER boundary when both sources truncated: a day is complete only once both reach it")
        void boundaryIsTheLaterOfTheTwoSources() {
            ReflectionTestUtils.setField(service, "maxPastFires", 1);
            Instant agentOldest = RAN_AT;
            Instant epochOldest = RAN_AT.minusSeconds(7200);
            givenWindow(AgentRunWindowDto.of(List.of(runAt(agentOldest)), true, agentOldest));
            when(epochRepository.findWorkspaceFiresBetween(anyString(), any(), any(), anyInt()))
                    .thenReturn(List.of(fireRowAt(epochOldest),
                            fireRowAt(epochOldest.minusSeconds(60))));

            AgendaDto agenda = service.getAgenda(TENANT, ORG, "OWNER", WINDOW_FROM, WINDOW_TO, true);

            // Reporting the epoch scan's older boundary would promise that the hours
            // between them are complete, and the agent scan does not cover them.
            assertThat(agenda.pastCoveredFrom()).isEqualTo(agentOldest);
        }

        @Test
        @DisplayName("reports the EPOCH scan's boundary when only that source truncated")
        void epochOnlyBoundary() {
            // appendPastFires changed from returning a boolean to returning an instant in
            // the same pass. Its own boundary had no test, so an off-by-one there (the
            // NEWEST row instead of the oldest) would have been invisible: the flag would
            // still be true and the date would still look plausible.
            // Three rows for a cap of two: the epoch scan asks for one past the page
            // and the extra row is what proves there is more, exactly as the agent
            // window does. Two rows on a cap of two is a COMPLETE window.
            ReflectionTestUtils.setField(service, "maxPastFires", 2);
            Instant newer = RAN_AT;
            Instant oldest = RAN_AT.minusSeconds(1800);
            when(epochRepository.findWorkspaceFiresBetween(anyString(), any(), any(), anyInt()))
                    .thenReturn(List.of(fireRowAt(newer), fireRowAt(oldest),
                            fireRowAt(oldest.minusSeconds(60))));
            givenRuns();

            AgendaDto agenda = service.getAgenda(TENANT, ORG, "OWNER", WINDOW_FROM, WINDOW_TO, true);

            assertThat(agenda.pastTruncated()).isTrue();
            assertThat(agenda.pastCoveredFrom()).isEqualTo(oldest);
        }

        private WorkflowEpochRepository.WorkspaceFireRow fireRowAt(Instant at) {
            return new WorkflowEpochRepository.WorkspaceFireRow(
                    new WorkflowEpochRepository.EpochFireRow(
                            "run_1", "trigger:daily", 1, at, at.plusSeconds(5), false, null),
                    UUID.randomUUID(), "Daily digest", "WORKFLOW", "[]", null);
        }
    }

    @Nested
    @DisplayName("what it refuses to draw")
    class Skipped {

        @Test
        @DisplayName("a row with no agent or no start time is dropped rather than drawn anonymously")
        void dropsUnusableRows() {
            givenRuns(
                    new AgentRunFireDto(UUID.randomUUID(), null, null, RAN_AT, null, "COMPLETED", "CHAT", null),
                    new AgentRunFireDto(UUID.randomUUID(), AGENT_ID, "A", null, null, "COMPLETED", "CHAT", null),
                    run("CHAT", "COMPLETED"));

            // A chip with no resource cannot be named, filtered or opened: it would be a
            // dot on a calendar that leads nowhere.
            assertThat(agendaOccurrences()).hasSize(1);
        }

        @Test
        @DisplayName("agent-service being unavailable costs the agent rows and nothing else")
        void failsOpen() {
            // The client swallows its own failures into an empty list; what this pins is
            // that the agenda keeps rendering the rest of the month around that.
            givenWindow(AgentRunWindowDto.of(List.of(), false, null));

            AgendaDto agenda = service.getAgenda(TENANT, ORG, "OWNER", WINDOW_FROM, WINDOW_TO, true);

            assertThat(agenda.occurrences()).isEmpty();
            assertThat(agenda.pastTruncated()).isFalse();
            verify(epochRepository).findWorkspaceFiresBetween(anyString(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("the agent source is still read when the EPOCH scan has already truncated")
        void epochTruncationDoesNotSkipAgents() {
            // Two rows on a cap of one: the second is the probe that makes the epoch
            // scan report truncation.
            ReflectionTestUtils.setField(service, "maxPastFires", 1);
            when(epochRepository.findWorkspaceFiresBetween(anyString(), any(), any(), anyInt()))
                    .thenReturn(List.of(fireRow(), fireRow()));
            givenRuns(run("CHAT", "COMPLETED"));

            AgendaDto agenda = service.getAgenda(TENANT, ORG, "OWNER", WINDOW_FROM, WINDOW_TO, true);

            // A short-circuiting `||` here would silently drop every agent run as soon as
            // a busy workspace filled the epoch cap - the exact windows where the user
            // most needs to see what ran.
            verify(agentClient).getWorkspaceAgentRuns(anyString(), anyString(), any(), any(), any(), anyInt());
            assertThat(agenda.occurrences()).extracting(Occurrence::resourceType)
                    .contains(ResourceType.AGENT);
            assertThat(agenda.pastTruncated()).isTrue();
        }

        private WorkflowEpochRepository.WorkspaceFireRow fireRow() {
            return new WorkflowEpochRepository.WorkspaceFireRow(
                    new WorkflowEpochRepository.EpochFireRow(
                            "run_1", "trigger:daily", 1, RAN_AT, RAN_AT.plusSeconds(5), false, null),
                    UUID.randomUUID(), "Daily digest", "WORKFLOW", "[]", null);
        }
    }

    @Test
    @DisplayName("agent runs are merged into one chronological list with the workflow fires")
    void mergedChronologically() {
        Instant earlier = RAN_AT.minusSeconds(600);
        when(epochRepository.findWorkspaceFiresBetween(anyString(), any(), any(), anyInt()))
                .thenReturn(List.of(new WorkflowEpochRepository.WorkspaceFireRow(
                        new WorkflowEpochRepository.EpochFireRow(
                                "run_1", "trigger:daily", 1, earlier, earlier.plusSeconds(5), false, null),
                        UUID.randomUUID(), "Daily digest", "WORKFLOW", "[]", null)));
        givenRuns(run("CHAT", "COMPLETED"));

        assertThat(agendaOccurrences()).extracting(Occurrence::resourceType)
                .containsExactly(ResourceType.WORKFLOW, ResourceType.AGENT);
    }

    @Test
    @DisplayName("nothing about the workflow path changes: a fire still carries its trigger kind")
    void workflowPathUnchanged() {
        when(epochRepository.findWorkspaceFiresBetween(anyString(), any(), any(), anyInt()))
                .thenReturn(List.of(new WorkflowEpochRepository.WorkspaceFireRow(
                        new WorkflowEpochRepository.EpochFireRow(
                                "run_1", "trigger:daily", 1, RAN_AT, RAN_AT.plusSeconds(5), false, null),
                        UUID.randomUUID(), "Daily digest", "WORKFLOW",
                        "[{\"id\":\"daily\",\"label\":\"Daily\",\"type\":\"schedule\"}]", null)));
        givenRuns();

        Occurrence occurrence = agendaOccurrences().get(0);

        assertThat(occurrence.launchSource()).isNull();
        assertThat(occurrence.conversationId()).isNull();
        assertThat(occurrence.triggerId()).isEqualTo("trigger:daily");
        verify(agentClient, never())
                .getWorkspaceAgentRuns(anyString(), anyString(), any(), any(), any(), eq(0));
    }
}
