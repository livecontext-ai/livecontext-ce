package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.persistence.StepDataNativeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code workflow_step_data.metadata} flag of a FAILED row whose traversal continues.
 *
 * <p>Split routing reads that column ({@code WorkflowStepDataRepository.findPassedItemIndicesByEpoch}),
 * so a split item that failed with {@code continueOnFailure} still reaches the nodes below it. Without
 * the flag on the row, the item drops out of the split exactly like a plain failure. Only a flagged
 * output sets it: every other row stays byte-identical.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepDataPersistenceService - continueOnFailure flag on the step row")
class StepDataPersistenceServiceContinueOnFailureTest {

    @Mock private StepDataNativeRepository nativeRepository;
    @Mock private StepPayloadService stepPayloadService;
    @Mock private StepMetadataBuilder metadataBuilder;
    @Mock private WorkflowEntityResolverService entityResolverService;

    private StepDataPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new StepDataPersistenceService(
            nativeRepository, stepPayloadService, metadataBuilder, entityResolverService);
    }

    private static StepExecutionResult failed(Map<String, Object> output) {
        return new StepExecutionResult("mcp:call", NodeStatus.FAILED, "provider down", output, 5L, null);
    }

    @Test
    @DisplayName("REGRESSION: a continued FAILED output stamps metadata.policy_continue_on_failure=true on the row")
    void flaggedFailureStampsTheRowMetadata() {
        WorkflowStepDataEntity entity = new WorkflowStepDataEntity();

        service.enrichEntityWithNodeTypeFields(entity, "mcp:call", failed(Map.of(
            "error", "provider down", ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, true)));

        assertThat(entity.getMetadata()).containsEntry(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, true);
    }

    @Test
    @DisplayName("an unflagged FAILED output leaves the row metadata without the flag")
    void unflaggedFailureLeavesTheRowUnflagged() {
        WorkflowStepDataEntity entity = new WorkflowStepDataEntity();

        service.enrichEntityWithNodeTypeFields(entity, "mcp:call", failed(Map.of("error", "provider down")));

        assertThat(entity.getMetadata()).doesNotContainKey(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE);
    }

    @Test
    @DisplayName("a policy_continue_on_failure=false value is not taken for the flag")
    void falseValueIsNotTheFlag() {
        WorkflowStepDataEntity entity = new WorkflowStepDataEntity();

        service.enrichEntityWithNodeTypeFields(entity, "mcp:call", failed(Map.of(
            "error", "provider down", ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, false)));

        assertThat(entity.getMetadata()).doesNotContainKey(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE);
    }

    @Test
    @DisplayName("the flag is ADDED to metadata already on the row, nothing there is replaced")
    void flagIsMergedIntoExistingMetadata() {
        WorkflowStepDataEntity entity = new WorkflowStepDataEntity();
        Map<String, Object> existing = new HashMap<>();
        existing.put("statusMessage", "provider down");
        entity.setMetadata(existing);

        service.enrichEntityWithNodeTypeFields(entity, "mcp:call", failed(Map.of(
            "error", "provider down", ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, true)));

        assertThat(entity.getMetadata())
            .containsEntry("statusMessage", "provider down")
            .containsEntry(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, true);
    }
}
