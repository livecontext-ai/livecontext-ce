package com.apimarketplace.orchestrator.services;

import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for WorkflowPlanVersionService - the versioning system.
 *
 * Tests cover:
 * - createVersion: new version creation, change detection, label support
 * - plansAreEqual: deep comparison, transient field stripping
 * - Retry logic on version number collision
 * - No-op when plan unchanged
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPlanVersionService")
class WorkflowPlanVersionServiceTest {

    @Mock
    private WorkflowPlanVersionRepository versionRepository;

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private StorageBreakdownService breakdownService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WorkflowPlanVersionService service;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String USER_ID = "user-123";

    @BeforeEach
    void setUp() {
        service = new WorkflowPlanVersionService(versionRepository,
                workflowRepository, breakdownService, objectMapper);
        ReflectionTestUtils.setField(service, "maxVersions", 20);
    }

    // =========================================================================
    // createVersion - new version creation
    // =========================================================================

    @Nested
    @DisplayName("createVersion")
    class CreateVersionTests {

        @Test
        @DisplayName("Should create v1 when no versions exist")
        void shouldCreateV1WhenNoVersionsExist() {
            Map<String, Object> plan = Map.of("name", "My Workflow", "mcps", List.of());

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.empty());
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            int version = service.createVersion(WORKFLOW_ID, plan, USER_ID);

            assertThat(version).isEqualTo(1);

            ArgumentCaptor<WorkflowPlanVersionEntity> captor = ArgumentCaptor.forClass(WorkflowPlanVersionEntity.class);
            verify(versionRepository).save(captor.capture());
            WorkflowPlanVersionEntity saved = captor.getValue();
            assertThat(saved.getWorkflowId()).isEqualTo(WORKFLOW_ID);
            assertThat(saved.getVersion()).isEqualTo(1);
            assertThat(saved.getPlan()).isEqualTo(plan);
            assertThat(saved.getCreatedBy()).isEqualTo(USER_ID);
            assertThat(saved.getLabel()).isNull();
        }

        @Test
        @DisplayName("Should create v2 when plan differs from latest version")
        void shouldCreateV2WhenPlanDiffers() {
            Map<String, Object> oldPlan = Map.of("name", "Old", "mcps", List.of());
            Map<String, Object> newPlan = Map.of("name", "New", "mcps", List.of());

            WorkflowPlanVersionEntity v1 = new WorkflowPlanVersionEntity(WORKFLOW_ID, 1, oldPlan, USER_ID);

            // First call: check existing max (returns 1)
            // Second call in retry loop: also returns 1, so nextVersion = 2
            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(1));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 1)).thenReturn(Optional.of(v1));
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(versionRepository.countByWorkflowId(WORKFLOW_ID)).thenReturn(2L);

            int version = service.createVersion(WORKFLOW_ID, newPlan, USER_ID);

            assertThat(version).isEqualTo(2);
            verify(versionRepository).save(any());
        }

        @Test
        @DisplayName("Should skip version creation when plan is unchanged")
        void shouldSkipWhenPlanUnchanged() {
            Map<String, Object> plan = new HashMap<>(Map.of("name", "Same", "mcps", List.of()));

            WorkflowPlanVersionEntity v1 = new WorkflowPlanVersionEntity(WORKFLOW_ID, 1, plan, USER_ID);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(1));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 1)).thenReturn(Optional.of(v1));

            int version = service.createVersion(WORKFLOW_ID, plan, USER_ID);

            assertThat(version).isEqualTo(1);
            verify(versionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should ignore tenant_id difference when comparing plans")
        void shouldIgnoreTenantIdDifference() {
            Map<String, Object> planWithTenant = new HashMap<>(Map.of("name", "WF", "tenant_id", "tenant-A"));
            Map<String, Object> planWithDifferentTenant = new HashMap<>(Map.of("name", "WF", "tenant_id", "tenant-B"));

            WorkflowPlanVersionEntity v1 = new WorkflowPlanVersionEntity(WORKFLOW_ID, 1, planWithTenant, USER_ID);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(1));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 1)).thenReturn(Optional.of(v1));

            int version = service.createVersion(WORKFLOW_ID, planWithDifferentTenant, USER_ID);

            assertThat(version).isEqualTo(1);
            verify(versionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should set label when provided")
        void shouldSetLabelWhenProvided() {
            Map<String, Object> plan = Map.of("name", "WF");

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.empty());
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            int version = service.createVersion(WORKFLOW_ID, plan, USER_ID, "Agent session");

            assertThat(version).isEqualTo(1);

            ArgumentCaptor<WorkflowPlanVersionEntity> captor = ArgumentCaptor.forClass(WorkflowPlanVersionEntity.class);
            verify(versionRepository).save(captor.capture());
            assertThat(captor.getValue().getLabel()).isEqualTo("Agent session");
        }

        @Test
        @DisplayName("Should not set label when null")
        void shouldNotSetLabelWhenNull() {
            Map<String, Object> plan = Map.of("name", "WF");

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.empty());
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.createVersion(WORKFLOW_ID, plan, USER_ID, null);

            ArgumentCaptor<WorkflowPlanVersionEntity> captor = ArgumentCaptor.forClass(WorkflowPlanVersionEntity.class);
            verify(versionRepository).save(captor.capture());
            assertThat(captor.getValue().getLabel()).isNull();
        }

        @Test
        @DisplayName("Should not set label when blank")
        void shouldNotSetLabelWhenBlank() {
            Map<String, Object> plan = Map.of("name", "WF");

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.empty());
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.createVersion(WORKFLOW_ID, plan, USER_ID, "   ");

            ArgumentCaptor<WorkflowPlanVersionEntity> captor = ArgumentCaptor.forClass(WorkflowPlanVersionEntity.class);
            verify(versionRepository).save(captor.capture());
            assertThat(captor.getValue().getLabel()).isNull();
        }

        @Test
        @DisplayName("Overload without label delegates correctly")
        void overloadWithoutLabelDelegatesCorrectly() {
            Map<String, Object> plan = Map.of("name", "WF");

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.empty());
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            int version = service.createVersion(WORKFLOW_ID, plan, USER_ID);

            assertThat(version).isEqualTo(1);
            ArgumentCaptor<WorkflowPlanVersionEntity> captor = ArgumentCaptor.forClass(WorkflowPlanVersionEntity.class);
            verify(versionRepository).save(captor.capture());
            assertThat(captor.getValue().getLabel()).isNull();
        }

        @Test
        @DisplayName("Should track storage on version creation")
        void shouldTrackStorageOnVersionCreation() {
            Map<String, Object> plan = Map.of("name", "WF");

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.empty());
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.empty());

            service.createVersion(WORKFLOW_ID, plan, USER_ID);

            // Issue #149 - 4-arg variant threads orgId (null here since workflow not found).
            verify(breakdownService).trackSave(eq(USER_ID), eq("CONFIGURATION"), anyLong(), org.mockito.ArgumentMatchers.isNull());
        }

        @Test
        @DisplayName("Issue #149 - trackSave threads workflow's organizationId when workflow is found")
        void shouldThreadOrgIdFromWorkflow() {
            Map<String, Object> plan = Map.of("name", "WF");
            WorkflowEntity workflow = new WorkflowEntity();
            workflow.setOrganizationId("org-42");

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.empty());
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));

            service.createVersion(WORKFLOW_ID, plan, USER_ID);

            verify(breakdownService).trackSave(eq(USER_ID), eq("CONFIGURATION"), anyLong(), eq("org-42"));
        }

        @Test
        @DisplayName("Should NOT track storage when plan unchanged")
        void shouldNotTrackStorageWhenUnchanged() {
            Map<String, Object> plan = Map.of("name", "WF");
            WorkflowPlanVersionEntity v1 = new WorkflowPlanVersionEntity(WORKFLOW_ID, 1, plan, USER_ID);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(1));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 1)).thenReturn(Optional.of(v1));

            service.createVersion(WORKFLOW_ID, plan, USER_ID);

            verify(breakdownService, never()).trackSave(anyString(), anyString(), anyLong());
            verify(breakdownService, never()).trackSave(anyString(), anyString(), anyLong(), anyString());
        }
    }

    // =========================================================================
    // resolveContentVersionForExecution - execution-time resolution
    // =========================================================================

    @Nested
    @DisplayName("resolveContentVersionForExecution")
    class ResolveContentVersionForExecutionTests {

        @Test
        @DisplayName("Regression 2026-06-11: drifted execution plan OVERWRITES the latest version in place - never mints a new number (v21 stayed v21 on re-fire)")
        void driftedPlanOverwritesLatestVersionInPlace() {
            // Pre-fix, this path called createVersion and a re-fire (play, epoch 2)
            // minted v(max+1) whenever the executing plan differed byte-wise from
            // the stored latest (FE vs JSONB serialization drift).
            Map<String, Object> storedV21 = Map.of("name", "WF", "mcps", List.of(Map.of("label", "A", "params", Map.of("url", "old"))));
            Map<String, Object> executing = Map.of("name", "WF", "mcps", List.of(Map.of("label", "A", "params", Map.of("url", "new"))));
            WorkflowPlanVersionEntity latest = new WorkflowPlanVersionEntity(WORKFLOW_ID, 21, new HashMap<>(storedV21), USER_ID);
            latest.setLabel("user save");

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(21));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 21)).thenReturn(Optional.of(latest));
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(unpinnedWorkflow()));
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            int version = service.resolveContentVersionForExecution(WORKFLOW_ID, executing, USER_ID);

            assertThat(version).isEqualTo(21);
            ArgumentCaptor<WorkflowPlanVersionEntity> captor = ArgumentCaptor.forClass(WorkflowPlanVersionEntity.class);
            verify(versionRepository).save(captor.capture());
            WorkflowPlanVersionEntity saved = captor.getValue();
            // Same row, same number, label preserved, content replaced.
            assertThat(saved.getVersion()).isEqualTo(21);
            assertThat(saved.getPlan()).isEqualTo(executing);
            assertThat(saved.getLabel()).isEqualTo("user save");
        }

        @Test
        @DisplayName("Plan equal to the latest version is a read-only resolve (no write at all)")
        void equalPlanIsReadOnly() {
            Map<String, Object> plan = Map.of("name", "WF", "mcps", List.of());
            WorkflowPlanVersionEntity latest = new WorkflowPlanVersionEntity(WORKFLOW_ID, 7, new HashMap<>(plan), USER_ID);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(7));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 7)).thenReturn(Optional.of(latest));

            int version = service.resolveContentVersionForExecution(WORKFLOW_ID, plan, USER_ID);

            assertThat(version).isEqualTo(7);
            verify(versionRepository, never()).save(any());
            verify(workflowRepository, never()).findById(any());
        }

        @Test
        @DisplayName("Pinned latest version is immutable: drifted content mints a draft version instead of mutating the pinned row")
        void pinnedLatestVersionIsNeverOverwritten() {
            Map<String, Object> storedPinned = Map.of("name", "WF", "mcps", List.of(Map.of("label", "A")));
            Map<String, Object> executing = Map.of("name", "WF", "mcps", List.of(Map.of("label", "A", "params", Map.of("k", "v"))));
            WorkflowPlanVersionEntity pinnedRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 5, new HashMap<>(storedPinned), USER_ID);

            WorkflowEntity workflow = unpinnedWorkflow();
            workflow.setPinnedVersion(5);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(5));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 5)).thenReturn(Optional.of(pinnedRow));
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(versionRepository.countByWorkflowId(WORKFLOW_ID)).thenReturn(6L);

            int version = service.resolveContentVersionForExecution(WORKFLOW_ID, executing, USER_ID);

            // createVersion lane: a NEW v6 row is created; the pinned v5 row's content is untouched.
            assertThat(version).isEqualTo(6);
            assertThat(pinnedRow.getPlan()).isEqualTo(storedPinned);
        }

        @Test
        @DisplayName("Pinned version that is NOT the latest does not block the overwrite - only the pinned row itself is immutable")
        void pinnedNotLatestStillOverwritesLatestInPlace() {
            // Exactness guard: the immutability check must be pinned == max, not <=.
            // Pinned v3 with drafts up to v5: executing drifted content overwrites
            // the v5 draft in place; the pinned v3 row is never involved.
            Map<String, Object> storedV5 = Map.of("name", "WF", "mcps", List.of(Map.of("label", "A")));
            Map<String, Object> executing = Map.of("name", "WF", "mcps", List.of(Map.of("label", "A", "params", Map.of("k", "v"))));
            WorkflowPlanVersionEntity latestDraft = new WorkflowPlanVersionEntity(WORKFLOW_ID, 5, new HashMap<>(storedV5), USER_ID);

            WorkflowEntity workflow = unpinnedWorkflow();
            workflow.setPinnedVersion(3);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(5));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 5)).thenReturn(Optional.of(latestDraft));
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            int version = service.resolveContentVersionForExecution(WORKFLOW_ID, executing, USER_ID);

            assertThat(version).isEqualTo(5);
            assertThat(latestDraft.getPlan()).isEqualTo(executing);
            // No new row minted (save was the in-place overwrite of the v5 entity).
            verify(versionRepository).save(latestDraft);
        }

        @Test
        @DisplayName("Latest row missing despite max>0 (corrupt/purged history) falls back to createVersion")
        void missingLatestRowFallsBackToCreateVersion() {
            Map<String, Object> executing = Map.of("name", "WF", "mcps", List.of());

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(4));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 4)).thenReturn(Optional.empty());
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            int version = service.resolveContentVersionForExecution(WORKFLOW_ID, executing, USER_ID);

            // createVersion lane: dedupe re-checks v4 (still missing) → mints v5.
            assertThat(version).isEqualTo(5);
        }

        @Test
        @DisplayName("Empty history seeds v1 via createVersion")
        void emptyHistorySeedsV1() {
            Map<String, Object> plan = Map.of("name", "WF");

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.empty());
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            int version = service.resolveContentVersionForExecution(WORKFLOW_ID, plan, USER_ID);

            assertThat(version).isEqualTo(1);
        }

        private WorkflowEntity unpinnedWorkflow() {
            WorkflowEntity workflow = new WorkflowEntity();
            workflow.setId(WORKFLOW_ID);
            workflow.setTenantId(USER_ID);
            workflow.setName("WF");
            return workflow;
        }
    }

    // =========================================================================
    // createVersion - retry logic
    // =========================================================================

    @Nested
    @DisplayName("Retry Logic")
    class RetryLogicTests {

        @Test
        @DisplayName("Should retry on version number collision and succeed")
        void shouldRetryOnCollisionAndSucceed() {
            Map<String, Object> plan = Map.of("name", "WF");

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.empty());
            // First save throws collision, second succeeds
            when(versionRepository.save(any()))
                    .thenThrow(new DataIntegrityViolationException("unique constraint"))
                    .thenAnswer(inv -> inv.getArgument(0));

            int version = service.createVersion(WORKFLOW_ID, plan, USER_ID);

            assertThat(version).isEqualTo(1);
            verify(versionRepository, times(2)).save(any());
        }

        @Test
        @DisplayName("Should throw after max retries exhausted")
        void shouldThrowAfterMaxRetries() {
            Map<String, Object> plan = Map.of("name", "WF");

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.empty());
            when(versionRepository.save(any()))
                    .thenThrow(new DataIntegrityViolationException("unique constraint"));

            assertThatThrownBy(() -> service.createVersion(WORKFLOW_ID, plan, USER_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);

            verify(versionRepository, times(3)).save(any());
        }
    }

    // =========================================================================
    // plansAreEqual - deep comparison
    // =========================================================================

    @Nested
    @DisplayName("plansAreEqual")
    class PlansAreEqualTests {

        @Test
        @DisplayName("Same reference returns true")
        void sameReferenceReturnsTrue() {
            Map<String, Object> plan = Map.of("name", "WF");
            assertThat(service.plansAreEqual(plan, plan)).isTrue();
        }

        @Test
        @DisplayName("Null vs non-null returns false")
        void nullVsNonNullReturnsFalse() {
            assertThat(service.plansAreEqual(null, Map.of("name", "WF"))).isFalse();
            assertThat(service.plansAreEqual(Map.of("name", "WF"), null)).isFalse();
        }

        @Test
        @DisplayName("Both null returns true")
        void bothNullReturnsTrue() {
            assertThat(service.plansAreEqual(null, null)).isTrue();
        }

        @Test
        @DisplayName("Equal plans return true")
        void equalPlansReturnTrue() {
            Map<String, Object> plan1 = new HashMap<>(Map.of("name", "WF", "mcps", List.of("a", "b")));
            Map<String, Object> plan2 = new HashMap<>(Map.of("name", "WF", "mcps", List.of("a", "b")));
            assertThat(service.plansAreEqual(plan1, plan2)).isTrue();
        }

        @Test
        @DisplayName("Different plans return false")
        void differentPlansReturnFalse() {
            Map<String, Object> plan1 = Map.of("name", "WF1");
            Map<String, Object> plan2 = Map.of("name", "WF2");
            assertThat(service.plansAreEqual(plan1, plan2)).isFalse();
        }

        @Test
        @DisplayName("Should ignore tenant_id when comparing")
        void shouldIgnoreTenantId() {
            Map<String, Object> plan1 = new HashMap<>(Map.of("name", "WF", "tenant_id", "t1"));
            Map<String, Object> plan2 = new HashMap<>(Map.of("name", "WF", "tenant_id", "t2"));
            assertThat(service.plansAreEqual(plan1, plan2)).isTrue();
        }

        @Test
        @DisplayName("Should detect difference even with same tenant_id")
        void shouldDetectDifferenceWithSameTenantId() {
            Map<String, Object> plan1 = new HashMap<>(Map.of("name", "WF1", "tenant_id", "t1"));
            Map<String, Object> plan2 = new HashMap<>(Map.of("name", "WF2", "tenant_id", "t1"));
            assertThat(service.plansAreEqual(plan1, plan2)).isFalse();
        }

        @Test
        @DisplayName("Should handle nested structures")
        void shouldHandleNestedStructures() {
            Map<String, Object> plan1 = new HashMap<>();
            plan1.put("name", "WF");
            plan1.put("mcps", List.of(Map.of("id", "s1", "label", "Step")));

            Map<String, Object> plan2 = new HashMap<>();
            plan2.put("name", "WF");
            plan2.put("mcps", List.of(Map.of("id", "s1", "label", "Step")));

            assertThat(service.plansAreEqual(plan1, plan2)).isTrue();
        }

        @Test
        @DisplayName("Should detect nested differences")
        void shouldDetectNestedDifferences() {
            Map<String, Object> plan1 = new HashMap<>();
            plan1.put("mcps", List.of(Map.of("id", "s1", "label", "Step A")));

            Map<String, Object> plan2 = new HashMap<>();
            plan2.put("mcps", List.of(Map.of("id", "s1", "label", "Step B")));

            assertThat(service.plansAreEqual(plan1, plan2)).isFalse();
        }

        @Test
        @DisplayName("Should handle Integer vs Long type differences via Jackson normalization")
        void shouldHandleTypeDifferencesViaJackson() {
            Map<String, Object> plan1 = new HashMap<>();
            plan1.put("count", 42);

            Map<String, Object> plan2 = new HashMap<>();
            plan2.put("count", 42);

            assertThat(service.plansAreEqual(plan1, plan2)).isTrue();
        }

        @Test
        @DisplayName("Plans with only tenant_id difference are equal")
        void plansWithOnlyTenantIdDifferenceAreEqual() {
            Map<String, Object> plan1 = new HashMap<>();
            plan1.put("name", "WF");
            plan1.put("tenant_id", "aaa");

            Map<String, Object> plan2 = new HashMap<>();
            plan2.put("name", "WF");
            // No tenant_id at all

            assertThat(service.plansAreEqual(plan1, plan2)).isTrue();
        }
    }

    // =========================================================================
    // Version history scenarios (end-to-end flow)
    // =========================================================================

    @Nested
    @DisplayName("Version History Scenarios")
    class VersionHistoryScenarios {

        @Test
        @DisplayName("Save A → v1, Save B → v2, Save B again → still v2")
        void saveABBScenario() {
            Map<String, Object> planA = Map.of("name", "Plan A");
            Map<String, Object> planB = Map.of("name", "Plan B");

            // Save A → v1
            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.empty());
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            int v1 = service.createVersion(WORKFLOW_ID, planA, USER_ID);
            assertThat(v1).isEqualTo(1);

            // Save B → v2
            WorkflowPlanVersionEntity v1Entity = new WorkflowPlanVersionEntity(WORKFLOW_ID, 1, planA, USER_ID);
            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(1));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 1)).thenReturn(Optional.of(v1Entity));
            when(versionRepository.countByWorkflowId(WORKFLOW_ID)).thenReturn(2L);

            int v2 = service.createVersion(WORKFLOW_ID, planB, USER_ID);
            assertThat(v2).isEqualTo(2);

            // Save B again → still v2 (no change)
            WorkflowPlanVersionEntity v2Entity = new WorkflowPlanVersionEntity(WORKFLOW_ID, 2, planB, USER_ID);
            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(2));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 2)).thenReturn(Optional.of(v2Entity));

            int v2Again = service.createVersion(WORKFLOW_ID, planB, USER_ID);
            assertThat(v2Again).isEqualTo(2);

            // Verify save was called exactly twice (v1 + v2), not a third time
            verify(versionRepository, times(2)).save(any());
        }

        @Test
        @DisplayName("AI Builder: save with Agent session label")
        void aiBuilderSaveWithLabel() {
            Map<String, Object> plan = Map.of("name", "AI WF", "mcps", List.of());

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.empty());
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            int version = service.createVersion(WORKFLOW_ID, plan, USER_ID, "Agent session");

            assertThat(version).isEqualTo(1);
            ArgumentCaptor<WorkflowPlanVersionEntity> captor = ArgumentCaptor.forClass(WorkflowPlanVersionEntity.class);
            verify(versionRepository).save(captor.capture());
            assertThat(captor.getValue().getLabel()).isEqualTo("Agent session");
        }

        @Test
        @DisplayName("Run auto-save: canvas differs from latest version → new version")
        void runAutoSaveCreatesNewVersion() {
            Map<String, Object> savedPlan = Map.of("name", "Saved");
            Map<String, Object> canvasPlan = Map.of("name", "Modified on canvas");

            WorkflowPlanVersionEntity v1 = new WorkflowPlanVersionEntity(WORKFLOW_ID, 1, savedPlan, USER_ID);
            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(1));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 1)).thenReturn(Optional.of(v1));
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(versionRepository.countByWorkflowId(WORKFLOW_ID)).thenReturn(2L);

            int version = service.createVersion(WORKFLOW_ID, canvasPlan, USER_ID);

            assertThat(version).isEqualTo(2);
            verify(versionRepository).save(any());
        }

        @Test
        @DisplayName("Run without changes: no new version")
        void runWithoutChangesNoNewVersion() {
            Map<String, Object> plan = Map.of("name", "Same");

            WorkflowPlanVersionEntity v1 = new WorkflowPlanVersionEntity(WORKFLOW_ID, 1, plan, USER_ID);
            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(1));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 1)).thenReturn(Optional.of(v1));

            int version = service.createVersion(WORKFLOW_ID, plan, USER_ID);

            assertThat(version).isEqualTo(1);
            verify(versionRepository, never()).save(any());
        }
    }

    // =========================================================================
    // createOrUpdateSessionVersion - session-scoped versioning
    // =========================================================================

    @Nested
    @DisplayName("createOrUpdateSessionVersion - session-scoped dedup")
    class SessionVersionTests {

        private static final String SESSION_A = "wb_aaa111bbb222";
        private static final String SESSION_B = "wb_xxx999yyy888";

        @Test
        @DisplayName("No versions exist → creates new version with sessionId as label")
        void noVersions_createsNew() {
            Map<String, Object> plan = Map.of("name", "WF");

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.empty());
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            int version = service.createOrUpdateSessionVersion(WORKFLOW_ID, plan, USER_ID, SESSION_A);

            assertThat(version).isEqualTo(1);
            ArgumentCaptor<WorkflowPlanVersionEntity> captor = ArgumentCaptor.forClass(WorkflowPlanVersionEntity.class);
            verify(versionRepository).save(captor.capture());
            assertThat(captor.getValue().getLabel()).isEqualTo(SESSION_A);
        }

        @Test
        @DisplayName("Latest version has same sessionId → overwrites plan in-place")
        void sameSession_overwritesInPlace() {
            Map<String, Object> oldPlan = Map.of("name", "Old");
            Map<String, Object> newPlan = Map.of("name", "New");

            WorkflowPlanVersionEntity v3 = new WorkflowPlanVersionEntity(WORKFLOW_ID, 3, oldPlan, USER_ID);
            v3.setLabel(SESSION_A);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(3));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 3)).thenReturn(Optional.of(v3));
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            int version = service.createOrUpdateSessionVersion(WORKFLOW_ID, newPlan, USER_ID, SESSION_A);

            assertThat(version).isEqualTo(3); // same version number
            verify(versionRepository).save(v3); // overwrote existing entity
            assertThat(v3.getPlan().get("name")).isEqualTo("New");
        }

        @Test
        @DisplayName("Same session, plan unchanged → skip (no DB write)")
        void sameSession_planUnchanged_skip() {
            Map<String, Object> plan = Map.of("name", "Same");

            WorkflowPlanVersionEntity v3 = new WorkflowPlanVersionEntity(WORKFLOW_ID, 3, plan, USER_ID);
            v3.setLabel(SESSION_A);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(3));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 3)).thenReturn(Optional.of(v3));

            int version = service.createOrUpdateSessionVersion(WORKFLOW_ID, plan, USER_ID, SESSION_A);

            assertThat(version).isEqualTo(3);
            verify(versionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Latest version has different label → creates new version")
        void differentSession_createsNew() {
            Map<String, Object> oldPlan = Map.of("name", "Old");
            Map<String, Object> newPlan = Map.of("name", "New");

            WorkflowPlanVersionEntity v3 = new WorkflowPlanVersionEntity(WORKFLOW_ID, 3, oldPlan, USER_ID);
            v3.setLabel("User save"); // different label

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(3));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 3)).thenReturn(Optional.of(v3));
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(versionRepository.countByWorkflowId(WORKFLOW_ID)).thenReturn(4L);

            int version = service.createOrUpdateSessionVersion(WORKFLOW_ID, newPlan, USER_ID, SESSION_B);

            assertThat(version).isEqualTo(4); // new version
            ArgumentCaptor<WorkflowPlanVersionEntity> captor = ArgumentCaptor.forClass(WorkflowPlanVersionEntity.class);
            // save called twice: once for overwrite check fail path, once for new creation
            verify(versionRepository, atLeastOnce()).save(captor.capture());
            WorkflowPlanVersionEntity created = captor.getAllValues().stream()
                    .filter(e -> e.getVersion() == 4)
                    .findFirst().orElseThrow();
            assertThat(created.getLabel()).isEqualTo(SESSION_B);
        }

        @Test
        @DisplayName("Different label but plan unchanged → skip")
        void differentLabel_planUnchanged_skip() {
            Map<String, Object> plan = Map.of("name", "Same");

            WorkflowPlanVersionEntity v3 = new WorkflowPlanVersionEntity(WORKFLOW_ID, 3, plan, USER_ID);
            v3.setLabel("User save");

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(3));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 3)).thenReturn(Optional.of(v3));

            int version = service.createOrUpdateSessionVersion(WORKFLOW_ID, plan, USER_ID, SESSION_A);

            assertThat(version).isEqualTo(3);
            verify(versionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Full scenario: session A modifies 3 times, then session B modifies")
        void fullScenario_twoSessions() {
            // Session A: first modification → creates v2
            Map<String, Object> planA1 = Map.of("name", "A1");
            WorkflowPlanVersionEntity v1 = new WorkflowPlanVersionEntity(WORKFLOW_ID, 1, Map.of("name", "Original"), USER_ID);
            v1.setLabel("User save");

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(1));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 1)).thenReturn(Optional.of(v1));
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(versionRepository.countByWorkflowId(WORKFLOW_ID)).thenReturn(2L);

            int v2 = service.createOrUpdateSessionVersion(WORKFLOW_ID, planA1, USER_ID, SESSION_A);
            assertThat(v2).isEqualTo(2);

            // Session A: second modification → overwrites v2
            Map<String, Object> planA2 = Map.of("name", "A2");
            WorkflowPlanVersionEntity v2Entity = new WorkflowPlanVersionEntity(WORKFLOW_ID, 2, planA1, USER_ID);
            v2Entity.setLabel(SESSION_A);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(2));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 2)).thenReturn(Optional.of(v2Entity));

            int v2Again = service.createOrUpdateSessionVersion(WORKFLOW_ID, planA2, USER_ID, SESSION_A);
            assertThat(v2Again).isEqualTo(2); // same version number, overwritten

            // Session B: modification → creates v3
            Map<String, Object> planB1 = Map.of("name", "B1");
            v2Entity.setPlan(planA2); // v2 now has A2 plan
            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(2));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 2)).thenReturn(Optional.of(v2Entity));
            when(versionRepository.countByWorkflowId(WORKFLOW_ID)).thenReturn(3L);

            int v3 = service.createOrUpdateSessionVersion(WORKFLOW_ID, planB1, USER_ID, SESSION_B);
            assertThat(v3).isEqualTo(3); // new version for different session
        }
    }

    // =========================================================================
    // getCurrentVersion
    // =========================================================================

    @Nested
    @DisplayName("getCurrentVersion")
    class GetCurrentVersionTests {

        @Test
        @DisplayName("Returns max version when exists")
        void returnsMaxVersionWhenExists() {
            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(5));
            assertThat(service.getCurrentVersion(WORKFLOW_ID)).isEqualTo(5);
        }

        @Test
        @DisplayName("Returns 0 when no versions exist")
        void returnsZeroWhenNoVersions() {
            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.empty());
            assertThat(service.getCurrentVersion(WORKFLOW_ID)).isEqualTo(0);
        }
    }

    // =========================================================================
    // renameVersion
    // =========================================================================

    @Nested
    @DisplayName("renameVersion")
    class RenameVersionTests {

        @Test
        @DisplayName("Should rename existing version")
        void shouldRenameExistingVersion() {
            WorkflowPlanVersionEntity entity = new WorkflowPlanVersionEntity(WORKFLOW_ID, 1, Map.of(), USER_ID);
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 1)).thenReturn(Optional.of(entity));
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            WorkflowPlanVersionEntity result = service.renameVersion(WORKFLOW_ID, 1, "Production");

            assertThat(result.getLabel()).isEqualTo("Production");
        }

        @Test
        @DisplayName("Should throw for non-existent version")
        void shouldThrowForNonExistentVersion() {
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 99)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.renameVersion(WORKFLOW_ID, 99, "label"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Version 99 not found");
        }
    }

    // =========================================================================
    // Purge Protection - Pinned Version Survives
    // =========================================================================

    @Nested
    @DisplayName("Purge Protection for Pinned Versions")
    class PurgeProtectionTests {

        @Test
        @DisplayName("Pinned v1 survives purge when at v50 (>20 versions retained)")
        void pinnedVersionSurvivedPurge() {
            // Given: workflow with v1 pinned, currently at v49, creating v50
            WorkflowEntity workflow = new WorkflowEntity();
            workflow.setId(WORKFLOW_ID);
            workflow.setPinnedVersion(1);

            Map<String, Object> oldPlan = Map.of("name", "v49");
            Map<String, Object> newPlan = Map.of("name", "v50");

            WorkflowPlanVersionEntity v49 = new WorkflowPlanVersionEntity(WORKFLOW_ID, 49, oldPlan, USER_ID);
            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(49));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 49)).thenReturn(Optional.of(v49));
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            // 50 versions total - exceeds maxVersions (20)
            when(versionRepository.countByWorkflowId(WORKFLOW_ID)).thenReturn(50L);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));

            // When: create v50
            int version = service.createVersion(WORKFLOW_ID, newPlan, USER_ID);

            // Then: v50 created, and purge used the EXCLUDING variant to protect v1
            assertThat(version).isEqualTo(50);
            verify(versionRepository).purgeOldVersionsExcluding(WORKFLOW_ID, 20, 1);
            verify(versionRepository, never()).purgeOldVersions(WORKFLOW_ID, 20);
        }

        @Test
        @DisplayName("Unpinned: purge uses standard method (no protection)")
        void unpinnedVersionPurgedNormally() {
            // Given: workflow with NO pinned version, at v49, creating v50
            WorkflowEntity workflow = new WorkflowEntity();
            workflow.setId(WORKFLOW_ID);
            workflow.setPinnedVersion(null); // unpinned

            Map<String, Object> oldPlan = Map.of("name", "v49");
            Map<String, Object> newPlan = Map.of("name", "v50");

            WorkflowPlanVersionEntity v49 = new WorkflowPlanVersionEntity(WORKFLOW_ID, 49, oldPlan, USER_ID);
            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(49));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 49)).thenReturn(Optional.of(v49));
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(versionRepository.countByWorkflowId(WORKFLOW_ID)).thenReturn(50L);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));

            // When: create v50
            int version = service.createVersion(WORKFLOW_ID, newPlan, USER_ID);

            // Then: standard purge (v1 can be deleted)
            assertThat(version).isEqualTo(50);
            verify(versionRepository).purgeOldVersions(WORKFLOW_ID, 20);
            verify(versionRepository, never()).purgeOldVersionsExcluding(any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("After unpin: previously pinned v1 loses protection on next purge")
        void afterUnpinVersionLosesProtection() {
            // Given: workflow was pinned to v1, user unpinned, now at v50 creating v51
            WorkflowEntity workflow = new WorkflowEntity();
            workflow.setId(WORKFLOW_ID);
            workflow.setPinnedVersion(null); // UNPINNED - v1 no longer protected

            Map<String, Object> oldPlan = Map.of("name", "v50");
            Map<String, Object> newPlan = Map.of("name", "v51");

            WorkflowPlanVersionEntity v50 = new WorkflowPlanVersionEntity(WORKFLOW_ID, 50, oldPlan, USER_ID);
            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(50));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 50)).thenReturn(Optional.of(v50));
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(versionRepository.countByWorkflowId(WORKFLOW_ID)).thenReturn(51L);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));

            // When: create v51
            int version = service.createVersion(WORKFLOW_ID, newPlan, USER_ID);

            // Then: standard purge - v1 has no protection, will be purged normally
            assertThat(version).isEqualTo(51);
            verify(versionRepository).purgeOldVersions(WORKFLOW_ID, 20);
            verify(versionRepository, never()).purgeOldVersionsExcluding(any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("No purge when version count within limit")
        void noPurgeWhenWithinLimit() {
            // Given: only 5 versions - no purge needed
            Map<String, Object> oldPlan = Map.of("name", "v4");
            Map<String, Object> newPlan = Map.of("name", "v5");

            WorkflowPlanVersionEntity v4 = new WorkflowPlanVersionEntity(WORKFLOW_ID, 4, oldPlan, USER_ID);
            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(4));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 4)).thenReturn(Optional.of(v4));
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(versionRepository.countByWorkflowId(WORKFLOW_ID)).thenReturn(5L);

            // When
            service.createVersion(WORKFLOW_ID, newPlan, USER_ID);

            // Then: no purge at all
            verify(versionRepository, never()).purgeOldVersions(any(), anyInt());
            verify(versionRepository, never()).purgeOldVersionsExcluding(any(), anyInt(), anyInt());
            // Issue #149 - createVersion now looks up the workflow once for org plumbing.
            // The purge-protection path stays untouched (no purgeOldVersions call).
        }
    }
}
