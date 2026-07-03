package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EvalContextBuilder.
 * Verifies that evaluation contexts are built correctly for expression evaluation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EvalContextBuilder")
class EvalContextBuilderTest {

    @Mock
    private WorkflowPlan mockPlan;

    private ExecutionContext baseContext;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("user_id", 42);
        triggerData.put("status", "active");
        triggerData.put("email", "test@example.com");

        baseContext = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            triggerData,
            mockPlan
        );
    }

    // =========================================================================
    // buildStandardEvalContext() tests
    // =========================================================================

    @Nested
    @DisplayName("buildStandardEvalContext()")
    class BuildStandardEvalContextTests {

        @Test
        @DisplayName("Should include trigger data at top level")
        void shouldIncludeTriggerDataAtTopLevel() {
            Map<String, Object> result = EvalContextBuilder.buildStandardEvalContext(baseContext);

            assertEquals(42, result.get("user_id"));
            assertEquals("active", result.get("status"));
            assertEquals("test@example.com", result.get("email"));
        }

        @Test
        @DisplayName("Should include trigger data under 'trigger' key")
        void shouldIncludeTriggerDataUnderTriggerKey() {
            Map<String, Object> result = EvalContextBuilder.buildStandardEvalContext(baseContext);

            assertNotNull(result.get("trigger"));
            @SuppressWarnings("unchecked")
            Map<String, Object> trigger = (Map<String, Object>) result.get("trigger");
            assertEquals(42, trigger.get("user_id"));
        }

        @Test
        @DisplayName("Should include step outputs keyed by node ID")
        void shouldIncludeStepOutputsKeyedByNodeId() {
            NodeExecutionResult stepResult = NodeExecutionResult.success("mcp:step1",
                Map.of("data", "hello"));
            ExecutionContext contextWithStep = baseContext.withResult("mcp:step1", stepResult);

            Map<String, Object> result = EvalContextBuilder.buildStandardEvalContext(contextWithStep);

            assertNotNull(result.get("mcp:step1"));
        }

        @Test
        @DisplayName("Should extract trigger output fields to top level")
        void shouldExtractTriggerOutputFieldsToTopLevel() {
            // Simulate a trigger output wrapped in the standard structure
            NodeExecutionResult triggerResult = NodeExecutionResult.success("trigger:webhook",
                Map.of("payload", Map.of("name", "John"), "custom_field", "value123"));
            ExecutionContext contextWithTrigger = baseContext.withResult("trigger:webhook", triggerResult);

            Map<String, Object> result = EvalContextBuilder.buildStandardEvalContext(contextWithTrigger);

            // The trigger output fields should be extracted to top level
            assertEquals("value123", result.get("custom_field"));
        }

        @Test
        @DisplayName("Should exclude metadata keys from trigger output extraction")
        void shouldExcludeMetadataKeysFromTriggerOutputExtraction() {
            // Build trigger output that includes metadata keys
            Map<String, Object> triggerOutput = new HashMap<>();
            triggerOutput.put("user_name", "John");
            triggerOutput.put("trigger_id", "t123");
            triggerOutput.put("trigger_data", Map.of());
            triggerOutput.put("item_id", "i1");
            triggerOutput.put("item_index", 99);
            triggerOutput.put("httpstatus", Map.of("code", 200));
            triggerOutput.put("itemIndex", 99);
            triggerOutput.put("currentIteration", 1);
            triggerOutput.put("iteration", 1);

            Map<String, Object> wrappedOutput = Map.of("output", triggerOutput);

            // Create context with trigger step output manually
            ExecutionContext ctx = new ExecutionContext(
                "run-1", "workflow-run-1", "tenant-1",
                "item-1", 0,
                null, 0, 0,
                new HashMap<>(),
                Map.of("trigger:test", wrappedOutput),
                com.apimarketplace.orchestrator.execution.v2.state.ExecutionState.create(),
                mockPlan
            );

            Map<String, Object> result = EvalContextBuilder.buildStandardEvalContext(ctx);

            // user_name should be extracted to top level
            assertEquals("John", result.get("user_name"));

            // item_index and item_id are re-added by step 4 with the context values (0, "item-1"),
            // NOT the trigger output values (99, "i1")
            assertEquals(0, result.get("item_index"));
            assertEquals("item-1", result.get("item_id"));
        }

        @Test
        @DisplayName("Should exclude currentIteration from trigger output extraction")
        void shouldExcludeCurrentIterationFromTriggerOutputExtraction() {
            Map<String, Object> triggerOutput = new HashMap<>();
            triggerOutput.put("user_name", "John");
            triggerOutput.put("currentIteration", 5);
            triggerOutput.put("iteration", 5);

            NodeExecutionResult triggerResult = NodeExecutionResult.success("trigger:webhook", triggerOutput);
            ExecutionContext contextWithTrigger = baseContext.withResult("trigger:webhook", triggerResult);

            Map<String, Object> result = EvalContextBuilder.buildStandardEvalContext(contextWithTrigger);

            assertEquals("John", result.get("user_name"));
            assertFalse(result.containsKey("currentIteration"),
                "currentIteration metadata should not be extracted to top-level eval context");
            assertFalse(result.containsKey("iteration"),
                "iteration metadata should not be extracted to top-level eval context");
        }

        @Test
        @DisplayName("Should include item_index and item_id")
        void shouldIncludeItemContext() {
            Map<String, Object> result = EvalContextBuilder.buildStandardEvalContext(baseContext);

            assertEquals(0, result.get("item_index"));
            assertEquals("item-1", result.get("item_id"));
        }

        @Test
        @DisplayName("Should handle null trigger data gracefully")
        void shouldHandleNullTriggerDataGracefully() {
            ExecutionContext ctx = new ExecutionContext(
                "run-1", "workflow-run-1", "tenant-1",
                "item-1", 0,
                null, 0, 0,
                null,
                new HashMap<>(),
                com.apimarketplace.orchestrator.execution.v2.state.ExecutionState.create(),
                mockPlan
            );

            Map<String, Object> result = EvalContextBuilder.buildStandardEvalContext(ctx);

            assertNotNull(result);
            assertNull(result.get("trigger"));
            assertEquals(0, result.get("item_index"));
        }

        @Test
        @DisplayName("Should handle empty step outputs")
        void shouldHandleEmptyStepOutputs() {
            Map<String, Object> result = EvalContextBuilder.buildStandardEvalContext(baseContext);

            assertNotNull(result);
            // Should still have trigger data and item context
            assertEquals(42, result.get("user_id"));
            assertEquals(0, result.get("item_index"));
        }

        @Test
        @DisplayName("Should not extract non-trigger step outputs to top level")
        void shouldNotExtractNonTriggerStepOutputsToTopLevel() {
            // Add an mcp step output
            NodeExecutionResult mcpResult = NodeExecutionResult.success("mcp:api_call",
                Map.of("response_data", "some value"));
            ExecutionContext contextWithMcp = baseContext.withResult("mcp:api_call", mcpResult);

            Map<String, Object> result = EvalContextBuilder.buildStandardEvalContext(contextWithMcp);

            // The mcp:api_call should be in the context as a whole
            assertNotNull(result.get("mcp:api_call"));
            // But its inner fields should NOT be extracted to top level (only triggers are extracted)
            assertNull(result.get("response_data"));
        }

        @Test
        @DisplayName("Should handle multiple step outputs")
        void shouldHandleMultipleStepOutputs() {
            NodeExecutionResult step1 = NodeExecutionResult.success("mcp:step1",
                Map.of("data1", "value1"));
            NodeExecutionResult step2 = NodeExecutionResult.success("mcp:step2",
                Map.of("data2", "value2"));
            ExecutionContext ctx = baseContext
                .withResult("mcp:step1", step1)
                .withResult("mcp:step2", step2);

            Map<String, Object> result = EvalContextBuilder.buildStandardEvalContext(ctx);

            assertNotNull(result.get("mcp:step1"));
            assertNotNull(result.get("mcp:step2"));
        }
    }

    // =========================================================================
    // buildAggregateEvalContext() tests
    // =========================================================================

    @Nested
    @DisplayName("buildAggregateEvalContext()")
    class BuildAggregateEvalContextTests {

        @Test
        @DisplayName("Should include trigger data at top level")
        void shouldIncludeTriggerDataAtTopLevel() {
            Map<String, Object> result = EvalContextBuilder.buildAggregateEvalContext(baseContext);

            assertEquals(42, result.get("user_id"));
            assertEquals("active", result.get("status"));
        }

        @Test
        @DisplayName("Should include trigger data under 'trigger' key")
        void shouldIncludeTriggerDataUnderTriggerKey() {
            Map<String, Object> result = EvalContextBuilder.buildAggregateEvalContext(baseContext);

            assertNotNull(result.get("trigger"));
        }

        @Test
        @DisplayName("Should extract ALL step output fields to top level")
        void shouldExtractAllStepOutputFieldsToTopLevel() {
            NodeExecutionResult mcpResult = NodeExecutionResult.success("mcp:api_call",
                Map.of("response_data", "some value", "count", 5));
            ExecutionContext ctx = baseContext.withResult("mcp:api_call", mcpResult);

            Map<String, Object> result = EvalContextBuilder.buildAggregateEvalContext(ctx);

            // Unlike standard context, aggregate extracts from ALL steps
            assertEquals("some value", result.get("response_data"));
            assertEquals(5, result.get("count"));
        }

        @Test
        @DisplayName("Should extract trigger output fields without metadata filtering")
        void shouldExtractTriggerOutputFieldsWithoutMetadataFiltering() {
            // Build trigger output that includes metadata keys
            Map<String, Object> triggerOutput = new HashMap<>();
            triggerOutput.put("user_name", "John");
            triggerOutput.put("trigger_id", "t123"); // This IS extracted in aggregate mode

            Map<String, Object> wrappedOutput = Map.of("output", triggerOutput);

            ExecutionContext ctx = new ExecutionContext(
                "run-1", "workflow-run-1", "tenant-1",
                "item-1", 0,
                null, 0, 0,
                new HashMap<>(),
                Map.of("trigger:test", wrappedOutput),
                com.apimarketplace.orchestrator.execution.v2.state.ExecutionState.create(),
                mockPlan
            );

            Map<String, Object> result = EvalContextBuilder.buildAggregateEvalContext(ctx);

            // Aggregate does NOT filter metadata keys
            assertEquals("John", result.get("user_name"));
            assertEquals("t123", result.get("trigger_id"));
        }

        @Test
        @DisplayName("Should include item context")
        void shouldIncludeItemContext() {
            Map<String, Object> result = EvalContextBuilder.buildAggregateEvalContext(baseContext);

            assertEquals(0, result.get("item_index"));
            assertEquals("item-1", result.get("item_id"));
        }

        @Test
        @DisplayName("Should handle null trigger data")
        void shouldHandleNullTriggerData() {
            ExecutionContext ctx = new ExecutionContext(
                "run-1", "workflow-run-1", "tenant-1",
                "item-1", 0,
                null, 0, 0,
                null,
                new HashMap<>(),
                com.apimarketplace.orchestrator.execution.v2.state.ExecutionState.create(),
                mockPlan
            );

            Map<String, Object> result = EvalContextBuilder.buildAggregateEvalContext(ctx);

            assertNotNull(result);
            assertEquals(0, result.get("item_index"));
        }

        @Test
        @DisplayName("Should handle step outputs without wrapped format")
        void shouldHandleStepOutputsWithoutWrappedFormat() {
            // Step output that is NOT a map (should not cause errors)
            ExecutionContext ctx = new ExecutionContext(
                "run-1", "workflow-run-1", "tenant-1",
                "item-1", 0,
                null, 0, 0,
                new HashMap<>(),
                Map.of("simple_key", "simple_value"),
                com.apimarketplace.orchestrator.execution.v2.state.ExecutionState.create(),
                mockPlan
            );

            Map<String, Object> result = EvalContextBuilder.buildAggregateEvalContext(ctx);

            assertNotNull(result);
            assertEquals("simple_value", result.get("simple_key"));
        }
    }

    // =========================================================================
    // getExcludedKeys() tests
    // =========================================================================

    @Nested
    @DisplayName("getExcludedKeys()")
    class GetExcludedKeysTests {

        @Test
        @DisplayName("Should return the complete set of excluded metadata keys")
        void shouldReturnCompleteSetOfExcludedKeys() {
            Set<String> excluded = EvalContextBuilder.getExcludedKeys();

            assertTrue(excluded.contains("trigger_id"));
            assertTrue(excluded.contains("trigger_data"));
            assertTrue(excluded.contains("item_id"));
            assertTrue(excluded.contains("item_index"));
            assertTrue(excluded.contains("httpstatus"));
            assertTrue(excluded.contains("itemIndex"));
            assertTrue(excluded.contains("currentIteration"));
            assertTrue(excluded.contains("iteration"));
            assertEquals(8, excluded.size());
        }

        @Test
        @DisplayName("Should return unmodifiable set")
        void shouldReturnUnmodifiableSet() {
            Set<String> excluded = EvalContextBuilder.getExcludedKeys();

            assertThrows(UnsupportedOperationException.class, () -> excluded.add("new_key"));
        }
    }

    // =========================================================================
    // Comparison tests (Standard vs Aggregate)
    // =========================================================================

    @Nested
    @DisplayName("Standard vs Aggregate comparison")
    class ComparisonTests {

        @Test
        @DisplayName("Standard should NOT extract mcp output fields to top level")
        void standardShouldNotExtractMcpOutputFieldsToTopLevel() {
            NodeExecutionResult mcpResult = NodeExecutionResult.success("mcp:api_call",
                Map.of("response_data", "value"));
            ExecutionContext ctx = baseContext.withResult("mcp:api_call", mcpResult);

            Map<String, Object> standardResult = EvalContextBuilder.buildStandardEvalContext(ctx);

            // Standard context does NOT extract mcp output fields
            assertNull(standardResult.get("response_data"));
        }

        @Test
        @DisplayName("Aggregate SHOULD extract mcp output fields to top level")
        void aggregateShouldExtractMcpOutputFieldsToTopLevel() {
            NodeExecutionResult mcpResult = NodeExecutionResult.success("mcp:api_call",
                Map.of("response_data", "value"));
            ExecutionContext ctx = baseContext.withResult("mcp:api_call", mcpResult);

            Map<String, Object> aggregateResult = EvalContextBuilder.buildAggregateEvalContext(ctx);

            // Aggregate context DOES extract all output fields
            assertEquals("value", aggregateResult.get("response_data"));
        }

        @Test
        @DisplayName("Both should include trigger data and item context")
        void bothShouldIncludeTriggerDataAndItemContext() {
            Map<String, Object> standardResult = EvalContextBuilder.buildStandardEvalContext(baseContext);
            Map<String, Object> aggregateResult = EvalContextBuilder.buildAggregateEvalContext(baseContext);

            // Both include trigger data
            assertEquals(standardResult.get("user_id"), aggregateResult.get("user_id"));
            assertEquals(standardResult.get("status"), aggregateResult.get("status"));

            // Both include item context
            assertEquals(standardResult.get("item_index"), aggregateResult.get("item_index"));
            assertEquals(standardResult.get("item_id"), aggregateResult.get("item_id"));

            // Both include trigger under "trigger" key
            assertNotNull(standardResult.get("trigger"));
            assertNotNull(aggregateResult.get("trigger"));
        }

        @Test
        @DisplayName("Standard should filter metadata keys, Aggregate should not")
        void standardShouldFilterMetadataKeysAggregateNot() {
            // Build context with trigger output containing both user data and metadata
            Map<String, Object> triggerOutput = new HashMap<>();
            triggerOutput.put("user_name", "Alice");
            triggerOutput.put("httpstatus", Map.of("code", 200));
            triggerOutput.put("currentIteration", 3);

            Map<String, Object> wrappedOutput = Map.of("output", triggerOutput);

            ExecutionContext ctx = new ExecutionContext(
                "run-1", "workflow-run-1", "tenant-1",
                "item-1", 0,
                null, 0, 0,
                new HashMap<>(),
                Map.of("trigger:test", wrappedOutput),
                com.apimarketplace.orchestrator.execution.v2.state.ExecutionState.create(),
                mockPlan
            );

            Map<String, Object> standardResult = EvalContextBuilder.buildStandardEvalContext(ctx);
            Map<String, Object> aggregateResult = EvalContextBuilder.buildAggregateEvalContext(ctx);

            // Both extract user_name
            assertEquals("Alice", standardResult.get("user_name"));
            assertEquals("Alice", aggregateResult.get("user_name"));

            // Standard filters httpstatus and currentIteration from top-level extraction
            assertFalse(standardResult.containsKey("currentIteration"),
                "Standard context should filter currentIteration from trigger output");

            // Aggregate extracts them to top level
            assertEquals(3, aggregateResult.get("currentIteration"));
        }
    }

    // =========================================================================
    // Workflow variables bundle ("vars" global data) tests
    // =========================================================================

    @Nested
    @DisplayName("Workflow variables bundle (vars)")
    class WorkflowVariablesBundleTests {

        private final Map<String, Object> bundle =
            Map.of("api_url", "https://api.example.com", "n", 5);

        @Test
        @DisplayName("Standard context should expose the vars global data under the 'vars' key")
        void standardContextShouldExposeVarsBundle() {
            ExecutionContext ctx = baseContext.withGlobalData("vars", bundle);

            Map<String, Object> result = EvalContextBuilder.buildStandardEvalContext(ctx);

            assertEquals(bundle, result.get("vars"));
        }

        @Test
        @DisplayName("Aggregate context should expose the vars global data under the 'vars' key")
        void aggregateContextShouldExposeVarsBundle() {
            ExecutionContext ctx = baseContext.withGlobalData("vars", bundle);

            Map<String, Object> result = EvalContextBuilder.buildAggregateEvalContext(ctx);

            assertEquals(bundle, result.get("vars"));
        }

        @Test
        @DisplayName("Standard context should have no 'vars' key when no bundle was attached")
        void standardContextShouldOmitVarsWhenAbsent() {
            Map<String, Object> result = EvalContextBuilder.buildStandardEvalContext(baseContext);

            assertFalse(result.containsKey("vars"));
        }

        @Test
        @DisplayName("Aggregate context should have no 'vars' key when no bundle was attached")
        void aggregateContextShouldOmitVarsWhenAbsent() {
            Map<String, Object> result = EvalContextBuilder.buildAggregateEvalContext(baseContext);

            assertFalse(result.containsKey("vars"));
        }
    }
}
