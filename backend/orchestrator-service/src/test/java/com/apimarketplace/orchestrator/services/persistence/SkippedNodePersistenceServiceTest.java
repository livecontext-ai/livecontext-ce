package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.execution.NodeType;
import com.apimarketplace.orchestrator.domain.workflow.Core;
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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SkippedNodePersistenceService.
 *
 * This service is responsible for persisting skipped nodes in workflow_step_data.
 * Called when a node is skipped due to decision branch not taken or skip propagation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkippedNodePersistenceService")
class SkippedNodePersistenceServiceTest {

    @Mock
    private StepDataNativeRepository nativeRepository;

    @Mock
    private StepPayloadService stepPayloadService;

    @Mock
    private WorkflowEntityResolverService entityResolverService;

    @Mock
    private WorkflowExecution execution;

    @Mock
    private WorkflowPlan plan;

    private SkippedNodePersistenceService service;

    @BeforeEach
    void setUp() {
        service = new SkippedNodePersistenceService(
                nativeRepository,
                stepPayloadService,
                entityResolverService
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // recordSkippedNode() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("recordSkippedNode()")
    class RecordSkippedNodeTests {

        @Test
        @DisplayName("Should return false when workflow run ID cannot be resolved")
        void shouldReturnFalseWhenWorkflowRunIdCannotBeResolved() {
            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.empty());
            when(execution.getRunId()).thenReturn("run-123");

            boolean result = service.recordSkippedNode(
                    execution, "mcp:step", "Step", "decision_branch_not_taken",
                    "core:decision", 0
            );

            assertFalse(result);
            verifyNoInteractions(nativeRepository);
        }

        @Test
        @DisplayName("Should return false when DB detects duplicate (ON CONFLICT)")
        void shouldReturnFalseWhenDbDetectsDuplicate() {
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();
            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-1");
            when(stepPayloadService.persistSkippedNodePayload(anyString(), any(), anyInt())).thenReturn(storageId);
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(false);

            boolean result = service.recordSkippedNode(
                    execution, "mcp:step", "Step", "decision_branch_not_taken",
                    "core:decision", 0
            );

            assertFalse(result);
        }

        @Test
        @DisplayName("Sibling stamp: skip-payload persist failure keeps status SKIPPED but stamps the storage-loss marker on error_message (never silent)")
        void stampsErrorMessageWhenSkipPayloadPersistFails() {
            // Pre-fix sibling of the output-loss bug: a null storageId from
            // persistSkippedNodePayload was swallowed with NO stamp at all -
            // the SKIPPED row landed with a silently missing payload blob.
            UUID workflowRunId = UUID.randomUUID();
            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-1");
            when(stepPayloadService.persistSkippedNodePayload(anyString(), any(), anyInt())).thenReturn(null);
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);

            boolean result = service.recordSkippedNode(
                    execution, "mcp:step", "Step", "decision_branch_not_taken",
                    "core:decision", 0);

            assertTrue(result, "the SKIPPED row must still land");
            ArgumentCaptor<WorkflowStepDataEntity> captor = ArgumentCaptor.forClass(WorkflowStepDataEntity.class);
            verify(nativeRepository).insertIgnoringDuplicate(captor.capture());
            WorkflowStepDataEntity captured = captor.getValue();
            assertEquals("SKIPPED", captured.getStatus(),
                    "a skip is not a failure - status must stay SKIPPED");
            assertNull(captured.getOutputStorageId());
            assertNotNull(captured.getErrorMessage(), "the loss must be stamped, never silent");
            assertTrue(captured.getErrorMessage().contains("[storage] Skip payload persist failed"),
                    "marker must name the skip-payload loss - got: " + captured.getErrorMessage());
        }

        @Test
        @DisplayName("BEHAVIOUR GUARD: a successful skip-payload persist leaves error_message null")
        void successfulSkipPayloadLeavesErrorMessageNull() {
            UUID workflowRunId = UUID.randomUUID();
            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-1");
            when(stepPayloadService.persistSkippedNodePayload(anyString(), any(), anyInt()))
                    .thenReturn(UUID.randomUUID());
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);

            assertTrue(service.recordSkippedNode(
                    execution, "mcp:step", "Step", "decision_branch_not_taken",
                    "core:decision", 0));

            ArgumentCaptor<WorkflowStepDataEntity> captor = ArgumentCaptor.forClass(WorkflowStepDataEntity.class);
            verify(nativeRepository).insertIgnoringDuplicate(captor.capture());
            assertNull(captor.getValue().getErrorMessage());
        }

        @Test
        @DisplayName("Should successfully record skipped node")
        void shouldSuccessfullyRecordSkippedNode() {
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();

            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-1");
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);
            when(stepPayloadService.persistSkippedNodePayload(anyString(), any(), anyInt())).thenReturn(storageId);

            // nativeRepository.insertIgnoringDuplicate already set to return true

            boolean result = service.recordSkippedNode(
                    execution, "mcp:step", "Step", "decision_branch_not_taken",
                    "core:decision", 0
            );

            assertTrue(result);
            verify(nativeRepository).insertIgnoringDuplicate(any(WorkflowStepDataEntity.class));
        }

        @Test
        @DisplayName("Should set SKIPPED status on entity")
        void shouldSetSkippedStatusOnEntity() {
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();

            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-1");
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);
            when(stepPayloadService.persistSkippedNodePayload(anyString(), any(), anyInt())).thenReturn(storageId);

            // nativeRepository.insertIgnoringDuplicate already set to return true

            ArgumentCaptor<WorkflowStepDataEntity> entityCaptor =
                    ArgumentCaptor.forClass(WorkflowStepDataEntity.class);

            service.recordSkippedNode(
                    execution, "mcp:step", "Step", "decision_branch_not_taken",
                    "core:decision", 5
            );

            verify(nativeRepository).insertIgnoringDuplicate(entityCaptor.capture());
            WorkflowStepDataEntity entity = entityCaptor.getValue();

            assertEquals("SKIPPED", entity.getStatus());
            assertEquals("decision_branch_not_taken", entity.getSkipReason());
            assertEquals("core:decision", entity.getSkipSourceNode());
            assertEquals(5, entity.getItemIndex());
        }

        @Test
        @DisplayName("regression: SKIPPED rows include input data for inspector tabs")
        void skippedRowsIncludeInputDataForInspectorTabs() {
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();

            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-1");
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);
            when(stepPayloadService.persistSkippedNodePayload(anyString(), any(), anyInt())).thenReturn(storageId);

            service.recordSkippedNode(
                    execution, "core:apply_ops", "apply_ops", "Not routed to this branch",
                    "core:route_item", 1, 7, "trigger:start"
            );

            ArgumentCaptor<WorkflowStepDataEntity> entityCaptor =
                    ArgumentCaptor.forClass(WorkflowStepDataEntity.class);
            verify(nativeRepository).insertIgnoringDuplicate(entityCaptor.capture());

            Map<String, Object> inputData = entityCaptor.getValue().getInputData();
            assertNotNull(inputData);
            assertEquals(true, inputData.get("skipped"));
            assertEquals("Not routed to this branch", inputData.get("skip_reason"));
            assertEquals("core:route_item", inputData.get("skip_source_node"));
            assertEquals(1, inputData.get("item_index"));
            assertEquals(7, inputData.get("epoch"));
            assertEquals("trigger:start", inputData.get("trigger_id"));
            // last_upstream_item and last_upstream_snapshot removed - they duplicated
            // the full workflow context into each SKIPPED row, causing 225 MB of bloat.
            assertNull(inputData.get("last_upstream_item"));
            assertNull(inputData.get("last_upstream_snapshot"));
        }

        @Test
        @DisplayName("regression: SKIPPED rows no longer embed upstream data (OOM prevention)")
        void skippedRowsDoNotEmbedUpstreamData() {
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();

            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-1");
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);
            when(stepPayloadService.persistSkippedNodePayload(anyString(), any(), anyInt())).thenReturn(storageId);

            service.recordSkippedNode(
                    execution, "table:record_tech", "record_tech", "Predecessor agent:classify was skipped",
                    "agent:classify", 0, 82, "trigger:cron"
            );

            ArgumentCaptor<WorkflowStepDataEntity> entityCaptor =
                    ArgumentCaptor.forClass(WorkflowStepDataEntity.class);
            verify(nativeRepository).insertIgnoringDuplicate(entityCaptor.capture());

            Map<String, Object> inputData = entityCaptor.getValue().getInputData();
            assertNotNull(inputData);
            assertEquals(true, inputData.get("skipped"));
            assertNull(inputData.get("last_upstream_item"));
            assertNull(inputData.get("last_upstream_snapshot"));
        }

        @Test
        @DisplayName("Stamps skipped native inserts with organization from the async org scope")
        void skippedNativeInsertCarriesAsyncOrganizationScope() {
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();

            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-1");
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);
            when(stepPayloadService.persistSkippedNodePayload(anyString(), any(), anyInt())).thenReturn(storageId);

            TenantResolver.runWithOrgScope("org-async", () ->
                    service.recordSkippedNode(
                            execution, "mcp:step", "Step", "decision_branch_not_taken",
                            "core:decision", 0));

            ArgumentCaptor<WorkflowStepDataEntity> entityCaptor =
                    ArgumentCaptor.forClass(WorkflowStepDataEntity.class);
            verify(nativeRepository).insertIgnoringDuplicate(entityCaptor.capture());
            assertEquals("org-async", entityCaptor.getValue().getOrganizationId(),
                    "Native skipped workflow_step_data inserts must preserve organization scope across async boundaries");
        }

        @Test
        @DisplayName("Should set correct normalized key for mcp node")
        void shouldSetCorrectNormalizedKeyForMcpNode() {
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();

            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-1");
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);
            when(stepPayloadService.persistSkippedNodePayload(anyString(), any(), anyInt())).thenReturn(storageId);

            // nativeRepository.insertIgnoringDuplicate already set to return true

            ArgumentCaptor<WorkflowStepDataEntity> entityCaptor =
                    ArgumentCaptor.forClass(WorkflowStepDataEntity.class);

            service.recordSkippedNode(
                    execution, "mcp:api_call", "API Call", "skip_propagation",
                    "core:decision", 0
            );

            verify(nativeRepository).insertIgnoringDuplicate(entityCaptor.capture());
            assertEquals("mcp:api_call", entityCaptor.getValue().getNormalizedKey());
        }

        @Test
        @DisplayName("Should set correct normalized key for trigger node")
        void shouldSetCorrectNormalizedKeyForTriggerNode() {
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();

            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-1");
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);
            when(stepPayloadService.persistSkippedNodePayload(anyString(), any(), anyInt())).thenReturn(storageId);

            // nativeRepository.insertIgnoringDuplicate already set to return true

            ArgumentCaptor<WorkflowStepDataEntity> entityCaptor =
                    ArgumentCaptor.forClass(WorkflowStepDataEntity.class);

            service.recordSkippedNode(
                    execution, "trigger:webhook", "Webhook", "skip_propagation",
                    "core:decision", 0
            );

            verify(nativeRepository).insertIgnoringDuplicate(entityCaptor.capture());
            assertEquals("trigger:webhook", entityCaptor.getValue().getNormalizedKey());
        }

        @Test
        @DisplayName("Should return false when save throws exception")
        void shouldReturnFalseWhenSaveThrowsException() {
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();

            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-1");
            when(stepPayloadService.persistSkippedNodePayload(anyString(), any(), anyInt())).thenReturn(storageId);
            when(nativeRepository.insertIgnoringDuplicate(any())).thenThrow(new RuntimeException("DB error"));

            boolean result = service.recordSkippedNode(
                    execution, "mcp:step", "Step", "skip_propagation",
                    "core:decision", 0
            );

            assertFalse(result);
        }

        @Test
        @DisplayName("Should persist skip payload with correct data")
        void shouldPersistSkipPayloadWithCorrectData() {
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();

            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-1");
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);

            when(stepPayloadService.persistSkippedNodePayload(anyString(), any(), anyInt()))
                    .thenReturn(storageId);

            service.recordSkippedNode(
                    execution, "mcp:api_call", "API Call", "decision_branch_not_taken",
                    "core:decision", 3
            );

            ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(stepPayloadService).persistSkippedNodePayload(eq("tenant-1"), payloadCaptor.capture(), eq(0));

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = payloadCaptor.getValue();
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) payload.get("output");

            assertNotNull(output, "Skip payload should contain 'output' key");
            assertEquals("SKIPPED", output.get("status"));
            assertEquals("decision_branch_not_taken", output.get("skipReason"));
            assertEquals("core:decision", output.get("skipSourceNode"));
            assertEquals("mcp:api_call", output.get("nodeId"));
            assertEquals("API Call", output.get("nodeLabel"));
            assertEquals(3, output.get("itemIndex"));
        }

        @Test
        @DisplayName("Should include metadata with workflow context")
        void shouldIncludeMetadataWithWorkflowContext() {
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();

            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-1");
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);
            when(stepPayloadService.persistSkippedNodePayload(anyString(), any(), anyInt())).thenReturn(storageId);

            // nativeRepository.insertIgnoringDuplicate already set to return true

            ArgumentCaptor<WorkflowStepDataEntity> entityCaptor =
                    ArgumentCaptor.forClass(WorkflowStepDataEntity.class);

            service.recordSkippedNode(
                    execution, "mcp:step", "Step", "skip_propagation",
                    "core:decision", 0
            );

            verify(nativeRepository).insertIgnoringDuplicate(entityCaptor.capture());
            Map<String, Object> metadata = entityCaptor.getValue().getMetadata();

            assertNotNull(metadata);
            assertEquals(workflowRunId.toString(), metadata.get("workflowRunId"));
            assertEquals("workflow-1", metadata.get("workflowId"));
            assertEquals("tenant-1", metadata.get("tenantId"));
            assertEquals("skip_propagation", metadata.get("skipReason"));
            assertEquals("core:decision", metadata.get("skipSourceNode"));
            assertEquals("MCP", metadata.get("nodeType"));
        }

        @Test
        @DisplayName("Should set node_type column based on nodeId prefix")
        void shouldSetNodeTypeColumn() {
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();

            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-1");
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);
            when(stepPayloadService.persistSkippedNodePayload(anyString(), any(), anyInt())).thenReturn(storageId);

            ArgumentCaptor<WorkflowStepDataEntity> entityCaptor =
                    ArgumentCaptor.forClass(WorkflowStepDataEntity.class);

            service.recordSkippedNode(
                    execution, "mcp:step", "Step", "skip_propagation",
                    "core:decision", 0
            );

            verify(nativeRepository).insertIgnoringDuplicate(entityCaptor.capture());
            assertEquals(NodeType.MCP, entityCaptor.getValue().getNodeType());
        }

        @Test
        @DisplayName("Should resolve core node type from plan for core: prefix")
        void shouldResolveCoreNodeTypeFromPlan() {
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();

            Core transformCore = mock(Core.class);
            when(transformCore.getNormalizedKey()).thenReturn("core:falseaction");
            when(transformCore.type()).thenReturn("transform");

            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-1");
            when(plan.getCores()).thenReturn(List.of(transformCore));
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);
            when(stepPayloadService.persistSkippedNodePayload(anyString(), any(), anyInt())).thenReturn(storageId);

            ArgumentCaptor<WorkflowStepDataEntity> entityCaptor =
                    ArgumentCaptor.forClass(WorkflowStepDataEntity.class);

            service.recordSkippedNode(
                    execution, "core:falseaction", "FalseAction", "branch not selected",
                    "core:checkcondition", 0
            );

            verify(nativeRepository).insertIgnoringDuplicate(entityCaptor.capture());
            WorkflowStepDataEntity entity = entityCaptor.getValue();
            assertEquals(NodeType.TRANSFORM, entity.getNodeType());
            assertEquals("TRANSFORM", entity.getMetadata().get("nodeType"));
        }

        @Test
        @DisplayName("Should set trigger_id to default when not set")
        void shouldSetTriggerIdDefault() {
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();

            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-1");
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);
            when(stepPayloadService.persistSkippedNodePayload(anyString(), any(), anyInt())).thenReturn(storageId);

            ArgumentCaptor<WorkflowStepDataEntity> entityCaptor =
                    ArgumentCaptor.forClass(WorkflowStepDataEntity.class);

            service.recordSkippedNode(
                    execution, "mcp:step", "Step", "skip", "core:decision", 0
            );

            verify(nativeRepository).insertIgnoringDuplicate(entityCaptor.capture());
            assertEquals("trigger:default", entityCaptor.getValue().getTriggerId());
        }

        @Test
        @DisplayName("regression: SKIPPED rows inherit explicit triggerId and do not drift to trigger:default")
        void explicitTriggerIdHealsSkippedNodeRow() {
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();
            String realTriggerId = "trigger:start";

            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(execution.getRunId()).thenReturn("run-critical-3");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-1");
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);
            when(stepPayloadService.persistSkippedNodePayload(anyString(), any(), anyInt())).thenReturn(storageId);

            service.recordSkippedNode(
                    execution,
                    "core:apply_ops",
                    "Apply Ops",
                    "Predecessor core:route_item was skipped",
                    "core:route_item",
                    0,
                    1,
                    realTriggerId);

            ArgumentCaptor<WorkflowStepDataEntity> entityCaptor =
                    ArgumentCaptor.forClass(WorkflowStepDataEntity.class);
            verify(nativeRepository).insertIgnoringDuplicate(entityCaptor.capture());

            WorkflowStepDataEntity entity = entityCaptor.getValue();
            assertEquals(realTriggerId, entity.getTriggerId());
            assertNotEquals("trigger:default", entity.getTriggerId());
            verify(entityResolverService, never()).getCurrentEpochFromRun(workflowRunId);
        }

        @Test
        @DisplayName("regression: SKIPPED output payload carries the same explicit trigger_id as the DB row")
        void skipPayloadCarriesExplicitTriggerId() {
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();
            String realTriggerId = "trigger:start";

            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(execution.getRunId()).thenReturn("run-critical-3-payload");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-1");
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);
            when(stepPayloadService.persistSkippedNodePayload(anyString(), any(), anyInt())).thenReturn(storageId);

            service.recordSkippedNode(
                    execution,
                    "core:apply_default",
                    "Apply Default",
                    "Predecessor core:route_item was skipped",
                    "core:route_item",
                    0,
                    1,
                    realTriggerId);

            ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(stepPayloadService).persistSkippedNodePayload(eq("tenant-1"), payloadCaptor.capture(), eq(1));

            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) payloadCaptor.getValue().get("output");
            assertEquals(realTriggerId, output.get("trigger_id"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Edge cases tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should normalize step alias correctly")
        void shouldNormalizeStepAliasCorrectly() {
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();

            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-1");
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);
            when(stepPayloadService.persistSkippedNodePayload(anyString(), any(), anyInt())).thenReturn(storageId);

            // nativeRepository.insertIgnoringDuplicate already set to return true

            ArgumentCaptor<WorkflowStepDataEntity> entityCaptor =
                    ArgumentCaptor.forClass(WorkflowStepDataEntity.class);

            service.recordSkippedNode(
                    execution, "mcp:my_api", "My API Call", "skip_propagation",
                    "core:decision", 0
            );

            verify(nativeRepository).insertIgnoringDuplicate(entityCaptor.capture());
            assertEquals("my_api_call", entityCaptor.getValue().getStepAlias());
        }

        @Test
        @DisplayName("Should set iteration to 0 for skipped nodes")
        void shouldSetIterationToZeroForSkippedNodes() {
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();

            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-1");
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);
            when(stepPayloadService.persistSkippedNodePayload(anyString(), any(), anyInt())).thenReturn(storageId);

            // nativeRepository.insertIgnoringDuplicate already set to return true

            ArgumentCaptor<WorkflowStepDataEntity> entityCaptor =
                    ArgumentCaptor.forClass(WorkflowStepDataEntity.class);

            service.recordSkippedNode(
                    execution, "mcp:step", "Step", "skip_propagation",
                    "core:decision", 0
            );

            verify(nativeRepository).insertIgnoringDuplicate(entityCaptor.capture());
            assertEquals(0, entityCaptor.getValue().getIteration());
            assertEquals(0, entityCaptor.getValue().getSpawn(),
                "no rerun yet (resolver returns 0) → spawn 0");
        }

        @Test
        @DisplayName("regression: SKIPPED rows carry the run's current spawn after a rerun (was hardcoded 0)")
        void skippedRowsCarryCurrentSpawnAfterRerun() {
            // Bug: a rerun bumps the run's spawn; the re-evaluated decision writes SKIPPED
            // rows for the deactivated branch - those rows were stamped spawn=0, making
            // post-rerun skip state indistinguishable from pre-rerun rows. The spawn-aware
            // step aggregation then let the stale COMPLETED row dominate ("completed"
            // status on a branch the rerun deactivated).
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();

            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(0);
            when(entityResolverService.getCurrentSpawnFromRun(workflowRunId)).thenReturn(2); // after 2 reruns
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-1");
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);
            when(stepPayloadService.persistSkippedNodePayload(anyString(), any(), anyInt())).thenReturn(storageId);

            ArgumentCaptor<WorkflowStepDataEntity> entityCaptor =
                    ArgumentCaptor.forClass(WorkflowStepDataEntity.class);

            service.recordSkippedNode(
                    execution, "mcp:if_path", "If Path", "branch not selected",
                    "core:gate", 0
            );

            verify(nativeRepository).insertIgnoringDuplicate(entityCaptor.capture());
            assertEquals(2, entityCaptor.getValue().getSpawn(),
                "SKIPPED row must carry the run's current spawn, mirroring StepDataPersistenceService");
        }

        @Test
        @DisplayName("Should use current epoch from resolver")
        void shouldUseCurrentEpochFromResolver() {
            UUID workflowRunId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();

            when(entityResolverService.resolveWorkflowRunId(execution)).thenReturn(Optional.of(workflowRunId));
            when(entityResolverService.getCurrentEpochFromRun(workflowRunId)).thenReturn(3); // Epoch 3
            when(execution.getRunId()).thenReturn("run-123");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-1");
            when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);
            when(stepPayloadService.persistSkippedNodePayload(anyString(), any(), anyInt())).thenReturn(storageId);

            // nativeRepository.insertIgnoringDuplicate already set to return true

            ArgumentCaptor<WorkflowStepDataEntity> entityCaptor =
                    ArgumentCaptor.forClass(WorkflowStepDataEntity.class);

            service.recordSkippedNode(
                    execution, "mcp:step", "Step", "skip_propagation",
                    "core:decision", 0
            );

            verify(nativeRepository).insertIgnoringDuplicate(entityCaptor.capture());
            assertEquals(3, entityCaptor.getValue().getEpoch());
        }
    }
}
