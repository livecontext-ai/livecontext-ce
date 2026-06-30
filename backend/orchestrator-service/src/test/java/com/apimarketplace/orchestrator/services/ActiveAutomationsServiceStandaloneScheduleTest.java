package com.apimarketplace.orchestrator.services;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto.ResourceType;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto.TriggerType;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowEntity.WorkflowType;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for standalone-schedule surfacing in
 * {@link ActiveAutomationsService}.
 *
 * <p><b>Bug.</b> A "standalone" schedule row carries a NULL {@code workflow_id} by
 * design (V206 {@code raise_immutable_workflow_id} anti-hijack immutability) and is
 * linked to its owning workflow ONLY through the plan's schedule-trigger
 * {@code scheduleId} param. The pre-fix service joined schedules to workflows ONLY
 * by the {@code workflow_id} column ({@code schedulesByWorkflow}), so a pinned
 * workflow whose sole trigger is a standalone schedule emitted ZERO automation
 * rows - the notification bell's Triggers tab (and, for a notification-free new
 * account, the WHOLE bell, which self-hides on an empty automations list) stayed
 * empty even though the schedule was armed and firing.
 *
 * <p><b>Fix.</b> Each pinned workflow's plan {@code scheduleId} references are
 * resolved against the already-loaded enabled+org-filtered schedule set - no extra
 * wire call, pin gate and org scope preserved.
 *
 * <p>The first test fails on the pre-fix code (empty result) and passes post-fix.
 * Field values mirror the real reproduction captured in the local DB
 * (workflow {@code f9e93b0f…}, standalone schedule {@code 56bcbd77…}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActiveAutomationsService - standalone schedule surfacing (workflow_id NULL)")
class ActiveAutomationsServiceStandaloneScheduleTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private TriggerClient triggerClient;
    @Mock private AgentClient agentClient;

    private ActiveAutomationsService service;

    private static final String TENANT_ID = "tenant-77";
    private static final String ORG_ID = "org-1234-abcd";
    private static final String ORG_ROLE = "MEMBER";
    private static final UUID WORKFLOW_ID = UUID.fromString("f9e93b0f-2316-4e83-9b5c-521e627555a3");
    private static final UUID WORKFLOW_ID_2 = UUID.fromString("a1b2c3d4-0000-4000-8000-000000000002");
    private static final UUID SCHEDULE_ID = UUID.fromString("56bcbd77-db50-4df5-b402-dfbb74f530d8");
    private static final UUID OTHER_SCHEDULE_ID = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final UUID PUBLICATION_ID = UUID.fromString("c0ffee00-0000-4000-8000-000000000001");

    @BeforeEach
    void setUp() {
        service = new ActiveAutomationsService(workflowRepository, runRepository,
                triggerClient, agentClient);
    }

    @Test
    @DisplayName("standalone schedule (workflow_id NULL) referenced by a pinned plan IS surfaced - the reported bug")
    void standaloneScheduleOnPinnedWorkflowIsSurfaced() {
        WorkflowEntity wf = pinnedWorkflow(WORKFLOW_ID, schedulePlanWithId(SCHEDULE_ID.toString()));
        ScheduledExecutionDto standalone = standaloneSchedule(SCHEDULE_ID, ORG_ID, /* enabled */ true);

        stubOrgQueries(List.of(wf), List.of(standalone));

        List<ActiveAutomationDto> result = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        // Pre-fix: empty (standalone dropped by the workflow_id-only join). Post-fix: 1 row.
        assertThat(result).hasSize(1);
        ActiveAutomationDto row = result.get(0);
        assertThat(row.triggerType()).isEqualTo(TriggerType.SCHEDULE);
        assertThat(row.resourceId()).isEqualTo(WORKFLOW_ID);
        assertThat(row.isPinned()).isTrue();
        assertThat(row.schedule()).isNotNull();
        assertThat(row.schedule().cronExpression()).isEqualTo("* * * * *");
        assertThat(row.schedule().nextFireAt()).isEqualTo(Instant.parse("2026-06-18T14:46:00Z"));
    }

    @Test
    @DisplayName("an attached schedule (workflow_id set) ALSO referenced by the plan scheduleId is emitted exactly once - no double-emit")
    void attachedAndPlanReferencedScheduleEmittedOnce() {
        WorkflowEntity wf = pinnedWorkflow(WORKFLOW_ID, schedulePlanWithId(SCHEDULE_ID.toString()));
        ScheduledExecutionDto attached = standaloneSchedule(SCHEDULE_ID, ORG_ID, true);
        attached.setWorkflowId(WORKFLOW_ID); // attached: present in schedulesByWorkflow too

        stubOrgQueries(List.of(wf), List.of(attached));

        List<ActiveAutomationDto> result = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        // De-dup guard: emitted via schedulesByWorkflow, skipped by the standalone pass.
        assertThat(result).hasSize(1);
        assertThat(result.get(0).triggerType()).isEqualTo(TriggerType.SCHEDULE);
        assertThat(result.get(0).resourceId()).isEqualTo(WORKFLOW_ID);
    }

    @Test
    @DisplayName("a DISABLED standalone schedule is NOT surfaced - the enabled filter still gates the standalone pass")
    void disabledStandaloneScheduleIsNotSurfaced() {
        WorkflowEntity wf = pinnedWorkflow(WORKFLOW_ID, schedulePlanWithId(SCHEDULE_ID.toString()));
        ScheduledExecutionDto disabled = standaloneSchedule(SCHEDULE_ID, ORG_ID, /* enabled */ false);

        stubOrgQueries(List.of(wf), List.of(disabled));

        List<ActiveAutomationDto> result = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("a standalone schedule of ANOTHER org is NOT surfaced - org scope preserved on the standalone pass")
    void otherOrgStandaloneScheduleIsNotSurfaced() {
        WorkflowEntity wf = pinnedWorkflow(WORKFLOW_ID, schedulePlanWithId(SCHEDULE_ID.toString()));
        ScheduledExecutionDto otherOrg = standaloneSchedule(SCHEDULE_ID, "org-SOMEONE-else", true);

        stubOrgQueries(List.of(wf), List.of(otherOrg));

        List<ActiveAutomationDto> result = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("an UNPINNED workflow referencing a standalone schedule is NOT surfaced - pin gate preserved")
    void unpinnedWorkflowStandaloneScheduleIsNotSurfaced() {
        WorkflowEntity wf = pinnedWorkflow(WORKFLOW_ID, schedulePlanWithId(SCHEDULE_ID.toString()));
        wf.setPinnedVersion(null); // unpinned
        ScheduledExecutionDto standalone = standaloneSchedule(SCHEDULE_ID, ORG_ID, true);

        stubOrgQueries(List.of(wf), List.of(standalone));

        List<ActiveAutomationDto> result = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("a malformed scheduleId in the plan is skipped without dropping a sibling valid standalone schedule")
    void malformedScheduleIdIsSkippedButValidSiblingSurfaces() {
        // Two schedule triggers: one with a junk scheduleId, one valid.
        Map<String, Object> plan = new HashMap<>();
        plan.put("triggers", List.of(
                scheduleTrigger("broken", "not-a-uuid"),
                scheduleTrigger("good", SCHEDULE_ID.toString())));
        WorkflowEntity wf = pinnedWorkflow(WORKFLOW_ID, plan);
        ScheduledExecutionDto standalone = standaloneSchedule(SCHEDULE_ID, ORG_ID, true);

        stubOrgQueries(List.of(wf), List.of(standalone));

        List<ActiveAutomationDto> result = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        // No exception, junk trigger ignored, the valid standalone still surfaces.
        assertThat(result).hasSize(1);
        assertThat(result.get(0).resourceId()).isEqualTo(WORKFLOW_ID);
    }

    @Test
    @DisplayName("legacy attached schedule with NO scheduleId in the plan still surfaces - backward compatibility")
    void legacyAttachedScheduleWithoutPlanReferenceStillSurfaces() {
        // Plan declares a schedule trigger but carries NO scheduleId param (old
        // attached model); the schedule row links via its workflow_id column.
        Map<String, Object> plan = new HashMap<>();
        plan.put("triggers", List.of(scheduleTrigger("scheduler", /* scheduleId */ null)));
        WorkflowEntity wf = pinnedWorkflow(WORKFLOW_ID, plan);
        ScheduledExecutionDto attached = standaloneSchedule(SCHEDULE_ID, ORG_ID, true);
        attached.setWorkflowId(WORKFLOW_ID);

        stubOrgQueries(List.of(wf), List.of(attached));

        List<ActiveAutomationDto> result = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).resourceId()).isEqualTo(WORKFLOW_ID);
    }

    @Test
    @DisplayName("a well-formed scheduleId that resolves to NO enabled schedule is skipped - the not-found branch (deleted / max-reached row)")
    void phantomScheduleIdResolvingToNoEnabledScheduleIsSkipped() {
        // Plan references SCHEDULE_ID, but the org's enabled set contains only an
        // UNRELATED standalone schedule (OTHER_SCHEDULE_ID) - so the by-id lookup
        // misses. This is the s == null branch, distinct from the disabled-filter
        // case: the map is non-empty, the looked-up id is simply absent.
        WorkflowEntity wf = pinnedWorkflow(WORKFLOW_ID, schedulePlanWithId(SCHEDULE_ID.toString()));
        ScheduledExecutionDto unrelated = standaloneSchedule(OTHER_SCHEDULE_ID, ORG_ID, true);

        stubOrgQueries(List.of(wf), List.of(unrelated));

        List<ActiveAutomationDto> result = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("the SAME standalone schedule referenced by two pinned workflows surfaces once under EACH - per-workflow attribution")
    void sameStandaloneScheduleReferencedByTwoPinnedWorkflowsSurfacesForEach() {
        WorkflowEntity wf1 = pinnedWorkflow(WORKFLOW_ID, schedulePlanWithId(SCHEDULE_ID.toString()));
        WorkflowEntity wf2 = pinnedWorkflow(WORKFLOW_ID_2, schedulePlanWithId(SCHEDULE_ID.toString()));
        ScheduledExecutionDto standalone = standaloneSchedule(SCHEDULE_ID, ORG_ID, true);

        stubOrgQueries(List.of(wf1, wf2), List.of(standalone));

        List<ActiveAutomationDto> result = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        // One row per workflow (de-dup is per-workflow, not global).
        assertThat(result).hasSize(2);
        assertThat(result).allMatch(r -> r.triggerType() == TriggerType.SCHEDULE);
        assertThat(result).extracting(ActiveAutomationDto::resourceId)
                .containsExactlyInAnyOrder(WORKFLOW_ID, WORKFLOW_ID_2);
    }

    @Test
    @DisplayName("an APPLICATION-type pinned workflow surfaces its standalone schedule with APPLICATION routing (publicationId)")
    void applicationStandaloneScheduleRoutesWithPublicationId() {
        WorkflowEntity app = pinnedWorkflow(WORKFLOW_ID, schedulePlanWithId(SCHEDULE_ID.toString()));
        app.setWorkflowType(WorkflowType.APPLICATION);
        app.setSourcePublicationId(PUBLICATION_ID);
        ScheduledExecutionDto standalone = standaloneSchedule(SCHEDULE_ID, ORG_ID, true);

        stubOrgQueries(List.of(app), List.of(standalone));

        List<ActiveAutomationDto> result = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        assertThat(result).hasSize(1);
        ActiveAutomationDto row = result.get(0);
        assertThat(row.resourceType()).isEqualTo(ResourceType.APPLICATION);
        assertThat(row.triggerType()).isEqualTo(TriggerType.SCHEDULE);
        assertThat(row.publicationId()).isEqualTo(PUBLICATION_ID.toString());
    }

    @Test
    @DisplayName("a pinned workflow with a NULL plan is handled safely and surfaces nothing - defensive guard")
    void nullPlanIsHandledSafelyAndSurfacesNothing() {
        WorkflowEntity wf = pinnedWorkflow(WORKFLOW_ID, /* plan */ null);
        // An enabled standalone schedule exists in the org but nothing references it.
        ScheduledExecutionDto standalone = standaloneSchedule(SCHEDULE_ID, ORG_ID, true);

        stubOrgQueries(List.of(wf), List.of(standalone));

        List<ActiveAutomationDto> result = service.getActiveAutomations(TENANT_ID, ORG_ID, ORG_ROLE);

        // No NPE on the null plan; no row without a plan reference.
        assertThat(result).isEmpty();
    }

    // ===== helpers =====

    private void stubOrgQueries(List<WorkflowEntity> workflows, List<ScheduledExecutionDto> schedules) {
        when(triggerClient.getSchedulesByOrganization(eq(ORG_ID))).thenReturn(schedules);
        when(workflowRepository.findByOrganizationIdStrictAndIsActiveTrueOrderByCreatedAtDesc(eq(ORG_ID)))
                .thenReturn(workflows);
        when(agentClient.getAgents(eq(TENANT_ID), eq(ORG_ID), eq(ORG_ROLE)))
                .thenReturn(Collections.emptyList());
        // findWorkflowIdsWithTokens (Set) and findProductionRunsBatch (List) are
        // left unstubbed: Mockito's default answer returns empty collections, so
        // the pinned-workflow path runs without webhook tokens or production runs.
    }

    private WorkflowEntity pinnedWorkflow(UUID id, Map<String, Object> plan) {
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(id);
        wf.setName("z");
        wf.setOrganizationId(ORG_ID);
        wf.setPinnedVersion(3);
        wf.setNodeIcons(List.of()); // isolate the schedule path: no declared-kind rows
        wf.setPlan(plan);
        return wf;
    }

    private static ScheduledExecutionDto standaloneSchedule(UUID id, String orgId, boolean enabled) {
        ScheduledExecutionDto dto = new ScheduledExecutionDto();
        dto.setId(id);
        dto.setWorkflowId(null);     // standalone - the crux of the bug
        dto.setAgentEntityId(null);
        dto.setOrganizationId(orgId);
        dto.setTenantId(TENANT_ID);
        dto.setEnabled(enabled);
        dto.setCronExpression("* * * * *");
        dto.setTimezone("UTC");
        dto.setNextExecutionAt(Instant.parse("2026-06-18T14:46:00Z"));
        return dto;
    }

    /** A plan with a single schedule trigger carrying the given scheduleId param. */
    private static Map<String, Object> schedulePlanWithId(String scheduleId) {
        Map<String, Object> plan = new HashMap<>();
        plan.put("triggers", List.of(scheduleTrigger("scheduler", scheduleId)));
        return plan;
    }

    /** Ground-truth shape of a schedule trigger node (see workflow z's real plan). */
    private static Map<String, Object> scheduleTrigger(String id, String scheduleId) {
        Map<String, Object> params = new HashMap<>();
        params.put("cron", "* * * * *");
        params.put("enabled", true);
        params.put("timezone", "UTC");
        if (scheduleId != null) {
            params.put("scheduleId", scheduleId);
        }
        Map<String, Object> trigger = new HashMap<>();
        trigger.put("id", id);
        trigger.put("type", "schedule");
        trigger.put("label", "Scheduler");
        trigger.put("params", params);
        return trigger;
    }
}
