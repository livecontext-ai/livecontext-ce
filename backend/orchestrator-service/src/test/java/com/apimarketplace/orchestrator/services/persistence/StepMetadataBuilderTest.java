package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.orchestrator.domain.workflow.DecisionEvaluationInfo;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StepMetadataBuilder.
 *
 * This component is responsible for building metadata maps
 * used in workflow persistence for step entities.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepMetadataBuilder")
class StepMetadataBuilderTest {

    @Mock
    private WorkflowExecution execution;

    @Mock
    private WorkflowPlan plan;

    private StepMetadataBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new StepMetadataBuilder();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // buildMetadata() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("buildMetadata()")
    class BuildMetadataTests {

        @Test
        @DisplayName("Should include basic step info")
        void shouldIncludeBasicStepInfo() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-123");
            when(execution.getMergeStatesSnapshot()).thenReturn(null);
            when(execution.getDecisionEvaluation(anyString())).thenReturn(null);

            StepExecutionResult result = new StepExecutionResult(
                    "step-1", NodeStatus.COMPLETED, "Success",
                    Map.of("data", "value"), 100L, null
            );

            Map<String, Object> metadata = builder.buildMetadata(
                    execution, "step-1", "graph-1", result, workflowRunId
            );

            assertNotNull(metadata);
            assertEquals(NodeStatus.COMPLETED.toWireValue(), metadata.get("statusValue"));
            assertEquals("Success", metadata.get("statusMessage"));
            assertEquals("step-1", metadata.get("stepEventId"));
            assertEquals("step-1", metadata.get("resultStepId"));
            assertEquals("graph-1", metadata.get("graphNodeId"));
        }

        @Test
        @DisplayName("Should include error info when present")
        void shouldIncludeErrorInfoWhenPresent() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-123");
            when(execution.getMergeStatesSnapshot()).thenReturn(null);
            when(execution.getDecisionEvaluation(anyString())).thenReturn(null);

            RuntimeException error = new RuntimeException("Connection failed");
            StepExecutionResult result = new StepExecutionResult(
                    "step-1", NodeStatus.FAILED, "Connection failed",
                    null, 100L, error
            );

            Map<String, Object> metadata = builder.buildMetadata(
                    execution, "step-1", "graph-1", result, workflowRunId
            );

            assertEquals("java.lang.RuntimeException", metadata.get("errorType"));
            assertEquals("Connection failed", metadata.get("errorMessage"));
        }

        @Test
        @DisplayName("Should include context info")
        void shouldIncludeContextInfo() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-123");
            when(execution.getMergeStatesSnapshot()).thenReturn(null);
            when(execution.getDecisionEvaluation(anyString())).thenReturn(null);

            StepExecutionResult result = new StepExecutionResult(
                    "step-1", NodeStatus.COMPLETED, "Success",
                    Map.of("data", "value"), 250L, null
            );

            Map<String, Object> metadata = builder.buildMetadata(
                    execution, "step-1", "graph-1", result, workflowRunId
            );

            assertEquals("tenant-1", metadata.get("tenantId"));
            assertEquals(250L, metadata.get("executionTimeMs"));
            assertNotNull(metadata.get("recordedAt"));
            assertEquals(workflowRunId.toString(), metadata.get("workflowRunId"));
            assertEquals("workflow-123", metadata.get("workflowId"));
        }

        @Test
        @DisplayName("Should include merge states snapshot when available")
        void shouldIncludeMergeStatesSnapshotWhenAvailable() {
            UUID workflowRunId = UUID.randomUUID();
            Map<String, Object> mergeSnapshot = Map.of("merge-1", Map.of("status", "waiting"));
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-123");
            when(execution.getMergeStatesSnapshot()).thenReturn(mergeSnapshot);
            when(execution.getDecisionEvaluation(anyString())).thenReturn(null);

            StepExecutionResult result = new StepExecutionResult(
                    "step-1", NodeStatus.COMPLETED, "Success",
                    Map.of("data", "value"), 100L, null
            );

            Map<String, Object> metadata = builder.buildMetadata(
                    execution, "step-1", "graph-1", result, workflowRunId
            );

            assertEquals(mergeSnapshot, metadata.get("mergeStates"));
        }

        @Test
        @DisplayName("Should not include empty merge states snapshot")
        void shouldNotIncludeEmptyMergeStatesSnapshot() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-123");
            when(execution.getMergeStatesSnapshot()).thenReturn(Map.of());
            when(execution.getDecisionEvaluation(anyString())).thenReturn(null);

            StepExecutionResult result = new StepExecutionResult(
                    "step-1", NodeStatus.COMPLETED, "Success",
                    Map.of("data", "value"), 100L, null
            );

            Map<String, Object> metadata = builder.buildMetadata(
                    execution, "step-1", "graph-1", result, workflowRunId
            );

            assertFalse(metadata.containsKey("mergeStates"));
        }

        @Test
        @DisplayName("Should include item context from output")
        void shouldIncludeItemContextFromOutput() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-123");
            when(execution.getMergeStatesSnapshot()).thenReturn(null);
            when(execution.getDecisionEvaluation(anyString())).thenReturn(null);

            Map<String, Object> output = new HashMap<>();
            output.put("itemId", "item-123");
            output.put("triggerId", "trigger-456");
            output.put("absoluteIndex", 5);
            output.put("tenantId", "item-tenant");

            StepExecutionResult result = new StepExecutionResult(
                    "step-1", NodeStatus.COMPLETED, "Success", output, 100L, null
            );

            Map<String, Object> metadata = builder.buildMetadata(
                    execution, "step-1", "graph-1", result, workflowRunId
            );

            assertEquals("item-123", metadata.get("itemId"));
            assertEquals("trigger-456", metadata.get("triggerId"));
            assertEquals(5, metadata.get("itemAbsoluteIndex"));
            assertEquals("item-tenant", metadata.get("itemTenantId"));
        }

        @Test
        @DisplayName("Should include tool info from output metadata")
        void shouldIncludeToolInfoFromOutputMetadata() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getTenantId()).thenReturn("tenant-1");
            when(plan.getId()).thenReturn("workflow-123");
            when(execution.getMergeStatesSnapshot()).thenReturn(null);
            when(execution.getDecisionEvaluation(anyString())).thenReturn(null);

            Map<String, Object> output = new HashMap<>();
            output.put("metadata", Map.of(
                    "iconSlug", "api-icon",
                    "toolName", "API Tool",
                    "apiName", "test-api"
            ));
            output.put("tool_id", "tool-uuid");

            StepExecutionResult result = new StepExecutionResult(
                    "step-1", NodeStatus.COMPLETED, "Success", output, 100L, null
            );

            Map<String, Object> metadata = builder.buildMetadata(
                    execution, "step-1", "graph-1", result, workflowRunId
            );

            assertEquals("api-icon", metadata.get("iconSlug"));
            assertEquals("API Tool", metadata.get("toolName"));
            assertEquals("test-api", metadata.get("apiName"));
            assertEquals("tool-uuid", metadata.get("toolId"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // findDecisionEvaluation() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("findDecisionEvaluation()")
    class FindDecisionEvaluationTests {

        @Test
        @DisplayName("Should find evaluation by stepId")
        void shouldFindEvaluationByStepId() {
            DecisionEvaluationInfo evaluation = createDecisionEvaluation("decision-1");
            when(execution.getDecisionEvaluation("step-1")).thenReturn(evaluation);

            DecisionEvaluationInfo found = builder.findDecisionEvaluation(
                    execution, "step-1", "graph-1", "result-1"
            );

            assertEquals(evaluation, found);
        }

        @Test
        @DisplayName("Should fallback to graphNodeId when stepId not found")
        void shouldFallbackToGraphNodeIdWhenStepIdNotFound() {
            DecisionEvaluationInfo evaluation = createDecisionEvaluation("decision-1");
            when(execution.getDecisionEvaluation("step-1")).thenReturn(null);
            when(execution.getDecisionEvaluation("graph-1")).thenReturn(evaluation);

            DecisionEvaluationInfo found = builder.findDecisionEvaluation(
                    execution, "step-1", "graph-1", "result-1"
            );

            assertEquals(evaluation, found);
        }

        @Test
        @DisplayName("Should fallback to resultStepId when graphNodeId not found")
        void shouldFallbackToResultStepIdWhenGraphNodeIdNotFound() {
            DecisionEvaluationInfo evaluation = createDecisionEvaluation("decision-1");
            when(execution.getDecisionEvaluation("step-1")).thenReturn(null);
            when(execution.getDecisionEvaluation("graph-1")).thenReturn(null);
            when(execution.getDecisionEvaluation("result-1")).thenReturn(evaluation);

            DecisionEvaluationInfo found = builder.findDecisionEvaluation(
                    execution, "step-1", "graph-1", "result-1"
            );

            assertEquals(evaluation, found);
        }

        @Test
        @DisplayName("Should return null when no evaluation found")
        void shouldReturnNullWhenNoEvaluationFound() {
            when(execution.getDecisionEvaluation(anyString())).thenReturn(null);

            DecisionEvaluationInfo found = builder.findDecisionEvaluation(
                    execution, "step-1", "graph-1", "result-1"
            );

            assertNull(found);
        }

        @Test
        @DisplayName("Should handle null graphNodeId and resultStepId")
        void shouldHandleNullGraphNodeIdAndResultStepId() {
            when(execution.getDecisionEvaluation("step-1")).thenReturn(null);

            DecisionEvaluationInfo found = builder.findDecisionEvaluation(
                    execution, "step-1", null, null
            );

            assertNull(found);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // convertDecisionEvaluationToMap() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("convertDecisionEvaluationToMap()")
    class ConvertDecisionEvaluationToMapTests {

        @Test
        @DisplayName("Should convert basic evaluation to map")
        void shouldConvertBasicEvaluationToMap() {
            DecisionEvaluationInfo evaluation = new DecisionEvaluationInfo(
                    "decision-1", "Check Status", "source-step",
                    "if", List.of(), null
            );

            Map<String, Object> map = builder.convertDecisionEvaluationToMap(evaluation);

            assertEquals("decision-1", map.get("decisionNodeId"));
            assertEquals("Check Status", map.get("decisionNodeLabel"));
            assertEquals("source-step", map.get("sourceStepId"));
            assertEquals("if", map.get("selectedBranch"));
        }

        @Test
        @DisplayName("Should convert conditions to list of maps")
        void shouldConvertConditionsToListOfMaps() {
            DecisionEvaluationInfo.ConditionEvaluation condition = new DecisionEvaluationInfo.ConditionEvaluation(
                    "if", "{{x}} > 10", "15 > 10", true, true, "if_branch", null
            );

            DecisionEvaluationInfo evaluation = new DecisionEvaluationInfo(
                    "decision-1", "Check Status", "source-step",
                    "if", List.of(condition), null
            );

            Map<String, Object> map = builder.convertDecisionEvaluationToMap(evaluation);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> conditions = (List<Map<String, Object>>) map.get("conditions");

            assertNotNull(conditions);
            assertEquals(1, conditions.size());

            Map<String, Object> condMap = conditions.get(0);
            assertEquals("if", condMap.get("type"));
            assertEquals("{{x}} > 10", condMap.get("originalExpression"));
            assertEquals("15 > 10", condMap.get("resolvedExpression"));
            assertEquals(true, condMap.get("result"));
            assertEquals(true, condMap.get("selected"));
            assertEquals("if_branch", condMap.get("targetBranch"));
        }

        @Test
        @DisplayName("Should include error message in condition when present")
        void shouldIncludeErrorMessageInConditionWhenPresent() {
            DecisionEvaluationInfo.ConditionEvaluation condition = new DecisionEvaluationInfo.ConditionEvaluation(
                    "if", "{{invalid}}", null, false, false, "if_branch", "Missing reference"
            );

            DecisionEvaluationInfo evaluation = new DecisionEvaluationInfo(
                    "decision-1", "Check Status", "source-step",
                    "else", List.of(condition), null
            );

            Map<String, Object> map = builder.convertDecisionEvaluationToMap(evaluation);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> conditions = (List<Map<String, Object>>) map.get("conditions");

            assertEquals("Missing reference", conditions.get(0).get("errorMessage"));
        }

        @Test
        @DisplayName("Should include context snapshot when present")
        void shouldIncludeContextSnapshotWhenPresent() {
            Map<String, Object> contextSnapshot = Map.of("x", 15, "y", "value");

            DecisionEvaluationInfo evaluation = new DecisionEvaluationInfo(
                    "decision-1", "Check Status", "source-step",
                    "if", List.of(), contextSnapshot
            );

            Map<String, Object> map = builder.convertDecisionEvaluationToMap(evaluation);

            assertEquals(contextSnapshot, map.get("contextSnapshot"));
        }

        @Test
        @DisplayName("Should not include empty context snapshot")
        void shouldNotIncludeEmptyContextSnapshot() {
            DecisionEvaluationInfo evaluation = new DecisionEvaluationInfo(
                    "decision-1", "Check Status", "source-step",
                    "if", List.of(), Map.of()
            );

            Map<String, Object> map = builder.convertDecisionEvaluationToMap(evaluation);

            assertFalse(map.containsKey("contextSnapshot"));
        }

        @Test
        @DisplayName("Should handle null conditions list")
        void shouldHandleNullConditionsList() {
            DecisionEvaluationInfo evaluation = new DecisionEvaluationInfo(
                    "decision-1", "Check Status", "source-step",
                    "else", null, null
            );

            Map<String, Object> map = builder.convertDecisionEvaluationToMap(evaluation);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> conditions = (List<Map<String, Object>>) map.get("conditions");

            assertNotNull(conditions);
            assertTrue(conditions.isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper methods
    // ═══════════════════════════════════════════════════════════════════════════

    private DecisionEvaluationInfo createDecisionEvaluation(String decisionNodeId) {
        return new DecisionEvaluationInfo(
                decisionNodeId, "Test Decision", "source-step",
                "if", List.of(), null
        );
    }
}
