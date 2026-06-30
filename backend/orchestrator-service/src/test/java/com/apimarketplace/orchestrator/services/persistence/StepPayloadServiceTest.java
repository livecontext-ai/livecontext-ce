package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.common.storage.exception.QuotaExceededException;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StepPayloadService.
 *
 * This service is responsible for persisting step payload data to storage.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepPayloadService")
class StepPayloadServiceTest {

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
    // persistStepPayload() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("persistStepPayload()")
    class PersistStepPayloadTests {

        @Test
        @DisplayName("Should persist payload for successful step")
        void shouldPersistPayloadForSuccessfulStep() {
            UUID expectedStorageId = UUID.randomUUID();
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), any(), any()))
                    .thenReturn(expectedStorageId);

            StepExecutionResult result = new StepExecutionResult(
                    "step-1", NodeStatus.COMPLETED, "Success",
                    Map.of("data", "value"), 100L, null
            );

            UUID storageId = service.persistStepPayload(execution, "step-1", "alias", result, Map.of(), 0);

            assertEquals(expectedStorageId, storageId);
            verify(storageService).saveJsonWithContext(eq("tenant-1"), any(Map.class), anyString(), isNull(), isNull(), any(), anyString(), anyInt(), anyInt(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("Should use extraMetadata when result is null (decision payload)")
        void shouldUseExtraMetadataWhenResultIsNull() {
            UUID expectedStorageId = UUID.randomUUID();
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any()))
                    .thenReturn(expectedStorageId);

            Map<String, Object> decisionMetadata = new HashMap<>(Map.of("selectedBranch", "if", "conditionResult", true));

            UUID storageId = service.persistStepPayload(execution, "decision-1", "alias", null, decisionMetadata, 0);

            assertEquals(expectedStorageId, storageId);
        }

        @Test
        @DisplayName("Should return null when storage throws exception")
        void shouldReturnNullWhenStorageThrowsException() {
            when(execution.getPlan()).thenReturn(plan);
            when(execution.getRunId()).thenReturn("run-123");
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), any(), any()))
                    .thenThrow(new RuntimeException("Storage error"));

            StepExecutionResult result = new StepExecutionResult(
                    "step-1", NodeStatus.COMPLETED, "Success",
                    Map.of("data", "value"), 100L, null
            );

            UUID storageId = service.persistStepPayload(execution, "step-1", "alias", result, Map.of(), 0);

            assertNull(storageId);
        }

        @Test
        @DisplayName("F2: QuotaExceededException is caught and surfaces as null, distinct from generic Exception")
        void quotaExceededExceptionIsCaughtDistinctly() {
            // F2 regression: pre-fix, both QuotaExceededException and generic
            // Exception were caught by `catch (Exception e)` and logged with the
            // same WARN message, making it impossible for operators to distinguish
            // "tenant action needed" from "infra action needed". Post-fix the
            // explicit `catch (QuotaExceededException)` runs first with a
            // quota-specific ERROR message including the tenantId. Return contract
            // (null UUID) is intentionally preserved - the caller in
            // StepDataPersistenceService stamps the row's error_message marker so
            // the failure remains visible to the user without changing the
            // exception flow shape consumed by 9+ caller sites.
            when(execution.getPlan()).thenReturn(plan);
            when(execution.getRunId()).thenReturn("run-quota");
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), any(), any()))
                    .thenThrow(new QuotaExceededException("Quota hard limit atteint", "tenant-1"));

            StepExecutionResult result = new StepExecutionResult(
                    "step-1", NodeStatus.COMPLETED, "Success",
                    Map.of("data", "value"), 100L, null
            );

            UUID storageId = service.persistStepPayload(execution, "step-1", "alias", result, Map.of(), 0);

            assertNull(storageId, "Contract: returns null so caller can stamp the row - never re-throws "
                    + "across the 9+ caller sites that currently treat the null contract as authoritative.");
        }

        @Test
        @DisplayName("Should extract tool UUID from step when available")
        void shouldExtractToolUuidFromStepWhenAvailable() {
            UUID expectedStorageId = UUID.randomUUID();
            UUID toolUuid = UUID.randomUUID();
            Step step = mock(Step.class);
            when(step.id()).thenReturn(toolUuid.toString());

            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.findStep(anyString())).thenReturn(Optional.of(step));
            when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), any(), any()))
                    .thenReturn(expectedStorageId);

            StepExecutionResult result = new StepExecutionResult(
                    "step-1", NodeStatus.COMPLETED, "Success",
                    Map.of("data", "value"), 100L, null
            );

            UUID storageId = service.persistStepPayload(execution, "step-1", "alias", result, Map.of(), 0);

            assertEquals(expectedStorageId, storageId);
            verify(storageService).saveJsonWithContext(eq("tenant-1"), any(Map.class), anyString(), isNull(), eq(toolUuid), any(), anyString(), anyInt(), anyInt(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("Should handle invalid UUID in step id gracefully")
        void shouldHandleInvalidUuidInStepIdGracefully() {
            UUID expectedStorageId = UUID.randomUUID();
            Step step = mock(Step.class);
            when(step.id()).thenReturn("not-a-uuid");

            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.findStep(anyString())).thenReturn(Optional.of(step));
            when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), any(), any()))
                    .thenReturn(expectedStorageId);

            StepExecutionResult result = new StepExecutionResult(
                    "step-1", NodeStatus.COMPLETED, "Success",
                    Map.of("data", "value"), 100L, null
            );

            UUID storageId = service.persistStepPayload(execution, "step-1", "alias", result, Map.of(), 0);

            assertEquals(expectedStorageId, storageId);
            verify(storageService).saveJsonWithContext(eq("tenant-1"), any(Map.class), anyString(), isNull(), isNull(), any(), anyString(), anyInt(), anyInt(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("Should remove redundant fields from output payload")
        void shouldRemoveRedundantFieldsFromOutputPayload() {
            UUID expectedStorageId = UUID.randomUUID();
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.findStep(anyString())).thenReturn(Optional.empty());

            ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            when(storageService.saveJsonWithContext(anyString(), payloadCaptor.capture(), anyString(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), any(), any()))
                    .thenReturn(expectedStorageId);

            Map<String, Object> output = new HashMap<>();
            output.put("result", "success");
            output.put("resolved_params", Map.of("param", "value")); // Should be removed (in dedicated DB column)
            output.put("http_status", 200); // Should be removed
            output.put("iteration", 5); // Should be removed
            output.put("status", "COMPLETED"); // Should be removed

            StepExecutionResult result = new StepExecutionResult(
                    "step-1", NodeStatus.COMPLETED, "Success", output, 100L, null
            );

            service.persistStepPayload(execution, "step-1", "alias", result, Map.of(), 0);

            @SuppressWarnings("unchecked")
            Map<String, Object> savedPayload = payloadCaptor.getValue();
            @SuppressWarnings("unchecked")
            Map<String, Object> outputInPayload = (Map<String, Object>) savedPayload.get("output");

            assertNotNull(outputInPayload);
            assertTrue(outputInPayload.containsKey("result"));
            assertFalse(outputInPayload.containsKey("resolved_params"));
            assertFalse(outputInPayload.containsKey("http_status"));
            assertFalse(outputInPayload.containsKey("iteration"));
            assertFalse(outputInPayload.containsKey("status"));
        }

        @Test
        @DisplayName("Should preserve trigger payload field named processed_items")
        void shouldPreserveTriggerPayloadFieldNamedProcessedItems() {
            UUID expectedStorageId = UUID.randomUUID();
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.findStep(anyString())).thenReturn(Optional.empty());

            ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            when(storageService.saveJsonWithContext(anyString(), payloadCaptor.capture(), anyString(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), any(), any()))
                    .thenReturn(expectedStorageId);

            Map<String, Object> output = new HashMap<>();
            output.put("processed_items", java.util.List.of(Map.of("id", "processed-1")));
            output.put("current_email", java.util.List.of(Map.of("id", "email-1")));
            output.put("trigger_id", "trigger:start");
            output.put("item_index", 0);

            StepExecutionResult result = new StepExecutionResult(
                    "trigger:start", NodeStatus.COMPLETED, "Success", output, 100L, null
            );

            service.persistStepPayload(execution, "trigger:start", "start", result, Map.of(), 1);

            @SuppressWarnings("unchecked")
            Map<String, Object> savedPayload = payloadCaptor.getValue();
            @SuppressWarnings("unchecked")
            Map<String, Object> outputInPayload = (Map<String, Object>) savedPayload.get("output");

            assertNotNull(outputInPayload);
            assertEquals(output.get("processed_items"), outputInPayload.get("processed_items"));
            assertEquals(output.get("current_email"), outputInPayload.get("current_email"));
            assertFalse(outputInPayload.containsKey("trigger_id"), "Column-backed trigger metadata should still be stripped");
            assertFalse(outputInPayload.containsKey("item_index"), "Column-backed item metadata should still be stripped");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // persistSkippedNodePayload() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("persistSkippedNodePayload()")
    class PersistSkippedNodePayloadTests {

        @Test
        @DisplayName("Should persist skipped node payload")
        void shouldPersistSkippedNodePayload() {
            UUID expectedStorageId = UUID.randomUUID();
            when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), any(), any(), anyInt(), any(), any()))
                    .thenReturn(expectedStorageId);

            Map<String, Object> skipPayload = Map.of(
                    "status", "SKIPPED",
                    "skipReason", "decision_branch_not_taken"
            );

            UUID storageId = service.persistSkippedNodePayload("tenant-1", skipPayload);

            assertEquals(expectedStorageId, storageId);
            verify(storageService).saveJsonWithContext(eq("tenant-1"), eq(skipPayload), anyString(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(0), isNull(), eq("SKIPPED_NODE"));
        }

        @Test
        @DisplayName("Should return null when storage throws exception")
        void shouldReturnNullWhenStorageThrowsException() {
            when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), any(), any(), anyInt(), any(), any()))
                    .thenThrow(new RuntimeException("Storage error"));

            Map<String, Object> skipPayload = Map.of("status", "SKIPPED");

            UUID storageId = service.persistSkippedNodePayload("tenant-1", skipPayload);

            assertNull(storageId);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // buildStepKey() tests (verified via stepKey argument to saveJsonWithContext)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("buildStepKey() - step key prefix derivation")
    class BuildStepKeyTests {

        @Test
        @DisplayName("Should use mcp: prefix when alias has no prefix and stepId has no prefix")
        void shouldUseMcpPrefixWhenNoPrefixAvailable() {
            UUID expectedStorageId = UUID.randomUUID();
            when(execution.getPlan()).thenReturn(plan);
            when(execution.getRunId()).thenReturn("run-1");
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.findStep(anyString())).thenReturn(Optional.empty());

            ArgumentCaptor<String> stepKeyCaptor = ArgumentCaptor.forClass(String.class);
            when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), stepKeyCaptor.capture(), anyInt(), anyInt(), anyInt(), any(), any()))
                    .thenReturn(expectedStorageId);

            StepExecutionResult result = new StepExecutionResult(
                    "step-1", NodeStatus.COMPLETED, "Success",
                    Map.of("data", "value"), 100L, null
            );

            service.persistStepPayload(execution, "some_step", "alias", result, Map.of(), 0);

            assertEquals("mcp:alias", stepKeyCaptor.getValue());
        }

        @Test
        @DisplayName("Should use core: prefix when alias has no prefix but stepId starts with core:")
        void shouldUseCorePrefixWhenStepIdIsCoreKey() {
            UUID expectedStorageId = UUID.randomUUID();
            when(execution.getPlan()).thenReturn(plan);
            when(execution.getRunId()).thenReturn("run-1");
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.findStep(anyString())).thenReturn(Optional.empty());

            ArgumentCaptor<String> stepKeyCaptor = ArgumentCaptor.forClass(String.class);
            when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), stepKeyCaptor.capture(), anyInt(), anyInt(), anyInt(), any(), any()))
                    .thenReturn(expectedStorageId);

            StepExecutionResult result = new StepExecutionResult(
                    "step-1", NodeStatus.COMPLETED, "Success",
                    Map.of("data", "value"), 100L, null
            );

            // stepId has core: prefix, alias has no prefix
            service.persistStepPayload(execution, "core:aggregate_posts", "aggregate_posts", result, Map.of(), 0);

            assertEquals("core:aggregate_posts", stepKeyCaptor.getValue());
        }

        @Test
        @DisplayName("Should use agent: prefix when alias has no prefix but stepId starts with agent:")
        void shouldUseAgentPrefixWhenStepIdIsAgentKey() {
            UUID expectedStorageId = UUID.randomUUID();
            when(execution.getPlan()).thenReturn(plan);
            when(execution.getRunId()).thenReturn("run-1");
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.findStep(anyString())).thenReturn(Optional.empty());

            ArgumentCaptor<String> stepKeyCaptor = ArgumentCaptor.forClass(String.class);
            when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), stepKeyCaptor.capture(), anyInt(), anyInt(), anyInt(), any(), any()))
                    .thenReturn(expectedStorageId);

            StepExecutionResult result = new StepExecutionResult(
                    "step-1", NodeStatus.COMPLETED, "Success",
                    Map.of("data", "value"), 100L, null
            );

            service.persistStepPayload(execution, "agent:my_agent", "my_agent", result, Map.of(), 0);

            assertEquals("agent:my_agent", stepKeyCaptor.getValue());
        }

        @Test
        @DisplayName("Should use trigger: prefix when alias has no prefix but stepId starts with trigger:")
        void shouldUseTriggerPrefixWhenStepIdIsTriggerKey() {
            UUID expectedStorageId = UUID.randomUUID();
            when(execution.getPlan()).thenReturn(plan);
            when(execution.getRunId()).thenReturn("run-1");
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.findStep(anyString())).thenReturn(Optional.empty());

            ArgumentCaptor<String> stepKeyCaptor = ArgumentCaptor.forClass(String.class);
            when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), stepKeyCaptor.capture(), anyInt(), anyInt(), anyInt(), any(), any()))
                    .thenReturn(expectedStorageId);

            StepExecutionResult result = new StepExecutionResult(
                    "step-1", NodeStatus.COMPLETED, "Success",
                    Map.of("data", "value"), 100L, null
            );

            service.persistStepPayload(execution, "trigger:my_webhook", "my_webhook", result, Map.of(), 0);

            assertEquals("trigger:my_webhook", stepKeyCaptor.getValue());
        }

        @Test
        @DisplayName("Should use prefix from alias when alias already has a prefix")
        void shouldUsePrefixFromAliasWhenAliasHasPrefix() {
            UUID expectedStorageId = UUID.randomUUID();
            when(execution.getPlan()).thenReturn(plan);
            when(execution.getRunId()).thenReturn("run-1");
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.findStep(anyString())).thenReturn(Optional.empty());

            ArgumentCaptor<String> stepKeyCaptor = ArgumentCaptor.forClass(String.class);
            when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), stepKeyCaptor.capture(), anyInt(), anyInt(), anyInt(), any(), any()))
                    .thenReturn(expectedStorageId);

            StepExecutionResult result = new StepExecutionResult(
                    "step-1", NodeStatus.COMPLETED, "Success",
                    Map.of("data", "value"), 100L, null
            );

            service.persistStepPayload(execution, "core:split_posts", "core:split_posts", result, Map.of(), 0);

            assertEquals("core:split_posts", stepKeyCaptor.getValue());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Edge cases tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle empty output gracefully")
        void shouldHandleEmptyOutputGracefully() {
            UUID expectedStorageId = UUID.randomUUID();
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), any(), any()))
                    .thenReturn(expectedStorageId);

            StepExecutionResult result = new StepExecutionResult(
                    "step-1", NodeStatus.COMPLETED, "Success",
                    Map.of(), 100L, null // Empty output
            );

            UUID storageId = service.persistStepPayload(execution, "step-1", "alias", result, Map.of(), 0);

            assertEquals(expectedStorageId, storageId);
        }

        @Test
        @DisplayName("Should handle null output in result gracefully")
        void shouldHandleNullOutputInResultGracefully() {
            UUID expectedStorageId = UUID.randomUUID();
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.findStep(anyString())).thenReturn(Optional.empty());
            when(storageService.saveJsonWithContext(anyString(), any(), anyString(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), any(), any()))
                    .thenReturn(expectedStorageId);

            StepExecutionResult result = new StepExecutionResult(
                    "step-1", NodeStatus.COMPLETED, "Success",
                    null, 100L, null // Null output
            );

            UUID storageId = service.persistStepPayload(execution, "step-1", "alias", result, Map.of(), 0);

            assertEquals(expectedStorageId, storageId);
        }

        @Test
        @DisplayName("Should add extraMetadata to payload")
        void shouldAddExtraMetadataToPayload() {
            UUID expectedStorageId = UUID.randomUUID();
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.findStep(anyString())).thenReturn(Optional.empty());

            ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            when(storageService.saveJsonWithContext(anyString(), payloadCaptor.capture(), anyString(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), any(), any()))
                    .thenReturn(expectedStorageId);

            StepExecutionResult result = new StepExecutionResult(
                    "step-1", NodeStatus.COMPLETED, "Success",
                    Map.of("data", "value"), 100L, null
            );

            Map<String, Object> extraMetadata = Map.of("customField", "customValue");

            service.persistStepPayload(execution, "step-1", "alias", result, extraMetadata, 0);

            @SuppressWarnings("unchecked")
            Map<String, Object> savedPayload = payloadCaptor.getValue();
            assertEquals("customValue", savedPayload.get("customField"));
        }
    }
}
