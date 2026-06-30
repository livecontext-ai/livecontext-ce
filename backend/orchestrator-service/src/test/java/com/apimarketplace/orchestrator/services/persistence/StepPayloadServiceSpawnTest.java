package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StepPayloadService spawn propagation fix.
 *
 * The fix: persistStepPayload() now extracts spawn from the result output
 * and passes it to the 12-parameter saveJsonWithContext overload.
 *
 * Tests both the private extractSpawn() method via reflection and the
 * end-to-end spawn propagation through persistStepPayload().
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepPayloadService spawn propagation")
class StepPayloadServiceSpawnTest {

    @Mock
    private StorageService storageService;

    @Mock
    private OutputSchemaMapper outputSchemaMapper;

    @Mock
    private WorkflowExecution execution;

    @Mock
    private WorkflowPlan plan;

    private StepPayloadService service;

    @BeforeEach
    void setUp() {
        service = new StepPayloadService(storageService, outputSchemaMapper);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // extractSpawn() tests via reflection
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("extractSpawn() via reflection")
    class ExtractSpawnTests {

        private Method extractSpawn;

        @BeforeEach
        void setUpReflection() throws Exception {
            extractSpawn = StepPayloadService.class.getDeclaredMethod("extractSpawn", StepExecutionResult.class);
            extractSpawn.setAccessible(true);
        }

        @Test
        @DisplayName("Should extract spawn value from result output")
        void extractSpawn_fromResultOutput_returnsSpawnValue() throws Exception {
            StepExecutionResult result = new StepExecutionResult(
                "step-1", NodeStatus.COMPLETED, "Success",
                Map.of("spawn", 3, "data", "value"), 100L, null
            );

            int spawn = (int) extractSpawn.invoke(service, result);
            assertEquals(3, spawn);
        }

        @Test
        @DisplayName("Should return 0 when result is null")
        void extractSpawn_withNullResult_returnsZero() throws Exception {
            int spawn = (int) extractSpawn.invoke(service, (StepExecutionResult) null);
            assertEquals(0, spawn);
        }

        @Test
        @DisplayName("Should return 0 when output is null")
        void extractSpawn_withNullOutput_returnsZero() throws Exception {
            StepExecutionResult result = new StepExecutionResult(
                "step-1", NodeStatus.COMPLETED, "Success",
                null, 100L, null
            );

            int spawn = (int) extractSpawn.invoke(service, result);
            assertEquals(0, spawn);
        }

        @Test
        @DisplayName("Should return 0 when output has no spawn key")
        void extractSpawn_withMissingKey_returnsZero() throws Exception {
            StepExecutionResult result = new StepExecutionResult(
                "step-1", NodeStatus.COMPLETED, "Success",
                Map.of("data", "value", "other", 42), 100L, null
            );

            int spawn = (int) extractSpawn.invoke(service, result);
            assertEquals(0, spawn);
        }

        @Test
        @DisplayName("Should handle spawn as Long type")
        void extractSpawn_withLongValue_returnsIntValue() throws Exception {
            Map<String, Object> output = new HashMap<>();
            output.put("spawn", 5L);
            StepExecutionResult result = new StepExecutionResult(
                "step-1", NodeStatus.COMPLETED, "Success",
                output, 100L, null
            );

            int spawn = (int) extractSpawn.invoke(service, result);
            assertEquals(5, spawn);
        }

        @Test
        @DisplayName("Should handle spawn as Double type")
        void extractSpawn_withDoubleValue_returnsIntValue() throws Exception {
            Map<String, Object> output = new HashMap<>();
            output.put("spawn", 7.0);
            StepExecutionResult result = new StepExecutionResult(
                "step-1", NodeStatus.COMPLETED, "Success",
                output, 100L, null
            );

            int spawn = (int) extractSpawn.invoke(service, result);
            assertEquals(7, spawn);
        }

        @Test
        @DisplayName("Should return 0 when spawn value is a non-Number type")
        void extractSpawn_withNonNumberValue_returnsZero() throws Exception {
            Map<String, Object> output = new HashMap<>();
            output.put("spawn", "not-a-number");
            StepExecutionResult result = new StepExecutionResult(
                "step-1", NodeStatus.COMPLETED, "Success",
                output, 100L, null
            );

            int spawn = (int) extractSpawn.invoke(service, result);
            assertEquals(0, spawn);
        }

        @Test
        @DisplayName("Should return 0 when spawn value is null in map")
        void extractSpawn_withNullValueInMap_returnsZero() throws Exception {
            Map<String, Object> output = new HashMap<>();
            output.put("spawn", null);
            StepExecutionResult result = new StepExecutionResult(
                "step-1", NodeStatus.COMPLETED, "Success",
                output, 100L, null
            );

            int spawn = (int) extractSpawn.invoke(service, result);
            assertEquals(0, spawn);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // End-to-end spawn propagation through persistStepPayload()
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Spawn propagation through persistStepPayload()")
    class SpawnPropagationTests {

        @BeforeEach
        void setUpExecution() {
            lenient().when(execution.getPlan()).thenReturn(plan);
            lenient().when(execution.getRunId()).thenReturn("run-123");
            lenient().when(plan.getTenantId()).thenReturn("tenant-1");
            lenient().when(plan.getId()).thenReturn("workflow-1");
            lenient().when(plan.findStep(anyString())).thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("Should pass spawn=3 to 12-param saveJsonWithContext when output has spawn=3")
        void persistStepPayload_passesSpawnFromOutput() {
            UUID expectedStorageId = UUID.randomUUID();
            when(storageService.saveJsonWithContext(
                anyString(), any(), anyString(), any(), any(),
                anyString(), anyString(), anyInt(), anyInt(), anyInt(),
                anyString(), anyString()
            )).thenReturn(expectedStorageId);

            Map<String, Object> output = new HashMap<>();
            output.put("data", "value");
            output.put("spawn", 3);
            StepExecutionResult result = new StepExecutionResult(
                "step-1", NodeStatus.COMPLETED, "Success",
                output, 100L, null
            );

            UUID storageId = service.persistStepPayload(execution, "mcp:step-1", "step-1", result, Map.of(), 2);

            assertNotNull(storageId);
            // Verify that spawn=3 was passed (10th argument, 0-indexed position 9)
            verify(storageService).saveJsonWithContext(
                eq("tenant-1"),       // tenantId
                any(),                // payload
                anyString(),          // contentType
                isNull(),             // expiresAt
                isNull(),             // toolUuid (no step found)
                eq("run-123"),        // runId
                anyString(),          // stepKey
                anyInt(),             // itemIndex
                eq(2),                // epoch
                eq(3),                // spawn - THE FIX
                eq("workflow-1"),     // workflowId
                eq("STEP_OUTPUT")     // sourceType
            );
        }

        @Test
        @DisplayName("Should pass spawn=0 when output has no spawn key")
        void persistStepPayload_passesZeroSpawnWhenMissing() {
            UUID expectedStorageId = UUID.randomUUID();
            when(storageService.saveJsonWithContext(
                anyString(), any(), anyString(), any(), any(),
                anyString(), anyString(), anyInt(), anyInt(), anyInt(),
                anyString(), anyString()
            )).thenReturn(expectedStorageId);

            StepExecutionResult result = new StepExecutionResult(
                "step-1", NodeStatus.COMPLETED, "Success",
                Map.of("data", "value"), 100L, null
            );

            service.persistStepPayload(execution, "mcp:step-1", "step-1", result, Map.of(), 0);

            verify(storageService).saveJsonWithContext(
                eq("tenant-1"),
                any(),
                anyString(),
                isNull(),
                isNull(),
                eq("run-123"),
                anyString(),
                anyInt(),
                eq(0),                // epoch
                eq(0),                // spawn = 0 (no spawn in output)
                eq("workflow-1"),
                eq("STEP_OUTPUT")
            );
        }

        @Test
        @DisplayName("Should pass spawn=0 when result output is null")
        void persistStepPayload_passesZeroSpawnWhenOutputNull() {
            UUID expectedStorageId = UUID.randomUUID();
            // When result is null, it goes to persistDecisionPayload path
            // which uses 11-param overload. So let's test with non-null result but null output.
            when(storageService.saveJsonWithContext(
                anyString(), any(), anyString(), any(), any(),
                anyString(), anyString(), anyInt(), anyInt(), anyInt(),
                anyString(), anyString()
            )).thenReturn(expectedStorageId);

            StepExecutionResult result = new StepExecutionResult(
                "step-1", NodeStatus.COMPLETED, "Success",
                null, 100L, null
            );

            service.persistStepPayload(execution, "mcp:step-1", "step-1", result, Map.of(), 1);

            verify(storageService).saveJsonWithContext(
                eq("tenant-1"),
                any(),
                anyString(),
                isNull(),
                isNull(),
                eq("run-123"),
                anyString(),
                anyInt(),
                eq(1),                // epoch
                eq(0),                // spawn = 0 (null output)
                eq("workflow-1"),
                eq("STEP_OUTPUT")
            );
        }

        @Test
        @DisplayName("Should pass large spawn values correctly")
        void persistStepPayload_passesLargeSpawnValue() {
            UUID expectedStorageId = UUID.randomUUID();
            when(storageService.saveJsonWithContext(
                anyString(), any(), anyString(), any(), any(),
                anyString(), anyString(), anyInt(), anyInt(), anyInt(),
                anyString(), anyString()
            )).thenReturn(expectedStorageId);

            Map<String, Object> output = new HashMap<>();
            output.put("data", "value");
            output.put("spawn", 42);
            StepExecutionResult result = new StepExecutionResult(
                "step-1", NodeStatus.COMPLETED, "Success",
                output, 100L, null
            );

            service.persistStepPayload(execution, "mcp:step-1", "step-1", result, Map.of(), 5);

            verify(storageService).saveJsonWithContext(
                eq("tenant-1"),
                any(),
                anyString(),
                isNull(),
                isNull(),
                eq("run-123"),
                anyString(),
                anyInt(),
                eq(5),                // epoch
                eq(42),               // spawn = 42
                eq("workflow-1"),
                eq("STEP_OUTPUT")
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // extractItemIndex() tests via reflection (companion method)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("extractItemIndex() via reflection")
    class ExtractItemIndexTests {

        private Method extractItemIndex;

        @BeforeEach
        void setUpReflection() throws Exception {
            extractItemIndex = StepPayloadService.class.getDeclaredMethod("extractItemIndex", StepExecutionResult.class);
            extractItemIndex.setAccessible(true);
        }

        @Test
        @DisplayName("Should extract item_index from result output")
        void extractItemIndex_fromOutput_returnsValue() throws Exception {
            StepExecutionResult result = new StepExecutionResult(
                "step-1", NodeStatus.COMPLETED, "Success",
                Map.of("item_index", 5), 100L, null
            );

            Integer itemIndex = (Integer) extractItemIndex.invoke(service, result);
            assertEquals(5, itemIndex);
        }

        @Test
        @DisplayName("Should extract itemIndex (camelCase) from result output")
        void extractItemIndex_camelCase_returnsValue() throws Exception {
            StepExecutionResult result = new StepExecutionResult(
                "step-1", NodeStatus.COMPLETED, "Success",
                Map.of("itemIndex", 7), 100L, null
            );

            Integer itemIndex = (Integer) extractItemIndex.invoke(service, result);
            assertEquals(7, itemIndex);
        }

        @Test
        @DisplayName("Should return 0 when result is null")
        void extractItemIndex_nullResult_returnsZero() throws Exception {
            Integer itemIndex = (Integer) extractItemIndex.invoke(service, (StepExecutionResult) null);
            assertEquals(0, itemIndex);
        }

        @Test
        @DisplayName("Should return 0 when output has no item index key")
        void extractItemIndex_missingKey_returnsZero() throws Exception {
            StepExecutionResult result = new StepExecutionResult(
                "step-1", NodeStatus.COMPLETED, "Success",
                Map.of("other", "value"), 100L, null
            );

            Integer itemIndex = (Integer) extractItemIndex.invoke(service, result);
            assertEquals(0, itemIndex);
        }
    }
}
