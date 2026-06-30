package com.apimarketplace.orchestrator.services.triggers;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.execution.v2.nodes.WorkflowTriggerNodeSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the WorkflowTrigger runtime/persistence shape drift.
 *
 * Before the fix:
 * - Resolver emitted vestigial "data", "count", "status" keys that customTransform dropped,
 *   causing the runtime SpEL shape and the persisted DB shape to diverge.
 * - Dispatch service emitted "triggeredAt" (camelCase); resolver had to remap to "triggered_at".
 * - "parentStatistics" was emitted by the resolver but silently dropped by customTransform.
 * - Parent-output flattening only happened in customTransform (DB path), not at resolver
 *   time (SpEL path), so {{trigger.parent_key}} failed at runtime.
 *
 * After the fix:
 * - Resolver canonical shape: triggered_at (snake), triggered_by, parentWorkflowId, parentRunId,
 *   parentStatus, parentStatistics, result, + flattened parent keys.
 * - No "data", "count", or "status" in resolver output.
 * - customTransform is a pass-through of the same canonical keys → runtime = DB.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkflowTriggerResolver - canonical shape alignment")
class WorkflowTriggerResolverTest {

    @Mock
    private TriggerUserResolver triggerUserResolver;

    @InjectMocks
    private WorkflowTriggerResolver resolver;

    private static final String TENANT_ID = "tenant-abc";
    private static final String DISPLAY_NAME = "Alice";

    @BeforeEach
    void setUp() {
        when(triggerUserResolver.resolveDisplayName(TENANT_ID)).thenReturn(DISPLAY_NAME);
    }

    private Trigger workflowTrigger() {
        return new Trigger("trigger:parent-wf", "parent_workflow", "single", "workflow",
                Map.of(), null);
    }

    // ===================================================================
    // canHandle()
    // ===================================================================

    @Nested
    @DisplayName("canHandle()")
    class CanHandle {

        @Test
        @DisplayName("Handles 'workflow' regardless of case")
        void handlesWorkflowType() {
            assertThat(resolver.canHandle("workflow")).isTrue();
            assertThat(resolver.canHandle("Workflow")).isTrue();
            assertThat(resolver.canHandle("WORKFLOW")).isTrue();
        }

        @Test
        @DisplayName("Refuses other trigger types")
        void refusesOtherTypes() {
            assertThat(resolver.canHandle("schedule")).isFalse();
            assertThat(resolver.canHandle("webhook")).isFalse();
            assertThat(resolver.canHandle("manual")).isFalse();
        }
    }

    // ===================================================================
    // resolve() - canonical keys present
    // ===================================================================

    @Nested
    @DisplayName("resolve() - canonical keys")
    class CanonicalKeys {

        @Test
        @DisplayName("Emits triggered_at (snake_case) - regression: pre-fix dispatch sent triggeredAt camel")
        void emitsSnakeCaseTriggeredAt() {
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("triggered_at", "2026-05-01T12:00:00Z");

            Map<String, Object> output = resolver.resolve(workflowTrigger(), TENANT_ID, inputs);

            assertThat(output).containsKey("triggered_at");
            assertThat(output).doesNotContainKey("triggeredAt");
        }

        @Test
        @DisplayName("Prefers triggered_at from input over triggeredAt (camel) for chain continuity")
        void preferSnakeCaseOverCamel() {
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("triggered_at", "2026-05-01T12:00:00Z");
            inputs.put("triggeredAt", "2026-01-01T00:00:00Z");

            Map<String, Object> output = resolver.resolve(workflowTrigger(), TENANT_ID, inputs);

            assertThat(output.get("triggered_at")).isEqualTo("2026-05-01T12:00:00Z");
        }

        @Test
        @DisplayName("Falls back to camelCase triggeredAt if snake_case is missing")
        void fallsBackToCamelTriggeredAt() {
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("triggeredAt", "2026-05-01T12:00:00Z");

            Map<String, Object> output = resolver.resolve(workflowTrigger(), TENANT_ID, inputs);

            assertThat(output.get("triggered_at")).isEqualTo("2026-05-01T12:00:00Z");
        }

        @Test
        @DisplayName("Emits triggered_by from TriggerUserResolver")
        void emitsTriggeredBy() {
            Map<String, Object> output = resolver.resolve(workflowTrigger(), TENANT_ID, null);

            assertThat(output.get("triggered_by")).isEqualTo(DISPLAY_NAME);
        }

        @Test
        @DisplayName("Emits parentWorkflowId and parentRunId from resolvedInputs")
        void emitsParentMetadata() {
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("parentWorkflowId", "wf-parent-123");
            inputs.put("parentRunId", "run-456");

            Map<String, Object> output = resolver.resolve(workflowTrigger(), TENANT_ID, inputs);

            assertThat(output.get("parentWorkflowId")).isEqualTo("wf-parent-123");
            assertThat(output.get("parentRunId")).isEqualTo("run-456");
        }

        @Test
        @DisplayName("Emits parentStatus from resolvedInputs 'status' key")
        void emitsParentStatus() {
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("status", "COMPLETED");

            Map<String, Object> output = resolver.resolve(workflowTrigger(), TENANT_ID, inputs);

            assertThat(output.get("parentStatus")).isEqualTo("COMPLETED");
        }
    }

    // ===================================================================
    // resolve() - parentStatistics preserved (regression)
    // ===================================================================

    @Nested
    @DisplayName("resolve() - parentStatistics preserved through customTransform")
    class ParentStatistics {

        @Test
        @DisplayName("parentStatistics is present in resolver output - regression: pre-fix it was dropped by customTransform")
        void resolverEmitsParentStatistics() {
            Map<String, Object> stats = Map.of("completedSteps", 5, "failedSteps", 0, "totalSteps", 5);
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("statistics", stats);

            Map<String, Object> output = resolver.resolve(workflowTrigger(), TENANT_ID, inputs);

            assertThat(output).containsKey("parentStatistics");
            assertThat(output.get("parentStatistics")).isEqualTo(stats);
        }

        @Test
        @DisplayName("parentStatistics survives customTransform (runtime == DB shape)")
        void parentStatisticsSurvivesCustomTransform() {
            Map<String, Object> stats = Map.of("completedSteps", 3, "failedSteps", 1, "totalSteps", 4);
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("statistics", stats);

            Map<String, Object> resolverOutput = resolver.resolve(workflowTrigger(), TENANT_ID, inputs);

            WorkflowTriggerNodeSpec spec = new WorkflowTriggerNodeSpec();
            Map<String, Object> dbOutput = spec.customTransform(resolverOutput);

            assertThat(dbOutput).containsKey("parentStatistics");
            assertThat(dbOutput.get("parentStatistics")).isEqualTo(stats);
        }

        @Test
        @DisplayName("No parentStatistics key when input has no statistics")
        void noParentStatisticsWhenAbsent() {
            Map<String, Object> output = resolver.resolve(workflowTrigger(), TENANT_ID, Map.of());

            assertThat(output).doesNotContainKey("parentStatistics");
        }
    }

    // ===================================================================
    // resolve() - vestigial keys dropped (regression)
    // ===================================================================

    @Nested
    @DisplayName("resolve() - vestigial keys absent")
    class VestigialKeysDropped {

        @Test
        @DisplayName("'data' array not emitted - regression: pre-fix resolver emitted it but customTransform dropped it")
        void dataArrayNotEmitted() {
            Map<String, Object> output = resolver.resolve(workflowTrigger(), TENANT_ID, null);

            assertThat(output).doesNotContainKey("data");
        }

        @Test
        @DisplayName("'count' not emitted - regression: pre-fix resolver emitted it but customTransform dropped it")
        void countNotEmitted() {
            Map<String, Object> output = resolver.resolve(workflowTrigger(), TENANT_ID, null);

            assertThat(output).doesNotContainKey("count");
        }

        @Test
        @DisplayName("'status: success' not emitted - regression: pre-fix resolver emitted it but customTransform dropped it")
        void statusSuccessNotEmitted() {
            // 'status' was previously emitted as "success" in the resolver, confusingly
            // shadowing the parentStatus. After the fix it is absent unless it is a
            // flattened parent output key.
            Map<String, Object> inputs = Map.of("parentWorkflowId", "wf-1");
            Map<String, Object> output = resolver.resolve(workflowTrigger(), TENANT_ID, inputs);

            // No 'status' unless the parent result explicitly had a 'status' key
            assertThat(output).doesNotContainKey("status");
        }
    }

    // ===================================================================
    // resolve() - parent result flattened at root (for SpEL at runtime)
    // ===================================================================

    @Nested
    @DisplayName("resolve() - parent result flattened at root for SpEL")
    class ParentResultFlattened {

        @Test
        @DisplayName("Parent result keys are flattened to root level so SpEL can reference them without .result. prefix")
        void parentResultFlattenedToRoot() {
            Map<String, Object> parentResult = new HashMap<>();
            parentResult.put("email_sent", true);
            parentResult.put("recipient_count", 42);

            Map<String, Object> inputs = new HashMap<>();
            inputs.put("result", parentResult);

            Map<String, Object> output = resolver.resolve(workflowTrigger(), TENANT_ID, inputs);

            // Both the nested 'result' object and the flattened keys should be present
            assertThat(output).containsKey("result");
            assertThat(output).containsEntry("email_sent", true);
            assertThat(output).containsEntry("recipient_count", 42);
        }

        @Test
        @DisplayName("Flattened parent keys do NOT overwrite the resolver's own triggered_at")
        void flattenedKeysDoNotOverwriteTriggeredAt() {
            // If the parent workflow happened to output a key named "triggered_at",
            // the resolver's own triggered_at must win (putIfAbsent semantics).
            Map<String, Object> parentResult = new HashMap<>();
            parentResult.put("triggered_at", "should-not-overwrite");
            parentResult.put("custom_parent_key", "my-value");

            Map<String, Object> inputs = new HashMap<>();
            inputs.put("triggered_at", "2026-05-01T12:00:00Z");
            inputs.put("result", parentResult);

            Map<String, Object> output = resolver.resolve(workflowTrigger(), TENANT_ID, inputs);

            // The resolver's triggered_at must win over the parent result's triggered_at
            assertThat(output.get("triggered_at")).isEqualTo("2026-05-01T12:00:00Z");
            // But non-reserved parent keys should still be flattened
            assertThat(output).containsEntry("custom_parent_key", "my-value");
        }

        @Test
        @DisplayName("customTransform preserves flattened parent keys (runtime == DB shape)")
        void customTransformPreservesFlattenedParentKeys() {
            Map<String, Object> parentResult = new HashMap<>();
            parentResult.put("total_processed", 100);
            parentResult.put("error_rate", 0.01);

            Map<String, Object> inputs = new HashMap<>();
            inputs.put("result", parentResult);

            Map<String, Object> resolverOutput = resolver.resolve(workflowTrigger(), TENANT_ID, inputs);

            WorkflowTriggerNodeSpec spec = new WorkflowTriggerNodeSpec();
            Map<String, Object> dbOutput = spec.customTransform(resolverOutput);

            assertThat(dbOutput).containsEntry("total_processed", 100);
            assertThat(dbOutput).containsEntry("error_rate", 0.01);
            assertThat(dbOutput).containsKey("result");
        }
    }

    // ===================================================================
    // resolve() - null / empty inputs guard
    // ===================================================================

    @Nested
    @DisplayName("resolve() - null inputs handled gracefully")
    class NullInputs {

        @Test
        @DisplayName("Null resolvedInputs produces minimal valid output")
        void nullInputsProducesMinimalOutput() {
            Map<String, Object> output = resolver.resolve(workflowTrigger(), TENANT_ID, null);

            assertThat(output).containsKey("triggered_at");
            assertThat(output).containsKey("triggered_by");
            assertThat(output.get("result")).isInstanceOf(Map.class);
            assertThat(output).doesNotContainKey("data");
            assertThat(output).doesNotContainKey("count");
        }
    }
}
