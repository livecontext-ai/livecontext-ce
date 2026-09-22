package com.apimarketplace.orchestrator.services;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto.ResourceType;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.credit.WorkflowBudgetPeriod;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The wiring that carries a workflow's spending block onto its schedule rows.
 *
 * <p>{@code budgetBlock} is unit-tested on its own and {@code AgendaService} is
 * tested on hand-built {@code ScheduleInfo} values, so both ends were covered
 * and the join between them was not: deleting the {@code budgetOwner} argument
 * at the two call sites left every other test green while the calendar quietly
 * greyed nothing. A feature that is green and dead is the failure this repo's
 * own guidance calls out by name, so it gets a test that drives the real
 * {@code getActiveAutomations}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActiveAutomationsService - a workflow's spending block reaches its schedule rows")
class ActiveAutomationsServiceBudgetWiringTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private TriggerClient triggerClient;
    @Mock private AgentClient agentClient;
    @Mock private WorkflowEpochService epochService;

    private ActiveAutomationsService service;

    private static final String TENANT_ID = "tenant-budget";
    private static final String ORG_ID = "org-budget";
    private static final String ORG_ROLE = "MEMBER";
    private static final UUID WORKFLOW_ID = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID SCHEDULE_ID = UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa");
    private static final UUID AGENT_ID = UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-ffffffffffff");

    @BeforeEach
    void setUp() {
        service = new ActiveAutomationsService(workflowRepository, runRepository, triggerClient, agentClient,
                epochService);
    }

    private WorkflowEntity workflow(String cap, String mode, String spent, Instant periodStart) {
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(WORKFLOW_ID);
        wf.setName("Nightly Digest");
        wf.setOrganizationId(ORG_ID);
        wf.setPinnedVersion(3);
        wf.setNodeIcons(List.of());   // isolate the schedule path
        wf.setBudgetCredits(cap == null ? null : new BigDecimal(cap));
        wf.setBudgetPeriodMode(mode);
        // DB-managed columns: no setters, by design.
        ReflectionTestUtils.setField(wf, "budgetPeriodSpent", spent == null ? null : new BigDecimal(spent));
        ReflectionTestUtils.setField(wf, "budgetPeriodStartedAt", periodStart);
        return wf;
    }

    private static ScheduledExecutionDto schedule(UUID workflowId, UUID agentId) {
        ScheduledExecutionDto dto = new ScheduledExecutionDto();
        dto.setId(SCHEDULE_ID);
        dto.setWorkflowId(workflowId);
        dto.setAgentEntityId(agentId);
        dto.setOrganizationId(ORG_ID);
        dto.setTenantId(TENANT_ID);
        dto.setEnabled(true);
        dto.setCronExpression("0 9 * * *");
        dto.setTimezone("UTC");
        dto.setNextExecutionAt(Instant.parse("2026-09-20T09:00:00Z"));
        return dto;
    }

    private void stubWorkflow(WorkflowEntity wf) {
        when(triggerClient.getSchedulesByOrganization(eq(ORG_ID)))
                .thenReturn(List.of(schedule(WORKFLOW_ID, null)));
        when(workflowRepository.findByOrganizationIdStrictAndIsActiveTrueOrderByCreatedAtDesc(eq(ORG_ID)))
                .thenReturn(List.of(wf));
        when(agentClient.getAgents(eq(TENANT_ID), eq(ORG_ID), eq(ORG_ROLE)))
                .thenReturn(Collections.emptyList());
    }

    private ActiveAutomationDto.ScheduleInfo onlySchedule() {
        List<ActiveAutomationDto> rows = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).schedule()).isNotNull();
        return rows.get(0).schedule();
    }

    @Test
    @DisplayName("a workflow over its cap marks its schedule blocked, and says when that lifts")
    void overCapMarksTheScheduleBlocked() {
        // Delete the `w` argument at the attached-schedule call site and this
        // test turns red, together with `cumulativeBlocksWithNoDate`. Nothing
        // outside this file notices, which is the whole reason it exists.
        stubWorkflow(workflow("10", "monthly", "10", WorkflowBudgetPeriod.periodStart("monthly", Instant.now())));

        ActiveAutomationDto.ScheduleInfo info = onlySchedule();

        assertThat(info.budgetBlocked()).isTrue();
        assertThat(info.budgetBlockedUntil())
                .isEqualTo(WorkflowBudgetPeriod.nextPeriodStart("monthly", Instant.now()));
        // The SCHEDULE is untouched: it is still armed, still has its cron. Only
        // the workflow is resting, and only until the date above.
        assertThat(info.armed()).isTrue();
        assertThat(info.pausedReason()).isNull();
    }

    @Test
    @DisplayName("a workflow under its cap marks nothing, so the calendar is left alone")
    void underCapIsNotBlocked() {
        stubWorkflow(workflow("10", "monthly", "3", WorkflowBudgetPeriod.periodStart("monthly", Instant.now())));

        ActiveAutomationDto.ScheduleInfo info = onlySchedule();

        assertThat(info.budgetBlocked()).isFalse();
        assertThat(info.budgetBlockedUntil()).isNull();
    }

    @Test
    @DisplayName("an uncapped workflow is never blocked, whatever it has spent")
    void uncappedIsNeverBlocked() {
        stubWorkflow(workflow(null, "monthly", "9999", WorkflowBudgetPeriod.periodStart("monthly", Instant.now())));

        assertThat(onlySchedule().budgetBlocked()).isFalse();
    }

    @Test
    @DisplayName("an AGENT schedule is never blocked by a workflow cap it has nothing to do with")
    void agentScheduleIsNeverBlocked() {
        // The branch most likely to rot, because it is an absence: an agent's
        // budget is a different subsystem with its own counter and its own
        // reset. Passing a workflow here would grey agent schedules on the
        // calendar for a cap that does not govern them.
        AgentDto agent = new AgentDto();
        agent.setId(AGENT_ID);
        agent.setName("Researcher");
        when(triggerClient.getSchedulesByOrganization(eq(ORG_ID)))
                .thenReturn(List.of(schedule(null, AGENT_ID)));
        when(workflowRepository.findByOrganizationIdStrictAndIsActiveTrueOrderByCreatedAtDesc(eq(ORG_ID)))
                .thenReturn(List.of());
        when(agentClient.getAgents(eq(TENANT_ID), eq(ORG_ID), eq(ORG_ROLE))).thenReturn(List.of(agent));

        List<ActiveAutomationDto> rows = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        assertThat(rows).isNotEmpty();
        ActiveAutomationDto agentRow = rows.stream()
                .filter(r -> r.resourceType() == ResourceType.AGENT)
                .findFirst()
                .orElseThrow();
        assertThat(agentRow.schedule()).isNotNull();
        assertThat(agentRow.schedule().budgetBlocked()).isFalse();
        assertThat(agentRow.schedule().budgetBlockedUntil()).isNull();
    }

    private void stubAgentOnly(AgentDto agent) {
        when(triggerClient.getSchedulesByOrganization(eq(ORG_ID)))
                .thenReturn(List.of(schedule(null, AGENT_ID)));
        when(workflowRepository.findByOrganizationIdStrictAndIsActiveTrueOrderByCreatedAtDesc(eq(ORG_ID)))
                .thenReturn(List.of());
        when(agentClient.getAgents(eq(TENANT_ID), eq(ORG_ID), eq(ORG_ROLE))).thenReturn(List.of(agent));
    }

    private ActiveAutomationDto.ScheduleInfo onlyAgentSchedule() {
        List<ActiveAutomationDto> rows = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);
        return rows.stream()
                .filter(r -> r.resourceType() == ResourceType.AGENT && r.schedule() != null)
                .findFirst()
                .orElseThrow()
                .schedule();
    }

    private static AgentDto agentWithVerdict(Boolean blocked, Instant until) {
        AgentDto agent = new AgentDto();
        agent.setId(AGENT_ID);
        agent.setName("Researcher");
        agent.setCreditBudget(new BigDecimal("1"));
        agent.setCreditsConsumed(new BigDecimal("3"));
        agent.setBudgetBlocked(blocked);
        agent.setBudgetBlockedUntil(until);
        return agent;
    }

    @Test
    @DisplayName("an agent over its OWN cap marks its schedule blocked, so the calendar greys it")
    void agentOverItsOwnCapMarksTheScheduleBlocked() {
        // The other half of the join, added with the schedule gate: the engine now refuses
        // these fires, so a calendar that still drew them would be promising runs the
        // product has already decided not to make.
        Instant lifts = Instant.parse("2026-10-01T00:00:00Z");
        stubAgentOnly(agentWithVerdict(true, lifts));

        ActiveAutomationDto.ScheduleInfo info = onlyAgentSchedule();

        assertThat(info.budgetBlocked()).isTrue();
        assertThat(info.budgetBlockedUntil()).isEqualTo(lifts);
        // The schedule itself is untouched, exactly as on the workflow side: still armed,
        // still on its cron, resting until the cap lifts.
        assertThat(info.armed()).isTrue();
        assertThat(info.pausedReason()).isNull();
    }

    @Test
    @DisplayName("an agent blocked by a cap that never resets carries NO date")
    void agentCumulativeBlockCarriesNoDate() {
        // Null "until" beside a true "blocked" is what tells the agenda to grey the WHOLE
        // future rather than a window of it. Same contract as the workflow side, so nothing
        // downstream has to know which kind of resource it is looking at.
        stubAgentOnly(agentWithVerdict(true, null));

        ActiveAutomationDto.ScheduleInfo info = onlyAgentSchedule();

        assertThat(info.budgetBlocked()).isTrue();
        assertThat(info.budgetBlockedUntil()).isNull();
    }

    @Test
    @DisplayName("an agent whose payload carries no verdict is drawn as normal")
    void agentWithNoVerdictIsNotBlocked() {
        // Null arrives from a build that predates the field, which is every payload during
        // a rolling deploy. Reading it as blocked would grey the whole fleet's calendar.
        stubAgentOnly(agentWithVerdict(null, null));

        assertThat(onlyAgentSchedule().budgetBlocked()).isFalse();
    }

    @Test
    @DisplayName("a spend from an EXPIRED period does not block: the allowance already restarted")
    void rolledOverSpendDoesNotBlock() {
        stubWorkflow(workflow("10", "monthly", "50",
                Instant.now().minus(60, java.time.temporal.ChronoUnit.DAYS)));

        assertThat(onlySchedule().budgetBlocked()).isFalse();
    }

    @Test
    @DisplayName("a cap that never resets blocks with NO date, which is not the same as unblocked")
    void cumulativeBlocksWithNoDate() {
        stubWorkflow(workflow("10", "cumulative", "50", null));

        ActiveAutomationDto.ScheduleInfo info = onlySchedule();

        assertThat(info.budgetBlocked()).isTrue();
        assertThat(info.budgetBlockedUntil()).isNull();
    }


    @Test
    @DisplayName("a STANDALONE schedule inherits the block too: the second call site is real code")
    void standaloneScheduleAlsoInheritsTheBlock() {
        // A standalone schedule carries workflow_id = NULL by design and is
        // resolved from the pinned plan's scheduleId, through a SECOND call
        // site. Covering only the attached one left that half free to be
        // deleted with the suite still green - and a workflow whose only
        // trigger is standalone would have gone on drawing fires it cannot run.
        WorkflowEntity wf = workflow("10", "monthly", "10",
                WorkflowBudgetPeriod.periodStart("monthly", Instant.now()));
        wf.setPlan(planReferencingSchedule());

        ScheduledExecutionDto standalone = schedule(null, null);
        when(triggerClient.getSchedulesByOrganization(eq(ORG_ID))).thenReturn(List.of(standalone));
        when(workflowRepository.findByOrganizationIdStrictAndIsActiveTrueOrderByCreatedAtDesc(eq(ORG_ID)))
                .thenReturn(List.of(wf));
        when(agentClient.getAgents(eq(TENANT_ID), eq(ORG_ID), eq(ORG_ROLE)))
                .thenReturn(Collections.emptyList());

        ActiveAutomationDto.ScheduleInfo info = onlySchedule();

        assertThat(info.budgetBlocked()).isTrue();
        assertThat(info.budgetBlockedUntil())
                .isEqualTo(WorkflowBudgetPeriod.nextPeriodStart("monthly", Instant.now()));
    }

    /** A plan whose single schedule trigger names SCHEDULE_ID, the standalone link. */
    private static java.util.Map<String, Object> planReferencingSchedule() {
        java.util.Map<String, Object> trigger = new java.util.HashMap<>();
        trigger.put("id", "daily");
        trigger.put("type", "schedule");
        trigger.put("params", java.util.Map.of("scheduleId", SCHEDULE_ID.toString()));
        java.util.Map<String, Object> plan = new java.util.HashMap<>();
        plan.put("triggers", List.of(trigger));
        return plan;
    }
}
