package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.workflow.DecisionEvaluationInfo;
import com.apimarketplace.orchestrator.persistence.StepDataNativeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F10 regression - DecisionPersistenceService must persist the row even
 * when the storage-payload write fails. Pre-fix, a single transient
 * S3/MinIO hiccup discarded the decision row including its branch routing
 * decision (selected_branch column) - replay and post-mortem audit were
 * left with no record of which branch fired. Post-fix the row lands with
 * outputStorageId=NULL and metadata._storagePayloadFailed=true so
 * operators know the audit blob is missing but routing intent is preserved.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DecisionPersistenceService")
class DecisionPersistenceServiceTest {

    @Mock
    private StepDataNativeRepository nativeRepository;

    @Mock
    private StorageService storageService;

    private DecisionPersistenceService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        service = new DecisionPersistenceService(nativeRepository, storageService, objectMapper);
    }

    @Test
    @DisplayName("F10: persists decision row even when storage payload write returns null")
    void f10PersistsRowWhenStorageReturnsNull() {
        // Force storage payload to fail (returns null - F2-class swallow).
        when(storageService.saveJsonWithContext(
                anyString(), any(), anyString(), isNull(),
                isNull(), anyString(), isNull(), isNull(), anyInt(),
                anyString(), anyString())).thenReturn(null);
        when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);

        // DecisionPersistenceContext(runId, workflowRunId, workflowId, tenantId, currentEpoch)
        DecisionPersistenceService.DecisionPersistenceContext context =
                new DecisionPersistenceService.DecisionPersistenceContext(
                        "run-123", UUID.randomUUID(), "workflow-id", "tenant-1", 1);
        // DecisionEvaluationInfo(decisionNodeId, decisionNodeLabel, sourceStepId, selectedBranch, conditions, contextSnapshot)
        DecisionEvaluationInfo evaluation = new DecisionEvaluationInfo(
                "decision-node-1", "My Decision", "source-1", "if",
                List.of(), Map.of());

        boolean result = service.recordDecisionEvaluation(context, evaluation);

        // Pre-fix: storageId==null → return false → no row persisted → branch
        // routing lost from post-mortem. Post-fix: row STILL persists with
        // outputStorageId=null + metadata marker.
        assertThat(result)
                .as("F10: decision row must persist even when payload storage fails - routing intent is preserved")
                .isTrue();

        ArgumentCaptor<WorkflowStepDataEntity> captor = ArgumentCaptor.forClass(WorkflowStepDataEntity.class);
        verify(nativeRepository).insertIgnoringDuplicate(captor.capture());
        WorkflowStepDataEntity captured = captor.getValue();

        assertThat(captured.getOutputStorageId())
                .as("Storage failed → outputStorageId must reflect that")
                .isNull();
        assertThat(captured.getSelectedBranch())
                .as("Branch routing decision (the load-bearing field for replay/audit) must be preserved")
                .isEqualTo("if");
        assertThat(captured.getMetadata())
                .as("Metadata must carry the _storagePayloadFailed marker so operators know the audit blob is missing")
                .containsEntry("_storagePayloadFailed", true);
    }

    @Test
    @DisplayName("Happy path: payload persisted, no marker stamp")
    void happyPathPersistsNormally() {
        UUID storageId = UUID.randomUUID();
        when(storageService.saveJsonWithContext(
                anyString(), any(), anyString(), isNull(),
                isNull(), anyString(), isNull(), isNull(), anyInt(),
                anyString(), anyString())).thenReturn(storageId);
        when(nativeRepository.insertIgnoringDuplicate(any())).thenReturn(true);

        // DecisionPersistenceContext(runId, workflowRunId, workflowId, tenantId, currentEpoch)
        DecisionPersistenceService.DecisionPersistenceContext context =
                new DecisionPersistenceService.DecisionPersistenceContext(
                        "run-123", UUID.randomUUID(), "workflow-id", "tenant-1", 1);
        // DecisionEvaluationInfo(decisionNodeId, decisionNodeLabel, sourceStepId, selectedBranch, conditions, contextSnapshot)
        DecisionEvaluationInfo evaluation = new DecisionEvaluationInfo(
                "decision-node-1", "My Decision", "source-1", "if",
                List.of(), Map.of());

        boolean result = service.recordDecisionEvaluation(context, evaluation);

        assertThat(result).isTrue();

        ArgumentCaptor<WorkflowStepDataEntity> captor = ArgumentCaptor.forClass(WorkflowStepDataEntity.class);
        verify(nativeRepository).insertIgnoringDuplicate(captor.capture());
        WorkflowStepDataEntity captured = captor.getValue();
        assertThat(captured.getOutputStorageId()).isEqualTo(storageId);
        // Marker MUST be absent on the happy path so the FE doesn't trigger
        // the "audit blob missing" fallback for healthy decisions.
        if (captured.getMetadata() != null) {
            assertThat(captured.getMetadata()).doesNotContainKey("_storagePayloadFailed");
        }
    }
}
