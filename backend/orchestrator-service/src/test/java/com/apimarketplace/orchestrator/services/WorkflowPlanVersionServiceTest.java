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
            when(workflowRepository.findPinnedVersionById(WORKFLOW_ID)).thenReturn(Optional.empty());
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
            // Stub an UNPINNED workflow explicitly: without it Mockito returns
            // Optional.empty() and the test would silently exercise "workflow row
            // absent" instead of the unpinned case it claims to cover.
            when(workflowRepository.findPinnedVersionById(WORKFLOW_ID)).thenReturn(Optional.empty());

            int version = service.resolveContentVersionForExecution(WORKFLOW_ID, plan, USER_ID);

            assertThat(version).isEqualTo(7);
            // Read-only: the pin lookup runs (it decides whether this run executes the
            // pinned plan) but no version row is ever written, and the workflow row is
            // read at most once - the pin check must not add repeated lookups here.
            verify(versionRepository, never()).save(any());
            verify(workflowRepository, times(1)).findPinnedVersionById(WORKFLOW_ID);
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
            when(workflowRepository.findPinnedVersionById(WORKFLOW_ID)).thenReturn(Optional.ofNullable(workflow.getPinnedVersion()));
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
            // the v5 draft in place; the pinned v3 row is read (to rule out a pinned
            // production fire) but never written.
            Map<String, Object> storedV3 = Map.of("name", "WF", "mcps", List.of(Map.of("label", "PINNED")));
            Map<String, Object> storedV5 = Map.of("name", "WF", "mcps", List.of(Map.of("label", "A")));
            Map<String, Object> executing = Map.of("name", "WF", "mcps", List.of(Map.of("label", "A", "params", Map.of("k", "v"))));
            WorkflowPlanVersionEntity pinnedRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 3, new HashMap<>(storedV3), USER_ID);
            WorkflowPlanVersionEntity latestDraft = new WorkflowPlanVersionEntity(WORKFLOW_ID, 5, new HashMap<>(storedV5), USER_ID);

            WorkflowEntity workflow = unpinnedWorkflow();
            workflow.setPinnedVersion(3);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(5));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 5)).thenReturn(Optional.of(latestDraft));
            // Stub the pinned row explicitly: without it Mockito answers empty and the
            // test would silently traverse the missing-pinned-row WARN branch instead
            // of the "pin exists, content differs" path it claims to cover.
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 3)).thenReturn(Optional.of(pinnedRow));
            when(workflowRepository.findPinnedVersionById(WORKFLOW_ID)).thenReturn(Optional.ofNullable(workflow.getPinnedVersion()));
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            int version = service.resolveContentVersionForExecution(WORKFLOW_ID, executing, USER_ID);

            assertThat(version).isEqualTo(5);
            assertThat(latestDraft.getPlan()).isEqualTo(executing);
            // The pinned row was consulted but must come out untouched.
            assertThat(pinnedRow.getPlan()).isEqualTo(storedV3);
            // No new row minted (save was the in-place overwrite of the v5 entity).
            verify(versionRepository).save(latestDraft);
        }

        @Test
        @DisplayName("Regression 2026-07-20: a production run of a PINNED workflow resolves to the pinned number and never overwrites the newer draft")
        void pinnedProductionRunResolvesToPinnedVersionAndSparesTheDraft() {
            // Prod incident (workflow "Pro Mail Filter"): pinned v17, draft v18 saved
            // later with different content. Resolution only compared against the
            // LATEST row, so the executing v17 plan looked like drifted canvas
            // content: it overwrote the v18 draft in place and the run was stamped
            // v18. Symptom: the draft was silently destroyed and no run ever showed
            // "v17" again (two runs both displaying "v18" with different plans).
            //
            // Reached via the pin-blind callers of this method - WorkflowResumeService
            // .stampPlanVersion (writes run.planVersion) and WorkflowPersistenceService
            // .autoArchiveExecutionPlan (overwrites the draft). NOT via a scheduled
            // fire: ReusableTriggerService's pinned lane resolves the version upstream
            // and never calls this method. Fixing it here covers every caller.
            Map<String, Object> pinnedContent = Map.of("name", "WF", "cores", List.of(Map.of("label", "snapshot")));
            Map<String, Object> draftContent = Map.of("name", "WF", "cores", List.of(Map.of("label", "other")));
            WorkflowPlanVersionEntity pinnedRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 17, new HashMap<>(pinnedContent), USER_ID);
            WorkflowPlanVersionEntity draftRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 18, new HashMap<>(draftContent), USER_ID);

            WorkflowEntity workflow = unpinnedWorkflow();
            workflow.setPinnedVersion(17);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(18));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 18)).thenReturn(Optional.of(draftRow));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 17)).thenReturn(Optional.of(pinnedRow));
            when(workflowRepository.findPinnedVersionById(WORKFLOW_ID)).thenReturn(Optional.ofNullable(workflow.getPinnedVersion()));

            // The scheduled production fire executes the pinned v17 content.
            int version = service.resolveContentVersionForExecution(WORKFLOW_ID, pinnedContent, USER_ID);

            // The run must be stamped with the version it actually executes.
            assertThat(version).isEqualTo(17);
            // The user's newer draft must survive untouched, and nothing is written.
            assertThat(draftRow.getPlan()).isEqualTo(draftContent);
            verify(versionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Pinned workflow: an editor run whose content matches NEITHER pin nor draft still overwrites the draft in place")
        void pinnedWorkflowEditorDriftStillOverwritesDraft() {
            // Guard against over-correcting: only content that IS the pinned plan
            // short-circuits. A genuine editor edit on a pinned workflow keeps the
            // established in-place-overwrite semantics so drafts do not inflate.
            Map<String, Object> pinnedContent = Map.of("name", "WF", "cores", List.of(Map.of("label", "pinned")));
            Map<String, Object> draftContent = Map.of("name", "WF", "cores", List.of(Map.of("label", "draft")));
            Map<String, Object> editing = Map.of("name", "WF", "cores", List.of(Map.of("label", "edited")));
            WorkflowPlanVersionEntity pinnedRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 17, new HashMap<>(pinnedContent), USER_ID);
            WorkflowPlanVersionEntity draftRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 18, new HashMap<>(draftContent), USER_ID);

            WorkflowEntity workflow = unpinnedWorkflow();
            workflow.setPinnedVersion(17);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(18));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 18)).thenReturn(Optional.of(draftRow));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 17)).thenReturn(Optional.of(pinnedRow));
            when(workflowRepository.findPinnedVersionById(WORKFLOW_ID)).thenReturn(Optional.ofNullable(workflow.getPinnedVersion()));
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            int version = service.resolveContentVersionForExecution(WORKFLOW_ID, editing, USER_ID);

            assertThat(version).isEqualTo(18);
            assertThat(draftRow.getPlan()).isEqualTo(editing);
            assertThat(pinnedRow.getPlan()).isEqualTo(pinnedContent);
        }

        @Test
        @DisplayName("Pinned row missing (purged/deleted): WARNs and falls through to the legacy lane instead of resolving blind")
        void missingPinnedRowFallsThroughToLegacyLane() {
            // purgeOldVersions protects the pin, so this is only reachable through a
            // manual delete or a botched restore. The pin cannot be verified, so the
            // legacy overwrite lane runs - asserted here so the residual data-loss
            // exposure is explicit and reviewed rather than accidental.
            Map<String, Object> draftContent = Map.of("name", "WF", "cores", List.of(Map.of("label", "draft")));
            Map<String, Object> executing = Map.of("name", "WF", "cores", List.of(Map.of("label", "executing")));
            WorkflowPlanVersionEntity draftRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 18, new HashMap<>(draftContent), USER_ID);

            WorkflowEntity workflow = unpinnedWorkflow();
            workflow.setPinnedVersion(17);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(18));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 18)).thenReturn(Optional.of(draftRow));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 17)).thenReturn(Optional.empty());
            when(workflowRepository.findPinnedVersionById(WORKFLOW_ID)).thenReturn(Optional.ofNullable(workflow.getPinnedVersion()));
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ch.qos.logback.classic.Logger serviceLogger =
                    (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(WorkflowPlanVersionService.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            serviceLogger.addAppender(appender);
            try {
                int version = service.resolveContentVersionForExecution(WORKFLOW_ID, executing, USER_ID);

                assertThat(version).isEqualTo(18);
                assertThat(draftRow.getPlan()).isEqualTo(executing);
                // The WARN is the only signal that a pin could not be honoured, so it
                // is part of the contract, not decoration: without it the fall-through
                // is indistinguishable from a normal unpinned resolve.
                assertThat(appender.list)
                        .anySatisfy(event -> {
                            assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                            assertThat(event.getFormattedMessage())
                                    .contains("Pinned version 17")
                                    .contains("is missing");
                        });
            } finally {
                serviceLogger.detachAppender(appender);
            }
        }

        @Test
        @DisplayName("Regression 2026-07-20: a mis-stamped pinned run would be REFUSED by the production chokepoint, so the resolver must answer the pinned number")
        void pinnedRunKeepsAVersionThatPassesTheProductionChokepoint() {
            // ProductionRunResolver.isAllowedForProduction gates the next production
            // fire on Objects.equals(run.getPlanVersion(), workflow.getPinnedVersion()).
            // Pre-fix this resolver answered 18 for a run executing pinned v17, so the
            // stamped run no longer matched the pin and production fires were refused
            // at the chokepoint - the mislabel breaks execution, it is not cosmetic.
            // This test asserts the resolver's answer satisfies that equality.
            Map<String, Object> pinnedContent = Map.of("name", "WF", "cores", List.of(Map.of("label", "snapshot")));
            Map<String, Object> draftContent = Map.of("name", "WF", "cores", List.of(Map.of("label", "draft")));
            WorkflowPlanVersionEntity pinnedRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 17, new HashMap<>(pinnedContent), USER_ID);
            WorkflowPlanVersionEntity draftRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 18, new HashMap<>(draftContent), USER_ID);

            WorkflowEntity workflow = unpinnedWorkflow();
            workflow.setPinnedVersion(17);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(18));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 18)).thenReturn(Optional.of(draftRow));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 17)).thenReturn(Optional.of(pinnedRow));
            when(workflowRepository.findPinnedVersionById(WORKFLOW_ID)).thenReturn(Optional.ofNullable(workflow.getPinnedVersion()));

            int stamped = service.resolveContentVersionForExecution(WORKFLOW_ID, pinnedContent, USER_ID);

            // Feed the answer through the REAL chokepoint rather than re-implementing
            // its comparison here, so a change to ProductionRunResolver's semantics
            // breaks this test instead of silently invalidating it.
            com.apimarketplace.orchestrator.domain.WorkflowRunEntity run =
                    new com.apimarketplace.orchestrator.domain.WorkflowRunEntity();
            run.setPlanVersion(stamped);
            assertThat(new com.apimarketplace.orchestrator.trigger.ProductionRunResolver(null, null)
                    .isAllowedForProduction(run, workflow))
                    .as("a run stamped v%s must be accepted for production on a workflow pinned to v%s",
                            stamped, workflow.getPinnedVersion())
                    .isTrue();
        }

        @Test
        @DisplayName("Restored pinned version: standalone trigger refs stripped by the restore still resolve to the PINNED number")
        void restoredPinnedPlanMissingTriggerRefsStillMatchesThePin() {
            // Restoring a version writes the historical plan through
            // PlanStripUtils.deepCopyAndStrip, which removes scheduleId/webhookId/
            // chatEndpointId/formEndpointId from triggers[].params. Restoring the
            // PINNED version onto a pinned workflow thus yields a live plan equal to
            // the pinned row MINUS those refs. On a plain equality check the pin is
            // missed and the legacy lane overwrites the newer draft - the same data
            // loss, reached through the restore path.
            Map<String, Object> pinnedStored = Map.of("name", "WF", "triggers",
                    List.of(new HashMap<>(Map.of("label", "Daily", "params",
                            new HashMap<>(Map.of("cron", "0 8 * * *", "scheduleId", "sched-abc"))))));
            Map<String, Object> restoredLive = Map.of("name", "WF", "triggers",
                    List.of(new HashMap<>(Map.of("label", "Daily", "params",
                            new HashMap<>(Map.of("cron", "0 8 * * *"))))));
            Map<String, Object> draftContent = Map.of("name", "WF", "triggers", List.of(), "cores", List.of(Map.of("label", "draft")));
            WorkflowPlanVersionEntity pinnedRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 17, new HashMap<>(pinnedStored), USER_ID);
            WorkflowPlanVersionEntity draftRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 18, new HashMap<>(draftContent), USER_ID);

            WorkflowEntity workflow = unpinnedWorkflow();
            workflow.setPinnedVersion(17);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(18));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 18)).thenReturn(Optional.of(draftRow));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 17)).thenReturn(Optional.of(pinnedRow));
            when(workflowRepository.findPinnedVersionById(WORKFLOW_ID)).thenReturn(Optional.ofNullable(workflow.getPinnedVersion()));

            int version = service.resolveContentVersionForExecution(WORKFLOW_ID, restoredLive, USER_ID);

            assertThat(version).isEqualTo(17);
            assertThat(draftRow.getPlan()).isEqualTo(draftContent);
            // Normalization must not mutate either operand.
            assertThat(((Map<?, ?>) ((List<?>) pinnedRow.getPlan().get("triggers")).get(0)))
                    .extracting("params").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .containsEntry("scheduleId", "sched-abc");
            verify(versionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Regression 2026-07-20: UNPINNED version replay must not overwrite the latest version with the historical plan")
        void unpinnedVersionReplayDoesNotClobberTheLatestVersion() {
            // Sibling of the pinned incident, on an UNPINNED workflow. Replaying an
            // old version goes findOrCreateRunForVersion -> createExecution ->
            // recordWorkflowStart -> autoArchiveExecutionPlan, which hands the
            // HISTORICAL plan to this resolver. With no pin, both pin branches are
            // skipped and the legacy lane overwrites the LATEST version row with the
            // old plan: replaying v3 silently destroys v9's stored content. The other
            // replay paths carve this out (ReusableTriggerService diverts replays,
            // stampPlanVersion uses createVersion for __versionReplay__);
            // autoArchiveExecutionPlan has no such carve-out.
            Map<String, Object> historicalV3 = Map.of("name", "WF", "cores", List.of(Map.of("label", "old_step")));
            Map<String, Object> latestV9 = Map.of("name", "WF", "cores", List.of(Map.of("label", "current_step")));
            WorkflowPlanVersionEntity latestRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 9, new HashMap<>(latestV9), USER_ID);
            WorkflowPlanVersionEntity historicalRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 3, new HashMap<>(historicalV3), USER_ID);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(9));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 9)).thenReturn(Optional.of(latestRow));
            lenient().when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 3)).thenReturn(Optional.of(historicalRow));
            // The history the resolver scans before overwriting anything.
            when(versionRepository.findByWorkflowIdOrderByVersionDesc(WORKFLOW_ID))
                    .thenReturn(List.of(latestRow, historicalRow));
            // Unpinned.
            when(workflowRepository.findPinnedVersionById(WORKFLOW_ID)).thenReturn(Optional.empty());
            lenient().when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            int version = service.resolveContentVersionForExecution(WORKFLOW_ID, historicalV3, USER_ID);

            // The run replays v3, so v3 is the content-true answer.
            assertThat(version)
                    .as("replaying v3 must resolve to v3, not to the latest number")
                    .isEqualTo(3);
            // And v9's stored content must survive untouched - this is the data loss.
            assertThat(latestRow.getPlan())
                    .as("the latest version's stored plan must not be clobbered by the replayed plan")
                    .isEqualTo(latestV9);
            // Read-only resolve: nothing is written at all.
            verify(versionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Pinned workflow, editor run on the CURRENT DRAFT: exact draft match beats a normalized pin match")
        void editorRunOnDraftIsNotStampedWithThePinWhenOnlyTriggerRefsDiffer() {
            // The pin branch matches with trigger refs normalized away, so a draft
            // that differs from the pin by nothing but a re-armed scheduleId (what a
            // schedule re-arm + save produces) would otherwise make an editor run on
            // that DRAFT resolve to the PIN. A run at the pinned version can then
            // adopt the live production run and write its mock override onto it
            // (EditorRunResolver). The plan here is byte-exactly the draft, so the
            // draft number is the truthful answer.
            Map<String, Object> pinnedV17 = Map.of("name", "WF", "triggers",
                    List.of(new HashMap<>(Map.of("label", "Daily", "params",
                            new HashMap<>(Map.of("cron", "0 8 * * *", "scheduleId", "sched-OLD"))))));
            Map<String, Object> draftV18 = Map.of("name", "WF", "triggers",
                    List.of(new HashMap<>(Map.of("label", "Daily", "params",
                            new HashMap<>(Map.of("cron", "0 8 * * *", "scheduleId", "sched-NEW"))))));
            WorkflowPlanVersionEntity pinnedRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 17, new HashMap<>(pinnedV17), USER_ID);
            WorkflowPlanVersionEntity draftRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 18, new HashMap<>(draftV18), USER_ID);

            WorkflowEntity workflow = unpinnedWorkflow();
            workflow.setPinnedVersion(17);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(18));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 18)).thenReturn(Optional.of(draftRow));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 17)).thenReturn(Optional.of(pinnedRow));
            when(workflowRepository.findPinnedVersionById(WORKFLOW_ID)).thenReturn(Optional.ofNullable(workflow.getPinnedVersion()));

            // The editor canvas IS the draft, byte for byte.
            int version = service.resolveContentVersionForExecution(WORKFLOW_ID, draftV18, USER_ID);

            assertThat(version)
                    .as("an editor run on the draft must stay on the draft number, not be stamped with the pin")
                    .isEqualTo(18);
            verify(versionRepository, never()).save(any());
        }

        @Test
        @DisplayName("History scan failure degrades to the legacy lane instead of breaking the fire")
        void historyScanFailureDegradesToTheLegacyLane() {
            // The scan runs on every drifted resolve, i.e. inside trigger fires. A
            // repository failure there must not propagate: the documented degrade is
            // "fall back to the pre-existing overwrite behaviour".
            Map<String, Object> latestV9 = Map.of("name", "WF", "cores", List.of(Map.of("label", "current")));
            Map<String, Object> executing = Map.of("name", "WF", "cores", List.of(Map.of("label", "edited")));
            WorkflowPlanVersionEntity latestRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 9, new HashMap<>(latestV9), USER_ID);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(9));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 9)).thenReturn(Optional.of(latestRow));
            when(workflowRepository.findPinnedVersionById(WORKFLOW_ID)).thenReturn(Optional.empty());
            when(versionRepository.findByWorkflowIdOrderByVersionDesc(WORKFLOW_ID))
                    .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            int version = service.resolveContentVersionForExecution(WORKFLOW_ID, executing, USER_ID);

            assertThat(version).isEqualTo(9);
            assertThat(latestRow.getPlan()).isEqualTo(executing);
        }

        @Test
        @DisplayName("KNOWN LIMIT (mirror of the draft-wins rule): when the draft IS the restored pinned plan, the run is stamped with the draft, not the pin")
        void restoredPinThatAlsoEqualsTheDraftResolvesToTheDraft() {
            // Genuine ambiguity: the executing plan is byte-exact BOTH the draft and
            // (after ref normalization) the pin. The draft-wins rule sends it to 18,
            // which ProductionRunResolver then refuses for production.
            //
            // Deliberate: the demonstrated prod incident is an editor run on the draft
            // being stamped with the pin and then mutating the live production run.
            // Preferring the pin here would reopen it for every re-armed pinned
            // workflow, to close a case that needs a restore AND a draft that happens
            // to equal the restored plan. Characterized so the trade-off is visible
            // and cannot flip silently.
            Map<String, Object> restoredNoRefs = Map.of("name", "WF", "triggers",
                    List.of(new HashMap<>(Map.of("label", "Daily", "params",
                            new HashMap<>(Map.of("cron", "0 8 * * *"))))));
            Map<String, Object> pinnedWithRef = Map.of("name", "WF", "triggers",
                    List.of(new HashMap<>(Map.of("label", "Daily", "params",
                            new HashMap<>(Map.of("cron", "0 8 * * *", "scheduleId", "sched-abc"))))));
            WorkflowPlanVersionEntity pinnedRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 17, new HashMap<>(pinnedWithRef), USER_ID);
            WorkflowPlanVersionEntity draftRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 18, new HashMap<>(restoredNoRefs), USER_ID);

            WorkflowEntity workflow = unpinnedWorkflow();
            workflow.setPinnedVersion(17);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(18));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 18)).thenReturn(Optional.of(draftRow));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 17)).thenReturn(Optional.of(pinnedRow));
            when(workflowRepository.findPinnedVersionById(WORKFLOW_ID)).thenReturn(Optional.ofNullable(workflow.getPinnedVersion()));

            int version = service.resolveContentVersionForExecution(WORKFLOW_ID, restoredNoRefs, USER_ID);

            assertThat(version)
                    .as("draft-wins: the plan is byte-exact the draft, so the draft number is returned")
                    .isEqualTo(18);
            verify(versionRepository, never()).save(any());
        }

        @Test
        @DisplayName("KNOWN LIMIT: replaying a version that differs from the latest ONLY by trigger refs refreshes the latest instead of resolving to the replayed version")
        void replayOfARefOnlyDifferentVersionFallsBackToTheRefreshLane() {
            // Characterization, not an endorsement. The re-arm gate cannot tell
            // "latest, re-bound" from "an older version whose only difference from
            // latest is its refs" - both are normalized-equal to latest. The gate
            // resolves it as a re-bind: the latest row is refreshed in place and the
            // run is stamped with the latest number rather than the replayed one.
            // Damage is bounded to the infra ref regressing to the older value (the
            // plan logic is identical by construction); the alternative would break
            // the far more common re-arm path. Revisit if version rows ever gain a
            // content hash that survives ref rebinding.
            Map<String, Object> historicalV3 = Map.of("name", "WF", "triggers",
                    List.of(new HashMap<>(Map.of("label", "Daily", "params",
                            new HashMap<>(Map.of("cron", "0 8 * * *", "scheduleId", "sched-OLD"))))));
            Map<String, Object> latestV9 = Map.of("name", "WF", "triggers",
                    List.of(new HashMap<>(Map.of("label", "Daily", "params",
                            new HashMap<>(Map.of("cron", "0 8 * * *", "scheduleId", "sched-NEW"))))));
            WorkflowPlanVersionEntity historicalRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 3, new HashMap<>(historicalV3), USER_ID);
            WorkflowPlanVersionEntity latestRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 9, new HashMap<>(latestV9), USER_ID);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(9));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 9)).thenReturn(Optional.of(latestRow));
            when(workflowRepository.findPinnedVersionById(WORKFLOW_ID)).thenReturn(Optional.empty());
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            int version = service.resolveContentVersionForExecution(WORKFLOW_ID, historicalV3, USER_ID);

            assertThat(version).isEqualTo(9);
            assertThat(latestRow.getPlan()).isEqualTo(historicalV3);
            assertThat(historicalRow.getPlan()).isEqualTo(historicalV3);
        }

        @Test
        @DisplayName("Unpinned re-arm: a re-bound scheduleId refreshes the LATEST row, never resolves to an older same-logic version")
        void unpinnedRearmRefreshesLatestInsteadOfMatchingAnOlderVersion() {
            // Guard on the history scan's blast radius. The caller rules `latest` out
            // with a PLAIN comparison, but the scan matches with trigger refs
            // normalized away - so a plan that is `latest` with a re-armed scheduleId
            // would skip `latest` (excluded from the scan) and match an older
            // same-logic version, stamping the run with a stale number. This is the
            // unpinned refresh lane's normal traffic (ReusableTriggerService hands it
            // workflow.getPlan(), carrying the CURRENT refs).
            Map<String, Object> sameLogicOldRef = Map.of("name", "WF", "triggers",
                    List.of(new HashMap<>(Map.of("label", "Daily", "params",
                            new HashMap<>(Map.of("cron", "0 8 * * *", "scheduleId", "sched-OLD"))))));
            Map<String, Object> liveRearmed = Map.of("name", "WF", "triggers",
                    List.of(new HashMap<>(Map.of("label", "Daily", "params",
                            new HashMap<>(Map.of("cron", "0 8 * * *", "scheduleId", "sched-NEW"))))));
            // v3 and v9 carry the same logic; only v9 is the canvas' lineage.
            WorkflowPlanVersionEntity oldRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 3, new HashMap<>(sameLogicOldRef), USER_ID);
            WorkflowPlanVersionEntity latestRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 9, new HashMap<>(sameLogicOldRef), USER_ID);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(9));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 9)).thenReturn(Optional.of(latestRow));
            lenient().when(versionRepository.findByWorkflowIdOrderByVersionDesc(WORKFLOW_ID))
                    .thenReturn(List.of(latestRow, oldRow));
            when(workflowRepository.findPinnedVersionById(WORKFLOW_ID)).thenReturn(Optional.empty());
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            int version = service.resolveContentVersionForExecution(WORKFLOW_ID, liveRearmed, USER_ID);

            assertThat(version)
                    .as("a re-armed schedule must stay on the latest version, not fall back to v3")
                    .isEqualTo(9);
            // And the row is refreshed in place so the stored ref stops being stale.
            assertThat(latestRow.getPlan()).isEqualTo(liveRearmed);
            assertThat(oldRow.getPlan()).isEqualTo(sameLogicOldRef);
        }

        @Test
        @DisplayName("Pinned == latest: replaying an older version resolves to it instead of minting a spurious draft")
        void pinnedLatestVersionReplayResolvesToTheHistoricalVersion() {
            // The pin == max lane has its own write (createVersion). A replay reaching
            // it must resolve to the replayed version rather than mint a draft, same
            // as the unpinned lane.
            Map<String, Object> historicalV3 = Map.of("name", "WF", "cores", List.of(Map.of("label", "old_step")));
            Map<String, Object> pinnedLatestV9 = Map.of("name", "WF", "cores", List.of(Map.of("label", "current_step")));
            WorkflowPlanVersionEntity pinnedLatest = new WorkflowPlanVersionEntity(WORKFLOW_ID, 9, new HashMap<>(pinnedLatestV9), USER_ID);
            WorkflowPlanVersionEntity historicalRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 3, new HashMap<>(historicalV3), USER_ID);

            WorkflowEntity workflow = unpinnedWorkflow();
            workflow.setPinnedVersion(9);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(9));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 9)).thenReturn(Optional.of(pinnedLatest));
            when(versionRepository.findByWorkflowIdOrderByVersionDesc(WORKFLOW_ID))
                    .thenReturn(List.of(pinnedLatest, historicalRow));
            when(workflowRepository.findPinnedVersionById(WORKFLOW_ID)).thenReturn(Optional.ofNullable(workflow.getPinnedVersion()));

            int version = service.resolveContentVersionForExecution(WORKFLOW_ID, historicalV3, USER_ID);

            assertThat(version).isEqualTo(3);
            // Neither a new row minted nor the pinned row touched.
            assertThat(pinnedLatest.getPlan()).isEqualTo(pinnedLatestV9);
            verify(versionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Trigger re-arm: a CHANGED scheduleId still matches the pin (normalization is symmetric, not restore-only)")
        void changedStandaloneTriggerRefStillMatchesThePin() {
            // The strip is applied to both operands, so it covers more than restore:
            // re-arming a schedule or re-issuing a webhook rewrites these refs on the
            // live plan. Same plan, different infrastructure binding - it must keep
            // resolving to the pin so the run's stamp stays equal to pinned_version
            // across a re-arm, instead of drifting one above it.
            Map<String, Object> pinnedStored = Map.of("name", "WF", "triggers",
                    List.of(new HashMap<>(Map.of("label", "Daily", "params",
                            new HashMap<>(Map.of("cron", "0 8 * * *", "scheduleId", "sched-OLD"))))));
            Map<String, Object> rearmedLive = Map.of("name", "WF", "triggers",
                    List.of(new HashMap<>(Map.of("label", "Daily", "params",
                            new HashMap<>(Map.of("cron", "0 8 * * *", "scheduleId", "sched-NEW"))))));
            Map<String, Object> draftContent = Map.of("name", "WF", "triggers", List.of(), "cores", List.of(Map.of("label", "draft")));
            WorkflowPlanVersionEntity pinnedRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 17, new HashMap<>(pinnedStored), USER_ID);
            WorkflowPlanVersionEntity draftRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 18, new HashMap<>(draftContent), USER_ID);

            WorkflowEntity workflow = unpinnedWorkflow();
            workflow.setPinnedVersion(17);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(18));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 18)).thenReturn(Optional.of(draftRow));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 17)).thenReturn(Optional.of(pinnedRow));
            when(workflowRepository.findPinnedVersionById(WORKFLOW_ID)).thenReturn(Optional.ofNullable(workflow.getPinnedVersion()));

            int version = service.resolveContentVersionForExecution(WORKFLOW_ID, rearmedLive, USER_ID);

            assertThat(version).isEqualTo(17);
            assertThat(draftRow.getPlan()).isEqualTo(draftContent);
            verify(versionRepository, never()).save(any());
        }

        @Test
        @DisplayName("A non-stripped field difference (cron) does NOT match the pin - normalization must not create false positives")
        void differingCronDoesNotMatchThePin() {
            // Guard on the normalization's blast radius: only the four infrastructure
            // refs are ignored. A load-bearing field must still count as different,
            // otherwise the pin check would swallow real plan changes.
            Map<String, Object> pinnedStored = Map.of("name", "WF", "triggers",
                    List.of(new HashMap<>(Map.of("label", "Daily", "params",
                            new HashMap<>(Map.of("cron", "0 8 * * *", "scheduleId", "sched-abc"))))));
            Map<String, Object> editedLive = Map.of("name", "WF", "triggers",
                    List.of(new HashMap<>(Map.of("label", "Daily", "params",
                            new HashMap<>(Map.of("cron", "0 20 * * *"))))));
            Map<String, Object> draftContent = Map.of("name", "WF", "triggers", List.of(), "cores", List.of(Map.of("label", "draft")));
            WorkflowPlanVersionEntity pinnedRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 17, new HashMap<>(pinnedStored), USER_ID);
            WorkflowPlanVersionEntity draftRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 18, new HashMap<>(draftContent), USER_ID);

            WorkflowEntity workflow = unpinnedWorkflow();
            workflow.setPinnedVersion(17);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(18));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 18)).thenReturn(Optional.of(draftRow));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 17)).thenReturn(Optional.of(pinnedRow));
            when(workflowRepository.findPinnedVersionById(WORKFLOW_ID)).thenReturn(Optional.ofNullable(workflow.getPinnedVersion()));
            when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            int version = service.resolveContentVersionForExecution(WORKFLOW_ID, editedLive, USER_ID);

            // Not the pin: falls through to the legacy lane and overwrites the draft.
            assertThat(version).isEqualTo(18);
            assertThat(pinnedRow.getPlan()).isEqualTo(pinnedStored);
        }

        @Test
        @DisplayName("Restored pinned version when the pin IS the latest: resolves to the pin, no spurious draft minted")
        void restoredPinnedPlanWhenPinIsLatestDoesNotMintASpuriousVersion() {
            // Sibling of the pin-trails-draft case: restoreVersion strips standalone
            // trigger refs regardless of where the pin sits. With pin == max the
            // dedicated pin branch is skipped (it is gated on pinned != max), so the
            // restored plan misses the byte comparison and would mint v18 - stamping
            // the run one above the pin, which ProductionRunResolver then refuses.
            Map<String, Object> pinnedStored = Map.of("name", "WF", "triggers",
                    List.of(new HashMap<>(Map.of("label", "Daily", "params",
                            new HashMap<>(Map.of("cron", "0 8 * * *", "scheduleId", "sched-abc"))))));
            Map<String, Object> restoredLive = Map.of("name", "WF", "triggers",
                    List.of(new HashMap<>(Map.of("label", "Daily", "params",
                            new HashMap<>(Map.of("cron", "0 8 * * *"))))));
            WorkflowPlanVersionEntity pinnedLatest = new WorkflowPlanVersionEntity(WORKFLOW_ID, 17, new HashMap<>(pinnedStored), USER_ID);

            WorkflowEntity workflow = unpinnedWorkflow();
            workflow.setPinnedVersion(17);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(17));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 17)).thenReturn(Optional.of(pinnedLatest));
            when(workflowRepository.findPinnedVersionById(WORKFLOW_ID)).thenReturn(Optional.ofNullable(workflow.getPinnedVersion()));

            int version = service.resolveContentVersionForExecution(WORKFLOW_ID, restoredLive, USER_ID);

            assertThat(version).isEqualTo(17);
            // The pinned row stays immutable and no v18 row is minted - the latter is
            // where this test really bites, since pre-fix the createVersion lane is
            // what produced the above-the-pin stamp.
            assertThat(pinnedLatest.getPlan()).isEqualTo(pinnedStored);
            verify(versionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Pinned content identical to the newer draft still resolves to the PINNED number (semantic truth, not just content truth)")
        void pinnedContentEqualToDraftResolvesToPinnedNumber() {
            // Decides the ordering of the pin check: both numbers are content-true
            // here, but the run executes the PIN. Labelling it with the draft number
            // is exactly what erased "v17" from the prod run history. This is the case
            // that justifies paying one indexed lookup before the latest comparison.
            Map<String, Object> sharedContent = Map.of("name", "WF", "cores", List.of(Map.of("label", "snapshot")));
            WorkflowPlanVersionEntity pinnedRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 17, new HashMap<>(sharedContent), USER_ID);
            WorkflowPlanVersionEntity draftRow = new WorkflowPlanVersionEntity(WORKFLOW_ID, 18, new HashMap<>(sharedContent), USER_ID);

            WorkflowEntity workflow = unpinnedWorkflow();
            workflow.setPinnedVersion(17);

            when(versionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(18));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 18)).thenReturn(Optional.of(draftRow));
            when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 17)).thenReturn(Optional.of(pinnedRow));
            when(workflowRepository.findPinnedVersionById(WORKFLOW_ID)).thenReturn(Optional.ofNullable(workflow.getPinnedVersion()));

            int version = service.resolveContentVersionForExecution(WORKFLOW_ID, sharedContent, USER_ID);

            assertThat(version).isEqualTo(17);
            verify(versionRepository, never()).save(any());
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
