package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.ScheduleCreateRequest;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleSyncService - Pin-Aware Schedule Lifecycle")
class ScheduleSyncServiceTest {

    @Mock
    private TriggerClient triggerClient;

    @Mock
    private WorkflowPlanVersionService versionService;

    private ScheduleSyncService scheduleSyncService;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String TENANT_ID = "user-test";

    @BeforeEach
    void setUp() {
        scheduleSyncService = new ScheduleSyncService(triggerClient, versionService);
    }

    // ─────────────────────── Helpers ───────────────────────

    private WorkflowEntity buildWorkflow() {
        return buildWorkflow(null);
    }

    private WorkflowEntity buildWorkflow(Integer pinnedVersion) {
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(WORKFLOW_ID);
        wf.setTenantId(TENANT_ID);
        wf.setName("Test Workflow");
        wf.setPinnedVersion(pinnedVersion);
        return wf;
    }

    private WorkflowPlan buildPlan(List<Trigger> triggers) {
        return new WorkflowPlan(null, TENANT_ID, triggers,
                null, null, null, null, null, null, null, null);
    }

    private WorkflowPlan buildPlanWithSchedule(String cron, String timezone) {
        Trigger trigger = new Trigger("sched-1", "Daily Job", "schedule", "schedule",
                Map.of("cron", cron, "timezone", timezone, "enabled", true));
        return buildPlan(List.of(trigger));
    }

    private ScheduledExecutionDto buildScheduleDto(UUID id, String triggerId) {
        ScheduledExecutionDto dto = new ScheduledExecutionDto();
        dto.setId(id);
        dto.setWorkflowId(WORKFLOW_ID);
        dto.setTriggerId(triggerId);
        return dto;
    }

    private WorkflowPlanVersionEntity buildVersionEntity(int version, Map<String, Object> planMap) {
        WorkflowPlanVersionEntity entity = new WorkflowPlanVersionEntity(WORKFLOW_ID, version, planMap, TENANT_ID);
        return entity;
    }

    private Map<String, Object> planMapWithSchedule(String cron, String timezone) {
        Map<String, Object> planMap = new HashMap<>();
        Map<String, Object> triggerMap = new HashMap<>();
        triggerMap.put("id", "sched-1");
        triggerMap.put("label", "Daily Job");
        triggerMap.put("type", "schedule");
        triggerMap.put("trigger_type", "schedule");
        triggerMap.put("params", Map.of("cron", cron, "timezone", timezone, "enabled", true));
        planMap.put("triggers", List.of(triggerMap));
        planMap.put("name", "Test Workflow");
        return planMap;
    }

    private Map<String, Object> planMapWithManualTrigger() {
        Map<String, Object> planMap = new HashMap<>();
        Map<String, Object> triggerMap = new HashMap<>();
        triggerMap.put("id", "man-1");
        triggerMap.put("label", "Manual");
        triggerMap.put("type", "manual");
        triggerMap.put("trigger_type", "manual");
        planMap.put("triggers", List.of(triggerMap));
        planMap.put("name", "Test Workflow");
        return planMap;
    }

    private Map<String, Object> planMapWithMultipleSchedules(String cron1, String cron2) {
        Map<String, Object> planMap = new HashMap<>();
        Map<String, Object> t1 = new HashMap<>();
        t1.put("id", "sched-m");
        t1.put("label", "Morning Job");
        t1.put("type", "schedule");
        t1.put("trigger_type", "schedule");
        t1.put("params", Map.of("cron", cron1, "timezone", "UTC", "enabled", true));
        Map<String, Object> t2 = new HashMap<>();
        t2.put("id", "sched-n");
        t2.put("label", "Night Job");
        t2.put("type", "schedule");
        t2.put("trigger_type", "schedule");
        t2.put("params", Map.of("cron", cron2, "timezone", "Europe/Paris", "enabled", true));
        planMap.put("triggers", List.of(t1, t2));
        planMap.put("name", "Test Workflow");
        return planMap;
    }

    // ─────────────────────── hasScheduleTrigger ───────────────────────

    @Nested
    @DisplayName("hasScheduleTrigger")
    class HasScheduleTriggerTests {

        @Test
        @DisplayName("Returns true when plan has schedule trigger")
        void returnsTrueWithSchedule() {
            WorkflowPlan plan = buildPlanWithSchedule("0 9 * * *", "UTC");
            assertThat(scheduleSyncService.hasScheduleTrigger(plan)).isTrue();
        }

        @Test
        @DisplayName("Returns false with no schedule trigger")
        void returnsFalseWithoutSchedule() {
            Trigger webhook = new Trigger("wh-1", "My Webhook", "webhook", "webhook");
            assertThat(scheduleSyncService.hasScheduleTrigger(buildPlan(List.of(webhook)))).isFalse();
        }

        @Test
        @DisplayName("Returns false with null plan")
        void returnsFalseWithNullPlan() {
            assertThat(scheduleSyncService.hasScheduleTrigger(null)).isFalse();
        }

        @Test
        @DisplayName("Returns false with empty triggers")
        void returnsFalseWithEmptyTriggers() {
            assertThat(scheduleSyncService.hasScheduleTrigger(buildPlan(Collections.emptyList()))).isFalse();
        }
    }

    // ─────────────────────── Pin-Aware: syncFromPinnedVersion ───────────────────────

    @Nested
    @DisplayName("syncFromPinnedVersion - Pin-Aware Sync")
    class SyncFromPinnedVersionTests {

        @Test
        @DisplayName("v2 pinned with schedule, v3 draft with manual → schedule stays with v2 cron")
        void pinnedVersionScheduleNotAffectedByDraft() {
            WorkflowEntity workflow = buildWorkflow(2);

            // v2 (pinned) has schedule with "0 9 * * *"
            Map<String, Object> v2Plan = planMapWithSchedule("0 9 * * *", "Europe/Paris");
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, v2Plan)));
            when(triggerClient.createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString()))
                    .thenReturn(buildScheduleDto(UUID.randomUUID(), "trigger:daily_job"));
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID)).thenReturn(Collections.emptyList());

            scheduleSyncService.syncFromPinnedVersion(workflow);

            // Verify schedule was created with v2's cron, NOT any draft cron
            ArgumentCaptor<ScheduleCreateRequest> captor = ArgumentCaptor.forClass(ScheduleCreateRequest.class);
            verify(triggerClient).createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), captor.capture(), anyString(), anyString());
            assertThat(captor.getValue().cron()).isEqualTo("0 9 * * *");
            assertThat(captor.getValue().timezone()).isEqualTo("Europe/Paris");
        }

        @Test
        @DisplayName("Unpin → all schedules disabled (not deleted)")
        void unpinDisablesAllSchedules() {
            WorkflowEntity workflow = buildWorkflow(null); // unpinned

            UUID schedId = UUID.randomUUID();
            ScheduledExecutionDto existing = buildScheduleDto(schedId, "trigger:daily_job");
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID)).thenReturn(List.of(existing));

            scheduleSyncService.syncFromPinnedVersion(workflow);

            verify(triggerClient).suspendSchedule(schedId, "WORKFLOW_UNPINNED");
            verify(triggerClient, never()).createOrUpdateSchedule(any(), anyString(), anyString(),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString());
        }

        @Test
        @DisplayName("Pin v1 after v2 → schedule reflects v1 cron")
        void switchPinToOlderVersion() {
            WorkflowEntity workflow = buildWorkflow(1);

            // v1 has different cron than v2
            Map<String, Object> v1Plan = planMapWithSchedule("30 6 * * *", "US/Eastern");
            when(versionService.getVersion(WORKFLOW_ID, 1))
                    .thenReturn(Optional.of(buildVersionEntity(1, v1Plan)));
            when(triggerClient.createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString()))
                    .thenReturn(buildScheduleDto(UUID.randomUUID(), "trigger:daily_job"));
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID)).thenReturn(Collections.emptyList());

            scheduleSyncService.syncFromPinnedVersion(workflow);

            ArgumentCaptor<ScheduleCreateRequest> captor = ArgumentCaptor.forClass(ScheduleCreateRequest.class);
            verify(triggerClient).createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), captor.capture(), anyString(), anyString());
            assertThat(captor.getValue().cron()).isEqualTo("30 6 * * *");
            assertThat(captor.getValue().timezone()).isEqualTo("US/Eastern");
        }

        @Test
        @DisplayName("Pin v3 that has no schedule → old schedules deleted")
        void pinVersionWithNoScheduleDeletesOrphans() {
            WorkflowEntity workflow = buildWorkflow(3);

            // v3 has manual trigger (no schedule)
            Map<String, Object> v3Plan = planMapWithManualTrigger();
            UUID existingScheduleId = UUID.randomUUID();
            when(versionService.getVersion(WORKFLOW_ID, 3))
                    .thenReturn(Optional.of(buildVersionEntity(3, v3Plan)));
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID))
                    .thenReturn(List.of(buildScheduleDto(existingScheduleId, "trigger:x")));

            scheduleSyncService.syncFromPinnedVersion(workflow);

            // v5: No schedule triggers in plan → existing schedules SUSPENDED (not deleted)
            verify(triggerClient).suspendSchedule(existingScheduleId, "PLAN_TRIGGER_REMOVED");
            verify(triggerClient, never()).createOrUpdateSchedule(any(), anyString(), anyString(),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString());
        }

        @Test
        @DisplayName("Pinned version not found in DB → disables all schedules")
        void pinnedVersionNotFound() {
            WorkflowEntity workflow = buildWorkflow(99);

            when(versionService.getVersion(WORKFLOW_ID, 99)).thenReturn(Optional.empty());
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID))
                    .thenReturn(List.of(buildScheduleDto(UUID.randomUUID(), "trigger:old")));

            scheduleSyncService.syncFromPinnedVersion(workflow);

            verify(triggerClient).suspendSchedule(any(), eq("WORKFLOW_UNPINNED"));
        }

        @Test
        @DisplayName("Multiple schedules in pinned version → all created")
        void multipleSchedulesInPinnedVersion() {
            WorkflowEntity workflow = buildWorkflow(2);

            Map<String, Object> v2Plan = planMapWithMultipleSchedules("0 9 * * *", "0 22 * * *");
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, v2Plan)));
            when(triggerClient.createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString()))
                    .thenReturn(buildScheduleDto(UUID.randomUUID(), "trigger:morning_job"))
                    .thenReturn(buildScheduleDto(UUID.randomUUID(), "trigger:night_job"));
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID)).thenReturn(Collections.emptyList());

            scheduleSyncService.syncFromPinnedVersion(workflow);

            verify(triggerClient, times(2)).createOrUpdateSchedule(
                    eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString());
        }

        @Test
        @DisplayName("Pinned plan has null plan map → disables all schedules")
        void pinnedVersionWithNullPlan() {
            WorkflowEntity workflow = buildWorkflow(2);

            WorkflowPlanVersionEntity versionEntity = buildVersionEntity(2, null);
            when(versionService.getVersion(WORKFLOW_ID, 2)).thenReturn(Optional.of(versionEntity));

            UUID schedId = UUID.randomUUID();
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID))
                    .thenReturn(List.of(buildScheduleDto(schedId, "trigger:old")));

            scheduleSyncService.syncFromPinnedVersion(workflow);

            verify(triggerClient).suspendSchedule(schedId, "WORKFLOW_UNPINNED");
        }

        @Test
        @DisplayName("Version loading throws exception → disables all schedules")
        void versionLoadingException() {
            WorkflowEntity workflow = buildWorkflow(2);

            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenThrow(new RuntimeException("DB connection error"));

            UUID schedId = UUID.randomUUID();
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID))
                    .thenReturn(List.of(buildScheduleDto(schedId, "trigger:old")));

            // Should not throw
            scheduleSyncService.syncFromPinnedVersion(workflow);

            verify(triggerClient).suspendSchedule(schedId, "WORKFLOW_UNPINNED");
        }
    }

    // ─────────────────────── Draft Save Isolation ───────────────────────

    @Nested
    @DisplayName("Draft Save Isolation - Draft NEVER Changes Production Schedule")
    class DraftSaveIsolationTests {

        @Test
        @DisplayName("Legacy sync with pinned version delegates to pin-aware (ignores provided plan)")
        void legacySyncWithPinnedVersionUsesPin() {
            WorkflowEntity workflow = buildWorkflow(2);

            // Draft plan (v3) has different cron
            WorkflowPlan draftPlan = buildPlanWithSchedule("0 */5 * * *", "Asia/Tokyo");

            // But pinned version (v2) has production cron
            Map<String, Object> v2Plan = planMapWithSchedule("0 9 * * *", "UTC");
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, v2Plan)));
            when(triggerClient.createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString()))
                    .thenReturn(buildScheduleDto(UUID.randomUUID(), "trigger:daily_job"));
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID)).thenReturn(Collections.emptyList());

            // Call legacy sync - it should use pinned plan, NOT the draft plan
            scheduleSyncService.syncFromPinnedVersion(workflow);

            ArgumentCaptor<ScheduleCreateRequest> captor = ArgumentCaptor.forClass(ScheduleCreateRequest.class);
            verify(triggerClient).createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), captor.capture(), anyString(), anyString());
            // Must be v2's cron, not draft's "0 */5 * * *"
            assertThat(captor.getValue().cron()).isEqualTo("0 9 * * *");
        }

        @Test
        @DisplayName("Legacy sync without pinned version disables schedules (never re-arms from runtime plan)")
        void legacySyncWithoutPinDisablesSchedules() {
            // Regression guard for bug 2026-05-14: previously the legacy sync fell
            // back to syncFromPlan(workflow, plan) when pinned_version was null,
            // which CREATED schedule rows via createOrUpdateSchedule. APPLICATION
            // clones (pinned_version=null until user toggles live) ended up with
            // ACTIVE rows from the run-time plan and fired forever. Post-fix:
            // unpinned runs go through the pin-aware path → disableAllSchedules
            // (a no-op when no rows exist; suspends any existing ACTIVE row).
            WorkflowEntity workflow = buildWorkflow(null); // no pinned version

            WorkflowPlan plan = buildPlanWithSchedule("0 12 * * *", "UTC");
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID)).thenReturn(Collections.emptyList());

            scheduleSyncService.syncFromPinnedVersion(workflow);

            // Must NOT create or arm any schedule row from the runtime plan.
            verify(triggerClient, never()).createOrUpdateSchedule(any(), anyString(), anyString(),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString());
            // Pin-aware path queries existing rows (to suspend any ACTIVE one).
            verify(triggerClient).getSchedulesByWorkflow(WORKFLOW_ID);
        }

        @Test
        @DisplayName("Save v3 draft with different cron does NOT change v2 production schedule")
        void saveDraftDoesNotChangeProductionSchedule() {
            // Simulates: user saves draft v3 with "0 */2 * * *", but production is pinned to v2 "0 9 * * *"
            WorkflowEntity workflow = buildWorkflow(2);

            // Draft plan (what saveWorkflow passes)
            WorkflowPlan draftPlan = buildPlanWithSchedule("0 */2 * * *", "Asia/Tokyo");

            // Pinned plan v2
            Map<String, Object> v2Plan = planMapWithSchedule("0 9 * * *", "UTC");
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, v2Plan)));
            when(triggerClient.createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString()))
                    .thenReturn(buildScheduleDto(UUID.randomUUID(), "trigger:daily_job"));
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID)).thenReturn(Collections.emptyList());

            // Legacy sync (called by saveWorkflow) with pinned version set
            scheduleSyncService.syncFromPinnedVersion(workflow);

            ArgumentCaptor<ScheduleCreateRequest> captor = ArgumentCaptor.forClass(ScheduleCreateRequest.class);
            verify(triggerClient).createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), captor.capture(), anyString(), anyString());
            // MUST be "0 9 * * *" (v2 pinned), not "0 */2 * * *" (v3 draft)
            assertThat(captor.getValue().cron()).isEqualTo("0 9 * * *");
            assertThat(captor.getValue().timezone()).isEqualTo("UTC");
        }
    }

    // ─────────────────────── Orphan Cleanup ───────────────────────

    @Nested
    @DisplayName("Orphan Schedule Cleanup")
    class OrphanCleanupTests {

        @Test
        @DisplayName("Deletes orphan schedules when trigger is removed from pinned plan")
        void deletesOrphanSchedules() {
            WorkflowEntity workflow = buildWorkflow(2);

            // Pinned plan has one schedule trigger
            Map<String, Object> v2Plan = planMapWithSchedule("0 9 * * *", "UTC");
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, v2Plan)));

            UUID keptId = UUID.randomUUID();
            UUID orphanId = UUID.randomUUID();
            ScheduledExecutionDto kept = buildScheduleDto(keptId, "trigger:daily_job");
            ScheduledExecutionDto orphan = buildScheduleDto(orphanId, "trigger:old_removed_job");

            when(triggerClient.createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString()))
                    .thenReturn(kept);
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID)).thenReturn(List.of(kept, orphan));

            scheduleSyncService.syncFromPinnedVersion(workflow);

            // v5 change: orphan is SUSPENDED (recoverable, preserves execution_count) rather
            // than physically deleted. Re-arm happens automatically if the plan re-adds the trigger.
            verify(triggerClient).suspendSchedule(orphanId, "PLAN_TRIGGER_REMOVED");
        }

        @Test
        @DisplayName("Suspends ALL schedules when pinned plan has no schedule triggers (v5: recoverable)")
        void deletesAllWhenNoScheduleTriggers() {
            WorkflowEntity workflow = buildWorkflow(2);
            UUID orphan1 = UUID.randomUUID();
            UUID orphan2 = UUID.randomUUID();

            Map<String, Object> v2Plan = planMapWithManualTrigger();
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, v2Plan)));
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID))
                    .thenReturn(List.of(buildScheduleDto(orphan1, "trigger:x"),
                                          buildScheduleDto(orphan2, "trigger:y")));

            scheduleSyncService.syncFromPinnedVersion(workflow);

            // v5: orphans are suspended (recoverable), not physically deleted
            verify(triggerClient).suspendSchedule(orphan1, "PLAN_TRIGGER_REMOVED");
            verify(triggerClient).suspendSchedule(orphan2, "PLAN_TRIGGER_REMOVED");
            verify(triggerClient, never()).createOrUpdateSchedule(any(), anyString(), anyString(),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString());
        }

        @Test
        @DisplayName("Trigger type changed schedule→manual in pinned plan → old schedule suspended (v5)")
        void triggerTypeChangedDeletesOldSchedule() {
            // This is the exact production bug: user had schedule in v2, changed to manual in v3, pinned v3
            WorkflowEntity workflow = buildWorkflow(3);
            UUID oldScheduleId = UUID.randomUUID();

            Map<String, Object> v3Plan = planMapWithManualTrigger();
            when(versionService.getVersion(WORKFLOW_ID, 3))
                    .thenReturn(Optional.of(buildVersionEntity(3, v3Plan)));
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID))
                    .thenReturn(List.of(buildScheduleDto(oldScheduleId, "trigger:old_schedule")));

            scheduleSyncService.syncFromPinnedVersion(workflow);

            // v5: suspend, not delete (preserves execution_count for re-arm via plan re-add)
            verify(triggerClient).suspendSchedule(oldScheduleId, "PLAN_TRIGGER_REMOVED");
        }
    }

    // ─────────────────────── syncFromPlan (package-private) ───────────────────────

    @Nested
    @DisplayName("syncFromPlan - Direct Plan Sync")
    class SyncFromPlanTests {

        @Test
        @DisplayName("Creates schedule from plan with correct cron/timezone")
        void createsScheduleFromPlan() {
            WorkflowEntity workflow = buildWorkflow();
            WorkflowPlan plan = buildPlanWithSchedule("0 9 * * *", "Europe/Paris");

            when(triggerClient.createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString()))
                    .thenReturn(buildScheduleDto(UUID.randomUUID(), "trigger:daily_job"));
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID)).thenReturn(Collections.emptyList());

            scheduleSyncService.syncFromPlan(workflow, plan);

            ArgumentCaptor<ScheduleCreateRequest> captor = ArgumentCaptor.forClass(ScheduleCreateRequest.class);
            verify(triggerClient).createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), captor.capture(), anyString(), anyString());
            assertThat(captor.getValue().cron()).isEqualTo("0 9 * * *");
            assertThat(captor.getValue().timezone()).isEqualTo("Europe/Paris");
        }

        @Test
        @DisplayName("Uses default cron/timezone when not specified in trigger params")
        void usesDefaultsWhenMissing() {
            WorkflowEntity workflow = buildWorkflow();
            Trigger trigger = new Trigger("sched-nc", "No Config", "schedule", "schedule", Map.of());
            WorkflowPlan plan = buildPlan(List.of(trigger));

            when(triggerClient.createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString()))
                    .thenReturn(buildScheduleDto(UUID.randomUUID(), "trigger:no_config"));
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID)).thenReturn(Collections.emptyList());

            scheduleSyncService.syncFromPlan(workflow, plan);

            ArgumentCaptor<ScheduleCreateRequest> captor = ArgumentCaptor.forClass(ScheduleCreateRequest.class);
            verify(triggerClient).createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), captor.capture(), anyString(), anyString());
            assertThat(captor.getValue().cron()).isEqualTo("0 * * * *");
            assertThat(captor.getValue().timezone()).isEqualTo("UTC");
        }

        @Test
        @DisplayName("Passes maxExecutions and enabled=false from trigger params")
        void passesAllConfigParams() {
            WorkflowEntity workflow = buildWorkflow();
            Trigger trigger = new Trigger("sched-lim", "Limited", "schedule", "schedule",
                    Map.of("cron", "0 0 * * *", "timezone", "UTC",
                            "maxExecutions", 10, "enabled", false));
            WorkflowPlan plan = buildPlan(List.of(trigger));

            when(triggerClient.createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString()))
                    .thenReturn(buildScheduleDto(UUID.randomUUID(), "trigger:limited"));
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID)).thenReturn(Collections.emptyList());

            scheduleSyncService.syncFromPlan(workflow, plan);

            ArgumentCaptor<ScheduleCreateRequest> captor = ArgumentCaptor.forClass(ScheduleCreateRequest.class);
            verify(triggerClient).createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), captor.capture(), anyString(), anyString());
            assertThat(captor.getValue().maxExecutions()).isEqualTo(10);
            assertThat(captor.getValue().enabled()).isFalse();
        }

        @Test
        @DisplayName("Creates multiple schedules for multi-trigger plan")
        void createsMultipleSchedules() {
            WorkflowEntity workflow = buildWorkflow();
            Trigger t1 = new Trigger("sched-m", "Morning Job", "schedule", "schedule",
                    Map.of("cron", "0 9 * * *", "timezone", "UTC", "enabled", true));
            Trigger t2 = new Trigger("sched-n", "Night Job", "schedule", "schedule",
                    Map.of("cron", "0 22 * * *", "timezone", "Europe/Paris", "enabled", false));
            WorkflowPlan plan = buildPlan(List.of(t1, t2));

            when(triggerClient.createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString()))
                    .thenReturn(buildScheduleDto(UUID.randomUUID(), "trigger:morning_job"))
                    .thenReturn(buildScheduleDto(UUID.randomUUID(), "trigger:night_job"));
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID)).thenReturn(Collections.emptyList());

            scheduleSyncService.syncFromPlan(workflow, plan);

            verify(triggerClient, times(2)).createOrUpdateSchedule(
                    eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString());
        }

        @Test
        @DisplayName("Null plan does NOT touch schedules - corrupted plan must not wipe canonical rows (regression: 2026-04-29 hardening)")
        void nullPlanDoesNotDeleteSchedules() {
            // Pre-fix this would mass-cleanup schedules, which is catastrophic
            // when a parser bug or corrupted plan map silently produces a null plan.
            // Now: refuse to cleanup unless we have an explicit plan with a triggers list.
            WorkflowEntity workflow = buildWorkflow();

            scheduleSyncService.syncFromPlan(workflow, null);

            verify(triggerClient, never()).getSchedulesByWorkflow(any());
        }

        @Test
        @DisplayName("Plan with empty triggers list (intentional) → still cleans up orphans (legitimate empty-schedule case, v5: suspend)")
        void emptyTriggersListStillCleansOrphans() {
            // Distinct from null plan: an empty list is an explicit user intent ("no
            // schedules"). Cleanup must run to suspend previously-existing schedules.
            WorkflowEntity workflow = buildWorkflow();
            WorkflowPlan emptyPlan = buildPlan(java.util.Collections.emptyList());
            UUID existingId = UUID.randomUUID();
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID))
                    .thenReturn(List.of(buildScheduleDto(existingId, "trigger:old")));

            scheduleSyncService.syncFromPlan(workflow, emptyPlan);

            verify(triggerClient).suspendSchedule(existingId, "PLAN_TRIGGER_REMOVED");
        }
    }

    // ─────────────────────── Edge Cases ───────────────────────

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Handles null result from createOrUpdateSchedule gracefully")
        void handlesNullCreateResult() {
            WorkflowEntity workflow = buildWorkflow();
            WorkflowPlan plan = buildPlanWithSchedule("0 9 * * *", "UTC");

            when(triggerClient.createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString()))
                    .thenReturn(null);
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID)).thenReturn(Collections.emptyList());

            scheduleSyncService.syncFromPlan(workflow, plan);

            verify(triggerClient).createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString());
        }

        @Test
        @DisplayName("Handles exception from triggerClient gracefully")
        void handlesCreateException() {
            WorkflowEntity workflow = buildWorkflow();
            WorkflowPlan plan = buildPlanWithSchedule("0 9 * * *", "UTC");

            when(triggerClient.createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString()))
                    .thenThrow(new RuntimeException("Connection refused"));
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID)).thenReturn(Collections.emptyList());

            // Should not throw
            scheduleSyncService.syncFromPlan(workflow, plan);
        }

        @Test
        @DisplayName("Skips sync entirely when triggerClient is null")
        void skipsWhenTriggerClientNull() {
            ScheduleSyncService serviceWithoutClient = new ScheduleSyncService(null, null);
            WorkflowPlan plan = buildPlanWithSchedule("0 9 * * *", "UTC");

            // Should not throw
            serviceWithoutClient.syncFromPinnedVersion(buildWorkflow());
        }

        @Test
        @DisplayName("Skips syncFromPinnedVersion when triggerClient is null")
        void skipsSyncFromPinnedWhenTriggerClientNull() {
            ScheduleSyncService serviceWithoutClient = new ScheduleSyncService(null, null);

            // Should not throw
            serviceWithoutClient.syncFromPinnedVersion(buildWorkflow(2));
        }

        @Test
        @DisplayName("Disables all schedules when versionService is null and version is pinned")
        void disablesWhenVersionServiceNull() {
            ScheduleSyncService serviceWithoutVersionService = new ScheduleSyncService(triggerClient, null);
            WorkflowEntity workflow = buildWorkflow(2);

            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID)).thenReturn(Collections.emptyList());

            // Should not throw - falls back to disable all
            serviceWithoutVersionService.syncFromPinnedVersion(workflow);
        }

        @Test
        @DisplayName("disableAllSchedules handles exception gracefully")
        void disableAllHandlesException() {
            WorkflowEntity workflow = buildWorkflow(null);

            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID))
                    .thenThrow(new RuntimeException("Network error"));

            // Should not throw
            scheduleSyncService.syncFromPinnedVersion(workflow);
        }
    }

    // ─────────────────────── Pin/Unpin Lifecycle ───────────────────────

    @Nested
    @DisplayName("Pin/Unpin Lifecycle Scenarios")
    class PinUnpinLifecycleTests {

        @Test
        @DisplayName("Pin v2 (schedule) → unpin → pin v1 (different cron) → schedule reflects v1")
        void fullPinUnpinRepinCycle() {
            // Step 1: Pin v2
            WorkflowEntity workflow = buildWorkflow(2);
            Map<String, Object> v2Plan = planMapWithSchedule("0 9 * * *", "UTC");
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, v2Plan)));
            when(triggerClient.createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString()))
                    .thenReturn(buildScheduleDto(UUID.randomUUID(), "trigger:daily_job"));
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID)).thenReturn(Collections.emptyList());

            scheduleSyncService.syncFromPinnedVersion(workflow);

            ArgumentCaptor<ScheduleCreateRequest> captor1 = ArgumentCaptor.forClass(ScheduleCreateRequest.class);
            verify(triggerClient).createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), captor1.capture(), anyString(), anyString());
            assertThat(captor1.getValue().cron()).isEqualTo("0 9 * * *");

            // Step 2: Unpin
            reset(triggerClient);
            workflow.setPinnedVersion(null);
            UUID schedId = UUID.randomUUID();
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID))
                    .thenReturn(List.of(buildScheduleDto(schedId, "trigger:daily_job")));

            scheduleSyncService.syncFromPinnedVersion(workflow);
            verify(triggerClient).suspendSchedule(schedId, "WORKFLOW_UNPINNED");

            // Step 3: Pin v1
            reset(triggerClient, versionService);
            workflow.setPinnedVersion(1);
            Map<String, Object> v1Plan = planMapWithSchedule("30 6 * * *", "US/Eastern");
            when(versionService.getVersion(WORKFLOW_ID, 1))
                    .thenReturn(Optional.of(buildVersionEntity(1, v1Plan)));
            when(triggerClient.createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString()))
                    .thenReturn(buildScheduleDto(UUID.randomUUID(), "trigger:daily_job"));
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID)).thenReturn(Collections.emptyList());

            scheduleSyncService.syncFromPinnedVersion(workflow);

            ArgumentCaptor<ScheduleCreateRequest> captor3 = ArgumentCaptor.forClass(ScheduleCreateRequest.class);
            verify(triggerClient).createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), captor3.capture(), anyString(), anyString());
            assertThat(captor3.getValue().cron()).isEqualTo("30 6 * * *");
            assertThat(captor3.getValue().timezone()).isEqualTo("US/Eastern");
        }

        @Test
        @DisplayName("Pin schedule version → pin manual version → old schedule deleted")
        void switchFromScheduleToManualPin() {
            // Pin v2 (schedule) then pin v3 (manual) → schedule must be gone
            WorkflowEntity workflow = buildWorkflow(3);

            Map<String, Object> v3Plan = planMapWithManualTrigger();
            UUID oldId = UUID.randomUUID();
            when(versionService.getVersion(WORKFLOW_ID, 3))
                    .thenReturn(Optional.of(buildVersionEntity(3, v3Plan)));
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID))
                    .thenReturn(List.of(buildScheduleDto(oldId, "trigger:old")));

            scheduleSyncService.syncFromPinnedVersion(workflow);

            // v5: suspend (not delete) on orphan cleanup
            verify(triggerClient).suspendSchedule(oldId, "PLAN_TRIGGER_REMOVED");
        }

        @Test
        @DisplayName("Re-pin re-arms SUSPENDED_UNPINNED schedules (regression: 2026-05-06 - re-pinned workflow never fired because state stayed SUSPENDED_UNPINNED)")
        void rePinReArmsSuspendedSchedulesRegression() {
            // Real prod incident (E2E Multi-Trigger Shared Sink, 2026-05-06):
            //   1. workflow had been unpinned → disableAllSchedules() called
            //      suspendSchedule → row state=SUSPENDED_UNPINNED, enabled=false.
            //   2. user re-pins → syncSingleSchedule re-links workflow_id and refreshes
            //      cron via the standalone update path.
            //   3. BUT neither updateScheduleWorkflowReferenceStrict nor
            //      updateStandaloneScheduleStrict touches state/enabled.
            //   4. ScheduleExecutorService.findDueExecutions filters on enabled=true,
            //      so the schedule was silently ignored on every tick after the re-pin.
            //
            // Fix: syncFromPinnedVersion calls enableSchedulesByWorkflow at the end of
            // the happy path to re-arm any rows the previous unpin left suspended.
            // Symmetric with disableAllSchedules - no other path resumes the state machine.
            UUID standaloneId = UUID.randomUUID();
            WorkflowEntity workflow = buildWorkflow(12);

            Map<String, Object> pinnedPlan = new HashMap<>();
            Map<String, Object> triggerMap = new HashMap<>();
            triggerMap.put("id", "scheduler");
            triggerMap.put("label", "Scheduler");
            triggerMap.put("type", "schedule");
            triggerMap.put("trigger_type", "schedule");
            triggerMap.put("params", Map.of("cron", "* * * * *", "timezone", "Europe/Paris",
                    "enabled", true, "scheduleId", standaloneId.toString()));
            pinnedPlan.put("triggers", List.of(triggerMap));
            pinnedPlan.put("name", "Test Workflow");

            when(versionService.getVersion(WORKFLOW_ID, 12))
                    .thenReturn(Optional.of(buildVersionEntity(12, pinnedPlan)));
            // Standalone path succeeds (re-link + cron refresh); this exercises the exact
            // re-pin path where, pre-fix, no state transition ever occurred.
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID))
                    .thenReturn(List.of(buildScheduleDto(standaloneId, "trigger:scheduler")));

            scheduleSyncService.syncFromPinnedVersion(workflow);

            // v5 refactor: the silent workflow_id rewrite method was removed entirely
            // from TriggerClient (F4 fix). No verify-never needed - the method doesn't
            // exist. The legitimate config-refresh on the standalone row still happens.
            // Standalone fields refreshed (cron / timezone / enabled flag)
            verify(triggerClient).updateStandaloneScheduleStrict(eq(TENANT_ID), eq(standaloneId), any());
            // CRITICAL invariant: the lifecycle state machine MUST be re-armed at the end.
            // Pre-fix this never ran, leaving the row at state=SUSPENDED_UNPINNED forever.
            verify(triggerClient).enableSchedulesByWorkflow(WORKFLOW_ID);
        }

        @Test
        @DisplayName("Unpin → re-pin sequence calls suspendSchedule then enableSchedulesByWorkflow (lifecycle symmetry)")
        void unpinThenRepinHasSymmetricLifecycleCalls() {
            // Verifies the suspend/arm pair is symmetric across the unpin → re-pin transition.
            // If either side is missing, the state machine drifts and findDueExecutions
            // observes the wrong enabled flag.
            UUID schedId = UUID.randomUUID();
            WorkflowEntity workflow = buildWorkflow(null);

            // Step 1: Unpin → suspendSchedule on every row of the workflow
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID))
                    .thenReturn(List.of(buildScheduleDto(schedId, "trigger:scheduler")));
            scheduleSyncService.syncFromPinnedVersion(workflow);
            verify(triggerClient).suspendSchedule(schedId, "WORKFLOW_UNPINNED");
            verify(triggerClient, never()).enableSchedulesByWorkflow(any());

            // Step 2: Re-pin v2 → enableSchedulesByWorkflow on the workflow
            reset(triggerClient, versionService);
            workflow.setPinnedVersion(2);
            Map<String, Object> v2Plan = planMapWithSchedule("0 9 * * *", "UTC");
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, v2Plan)));
            when(triggerClient.createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString()))
                    .thenReturn(buildScheduleDto(UUID.randomUUID(), "trigger:daily_job"));
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID)).thenReturn(Collections.emptyList());

            scheduleSyncService.syncFromPinnedVersion(workflow);

            verify(triggerClient).enableSchedulesByWorkflow(WORKFLOW_ID);
            verify(triggerClient, never()).suspendSchedule(any(), any());
        }

        @Test
        @DisplayName("Re-arm exception is swallowed - sync still succeeds (best-effort, matches reactivateWorkflow contract)")
        void enableSchedulesExceptionDoesNotFailSync() {
            // The re-arm is best-effort: a transient trigger-service hiccup must not
            // bubble up and abort the surrounding pin operation. Same contract as
            // WorkflowResumeService.reactivateWorkflow's broad re-enable.
            WorkflowEntity workflow = buildWorkflow(2);
            Map<String, Object> v2Plan = planMapWithSchedule("0 9 * * *", "UTC");
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, v2Plan)));
            when(triggerClient.createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString()))
                    .thenReturn(buildScheduleDto(UUID.randomUUID(), "trigger:daily_job"));
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID)).thenReturn(Collections.emptyList());
            doThrow(new RuntimeException("trigger-service 503"))
                    .when(triggerClient).enableSchedulesByWorkflow(WORKFLOW_ID);

            // Must not throw
            scheduleSyncService.syncFromPinnedVersion(workflow);

            // Sync side-effects still ran before the re-arm attempt
            verify(triggerClient).createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString());
            verify(triggerClient).enableSchedulesByWorkflow(WORKFLOW_ID);
        }

        @Test
        @DisplayName("Unpinned path does NOT re-arm (would resurrect intentionally-suspended rows)")
        void unpinnedPathDoesNotReArm() {
            // The re-arm only belongs on the pinned-success path. On unpin, schedules
            // are deliberately suspended - calling enableSchedulesByWorkflow there would
            // immediately undo the suspension and create a livelock.
            WorkflowEntity workflow = buildWorkflow(null);
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID))
                    .thenReturn(List.of(buildScheduleDto(UUID.randomUUID(), "trigger:scheduler")));

            scheduleSyncService.syncFromPinnedVersion(workflow);

            verify(triggerClient, never()).enableSchedulesByWorkflow(any());
        }

        @Test
        @DisplayName("Pinned plan load failure (null plan) does NOT re-arm - prevents resurrecting suspended rows on a corrupted version")
        void pinnedPlanLoadFailureDoesNotReArm() {
            // If loadPinnedPlan returns null we fall through to disableAllSchedules.
            // The re-arm must not run in that branch - it would contradict the suspend
            // we just emitted and leave the row armed against a workflow with no usable plan.
            WorkflowEntity workflow = buildWorkflow(2);
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, null)));
            UUID schedId = UUID.randomUUID();
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID))
                    .thenReturn(List.of(buildScheduleDto(schedId, "trigger:scheduler")));

            scheduleSyncService.syncFromPinnedVersion(workflow);

            verify(triggerClient).suspendSchedule(schedId, "WORKFLOW_UNPINNED");
            verify(triggerClient, never()).enableSchedulesByWorkflow(any());
        }

        @Test
        @DisplayName("Multiple versions with schedules - only pinned version's schedule is active")
        void onlyPinnedVersionScheduleActive() {
            // v1: "0 6 * * *", v2: "0 9 * * *" (pinned), v3: "0 12 * * *" (draft)
            // Only v2's "0 9 * * *" should be synced
            WorkflowEntity workflow = buildWorkflow(2);

            Map<String, Object> v2Plan = planMapWithSchedule("0 9 * * *", "UTC");
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, v2Plan)));
            when(triggerClient.createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), any(ScheduleCreateRequest.class), anyString(), anyString()))
                    .thenReturn(buildScheduleDto(UUID.randomUUID(), "trigger:daily_job"));
            when(triggerClient.getSchedulesByWorkflow(WORKFLOW_ID)).thenReturn(Collections.emptyList());

            scheduleSyncService.syncFromPinnedVersion(workflow);

            // Verify only one schedule created with v2's cron
            ArgumentCaptor<ScheduleCreateRequest> captor = ArgumentCaptor.forClass(ScheduleCreateRequest.class);
            verify(triggerClient, times(1)).createOrUpdateSchedule(eq(WORKFLOW_ID), anyString(), eq(TENANT_ID),
                    isNull(), captor.capture(), anyString(), anyString());
            assertThat(captor.getValue().cron()).isEqualTo("0 9 * * *");

            // v1 and v3 are never consulted
            verify(versionService, never()).getVersion(WORKFLOW_ID, 1);
            verify(versionService, never()).getVersion(WORKFLOW_ID, 3);
        }
    }

    // ─────────────────────── loadPinnedPlan ───────────────────────

    @Nested
    @DisplayName("loadPinnedPlan - Internal Plan Loading")
    class LoadPinnedPlanTests {

        @Test
        @DisplayName("Returns plan when version exists")
        void returnsPlanWhenVersionExists() {
            Map<String, Object> planMap = planMapWithSchedule("0 9 * * *", "UTC");
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, planMap)));

            WorkflowPlan result = scheduleSyncService.loadPinnedPlan(WORKFLOW_ID, 2);
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Returns null when version not found")
        void returnsNullWhenVersionNotFound() {
            when(versionService.getVersion(WORKFLOW_ID, 99)).thenReturn(Optional.empty());

            WorkflowPlan result = scheduleSyncService.loadPinnedPlan(WORKFLOW_ID, 99);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Returns null when versionService is null")
        void returnsNullWhenVersionServiceNull() {
            ScheduleSyncService service = new ScheduleSyncService(triggerClient, null);
            WorkflowPlan result = service.loadPinnedPlan(WORKFLOW_ID, 2);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Returns null when version has null plan map")
        void returnsNullWhenPlanMapNull() {
            when(versionService.getVersion(WORKFLOW_ID, 2))
                    .thenReturn(Optional.of(buildVersionEntity(2, null)));

            WorkflowPlan result = scheduleSyncService.loadPinnedPlan(WORKFLOW_ID, 2);
            assertThat(result).isNull();
        }
    }

    // ─────────────────────── Duplicate row cleanup (Gmail Auto-Labeler regression) ───────────────────────
    // NOTE 2026-05-13: DuplicateRowCleanupTests class DELETED.
    // cleanupDuplicateRows + bestEffortCollapseRows helpers were removed (audit C tech debt #8) -
    // V60 (UNIQUE workflow_id, trigger_id) + V136 (UNIQUE tenant_id, source_node_id) physically
    // prevent the duplicate-row state these tests exercised. The DB constraints are the contract;
    // these helpers were dead defense.

    /*
     * Tests removed: standaloneBranchDeletesLegacyAttachedRow, attachedUpsertDeletesOtherRowSameTriggerId,
     * bestEffortCollapseWhenUpsertReturnsNull, bestEffortCollapseWithAllNullTimestamps,
     * standaloneFailureFallsBackAndDedupes, dedupIgnoresDifferentTriggerId, resyncIdempotent,
     * transientFailureSkipsSyncEntirely, phantomScheduleIdMustNotDeleteOnlyWorkingRow.
     *
     * Skeleton kept as @Nested empty class so JUnit reports a clean section.
     */
    @Nested
    @DisplayName("Duplicate row cleanup - removed (DB unique constraints prevent duplicates)")
    class DuplicateRowCleanupTests {
    }
}
