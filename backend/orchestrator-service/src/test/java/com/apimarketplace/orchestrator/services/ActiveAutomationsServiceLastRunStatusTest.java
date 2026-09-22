package com.apimarketplace.orchestrator.services;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService.LatestEpochOutcome;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for the "Last: {verdict} {time}" line the notification bell's Triggers tab draws
 * for every automation.
 *
 * <p>The failure mode this guards against is a CONFIDENT WRONG ANSWER, and it has two shapes.
 * One is a verdict that describes the wrong KIND of ending - claiming a fire completed while it
 * is still executing, or handing over a run status for an epoch the run panel leaves blank one
 * click away. The other is a verdict that describes the wrong FIRE - the timestamp on the line
 * coming from one execution and the badge beside it from another. Every case below pins either
 * a specific verdict or an explicit null, never "some status came back".
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActiveAutomationsService - last-run outcome badge")
class ActiveAutomationsServiceLastRunStatusTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private TriggerClient triggerClient;
    @Mock private AgentClient agentClient;
    @Mock private WorkflowEpochService epochService;

    private ActiveAutomationsService service;

    private static final String TENANT_ID = "tenant-77";
    private static final String ORG_ID = "org-1234-abcd";
    private static final String ORG_ROLE = "MEMBER";
    private static final UUID WORKFLOW_ID = UUID.fromString("f9e93b0f-2316-4e83-9b5c-521e627555a3");
    private static final UUID SCHEDULE_ID = UUID.fromString("56bcbd77-db50-4df5-b402-dfbb74f530d8");
    private static final UUID WEEKLY_SCHEDULE_ID = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final UUID PRODUCTION_RUN_ID = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final String RUN_ID_PUBLIC = "run_<id>";
    private static final Instant EPOCH_FIRED_AT = Instant.parse("2026-09-06T10:00:00Z");
    private static final String NIGHTLY_TRIGGER_ID = "trigger:nightly";
    private static final String WEEKLY_TRIGGER_ID = "trigger:weekly";
    private static final Instant DRAFT_RAN_AT = Instant.parse("2026-09-06T18:00:00Z");

    @BeforeEach
    void setUp() {
        service = new ActiveAutomationsService(workflowRepository, runRepository,
                triggerClient, agentClient, epochService);
    }

    // ── resolveLastRunStatus: which ending, and whose ────────────────────────────

    @Test
    @DisplayName("A closed epoch's own outcome WINS over the run status - the run may already be firing again")
    void closedEpochOutcomeOutranksRunStatus() {
        // The reusable-trigger shape: the run is parked waiting for the next fire while the
        // epoch that just ended holds the verdict. Reading the run here would badge
        // WAITING_TRIGGER, which is not an outcome at all.
        String status = ActiveAutomationsService.resolveLastRunStatus(
                epoch("FAILED", /* active */ false), RunStatus.WAITING_TRIGGER);

        assertThat(status).isEqualTo("FAILED");
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"PENDING", "RUNNING", "PAUSED", "AWAITING_SIGNAL"})
    @DisplayName("An OPEN epoch under an executing run reads RUNNING, whatever the run's exact phase")
    void openEpochUnderExecutingRunIsRunning(RunStatus executing) {
        // The epoch carries no outcome (its stored state is the one written when it opened),
        // so only the run can say it is still going. AWAITING_SIGNAL - blocked on an approval -
        // is executing as much as RUNNING is: the fire has not ended. All four members of the
        // set are covered, so dropping one from it fails here.
        assertThat(ActiveAutomationsService.resolveLastRunStatus(epoch(null, true), executing))
                .isEqualTo("RUNNING");
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"CANCELLED", "TIMEOUT"})
    @DisplayName("An OPEN epoch under a run KILLED mid-flight reports the kill, not RUNNING")
    void openEpochUnderKilledRunReportsTheKill(RunStatus killed) {
        // Cancel / timeout leave the epoch unclosed for good. Calling that RUNNING would put a
        // live pulse on a row that will never move again.
        assertThat(ActiveAutomationsService.resolveLastRunStatus(epoch(null, true), killed))
                .isEqualTo(killed.name());
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"COMPLETED", "FAILED", "PARTIAL_SUCCESS", "SKIPPED"})
    @DisplayName("An OPEN epoch under a run that ENDED normally stays silent - the run panel shows nothing there either")
    void openEpochUnderFinishedRunAnswersNull(RunStatus finished) {
        // These are terminal too, but they are not a kill: the epoch is open because its close
        // was DEFERRED. resolveEpochBadgeStatus (runFormatting.ts) falls through to the epoch's
        // own absent outcome and badges nothing. Handing the run's verdict over here would draw
        // a green check in the bell on an epoch the run panel leaves blank - and PARTIAL_SUCCESS
        // and SKIPPED are not even values EpochStatusIcon can name.
        assertThat(ActiveAutomationsService.resolveLastRunStatus(epoch(null, true), finished)).isNull();
    }

    @Test
    @DisplayName("An OPEN epoch on a run parked at WAITING_TRIGGER, or on no run at all, answers NULL")
    void openEpochWithoutAnExecutingRunAnswersNull() {
        assertThat(ActiveAutomationsService.resolveLastRunStatus(epoch(null, true), RunStatus.WAITING_TRIGGER))
                .isNull();
        // A pinned workflow whose production run could not be resolved: nothing to ask.
        assertThat(ActiveAutomationsService.resolveLastRunStatus(epoch(null, true), null)).isNull();
    }

    @Test
    @DisplayName("A CLOSED epoch with no outcome answers NULL - it ran nothing but its trigger")
    void closedEpochWithoutOutcomeAnswersNull() {
        // The backend attaches no outcome to an epoch that executed nothing but its trigger.
        // The run status must NOT fill that gap: an armed fire is not a completed one.
        assertThat(ActiveAutomationsService.resolveLastRunStatus(epoch(null, false), RunStatus.COMPLETED))
                .isNull();
    }

    @Test
    @DisplayName("No epoch at all answers NULL - the row never fired")
    void noEpochAnswersNull() {
        assertThat(ActiveAutomationsService.resolveLastRunStatus(null, RunStatus.COMPLETED)).isNull();
        assertThat(ActiveAutomationsService.resolveLastRunStatus(null, null)).isNull();
    }

    // ── The line as a whole: one event, or no verdict ────────────────────────────

    @Test
    @DisplayName("A declared-kind row takes BOTH halves of the line from the production run's last epoch")
    void declaredKindRowTakesTimeAndVerdictFromTheSameEpoch() {
        // The workflow was also run from the builder 8 hours later, which stamps lastExecutedAt.
        // Printing that draft's timestamp next to the production run's verdict would say
        // "Last: failed, 2 minutes ago" about an execution that is neither.
        WorkflowEntity wf = pinnedWorkflow();
        wf.setLastExecutedAt(DRAFT_RAN_AT);
        stubOrgQueries(List.of(wf));
        givenProductionRun(wf, RunStatus.WAITING_TRIGGER, epoch("FAILED", false));

        List<ActiveAutomationDto> result = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        assertThat(result).singleElement()
                .extracting(ActiveAutomationDto::triggerType, ActiveAutomationDto::lastRunStatus,
                        ActiveAutomationDto::lastRunAt)
                .containsExactly(ActiveAutomationDto.TriggerType.MANUAL, "FAILED", EPOCH_FIRED_AT);
    }

    @Test
    @DisplayName("Agenda exposes every exact trigger from the immutable production plan")
    void agendaUsesExactProductionTriggersInsteadOfTheDraft() {
        WorkflowEntity wf = pinnedWorkflow();
        wf.setProductionRunId(PRODUCTION_RUN_ID);
        wf.setPlan(planWith(Map.of("draft_only", "manual")));
        wf.setNodeIcons(List.of(Map.of("nodeKind", "entry", "nodeId", "manual-trigger")));
        stubOrgQueries(List.of(wf));

        WorkflowRunEntity run = productionRun(wf, RunStatus.WAITING_TRIGGER);
        ReflectionTestUtils.setField(run, "id", PRODUCTION_RUN_ID);
        run.setPlanVersion(wf.getPinnedVersion());
        run.setPlan(planWith(Map.of("first_manual", "manual", "second_manual", "manual")));
        when(runRepository.findProductionRunsBatch(eq(List.of(WORKFLOW_ID)))).thenReturn(List.of(run));
        when(epochService.getLatestEpochOutcomeByRunIds(anyList())).thenReturn(Map.of());

        List<ActiveAutomationDto> result = service.getAgendaAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        assertThat(result).hasSize(2)
                .extracting(ActiveAutomationDto::triggerId)
                .containsExactlyInAnyOrder("trigger:first_manual", "trigger:second_manual");
        assertThat(result).extracting(ActiveAutomationDto::triggerLabel)
                .containsExactlyInAnyOrder("first_manual", "second_manual");
    }

    @Test
    @DisplayName("Agenda refuses a stale production FK instead of cataloguing triggers from a scanned editor run")
    void agendaRefusesTriggersWhenTheProductionIdentityIsNotVerifiable() {
        WorkflowEntity wf = pinnedWorkflow();
        wf.setProductionRunId(PRODUCTION_RUN_ID);
        stubOrgQueries(List.of(wf));

        WorkflowRunEntity staleFk = productionRun(wf, RunStatus.WAITING_TRIGGER);
        ReflectionTestUtils.setField(staleFk, "id", PRODUCTION_RUN_ID);
        staleFk.setPlanVersion(wf.getPinnedVersion() - 1);
        staleFk.setPlan(planWith(Map.of("stale_manual", "manual")));
        WorkflowRunEntity scannedEditor = productionRun(wf, RunStatus.WAITING_TRIGGER);
        scannedEditor.setPlanVersion(wf.getPinnedVersion());
        scannedEditor.setPlan(planWith(Map.of("editor_manual", "manual")));
        when(runRepository.findProductionRunsBatch(eq(List.of(WORKFLOW_ID))))
                .thenReturn(List.of(scannedEditor));
        when(runRepository.findAllById(eq(List.of(PRODUCTION_RUN_ID)))).thenReturn(List.of(staleFk));

        assertThat(service.getAgendaAutomations(TENANT_ID, ORG_ID, ORG_ROLE)).isEmpty();
    }

    @Test
    @DisplayName("Agenda exposes only webhook triggers backed by an active token")
    void agendaFiltersWebhookTriggersByTheirExactActiveToken() {
        WorkflowEntity wf = pinnedWorkflow();
        wf.setProductionRunId(PRODUCTION_RUN_ID);
        stubOrgQueries(List.of(wf));

        WorkflowRunEntity run = productionRun(wf, RunStatus.WAITING_TRIGGER);
        ReflectionTestUtils.setField(run, "id", PRODUCTION_RUN_ID);
        run.setPlanVersion(wf.getPinnedVersion());
        run.setPlan(planWith(Map.of("active_hook", "webhook", "paused_hook", "webhook")));
        when(runRepository.findProductionRunsBatch(eq(List.of(WORKFLOW_ID)))).thenReturn(List.of(run));
        when(triggerClient.findActiveTriggerIdsByWorkflow(eq(List.of(WORKFLOW_ID))))
                .thenReturn(Map.of(WORKFLOW_ID, Set.of("trigger:active_hook")));
        when(epochService.getLatestEpochOutcomeByRunIds(anyList())).thenReturn(Map.of());

        assertThat(service.getAgendaAutomations(TENANT_ID, ORG_ID, ORG_ROLE)).singleElement()
                .extracting(ActiveAutomationDto::triggerId)
                .isEqualTo("trigger:active_hook");
    }

    @Test
    @DisplayName("A cancelled production run exposes the workflow as paused")
    void cancelledProductionRunMarksTheResourcePaused() {
        WorkflowEntity wf = pinnedWorkflow();
        stubOrgQueries(List.of(wf));
        givenProductionRun(wf, RunStatus.CANCELLED, epoch(null, true));

        assertThat(service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE)).singleElement()
                .extracting(ActiveAutomationDto::resourcePaused)
                .isEqualTo(true);
    }

    @Test
    @DisplayName("A SCHEDULE row takes the epoch when the epoch is ITS OWN trigger's fire")
    void scheduleRowTakesTheEpochOfItsOwnTrigger() {
        // Same-trigger case: the epoch and the schedule's lastExecutionAt are the same fire
        // recorded by two writers, so the epoch is simply the reading that comes with a verdict.
        WorkflowEntity wf = pinnedWorkflow();
        wf.setNodeIcons(List.of());   // isolate the schedule row
        ScheduledExecutionDto nightly = schedule(Instant.parse("2026-09-06T04:00:00Z"));
        stubOrgQueries(List.of(wf), List.of(nightly));
        givenProductionRun(wf, RunStatus.WAITING_TRIGGER, epoch("COMPLETED", false, NIGHTLY_TRIGGER_ID));

        List<ActiveAutomationDto> result = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        assertThat(result).singleElement()
                .extracting(ActiveAutomationDto::lastRunStatus, ActiveAutomationDto::lastRunAt)
                .containsExactly("COMPLETED", EPOCH_FIRED_AT);
    }

    @Test
    @DisplayName("A schedule that did NOT fire the last epoch keeps its own time and shows no verdict")
    void aScheduleThatDidNotFireTheLastEpochIsNotBadged() {
        // THE multi-schedule case. Every trigger of a workflow fires into one production run,
        // so its newest epoch belongs to whichever fired last. A weekly schedule that ran three
        // days ago must not print the nightly one's 10:00 fire, nor claim its verdict: that row
        // would be naming a run it never caused. It keeps its own lastExecutionAt, unbadged.
        WorkflowEntity wf = pinnedWorkflow();
        wf.setNodeIcons(List.of());
        Instant weeklyFiredAt = Instant.parse("2026-09-03T06:00:00Z");
        ScheduledExecutionDto nightly = schedule(SCHEDULE_ID, NIGHTLY_TRIGGER_ID,
                Instant.parse("2026-09-06T04:00:00Z"));
        ScheduledExecutionDto weekly = schedule(WEEKLY_SCHEDULE_ID, WEEKLY_TRIGGER_ID, weeklyFiredAt);
        stubOrgQueries(List.of(wf), List.of(nightly, weekly));
        givenProductionRun(wf, RunStatus.WAITING_TRIGGER, epoch("FAILED", false, NIGHTLY_TRIGGER_ID));

        List<ActiveAutomationDto> result = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        assertThat(result).hasSize(2);
        ActiveAutomationDto nightlyRow = rowOfSchedule(result, SCHEDULE_ID);
        ActiveAutomationDto weeklyRow = rowOfSchedule(result, WEEKLY_SCHEDULE_ID);
        assertThat(nightlyRow.lastRunStatus()).isEqualTo("FAILED");
        assertThat(nightlyRow.lastRunAt()).isEqualTo(EPOCH_FIRED_AT);
        assertThat(weeklyRow.lastRunStatus()).isNull();
        assertThat(weeklyRow.lastRunAt()).isEqualTo(weeklyFiredAt);
    }

    @Test
    @DisplayName("An unattributable schedule row falls back to its own time, then to the workflow's")
    void anUnattributableScheduleRowWalksItsFallbackChain() {
        // A schedule row carrying no trigger id (legacy row, or one whose trigger left the plan)
        // can never be matched, so it can never be badged. It must still print the best
        // timestamp it has - its own fire, or the workflow's if it has never fired.
        WorkflowEntity wf = pinnedWorkflow();
        wf.setNodeIcons(List.of());
        wf.setLastExecutedAt(DRAFT_RAN_AT);
        ScheduledExecutionDto ownFire = schedule(SCHEDULE_ID, null, Instant.parse("2026-09-05T04:00:00Z"));
        ScheduledExecutionDto neverFired = schedule(WEEKLY_SCHEDULE_ID, null, null);
        stubOrgQueries(List.of(wf), List.of(ownFire, neverFired));
        givenProductionRun(wf, RunStatus.WAITING_TRIGGER, epoch("COMPLETED", false, NIGHTLY_TRIGGER_ID));

        List<ActiveAutomationDto> result = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        assertThat(rowOfSchedule(result, SCHEDULE_ID))
                .extracting(ActiveAutomationDto::lastRunAt, ActiveAutomationDto::lastRunStatus)
                .containsExactly(Instant.parse("2026-09-05T04:00:00Z"), null);
        assertThat(rowOfSchedule(result, WEEKLY_SCHEDULE_ID))
                .extracting(ActiveAutomationDto::lastRunAt, ActiveAutomationDto::lastRunStatus)
                .containsExactly(DRAFT_RAN_AT, null);
    }

    @Test
    @DisplayName("A WEBHOOK row is badged only when the last epoch was a WEBHOOK fire")
    void aWebhookRowIsNotBadgedForAnotherKindsFire() {
        // One run, several triggers. A workflow whose manual trigger just failed must not put
        // that red cross on its webhook row: that row would be reporting a run it never caused.
        // The epoch names the trigger that opened it, and the plan says what kind that is.
        WorkflowEntity wf = pinnedWorkflow();
        wf.setNodeIcons(List.of());                       // isolate the webhook row
        wf.setPlan(planWith(Map.of("hook", "webhook", "run_it", "manual")));
        wf.setLastExecutedAt(DRAFT_RAN_AT);
        stubOrgQueries(List.of(wf));
        when(triggerClient.findWorkflowIdsWithTokens(anyList())).thenReturn(Set.of(WORKFLOW_ID));
        givenProductionRun(wf, RunStatus.WAITING_TRIGGER, epoch("FAILED", false, "trigger:run_it"));

        List<ActiveAutomationDto> result = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        assertThat(result).singleElement()
                .extracting(ActiveAutomationDto::triggerType, ActiveAutomationDto::lastRunStatus,
                        ActiveAutomationDto::lastRunAt)
                .containsExactly(ActiveAutomationDto.TriggerType.WEBHOOK, null, DRAFT_RAN_AT);
    }

    @Test
    @DisplayName("A declared-kind row is badged only when the last epoch was ITS kind's fire")
    void aDeclaredKindRowIsBadgedOnlyForItsOwnKind() {
        // Two declared kinds on one workflow, one epoch. Exactly one row may claim it.
        WorkflowEntity wf = pinnedWorkflow();
        wf.setNodeIcons(List.of(
                Map.of("nodeKind", "entry", "nodeId", "manual-trigger"),
                Map.of("nodeKind", "entry", "nodeId", "chat-trigger")));
        wf.setPlan(planWith(Map.of("run_it", "manual", "talk", "chat")));
        wf.setLastExecutedAt(DRAFT_RAN_AT);
        stubOrgQueries(List.of(wf));
        givenProductionRun(wf, RunStatus.WAITING_TRIGGER, epoch("COMPLETED", false, "trigger:talk"));

        List<ActiveAutomationDto> result = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        assertThat(rowOfKind(result, ActiveAutomationDto.TriggerType.CHAT))
                .extracting(ActiveAutomationDto::lastRunStatus, ActiveAutomationDto::lastRunAt)
                .containsExactly("COMPLETED", EPOCH_FIRED_AT);
        assertThat(rowOfKind(result, ActiveAutomationDto.TriggerType.MANUAL))
                .extracting(ActiveAutomationDto::lastRunStatus, ActiveAutomationDto::lastRunAt)
                .containsExactly(null, DRAFT_RAN_AT);
    }

    @Test
    @DisplayName("An epoch fired by a trigger the plan no longer declares badges nothing")
    void anUnrecognisedFiringTriggerBadgesNothing() {
        // Renamed or deleted trigger: the key on the epoch matches no plan trigger, so the kind
        // is unknown. Unknown must match no row - a missing badge, never a misplaced one.
        WorkflowEntity wf = pinnedWorkflow();
        wf.setPlan(planWith(Map.of("run_it", "manual")));
        wf.setLastExecutedAt(DRAFT_RAN_AT);
        stubOrgQueries(List.of(wf));
        givenProductionRun(wf, RunStatus.WAITING_TRIGGER, epoch("FAILED", false, "trigger:gone"));

        assertThat(service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE)).singleElement()
                .extracting(ActiveAutomationDto::lastRunStatus, ActiveAutomationDto::lastRunAt)
                .containsExactly(null, DRAFT_RAN_AT);
    }

    @Test
    @DisplayName("The epochs read come from the FK production run, not from the newest run at the pinned version")
    void theBadgeReadsTheRunTriggersActuallyFireInto() {
        // ProductionRunResolver is FK-first, and an EDITOR run sits at the same pinned version -
        // so the started_at-ordered scan used for the click target can hand back a builder
        // session. Reading its epochs would badge the row with the time and verdict of a Play
        // click. The FK has to win.
        WorkflowEntity wf = pinnedWorkflow();
        wf.setProductionRunId(PRODUCTION_RUN_ID);
        stubOrgQueries(List.of(wf));
        WorkflowRunEntity editorRun = productionRun(wf, RunStatus.WAITING_TRIGGER);   // the scan's answer
        WorkflowRunEntity fkRun = new WorkflowRunEntity();
        // Hibernate assigns the id in production; the FK lookup is keyed by it, so the test has
        // to place it the same way rather than inventing an accessor the entity does not have.
        ReflectionTestUtils.setField(fkRun, "id", PRODUCTION_RUN_ID);
        fkRun.setWorkflow(wf);
        fkRun.setRunIdPublic("run_<id>_production");
        fkRun.setStatus(RunStatus.WAITING_TRIGGER);
        fkRun.setPlanVersion(wf.getPinnedVersion());
        when(runRepository.findProductionRunsBatch(eq(List.of(WORKFLOW_ID)))).thenReturn(List.of(editorRun));
        when(runRepository.findAllById(eq(List.of(PRODUCTION_RUN_ID)))).thenReturn(List.of(fkRun));
        // Only the FK run has an outcome; keying by the editor run would find nothing.
        when(epochService.getLatestEpochOutcomeByRunIds(anyList())).thenReturn(
                Map.of("run_<id>_production", epoch("FAILED", false, "trigger:run_it")));
        wf.setPlan(planWith(Map.of("run_it", "manual")));

        List<ActiveAutomationDto> result = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        assertThat(result).singleElement()
                .extracting(ActiveAutomationDto::lastRunStatus).isEqualTo("FAILED");
        // Resource controls must target the FK run that triggers actually fire into.
        assertThat(result.get(0).productionRunIdPublic()).isEqualTo("run_<id>_production");
    }

    @Test
    @DisplayName("A workflow with NO readable plan badges nothing - the kind of the fire cannot be named")
    void anUnreadablePlanBadgesNothing() {
        // No plan at all (and, by the same path, one WorkflowPlan.fromMap rejects). Without it
        // nothing can say which KIND fired, and an unnameable fire must not be claimed by a row.
        WorkflowEntity wf = pinnedWorkflow();
        wf.setPlan(null);
        wf.setLastExecutedAt(DRAFT_RAN_AT);
        stubOrgQueries(List.of(wf));
        givenProductionRun(wf, RunStatus.WAITING_TRIGGER, epoch("COMPLETED", false));

        assertThat(service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE)).singleElement()
                .extracting(ActiveAutomationDto::lastRunStatus, ActiveAutomationDto::lastRunAt)
                .containsExactly(null, DRAFT_RAN_AT);
    }

    @Test
    @DisplayName("A production_run_id pointing at a SHOWCASE run is declined, and the scan answers instead")
    void aShowcaseFkRunIsNotReadAsProduction() {
        // A showcase run is a published snapshot's replay. The pinned-version scan excludes
        // those explicitly, so adopting one through the FK would make this path less filtered
        // than the one it replaced - and badge the row from a run that is not production.
        WorkflowEntity wf = pinnedWorkflow();
        wf.setProductionRunId(PRODUCTION_RUN_ID);
        stubOrgQueries(List.of(wf));
        WorkflowRunEntity scanned = productionRun(wf, RunStatus.WAITING_TRIGGER);
        WorkflowRunEntity showcase = new WorkflowRunEntity();
        ReflectionTestUtils.setField(showcase, "id", PRODUCTION_RUN_ID);
        showcase.setWorkflow(wf);
        showcase.setRunIdPublic("showcase_deadbeef");
        showcase.setStatus(RunStatus.COMPLETED);
        when(runRepository.findProductionRunsBatch(eq(List.of(WORKFLOW_ID)))).thenReturn(List.of(scanned));
        when(runRepository.findAllById(eq(List.of(PRODUCTION_RUN_ID)))).thenReturn(List.of(showcase));
        when(epochService.getLatestEpochOutcomeByRunIds(anyList()))
                .thenReturn(Map.of(RUN_ID_PUBLIC, epoch("COMPLETED", false)));

        List<ActiveAutomationDto> result = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        // The scan's run was read: its outcome is the one that surfaced.
        assertThat(result).singleElement()
                .extracting(ActiveAutomationDto::lastRunStatus).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("A production_run_id whose row is gone falls back to the scan rather than losing the badge")
    void aDanglingFkFallsBackToTheScan() {
        WorkflowEntity wf = pinnedWorkflow();
        wf.setProductionRunId(PRODUCTION_RUN_ID);
        stubOrgQueries(List.of(wf));
        when(runRepository.findProductionRunsBatch(eq(List.of(WORKFLOW_ID))))
                .thenReturn(List.of(productionRun(wf, RunStatus.WAITING_TRIGGER)));
        when(runRepository.findAllById(eq(List.of(PRODUCTION_RUN_ID)))).thenReturn(List.of());
        when(epochService.getLatestEpochOutcomeByRunIds(anyList()))
                .thenReturn(Map.of(RUN_ID_PUBLIC, epoch("FAILED", false)));

        assertThat(service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE)).singleElement()
                .extracting(ActiveAutomationDto::lastRunStatus).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("An epoch read that FAILS costs the badges, never the whole home payload")
    void aFailingEpochReadDegradesToNoBadge() {
        // This endpoint serves the bell's inbox, its unread count and its automations in one
        // response, and the frontend polls it from every page. A workflow_epochs hiccup must
        // leave every row standing, with the timestamp it had before this feature existed.
        WorkflowEntity wf = pinnedWorkflow();
        wf.setLastExecutedAt(DRAFT_RAN_AT);
        stubOrgQueries(List.of(wf));
        when(runRepository.findProductionRunsBatch(eq(List.of(WORKFLOW_ID))))
                .thenReturn(List.of(productionRun(wf, RunStatus.WAITING_TRIGGER)));
        when(epochService.getLatestEpochOutcomeByRunIds(anyList()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("epoch read timed out"));

        List<ActiveAutomationDto> result = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        assertThat(result).singleElement()
                .extracting(ActiveAutomationDto::lastRunStatus, ActiveAutomationDto::lastRunAt)
                .containsExactly(null, DRAFT_RAN_AT);
    }

    @Test
    @DisplayName("An AGENT schedule row keeps the schedule's own time and is never badged")
    void agentRowsCarryNoVerdict() {
        // Agents have no pinning concept, so no production run and no epoch to read. The row
        // must still show when its schedule last fired.
        Instant agentFiredAt = Instant.parse("2026-09-06T07:00:00Z");
        UUID agentId = UUID.fromString("a9e40014-0000-4000-8000-000000000001");
        AgentDto agent = new AgentDto();
        agent.setId(agentId);
        agent.setName("Morning briefing");
        agent.setIsActive(false);
        ScheduledExecutionDto agentSchedule = schedule(SCHEDULE_ID, NIGHTLY_TRIGGER_ID, agentFiredAt);
        agentSchedule.setWorkflowId(null);
        agentSchedule.setAgentEntityId(agentId);

        when(triggerClient.getSchedulesByOrganization(eq(ORG_ID))).thenReturn(List.of(agentSchedule));
        when(workflowRepository.findByOrganizationIdStrictAndIsActiveTrueOrderByCreatedAtDesc(eq(ORG_ID)))
                .thenReturn(Collections.emptyList());
        when(agentClient.getAgents(eq(TENANT_ID), eq(ORG_ID), eq(ORG_ROLE))).thenReturn(List.of(agent));

        List<ActiveAutomationDto> result = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        assertThat(result).singleElement()
                .extracting(ActiveAutomationDto::resourceType, ActiveAutomationDto::lastRunAt,
                        ActiveAutomationDto::lastRunStatus, ActiveAutomationDto::resourcePaused)
                .containsExactly(ActiveAutomationDto.ResourceType.AGENT, agentFiredAt, null, true);
    }

    @Test
    @DisplayName("With no epoch to read, the row keeps its old timestamp and shows NO verdict")
    void withoutAnEpochTheTimeSurvivesButTheVerdictDoesNot() {
        // findProductionRunsBatch unstubbed, so Mockito returns an empty list. The row must
        // still be emitted with the timestamp it has always shown - a borrowed verdict beside
        // it is the thing that would be new and wrong.
        WorkflowEntity wf = pinnedWorkflow();
        wf.setLastExecutedAt(DRAFT_RAN_AT);
        stubOrgQueries(List.of(wf));

        List<ActiveAutomationDto> result = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        assertThat(result).singleElement()
                .extracting(ActiveAutomationDto::lastRunStatus, ActiveAutomationDto::lastRunAt)
                .containsExactly(null, DRAFT_RAN_AT);
    }

    @Test
    @DisplayName("Epoch outcomes are fetched ONCE for the whole popover, keyed by run public id")
    void epochOutcomesAreFetchedInOneBatch() {
        // The bell renders one row per (resource, kind), several per workflow. Resolving the
        // verdict per ROW would multiply a JSONB read by the row count on every poll - the
        // reason the lookup is a batch keyed by run id, done before the row loop.
        WorkflowEntity wf = pinnedWorkflow();
        wf.setNodeIcons(List.of(
                Map.of("nodeKind", "entry", "nodeId", "manual-trigger"),
                Map.of("nodeKind", "entry", "nodeId", "chat-trigger")));
        stubOrgQueries(List.of(wf));
        givenProductionRun(wf, RunStatus.WAITING_TRIGGER, epoch("COMPLETED", false));

        List<ActiveAutomationDto> result = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> runIds = ArgumentCaptor.forClass(List.class);
        verify(epochService, times(1)).getLatestEpochOutcomeByRunIds(runIds.capture());
        assertThat(runIds.getValue()).containsExactly(RUN_ID_PUBLIC);
        // Both rows were served by that ONE read; only the kind that fired claims its verdict.
        assertThat(result).hasSize(2);
        assertThat(rowOfKind(result, ActiveAutomationDto.TriggerType.MANUAL).lastRunStatus())
                .isEqualTo("COMPLETED");
        assertThat(rowOfKind(result, ActiveAutomationDto.TriggerType.CHAT).lastRunStatus()).isNull();
    }

    @Test
    @DisplayName("The verdict reaches the wire as `lastRunStatus`, and is omitted entirely when absent")
    void theFieldIsSerializedUnderTheNameTheFrontendReads() {
        // The frontend reads `a.lastRunStatus`. A rename or a serialization quirk here shows up
        // as an icon that silently never appears, so pin the JSON, not just the record.
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ActiveAutomationDto withVerdict = new ActiveAutomationDto(
                ActiveAutomationDto.ResourceType.WORKFLOW, WORKFLOW_ID, "Nightly digest", null,
                ActiveAutomationDto.TriggerType.MANUAL, null, null, EPOCH_FIRED_AT, true, null, null,
                "FAILED");

        ObjectNode json = mapper.valueToTree(withVerdict);
        assertThat(json.get("lastRunStatus").asText()).isEqualTo("FAILED");

        // NON_NULL on the record: "no honest answer" must not reach the client as a null field
        // it could mistake for a status.
        ObjectNode silent = mapper.valueToTree(new ActiveAutomationDto(
                ActiveAutomationDto.ResourceType.WORKFLOW, WORKFLOW_ID, "Nightly digest", null,
                ActiveAutomationDto.TriggerType.MANUAL, null, null, EPOCH_FIRED_AT, true, null, null,
                null));
        assertThat(silent.has("lastRunStatus")).isFalse();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────────

    /**
     * A plan carrying one trigger per entry, keyed label to type. The label is what
     * {@code Trigger.getNormalizedKey()} turns into {@code trigger:<label>} - the very key the
     * epoch header stores - so a fixture id like {@code "trigger:run_it"} is production-shaped.
     */
    private static Map<String, Object> planWith(Map<String, String> triggersByLabel) {
        List<Map<String, Object>> triggers = triggersByLabel.entrySet().stream()
                .map(e -> Map.<String, Object>of(
                        "id", e.getKey(),
                        "label", e.getKey(),
                        "type", e.getValue(),
                        "params", Map.of()))
                .toList();
        return Map.of("triggers", triggers, "cores", List.of(), "edges", List.of());
    }

    private static ActiveAutomationDto rowOfKind(List<ActiveAutomationDto> rows,
                                                 ActiveAutomationDto.TriggerType kind) {
        return rows.stream()
                .filter(r -> r.triggerType() == kind)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row of kind " + kind + " in " + rows));
    }

    private static ActiveAutomationDto rowOfSchedule(List<ActiveAutomationDto> rows, UUID scheduleId) {
        return rows.stream()
                .filter(r -> r.schedule() != null && scheduleId.equals(r.schedule().scheduleId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row for schedule " + scheduleId));
    }

    private static LatestEpochOutcome epoch(String outcome, boolean active) {
        return epoch(outcome, active, NIGHTLY_TRIGGER_ID);
    }

    private static LatestEpochOutcome epoch(String outcome, boolean active, String triggerId) {
        return new LatestEpochOutcome(outcome, active, EPOCH_FIRED_AT, triggerId);
    }

    private void givenProductionRun(WorkflowEntity wf, RunStatus runStatus, LatestEpochOutcome lastEpoch) {
        when(runRepository.findProductionRunsBatch(eq(List.of(WORKFLOW_ID))))
                .thenReturn(List.of(productionRun(wf, runStatus)));
        when(epochService.getLatestEpochOutcomeByRunIds(anyList()))
                .thenReturn(Map.of(RUN_ID_PUBLIC, lastEpoch));
    }

    private void stubOrgQueries(List<WorkflowEntity> workflows) {
        stubOrgQueries(workflows, Collections.emptyList());
    }

    private void stubOrgQueries(List<WorkflowEntity> workflows, List<ScheduledExecutionDto> schedules) {
        when(triggerClient.getSchedulesByOrganization(eq(ORG_ID))).thenReturn(schedules);
        when(workflowRepository.findByOrganizationIdStrictAndIsActiveTrueOrderByCreatedAtDesc(eq(ORG_ID)))
                .thenReturn(workflows);
        when(agentClient.getAgents(eq(TENANT_ID), eq(ORG_ID), eq(ORG_ROLE)))
                .thenReturn(Collections.emptyList());
        // findWorkflowIdsWithTokens is left unstubbed: Mockito's default answer returns an
        // empty set, so no webhook row joins the row under test.
    }

    private WorkflowEntity pinnedWorkflow() {
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(WORKFLOW_ID);
        wf.setName("Nightly digest");
        wf.setOrganizationId(ORG_ID);
        wf.setPinnedVersion(3);
        wf.setLastExecutedAt(EPOCH_FIRED_AT);
        wf.setNodeIcons(List.of(Map.of("nodeKind", "entry", "nodeId", "manual-trigger")));
        // The default epoch fixture is fired by NIGHTLY_TRIGGER_ID; the plan is what says which
        // KIND that is, so a row can tell whether the fire was its own.
        wf.setPlan(planWith(Map.of("nightly", "manual")));
        return wf;
    }

    private ScheduledExecutionDto schedule(Instant lastExecutionAt) {
        return schedule(SCHEDULE_ID, NIGHTLY_TRIGGER_ID, lastExecutionAt);
    }

    private ScheduledExecutionDto schedule(UUID id, String triggerId, Instant lastExecutionAt) {
        ScheduledExecutionDto dto = new ScheduledExecutionDto();
        dto.setId(id);
        dto.setTriggerId(triggerId);
        dto.setWorkflowId(WORKFLOW_ID);
        dto.setOrganizationId(ORG_ID);
        dto.setTenantId(TENANT_ID);
        dto.setEnabled(true);
        dto.setCronExpression("0 4 * * *");
        dto.setTimezone("UTC");
        dto.setNextExecutionAt(Instant.parse("2026-09-07T04:00:00Z"));
        dto.setLastExecutionAt(lastExecutionAt);
        return dto;
    }

    private WorkflowRunEntity productionRun(WorkflowEntity wf, RunStatus status) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setWorkflow(wf);
        run.setRunIdPublic(RUN_ID_PUBLIC);
        run.setStatus(status);
        return run;
    }
}
