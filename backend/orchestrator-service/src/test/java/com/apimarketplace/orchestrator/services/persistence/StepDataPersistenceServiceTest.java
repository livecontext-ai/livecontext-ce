package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.execution.NodeType;
import com.apimarketplace.orchestrator.domain.workflow.ErrorMessageLimits;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.persistence.StepDataNativeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StepDataPersistenceService.
 *
 * This service is responsible for building and persisting WorkflowStepDataEntity instances.
 * It handles the transformation of step execution results into database entities.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepDataPersistenceService")
class StepDataPersistenceServiceTest {

    @Mock
    private StepDataNativeRepository nativeRepository;

    @Mock
    private StepPayloadService stepPayloadService;

    @Mock
    private StepMetadataBuilder metadataBuilder;

    @Mock
    private WorkflowEntityResolverService entityResolverService;

    @Mock
    private WorkflowExecution execution;

    @Mock
    private WorkflowPlan plan;

    private StepDataPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new StepDataPersistenceService(
                nativeRepository,
                stepPayloadService,
                metadataBuilder,
                entityResolverService
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // recordStep() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("recordStep()")
    class RecordStepTests {

        @Test
        @DisplayName("Should return not persisted when workflow run ID cannot be resolved")
        void shouldReturnFalseWhenWorkflowRunIdCannotBeResolved() {
            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.empty());
            StepExecutionResult result = createSuccessResult("test-step");

            StepPersistenceResult persistenceResult = service.recordStep(execution, "mcp:step", "alias", "graph", result);

            assertFalse(persistenceResult.persisted());
            assertNull(persistenceResult.storageId());
            verifyNoInteractions(nativeRepository);
        }

        @Test
        @DisplayName("Should use default item index of 0 when not provided in output")
        void shouldUseDefaultItemIndexWhenNotProvided() {
            // Note: WorkflowStepDataEntity defaults itemIndex to 0 when null is passed
            // So we need to test that native insert IS called with the default value
            UUID workflowRunId = UUID.randomUUID();
            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(entityResolverService.getCurrentSpawnFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(UUID.randomUUID());
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(false);

            // Create result without item_index - will default to 0
            StepExecutionResult result = new StepExecutionResult(
                    "test-step", NodeStatus.COMPLETED, "Success",
                    Map.of("some_key", "value"), // no item_index, defaults to 0
                    100L, null
            );

            StepPersistenceResult persistenceResult = service.recordStep(execution, "mcp:step", "alias", "graph", result);

            assertFalse(persistenceResult.persisted());
            // Native insert is called because itemIndex defaults to 0, not null
            verify(nativeRepository).insertIgnoringDuplicate(any());
        }

        @Test
        @DisplayName("Should return not persisted when DB detects duplicate (ON CONFLICT)")
        void shouldReturnFalseWhenDbDetectsDuplicate() {
            UUID workflowRunId = UUID.randomUUID();
            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(entityResolverService.getCurrentSpawnFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(UUID.randomUUID());
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(false);

            StepExecutionResult result = createSuccessResultWithItemIndex("test-step", 0);

            StepPersistenceResult persistenceResult = service.recordStep(execution, "mcp:step", "alias", "graph", result);

            assertFalse(persistenceResult.persisted());
        }

        @Test
        @DisplayName("Should successfully record step and return storage ID when all conditions met")
        void shouldSuccessfullyRecordStepWhenAllConditionsMet() {
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();
            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(entityResolverService.getCurrentSpawnFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(storageId);
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);

            StepExecutionResult result = createSuccessResultWithItemIndex("test-step", 0);

            StepPersistenceResult persistenceResult = service.recordStep(execution, "mcp:step", "alias", "graph", result);

            assertTrue(persistenceResult.persisted());
            assertEquals(storageId, persistenceResult.storageId());
            verify(nativeRepository).insertIgnoringDuplicate(any(WorkflowStepDataEntity.class));
        }

        @Test
        @DisplayName("Stamps native step inserts with organization from the async org scope")
        void nativeStepInsertCarriesAsyncOrganizationScope() {
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();
            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(entityResolverService.getCurrentSpawnFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(storageId);
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);

            StepExecutionResult result = createSuccessResultWithItemIndex("test-step", 0);

            TenantResolver.runWithOrgScope("org-async", () ->
                    service.recordStep(execution, "mcp:step", "alias", "graph", result));

            ArgumentCaptor<WorkflowStepDataEntity> captor = ArgumentCaptor.forClass(WorkflowStepDataEntity.class);
            verify(nativeRepository).insertIgnoringDuplicate(captor.capture());
            assertEquals("org-async", captor.getValue().getOrganizationId(),
                    "Native workflow_step_data inserts must preserve organization scope across async boundaries");
        }

        @Test
        @DisplayName("Persists full error_message even when longer than the legacy VARCHAR(1000) limit (V186 regression)")
        void shouldPersistErrorMessageLongerThanLegacyVarchar1000Limit() {
            // Regression for the app-host 2026-05-11 incident on run
            // run_<id>, node mcp:run_report_analytics: a Google
            // 404 HTML page (~4 KB) blew past the old VARCHAR(1000) cap on
            // workflow_step_data.error_message. The DataIntegrityViolationException
            // was swallowed by recordStep's catch-all, the row was silently dropped,
            // and the UI showed the successor as SKIPPED with no failed predecessor.
            // V186 widens the column to TEXT. This test pins two contracts:
            //   1) the service does NOT pre-truncate the message,
            //   2) the entity propagates the full payload to the native repo.
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();
            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(entityResolverService.getCurrentSpawnFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(storageId);
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);

            String longErrorMessage = "404 Not Found on POST request: <!DOCTYPE html>" + "x".repeat(5000);
            StepExecutionResult result = new StepExecutionResult(
                    "test-step", NodeStatus.FAILED, longErrorMessage,
                    Map.of("item_index", 0), 100L, new RuntimeException("Upstream HTML error")
            );

            StepPersistenceResult persistenceResult = service.recordStep(execution, "mcp:step", "alias", "graph", result);

            assertTrue(persistenceResult.persisted());
            ArgumentCaptor<WorkflowStepDataEntity> captor = ArgumentCaptor.forClass(WorkflowStepDataEntity.class);
            verify(nativeRepository).insertIgnoringDuplicate(captor.capture());
            WorkflowStepDataEntity captured = captor.getValue();
            assertNotNull(captured.getErrorMessage());
            assertEquals(longErrorMessage, captured.getErrorMessage(),
                    "Service must not pre-truncate the error message - the DB column is TEXT (V186), "
                            + "and the legacy VARCHAR(1000) cap is the bug we are guarding against.");
            assertTrue(captured.getErrorMessage().length() > 1000,
                    "Captured length must exceed the legacy 1000-char limit so this test "
                            + "would have failed against the pre-V186 schema.");
        }

        @Test
        @DisplayName("Caps the persisted error_message at ErrorMessageLimits.MAX_LENGTH via the StepExecutionResult compact constructor")
        void shouldCapErrorMessageAtCentralisedLimit() {
            // Defence in depth on top of V186: the column is TEXT (no DB cap),
            // and the ErrorMessageLimits cap (16 K chars) prevents pathological
            // multi-MB upstream bodies from bloating workflow_step_data - the
            // highest-volume orchestrator table. Pins the contract that the
            // cap fires through the record's compact constructor without any
            // explicit call from the persistence layer.
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();
            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(entityResolverService.getCurrentSpawnFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(storageId);
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);

            int oversizedLength = ErrorMessageLimits.MAX_LENGTH * 4; // 64 K chars - well past cap
            String hugeMessage = "h".repeat(oversizedLength);
            StepExecutionResult result = new StepExecutionResult(
                    "test-step", NodeStatus.FAILED, hugeMessage,
                    Map.of("item_index", 0), 100L, new RuntimeException("upstream blob")
            );

            service.recordStep(execution, "mcp:step", "alias", "graph", result);

            ArgumentCaptor<WorkflowStepDataEntity> captor = ArgumentCaptor.forClass(WorkflowStepDataEntity.class);
            verify(nativeRepository).insertIgnoringDuplicate(captor.capture());
            String persisted = captor.getValue().getErrorMessage();
            assertNotNull(persisted);
            assertTrue(persisted.length() <= ErrorMessageLimits.MAX_LENGTH,
                    "Persisted error_message must respect MAX_LENGTH - got " + persisted.length());
            assertTrue(persisted.endsWith("…[truncated, was " + oversizedLength + " chars]"),
                    "Truncation marker must surface the original length so operators "
                            + "know to consult the S3 payload for the full body");
        }

        @Test
        @DisplayName("F2: stamps the storage-failure marker on the row when payload storage returns null (no silent corruption)")
        void stampsStorageFailureMarkerWhenRetryReturnsNull() {
            // Direct test of the F2 branch: storageId is null at the start of
            // recordStep (e.g. caller skipped buildStepEntity), retry returns null,
            // stamp branch must fire and the marker must reach the entity.
            UUID workflowRunId = UUID.randomUUID();
            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(entityResolverService.getCurrentSpawnFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-456");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            // Both persistStepPayload overloads return null → storage completely down.
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(null);
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(null);
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);

            StepExecutionResult result = createSuccessResultWithItemIndex("test-step", 0);

            service.recordStep(execution, "mcp:step", "alias", "graph", result);

            ArgumentCaptor<WorkflowStepDataEntity> captor = ArgumentCaptor.forClass(WorkflowStepDataEntity.class);
            verify(nativeRepository).insertIgnoringDuplicate(captor.capture());
            WorkflowStepDataEntity captured = captor.getValue();

            assertNull(captured.getOutputStorageId(),
                    "Storage layer is down - outputStorageId must reflect that");
            assertNotNull(captured.getErrorMessage(),
                    "F2 marker must be stamped so the UI surfaces the storage failure");
            assertTrue(captured.getErrorMessage().contains("[storage] Payload persist failed"),
                    "Marker text must be the exact F2 contract - got: " + captured.getErrorMessage());
        }

        @Test
        @DisplayName("Should return not persisted when save throws exception")
        void shouldReturnFalseWhenSaveThrowsException() {
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();
            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(entityResolverService.getCurrentSpawnFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(storageId);
            when(nativeRepository.insertIgnoringDuplicate(any())).thenThrow(new RuntimeException("DB error"));

            StepExecutionResult result = createSuccessResultWithItemIndex("test-step", 0);

            StepPersistenceResult persistenceResult = service.recordStep(execution, "mcp:step", "alias", "graph", result);

            assertFalse(persistenceResult.persisted());
            assertNull(persistenceResult.storageId());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // buildStepEntity() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("buildStepEntity()")
    class BuildStepEntityTests {

        @Test
        @DisplayName("Should build entity with correct basic fields")
        void shouldBuildEntityWithCorrectBasicFields() {
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(storageId);

            StepExecutionResult result = createSuccessResultWithItemIndex("test-step", 0);

            WorkflowStepDataEntity entity = service.buildStepEntity(
                    execution, workflowRunId, "mcp:step", "Test Step", "graph-1", result, 0, 0
            );

            assertNotNull(entity);
            assertEquals(workflowRunId, entity.getWorkflowRunId());
            assertEquals("run-123", entity.getRunId());
            assertEquals("COMPLETED", entity.getStatus());
            assertEquals("tenant-1", entity.getTenantId());
            assertEquals(storageId, entity.getOutputStorageId());
        }

        @Test
        @DisplayName("Should use step label from plan when available")
        void shouldUseStepLabelFromPlanWhenAvailable() {
            UUID workflowRunId = UUID.randomUUID();
            Step step = mock(Step.class);
            when(step.label()).thenReturn("My Custom Step");
            when(step.id()).thenReturn("tool-123");
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.of(step));
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(UUID.randomUUID());

            StepExecutionResult result = createSuccessResultWithItemIndex("test-step", 0);

            WorkflowStepDataEntity entity = service.buildStepEntity(
                    execution, workflowRunId, "mcp:step", "alias", "graph-1", result, 0, 0
            );

            assertEquals("my_custom_step", entity.getStepAlias());
            assertEquals("tool-123", entity.getToolId());
        }

        @Test
        @DisplayName("Should set normalized key for step")
        void shouldSetNormalizedKeyForStep() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(UUID.randomUUID());

            StepExecutionResult result = createSuccessResultWithItemIndex("test-step", 0);

            WorkflowStepDataEntity entity = service.buildStepEntity(
                    execution, workflowRunId, "mcp:api_call", "API Call", "graph-1", result, 0, 0
            );

            assertEquals("mcp:api_call", entity.getNormalizedKey());
        }

        @Test
        @DisplayName("Should extract error message for failed results")
        void shouldExtractErrorMessageForFailedResults() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(UUID.randomUUID());

            StepExecutionResult result = new StepExecutionResult(
                    "test-step", NodeStatus.FAILED, "Connection timeout",
                    Map.of("item_index", 0), 100L, new RuntimeException("Timeout")
            );

            WorkflowStepDataEntity entity = service.buildStepEntity(
                    execution, workflowRunId, "mcp:step", "alias", "graph-1", result, 0, 0
            );

            assertEquals("Connection timeout", entity.getErrorMessage());
            assertEquals("FAILED", entity.getStatus());
        }

        @Test
        @DisplayName("Should calculate start and end times based on execution time")
        void shouldCalculateStartAndEndTimesBasedOnExecutionTime() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(UUID.randomUUID());

            StepExecutionResult result = new StepExecutionResult(
                    "test-step", NodeStatus.COMPLETED, "Success",
                    Map.of("item_index", 0), 500L, null
            );

            WorkflowStepDataEntity entity = service.buildStepEntity(
                    execution, workflowRunId, "mcp:step", "alias", "graph-1", result, 0, 0
            );

            assertNotNull(entity.getStartTime());
            assertNotNull(entity.getEndTime());
            // End time should be after start time
            assertTrue(entity.getEndTime().isAfter(entity.getStartTime()) ||
                       entity.getEndTime().equals(entity.getStartTime()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // enrichEntityWithNodeTypeFields() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("enrichEntityWithNodeTypeFields()")
    class EnrichEntityWithNodeTypeFieldsTests {

        @Test
        @DisplayName("Should handle null result gracefully")
        void shouldHandleNullResultGracefully() {
            WorkflowStepDataEntity entity = new WorkflowStepDataEntity();

            assertDoesNotThrow(() -> service.enrichEntityWithNodeTypeFields(entity, "mcp:step", null));
        }

        @Test
        @DisplayName("Should handle null output gracefully")
        void shouldHandleNullOutputGracefully() {
            WorkflowStepDataEntity entity = new WorkflowStepDataEntity();
            StepExecutionResult result = new StepExecutionResult(
                    "test-step", NodeStatus.COMPLETED, "Success", null, 0L, null
            );

            assertDoesNotThrow(() -> service.enrichEntityWithNodeTypeFields(entity, "mcp:step", result));
        }

        @Test
        @DisplayName("Should set node type to DECISION when output indicates decision")
        void shouldSetNodeTypeToDecisionWhenOutputIndicatesDecision() {
            WorkflowStepDataEntity entity = new WorkflowStepDataEntity();
            StepExecutionResult result = new StepExecutionResult(
                    "test-step", NodeStatus.COMPLETED, "Success",
                    Map.of(
                            "node_type", "DECISION",
                            "selected_branch", "if",
                            "condition_expression", "x > 10",
                            "condition_result", true
                    ),
                    0L, null
            );

            service.enrichEntityWithNodeTypeFields(entity, "core:decision", result);

            assertEquals(NodeType.DECISION, entity.getNodeType());
            assertEquals("if", entity.getSelectedBranch());
            assertEquals("x > 10", entity.getConditionExpression());
            assertTrue(entity.getConditionResult());
        }

        @Test
        @DisplayName("Should derive selected_branch from OPTION selected_choice_index for split routing")
        void shouldDeriveSelectedBranchFromOptionChoiceIndexForSplitRouting() {
            WorkflowStepDataEntity entity = new WorkflowStepDataEntity();
            StepExecutionResult result = new StepExecutionResult(
                    "test-step", NodeStatus.COMPLETED, "Success",
                    Map.of(
                            "node_type", "OPTION",
                            "selected_choice", "high",
                            "selected_label", "High",
                            "selected_choice_index", 1
                    ),
                    0L, null
            );

            service.enrichEntityWithNodeTypeFields(entity, "core:choose_path", result);

            assertEquals(NodeType.OPTION, entity.getNodeType());
            assertEquals("choice_1", entity.getSelectedBranch());
            assertEquals(1, entity.getMetadata().get("selected_choice_index"));
            assertEquals("high", entity.getMetadata().get("selected_choice"));
        }

        @Test
        @DisplayName("Should persist HTML_EXTRACT as its own node type instead of falling back to DECISION")
        void shouldPersistHtmlExtractAsOwnNodeType() {
            WorkflowStepDataEntity entity = new WorkflowStepDataEntity();
            StepExecutionResult result = new StepExecutionResult(
                    "test-step", NodeStatus.COMPLETED, "Success",
                    Map.of(
                            "node_type", "HTML_EXTRACT",
                            "items", List.of(Map.of("title", "Ada")),
                            "count", 1,
                            "matched_root", 1,
                            "errors", List.of()
                    ),
                    0L, null
            );

            service.enrichEntityWithNodeTypeFields(entity, "core:parse_cards", result);

            assertEquals(NodeType.HTML_EXTRACT, entity.getNodeType());
            assertNull(entity.getSelectedBranch());
        }

        @Test
        @DisplayName("Should set item_id and trigger_id when present")
        void shouldSetItemIdAndTriggerIdWhenPresent() {
            WorkflowStepDataEntity entity = new WorkflowStepDataEntity();
            StepExecutionResult result = new StepExecutionResult(
                    "test-step", NodeStatus.COMPLETED, "Success",
                    Map.of("item_id", "item-123", "trigger_id", "trigger-456"),
                    0L, null
            );

            service.enrichEntityWithNodeTypeFields(entity, "mcp:step", result);

            assertEquals("item-123", entity.getItemId());
            assertEquals("trigger-456", entity.getTriggerId());
        }

    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Input data extraction tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Input Data Extraction")
    class InputDataExtractionTests {

        @Test
        @DisplayName("Should extract resolved_params from output into the input_data DB column")
        void shouldExtractInputDataFromOutput() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(UUID.randomUUID());

            Map<String, Object> resolvedParams = Map.of("param1", "value1", "param2", 42);
            StepExecutionResult result = new StepExecutionResult(
                    "test-step", NodeStatus.COMPLETED, "Success",
                    Map.of("item_index", 0, "resolved_params", resolvedParams),
                    100L, null
            );

            WorkflowStepDataEntity entity = service.buildStepEntity(
                    execution, workflowRunId, "mcp:step", "alias", "graph-1", result, 0, 0
            );

            assertNotNull(entity.getInputData());
            assertEquals("value1", entity.getInputData().get("param1"));
            assertEquals(42, entity.getInputData().get("param2"));
        }

        @Test
        @DisplayName("Should filter out INVALID_TEMPLATE values from input data")
        void shouldFilterOutInvalidTemplateValues() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(UUID.randomUUID());

            Map<String, Object> resolvedParams = new HashMap<>();
            resolvedParams.put("valid", "value");
            resolvedParams.put("invalid1", "INVALID_TEMPLATE: missing reference");
            resolvedParams.put("invalid2", "some ${unresolved} template");

            StepExecutionResult result = new StepExecutionResult(
                    "test-step", NodeStatus.COMPLETED, "Success",
                    Map.of("item_index", 0, "resolved_params", resolvedParams),
                    100L, null
            );

            WorkflowStepDataEntity entity = service.buildStepEntity(
                    execution, workflowRunId, "mcp:step", "alias", "graph-1", result, 0, 0
            );

            assertNotNull(entity.getInputData());
            assertTrue(entity.getInputData().containsKey("valid"));
            assertFalse(entity.getInputData().containsKey("invalid1"));
            assertFalse(entity.getInputData().containsKey("invalid2"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Normalized key computation tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Normalized Key Computation")
    class NormalizedKeyComputationTests {

        @Test
        @DisplayName("Should keep already normalized keys unchanged")
        void shouldKeepAlreadyNormalizedKeysUnchanged() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(UUID.randomUUID());

            StepExecutionResult result = createSuccessResultWithItemIndex("mcp:api_call", 0);

            WorkflowStepDataEntity entity = service.buildStepEntity(
                    execution, workflowRunId, "mcp:api_call", "API Call", "graph-1", result, 0, 0
            );

            assertEquals("mcp:api_call", entity.getNormalizedKey());
        }

        @Test
        @DisplayName("Should detect trigger prefix from stepId")
        void shouldDetectTriggerPrefixFromStepId() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(UUID.randomUUID());

            StepExecutionResult result = createSuccessResultWithItemIndex("trigger:start", 0);

            WorkflowStepDataEntity entity = service.buildStepEntity(
                    execution, workflowRunId, "trigger:start", "Start", "graph-1", result, 0, 0
            );

            assertEquals("trigger:start", entity.getNormalizedKey());
        }

        @Test
        @DisplayName("Should detect agent prefix from stepId")
        void shouldDetectAgentPrefixFromStepId() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(UUID.randomUUID());

            StepExecutionResult result = createSuccessResultWithItemIndex("agent:analyzer", 0);

            WorkflowStepDataEntity entity = service.buildStepEntity(
                    execution, workflowRunId, "agent:analyzer", "Analyzer", "graph-1", result, 0, 0
            );

            assertEquals("agent:analyzer", entity.getNormalizedKey());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Normalized key computation for core: nodes
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Normalized Key Computation for core: nodes")
    class CoreNormalizedKeyTests {

        @Test
        @DisplayName("Should keep core: prefix for aggregate nodes")
        void shouldKeepCorePrefixForAggregateNodes() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(UUID.randomUUID());

            StepExecutionResult result = new StepExecutionResult(
                    "core:aggregate_posts", NodeStatus.COMPLETED, "Success",
                    Map.of("node_type", "AGGREGATE", "aggregated_count", 10, "item_index", 0),
                    100L, null
            );

            WorkflowStepDataEntity entity = service.buildStepEntity(
                    execution, workflowRunId, "core:aggregate_posts", "aggregate_posts", "graph-1", result, 0, 0
            );

            assertEquals("core:aggregate_posts", entity.getNormalizedKey());
            assertEquals(NodeType.AGGREGATE, entity.getNodeType());
        }

        @Test
        @DisplayName("Should keep core: prefix for split nodes")
        void shouldKeepCorePrefixForSplitNodes() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(UUID.randomUUID());

            StepExecutionResult result = new StepExecutionResult(
                    "core:split_posts", NodeStatus.COMPLETED, "Success",
                    Map.of("node_type", "SPLIT", "item_count", 5, "item_index", 0),
                    100L, null
            );

            WorkflowStepDataEntity entity = service.buildStepEntity(
                    execution, workflowRunId, "core:split_posts", "split_posts", "graph-1", result, 0, 0
            );

            assertEquals("core:split_posts", entity.getNormalizedKey());
            assertEquals(NodeType.SPLIT_CONTROLLER, entity.getNodeType());
        }

        @Test
        @DisplayName("Should use core: prefix when stepId is not prefixed but nodeType is AGGREGATE")
        void shouldUseCoreWhenUnprefixedStepIdWithAggregateNodeType() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(UUID.randomUUID());

            StepExecutionResult result = new StepExecutionResult(
                    "aggregate_posts", NodeStatus.COMPLETED, "Success",
                    Map.of("node_type", "AGGREGATE", "aggregated_count", 10, "item_index", 0),
                    100L, null
            );

            WorkflowStepDataEntity entity = service.buildStepEntity(
                    execution, workflowRunId, "aggregate_posts", "aggregate_posts", "graph-1", result, 0, 0
            );

            assertEquals("core:aggregate_posts", entity.getNormalizedKey());
        }

        @Test
        @DisplayName("Should use core: prefix when stepId is not prefixed but nodeType is MERGE")
        void shouldUseCoreWhenUnprefixedStepIdWithMergeNodeType() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(UUID.randomUUID());

            StepExecutionResult result = new StepExecutionResult(
                    "wait_all", NodeStatus.COMPLETED, "Success",
                    Map.of("node_type", "MERGE", "merge_strategy", "wait_all", "item_index", 0),
                    100L, null
            );

            WorkflowStepDataEntity entity = service.buildStepEntity(
                    execution, workflowRunId, "wait_all", "wait_all", "graph-1", result, 0, 0
            );

            assertEquals("core:wait_all", entity.getNormalizedKey());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Node type prefix coherence tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Node Type Prefix Coherence")
    class NodeTypePrefixCoherenceTests {

        private WorkflowStepDataEntity buildEntityForStep(String stepId, String nodeTypeOutput) {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(UUID.randomUUID());

            Map<String, Object> output = new HashMap<>();
            output.put("item_index", 0);
            if (nodeTypeOutput != null) {
                output.put("node_type", nodeTypeOutput);
            }
            StepExecutionResult result = new StepExecutionResult(
                    stepId, NodeStatus.COMPLETED, "Success", output, 100L, null
            );

            return service.buildStepEntity(
                    execution, workflowRunId, stepId, stepId, "graph-1", result, 0, 0
            );
        }

        @Test
        @DisplayName("MCP steps should get mcp: prefix in normalizedKey")
        void mcpStepsShouldGetMcpPrefix() {
            WorkflowStepDataEntity entity = buildEntityForStep("mcp:api_call", null);
            assertEquals("mcp:api_call", entity.getNormalizedKey());
            assertEquals(NodeType.MCP, entity.getNodeType());
        }

        @Test
        @DisplayName("Trigger steps should get trigger: prefix in normalizedKey")
        void triggerStepsShouldGetTriggerPrefix() {
            WorkflowStepDataEntity entity = buildEntityForStep("trigger:webhook", null);
            assertEquals("trigger:webhook", entity.getNormalizedKey());
        }

        @Test
        @DisplayName("Agent steps should get agent: prefix in normalizedKey")
        void agentStepsShouldGetAgentPrefix() {
            WorkflowStepDataEntity entity = buildEntityForStep("agent:assistant", null);
            assertEquals("agent:assistant", entity.getNormalizedKey());
        }

        @Test
        @DisplayName("Core steps should get core: prefix in normalizedKey")
        void coreStepsShouldGetCorePrefix() {
            WorkflowStepDataEntity entity = buildEntityForStep("core:decision1", "DECISION");
            assertEquals("core:decision1", entity.getNormalizedKey());
            assertEquals(NodeType.DECISION, entity.getNodeType());
        }

        @Test
        @DisplayName("Table/CRUD steps should get table: prefix in normalizedKey")
        void tableStepsShouldGetTablePrefix() {
            WorkflowStepDataEntity entity = buildEntityForStep("table:users", "GET_ROWS");
            assertEquals("table:users", entity.getNormalizedKey());
            assertEquals(NodeType.GET_ROWS, entity.getNodeType());
        }

        @Test
        @DisplayName("Interface steps should get interface: prefix in normalizedKey")
        void interfaceStepsShouldGetInterfacePrefix() {
            WorkflowStepDataEntity entity = buildEntityForStep("interface:search_page", "INTERFACE");
            assertEquals("interface:search_page", entity.getNormalizedKey());
            assertEquals(NodeType.INTERFACE, entity.getNodeType());
        }

        @Test
        @DisplayName("INSERT_ROW should get table: prefix when unprefixed")
        void insertRowShouldGetTablePrefix() {
            WorkflowStepDataEntity entity = buildEntityForStep("insert_users", "INSERT_ROW");
            assertEquals("table:insert_users", entity.getNormalizedKey());
            assertEquals(NodeType.INSERT_ROW, entity.getNodeType());
        }

        @Test
        @DisplayName("GET_ROWS should get table: prefix when unprefixed")
        void getRowsShouldGetTablePrefix() {
            WorkflowStepDataEntity entity = buildEntityForStep("get_users", "GET_ROWS");
            assertEquals("table:get_users", entity.getNormalizedKey());
            assertEquals(NodeType.GET_ROWS, entity.getNodeType());
        }

        @Test
        @DisplayName("UPDATE_ROW should get table: prefix when unprefixed")
        void updateRowShouldGetTablePrefix() {
            WorkflowStepDataEntity entity = buildEntityForStep("update_users", "UPDATE_ROW");
            assertEquals("table:update_users", entity.getNormalizedKey());
            assertEquals(NodeType.UPDATE_ROW, entity.getNodeType());
        }

        @Test
        @DisplayName("DELETE_ROW should get table: prefix when unprefixed")
        void deleteRowShouldGetTablePrefix() {
            WorkflowStepDataEntity entity = buildEntityForStep("delete_users", "DELETE_ROW");
            assertEquals("table:delete_users", entity.getNormalizedKey());
            assertEquals(NodeType.DELETE_ROW, entity.getNodeType());
        }

        @Test
        @DisplayName("FILTER should get core: prefix when unprefixed")
        void filterShouldGetCorePrefix() {
            WorkflowStepDataEntity entity = buildEntityForStep("filter_active", "FILTER");
            assertEquals("core:filter_active", entity.getNormalizedKey());
            assertEquals(NodeType.FILTER, entity.getNodeType());
        }

        @Test
        @DisplayName("SORT should get core: prefix when unprefixed")
        void sortShouldGetCorePrefix() {
            WorkflowStepDataEntity entity = buildEntityForStep("sort_by_date", "SORT");
            assertEquals("core:sort_by_date", entity.getNormalizedKey());
            assertEquals(NodeType.SORT, entity.getNodeType());
        }

        @Test
        @DisplayName("TRANSFORM should get core: prefix when unprefixed")
        void transformShouldGetCorePrefix() {
            WorkflowStepDataEntity entity = buildEntityForStep("map_fields", "TRANSFORM");
            assertEquals("core:map_fields", entity.getNormalizedKey());
            assertEquals(NodeType.TRANSFORM, entity.getNodeType());
        }

        @Test
        @DisplayName("WAIT should get core: prefix when unprefixed")
        void waitShouldGetCorePrefix() {
            WorkflowStepDataEntity entity = buildEntityForStep("pause_5s", "WAIT");
            assertEquals("core:pause_5s", entity.getNormalizedKey());
            assertEquals(NodeType.WAIT, entity.getNodeType());
        }

        @Test
        @DisplayName("HTTP_REQUEST should get core: prefix when unprefixed")
        void httpRequestShouldGetCorePrefix() {
            WorkflowStepDataEntity entity = buildEntityForStep("call_api", "HTTP_REQUEST");
            assertEquals("core:call_api", entity.getNormalizedKey());
            assertEquals(NodeType.HTTP_REQUEST, entity.getNodeType());
        }

        @Test
        @DisplayName("CODE should get core: prefix when unprefixed")
        void codeShouldGetCorePrefix() {
            WorkflowStepDataEntity entity = buildEntityForStep("run_script", "CODE");
            assertEquals("core:run_script", entity.getNormalizedKey());
            assertEquals(NodeType.CODE, entity.getNodeType());
        }

        @Test
        @DisplayName("SEND_EMAIL should get core: prefix when unprefixed")
        void sendEmailShouldGetCorePrefix() {
            WorkflowStepDataEntity entity = buildEntityForStep("notify_user", "SEND_EMAIL");
            assertEquals("core:notify_user", entity.getNormalizedKey());
            assertEquals(NodeType.SEND_EMAIL, entity.getNodeType());
        }

        @Test
        @DisplayName("SUB_WORKFLOW should get core: prefix when unprefixed")
        void subWorkflowShouldGetCorePrefix() {
            WorkflowStepDataEntity entity = buildEntityForStep("child_flow", "SUB_WORKFLOW");
            assertEquals("core:child_flow", entity.getNormalizedKey());
            assertEquals(NodeType.SUB_WORKFLOW, entity.getNodeType());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 2026-05-21 CRITICAL 2 - trigger_id column wiring regression
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Regression tests for the e2e audit (2026-05-21) CRITICAL 2: pre-fix every
     * COMPLETED {@code workflow_step_data} row drifted to
     * {@code trigger_id="trigger:default"} because the entity builder only read
     * {@code output.trigger_id} from the step's output map. Non-trigger nodes
     * (the vast majority) landed under default → per-epoch detail view broken.
     *
     * <p>The fix added a {@code triggerId} parameter to {@code recordStep} +
     * {@code buildStepEntity} and uses it as the fallback before
     * {@code "trigger:default"}. {@code output.trigger_id} still wins (trigger
     * nodes publish their own ID), then explicit argument, then sentinel.
     *
     * <p>Verified resolution order:
     * <ol>
     *   <li>{@code output.trigger_id} present → use it (trigger nodes)</li>
     *   <li>else explicit {@code triggerId} argument non-null → use it</li>
     *   <li>else fall back to {@code "trigger:default"} (legacy back-compat)</li>
     * </ol>
     */
    @Nested
    @DisplayName("CRITICAL 2 (2026-05-21) - buildStepEntity trigger_id resolution order")
    class TriggerIdResolutionRegression {

        @Test
        @DisplayName("9-arg buildStepEntity threads explicit triggerId - non-trigger node WITHOUT output.trigger_id lands under the real DAG, NOT trigger:default")
        void explicitTriggerIdHealsNonTriggerNodeRow() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getRunId()).thenReturn("run-critical-2");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(UUID.randomUUID());

            // MCP node - output has NO trigger_id (the canonical case).
            StepExecutionResult result = createSuccessResultWithItemIndex("mcp:fetch_emails", 0);

            WorkflowStepDataEntity entity = service.buildStepEntity(
                execution, workflowRunId, "mcp:fetch_emails", "fetch_emails", "graph-1",
                result, /*epoch=*/5, /*spawn=*/0, /*triggerId=*/"trigger:cron");

            // POST-FIX: explicit argument wins, row lands under the real DAG.
            assertEquals("trigger:cron", entity.getTriggerId(),
                "Non-trigger node must inherit the explicit triggerId from the caller - " +
                "regression CRITICAL 2 (2026-05-21 e2e audit).");
        }

        @Test
        @DisplayName("8-arg buildStepEntity (legacy) - null triggerId still falls back to trigger:default (back-compat preserved)")
        void legacyEightArgPreservesDefaultSentinel() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getRunId()).thenReturn("run-legacy");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(UUID.randomUUID());

            StepExecutionResult result = createSuccessResultWithItemIndex("mcp:fetch", 0);

            // 8-arg call (legacy form) - delegates internally to 9-arg with triggerId=null.
            WorkflowStepDataEntity entity = service.buildStepEntity(
                execution, workflowRunId, "mcp:fetch", "fetch", "graph-1", result, 0, 0);

            // Back-compat: legacy callers still get the sentinel. Tests written
            // against the 8-arg signature continue to pass without changes.
            assertEquals("trigger:default", entity.getTriggerId());
        }

        @Test
        @DisplayName("output.trigger_id still wins over explicit argument (trigger-node back-compat)")
        void outputTriggerIdOverridesExplicit() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getRunId()).thenReturn("run-trigger-node");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(UUID.randomUUID());

            // Trigger node - output explicitly publishes a different trigger_id.
            StepExecutionResult result = new StepExecutionResult(
                "trigger:webhook", NodeStatus.COMPLETED, "Success",
                Map.of("trigger_id", "trigger:webhook-explicit", "item_index", 0),
                100L, null);

            WorkflowStepDataEntity entity = service.buildStepEntity(
                execution, workflowRunId, "trigger:webhook", "webhook", "graph-1",
                result, 0, 0, /*triggerId=*/"trigger:should-be-ignored");

            // output.trigger_id wins - preserves the trigger-node back-compat
            // contract (enrichEntityWithNodeTypeFields:223 reads output.trigger_id).
            assertEquals("trigger:webhook-explicit", entity.getTriggerId());
        }

        @Test
        @DisplayName("Blank explicit triggerId falls back to trigger:default (whitespace guard)")
        void blankTriggerIdFallsBackToDefault() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getRunId()).thenReturn("run-blank");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(UUID.randomUUID());

            StepExecutionResult result = createSuccessResultWithItemIndex("mcp:fetch", 0);

            WorkflowStepDataEntity entity = service.buildStepEntity(
                execution, workflowRunId, "mcp:fetch", "fetch", "graph-1",
                result, 0, 0, /*triggerId=*/"   ");

            assertEquals("trigger:default", entity.getTriggerId());
        }
    }

    @Nested
    @DisplayName("SKIPPED row coherence")
    class SkippedRowCoherenceRegression {

        @Test
        @DisplayName("regression: SKIPPED recordStep rows persist skip_reason from message when output lacks skip_reason")
        void skippedRecordStepRowsPersistSkipReasonFromMessage() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getRunId()).thenReturn("run-skipped-reason");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(metadataBuilder.buildMetadata(any(), any(), any(), any(), any())).thenReturn(new HashMap<>());
            when(stepPayloadService.persistStepPayload(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(UUID.randomUUID());

            StepExecutionResult result = new StepExecutionResult(
                "core:apply_ops",
                NodeStatus.SKIPPED,
                "No items routed to this branch",
                Map.of("item_index", 0),
                0L,
                null
            );

            WorkflowStepDataEntity entity = service.buildStepEntity(
                execution, workflowRunId, "core:apply_ops", "apply_ops", "core:apply_ops",
                result, 1, 0, "trigger:start");

            assertEquals("No items routed to this branch", entity.getSkipReason());
            assertEquals("No items routed to this branch", entity.getMetadata().get("skipReason"));
            assertEquals("trigger:start", entity.getTriggerId());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper methods
    // ═══════════════════════════════════════════════════════════════════════════

    private StepExecutionResult createSuccessResult(String stepId) {
        return new StepExecutionResult(
                stepId, NodeStatus.COMPLETED, "Success",
                Map.of("result", "ok"), 100L, null
        );
    }

    private StepExecutionResult createSuccessResultWithItemIndex(String stepId, int itemIndex) {
        return new StepExecutionResult(
                stepId, NodeStatus.COMPLETED, "Success",
                Map.of("result", "ok", "item_index", itemIndex), 100L, null
        );
    }
}
